package com.mola.cmd.proxy.app.acp.memory;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.memory.model.*;
import com.mola.cmd.proxy.app.acp.memory.prompt.DreamPromptTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 记忆整理器（Memory Dream），负责定期整合、去重、清理已有记忆。
 * <p>
 * 参考 Claude Code Auto Dream 机制，通过子 Client 让 LLM 做语义层面的
 * 矛盾检测、重复合并、过期清理和日期规范化。
 * <p>
 * 所有整理任务异步执行，不阻塞主对话流。
 */
public class MemoryDreamer {
    private static final java.util.concurrent.ConcurrentMap<String, Object> ACTIVE_SCOPES =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<String, Object> ownedScopes =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final Logger logger = LoggerFactory.getLogger(MemoryDreamer.class);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final MemoryConfig config;
    private final MemoryFileStore fileStore;
    private final AcpRobotParam robotParam;
    private final String executionWorkDir;
    private final MemoryScopeLockRegistry locks;

    private final ExecutorService dreamQueue = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(2),
            r -> {
                Thread t = new Thread(r, "memory-dream-queue");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );

    public MemoryDreamer(MemoryConfig config, MemoryFileStore fileStore, AcpRobotParam robotParam) {
        this(config, fileStore, robotParam, null, new MemoryScopeLockRegistry());
    }

    public MemoryDreamer(MemoryConfig config, MemoryFileStore fileStore,
                         AcpRobotParam robotParam,
                         MemoryScopeLockRegistry locks) {
        this(config, fileStore, robotParam, null, locks);
    }

    public MemoryDreamer(MemoryConfig config, MemoryFileStore fileStore,
                         AcpRobotParam robotParam, String executionWorkDir,
                         MemoryScopeLockRegistry locks) {
        this.config = config;
        this.fileStore = fileStore;
        this.robotParam = robotParam;
        this.executionWorkDir = executionWorkDir;
        this.locks = locks;
    }

    /**
     * 检查是否满足自动整理条件（双门控）。
     */
    public boolean shouldDream(String workspacePath) {
        if (!config.isDreamEnabled()) return false;
        ReentrantLock lock = locks.lockFor(fileStore, workspacePath);
        lock.lock();
        try {
            DreamState state = fileStore.loadDreamState(workspacePath);
            boolean timeOk =
                    state.getHoursSinceLastDream() >= config.getDreamMinHours();
            boolean sessionsOk =
                    state.getSessionsSinceLastDream() >= config.getDreamMinSessions();
            MemoryIndex index = fileStore.loadIndex(workspacePath);
            return timeOk && sessionsOk && index.getMemories().size() >= 3;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 异步提交整理任务。
     */
    public boolean submitDream(String workspacePath) {
        String scope = fileStore.getStorageKey(workspacePath);
        Object admission = new Object();
        if (ACTIVE_SCOPES.putIfAbsent(scope, admission) != null) return false;
        ownedScopes.put(scope, admission);
        try {
            dreamQueue.submit(() -> {
                try {
                    doDream(workspacePath);
                } finally {
                    ACTIVE_SCOPES.remove(scope, admission);
                    ownedScopes.remove(scope, admission);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            ACTIVE_SCOPES.remove(scope, admission);
            ownedScopes.remove(scope, admission);
            logger.warn("整理队列已满，跳过本次整理");
            return false;
        }
    }

    public boolean isDreaming(String workspacePath) {
        return ACTIVE_SCOPES.containsKey(fileStore.getStorageKey(workspacePath));
    }

    private void doDream(String workspacePath) {
        long startTime = System.currentTimeMillis();
        logger.info("开始记忆整理 (Memory Dream), workspacePath={}", workspacePath);

        try {
            // Phase 1: Orientation — 读取全部记忆
            MemoryIndex index;
            Map<String, String> details;
            ReentrantLock snapshotLock =
                    locks.lockFor(fileStore, workspacePath);
            snapshotLock.lock();
            try {
                index = fileStore.loadIndex(workspacePath);
                details = fileStore.loadAllDetails(workspacePath, index);
            } finally {
                snapshotLock.unlock();
            }
            if (index.getMemories().isEmpty()) {
                logger.info("无记忆可整理");
                return;
            }

            // Phase 2: Analysis — 构建 prompt 并调用子 Client
            String prompt = DreamPromptTemplate.build(index, details);
            String groupId = "memory_dreamer__" + workspacePath.hashCode();

            String response;
            String clientWorkDir = executionWorkDir == null
                    || executionWorkDir.trim().isEmpty()
                    ? workspacePath : executionWorkDir.trim();
            try (MemoryAcpClient client = new MemoryAcpClient(
                    clientWorkDir, groupId,
                    config.getSubClientTimeout() * 2,
                    robotParam, config.getExecutionModel())) {
                client.start();
                response = client.sendPromptSync(prompt);
            }
            logger.info("整理子 Client 返回, 长度={}", response.length());

            // Phase 3: 解析操作列表
            List<DreamAction> actions = parseDreamActions(response);
            if (actions.isEmpty()) {
                logger.info("记忆无需整理");
                ReentrantLock lock = locks.lockFor(fileStore, workspacePath);
                lock.lock();
                try {
                    saveDreamState(workspacePath, 0, 0, 0,
                            System.currentTimeMillis() - startTime);
                } finally {
                    lock.unlock();
                }
                return;
            }

            int[] result;
            long duration;
            ReentrantLock lock = locks.lockFor(fileStore, workspacePath);
            lock.lock();
            try {
                // 分析期间存储可能变化；在同一锁内重载、应用、归档、保存状态。
                MemoryIndex currentIndex = fileStore.loadIndex(workspacePath);
                result = applyDreamActions(
                        workspacePath, actions, currentIndex);
                int orphaned = fileStore.archiveOrphanedDetails(
                        workspacePath, currentIndex);
                if (orphaned > 0) {
                    logger.info("归档孤立明细文件 {} 个", orphaned);
                }
                duration = System.currentTimeMillis() - startTime;
                saveDreamState(workspacePath, result[0], result[1],
                        result[2], duration);
            } finally {
                lock.unlock();
            }
            logger.info("记忆整理完成: merged={}, removed={}, updated={}, 耗时={}ms",
                    result[0], result[1], result[2], duration);

        } catch (Exception e) {
            logger.error("记忆整理失败, workspacePath={}", workspacePath, e);
        }
    }

    /**
     * 执行整理操作，返回 [merged, removed, updated] 计数。
     */
    private int[] applyDreamActions(String workspacePath, List<DreamAction> actions, MemoryIndex index) {
        int merged = 0, removed = 0, updated = 0;
        String now = ZonedDateTime.now().format(ISO_FORMATTER);

        for (DreamAction action : actions) {
            if (action.getAction() == null || action.getAction() == DreamAction.ActionType.NOOP) {
                continue;
            }
            try {
                switch (action.getAction()) {
                    case MERGE:
                        if (action.getSourceIds() == null || action.getSourceIds().size() < 2
                                || action.getResult() == null) {
                            logger.warn("MERGE 操作参数不完整，跳过");
                            break;
                        }
                        // 删除旧条目
                        for (String sourceId : action.getSourceIds()) {
                            boolean ok = fileStore.removeAndArchive(workspacePath, index, sourceId);
                            if (ok) {
                                logger.info("[Dream MERGE] 归档旧条目: {}", sourceId);
                            }
                        }
                        // 创建合并后的新条目
                        DreamAction.MergeResult mr = action.getResult();
                        MemoryEntry mergedEntry = new MemoryEntry();
                        mergedEntry.setId("memory_" + UUID.randomUUID().toString().substring(0, 8));
                        mergedEntry.setType(mr.getType());
                        mergedEntry.setTitle(mr.getTitle());
                        mergedEntry.setSummary(mr.getSummary());
                        mergedEntry.setDetail(mr.getDetail());
                        mergedEntry.setTags(mr.getTags() != null ? mr.getTags() : new ArrayList<>());
                        mergedEntry.setCreatedAt(now);
                        mergedEntry.setUpdatedAt(now);
                        fileStore.writeDetail(workspacePath, mergedEntry);
                        index.getMemories().add(mergedEntry);
                        merged++;
                        logger.info("[Dream MERGE] 合并 {} 条为新条目: id={}, title=\"{}\"",
                                action.getSourceIds().size(), mergedEntry.getId(), mergedEntry.getTitle());
                        break;

                    case UPDATE:
                        if (action.getId() == null || action.getFields() == null || action.getFields().isEmpty()) {
                            logger.warn("UPDATE 操作参数不完整，跳过");
                            break;
                        }
                        for (MemoryEntry entry : index.getMemories()) {
                            if (entry.getId().equals(action.getId())) {
                                // detail 不存储在索引中。即使 Dream 只更新 summary/title，
                                // 写回前也必须从明细文件恢复旧正文，避免被 summary 覆盖。
                                fileStore.updateMemory(workspacePath, index, entry, next -> {
                                    Map<String, String> fields = action.getFields();
                                    if (fields.containsKey("detail")) next.setDetail(fields.get("detail"));
                                    if (fields.containsKey("summary")) next.setSummary(fields.get("summary"));
                                    if (fields.containsKey("title")) next.setTitle(fields.get("title"));
                                    next.setUpdatedAt(now);
                                });
                                updated++;
                                logger.info("[Dream UPDATE] id={}, title=\"{}\"", entry.getId(),
                                        action.getFields().getOrDefault("title", entry.getTitle()));
                                break;
                            }
                        }
                        break;

                    case DELETE:
                        if (action.getId() == null) {
                            logger.warn("DELETE 操作缺少 id，跳过");
                            break;
                        }
                        boolean ok = fileStore.removeAndArchive(workspacePath, index, action.getId());
                        if (ok) {
                            removed++;
                            logger.info("[Dream DELETE] id={}, reason=\"{}\"",
                                    action.getId(), action.getReason());
                        }
                        break;
                }
            } catch (Exception e) {
                logger.error("执行整理操作失败: action={}, id={}",
                        action.getAction(), action.getId(), e);
            }
        }

        if (merged > 0 || removed > 0 || updated > 0) {
            fileStore.saveIndex(workspacePath, index);
        }
        return new int[]{merged, removed, updated};
    }

    // ==================== JSON 解析 ====================

    List<DreamAction> parseDreamActions(String response) {
        List<DreamAction> actions = new ArrayList<>();
        if (response == null || response.trim().isEmpty()) return actions;

        String json = extractJsonArray(response);
        if (json == null) {
            logger.warn("无法从整理响应中提取 JSON 数组");
            return actions;
        }

        // 预处理中文引号
        json = json.replace('\u201c', '「').replace('\u201d', '」')
                   .replace('\u2018', '「').replace('\u2019', '」');

        try {
            com.google.gson.stream.JsonReader jsonReader =
                    new com.google.gson.stream.JsonReader(new StringReader(json));
            jsonReader.setLenient(true);
            JsonArray array = JsonParser.parseReader(jsonReader).getAsJsonArray();

            for (JsonElement elem : array) {
                JsonObject obj = elem.getAsJsonObject();
                DreamAction action = new DreamAction();

                String actionStr = obj.has("action") ? obj.get("action").getAsString() : "NOOP";
                try {
                    action.setAction(DreamAction.ActionType.valueOf(actionStr.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    action.setAction(DreamAction.ActionType.NOOP);
                }

                if (obj.has("id")) action.setId(obj.get("id").getAsString());
                if (obj.has("reason")) action.setReason(obj.get("reason").getAsString());

                // MERGE 专用字段
                if (obj.has("sourceIds") && obj.get("sourceIds").isJsonArray()) {
                    List<String> sourceIds = new ArrayList<>();
                    for (JsonElement id : obj.getAsJsonArray("sourceIds")) {
                        sourceIds.add(id.getAsString());
                    }
                    action.setSourceIds(sourceIds);
                }
                if (obj.has("result") && obj.get("result").isJsonObject()) {
                    JsonObject resultObj = obj.getAsJsonObject("result");
                    DreamAction.MergeResult mr = new DreamAction.MergeResult();
                    if (resultObj.has("type")) mr.setType(resultObj.get("type").getAsString());
                    if (resultObj.has("title")) mr.setTitle(resultObj.get("title").getAsString());
                    if (resultObj.has("summary")) mr.setSummary(resultObj.get("summary").getAsString());
                    if (resultObj.has("detail")) mr.setDetail(resultObj.get("detail").getAsString());
                    if (resultObj.has("tags") && resultObj.get("tags").isJsonArray()) {
                        List<String> tags = new ArrayList<>();
                        for (JsonElement tag : resultObj.getAsJsonArray("tags")) {
                            tags.add(tag.getAsString());
                        }
                        mr.setTags(tags);
                    }
                    action.setResult(mr);
                }

                // UPDATE 专用字段
                if (obj.has("fields") && obj.get("fields").isJsonObject()) {
                    Map<String, String> fields = new HashMap<>();
                    for (Map.Entry<String, JsonElement> entry : obj.getAsJsonObject("fields").entrySet()) {
                        if (entry.getValue().isJsonPrimitive()) {
                            fields.put(entry.getKey(), entry.getValue().getAsString());
                        }
                    }
                    action.setFields(fields);
                }

                actions.add(action);
            }
        } catch (JsonSyntaxException e) {
            logger.error("解析整理结果失败: {}", json, e);
        }
        return actions;
    }

    private String extractJsonArray(String text) {
        // 尝试从 ```json ... ``` 中提取
        int codeStart = text.indexOf("```json");
        if (codeStart >= 0) {
            int jsonStart = text.indexOf('\n', codeStart);
            int codeEnd = text.indexOf("```", jsonStart);
            if (jsonStart >= 0 && codeEnd > jsonStart) {
                return text.substring(jsonStart, codeEnd).trim();
            }
        }
        // 尝试从 ``` ... ``` 中提取
        codeStart = text.indexOf("```");
        if (codeStart >= 0) {
            int jsonStart = text.indexOf('\n', codeStart);
            int codeEnd = text.indexOf("```", jsonStart);
            if (jsonStart >= 0 && codeEnd > jsonStart) {
                String candidate = text.substring(jsonStart, codeEnd).trim();
                if (candidate.startsWith("[")) return candidate;
            }
        }
        // 直接找 [ ... ]
        int bracketStart = text.indexOf('[');
        int bracketEnd = text.lastIndexOf(']');
        if (bracketStart >= 0 && bracketEnd > bracketStart) {
            return text.substring(bracketStart, bracketEnd + 1);
        }
        return null;
    }

    // ==================== 状态管理 ====================

    private void saveDreamState(String workspacePath, int merged, int removed, int dateFixed, long durationMs) {
        DreamState state = new DreamState();
        state.setLastDreamTime(ZonedDateTime.now().format(ISO_FORMATTER));
        state.setSessionsSinceLastDream(0);  // 重置计数
        state.setLastMerged(merged);
        state.setLastRemoved(removed);
        state.setLastDateFixed(dateFixed);
        state.setLastDurationMs(durationMs);
        fileStore.saveDreamState(workspacePath, state);
    }

    public void shutdown() {
        dreamQueue.shutdown();
        try {
            if (!dreamQueue.awaitTermination(config.getSubClientTimeout() * 2L, TimeUnit.SECONDS)) {
                logger.warn("整理队列关闭超时，强制终止");
                shutdownNow();
            }
        } catch (InterruptedException e) {
            shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 立即取消整理任务，不参与进程 stop 的等待。 */
    public void shutdownNow() {
        dreamQueue.shutdownNow();
        ownedScopes.forEach((scope, admission) -> ACTIVE_SCOPES.remove(scope, admission));
        ownedScopes.clear();
    }
}
