package com.mola.cmd.proxy.app.acp.memory;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mola.cmd.proxy.app.acp.common.PathUtils;
import com.mola.cmd.proxy.app.acp.memory.model.DreamState;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryAction;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryEntry;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 记忆文件存储层，负责索引和明细文件的 CRUD。
 * <p>
 * 存储结构：
 * <pre>
 * {baseDir}/
 * ├── {workspacePath_hash}/
 * │   ├── MEMORY_INDEX.json
 * │   ├── memories/
 * │   │   ├── user_profile.md
 * │   │   └── ...
 * │   └── archive/
 * └── global/
 * </pre>
 */
public class MemoryFileStore {

    private static final Logger logger = LoggerFactory.getLogger(MemoryFileStore.class);
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 索引专用 Gson：排除 detail 和 sourceSession，这些只属于明细文件 */
    private static final Gson INDEX_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .setExclusionStrategies(new ExclusionStrategy() {
                private final Set<String> EXCLUDED = new HashSet<>(Arrays.asList("detail", "sourceSession"));
                @Override
                public boolean shouldSkipField(FieldAttributes f) {
                    return f.getDeclaringClass() == MemoryEntry.class && EXCLUDED.contains(f.getName());
                }
                @Override
                public boolean shouldSkipClass(Class<?> clazz) { return false; }
            })
            .create();
    private static final String INDEX_FILE = "MEMORY_INDEX.json";
    private static final String DREAM_STATE_FILE = "DREAM_STATE.json";
    private static final String MEMORIES_DIR = "memories";
    private static final String ARCHIVE_DIR = "archive";
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final Pattern DETAIL_FRONTMATTER = Pattern.compile(
            "\\A---\\r?\\n.*?\\r?\\n---\\r?\\n(?:\\r?\\n)?", Pattern.DOTALL);

    private final String baseDir;
    /** scope=robot 时非 null，用于在 workspacePath 下追加 robot 子目录 */
    private final String robotName;

    public MemoryFileStore(String baseDir) {
        this(baseDir, null);
    }

    public MemoryFileStore(String baseDir, String robotName) {
        this.baseDir = baseDir;
        this.robotName = robotName;
    }

    // ==================== 索引操作 ====================

    /**
     * 加载项目的记忆索引，不存在时返回空索引。
     */
    public MemoryIndex loadIndex(String workspacePath) {
        Path indexPath = getIndexFilePath(workspacePath);
        if (!Files.exists(indexPath)) {
            return new MemoryIndex();
        }
        try {
            String content = new String(Files.readAllBytes(indexPath), StandardCharsets.UTF_8);
            return PRETTY_GSON.fromJson(content, MemoryIndex.class);
        } catch (Exception e) {
            logger.error("加载记忆索引失败: {}", indexPath, e);
            return new MemoryIndex();
        }
    }

    /**
     * 保存索引文件。
     */
    public void saveIndex(String workspacePath, MemoryIndex index) {
        Path indexPath = getIndexFilePath(workspacePath);
        try {
            Files.createDirectories(indexPath.getParent());
            index.setLastUpdated(ZonedDateTime.now().format(ISO_FORMATTER));
            Files.write(indexPath, INDEX_GSON.toJson(index).getBytes(StandardCharsets.UTF_8));
            logger.info("记忆索引已保存: {}, 记忆数={}", indexPath, index.getMemories().size());
        } catch (IOException e) {
            logger.error("保存记忆索引失败: {}", indexPath, e);
        }
    }

    // ==================== 明细文件操作 ====================

    /**
     * 写入/更新明细文件（Markdown + Frontmatter 格式）。
     */
    public void writeDetail(String workspacePath, MemoryEntry entry) {
        Path memoriesDir = getProjectDir(workspacePath).resolve(MEMORIES_DIR);
        try {
            Files.createDirectories(memoriesDir);
            if (entry.getTags() == null) entry.setTags(new ArrayList<>());
            if (entry.getRelatedSkills() == null) {
                entry.setRelatedSkills(new ArrayList<>());
            }
            String fileName = buildFileName(entry.getType(), entry.getTitle());
            Path filePath = memoriesDir.resolve(fileName);

            StringBuilder sb = new StringBuilder();
            sb.append("---\n");
            sb.append("id: ").append(entry.getId()).append("\n");
            sb.append("type: ").append(entry.getType()).append("\n");
            sb.append("title: ").append(entry.getTitle()).append("\n");
            sb.append("tags: [").append(String.join(", ", entry.getTags())).append("]\n");
            if (entry.getRelatedSkills() != null && !entry.getRelatedSkills().isEmpty()) {
                sb.append("relatedSkills: [").append(String.join(", ", entry.getRelatedSkills())).append("]\n");
            }
            sb.append("createdAt: ").append(entry.getCreatedAt()).append("\n");
            sb.append("updatedAt: ").append(entry.getUpdatedAt()).append("\n");
            if (entry.getSourceSession() != null) {
                sb.append("sourceSession: ").append(entry.getSourceSession()).append("\n");
            }
            if (entry.getLastAccessedAt() != null) {
                sb.append("lastAccessedAt: ").append(entry.getLastAccessedAt()).append("\n");
            }
            if (entry.getAccessCount() > 0) {
                sb.append("accessCount: ").append(entry.getAccessCount()).append("\n");
            }
            sb.append("---\n\n");
            sb.append(entry.getDetail() != null ? entry.getDetail() : entry.getSummary());
            sb.append("\n");

            Files.write(filePath, sb.toString().getBytes(StandardCharsets.UTF_8));
            entry.setFile(filePath.toAbsolutePath().toString());
            logger.info("记忆明细已写入: {}", filePath);
        } catch (IOException e) {
            logger.error("写入记忆明细失败: {}", entry.getId(), e);
        }
    }

    /**
     * 从索引指向的 Markdown 文件读取明细正文，不包含 YAML frontmatter。
     * UPDATE 必须先调用此方法恢复索引中未持久化的 detail，再执行部分字段更新。
     */
    public String readDetailBody(MemoryEntry entry) throws IOException {
        if (entry == null || entry.getFile() == null
                || entry.getFile().trim().isEmpty()) {
            throw new IOException("记忆明细路径为空");
        }
        Path path = Paths.get(entry.getFile());
        if (!Files.isRegularFile(path)) {
            throw new IOException("记忆明细文件不存在: " + path);
        }
        String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        if (!content.startsWith("---")) {
            return trimTrailingLineBreaks(content);
        }
        Matcher matcher = DETAIL_FRONTMATTER.matcher(content);
        if (!matcher.find()) {
            throw new IOException("记忆明细 frontmatter 格式无效: " + path);
        }
        return trimTrailingLineBreaks(content.substring(matcher.end()));
    }

    private String appendDetail(String oldDetail, String addition, String now) {
        String oldText = trimTrailingLineBreaks(oldDetail);
        String newText = addition == null ? "" : addition.trim();
        if (newText.isEmpty()) return oldText;
        if (oldText.isEmpty()) return newText;
        if (oldText.contains(newText)) return oldText;
        String updateDate = now != null && now.length() >= 10
                ? now.substring(0, 10) : now;
        return oldText + "\n\n## " + updateDate + " 更新\n\n" + newText;
    }

    private String trimTrailingLineBreaks(String value) {
        if (value == null || value.isEmpty()) return "";
        int end = value.length();
        while (end > 0) {
            char c = value.charAt(end - 1);
            if (c != '\n' && c != '\r') break;
            end--;
        }
        return value.substring(0, end);
    }

    // ==================== 删除与归档 ====================

    /**
     * 删除记忆：从索引中移除，明细文件移到 archive 目录。
     *
     * @return true 如果成功删除
     */
    /**
     * 删除记忆：从索引中移除，明细文件移到 archive 目录，保存索引。
     * 这是对外的完整删除操作（load → remove → archive → save）。
     *
     * @return true 如果成功删除
     */
    public boolean deleteMemory(String workspacePath, String memoryId) {
        MemoryIndex index = loadIndex(workspacePath);
        boolean removed = removeAndArchive(workspacePath, index, memoryId);
        if (removed) {
            saveIndex(workspacePath, index);
        }
        return removed;
    }

    /**
     * 记录一次记忆访问：更新 lastAccessedAt 和 accessCount。
     *
     * @param workspacePath 当前工作目录
     * @param filePath      被读取的明细文件路径
     */
    public void touchMemory(String workspacePath, String filePath) {
        if (filePath == null) return;
        touchMemoriesReferenced(workspacePath,
                Collections.singletonList(filePath));
    }

    /**
     * 记录一次工具调用中引用到的记忆明细。references 可以是结构化文件路径，
     * 也可以是包含绝对路径的 Bash/exec 命令。同一次调用对同一记忆只计一次。
     * accessCount 的语义是“检测到的明细文件访问次数”，不代表 Agent 最终采用
     * 了该记忆的结论。
     *
     * @return 本次更新的记忆条目数
     */
    public int touchMemoriesReferenced(String workspacePath,
                                       Collection<String> references) {
        if (references == null || references.isEmpty()) return 0;
        MemoryIndex index = loadIndex(workspacePath);
        String now = ZonedDateTime.now().format(ISO_FORMATTER);
        boolean changed = false;
        int touched = 0;
        for (MemoryEntry entry : index.getMemories()) {
            if (isReferenced(workspacePath, entry.getFile(), references)) {
                entry.setLastAccessedAt(now);
                entry.setAccessCount(entry.getAccessCount() + 1);
                changed = true;
                touched++;
                logger.info("记忆明细访问已记录: id={}, accessCount={}",
                        entry.getId(), entry.getAccessCount());
            }
        }
        if (changed) {
            saveIndex(workspacePath, index);
        }
        return touched;
    }

    private boolean isReferenced(String workspacePath, String indexedFile,
                                 Collection<String> references) {
        if (indexedFile == null || indexedFile.isEmpty()) return false;
        String normalizedIndexed;
        try {
            normalizedIndexed = Paths.get(indexedFile).toAbsolutePath()
                    .normalize().toString();
        } catch (Exception e) {
            normalizedIndexed = indexedFile;
        }
        for (String reference : references) {
            if (reference == null || reference.isEmpty()) continue;
            // Bash/exec 常把明细绝对路径放在 cmd 字符串中；直接匹配当前索引中的
            // 已知路径，无需解析 shell，也不会把未知 memories/ 路径计入访问。
            if (reference.contains(indexedFile)
                    || reference.contains(normalizedIndexed)) {
                return true;
            }
            // 结构化 path 允许相对 workspace 的路径和未规范化路径。
            if (!containsShellSyntax(reference)) {
                try {
                    Path candidate = Paths.get(reference);
                    if (!candidate.isAbsolute()) {
                        candidate = Paths.get(workspacePath).resolve(candidate);
                    }
                    if (candidate.toAbsolutePath().normalize().toString()
                            .equals(normalizedIndexed)) {
                        return true;
                    }
                } catch (Exception ignored) {
                    // 非路径字符串由上面的已知绝对路径包含判断处理。
                }
            }
        }
        return false;
    }

    private boolean containsShellSyntax(String value) {
        return value.indexOf(' ') >= 0 || value.indexOf('\t') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('|') >= 0
                || value.indexOf(';') >= 0 || value.indexOf('&') >= 0;
    }

    // ==================== 批量操作 ====================

    /**
     * 执行子 Client 返回的批量操作（ADD/UPDATE/DELETE）。
     */
    public void applyActions(String workspacePath, List<MemoryAction> actions,
                             MemoryIndex existingIndex, int maxEntries) {
        String now = ZonedDateTime.now().format(ISO_FORMATTER);
        boolean changed = false;

        for (MemoryAction action : actions) {
            if (action.getAction() == null || action.getAction() == MemoryAction.ActionType.NOOP) {
                continue;
            }
            switch (action.getAction()) {
                case ADD:
                    if (existingIndex.getMemories().size() >= maxEntries) {
                        archiveOldest(workspacePath, existingIndex);
                    }
                    MemoryEntry newEntry = new MemoryEntry();
                    newEntry.setId("memory_" + UUID.randomUUID().toString().substring(0, 8));
                    newEntry.setType(action.getType());
                    newEntry.setTitle(action.getTitle());
                    newEntry.setSummary(action.getSummary());
                    newEntry.setDetail(action.getDetail());
                    newEntry.setTags(action.getTags() != null
                            ? action.getTags() : new ArrayList<>());
                    newEntry.setRelatedSkills(action.getRelatedSkills() != null
                            ? action.getRelatedSkills() : new ArrayList<>());
                    newEntry.setCreatedAt(now);
                    newEntry.setUpdatedAt(now);
                    writeDetail(workspacePath, newEntry);
                    existingIndex.getMemories().add(newEntry);
                    changed = true;
                    logger.info("[记忆 ADD] id={}, type={}, title=\"{}\", summary=\"{}\"",
                            newEntry.getId(), newEntry.getType(), newEntry.getTitle(), newEntry.getSummary());
                    break;

                case UPDATE:
                    for (MemoryEntry entry : existingIndex.getMemories()) {
                        if (entry.getId().equals(action.getId())) {
                            String oldTitle = entry.getTitle();
                            String oldSummary = entry.getSummary();
                            try {
                                entry.setDetail(readDetailBody(entry));
                            } catch (IOException e) {
                                throw new IllegalStateException(
                                        "无法安全更新记忆 " + entry.getId()
                                                + "：旧明细读取失败", e);
                            }
                            if (action.getType() != null) entry.setType(action.getType());
                            if (action.getTitle() != null) entry.setTitle(action.getTitle());
                            if (action.getSummary() != null) entry.setSummary(action.getSummary());
                            entry.setDetail(appendDetail(entry.getDetail(),
                                    action.getDetailAppend(), now));
                            if (action.getTags() != null) {
                                entry.setTags(action.getTags());
                            }
                            if (action.getRelatedSkills() != null) {
                                entry.setRelatedSkills(action.getRelatedSkills());
                            }
                            entry.setUpdatedAt(now);
                            writeDetail(workspacePath, entry);
                            changed = true;
                            logger.info("[记忆 UPDATE] id={}, type={}, title=\"{}\" -> \"{}\", summary=\"{}\" -> \"{}\"",
                                    entry.getId(), entry.getType(),
                                    oldTitle, entry.getTitle(),
                                    oldSummary, entry.getSummary());
                            break;
                        }
                    }
                    break;

                case DELETE:
                    // 先取出待删记忆信息用于日志
                    MemoryEntry toDelete = existingIndex.getMemories().stream()
                            .filter(e -> e.getId().equals(action.getId()))
                            .findFirst().orElse(null);
                    if (toDelete != null) {
                        logger.info("[记忆 DELETE] id={}, type={}, title=\"{}\", summary=\"{}\"",
                                toDelete.getId(), toDelete.getType(), toDelete.getTitle(), toDelete.getSummary());
                    }
                    changed |= removeAndArchive(workspacePath, existingIndex, action.getId());
                    break;
            }
        }

        if (changed) {
            saveIndex(workspacePath, existingIndex);
        }
    }

    /**
     * 归档最不活跃的记忆（按复合分数升序，归档分数最低的一条）。
     * 分数 = recency(lastAccessedAt 或 updatedAt) + 0.3 * log(1 + accessCount)
     */
    private void archiveOldest(String workspacePath, MemoryIndex index) {
        if (index.getMemories().isEmpty()) return;
        MemoryEntry lowest = index.getMemories().stream()
                .min(Comparator.comparingDouble(this::calcActivityScore))
                .orElse(null);
        if (lowest != null) {
            logger.info("容量超限，归档最不活跃记忆: {} - {} (score={})",
                    lowest.getId(), lowest.getTitle(), calcActivityScore(lowest));
            removeAndArchive(workspacePath, index, lowest.getId());
        }
    }

    /**
     * 计算记忆的活跃度分数。越高越活跃，越不容易被淘汰。
     */
    private double calcActivityScore(MemoryEntry entry) {
        String timeStr = entry.getLastAccessedAt() != null
                ? entry.getLastAccessedAt()
                : (entry.getUpdatedAt() != null ? entry.getUpdatedAt() : "");
        double recency = 0;
        if (!timeStr.isEmpty()) {
            try {
                long hours = Duration.between(
                        ZonedDateTime.parse(timeStr), ZonedDateTime.now()).toHours();
                recency = Math.exp(-0.01 * hours); // 指数衰减，约3天半衰期
            } catch (Exception ignored) {}
        }
        return recency + 0.3 * Math.log1p(entry.getAccessCount());
    }

    /**
     * 从指定的 index 对象中移除记忆条目，并将明细文件归档。
     * 不会触发 saveIndex——由调用方统一保存。
     *
     * @return true 如果找到并移除了该条目
     */
    public boolean removeAndArchive(String workspacePath, MemoryIndex index, String memoryId) {
        MemoryEntry target = null;
        Iterator<MemoryEntry> it = index.getMemories().iterator();
        while (it.hasNext()) {
            MemoryEntry entry = it.next();
            if (memoryId.equals(entry.getId())) {
                target = entry;
                it.remove();
                break;
            }
        }
        if (target == null) {
            logger.warn("记忆不存在: {}", memoryId);
            return false;
        }
        archiveDetailFile(workspacePath, target);
        logger.info("记忆已从索引移除并归档: {} - {}", target.getId(), target.getTitle());
        return true;
    }

    /**
     * 将明细文件移到 archive 目录。
     */
    private void archiveDetailFile(String workspacePath, MemoryEntry entry) {
        if (entry.getFile() == null) return;
        try {
            Path source = Paths.get(entry.getFile());
            if (Files.exists(source)) {
                Path archiveDir = getProjectDir(workspacePath).resolve(ARCHIVE_DIR);
                Files.createDirectories(archiveDir);
                Files.move(source, archiveDir.resolve(source.getFileName()));
            }
        } catch (IOException e) {
            logger.error("归档记忆文件失败: {}", entry.getFile(), e);
        }
    }

    /**
     * 清理过期的 project 类型记忆。
     *
     * @param expireDays 过期天数
     * @return 清理的记忆数量
     */
    public int cleanExpiredMemories(String workspacePath, int expireDays) {
        MemoryIndex index = loadIndex(workspacePath);
        String cutoff = ZonedDateTime.now().minusDays(expireDays).format(ISO_FORMATTER);
        List<String> toDelete = index.getMemories().stream()
                .filter(e -> "project".equals(e.getType()))
                .filter(e -> e.getUpdatedAt() != null && e.getUpdatedAt().compareTo(cutoff) < 0)
                .map(MemoryEntry::getId)
                .collect(Collectors.toList());

        for (String id : toDelete) {
            deleteMemory(workspacePath, id);
        }
        if (!toDelete.isEmpty()) {
            logger.info("清理过期记忆 {} 条, workspacePath={}", toDelete.size(), workspacePath);
        }
        return toDelete.size();
    }

    // ==================== Dream 状态操作 ====================

    /**
     * 加载整理状态，不存在时返回默认状态。
     */
    public DreamState loadDreamState(String workspacePath) {
        Path path = getProjectDir(workspacePath).resolve(DREAM_STATE_FILE);
        if (!Files.exists(path)) {
            return new DreamState();
        }
        try {
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            return PRETTY_GSON.fromJson(content, DreamState.class);
        } catch (Exception e) {
            logger.error("加载整理状态失败: {}", path, e);
            return new DreamState();
        }
    }

    /**
     * 保存整理状态。
     */
    public void saveDreamState(String workspacePath, DreamState state) {
        Path path = getProjectDir(workspacePath).resolve(DREAM_STATE_FILE);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, PRETTY_GSON.toJson(state).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.error("保存整理状态失败: {}", path, e);
        }
    }

    /**
     * 递增 session 计数（用于 Dream 触发条件判断）。
     */
    public void incrementDreamSessionCount(String workspacePath) {
        DreamState state = loadDreamState(workspacePath);
        state.setSessionsSinceLastDream(state.getSessionsSinceLastDream() + 1);
        saveDreamState(workspacePath, state);
    }

    /**
     * 读取所有明细文件内容。
     *
     * @return key=memoryId, value=明细文件的原始文本内容
     */
    public Map<String, String> loadAllDetails(String workspacePath, MemoryIndex index) {
        Map<String, String> details = new HashMap<>();
        for (MemoryEntry entry : index.getMemories()) {
            if (entry.getFile() != null) {
                try {
                    Path filePath = Paths.get(entry.getFile());
                    if (Files.exists(filePath)) {
                        String content = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
                        details.put(entry.getId(), content);
                    }
                } catch (IOException e) {
                    logger.warn("读取明细文件失败: {}", entry.getFile());
                }
            }
        }
        return details;
    }

    // ==================== 孤立文件清理 ====================

    /**
     * 将 memories/ 目录下未被索引引用的明细文件移到 archive/ 目录。
     *
     * @return 归档的孤立文件数量
     */
    public int archiveOrphanedDetails(String workspacePath, MemoryIndex index) {
        Path memoriesDir = getProjectDir(workspacePath).resolve(MEMORIES_DIR);
        if (!Files.exists(memoriesDir) || !Files.isDirectory(memoriesDir)) {
            return 0;
        }

        // 收集索引中所有明细文件的绝对路径
        Set<String> indexedPaths = new HashSet<>();
        for (MemoryEntry entry : index.getMemories()) {
            if (entry.getFile() != null) {
                indexedPaths.add(Paths.get(entry.getFile()).toAbsolutePath().toString());
            }
        }

        int archived = 0;
        try {
            Path archiveDir = getProjectDir(workspacePath).resolve(ARCHIVE_DIR);
            for (Path file : Files.list(memoriesDir).collect(Collectors.toList())) {
                if (!Files.isRegularFile(file)) continue;
                if (!indexedPaths.contains(file.toAbsolutePath().toString())) {
                    Files.createDirectories(archiveDir);
                    Files.move(file, archiveDir.resolve(file.getFileName()));
                    archived++;
                    logger.info("归档孤立明细文件: {}", file.getFileName());
                }
            }
        } catch (IOException e) {
            logger.error("清理孤立明细文件失败, workspacePath={}", workspacePath, e);
        }
        return archived;
    }

    // ==================== 查询 ====================

    /**
     * 列出项目的所有记忆（索引概要）。
     */
    public List<MemoryEntry> listMemories(String workspacePath) {
        return loadIndex(workspacePath).getMemories();
    }

    // ==================== 路径工具 ====================

    public String getIndexPath(String workspacePath) {
        return getIndexFilePath(workspacePath).toAbsolutePath().toString();
    }

    /**
     * 返回该 workspace/scope 的规范化实际存储目录，供跨 manager 锁定。
     */
    public String getStorageKey(String workspacePath) {
        return getProjectDir(workspacePath).toAbsolutePath()
                .normalize().toString();
    }

    /** Validate externally edited detail files before an optimistic resource commit. Caller holds the storage lock. */
    public void validateSyncedDetail(String workspace, String fileName, byte[] content) throws IOException {
        if (!fileName.endsWith(".md") || fileName.contains("/") || fileName.contains("\\") || fileName.startsWith(".")) throw new IOException("Only memory Markdown details are writable");
        if (content == null) return;
        parseSyncedDetail(workspace, fileName, new String(content, StandardCharsets.UTF_8));
    }

    private MemoryEntry parseSyncedDetail(String workspace, String fileName, String text) throws IOException {
        java.util.Map<String, String> fields = new java.util.HashMap<>();
        String body = text;
        if (text.startsWith("---")) {
            Matcher matcher = DETAIL_FRONTMATTER.matcher(text);
            if (!matcher.find()) throw new IOException("Invalid memory frontmatter");
            String[] lines = text.substring(0, matcher.end()).split("\r?\n");
            for (String line : lines) { int colon = line.indexOf(':'); if (colon > 0) fields.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim()); }
            body = text.substring(matcher.end());
        }
        MemoryIndex index = loadIndex(workspace); MemoryEntry entry = null;
        Path expected = getProjectDir(workspace).resolve(MEMORIES_DIR).resolve(fileName).toAbsolutePath().normalize();
        for (MemoryEntry candidate : index.getMemories()) {
            if (candidate.getFile() != null && Paths.get(candidate.getFile()).toAbsolutePath().normalize().equals(expected)) { entry = candidate; break; }
        }
        if (entry != null && fields.containsKey("id") && !entry.getId().equals(fields.get("id"))) throw new IOException("A synced edit cannot change the memory identity");
        if (entry == null) {
            entry = new MemoryEntry(); String id = fields.get("id");
            if (id != null && !id.matches("[a-zA-Z0-9_-]{1,128}")) throw new IOException("Invalid memory identity");
            entry.setId(id == null ? "memory_" + java.util.UUID.randomUUID().toString() : id);
            entry.setCreatedAt(ZonedDateTime.now().format(ISO_FORMATTER)); entry.setType("project"); entry.setTitle(fileName.substring(0, fileName.length() - 3));
        }
        if (fields.containsKey("type")) {
            String type = fields.get("type"); if (!java.util.Arrays.asList("project", "user", "feedback", "reference").contains(type)) throw new IOException("Invalid memory type"); entry.setType(type);
        }
        if (fields.containsKey("title") && !fields.get("title").isEmpty()) entry.setTitle(fields.get("title"));
        if (fields.containsKey("tags")) entry.setTags(parseSyncedList(fields.get("tags")));
        if (fields.containsKey("relatedSkills")) entry.setRelatedSkills(parseSyncedList(fields.get("relatedSkills")));
        String summary = body.trim().replaceAll("\\s+", " "); entry.setSummary(summary.substring(0, Math.min(summary.length(), 400)));
        entry.setDetail(body); entry.setFile(expected.toString()); entry.setUpdatedAt(ZonedDateTime.now().format(ISO_FORMATTER)); return entry;
    }

    private java.util.List<String> parseSyncedList(String text) throws IOException {
        if (!text.startsWith("[") || !text.endsWith("]")) throw new IOException("Memory lists must use [item, item] syntax");
        java.util.List<String> result = new java.util.ArrayList<>();
        for (String part : text.substring(1, text.length() - 1).split(",")) { String value = part.trim(); if (!value.isEmpty()) result.add(value); }
        return result;
    }

    public void validateSyncedDetails(String workspace, java.util.Map<String, byte[]> changes) throws IOException {
        java.util.Set<String> ids = new java.util.HashSet<>(); MemoryIndex index = loadIndex(workspace);
        for (MemoryEntry entry : index.getMemories()) {
            String file = entry.getFile() == null ? "" : Paths.get(entry.getFile()).getFileName().toString();
            if (!changes.containsKey(file)) ids.add(entry.getId());
        }
        for (java.util.Map.Entry<String, byte[]> change : changes.entrySet()) {
            validateSyncedDetail(workspace, change.getKey(), change.getValue());
            if (change.getValue() != null) {
                MemoryEntry entry = parseSyncedDetail(workspace, change.getKey(), new String(change.getValue(), StandardCharsets.UTF_8));
                if (!ids.add(entry.getId())) throw new IOException("Duplicate memory identity");
            }
        }
    }

    /** Apply detail changes to the generated index; the index itself is never accepted from a container. */
    public void reconcileSyncedDetails(String workspace, java.util.Set<String> fileNames) throws IOException {
        MemoryIndex index = loadIndex(workspace);
        for (String fileName : fileNames) {
            Path path = getProjectDir(workspace).resolve(MEMORIES_DIR).resolve(fileName).toAbsolutePath().normalize();
            MemoryEntry entry = Files.exists(path) ? parseSyncedDetail(workspace, fileName, new String(Files.readAllBytes(path), StandardCharsets.UTF_8)) : null;
            index.getMemories().removeIf(e -> e.getFile() != null && Paths.get(e.getFile()).toAbsolutePath().normalize().equals(path));
            if (entry != null) index.getMemories().add(entry);
        }
        if (!fileNames.isEmpty()) {
            index.setLastUpdated(ZonedDateTime.now().format(ISO_FORMATTER));
            com.mola.starweave.sdk.sync.ResourceFiles.write(getIndexFilePath(workspace), INDEX_GSON.toJson(index).getBytes(StandardCharsets.UTF_8));
        }
    }

    private Path getIndexFilePath(String workspacePath) {
        return getProjectDir(workspacePath).resolve(INDEX_FILE);
    }

    private Path getProjectDir(String workspacePath) {
        Path dir = Paths.get(baseDir, PathUtils.sanitizePath(workspacePath));
        if (robotName != null) {
            dir = dir.resolve(PathUtils.sanitizePath(robotName));
        }
        return dir;
    }

    /**
     * 根据类型和标题生成文件名：type_title_slug.md
     */
    private String buildFileName(String type, String title) {
        String slug = title.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_")
                .replaceAll("_+", "_")
                .toLowerCase();
        if (slug.length() > 40) {
            slug = slug.substring(0, 40);
        }
        return type + "_" + slug + ".md";
    }
}
