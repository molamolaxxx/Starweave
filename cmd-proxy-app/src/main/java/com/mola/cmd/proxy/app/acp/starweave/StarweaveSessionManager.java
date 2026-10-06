package com.mola.cmd.proxy.app.acp.starweave;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry;
import com.mola.cmd.proxy.app.acp.acpclient.AgentAddress;
import com.mola.cmd.proxy.app.acp.acpclient.MainSessionApplicationService;
import com.mola.cmd.proxy.app.acp.acpclient.PromptCommandResult;
import com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager;
import com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Owns environment-local Starweave MAIN session lifecycle. */
public final class StarweaveSessionManager {

    @FunctionalInterface
    public interface RobotResolver {
        AcpRobotParam resolve(String robotName);
    }

    @FunctionalInterface
    public interface FeatureInitializer {
        void initialize(String groupId, AcpClient client, AcpRobotParam robot) throws Exception;
    }

    private final String instanceId;
    private final AcpClientRegistry registry;
    private final MainSessionApplicationService sessionService;
    private final RobotResolver robotResolver;
    private final FeatureInitializer featureInitializer;
    private final StarweaveSessionIndex index;
    private final StarweaveSessionEventStore eventStore;
    private final StarweaveUploadStore uploadStore;
    private final Map<String, StarweaveTurnTracker> turnTrackers = new ConcurrentHashMap<>();
    private final Map<String, Object> historyProjectionLocks = new ConcurrentHashMap<>();
    private final Map<String, Object> openLocks = new ConcurrentHashMap<>();

    public StarweaveSessionManager(String instanceId, AcpClientRegistry registry,
                                   RobotResolver robotResolver,
                                   FeatureInitializer featureInitializer) {
        this(instanceId, registry, robotResolver, featureInitializer,
                new StarweaveSessionIndex(), new StarweaveSessionEventStore());
    }

    StarweaveSessionManager(String instanceId, AcpClientRegistry registry,
                            RobotResolver robotResolver,
                            FeatureInitializer featureInitializer,
                            StarweaveSessionIndex index,
                            StarweaveSessionEventStore eventStore) {
        this(instanceId, registry, robotResolver, featureInitializer, index,
                eventStore, new StarweaveUploadStore());
    }

    StarweaveSessionManager(String instanceId, AcpClientRegistry registry,
                            RobotResolver robotResolver,
                            FeatureInitializer featureInitializer,
                            StarweaveSessionIndex index,
                            StarweaveSessionEventStore eventStore,
                            StarweaveUploadStore uploadStore) {
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.sessionService = new MainSessionApplicationService(registry);
        this.robotResolver = Objects.requireNonNull(robotResolver, "robotResolver");
        this.featureInitializer = Objects.requireNonNull(featureInitializer, "featureInitializer");
        this.index = Objects.requireNonNull(index, "index");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.uploadStore = Objects.requireNonNull(uploadStore, "uploadStore");
    }

    public JSONObject open(String robotName) throws Exception {
        AcpRobotParam robot = requireRobot(robotName);
        AcpClientIdentity identity = StarweaveIdentity.identity(instanceId, robot.getName());
        String groupId = identity.getLogicalId();
        synchronized (openLocks.computeIfAbsent(groupId, ignored -> new Object())) {
            AcpClient existing = registry.getClient(groupId);
            if (existing != null) {
                if (!existing.getClientIdentity().isStarweave()) {
                    throw new IllegalStateException("groupId is occupied by a non-Starweave client");
                }
                StarweaveSessionIndex.Entry entry = index.get(groupId);
                if (entry == null || !entry.isActive()
                        || !Objects.equals(entry.getCurrentSessionId(), existing.getSessionId())) {
                    entry = index.activate(robot.getName(), groupId, existing.getSessionId());
                }
                return view(existing, entry);
            }

            StarweaveSessionIndex.Entry previous = index.get(groupId);
            boolean forceNew = previous == null || previous.isForceNewOnNextOpen()
                    || !previous.isActive();
            AcpClient created = sessionService.create(identity, robot.getWorkDir(), robot,
                    forceNew, clientInitializer(groupId, robot));
            StarweaveSessionIndex.Entry active = index.activate(
                    robot.getName(), groupId, created.getSessionId());
            eventStore.append(groupId, created.getSessionId(), active.getGeneration(),
                    "SESSION_STATE_CHANGED", statePayload(created));
            return view(created, active);
        }
    }

    /**
     * Restores an ACTIVE Starweave slot after its robot client was rebuilt.
     * A rename retires the old logical slot and opens the renamed robot as a
     * fresh slot; an ordinary refresh reloads the existing provider session.
     */
    public JSONObject reopenAfterRobotRefresh(String previousRobotName,
                                              String robotName) throws Exception {
        String previousName = requireText(previousRobotName, "previousRobotName");
        String currentName = requireText(robotName, "robotName");
        String previousGroupId = StarweaveIdentity.identity(
                instanceId, previousName).getLogicalId();
        StarweaveSessionIndex.Entry previous = index.get(previousGroupId);
        if (previous == null || !previous.isActive()) return null;

        if (!previousName.equals(currentName)) {
            registry.closeByGroupId(previousGroupId);
            turnTracker(previousGroupId).reset();
            StarweaveSessionIndex.Entry deleted = index.markDeleted(previousGroupId);
            JSONObject payload = new JSONObject(true);
            payload.put("reason", "ROBOT_REFRESH_RENAME");
            payload.put("robotName", currentName);
            eventStore.append(previousGroupId, previous.getCurrentSessionId(),
                    deleted.getGeneration(), "SESSION_DELETED", payload);
        }
        return open(currentName);
    }

    /**
     * Restarts every persisted ACTIVE slot after the runtime is rebuilt.
     * A broken or no-longer-eligible robot remains visible as CLOSED and does
     * not prevent the remaining Starweave sessions from being recovered.
     */
    public JSONObject recoverActiveSessions() {
        return recoverActiveSessions(groupId -> false);
    }

    public JSONObject recoverActiveSessions(Predicate<String> restoreSleeping) {
        Objects.requireNonNull(restoreSleeping, "restoreSleeping");
        JSONArray recovered = new JSONArray();
        JSONArray failed = new JSONArray();
        int attempted = 0;
        for (StarweaveSessionIndex.Entry entry : index.snapshot()) {
            if (!entry.isActive()) continue;
            attempted++;
            try {
                JSONObject view = open(entry.getRobotName());
                AcpClient client = registry.getClient(entry.getGroupId());
                if (client != null && restoreSleeping.test(entry.getGroupId())) {
                    client.restoreSleepAfterRestart();
                    view = view(client, index.get(entry.getGroupId()));
                }
                recovered.add(view);
            } catch (Exception error) {
                JSONObject failure = new JSONObject(true);
                failure.put("robotName", entry.getRobotName());
                failure.put("groupId", entry.getGroupId());
                failure.put("message", error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage());
                failed.add(failure);
            }
        }
        JSONObject result = new JSONObject(true);
        result.put("attempted", attempted);
        result.put("recoveredCount", recovered.size());
        result.put("failedCount", failed.size());
        result.put("recovered", recovered);
        result.put("failed", failed);
        return result;
    }

    public JSONArray list() {
        JSONArray result = new JSONArray();
        for (StarweaveSessionIndex.Entry entry : index.snapshot()) {
            if (!entry.isActive()) continue;
            AcpClient client = registry.getClient(entry.getGroupId());
            JSONObject item = client == null ? view(entry) : view(client, entry);
            result.add(item);
        }
        return result;
    }

    public JSONObject status(String groupId) {
        StarweaveSessionIndex.Entry entry = requireEntry(groupId);
        AcpClient client = registry.getClient(groupId);
        return client == null ? view(entry) : view(client, entry);
    }

    public JSONObject send(String groupId, String message, String expectedSessionId,
                           long expectedGeneration, String busyPolicy) {
        return send(groupId, message, expectedSessionId, expectedGeneration,
                busyPolicy, Collections.emptyList());
    }

    public JSONObject send(String groupId, String message, String expectedSessionId,
                           long expectedGeneration, String busyPolicy,
                           List<String> uploadIds) {
        return send(groupId, message, expectedSessionId, expectedGeneration, busyPolicy, uploadIds, null);
    }

    public JSONObject send(String groupId, String message, String expectedSessionId,
                           long expectedGeneration, String busyPolicy,
                           List<String> uploadIds, String clientMessageId) {
        StarweaveSessionIndex.Entry entry = requireCurrent(
                groupId, expectedSessionId, expectedGeneration);
        List<StarweaveUploadStore.ResolvedUpload> uploads = new java.util.ArrayList<>();
        List<Map<String, String>> files = new java.util.ArrayList<>();
        if (uploadIds != null) {
            if (uploadIds.size() > 10) throw new IllegalArgumentException("at most 10 uploads per message");
            for (String uploadId : uploadIds) {
                StarweaveUploadStore.ResolvedUpload upload = uploadStore.resolve(
                        uploadId, groupId, expectedSessionId, expectedGeneration);
                uploads.add(upload);
                Map<String, String> file = new java.util.LinkedHashMap<>();
                file.put(upload.fileName, java.util.Base64.getEncoder()
                        .encodeToString(upload.bytes));
                files.add(file);
            }
        }
        StarweaveTurnTracker turns = turnTracker(groupId);
        AcpClient beforeSend = registry.getClient(groupId);
        boolean sentWhileBusy = beforeSend != null
                && beforeSend.getState() == AbstractAcpClient.State.BUSY;
        String turnId = sentWhileBusy ? turns.beginPending() : turns.begin();
        PromptCommandResult result;
        try {
            result = sessionService.send(groupId, message,
                    files.isEmpty() ? null : files, busyPolicy);
        } catch (RuntimeException error) {
            turns.clear(turnId);
            throw error;
        }
        JSONObject response = commandResult(result);
        if (result.isAccepted()) {
            // 睡眠唤醒可能刚刚轮转到全新会话：事件必须落库到唤醒后的身份，
            // 否则用户消息会留在旧会话，且下一次发送命中过期 generation。
            StarweaveSessionIndex.Entry current = index.get(groupId);
            String eventSessionId = current != null && current.isActive()
                    ? current.getCurrentSessionId() : entry.getCurrentSessionId();
            long eventGeneration = current != null
                    ? current.getGeneration() : entry.getGeneration();
            JSONObject payload = new JSONObject(true);
            payload.put("content", message);
            payload.put("source", "STARWEAVE");
            payload.put("messageId", clientMessageId);
            JSONArray attachments = new JSONArray();
            for (StarweaveUploadStore.ResolvedUpload upload : uploads) {
                JSONObject attachment = new JSONObject(true);
                attachment.put("fileName", upload.fileName);
                attachment.put("size", upload.bytes.length);
                attachments.add(attachment);
                uploadStore.delete(upload.uploadId);
            }
            payload.put("attachments", attachments);
            eventStore.append(groupId, eventSessionId,
                    turnId, eventGeneration, "USER_MESSAGE_ACCEPTED", payload);
        } else {
            turns.clear(turnId);
        }
        return response;
    }

    public JSONObject upload(String groupId, String sessionId, long generation,
                             String fileName, String contentBase64) {
        requireCurrent(groupId, sessionId, generation);
        return uploadStore.stage(groupId, sessionId, generation,
                fileName, contentBase64);
    }

    public void publishInbound(String groupId, String sessionId, String content,
                               String source, JSONObject metadata) {
        StarweaveSessionIndex.Entry entry = requireEntry(groupId);
        if (!entry.isActive() || !Objects.equals(entry.getCurrentSessionId(), sessionId)) {
            throw stale("channel event targets an old session");
        }
        JSONObject payload = metadata == null
                ? new JSONObject(true) : new JSONObject(metadata);
        payload.put("content", content);
        payload.put("source", source == null ? "EXTERNAL" : source);
        eventStore.append(groupId, sessionId, turnTracker(groupId).currentOrBegin(),
                entry.getGeneration(), "CHANNEL_MESSAGE_RECEIVED", payload);
    }

    public JSONArray resources(String groupId, String sessionId, long generation) {
        AcpClient client = requireResourceClient(groupId, sessionId, generation);
        JSONArray result = new JSONArray();
        for (java.nio.file.Path path : safeResourcePaths(client, sessionId)) {
            JSONObject item = new JSONObject(true);
            item.put("resourceId", resourceId(groupId, sessionId, generation, path));
            item.put("fileName", path.getFileName().toString());
            try { item.put("size", java.nio.file.Files.size(path)); }
            catch (java.io.IOException ignored) { item.put("size", 0L); }
            item.put("contentType", contentType(path));
            result.add(item);
        }
        return result;
    }

    public JSONObject previewResource(String groupId, String sessionId, long generation,
                                      String requestedResourceId) {
        java.nio.file.Path path = resolveResource(
                groupId, sessionId, generation, requestedResourceId);
        StarweaveResourcePayload payload = readResource(path, 2 * 1024 * 1024);
        JSONObject result = new JSONObject(true);
        result.put("resourceId", requestedResourceId);
        result.put("fileName", payload.getFileName());
        result.put("contentType", payload.getContentType());
        byte[] bytes = payload.getBytes();
        if (isSafeRasterImage(payload.getContentType())) {
            result.put("kind", "image");
            result.put("contentBase64", java.util.Base64.getEncoder().encodeToString(bytes));
        } else if (isText(bytes, payload.getContentType())) {
            result.put("kind", "text");
            result.put("content", new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } else {
            result.put("kind", "binary");
        }
        result.put("truncated", fileSize(path) > bytes.length);
        return result;
    }

    /** Reads a text link against the selected session's workspace. */
    public com.mola.cmd.proxy.app.acp.filepreview.TextFilePreviewResult previewTextFile(
            String requestId, String groupId, String sessionId, long generation,
            String path, Integer maxBytes, String charset) {
        AcpClient client = requireResourceClient(groupId, sessionId, generation);
        return com.mola.cmd.proxy.app.acp.filepreview.TextFilePreviewReader.read(
                requestId, client.getWorkspacePath(), path, maxBytes, charset);
    }

    public StarweaveResourcePayload downloadResource(
            String groupId, String sessionId, long generation, String requestedResourceId) {
        java.nio.file.Path path = resolveResource(
                groupId, sessionId, generation, requestedResourceId);
        return readResource(path, StarweaveUploadStore.MAX_FILE_BYTES);
    }

    public JSONObject cancel(String groupId, String expectedSessionId,
                             long expectedGeneration) {
        requireCurrent(groupId, expectedSessionId, expectedGeneration);
        return commandResult(sessionService.cancel(groupId));
    }

    public JSONObject newSession(String groupId, String expectedSessionId,
                                 long expectedGeneration) throws Exception {
        StarweaveSessionIndex.Entry entry = requireCurrent(
                groupId, expectedSessionId, expectedGeneration);
        AcpClient current = requireReadyClient(groupId);
        AcpClient replacement = sessionService.replaceIfCurrent(
                groupId, current, null,
                clientInitializer(groupId, current.getRobotParam()));
        if (replacement == null) throw stale("client changed while creating a new session");
        StarweaveSessionIndex.Entry updated = index.updateSession(
                groupId, replacement.getSessionId());
        turnTracker(groupId).reset();
        eventStore.append(groupId, replacement.getSessionId(), updated.getGeneration(),
                "SESSION_REPLACED", replacementPayload(
                        entry.getCurrentSessionId(), replacement.getSessionId(), "MANUAL"));
        return view(replacement, updated);
    }

    public JSONObject restore(String groupId, String targetSessionId,
                              String expectedSessionId,
                              long expectedGeneration) throws Exception {
        StarweaveSessionIndex.Entry entry = requireCurrent(
                groupId, expectedSessionId, expectedGeneration);
        requireReadyClient(groupId);
        sessionService.restore(groupId, requireText(targetSessionId, "targetSessionId"),
                restored -> featureAndListener(groupId, restored, restored.getRobotParam()));
        AcpClient restored = registry.getClient(groupId);
        if (restored == null || !restored.getClientIdentity().isStarweave()) {
            throw new IllegalStateException("restored Starweave client is unavailable");
        }
        StarweaveSessionIndex.Entry updated = index.updateSession(
                groupId, restored.getSessionId());
        turnTracker(groupId).reset();
        eventStore.append(groupId, restored.getSessionId(), updated.getGeneration(),
                "SESSION_REPLACED", replacementPayload(
                        entry.getCurrentSessionId(), restored.getSessionId(), "RESTORE"));
        return view(restored, updated);
    }

    public JSONObject delete(String groupId, String sessionId,
                             long expectedGeneration) {
        StarweaveSessionIndex.Entry entry = requireCurrent(
                groupId, sessionId, expectedGeneration);
        AcpClient client = registry.getClient(groupId);
        if (client != null && client.getState() == AbstractAcpClient.State.BUSY) {
            throw new IllegalStateException("BUSY session must be cancelled before deletion");
        }
        registry.closeByGroupId(groupId);
        turnTracker(groupId).reset();
        StarweaveSessionIndex.Entry deleted = index.markDeleted(groupId);
        eventStore.append(groupId, entry.getCurrentSessionId(),
                deleted.getGeneration(), "SESSION_DELETED", new JSONObject(true));
        return view(deleted);
    }

    public JSONArray events(String groupId, String sessionId, long afterSeq) {
        requireKnownSession(groupId, sessionId);
        ensureHistoryProjection(groupId, sessionId);
        JSONArray result = new JSONArray();
        for (StarweaveSessionEvent event : eventStore.snapshot(
                groupId, sessionId, afterSeq)) result.add(event.toJson());
        return result;
    }

    public JSONObject eventBatch(String groupId, String sessionId, long afterSeq,
                                 Long generation) {
        if (generation == null) {
            requireKnownSession(groupId, sessionId);
            ensureHistoryProjection(groupId, sessionId);
        } else {
            requireCurrent(groupId, sessionId, generation);
        }
        if (afterSeq == 0L && generation == null && sessionId != null) {
            return eventStore.sessionSnapshot(groupId, sessionId).toJson();
        }
        return eventStore.read(groupId, sessionId, afterSeq, generation).toJson();
    }

    public JSONObject historyPage(String groupId, String sessionId, String before, int limit) {
        requireKnownSession(groupId, sessionId); ensureHistoryProjection(groupId, sessionId);
        return eventStore.historyPage(groupId, sessionId, before, limit);
    }

    public JSONObject awaitEventBatch(String groupId, String sessionId, long afterSeq,
                                      Long generation, long timeoutMillis) {
        if (generation == null) requireKnownSession(groupId, sessionId);
        else requireCurrent(groupId, sessionId, generation);
        return eventStore.await(groupId, sessionId, afterSeq,
                generation, timeoutMillis).toJson();
    }

    /** Reinstalls Starweave projection hooks when a shared lifecycle path replaces a client. */
    public void initializeReplacement(String groupId, AcpClient client,
                                      AcpRobotParam robot) throws Exception {
        if (client == null || !client.getClientIdentity().isStarweave()) {
            throw new IllegalArgumentException("replacement is not a Starweave client");
        }
        featureAndListener(groupId, client, robot);
    }

    /** Publishes a replacement performed by schedule/idle lifecycle code. */
    public void onSessionReplaced(String groupId, String oldSessionId,
                                  String newSessionId, String reason) {
        AcpClient client = registry.getClient(groupId);
        if (client == null || !client.getClientIdentity().isStarweave()
                || !Objects.equals(client.getSessionId(), newSessionId)) {
            throw new IllegalStateException("Starweave replacement is no longer current");
        }
        StarweaveSessionIndex.Entry updated = index.updateSession(groupId, newSessionId);
        turnTracker(groupId).reset();
        eventStore.append(groupId, newSessionId, updated.getGeneration(),
                "SESSION_REPLACED", replacementPayload(
                        oldSessionId, newSessionId, reason));
    }

    /**
     * Synchronizes the durable session index when a sleeping client wakes into a
     * deferred fresh session. Unlike {@link #onSessionReplaced}, the originating
     * turn is still in flight, so the turn tracker must be preserved: the user
     * message, lifecycle event, and reply all belong to the same logical turn.
     */
    public void onSessionRotatedOnWake(String groupId, String oldSessionId,
                                       String newSessionId) {
        AcpClient client = registry.getClient(groupId);
        if (client == null || !client.getClientIdentity().isStarweave()
                || !Objects.equals(client.getSessionId(), newSessionId)) {
            throw new IllegalStateException("Starweave wake rotation is no longer current");
        }
        StarweaveSessionIndex.Entry updated = index.updateSession(groupId, newSessionId);
        eventStore.append(groupId, newSessionId, updated.getGeneration(),
                "SESSION_REPLACED", replacementPayload(
                        oldSessionId, newSessionId, "AUTO_IDLE"));
    }

    private AcpClientRegistry.ClientInitializer clientInitializer(
            String groupId, AcpRobotParam robot) {
        return client -> featureAndListener(groupId, client, robot);
    }

    private void featureAndListener(String groupId, AcpClient client,
                                    AcpRobotParam robot) throws Exception {
        client.setGlobalListener(new StarweaveAcpResponseListener(
                groupId, client::getSessionId,
                () -> generation(groupId), eventStore, turnTracker(groupId)));
        client.setSessionRotationListener((previousSessionId, newSessionId) ->
                onSessionRotatedOnWake(groupId, previousSessionId, newSessionId));
        client.setNewSessionListener((previousSessionId, newSessionId, prompt, messageId) -> {
            onSessionReplaced(groupId, previousSessionId, newSessionId, "AGENT_TOOL");
            JSONObject payload = new JSONObject(true);
            payload.put("content", prompt);
            payload.put("messageId", messageId);
            payload.put("source", "AGENT_TOOL");
            payload.put("attachments", new JSONArray());
            eventStore.append(groupId, newSessionId, turnTracker(groupId).begin(),
                    generation(groupId), "USER_MESSAGE_ACCEPTED", payload);
        });
        featureInitializer.initialize(groupId, client, robot);
    }

    private long generation(String groupId) {
        StarweaveSessionIndex.Entry entry = index.get(groupId);
        return entry == null ? 1L : entry.getGeneration();
    }

    private StarweaveTurnTracker turnTracker(String groupId) {
        return turnTrackers.computeIfAbsent(groupId,
                ignored -> new StarweaveTurnTracker());
    }

    private void requireKnownSession(String groupId, String sessionId) {
        StarweaveSessionIndex.Entry entry = requireEntry(groupId);
        String requiredSessionId = requireText(sessionId, "sessionId");
        if (Objects.equals(entry.getCurrentSessionId(), requiredSessionId)) return;
        AcpClient client = registry.getClient(groupId);
        if (client != null) {
            for (ConversationHistoryManager.SessionSummary summary
                    : client.getHistoryManager().listRecentSessions(100)) {
                if (Objects.equals(summary.getSessionId(), requiredSessionId)) return;
            }
        }
        throw new IllegalArgumentException("session is not owned by this Starweave agent");
    }

    private AcpClient requireResourceClient(String groupId, String sessionId,
                                            long generation) {
        requireCurrent(groupId, sessionId, generation);
        AcpClient client = registry.getClient(groupId);
        if (client == null || !client.getClientIdentity().isStarweave()) {
            throw new IllegalStateException("Starweave session is unavailable");
        }
        return client;
    }

    private List<java.nio.file.Path> safeResourcePaths(AcpClient client, String sessionId) {
        java.nio.file.Path root = client.getHistoryManager()
                .getSessionFilesDirectory(sessionId);
        if (!java.nio.file.Files.isDirectory(root,
                java.nio.file.LinkOption.NOFOLLOW_LINKS)) return Collections.emptyList();
        List<java.nio.file.Path> result = new java.util.ArrayList<>();
        try {
            java.nio.file.Path realRoot = root.toRealPath(
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            try (java.util.stream.Stream<java.nio.file.Path> children =
                         java.nio.file.Files.list(realRoot)) {
                children.limit(201).forEach(path -> {
                    try {
                        if (java.nio.file.Files.isSymbolicLink(path)
                                || !java.nio.file.Files.isRegularFile(path,
                                java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
                        java.nio.file.Path real = path.toRealPath(
                                java.nio.file.LinkOption.NOFOLLOW_LINKS);
                        if (real.getParent().equals(realRoot)) result.add(real);
                    } catch (java.io.IOException ignored) { }
                });
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to enumerate session resources", e);
        }
        try {
            java.nio.file.Path workspace = java.nio.file.Paths.get(client.getWorkspacePath())
                    .toAbsolutePath().normalize().toRealPath(
                            java.nio.file.LinkOption.NOFOLLOW_LINKS);
            for (String value : client.getHistoryManager().getFileAbsolutePaths()) {
                java.nio.file.Path candidate = java.nio.file.Paths.get(value)
                        .toAbsolutePath().normalize();
                if (java.nio.file.Files.isSymbolicLink(candidate)
                        || !java.nio.file.Files.isRegularFile(candidate,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                java.nio.file.Path real = candidate.toRealPath(
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if (real.startsWith(workspace) && !result.contains(real)) result.add(real);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to validate workspace resources", e);
        }
        if (result.size() > 200) throw new IllegalStateException("too many session resources");
        result.sort(java.util.Comparator.comparing(path -> path.getFileName().toString()));
        return result;
    }

    private java.nio.file.Path resolveResource(String groupId, String sessionId,
                                               long generation, String requestedResourceId) {
        if (requestedResourceId == null || !requestedResourceId.matches("r-[0-9a-f]{32}")) {
            throw new IllegalArgumentException("invalid resourceId");
        }
        AcpClient client = requireResourceClient(groupId, sessionId, generation);
        for (java.nio.file.Path path : safeResourcePaths(client, sessionId)) {
            if (requestedResourceId.equals(resourceId(
                    groupId, sessionId, generation, path))) return path;
        }
        throw new IllegalArgumentException("resource not found in current session generation");
    }

    private static String resourceId(String groupId, String sessionId, long generation,
                                     java.nio.file.Path path) {
        String value = groupId + "\n" + sessionId + "\n" + generation
                + "\n" + path.toString();
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : digest) hex.append(String.format("%02x", item & 0xff));
            return "r-" + hex.substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static StarweaveResourcePayload readResource(java.nio.file.Path path,
                                                          int maxBytes) {
        try {
            long size = java.nio.file.Files.size(path);
            if (size > maxBytes) {
                if (maxBytes == StarweaveUploadStore.MAX_FILE_BYTES) {
                    throw new IllegalArgumentException("resource exceeds download limit");
                }
                byte[] bytes = new byte[maxBytes];
                try (java.io.InputStream input = java.nio.file.Files.newInputStream(path)) {
                    int offset = 0;
                    while (offset < bytes.length) {
                        int count = input.read(bytes, offset, bytes.length - offset);
                        if (count < 0) break;
                        offset += count;
                    }
                    if (offset < bytes.length) bytes = java.util.Arrays.copyOf(bytes, offset);
                }
                return new StarweaveResourcePayload(path.getFileName().toString(),
                        contentType(path), bytes);
            }
            return new StarweaveResourcePayload(path.getFileName().toString(),
                    contentType(path), java.nio.file.Files.readAllBytes(path));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to read session resource", e);
        }
    }

    private static long fileSize(java.nio.file.Path path) {
        try { return java.nio.file.Files.size(path); }
        catch (java.io.IOException ignored) { return 0L; }
    }

    private static String contentType(java.nio.file.Path path) {
        try {
            String detected = java.nio.file.Files.probeContentType(path);
            if (detected != null) return detected;
        } catch (java.io.IOException ignored) { }
        return "application/octet-stream";
    }

    private static boolean isText(byte[] bytes, String contentType) {
        if (contentType.startsWith("text/") || contentType.contains("json")
                || contentType.contains("xml") || contentType.contains("javascript")) return true;
        for (byte value : bytes) if (value == 0) return false;
        return true;
    }

    private static boolean isSafeRasterImage(String contentType) {
        return "image/png".equals(contentType) || "image/jpeg".equals(contentType)
                || "image/gif".equals(contentType) || "image/webp".equals(contentType);
    }

    /**
     * Older Provider histories predate the Starweave journal. Project them once
     * into the same structured event contract so restore renders immediately.
     */
    private void ensureHistoryProjection(String groupId, String sessionId) {
        if (eventStore.hasDurableEvents(groupId, sessionId)) return;
        Object lock = historyProjectionLocks.computeIfAbsent(
                groupId + "\n" + sessionId, ignored -> new Object());
        synchronized (lock) {
            if (eventStore.hasDurableEvents(groupId, sessionId)) return;
            AcpClient client = registry.getClient(groupId);
            if (client == null) return;
            List<ContextMessage> history = client.getHistoryManager()
                    .getFullHistory(sessionId);
            if (history.isEmpty()) return;
            long generation = generation(groupId);
            String turnId = null;
            for (ContextMessage message : history) {
                if (message.getRole() == ContextMessage.Role.USER) {
                    if (!message.isVisibleUserMessage()
                            || isLegacyTaskPrompt(message.getContent())) {
                        continue;
                    }
                    if (turnId != null) appendImportedTerminal(
                            groupId, sessionId, turnId, generation);
                    turnId = java.util.UUID.randomUUID().toString();
                    JSONObject payload = new JSONObject(true);
                    payload.put("content", message.getContent());
                    payload.put("source", "HISTORY");
                    eventStore.append(groupId, sessionId, turnId, generation,
                            "USER_MESSAGE_ACCEPTED", payload);
                } else if (message.getRole() == ContextMessage.Role.ASSISTANT) {
                    if (turnId == null) turnId = java.util.UUID.randomUUID().toString();
                    JSONObject payload = new JSONObject(true);
                    payload.put("text", message.getContent());
                    payload.put("source", "HISTORY");
                    eventStore.append(groupId, sessionId, turnId, generation,
                            "ASSISTANT_MESSAGE_DELTA", payload);
                } else if (message.getRole() == ContextMessage.Role.TOOL) {
                    if (turnId == null) turnId = java.util.UUID.randomUUID().toString();
                    JSONObject payload = new JSONObject(true);
                    payload.put("toolCallId", message.getToolCallId());
                    payload.put("title", message.getToolName());
                    payload.put("status", message.getStatus());
                    JSONObject update = new JSONObject(true);
                    update.put("rawInput", message.getRawInput() == null ? null
                            : com.alibaba.fastjson.JSON.parse(message.getRawInput().toString()));
                    update.put("rawOutput", message.getRawOutput() == null ? null
                            : com.alibaba.fastjson.JSON.parse(message.getRawOutput().toString()));
                    payload.put("update", update);
                    payload.put("source", "HISTORY");
                    eventStore.append(groupId, sessionId, turnId, generation,
                            "TOOL_CALL_UPDATED", payload);
                }
            }
            if (turnId != null) appendImportedTerminal(
                    groupId, sessionId, turnId, generation);
        }
    }

    private static boolean isLegacyTaskPrompt(String content) {
        if (content == null) return false;
        String normalized = content.trim();
        return normalized.startsWith("[Starweave Task]\n")
                && normalized.contains("\neventId: ")
                && normalized.contains("\neventType: ")
                && normalized.contains("\ntaskId: ");
    }

    private void appendImportedTerminal(String groupId, String sessionId,
                                        String turnId, long generation) {
        JSONObject payload = new JSONObject(true);
        payload.put("source", "HISTORY");
        eventStore.append(groupId, sessionId, turnId, generation,
                "TURN_COMPLETED", payload);
    }

    private AcpRobotParam requireRobot(String robotName) {
        AcpRobotParam robot = robotResolver.resolve(requireText(robotName, "robotName"));
        if (robot == null) throw new IllegalArgumentException("robot not found: " + robotName);
        if (!robot.isEnabled()) throw new IllegalStateException("robot is disabled: " + robotName);
        if (robot.isOnlySubAgent() || robot.isOnlyTeamMember()) {
            throw new IllegalStateException("robot cannot create a MAIN session: " + robotName);
        }
        return robot;
    }

    private StarweaveSessionIndex.Entry requireEntry(String groupId) {
        StarweaveSessionIndex.Entry entry = index.get(requireText(groupId, "groupId"));
        if (entry == null) throw new IllegalArgumentException("session not found: " + groupId);
        return entry;
    }

    private StarweaveSessionIndex.Entry requireCurrent(
            String groupId, String expectedSessionId, long expectedGeneration) {
        StarweaveSessionIndex.Entry entry = requireEntry(groupId);
        if (!entry.isActive()
                || !Objects.equals(entry.getCurrentSessionId(), expectedSessionId)
                || entry.getGeneration() != expectedGeneration) {
            throw stale("session identity changed");
        }
        return entry;
    }

    private AcpClient requireReadyClient(String groupId) {
        AcpClient client = registry.getClient(groupId);
        if (client != null && client.getState() == AbstractAcpClient.State.SLEEP) {
            try {
                client.wakeIfSleeping();
            } catch (IOException e) {
                throw new IllegalStateException("session wake failed", e);
            }
        }
        if (client == null || client.getState() != AbstractAcpClient.State.READY) {
            throw new IllegalStateException("session is not READY");
        }
        if (!client.getClientIdentity().isStarweave()) {
            throw new IllegalStateException("session is not owned by Starweave");
        }
        return client;
    }

    private static IllegalStateException stale(String message) {
        return new IllegalStateException("SESSION_STALE: " + message);
    }

    private static JSONObject commandResult(PromptCommandResult result) {
        JSONObject value = new JSONObject(true);
        value.put("accepted", result.isAccepted());
        value.put("code", result.getCode());
        value.put("message", result.getResult());
        return value;
    }

    private JSONObject view(AcpClient client,
                                   StarweaveSessionIndex.Entry entry) {
        JSONObject value = view(entry);
        value.put("sessionId", client.getSessionId());
        value.put("state", client.getState().name());
        value.put("contextUsage", client.getContextUsagePercentage());
        JSONArray sessions = new JSONArray();
        List<ConversationHistoryManager.SessionSummary> recent =
                client.getHistoryManager().listRecentSessions(50);
        for (ConversationHistoryManager.SessionSummary summary : recent) {
            JSONObject session = new JSONObject(true);
            session.put("sessionId", summary.getSessionId());
            session.put("preview", summary.getPreview());
            session.put("lastModified", summary.getLastModified());
            session.put("current", Objects.equals(
                    summary.getSessionId(), client.getSessionId()));
            sessions.add(session);
        }
        value.put("sessions", sessions);
        value.put("address", new AgentAddress(instanceId,
                client.getClientIdentity().getSurface(),
                client.getClientIdentity().getOwnerId(), entry.getRobotName()).toJson());
        return value;
    }

    private JSONObject view(StarweaveSessionIndex.Entry entry) {
        JSONObject value = new JSONObject(true);
        value.put("robotName", entry.getRobotName());
        value.put("groupId", entry.getGroupId());
        value.put("sessionId", entry.getCurrentSessionId());
        value.put("generation", entry.getGeneration());
        value.put("active", entry.isActive());
        value.put("forceNewOnNextOpen", entry.isForceNewOnNextOpen());
        value.put("updatedAt", entry.getUpdatedAt());
        value.put("state", entry.isActive() ? "CLOSED" : "DELETED");
        value.put("sessions", new JSONArray());
        value.put("address", new AgentAddress(instanceId,
                com.mola.cmd.proxy.app.acp.acpclient.ClientSurface.STARWEAVE,
                StarweaveIdentity.ownerId(instanceId), entry.getRobotName()).toJson());
        return value;
    }

    private static JSONObject statePayload(AcpClient client) {
        JSONObject payload = new JSONObject(true);
        payload.put("state", client.getState().name());
        return payload;
    }

    private static JSONObject replacementPayload(String oldSessionId,
                                                 String newSessionId,
                                                 String reason) {
        JSONObject payload = new JSONObject(true);
        payload.put("oldSessionId", oldSessionId);
        payload.put("newSessionId", newSessionId);
        payload.put("reason", reason);
        return payload;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
