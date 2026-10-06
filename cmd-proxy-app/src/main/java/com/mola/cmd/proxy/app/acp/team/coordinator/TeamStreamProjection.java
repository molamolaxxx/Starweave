package com.mola.cmd.proxy.app.acp.team.coordinator;

import com.alibaba.fastjson.JSONObject;
import java.util.*;
import java.util.function.Consumer;

/** Source checkpoints and replay-safe adaptation to the legacy MolaChat stream API. */
final class TeamStreamProjection {
    private final Map<String, Source> sources = new HashMap<>();
    private final CoordinationStore store;
    TeamStreamProjection(CoordinationStore store) { this.store = store; }
    static boolean isStream(JSONObject event) {
        return Arrays.asList("MESSAGE_CHUNK", "MESSAGE_COMPLETE", "MESSAGE_ERROR").contains(event.getString("type"));
    }
    private static String member(JSONObject event) {
        return event.getString("teamId") + ":" + event.getString("teamMemberId");
    }
    /** Called in source emission order. No callbacks or network work under this monitor. */
    synchronized void capture(JSONObject event) {
        if (!isStream(event)) return;
        String key = member(event);
        Source source = sources.computeIfAbsent(key, ignored -> new Source());
        JSONObject data = event.getJSONObject("data");
        if (data == null) { data = new JSONObject(); event.put("data", data); }
        data.put("projectionStreamId", source.id);
        data.put("projectionOffset", source.text.length());
        if ("MESSAGE_CHUNK".equals(event.getString("type"))) {
            String content = data.getString("content");
            if (content != null) source.text.append(content);
        } else {
            data.put("projectionText", source.text.toString());
            sources.remove(key);
        }
    }
    /** Invoked by the member's serial executor for both realtime and durable deliveries. */
    void project(JSONObject event, Consumer<JSONObject> sender) {
        JSONObject data = event.getJSONObject("data");
        String id = data == null ? null : data.getString("projectionStreamId");
        if (!isStream(event) || id == null) { sender.accept(event); return; }
        JSONObject progress = store.find("stream-projection-progress", id);
        if (progress == null) progress = MixedTeamCoordinator.object("offset", 0);
        if (progress.getBooleanValue("closed")) return;
        String memberKey = UUID.nameUUIDFromBytes(member(event).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        JSONObject active = store.find("stream-projection-members", memberKey);
        if (active != null && !id.equals(active.getString("streamId"))) {
            JSONObject previous = store.find("stream-projection-progress", active.getString("streamId"));
            if (previous == null || !previous.getBooleanValue("closed")) {
                JSONObject end = previous == null ? null : previous.getJSONObject("pendingEnd");
                if (end == null) throw new CoordinationException("STREAM_WAITING", "上一轮流尚未结束，等待可靠补偿");
                project(end, sender);
            }
        }
        if (active == null || !id.equals(active.getString("streamId")))
            store.save("stream-projection-members", memberKey, MixedTeamCoordinator.object("streamId", id));
        // Retry the exact unacknowledged chunk before choosing a larger checkpoint
        // suffix. Otherwise ACK loss can resend an already-visible prefix under a
        // different event ID, defeating MolaChat's existing deduplication.
        flushPending(id, progress, sender);
        int offset = progress.getIntValue("offset");
        if ("MESSAGE_CHUNK".equals(event.getString("type"))) {
            int start = data.getIntValue("projectionOffset");
            String content = data.getString("content");
            content = content == null ? "" : content;
            // A skipped or late frame is repaired from the terminal checkpoint. Never
            // append a gap or duplicate to the legacy API, which has no message identity.
            if (start > offset || start + content.length() <= offset) return;
            String suffix = content.substring(offset - start);
            sendChunk(id, progress, chunk(event, id, offset, suffix), start + content.length(), sender);
            return;
        }
        String text = data.getString("projectionText");
        if (text == null || offset > text.length())
            throw new CoordinationException("INVALID_STREAM", "流结束检查点不完整");
        if (offset < text.length()) {
            sendChunk(id, progress, chunk(event, id, offset, text.substring(offset)), text.length(), sender);
        }
        progress.put("pendingEnd", event);
        store.save("stream-projection-progress", id, progress);
        sender.accept(event);
        progress.put("closed", true);
        progress.remove("pendingEnd");
        progress.put("timestamp", System.currentTimeMillis());
        store.save("stream-projection-progress", id, progress);
    }
    private void sendChunk(String id, JSONObject progress, JSONObject chunk, int end, Consumer<JSONObject> sender) {
        progress.put("pendingChunk", chunk); progress.put("pendingOffset", end);
        store.save("stream-projection-progress", id, progress);
        flushPending(id, progress, sender);
    }
    private void flushPending(String id, JSONObject progress, Consumer<JSONObject> sender) {
        JSONObject pending = progress.getJSONObject("pendingChunk");
        if (pending == null) return;
        sender.accept(pending);
        progress.put("offset", progress.getIntValue("pendingOffset"));
        progress.remove("pendingChunk"); progress.remove("pendingOffset");
        store.save("stream-projection-progress", id, progress);
    }
    private static JSONObject chunk(JSONObject event, String id, int offset, String content) {
        JSONObject result = MixedTeamCoordinator.copy(event);
        result.put("type", "MESSAGE_CHUNK");
        // Stable across ACK loss/retry; MolaChat already deduplicates eventId.
        result.put("eventId", UUID.nameUUIDFromBytes((id + ":" + offset + ":" + content.length())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        result.put("data", MixedTeamCoordinator.object("content", content));
        return result;
    }
    private static final class Source {
        final String id = UUID.randomUUID().toString();
        final StringBuilder text = new StringBuilder();
    }
}
