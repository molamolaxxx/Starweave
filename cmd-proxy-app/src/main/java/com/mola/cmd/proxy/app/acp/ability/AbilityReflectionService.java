package com.mola.cmd.proxy.app.acp.ability;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.configui.ProviderModelDiscoveryService;
import com.mola.cmd.proxy.app.acp.common.PathUtils;
import com.mola.cmd.proxy.app.acp.acpclient.McpConfigLoader;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProviderRouter;
import com.mola.cmd.proxy.app.acp.memory.MemoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * 能力反思服务，独立于记忆系统。
 * <p>
 * 结合显式工作区 skills、记忆概要和 MCP 工具列表，排除用户级通用 skills，
 * 让 LLM 反思自身能力，将结果持久化到 .cmd-proxy/ability/{sanitized-robotName}/ability.md。
 * <p>
 * 触发时机：AcpClient 初始化时调用 {@link #submitReflection()}，内部判断是否需要执行：
 * <ul>
 *   <li>ability.md 不存在 → 首次反思</li>
 *   <li>skills 文件内容变动</li>
 *   <li>MCP 工具配置变动</li>
 *   <li>上次反思之后发生过 auto dream</li>
 * </ul>
 * 变动判断基于快照文件 snapshot.json，记录上次反思时的各项指纹。
 */
public class AbilityReflectionService {

    private static final Logger logger = LoggerFactory.getLogger(AbilityReflectionService.class);
    private static final String BASE_DIR = com.mola.cmd.proxy.app.utils.CmdProxyHome.pathOf("ability");
    private static final String ABILITY_FILE = "ability.md";
    private static final int POLICY_VERSION = 2;
    private static final String SNAPSHOT_FILE = "snapshot.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String robotName;
    private final String workspacePath;
    private final AcpRobotParam robotParam;
    private final int timeoutSeconds;
    private final List<Path> mcpConfigPaths;
    /** 记忆管理器，可为 null（未启用记忆时） */
    private final MemoryManager memoryManager;

    private final ExecutorService queue = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(2),
            r -> {
                Thread t = new Thread(r, "ability-reflection-queue");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy()
    );

    public AbilityReflectionService(String robotName, String workspacePath,
                                    AcpRobotParam robotParam,
                                    int timeoutSeconds,
                                    List<Path> mcpConfigPaths,
                                    MemoryManager memoryManager) {
        this.robotName = robotName;
        this.workspacePath = workspacePath;
        this.robotParam = robotParam;
        this.timeoutSeconds = timeoutSeconds;
        this.mcpConfigPaths = mcpConfigPaths != null ? mcpConfigPaths : Collections.emptyList();
        this.memoryManager = memoryManager;
    }

    // ==================== 触发入口 ====================

    /**
     * 提交能力反思任务。内部判断是否需要执行：
     * <ol>
     *   <li>ability.md 不存在 → 首次反思</li>
     *   <li>skills 内容指纹变动</li>
     *   <li>MCP 工具列表变动</li>
     *   <li>上次反思之后发生过 auto dream</li>
     * </ol>
     * 不满足任何条件时静默跳过。
     */
    public void submitReflection() {
        try {
            queue.submit(this::checkAndReflect);
        } catch (RejectedExecutionException e) {
            logger.warn("能力反思队列已满，跳过本次反思");
        }
    }

    void checkAndReflect() {
        try {
            Path abilityFile = getAbilityFilePath();
            if (!Files.exists(abilityFile)) {
                logger.info("ability.md 不存在，触发首次能力反思, robotName={}", robotName);
                doReflection();
                return;
            }

            // 加载上次快照
            Snapshot lastSnapshot = loadSnapshot();
            if (lastSnapshot == null || lastSnapshot.policyVersion != POLICY_VERSION) {
                logger.info("快照缺失或提取策略已更新，触发能力反思, robotName={}", robotName);
                doReflection();
                return;
            }

            // 构建当前指纹
            String currentSkillsHash = computeSkillsHash();
            List<String> currentMcpServers = loadMcpServerNames();
            String currentMcpHash = computeHash(String.join(",", currentMcpServers));
            String currentDreamTime = getLastDreamTime();

            boolean skillsChanged = !currentSkillsHash.equals(lastSnapshot.skillsHash);
            boolean profileChanged = !computeProfileHash().equals(lastSnapshot.profileHash);
            boolean mcpChanged = !currentMcpHash.equals(lastSnapshot.mcpHash);
            boolean dreamOccurred = currentDreamTime != null
                    && !currentDreamTime.equals(lastSnapshot.lastDreamTime);

            if (skillsChanged || mcpChanged || dreamOccurred || profileChanged) {
                logger.info("检测到变动，触发能力反思, robotName={}, skills={}, mcp={}, dream={}, profile={}",
                        robotName, skillsChanged, mcpChanged, dreamOccurred, profileChanged);
                doReflection();
            } else {
                logger.debug("无变动，跳过能力反思, robotName={}", robotName);
            }
        } catch (Exception e) {
            logger.error("能力反思检查失败, robotName={}", robotName, e);
        }
    }

    // ==================== 核心反思逻辑 ====================

    private void doReflection() {
        logger.info("开始能力反思, robotName={}, workspacePath={}", robotName, workspacePath);
        try {
            String smallModel = resolveSmallModel();
            if (smallModel == null || smallModel.trim().isEmpty()) {
                logger.info("未找到可用小模型，保留现有能力描述并跳过提取, robotName={}", robotName);
                return;
            }
            Map<String, String> workspaceSkills = loadWorkspaceSkills();
            List<String> mcpServerNames = loadMcpServerNames();
            String memorySummary = "";
            if (memoryManager != null) {
                try {
                    memorySummary = memoryManager.buildMemorySummary(workspacePath);
                } catch (Exception e) {
                    logger.warn("获取记忆概要失败，跳过记忆部分", e);
                }
            }

            String prompt = AbilityReflectionPromptTemplate.build(mcpServerNames, memorySummary, workspaceSkills, robotParam.getSignature());
            String response = runReflection(prompt, smallModel);

            if (response != null && !response.trim().isEmpty()) {
                writeAbilityFile(stripPreamble(response.trim()));
                saveSnapshot(mcpServerNames, workspaceSkills);
                logger.info("能力反思完成, robotName={}", robotName);
            } else {
                logger.warn("能力反思子 Client 返回为空, robotName={}", robotName);
            }
        } catch (Exception e) {
            logger.error("能力反思失败, robotName={}", robotName, e);
        }
    }

    String resolveSmallModel() {
        return ProviderModelDiscoveryService.findSmallModel(robotParam);
    }

    String runReflection(String prompt, String smallModel) throws IOException {
        String groupId = "ability_reflection__" + robotName.hashCode();
        try (AbilityReflectionAcpClient client = new AbilityReflectionAcpClient(
                workspacePath, groupId, timeoutSeconds, robotParam, smallModel)) {
            logger.info("能力提取使用小模型: {}, robotName={}", smallModel, robotName);
            client.start();
            return client.sendPromptSync(prompt);
        }
    }

    // ==================== 快照管理 ====================

    /**
     * 快照模型，记录上次反思时的各项指纹。
     */
    private static class Snapshot {
        int policyVersion;
        String profileHash;
        String skillsHash;
        String mcpHash;
        String lastDreamTime;
        String reflectionTime;
    }

    private Snapshot loadSnapshot() {
        Path path = getProjectDir().resolve(SNAPSHOT_FILE);
        if (!Files.exists(path)) return null;
        try {
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            return GSON.fromJson(content, Snapshot.class);
        } catch (Exception e) {
            logger.warn("加载快照失败: {}", path, e);
            return null;
        }
    }

    private void saveSnapshot(List<String> mcpServerNames, Map<String, String> workspaceSkills) {
        Snapshot snapshot = new Snapshot();
        snapshot.policyVersion = POLICY_VERSION;
        snapshot.profileHash = computeProfileHash();
        snapshot.skillsHash = computeHash(GSON.toJson(workspaceSkills));
        snapshot.mcpHash = computeHash(String.join(",", mcpServerNames));
        snapshot.lastDreamTime = getLastDreamTime();
        snapshot.reflectionTime = java.time.ZonedDateTime.now()
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        Path path = getProjectDir().resolve(SNAPSHOT_FILE);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, GSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.error("保存快照失败: {}", path, e);
        }
    }

    // ==================== 指纹计算 ====================

    private Map<String, String> loadWorkspaceSkills() throws IOException {
        return WorkspaceSkillLoader.load(AgentProviderRouter.getInstance()
                .resolve(robotParam.getAgentProvider()), workspacePath, robotParam);
    }

    private String computeSkillsHash() throws IOException {
        return computeHash(GSON.toJson(loadWorkspaceSkills()));
    }

    private String computeProfileHash() {
        return computeHash(robotName + "\n" + workspacePath + "\n" + robotParam.getSignature());
    }

    /**
     * 获取最近一次 auto dream 的时间。
     * 通过读取 memory 系统的 DREAM_STATE.json 获取。
     */
    private String getLastDreamTime() {
        if (memoryManager == null) return null;
        try {
            // 通过 MemoryManager 暴露的 DreamState 获取
            return memoryManager.getLastDreamTime(workspacePath);
        } catch (Exception e) {
            logger.warn("获取 lastDreamTime 失败", e);
            return null;
        }
    }

    // ==================== MCP 配置解析 ====================

    private List<String> loadMcpServerNames() {
        // 与 ACP session/new 使用同一解析器，统一支持 JSON 和 Codex TOML 配置。
        List<String> names = new ArrayList<>(McpConfigLoader.loadServerNames(
                mcpConfigPaths, workspacePath));
        Collections.sort(names);
        return names;
    }

    // ==================== 文件与路径工具 ====================

    /**
     * 裁掉 LLM 输出中第一个 Markdown 标题（# 开头的行）之前的前导文字。
     */
    private String stripPreamble(String text) {
        if (text.startsWith("#")) {
            return text;
        }
        int idx = text.indexOf("\n#");
        if (idx >= 0 && idx < text.length() - 2) {
            return text.substring(idx + 1);
        }
        // 如果整个文本以 # 开头，无需裁剪
        return text;
    }

    private void writeAbilityFile(String content) {
        Path filePath = getAbilityFilePath();
        try {
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));
            logger.info("ability.md 已写入: {}", filePath);
        } catch (IOException e) {
            logger.error("写入 ability.md 失败", e);
        }
    }

    private Path getAbilityFilePath() {
        return getProjectDir().resolve(ABILITY_FILE);
    }

    Path getProjectDir() {
        return Paths.get(BASE_DIR, PathUtils.sanitizePath(robotName));
    }

    private String computeHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }
}
