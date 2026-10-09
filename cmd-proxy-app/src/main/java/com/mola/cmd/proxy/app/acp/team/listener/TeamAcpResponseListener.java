package com.mola.cmd.proxy.app.acp.team.listener;

import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseContentRenderer;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventSink;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventType;
import com.mola.cmd.proxy.app.acp.team.model.TeamError;
import com.mola.cmd.proxy.app.acp.team.model.TeamErrorCode;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberState;
import com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将 AcpClient listener 回调映射为统一 TeamEventEnvelope。
 *
 * <p>本类不负责 client 创建或能力装配；后续 Team member runtime 直接注入使用。</p>
 */
public final class TeamAcpResponseListener implements AcpResponseListener {

    private final TeamRuntime runtime;
    private final String teamMemberId;
    private final String acpClientId;
    private final TeamEventSink sink;
    private final TeamMemberStateObserver stateObserver;
    private final AcpResponseContentRenderer renderer;
    private final ConversationHistoryManager historyManager;

    public TeamAcpResponseListener(TeamRuntime runtime,
                                   TeamMemberDefinition member,
                                   TeamEventSink sink) {
        this(runtime, member, sink, TeamMemberStateObserver.NOOP, null);
    }

    public TeamAcpResponseListener(TeamRuntime runtime,
                                   TeamMemberDefinition member,
                                   TeamEventSink sink,
                                   TeamMemberStateObserver stateObserver) {
        this(runtime, member, sink, stateObserver, null);
    }

    public TeamAcpResponseListener(TeamRuntime runtime,
                                   TeamMemberDefinition member,
                                   TeamEventSink sink,
                                   TeamMemberStateObserver stateObserver,
                                   ConversationHistoryManager historyManager) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
        TeamMemberDefinition checked = java.util.Objects.requireNonNull(member, "member");
        boolean belongsToTeam = false;
        for (TeamMemberDefinition defined : runtime.getDefinition().getMembers()) {
            if (checked.getTeamMemberId().equals(defined.getTeamMemberId())
                    && checked.getAcpClientId().equals(defined.getAcpClientId())) {
                belongsToTeam = true;
                break;
            }
        }
        if (!belongsToTeam) {
            throw new IllegalArgumentException(
                    "member does not belong to Team runtime definition");
        }
        this.teamMemberId = checked.getTeamMemberId();
        this.acpClientId = checked.getAcpClientId();
        this.sink = java.util.Objects.requireNonNull(sink, "sink");
        this.stateObserver = java.util.Objects.requireNonNull(
                stateObserver, "stateObserver");
        this.renderer = new AcpResponseContentRenderer(this::publishRendered);
        this.historyManager = historyManager;
    }

    @Override
    public void onMessage(String text) {
        if (historyManager != null) {
            com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage message =
                    historyManager.appendUiAssistant(text);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("messageId", message.getMessageId());
            data.put("revision", message.getRevision());
            data.put("content", message.getContent());
            publish(TeamEventType.MESSAGE_UPDATED, data);
        }
        renderer.onMessage(text);
    }

    @Override
    public void onToolCall(String toolCallId, String title, String status,
                           JsonObject update) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolCallId", nullToEmpty(toolCallId));
        data.put("title", nullToEmpty(title));
        data.put("status", nullToEmpty(status));
        data.put("update", update == null ? new JsonObject() : update);
        persist(TeamEventType.TOOL_CALL, data);
        com.google.gson.Gson gson = new com.google.gson.Gson();
        com.alibaba.fastjson.JSONObject preview = com.mola.cmd.proxy.app.acp.common.ToolOutputPreview.payload(
                com.alibaba.fastjson.JSON.parseObject(gson.toJson(data)));
        if (preview.getBooleanValue("outputTruncated")) {
            JsonObject previewUpdate = preview.getJSONObject("update") == null
                    ? new JsonObject() : gson.fromJson(preview.getJSONObject("update").toJSONString(), JsonObject.class);
            renderer.onToolCall(preview.getString("title"), status, previewUpdate);
        } else {
            renderer.onToolCall(title, status, update);
        }
        publish(TeamEventType.TOOL_CALL, preview);
    }

    @Override
    public void onSubAgentEvent(String eventType, String agentName, String detail) {
        renderer.onSubAgentEvent(eventType, agentName, detail);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventType", nullToEmpty(eventType));
        data.put("agentName", agentName);
        data.put("detail", nullToEmpty(detail));
        persist(TeamEventType.SUB_AGENT_EVENT, data);
        publish(TeamEventType.SUB_AGENT_EVENT, data);
    }

    @Override
    public void onScheduleEvent(String eventType, String detail, boolean expanded) {
        renderer.onScheduleEvent(eventType, detail, expanded);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventType", nullToEmpty(eventType));
        data.put("detail", nullToEmpty(detail));
        data.put("expanded", expanded);
        persist(TeamEventType.SCHEDULE_EVENT, data);
        publish(TeamEventType.SCHEDULE_EVENT, data);
    }

    @Override
    public void onTalkToEvent(String eventType, String robotName, String content) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventType", nullToEmpty(eventType));
        data.put("robotName", nullToEmpty(robotName));
        data.put("content", nullToEmpty(content));
        TeamEventType type;
        if ("TALK_TO_RECEIVE".equals(eventType)) {
            type = TeamEventType.TALK_TO_RECEIVE;
        } else if ("TALK_TO_QUEUED".equals(eventType)) {
            type = TeamEventType.TALK_TO_QUEUED;
        } else if ("TALK_TO_REJECTED".equals(eventType)) {
            type = TeamEventType.TALK_TO_REJECTED;
        } else {
            type = TeamEventType.TALK_TO_SEND;
        }
        persist(type, data);
        publish(type, data);
    }

    @Override
    public void onCompactionEvent(String eventType, String provider) {
        renderer.onCompactionEvent(eventType, provider);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventType", nullToEmpty(eventType));
        data.put("provider", nullToEmpty(provider));
        persist(TeamEventType.COMPACTION_EVENT, data);
        publish(TeamEventType.COMPACTION_EVENT, data);
    }

    @Override
    public void onTaskEvent(JsonObject payload) {
        Map<String, Object> data = new com.google.gson.Gson().fromJson(
                payload == null ? new JsonObject() : payload, Map.class);
        persist(TeamEventType.TASK_EVENT, data);
        publish(TeamEventType.TASK_EVENT, data);
    }

    @Override
    public void onLifecycleEvent(String eventType, String fromState, String toState,
                                 long durationMillis, boolean newSession) {
        renderer.onLifecycleEvent(eventType, fromState, toState, durationMillis, newSession);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventType", eventType);
        data.put("fromState", fromState);
        data.put("toState", toState);
        data.put("durationMillis", durationMillis);
        data.put("newSession", newSession);
        persist(TeamEventType.LIFECYCLE_EVENT, data);
        publish(TeamEventType.LIFECYCLE_EVENT, data);
        stateObserver.onState(runtime.getDefinition().getTeamId(),
                teamMemberId, TeamMemberState.READY, null);
    }

    @Override
    public void onComplete(String fullResponse) {
        renderer.onComplete();
    }

    /** Called by AcpClient only after the authoritative BUSY -> READY transition. */
    public void onClientReady() {
        stateObserver.onState(runtime.getDefinition().getTeamId(),
                teamMemberId, TeamMemberState.READY, null);
    }

    @Override
    public void onError(Exception error) {
        if (historyManager != null) historyManager.finishUiAssistant();
        String message = error == null ? "unknown Team ACP error" : error.getMessage();
        TeamError teamError = TeamError.of(TeamErrorCode.INTERNAL_ERROR,
                message == null || message.trim().isEmpty()
                        ? error.getClass().getSimpleName() : message, true);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", teamError.getCode());
        data.put("message", teamError.getMessage());
        data.put("retryable", teamError.isRetryable());
        data.put("content", AcpResponseContentRenderer.errorContent(error));
        persist(TeamEventType.MESSAGE_ERROR, data);
        publish(TeamEventType.MESSAGE_ERROR, data);
        stateObserver.onState(runtime.getDefinition().getTeamId(),
                teamMemberId, TeamMemberState.ERROR, teamError);
    }

    private void publish(TeamEventType type, Object data) {
        if (!runtime.isAcceptingRequests()) {
            return;
        }
        Map<String, Object> projected = new LinkedHashMap<>((Map<String, Object>) data);
        for (TeamMemberDefinition member : runtime.getDefinition().getMembers()) {
            if (teamMemberId.equals(member.getTeamMemberId())) projected.put("sessionId", member.getSessionId());
        }
        if (historyManager != null) projected.put("structuredMessages", true);
        sink.publish(TeamEventEnvelope.next(
                runtime, teamMemberId, acpClientId, type, projected));
    }

    private final java.util.concurrent.atomic.AtomicLong revision =
            new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
    private long nextRevision() {
        return revision.updateAndGet(previous -> Math.max(previous + 1, System.currentTimeMillis()));
    }

    private void persist(TeamEventType type, Map<String, Object> data) {
        if (historyManager == null || !runtime.isAcceptingRequests()) return;
        data.put("messageId", type == TeamEventType.TOOL_CALL
                ? "tool:" + data.get("toolCallId") : java.util.UUID.randomUUID().toString());
        data.put("revision", nextRevision());
        JsonObject value = new com.google.gson.Gson().toJsonTree(data).getAsJsonObject();
        historyManager.addEventMessage(type.name(), value);
    }

    private void publishRendered(String content, boolean end) {
        if (!content.isEmpty()) {
            publish(TeamEventType.MESSAGE_CHUNK,
                    Collections.singletonMap("content", content));
        }
        if (end) {
            publish(TeamEventType.MESSAGE_COMPLETE, Collections.emptyMap());
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
