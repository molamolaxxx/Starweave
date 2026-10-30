package com.mola.cmd.proxy.app.acp.memory;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProviderRouter;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryAction;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryIndex;
import com.mola.cmd.proxy.app.acp.memory.prompt.MemoryPromptTemplate;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 记忆提取器，封装子 Client 的调用和结果解析。
 * <p>
 * 所有提取请求（增量/全量）统一提交到内部单线程队列串行执行，保证：
 * <ul>
 *   <li>同一时刻只有一个提取任务在跑，天然避免索引并发写入</li>
 *   <li>调用方全部异步，不阻塞主对话流</li>
 *   <li>shutdown 时队列会执行完已提交的任务，不会丢失 close 时的提取</li>
 * </ul>
 */
public class MemoryExtractor {

    private static final Logger logger = LoggerFactory.getLogger(MemoryExtractor.class);

    private final MemoryConfig config;
    private final MemoryFileStore fileStore;
    private final AcpRobotParam robotParam;
    private final String executionWorkDir;
    private final MemoryScopeLockRegistry locks;
    private final MemoryClientFactory clientFactory;

    /** 每个主 ACP session 对应一个可复用的专属 Memory session。 */
    private final ConcurrentMap<String, MemoryExtractionClient> sessionClients =
            new ConcurrentHashMap<>();

    /** 已成功提取到的历史消息数量，按主 ACP session 隔离。 */
    private final ConcurrentMap<String, Integer> extractedSizes =
            new ConcurrentHashMap<>();

    /** Lifecycle requests live outside the lossy bounded extraction queue. */
    private final java.util.Set<String> pendingSleeps = ConcurrentHashMap.newKeySet();

    /** 单线程提取队列，串行执行所有提取任务 */
    private final ThreadPoolExecutor extractQueue = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(16),  // 有界队列，防止堆积过多
            r -> {
                Thread t = new Thread(r, "memory-extract-queue");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy()  // 队列满时丢弃最旧的（增量提取可丢，全量兜底）
    ) {
        @Override
        protected void afterExecute(Runnable task, Throwable failure) {
            super.afterExecute(task, failure);
            // Finish admitted extraction work before releasing any requested runtime.
            if (getQueue().isEmpty()) drainSleepRequests();
        }
    };

    public MemoryExtractor(MemoryConfig config, MemoryFileStore fileStore, AcpRobotParam robotParam) {
        this(config, fileStore, robotParam, null, new MemoryScopeLockRegistry());
    }

    public MemoryExtractor(MemoryConfig config, MemoryFileStore fileStore,
                           AcpRobotParam robotParam,
                           MemoryScopeLockRegistry locks) {
        this(config, fileStore, robotParam, null, locks);
    }

    public MemoryExtractor(MemoryConfig config, MemoryFileStore fileStore,
                           AcpRobotParam robotParam, String executionWorkDir,
                           MemoryScopeLockRegistry locks) {
        this(config, fileStore, robotParam, executionWorkDir, locks,
                MemoryExtractor::createClient);
    }

    MemoryExtractor(MemoryConfig config, MemoryFileStore fileStore,
                    AcpRobotParam robotParam, MemoryScopeLockRegistry locks,
                    MemoryClientFactory clientFactory) {
        this(config, fileStore, robotParam, null, locks, clientFactory);
    }

    MemoryExtractor(MemoryConfig config, MemoryFileStore fileStore,
                    AcpRobotParam robotParam, String executionWorkDir,
                    MemoryScopeLockRegistry locks,
                    MemoryClientFactory clientFactory) {
        this.config = config;
        this.fileStore = fileStore;
        this.robotParam = robotParam;
        this.executionWorkDir = executionWorkDir;
        this.locks = locks;
        this.clientFactory = clientFactory;
    }

    /**
     * 异步提交增量提取任务。
     * 只分析上次提取之后的新对话，每 N 轮触发时使用。
     */
    public void submitExtract(String sourceSessionId, String workspacePath,
                              List<ContextMessage> history) {
        if (history == null || history.isEmpty()) return;
        String sessionKey = requireSessionKey(sourceSessionId);
        // 快照 history，避免异步执行时原列表被修改
        List<ContextMessage> snapshot = new ArrayList<>(history);
        try {
            extractQueue.submit(() -> doIncrementalExtract(
                    sessionKey, workspacePath, snapshot));
        } catch (RejectedExecutionException e) {
            logger.warn("提取队列已满或已关闭，跳过本次增量提取");
        }
    }

    public void sleepSession(String sourceSessionId) {
        String sessionKey = requireSessionKey(sourceSessionId);
        if (extractQueue.isShutdown()) return;
        pendingSleeps.add(sessionKey);
        // Wake an idle worker without evicting extraction work if the queue is full.
        extractQueue.prestartCoreThread();
        extractQueue.getQueue().offer(() -> { });
    }

    private void drainSleepRequests() {
        for (String sessionKey : pendingSleeps) {
            if (!pendingSleeps.remove(sessionKey)) continue;
            MemoryExtractionClient client = sessionClients.get(sessionKey);
            if (client == null) continue;
            try {
                client.sleep();
            } catch (Exception failure) {
                logger.warn("后台 Memory session 休眠失败, sourceSessionId={}",
                        sessionKey, failure);
                closeSessionClient(sessionKey);
            }
        }
    }

    /**
     * session/load 成功后登记已加载历史的基线。与提取任务使用同一 FIFO 队列，
     * 保证随后提交的增量快照只读取恢复之后的新消息。
     */
    public void resumeSession(String sourceSessionId, int historySize) {
        String sessionKey = requireSessionKey(sourceSessionId);
        int baseline = Math.max(0, historySize);
        try {
            extractQueue.submit(() -> {
                Integer current = extractedSizes.get(sessionKey);
                if (current == null || current < baseline) {
                    extractedSizes.put(sessionKey, baseline);
                }
                logger.info("恢复记忆提取游标, sessionId={}, historySize={}",
                        sessionKey, baseline);
            });
        } catch (RejectedExecutionException e) {
            logger.warn("提取队列已满或已关闭，无法登记恢复会话游标, sessionId={}",
                    sessionKey);
        }
    }

    /**
     * 对仍将继续使用的主 session 执行启动补偿。与终态提取不同，成功后保留
     * Memory client 与游标，使后续增量请求能够命中同一 Memory session。
     */
    public void submitRecoverActive(String sourceSessionId, String workspacePath,
                                    List<ContextMessage> history,
                                    Runnable onSuccess,
                                    Consumer<Throwable> onFailure) {
        if (history == null || history.isEmpty()) {
            resumeSession(sourceSessionId, 0);
            if (onSuccess != null) onSuccess.run();
            return;
        }
        String sessionKey = requireSessionKey(sourceSessionId);
        List<ContextMessage> snapshot = new ArrayList<>(history);
        try {
            extractQueue.submit(() -> {
                try {
                    if (!doActiveRecovery(sessionKey, workspacePath, snapshot)) {
                        throw new IllegalStateException("活动会话记忆恢复失败");
                    }
                    if (onSuccess != null) onSuccess.run();
                } catch (Exception error) {
                    if (onFailure != null) onFailure.accept(error);
                }
            });
        } catch (RejectedExecutionException e) {
            logger.warn("提取队列已满或已关闭，跳过活动会话记忆恢复");
            if (onFailure != null) onFailure.accept(e);
        }
    }

    /**
     * 异步提交会话结束提取任务。
     * 只分析最后一次成功增量提取后的尾部；没有可靠游标的恢复场景自动全量分析。
     */
    public void submitExtractFull(String sourceSessionId, String workspacePath,
                                  List<ContextMessage> history) {
        submitExtractFull(sourceSessionId, workspacePath, history, null, null);
    }

    public void submitExtractFull(String sourceSessionId, String workspacePath,
                                  List<ContextMessage> history,
                                  Runnable onSuccess,
                                  Consumer<Throwable> onFailure) {
        if (history == null || history.isEmpty()) return;
        String sessionKey = requireSessionKey(sourceSessionId);
        List<ContextMessage> snapshot = new ArrayList<>(history);
        try {
            extractQueue.submit(() -> {
                try {
                    if (!doFinalExtract(sessionKey, workspacePath, snapshot)) {
                        throw new IllegalStateException("记忆提取执行失败");
                    }
                    if (onSuccess != null) onSuccess.run();
                } catch (Exception error) {
                    if (onFailure != null) onFailure.accept(error);
                }
            });
        } catch (RejectedExecutionException e) {
            logger.warn("提取队列已满或已关闭，跳过本次全量提取");
            if (onFailure != null) onFailure.accept(e);
        }
    }

    private void doIncrementalExtract(String sourceSessionId, String workspacePath,
                                      List<ContextMessage> history) {
        int lastSize = extractedSize(sourceSessionId, history.size());
        if (lastSize >= history.size()) {
            logger.info("无新对话内容，跳过增量提取");
            return;
        }
        List<ContextMessage> toExtract = history.subList(lastSize, history.size());
        logger.info("增量提取, 新消息数={}, 总消息数={}", toExtract.size(), history.size());
        if (doExtract(sourceSessionId, workspacePath, toExtract)) {
            extractedSizes.put(sourceSessionId, history.size());
        }
    }

    private boolean doFinalExtract(String sourceSessionId, String workspacePath,
                                   List<ContextMessage> history) {
        int lastSize = extractedSize(sourceSessionId, history.size());
        List<ContextMessage> tail = history.subList(lastSize, history.size());
        logger.info("会话结束尾部提取, 新消息数={}, 总消息数={}",
                tail.size(), history.size());
        boolean success = tail.isEmpty()
                || doExtract(sourceSessionId, workspacePath, tail);
        if (success && !tail.isEmpty()) {
            extractedSizes.put(sourceSessionId, history.size());
        }
        // A failed restore retained the sleeping client for retry. Preserve its cursor
        // even for a final extraction; ordinary failures already discarded the client.
        if (success || !sessionClients.containsKey(sourceSessionId)) {
            closeSessionClient(sourceSessionId);
            extractedSizes.remove(sourceSessionId);
        }
        return success;
    }

    private boolean doActiveRecovery(String sourceSessionId, String workspacePath,
                                     List<ContextMessage> history) {
        int lastSize = extractedSize(sourceSessionId, history.size());
        List<ContextMessage> tail = history.subList(lastSize, history.size());
        logger.info("活动恢复记忆提取, 新消息数={}, 总消息数={}",
                tail.size(), history.size());
        boolean success = tail.isEmpty()
                || doExtract(sourceSessionId, workspacePath, tail);
        if (success) {
            extractedSizes.put(sourceSessionId, history.size());
        }
        return success;
    }

    private int extractedSize(String sourceSessionId, int historySize) {
        int lastSize = extractedSizes.getOrDefault(sourceSessionId, 0);
        if (lastSize <= historySize) {
            return lastSize;
        }
        logger.warn("记忆提取历史发生回退，重置游标, sessionId={}, extracted={}, current={}",
                sourceSessionId, lastSize, historySize);
        extractedSizes.remove(sourceSessionId);
        closeSessionClient(sourceSessionId);
        return 0;
    }

    private boolean doExtract(String sourceSessionId, String workspacePath,
                              List<ContextMessage> history) {
        String historyText = serializeHistory(history);
        MemoryIndex existingIndex;
        ReentrantLock snapshotLock = locks.lockFor(fileStore, workspacePath);
        snapshotLock.lock();
        try {
            existingIndex = fileStore.loadIndex(workspacePath);
        } finally {
            snapshotLock.unlock();
        }
        List<String> availableSkills = scanAvailableSkills(workspacePath);
        String prompt = MemoryPromptTemplate.build(historyText, existingIndex, availableSkills);

        try {
            MemoryExtractionClient client = getOrCreateClient(
                    sourceSessionId, workspacePath);
            String response = client.sendPromptSync(prompt);
            logger.info("记忆提取子 Client 返回, 长度={}", response.length());

            List<MemoryAction> actions = parseActions(response);
            if (actions.isEmpty()) {
                logger.info("无需保存的记忆");
                return true;
            }

            ReentrantLock lock = locks.lockFor(fileStore, workspacePath);
            lock.lock();
            try {
                // LLM 调用期间存储可能已由其他 manager 更新，应用前必须重载。
                MemoryIndex currentIndex = fileStore.loadIndex(workspacePath);
                fileStore.applyActions(workspacePath, actions, currentIndex,
                        config.getMaxEntriesPerProject());
            } finally {
                lock.unlock();
            }
            logger.info("记忆提取完成, 操作数={}", actions.size());
            return true;
        } catch (Exception e) {
            // A failed session/load must retain its identity for the next attempt.
            if (!(e instanceof MemoryAcpClient.SessionRestoreException)) {
                closeSessionClient(sourceSessionId);
            }
            logger.error("记忆提取失败, workspacePath={}", workspacePath, e);
            return false;
        }
    }

    private MemoryExtractionClient getOrCreateClient(String sourceSessionId,
                                                      String workspacePath)
            throws IOException {
        MemoryExtractionClient existing = sessionClients.get(sourceSessionId);
        if (existing != null) {
            return existing;
        }
        String groupId = "memory_extractor__" + workspacePath.hashCode()
                + "__" + sourceSessionId.hashCode();
        String clientWorkDir = executionWorkDir == null
                || executionWorkDir.trim().isEmpty()
                ? workspacePath : executionWorkDir.trim();
        MemoryExtractionClient created = clientFactory.create(
                clientWorkDir, groupId, config.getSubClientTimeout(),
                robotParam, config);
        sessionClients.put(sourceSessionId, created);
        return created;
    }

    private static MemoryExtractionClient createClient(
            String workspacePath, String groupId, int timeoutSeconds,
            AcpRobotParam robotParam, MemoryConfig memoryConfig) throws IOException {
        MemoryAcpClient client = new MemoryAcpClient(
                workspacePath, groupId, timeoutSeconds, robotParam,
                memoryConfig == null ? null : memoryConfig.getExecutionModel());
        client.start();
        return client;
    }

    private String requireSessionKey(String sourceSessionId) {
        if (sourceSessionId == null || sourceSessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sourceSessionId 不能为空");
        }
        return sourceSessionId.trim();
    }

    private void closeSessionClient(String sourceSessionId) {
        MemoryExtractionClient client = sessionClients.remove(sourceSessionId);
        if (client == null) return;
        try {
            client.close();
        } catch (IOException e) {
            logger.warn("关闭 Memory session 失败, sourceSessionId={}",
                    sourceSessionId, e);
        }
    }

    private void closeAllSessionClients() {
        for (Map.Entry<String, MemoryExtractionClient> entry
                : sessionClients.entrySet()) {
            closeSessionClient(entry.getKey());
        }
    }

    interface MemoryExtractionClient extends AutoCloseable {
        String sendPromptSync(String promptText) throws IOException;

        void sleep() throws IOException;

        @Override
        void close() throws IOException;
    }

    interface MemoryClientFactory {
        MemoryExtractionClient create(String workspacePath, String groupId,
                                      int timeoutSeconds, AcpRobotParam robotParam,
                                      MemoryConfig memoryConfig) throws IOException;
    }

    // ==================== 对话历史序列化 ====================

    private String serializeHistory(List<ContextMessage> history) {
        StringBuilder sb = new StringBuilder();
        for (ContextMessage msg : history) {
            switch (msg.getRole()) {
                case USER:
                    sb.append("USER: ").append(msg.getContent()).append("\n\n");
                    break;
                case ASSISTANT:
                    sb.append("ASSISTANT: ").append(msg.getContent()).append("\n\n");
                    break;
                case TOOL:
                    sb.append(String.format("TOOL [%s]: %s → %s\n\n",
                            msg.getToolName(),
                            msg.getRawInput() != null ? msg.getRawInput().toString() : "",
                            msg.getRawOutput() != null ? truncate(msg.getRawOutput().toString(), 500) : ""));
                    break;
                case EVENT:
                    // UI projection only; USER/ASSISTANT/TOOL already contain model context.
                    break;
            }
        }
        return sb.toString();
    }

    private String truncate(String text, int maxLen) {
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    // ==================== JSON 解析 ====================

    List<MemoryAction> parseActions(String response) {
        List<MemoryAction> actions = new ArrayList<>();
        if (response == null || response.trim().isEmpty()) {
            return actions;
        }

        String json = extractJsonArray(response);
        if (json == null) {
            logger.warn("无法从响应中提取 JSON 数组");
            return actions;
        }

        // 预处理：LLM 生成的 JSON 中 detail/summary 字段可能包含中文引号（\u201c \u201d），
        // Gson 即使 lenient 模式也会将其误判为 JSON 字符串边界导致解析失败。
        // 将中文引号替换为中文括号，保留语义且不影响 JSON 解析。
        json = json.replace('\u201c', '「')
                   .replace('\u201d', '」')
                   .replace('\u2018', '「')
                   .replace('\u2019', '」');

        try {
            // 使用 lenient 模式解析，容忍子 Client 返回的非严格 JSON
            // （如 detail 字段中的中文引号、未转义换行符等）
            com.google.gson.stream.JsonReader jsonReader = new com.google.gson.stream.JsonReader(new StringReader(json));
            jsonReader.setLenient(true);
            JsonArray array = JsonParser.parseReader(jsonReader).getAsJsonArray();
            for (JsonElement elem : array) {
                JsonObject obj = elem.getAsJsonObject();
                MemoryAction action = new MemoryAction();

                String actionStr = obj.has("action") ? obj.get("action").getAsString() : "NOOP";
                try {
                    action.setAction(MemoryAction.ActionType.valueOf(actionStr.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    action.setAction(MemoryAction.ActionType.NOOP);
                }

                if (obj.has("id")) action.setId(obj.get("id").getAsString());
                if (obj.has("type")) action.setType(obj.get("type").getAsString());
                if (obj.has("title")) action.setTitle(obj.get("title").getAsString());
                if (obj.has("summary")) action.setSummary(obj.get("summary").getAsString());
                if (obj.has("detail")) action.setDetail(obj.get("detail").getAsString());
                if (obj.has("detailAppend")) {
                    action.setDetailAppend(obj.get("detailAppend").getAsString());
                }
                if (obj.has("tags") && obj.get("tags").isJsonArray()) {
                    List<String> tags = new ArrayList<>();
                    for (JsonElement tag : obj.getAsJsonArray("tags")) {
                        tags.add(tag.getAsString());
                    }
                    action.setTags(tags);
                }
                if (obj.has("relatedSkills") && obj.get("relatedSkills").isJsonArray()) {
                    List<String> skills = new ArrayList<>();
                    for (JsonElement skill : obj.getAsJsonArray("relatedSkills")) {
                        skills.add(skill.getAsString());
                    }
                    action.setRelatedSkills(skills);
                }

                actions.add(action);
            }
        } catch (JsonSyntaxException e) {
            logger.error("解析记忆提取结果失败: {}", json, e);
        }
        return actions;
    }

    private String extractJsonArray(String text) {
        int codeStart = text.indexOf("```json");
        if (codeStart >= 0) {
            int jsonStart = text.indexOf('\n', codeStart);
            int codeEnd = text.indexOf("```", jsonStart);
            if (jsonStart >= 0 && codeEnd > jsonStart) {
                return text.substring(jsonStart, codeEnd).trim();
            }
        }
        codeStart = text.indexOf("```");
        if (codeStart >= 0) {
            int jsonStart = text.indexOf('\n', codeStart);
            int codeEnd = text.indexOf("```", jsonStart);
            if (jsonStart >= 0 && codeEnd > jsonStart) {
                String candidate = text.substring(jsonStart, codeEnd).trim();
                if (candidate.startsWith("[")) return candidate;
            }
        }
        int bracketStart = text.indexOf('[');
        int bracketEnd = text.lastIndexOf(']');
        if (bracketStart >= 0 && bracketEnd > bracketStart) {
            return text.substring(bracketStart, bracketEnd + 1);
        }
        return null;
    }

    /**
     * 优雅关闭：不再接受新任务，等待已提交的任务执行完毕。
     * 最多等待 subClientTimeout * 2 秒（给队列中可能的两个任务足够时间）。
     */
    public void shutdown() {
        extractQueue.shutdown();
        try {
            if (!extractQueue.awaitTermination(config.getSubClientTimeout() * 2L, TimeUnit.SECONDS)) {
                logger.warn("提取队列关闭超时，强制终止");
                extractQueue.shutdownNow();
            }
        } catch (InterruptedException e) {
            extractQueue.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            closeAllSessionClients();
        }
    }

    /** 立即取消排队和正在等待的记忆模型调用，不参与进程 stop 的等待。 */
    public void shutdownNow() {
        extractQueue.shutdownNow();
        closeAllSessionClients();
    }

    // ==================== Skill 扫描 ====================

    /**
     * 扫描当前 workspace 下可用的 skill 目录名列表。
     * 通过 AgentProvider 获取 skills 相对路径，兼容不同 agent 实现。
     */
    private List<String> scanAvailableSkills(String workspacePath) {
        List<String> skills = new ArrayList<>();
        try {
            String skillsRelPath = AgentProviderRouter.getInstance()
                    .resolve(robotParam.getAgentProvider()).getSkillsRelativePath();
            Path skillsDir = Paths.get(workspacePath, skillsRelPath);
            if (!Files.exists(skillsDir) || !Files.isDirectory(skillsDir)) {
                return skills;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir)) {
                for (Path entry : stream) {
                    if (Files.isDirectory(entry) && Files.exists(entry.resolve("SKILL.md"))) {
                        skills.add(entry.getFileName().toString());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("扫描可用 skills 失败, workspacePath={}", workspacePath, e);
        }
        return skills;
    }
}
