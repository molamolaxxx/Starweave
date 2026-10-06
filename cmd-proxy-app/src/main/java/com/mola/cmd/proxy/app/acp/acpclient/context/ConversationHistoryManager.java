package com.mola.cmd.proxy.app.acp.acpclient.context;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.acpclient.ClientSurface;
import com.mola.cmd.proxy.app.acp.common.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 会话上下文管理器，负责内存中的消息收集、磁盘落盘与加载。
 * <p>
 * 存储目录结构：
 * <pre>
 * ~/.cmd-proxy/session/{workspaceDirName}/{sessionId}/
 *   ├── turn_0000.json
 *   ├── turn_0001.json
 *   └── resources.json   (去重的图片 base64)
 * </pre>
 */
/**
 * 会话上下文管理器，负责内存中的消息收集、磁盘落盘与加载。
 * <p>
 * 存储目录结构：
 * <pre>
 * ~/.cmd-proxy/session/{workspaceDirName}/{sessionId}/
 *   ├── turn_0000.json
 *   ├── turn_0001.json
 *   └── files/          (用户上传的文件，按原始文件名存储)
 * </pre>
 */
public class ConversationHistoryManager {

    private static final Logger logger = LoggerFactory.getLogger(ConversationHistoryManager.class);
    private static final Path SESSION_ROOT_DIR =
            com.mola.cmd.proxy.app.utils.CmdProxyHome.resolve("session");
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String MEMORY_PENDING_FILE = "memory_extract_pending.json";

    /** 按 workspacePath 隔离的 session 基础目录 */
    private final Path sessionBaseDir;

    /** 当前 turn 的上下文消息，flushTurn 后清空 */
    private final List<ContextMessage> currentTurn = new ArrayList<>();
    private ContextMessage streamingAssistant;
    private final java.util.Map<String, ContextMessage> acceptedUiMessages = new java.util.LinkedHashMap<>();
    private final Map<String, com.mola.cmd.proxy.app.acp.common.ChatHistoryPages> uiPages = new java.util.concurrent.ConcurrentHashMap<>();

    public synchronized com.alibaba.fastjson.JSONObject uiHistoryPage(String sessionId, String before, int limit,
            java.util.function.Supplier<com.alibaba.fastjson.JSONObject> projection) {
        Path directory = sessionBaseDir.resolve(sessionId).normalize();
        if (!directory.startsWith(sessionBaseDir.normalize())) throw new IllegalArgumentException("会话位置无效");
        ensureCurrentUiIdentities();
        StringBuilder version = new StringBuilder("v1:");
        try {
            if (Files.isDirectory(directory)) try (Stream<Path> files = Files.list(directory)) {
                for (Path path : files.filter(p -> p.getFileName().toString().startsWith("turn_") && p.toString().endsWith(".json")).sorted().collect(java.util.stream.Collectors.toList())) {
                    version.append(path.getFileName()).append(':').append(Files.size(path)).append(':').append(Files.getLastModifiedTime(path).toMillis()).append(';');
                }
            }
        } catch (IOException e) { throw new IllegalStateException("会话历史读取失败", e); }
        String memory = PRETTY_GSON.toJson(currentTurn) + PRETTY_GSON.toJson(acceptedUiMessages) + PRETTY_GSON.toJson(streamingAssistant);
        version.append(UUID.nameUUIDFromBytes(memory.getBytes(StandardCharsets.UTF_8)));
        return uiPages.computeIfAbsent(sessionId, id -> new com.mola.cmd.proxy.app.acp.common.ChatHistoryPages(directory.resolve("ui-message-pages"), sessionBaseDir + "\n" + id))
                .page(version.toString(), projection, before, limit);
    }

    public synchronized void acceptUiMessage(String id, String content, List<String> attachments) {
        if (id != null) acceptedUiMessages.put(id,
                new ContextMessage(ContextMessage.Role.USER, content, ContextMessage.UserOrigin.USER, attachments)
                        .withIdentity(id, 1L));
    }


    /** UI snapshot includes partial output; model history remains unchanged. */
    public synchronized List<ContextMessage> getUiHistory(String sessionId) {
        List<ContextMessage> result = new ArrayList<>(getFullHistory(sessionId));
        result.addAll(acceptedUiMessages.values());
        if (streamingAssistant != null) result.add(streamingAssistant);
        return result;
    }

    /** Preserve an interrupted partial reply without carrying it into the next turn. */
    public synchronized void finishUiAssistant() {
        if (streamingAssistant != null) {
            currentTurn.add(streamingAssistant);
            streamingAssistant = null;
        }
    }

    public synchronized ContextMessage appendUiAssistant(String text) {
        String id = streamingAssistant == null ? java.util.UUID.randomUUID().toString()
                : streamingAssistant.getMessageId();
        String content = (streamingAssistant == null ? "" : streamingAssistant.getContent()) + text;
        streamingAssistant = new ContextMessage(ContextMessage.Role.ASSISTANT, content)
                .withIdentity(id, content.length());
        return streamingAssistant;
    }

    public synchronized void addUserMessage(String content, ContextMessage.UserOrigin origin,
                                            List<String> attachments, String messageId) {
        if (messageId != null) acceptedUiMessages.remove(messageId);
        currentTurn.add(new ContextMessage(ContextMessage.Role.USER, content, origin, attachments)
                .withIdentity(messageId, 1L));
    }

    /** 累积的文件绝对路径（去重） */
    private final LinkedHashSet<String> fileAbsolutePaths = new LinkedHashSet<>();

    /** turn 计数器 */
    private final AtomicInteger turnCounter = new AtomicInteger(0);

    /** turn 完成回调，用于每 N 轮触发记忆提取等外部逻辑 */
    private Runnable onTurnFlushed;

    /**
     * @param robotName 机器人名称，用于按 robot 隔离 session 存储
     */
    public ConversationHistoryManager(String robotName) {
        String dirName = PathUtils.sanitizePath(robotName);
        this.sessionBaseDir = SESSION_ROOT_DIR.resolve(dirName);
    }

    /**
     * 按显式 Client Identity 隔离会话目录。
     * <p>
     * MAIN 继续沿用历史兼容行为：将整个 robot namespace 清洗为单目录。
     * TEAM 使用受校验的分层 namespace，例如 {@code team/{teamId}/{teamMemberId}}。
     */
    public ConversationHistoryManager(AcpClientIdentity identity) {
        this(identity, SESSION_ROOT_DIR);
    }

    /** 使用显式 session 根目录，供隔离环境和测试使用。 */
    public ConversationHistoryManager(AcpClientIdentity identity, Path sessionRootDir) {
        Objects.requireNonNull(identity, "identity");
        this.sessionBaseDir = resolveHistoryNamespace(identity, sessionRootDir);
    }

    static Path resolveHistoryNamespace(AcpClientIdentity identity) {
        return resolveHistoryNamespace(identity, SESSION_ROOT_DIR);
    }

    private static Path resolveHistoryNamespace(AcpClientIdentity identity,
                                                Path sessionRootDir) {
        Path root = Objects.requireNonNull(sessionRootDir, "sessionRootDir")
                .toAbsolutePath().normalize();
        if (!identity.isTeam()
                && identity.getSurface() != ClientSurface.STARWEAVE) {
            String dirName = PathUtils.sanitizePath(identity.getHistoryNamespace());
            if (dirName.isEmpty()) {
                throw new IllegalArgumentException("historyNamespace 清洗后不能为空");
            }
            return root.resolve(dirName).normalize();
        }

        String namespace = identity.getHistoryNamespace().replace('\\', '/');
        if (namespace.startsWith("/") || namespace.matches("^[a-zA-Z]:.*")) {
            throw new IllegalArgumentException("分层 historyNamespace 不能是绝对路径: " + namespace);
        }

        String[] segments = namespace.split("/", -1);
        if (segments.length < 2) {
            throw new IllegalArgumentException("分层 historyNamespace 必须是相对路径: " + namespace);
        }

        Path relative = Paths.get("");
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("分层 historyNamespace 包含非法路径段: " + namespace);
            }
            if (!segment.matches("[a-zA-Z0-9._-]+")) {
                throw new IllegalArgumentException(
                        "分层 historyNamespace 路径段只允许字母、数字、._-: " + segment);
            }
            relative = relative.resolve(segment);
        }

        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("TEAM historyNamespace 越出 session 根目录: " + namespace);
        }
        return resolved;
    }

    Path getSessionBaseDir() {
        return sessionBaseDir;
    }

    /**
     * 设置 turn 完成回调。每次 flushTurn 成功落盘后触发。
     */
    public void setOnTurnFlushed(Runnable callback) {
        this.onTurnFlushed = callback;
    }

    /**
     * 获取当前已完成的 turn 数。
     */
    public int getTurnCount() {
        return turnCounter.get();
    }

    /** Returns the last persisted message time for a session, or {@code 0}. */
    public long getLastMessageAt(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) return 0L;
        Path sessionDir = sessionBaseDir.resolve(sessionId);
        if (!Files.isDirectory(sessionDir)) return 0L;
        try (Stream<Path> files = Files.list(sessionDir)) {
            return files.filter(path -> path.getFileName().toString().matches("turn_\\d{4}\\.json"))
                    .mapToLong(path -> {
                        try { return Files.getLastModifiedTime(path).toMillis(); }
                        catch (IOException ignored) { return 0L; }
                    })
                    .max().orElse(0L);
        } catch (IOException e) {
            logger.warn("读取会话最后消息时间失败, sessionId={}", sessionId, e);
            return 0L;
        }
    }

    // ==================== 消息收集 ====================

    /** 记录一条用户消息 */
    public synchronized void addUserMessage(String content) {
        addUserMessage(content, ContextMessage.UserOrigin.USER);
    }

    /** Records a model-visible prompt while retaining whether it is UI-visible user input. */
    public synchronized void addUserMessage(String content, ContextMessage.UserOrigin origin) {
        currentTurn.add(new ContextMessage(ContextMessage.Role.USER, content, origin));
    }

    /** Records a user prompt together with the original names of this turn's attachments. */
    public synchronized void addUserMessage(String content, ContextMessage.UserOrigin origin,
                                            List<String> attachments) {
        currentTurn.add(new ContextMessage(
                ContextMessage.Role.USER, content, origin, attachments));
    }

    /** 记录一条 agent 回答 */
    public synchronized void addAssistantMessage(String content) {
        ContextMessage message = new ContextMessage(ContextMessage.Role.ASSISTANT, content);
        if (streamingAssistant != null) {
            message.withIdentity(streamingAssistant.getMessageId(), content.length() + 1L);
            streamingAssistant = null;
        }
        currentTurn.add(message);
    }

    /** 记录一条工具调用结果 */
    public synchronized void addToolMessage(String toolCallId, String toolName, String status,
                               JsonObject rawInput, JsonObject rawOutput) {
        currentTurn.add(new ContextMessage(toolCallId, toolName, status, rawInput, rawOutput));
    }

    /** Records a UI-only business event alongside the turn without changing model context. */
    public synchronized void addEventMessage(String eventType, JsonObject eventData) {
        currentTurn.add(ContextMessage.event(eventType, eventData));
    }

    /**
     * 将文件内容写入会话的 files 目录，并记录绝对路径。
     * 支持两种模式：value 为 URL 时通过 HTTP 下载，否则按 base64 解码。
     *
     * @param sessionId 当前会话 ID
     * @param files     文件列表，每个 Map 的 key 为文件名，value 为下载URL或base64内容
     * @return 本次实际保存的绝对路径；同名文件会分配不冲突的存储名
     */
    public List<String> saveFiles(String sessionId, List<Map<String, String>> files) {
        if (files == null || files.isEmpty() || sessionId == null) {
            return Collections.emptyList();
        }
        List<String> savedPaths = new ArrayList<>();
        try {
            Path filesDir = sessionBaseDir.resolve(sessionId).resolve("files");
            Files.createDirectories(filesDir);
            for (Map<String, String> fileMap : files) {
                for (Map.Entry<String, String> entry : fileMap.entrySet()) {
                    String fileName = entry.getKey();
                    String content = entry.getValue();
                    if (fileName == null || fileName.isEmpty() || content == null) continue;
                    byte[] fileBytes;
                    if (content.startsWith("http://") || content.startsWith("https://")) {
                        fileBytes = downloadFile(content);
                    } else {
                        fileBytes = Base64.getDecoder().decode(content);
                    }
                    if (fileBytes != null) {
                        Path filePath = writeUniqueFile(filesDir, fileName, fileBytes);
                        String absolutePath = filePath.toAbsolutePath().normalize().toString();
                        fileAbsolutePaths.add(absolutePath);
                        savedPaths.add(absolutePath);
                        logger.info("文件已保存: {}", absolutePath);
                    }
                }
            }
        } catch (IOException e) {
            logger.error("文件保存失败, sessionId={}", sessionId, e);
        }
        return Collections.unmodifiableList(savedPaths);
    }

    private static Path writeUniqueFile(Path directory, String originalName,
                                        byte[] content) throws IOException {
        String name = safeStorageFileName(originalName);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        int suffix = 1;
        while (true) {
            String candidateName = suffix == 1
                    ? name : base + "-" + suffix + extension;
            Path candidate = directory.resolve(candidateName).normalize();
            if (!directory.equals(candidate.getParent())) {
                throw new IOException("invalid attachment filename");
            }
            try {
                return Files.write(candidate, content, StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException collision) {
                suffix++;
            }
        }
    }

    private static String safeStorageFileName(String input) {
        String name = input.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        StringBuilder cleaned = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char value = name.charAt(i);
            cleaned.append(value < 32 || "<>:\"/\\|?*".indexOf(value) >= 0
                    ? '_' : value);
        }
        name = cleaned.toString().trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            name = "attachment";
        }
        return name.length() <= 160 ? name : name.substring(0, 160);
    }

    private byte[] downloadFile(String url) {
        try {
            java.net.URI uri = encodeUri(url);
            java.net.HttpURLConnection conn;
            if (url.startsWith("https://")) {
                javax.net.ssl.HttpsURLConnection httpsConn =
                        (javax.net.ssl.HttpsURLConnection) uri.toURL().openConnection();
                httpsConn.setSSLSocketFactory(getTrustAllSslSocketFactory());
                httpsConn.setHostnameVerifier((hostname, session) -> true);
                conn = httpsConn;
            } else {
                conn = (java.net.HttpURLConnection) uri.toURL().openConnection();
            }
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);
            conn.setRequestMethod("GET");
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                logger.error("文件下载失败, url={}, responseCode={}", url, responseCode);
                return null;
            }
            try (java.io.InputStream is = conn.getInputStream();
                 java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
                return baos.toByteArray();
            }
        } catch (Exception e) {
            logger.error("文件下载异常, url={}", url, e);
      return null;
        }
    }

    private java.net.URI encodeUri(String rawUrl) throws Exception {
        java.net.URL parsed = new java.net.URL(rawUrl);
        java.net.URI uri = new java.net.URI(parsed.getProtocol(), null, parsed.getHost(),
                parsed.getPort(), parsed.getPath(), parsed.getQuery(), null);
        return new java.net.URI(uri.toASCIIString());
    }

    private static javax.net.ssl.SSLSocketFactory getTrustAllSslSocketFactory() {
        try {
            javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                        public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                    }
            };
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            return sc.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException("初始化 TrustAll SSLSocketFactory 失败", e);
        }
    }

    /**
     * 获取所有已保存文件的绝对路径集合（只读）。
     */
    public Set<String> getFileAbsolutePaths() {
        return Collections.unmodifiableSet(fileAbsolutePaths);
    }

    /** Registers files that were already safely staged by an external channel. */
    public List<String> registerLocalFiles(Collection<String> localFiles) {
        if (localFiles == null) return Collections.emptyList();
        List<String> registered = new ArrayList<>();
        for (String value : localFiles) {
            if (value == null || value.trim().isEmpty()) continue;
            Path path = Paths.get(value).toAbsolutePath().normalize();
            if (Files.isRegularFile(path)) {
                String absolutePath = path.toString();
                fileAbsolutePaths.add(absolutePath);
                registered.add(absolutePath);
            }
        }
        return Collections.unmodifiableList(registered);
    }

    // ==================== 落盘 ====================

    /**
     * 将当前 turn 的消息落盘并清空内存。
     *
     * @param sessionId 当前会话 ID
     */
    public synchronized void flushTurn(String sessionId) {
        if (currentTurn.isEmpty() || sessionId == null) {
            return;
        }
        try {
            Path sessionDir = sessionBaseDir.resolve(sessionId);
            Files.createDirectories(sessionDir);

            int turn = turnCounter.getAndIncrement();
            Path turnFile = sessionDir.resolve(String.format("turn_%04d.json", turn));

            JsonArray array = new JsonArray();
            for (ContextMessage msg : currentTurn) {
                array.add(serializeMessage(msg));
            }

            Files.write(turnFile, PRETTY_GSON.toJson(array).getBytes(StandardCharsets.UTF_8));
            logger.info("会话上下文已落盘: {}, 消息数={}", turnFile, currentTurn.size());
        } catch (IOException e) {
            logger.error("会话上下文落盘失败, sessionId={}", sessionId, e);
        } finally {
            currentTurn.clear();
        }

        // 通知外部回调（如每 N 轮触发记忆提取）
        if (onTurnFlushed != null) {
            try {
                onTurnFlushed.run();
            } catch (Exception e) {
                logger.warn("onTurnFlushed 回调执行失败", e);
            }
        }
    }

    /**
     * 强制落盘（用于 close 等场景的兜底）。
     */
    public synchronized void forceFlush(String sessionId) {
        if (!currentTurn.isEmpty()) {
            flushTurn(sessionId);
        }
    }

    /**
     * 持久化某个 session 尚待执行的全量记忆提取。
     *
     * <p>标记与 turn 文件放在同一 session 目录，不复制对话正文。重复写入会用更大的
     * turnCount 覆盖旧值，因而进程重启和重复 close 都是幂等的。</p>
     */
    public boolean markMemoryExtractionPending(String sessionId, int turnCount) {
        if (sessionId == null || sessionId.trim().isEmpty() || turnCount <= 0) {
            return false;
        }
        Path sessionDir = sessionBaseDir.resolve(sessionId);
        Path marker = sessionDir.resolve(MEMORY_PENDING_FILE);
        Path temporary = sessionDir.resolve(MEMORY_PENDING_FILE + ".tmp");
        try {
            Files.createDirectories(sessionDir);
            int effectiveTurnCount = turnCount;
            PendingMemoryExtraction existing = readPendingMarker(marker, sessionId);
            if (existing != null) {
                effectiveTurnCount = Math.max(effectiveTurnCount, existing.getTurnCount());
            }
            JsonObject json = new JsonObject();
            json.addProperty("sessionId", sessionId);
            json.addProperty("turnCount", effectiveTurnCount);
            json.addProperty("updatedAt", System.currentTimeMillis());
            Files.write(temporary,
                    PRETTY_GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
            }
            logger.info("已记录待恢复记忆提取, sessionId={}, turnCount={}",
                    sessionId, effectiveTurnCount);
            return true;
        } catch (Exception e) {
            logger.warn("记录待恢复记忆提取失败, sessionId={}", sessionId, e);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
            return false;
        }
    }

    /** 列出当前 history namespace 中全部待恢复的记忆提取任务。 */
    public List<PendingMemoryExtraction> listPendingMemoryExtractions() {
        if (!Files.isDirectory(sessionBaseDir)) {
            return Collections.emptyList();
        }
        List<PendingMemoryExtraction> result = new ArrayList<>();
        try (Stream<Path> directories = Files.list(sessionBaseDir)) {
            directories.filter(Files::isDirectory).forEach(sessionDir -> {
                String sessionId = sessionDir.getFileName().toString();
                PendingMemoryExtraction pending = readPendingMarker(
                        sessionDir.resolve(MEMORY_PENDING_FILE), sessionId);
                if (pending != null) {
                    result.add(pending);
                }
            });
        } catch (IOException e) {
            logger.warn("遍历待恢复记忆提取失败: {}", sessionBaseDir, e);
        }
        result.sort(Comparator.comparing(PendingMemoryExtraction::getSessionId));
        return Collections.unmodifiableList(result);
    }

    /**
     * 在对应版本的提取真正成功后清除 pending 标记。
     * 若关机期间已写入更新的 turnCount，则旧任务不能误删新标记。
     */
    public boolean clearMemoryExtractionPending(String sessionId,
                                                int completedTurnCount) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return false;
        }
        Path marker = sessionBaseDir.resolve(sessionId).resolve(MEMORY_PENDING_FILE);
        PendingMemoryExtraction current = readPendingMarker(marker, sessionId);
        if (current == null || current.getTurnCount() > completedTurnCount) {
            return false;
        }
        try {
            boolean deleted = Files.deleteIfExists(marker);
            if (deleted) {
                logger.info("待恢复记忆提取已完成, sessionId={}, turnCount={}",
                        sessionId, completedTurnCount);
            }
            return deleted;
        } catch (IOException e) {
            logger.warn("清除待恢复记忆提取失败, sessionId={}", sessionId, e);
            return false;
        }
    }

    private PendingMemoryExtraction readPendingMarker(Path marker,
                                                      String fallbackSessionId) {
        if (!Files.isRegularFile(marker)) {
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(new String(
                    Files.readAllBytes(marker), StandardCharsets.UTF_8)).getAsJsonObject();
            String sessionId = json.has("sessionId")
                    ? json.get("sessionId").getAsString() : fallbackSessionId;
            int pendingTurnCount = json.has("turnCount")
                    ? json.get("turnCount").getAsInt() : 0;
            if (sessionId == null || sessionId.trim().isEmpty()
                    || pendingTurnCount <= 0) {
                logger.warn("忽略无效的待恢复记忆提取标记: {}", marker);
                return null;
            }
            return new PendingMemoryExtraction(sessionId, pendingTurnCount);
        } catch (Exception e) {
            logger.warn("读取待恢复记忆提取标记失败: {}", marker, e);
            return null;
        }
    }

    public static final class PendingMemoryExtraction {
        private final String sessionId;
        private final int turnCount;

        public PendingMemoryExtraction(String sessionId, int turnCount) {
            this.sessionId = sessionId;
            this.turnCount = turnCount;
        }

        public String getSessionId() {
            return sessionId;
        }

        public int getTurnCount() {
            return turnCount;
        }
    }

    // ==================== 加载 ====================

    /**
     * 获取完整的会话上下文（磁盘 + 内存未落盘部分）。
     */
    public synchronized List<ContextMessage> getFullHistory(String sessionId) {
        List<ContextMessage> result = new ArrayList<>();
        if (sessionId != null) {
            Path sessionDir = sessionBaseDir.resolve(sessionId);
            if (Files.isDirectory(sessionDir)) {
                try (Stream<Path> turnFiles = Files.list(sessionDir)) {
                    turnFiles
                            .filter(p -> p.getFileName().toString().startsWith("turn_")
                                    && p.getFileName().toString().endsWith(".json"))
                            .sorted()
                            .forEach(turnFile -> {
                                try {
                                    String content = new String(Files.readAllBytes(turnFile), StandardCharsets.UTF_8);
                                    JsonArray array = JsonParser.parseString(content).getAsJsonArray();
                                    int messageIndex = 0;
                                    for (JsonElement elem : array) {
                                        ContextMessage message = deserializeMessage(elem.getAsJsonObject());
                                        if (message.getMessageId() == null) message.withIdentity("history:" + sessionId + ":" + turnFile.getFileName() + ":" + messageIndex, message.getRevision());
                                        result.add(message); messageIndex++;
                                    }
                                } catch (IOException e) {
                                    logger.warn("读取 turn 文件失败: {}", turnFile, e);
                                }
                            });
                } catch (IOException e) {
                    logger.warn("遍历 session 目录失败: {}", sessionDir, e);
                }
            }
        }
        // 首次展示时补稳定标识，随后 flush 持久化同一 ID，游标不会随落盘失效。
        ensureCurrentUiIdentities();
        result.addAll(currentTurn);
        return Collections.unmodifiableList(result);
    }

    private void ensureCurrentUiIdentities() {
        for (ContextMessage message : currentTurn) {
            if (message.getMessageId() == null) message.withIdentity("ui:" + UUID.randomUUID(), message.getRevision());
        }
    }

    /**
     * 获取当前 turn 内存中尚未落盘的消息（只读）。
     */
    public synchronized List<ContextMessage> getCurrentTurn() {
        return Collections.unmodifiableList(new ArrayList<>(currentTurn));
    }

    /**
     * 从磁盘加载已保存的文件路径列表。
     */
    public List<String> loadFilePaths(String sessionId) {
        if (sessionId == null) return Collections.emptyList();
        Path filesDir = sessionBaseDir.resolve(sessionId).resolve("files");
        if (!Files.isDirectory(filesDir)) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(filesDir)) {
            files.forEach(p -> result.add(p.toAbsolutePath().toString()));
        } catch (IOException e) {
            logger.warn("读取文件目录失败: {}", filesDir, e);
        }
        return result;
    }

    /** Server-owned root for session resource APIs; rejects caller path syntax. */
    public Path getSessionFilesDirectory(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()
                || sessionId.contains("/") || sessionId.contains("\\")
                || ".".equals(sessionId) || "..".equals(sessionId)) {
            throw new IllegalArgumentException("invalid sessionId");
        }
        Path resolved = sessionBaseDir.resolve(sessionId).resolve("files").normalize();
        if (!resolved.startsWith(sessionBaseDir.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("sessionId escapes history root");
        }
        return resolved;
    }

    /**
     * 恢复会话的内存状态（用于 session/load 后）。
     * <p>
     * 从磁盘恢复 fileAbsolutePaths 和 turnCounter，
     * 使 historyManager 的状态与已落盘的数据一致。
     */
    public void restoreState(String sessionId) {
        if (sessionId == null) return;

        // 恢复文件路径
        fileAbsolutePaths.addAll(loadFilePaths(sessionId));

        // 恢复 turn 计数器：统计已有的 turn 文件数
        Path sessionDir = sessionBaseDir.resolve(sessionId);
        if (Files.isDirectory(sessionDir)) {
            try (Stream<Path> turnFiles = Files.list(sessionDir)) {
                long turnCount = turnFiles
                        .filter(p -> p.getFileName().toString().startsWith("turn_")
                                && p.getFileName().toString().endsWith(".json"))
                        .count();
                turnCounter.set((int) turnCount);
            } catch (IOException e) {
                logger.warn("恢复 turnCounter 失败: {}", sessionDir, e);
            }
        }

        logger.info("会话状态已恢复, sessionId={}, files={}, turnCounter={}",
                sessionId, fileAbsolutePaths.size(), turnCounter.get());
    }


    /**
     * 重置状态（用于 session 重建等场景）。
     */
    public synchronized void reset() {
        streamingAssistant = null;
        acceptedUiMessages.clear();
        currentTurn.clear();
        fileAbsolutePaths.clear();
        turnCounter.set(0);
    }

    /**
     * 查找当前 workspace 下最新的 sessionId。
     * <p>
     * 遍历 sessionBaseDir 下的所有子目录，按目录修改时间倒序排列，
     * 返回最新的目录名作为 sessionId。
     *
     * @return 最新的 sessionId，如果不存在则返回 null
     */
    
        /**
         * 查找当前 workspace 下最新的 sessionId。
         * <p>
         * 优先读取 last_session 标记文件（session/new 成功后写入），
         * 如果标记文件不存在，则回退到按目录修改时间查找。
         *
         * @return 最新的 sessionId，如果不存在则返回 null
         */
        public String findLatestSessionId() {
            // 优先读取标记文件
            Path marker = sessionBaseDir.resolve("last_session");
            if (Files.isRegularFile(marker)) {
                try {
                    String id = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim();
                    if (!id.isEmpty()) {
                        logger.info("从标记文件读取到最新 sessionId: {}", id);
                        return id;
                    }
                } catch (IOException e) {
                    logger.warn("读取 last_session 标记文件失败: {}", marker, e);
                }
            }

            // 回退：按目录修改时间查找
            if (!Files.isDirectory(sessionBaseDir)) {
                return null;
            }
            try (Stream<Path> sessions = Files.list(sessionBaseDir)) {
                return sessions
                        .filter(Files::isDirectory)
                        .filter(ConversationHistoryManager::containsTurnFile)
                        .max(Comparator.comparingLong(p -> {
                            try {
                                return Files.getLastModifiedTime(p).toMillis();
                            } catch (IOException e) {
                                return 0L;
                            }
                        }))
                        .map(p -> p.getFileName().toString())
                        .orElse(null);
            } catch (IOException e) {
                logger.warn("查找最新 sessionId 失败: {}", sessionBaseDir, e);
                return null;
            }
        }

        /**
         * 记录当前活跃的 sessionId 到标记文件。
         * 在 session/new 成功后立即调用，确保重启时能恢复到正确的会话。
         */
        public void saveLastSessionId(String sessionId) {
            if (sessionId == null) return;
            try {
                Files.createDirectories(sessionBaseDir);
                Path marker = sessionBaseDir.resolve("last_session");
                Files.write(marker, sessionId.getBytes(StandardCharsets.UTF_8));
                logger.info("已记录最新 sessionId: {}", sessionId);
            } catch (IOException e) {
                logger.warn("写入 last_session 标记文件失败, sessionId={}", sessionId, e);
            }
        }



    // ==================== 会话列表 ====================

    public static class SessionSummary {
        private final String sessionId;
        private final String preview;
        private final String lastModified;

        public SessionSummary(String sessionId, String preview, String lastModified) {
            this.sessionId = sessionId;
            this.preview = preview;
            this.lastModified = lastModified;
        }

        public String getSessionId() { return sessionId; }
        public String getPreview() { return preview; }
        public String getLastModified() { return lastModified; }
    }

    private static final Set<String> GREETINGS = new HashSet<>(Arrays.asList(
            "你好", "hi", "hello", "在吗", "hey", "嗨", "在不在", "您好"));

    /**
     * 列出最近的会话摘要。跳过纯寒暄，取第一条有实质内容的用户消息；
     * 若全是寒暄则取第一条 ASSISTANT 回复。截断到 30 字符。
     */
    public List<SessionSummary> listRecentSessions(int limit) {
        if (!Files.isDirectory(sessionBaseDir)) return Collections.emptyList();
        try (Stream<Path> sessions = Files.list(sessionBaseDir)) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
            List<Path> dirs = sessions
                    .filter(Files::isDirectory)
                    .filter(ConversationHistoryManager::containsTurnFile)
                    .sorted((a, b) -> {
                        try { return Long.compare(Files.getLastModifiedTime(b).toMillis(), Files.getLastModifiedTime(a).toMillis()); }
                        catch (IOException e) { return 0; }
                    })
                    .limit(limit)
                    .collect(java.util.stream.Collectors.toList());

            List<SessionSummary> result = new ArrayList<>();
            for (Path dir : dirs) {
                String sid = dir.getFileName().toString();
                String modified = sdf.format(new java.util.Date(Files.getLastModifiedTime(dir).toMillis()));
                String preview = extractPreview(dir);
                result.add(new SessionSummary(sid, preview, modified));
            }
            return result;
        } catch (IOException e) {
            logger.warn("listRecentSessions 失败", e);
            return Collections.emptyList();
        }
    }

    private String extractPreview(Path sessionDir) {
        try (Stream<Path> files = Files.list(sessionDir)) {
            List<Path> turnFiles = files
                    .filter(p -> p.getFileName().toString().startsWith("turn_") && p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());

            String firstAssistant = null;
            for (Path tf : turnFiles) {
                JsonArray arr = JsonParser.parseString(new String(Files.readAllBytes(tf), StandardCharsets.UTF_8)).getAsJsonArray();
                for (JsonElement el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    String role = obj.get("role").getAsString();
                    String content = obj.has("content") ? obj.get("content").getAsString() : null;
                    if (content == null || content.trim().isEmpty()) continue;
                    if ("USER".equals(role) && !GREETINGS.contains(content.trim().toLowerCase())) {
                        return sanitizePreview(content.trim(), 60);
                    }
                    if ("ASSISTANT".equals(role) && firstAssistant == null) {
                        firstAssistant = content.trim();
                    }
                }
            }
            return firstAssistant != null ? sanitizePreview(firstAssistant, 80) : "(空会话)";
        } catch (Exception e) {
            return "(读取失败)";
        }
    }

    private static boolean containsTurnFile(Path sessionDir) {
        try (Stream<Path> files = Files.list(sessionDir)) {
            return files.anyMatch(file -> {
                String name = file.getFileName().toString();
                return name.startsWith("turn_") && name.endsWith(".json");
            });
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 对 preview 文本进行清洗：
     * 1. 换行符替换为分号
     * 2. 转义 HTML 标签和 Markdown 特殊字符
     * 3. 截取至指定长度
     */
    private static String sanitizePreview(String s, int max) {
        // 换行符替换为分号
        String result = s.replaceAll("[\\r\\n]+", "; ");
        // 转义 HTML 标签
        result = result.replace("&", "&amp;")
                       .replace("<", "&lt;")
                       .replace(">", "&gt;")
                       .replace("\"", "&quot;");
        // 转义 Markdown 特殊字符: * _ ` # ~ [ ] |
        result = result.replaceAll("([*_`#~\\[\\]|])", "\\\\$1");
        return truncate(result, max);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ==================== 内部方法 ====================

    private JsonObject serializeMessage(ContextMessage msg) {
        JsonObject obj = new JsonObject();
        obj.addProperty("role", msg.getRole().name());
        if (msg.getMessageId() != null) obj.addProperty("messageId", msg.getMessageId());
        obj.addProperty("revision", msg.getRevision());
        if (msg.getRole() == ContextMessage.Role.TOOL) {
            obj.addProperty("toolCallId", msg.getToolCallId());
            obj.addProperty("toolName", msg.getToolName());
            obj.addProperty("status", msg.getStatus());
            if (msg.getRawInput() != null) obj.add("rawInput", msg.getRawInput());
            if (msg.getRawOutput() != null) obj.add("rawOutput", msg.getRawOutput());
        } else if (msg.getRole() == ContextMessage.Role.EVENT) {
            obj.addProperty("eventType", msg.getEventType());
            obj.add("eventData", msg.getEventData());
        } else {
            obj.addProperty("content", msg.getContent());
            if (msg.getRole() == ContextMessage.Role.USER && msg.getUserOrigin() != null) {
                obj.addProperty("userOrigin", msg.getUserOrigin().name());
            }
            if (msg.getRole() == ContextMessage.Role.USER
                    && !msg.getAttachments().isEmpty()) {
                JsonArray attachments = new JsonArray();
                for (String fileName : msg.getAttachments()) {
                    attachments.add(fileName);
                }
                obj.add("attachments", attachments);
            }
        }
        return obj;
    }

    private ContextMessage deserializeMessage(JsonObject obj) {
        return deserializeMessageBody(obj).withIdentity(
                obj.has("messageId") ? obj.get("messageId").getAsString() : null,
                obj.has("revision") ? obj.get("revision").getAsLong() : 0L);
    }

    private ContextMessage deserializeMessageBody(JsonObject obj) {
        ContextMessage.Role role = ContextMessage.Role.valueOf(obj.get("role").getAsString());
        if (role == ContextMessage.Role.TOOL) {
            return new ContextMessage(
                    obj.has("toolCallId") ? obj.get("toolCallId").getAsString() : null,
                    obj.has("toolName") ? obj.get("toolName").getAsString() : null,
                    obj.has("status") ? obj.get("status").getAsString() : null,
                    obj.has("rawInput") ? obj.getAsJsonObject("rawInput") : null,
                    obj.has("rawOutput") ? obj.getAsJsonObject("rawOutput") : null
            );
        }
        if (role == ContextMessage.Role.EVENT) {
            return ContextMessage.event(
                    obj.has("eventType") ? obj.get("eventType").getAsString() : "UNKNOWN",
                    obj.has("eventData") && obj.get("eventData").isJsonObject()
                            ? obj.getAsJsonObject("eventData") : new JsonObject());
        }
        String content = obj.has("content") ? obj.get("content").getAsString() : "";
        if (role != ContextMessage.Role.USER) return new ContextMessage(role, content);
        ContextMessage.UserOrigin origin = ContextMessage.UserOrigin.USER;
        if (obj.has("userOrigin")) {
            try {
                origin = ContextMessage.UserOrigin.valueOf(
                        obj.get("userOrigin").getAsString());
            } catch (IllegalArgumentException ignored) {
                // Old or unknown values remain visible rather than silently losing user data.
            }
        }
        List<String> attachments = new ArrayList<>();
        if (obj.has("attachments") && obj.get("attachments").isJsonArray()) {
            for (JsonElement attachment : obj.getAsJsonArray("attachments")) {
                if (attachment != null && attachment.isJsonPrimitive()) {
                    attachments.add(attachment.getAsString());
                }
            }
        }
        return new ContextMessage(role, content, origin, attachments);
    }
}
