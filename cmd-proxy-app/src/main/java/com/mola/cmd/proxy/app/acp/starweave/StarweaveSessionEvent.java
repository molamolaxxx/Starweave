package com.mola.cmd.proxy.app.acp.starweave;

import com.alibaba.fastjson.JSONObject;

/** Immutable structured event consumed by the Starweave session UI. */
public final class StarweaveSessionEvent {
    private final String groupId;
    private final String sessionId;
    private final String turnId;
    private final long generation;
    private final long eventSeq;
    private final long timestamp;
    private final String type;
    private final JSONObject payload;

    StarweaveSessionEvent(String groupId, String sessionId, long generation,
                          long eventSeq, long timestamp, String type,
                          JSONObject payload) {
        this(groupId, sessionId, null, generation, eventSeq, timestamp, type, payload);
    }

    StarweaveSessionEvent(String groupId, String sessionId, String turnId,
                          long generation, long eventSeq, long timestamp, String type,
                          JSONObject payload) {
        this.groupId = groupId;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.generation = generation;
        this.eventSeq = eventSeq;
        this.timestamp = timestamp;
        this.type = type;
        this.payload = payload == null ? new JSONObject(true)
                : new JSONObject(payload);
    }

    public JSONObject toJson() {
        return com.mola.cmd.proxy.app.acp.common.ToolOutputPreview.row(toStoredJson());
    }

    JSONObject toStoredJson() {
        JSONObject value = new JSONObject(true);
        value.put("schemaVersion", 1);
        value.put("groupId", groupId);
        value.put("sessionId", sessionId);
        value.put("turnId", turnId);
        value.put("generation", generation);
        value.put("eventSeq", eventSeq);
        value.put("timestamp", timestamp);
        value.put("type", type);
        value.put("payload", new JSONObject(payload));
        return value;
    }

    public String getGroupId() { return groupId; }
    public String getSessionId() { return sessionId; }
    public String getTurnId() { return turnId; }
    public long getGeneration() { return generation; }
    public long getEventSeq() { return eventSeq; }
    public long getTimestamp() { return timestamp; }
    public String getType() { return type; }
    public JSONObject getPayload() { return new JSONObject(payload); }

    static StarweaveSessionEvent fromJson(JSONObject value) {
        if (value == null || value.getIntValue("schemaVersion") != 1) {
            throw new IllegalArgumentException("unsupported Starweave event schema");
        }
        return new StarweaveSessionEvent(
                value.getString("groupId"), value.getString("sessionId"),
                value.getString("turnId"), value.getLongValue("generation"),
                value.getLongValue("eventSeq"), value.getLongValue("timestamp"),
                value.getString("type"), value.getJSONObject("payload"));
    }
}
