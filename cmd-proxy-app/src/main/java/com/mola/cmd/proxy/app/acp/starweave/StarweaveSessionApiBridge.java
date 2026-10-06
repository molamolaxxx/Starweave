package com.mola.cmd.proxy.app.acp.starweave;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * Java-owned runtime bridge used by ConfigUI.
 *
 * <p>ConfigUI is compiled before the Kotlin ACP composition root, so it must not
 * directly depend on methods added to {@code AcpProxy} in the same reactor build.</p>
 */
public final class StarweaveSessionApiBridge {

    private static volatile StarweaveSessionManager manager;

    private StarweaveSessionApiBridge() {
    }

    public static void install(StarweaveSessionManager sessionManager) {
        manager = sessionManager;
    }

    public static void clear(StarweaveSessionManager expected) {
        if (manager == expected) manager = null;
    }

    public static JSONObject open(String robotName) throws Exception {
        return requireManager().open(robotName);
    }

    public static JSONArray list() {
        StarweaveSessionManager current = manager;
        return current == null ? new JSONArray() : current.list();
    }

    public static JSONObject send(String groupId, String message,
                                  String expectedSessionId, long expectedGeneration,
                                  String busyPolicy) {
        return requireManager().send(groupId, message, expectedSessionId,
                expectedGeneration, busyPolicy);
    }

    public static JSONObject send(String groupId, String message,
                                  String expectedSessionId, long expectedGeneration,
                                  String busyPolicy, java.util.List<String> uploadIds) {
        return requireManager().send(groupId, message, expectedSessionId,
                expectedGeneration, busyPolicy, uploadIds);
    }

    public static JSONObject send(String groupId, String message,
                                  String expectedSessionId, long expectedGeneration,
                                  String busyPolicy, java.util.List<String> uploadIds, String messageId) {
        return requireManager().send(groupId, message, expectedSessionId,
                expectedGeneration, busyPolicy, uploadIds, messageId);
    }

    public static JSONObject upload(String groupId, String sessionId, long generation,
                                    String fileName, String contentBase64) {
        return requireManager().upload(groupId, sessionId, generation,
                fileName, contentBase64);
    }

    public static JSONArray resources(String groupId, String sessionId, long generation) {
        return requireManager().resources(groupId, sessionId, generation);
    }

    public static JSONObject previewResource(String groupId, String sessionId,
                                             long generation, String resourceId) {
        return requireManager().previewResource(groupId, sessionId, generation, resourceId);
    }

    public static com.mola.cmd.proxy.app.acp.filepreview.TextFilePreviewResult previewTextFile(
            String requestId, String groupId, String sessionId, long generation,
            String path, Integer maxBytes, String charset) {
        return requireManager().previewTextFile(requestId, groupId, sessionId, generation,
                path, maxBytes, charset);
    }

    public static StarweaveResourcePayload downloadResource(
            String groupId, String sessionId, long generation, String resourceId) {
        return requireManager().downloadResource(groupId, sessionId, generation, resourceId);
    }

    public static void publishInbound(String groupId, String sessionId, String content,
                                      String source, JSONObject metadata) {
        requireManager().publishInbound(groupId, sessionId, content, source, metadata);
    }

    public static JSONObject cancel(String groupId, String expectedSessionId,
                                    long expectedGeneration) {
        return requireManager().cancel(groupId, expectedSessionId, expectedGeneration);
    }

    public static JSONObject newSession(String groupId, String expectedSessionId,
                                        long expectedGeneration) throws Exception {
        return requireManager().newSession(groupId, expectedSessionId, expectedGeneration);
    }

    public static JSONObject restore(String groupId, String targetSessionId,
                                     String expectedSessionId, long expectedGeneration)
            throws Exception {
        return requireManager().restore(groupId, targetSessionId,
                expectedSessionId, expectedGeneration);
    }

    public static JSONObject delete(String groupId, String sessionId,
                                    long expectedGeneration) {
        return requireManager().delete(groupId, sessionId, expectedGeneration);
    }

    public static JSONArray events(String groupId, String sessionId, long afterSeq) {
        return requireManager().events(groupId, sessionId, afterSeq);
    }

    public static JSONObject historyPage(String groupId, String sessionId, String before, int limit) {
        return requireManager().historyPage(groupId, sessionId, before, limit);
    }

    public static JSONObject eventBatch(String groupId, String sessionId,
                                        long afterSeq, Long generation) {
        return requireManager().eventBatch(groupId, sessionId, afterSeq, generation);
    }

    public static JSONObject awaitEventBatch(String groupId, String sessionId,
                                             long afterSeq, Long generation,
                                             long timeoutMillis) {
        return requireManager().awaitEventBatch(groupId, sessionId, afterSeq,
                generation, timeoutMillis);
    }

    public static void initializeReplacement(String groupId,
            com.mola.cmd.proxy.app.acp.acpclient.AcpClient client,
            com.mola.cmd.proxy.app.acp.AcpRobotParam robot) throws Exception {
        requireManager().initializeReplacement(groupId, client, robot);
    }

    public static void onSessionReplaced(String groupId, String oldSessionId,
                                         String newSessionId, String reason) {
        requireManager().onSessionReplaced(
                groupId, oldSessionId, newSessionId, reason);
    }

    private static StarweaveSessionManager requireManager() {
        StarweaveSessionManager current = manager;
        if (current == null) {
            throw new IllegalStateException("Starweave session service is not running");
        }
        return current;
    }
}
