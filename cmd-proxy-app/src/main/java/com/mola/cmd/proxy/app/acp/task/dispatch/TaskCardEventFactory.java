package com.mola.cmd.proxy.app.acp.task.dispatch;

import com.alibaba.fastjson.JSONObject;

import java.time.Instant;

/** Creates the single business-card payload shared by live and history projections. */
public final class TaskCardEventFactory {
    public static final String SCHEMA_VERSION = "1";
    public static final String CARD_TYPE = "STARWEAVE_TASK";

    public JSONObject create(JSONObject outboxEvent, JSONObject taskView,
                             String deliveryState, String viewUrl) {
        if (outboxEvent == null) throw new IllegalArgumentException("outboxEvent is required");
        JSONObject task = taskView == null ? null : taskView.getJSONObject("task");
        if (task == null && taskView != null && taskView.containsKey("id")) task = taskView;
        if (task == null) throw new IllegalArgumentException("task is required");

        JSONObject payload = new JSONObject(true);
        payload.put("schemaVersion", SCHEMA_VERSION);
        payload.put("cardType", CARD_TYPE);
        payload.put("eventId", required(outboxEvent.getString("eventId"), "eventId"));
        payload.put("eventType", required(outboxEvent.getString("eventType"), "eventType"));
        payload.put("taskEventSeq", outboxEvent.getLong("seq"));
        payload.put("taskId", required(task.getString("id"), "task.id"));
        payload.put("name", task.getString("name"));
        JSONObject eventPayload = outboxEvent.getJSONObject("payload");
        payload.put("revision", eventPayload != null && eventPayload.getLong("revision") != null
                ? eventPayload.getLong("revision") : task.getLong("revision"));
        payload.put("contentVersion", task.getLong("contentVersion"));
        payload.put("status", task.getString("status"));
        payload.put("summary", summarize(task.getString("contentMarkdown"), 240));
        payload.put("target", copy(task.getJSONObject("target")));
        payload.put("assignee", copy(task.getJSONObject("assignee")));
        payload.put("deliveryState", trim(deliveryState).isEmpty()
                ? "PENDING" : deliveryState.trim());
        payload.put("viewUrl", viewUrl);
        payload.put("emittedAt", Instant.now().toString());
        Long eventContentVersion = outboxEvent.getLong("contentVersion");
        if (eventContentVersion != null
                && eventContentVersion.longValue() != task.getLongValue("contentVersion")) {
            payload.put("eventContentVersion", eventContentVersion);
        }
        if (eventPayload != null) {
            copyIfPresent(eventPayload, payload, "recipientRole");
            // Publisher notifications describe the committed completion, even after a reopen.
            if ("PUBLISHER".equals(eventPayload.getString("recipientRole"))) {
                copyIfPresent(eventPayload, payload, "status");
                copyIfPresent(eventPayload, payload, "contentVersion");
            }
            copyIfPresent(eventPayload, payload, "previousContentVersion");
            copyIfPresent(eventPayload, payload, "changeRange");
            copyIfPresent(eventPayload, payload, "previousStatus");
            copyIfPresent(eventPayload, payload, "reason");
            copyIfPresent(eventPayload, payload, "actorName");
            copyIfPresent(eventPayload, payload, "actorType");
            copyIfPresent(eventPayload, payload, "changedAt");
        }
        return payload;
    }

    private static void copyIfPresent(JSONObject source, JSONObject target, String key) {
        if (source.containsKey(key)) target.put(key, source.get(key));
    }

    private static JSONObject copy(JSONObject value) {
        return value == null ? null : new JSONObject(value);
    }

    static String summarize(String markdown, int maxCodePoints) {
        String normalized = trim(markdown).replaceAll("\\s+", " ");
        int count = normalized.codePointCount(0, normalized.length());
        if (count <= maxCodePoints) return normalized;
        int end = normalized.offsetByCodePoints(0, maxCodePoints);
        return normalized.substring(0, end) + "…";
    }

    private static String required(String value, String field) {
        String normalized = trim(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
