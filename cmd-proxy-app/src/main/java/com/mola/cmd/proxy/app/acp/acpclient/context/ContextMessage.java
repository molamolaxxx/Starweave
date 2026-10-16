package com.mola.cmd.proxy.app.acp.acpclient.context;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ACP 会话上下文中的单条消息记录。
 * <p>
 * 支持四种角色：
 * <ul>
 *   <li>{@link Role#USER} — 用户输入</li>
 *   <li>{@link Role#ASSISTANT} — agent 回答</li>
 *   <li>{@link Role#TOOL} — 工具调用及结果</li>
 *   <li>{@link Role#EVENT} — UI 可恢复的结构化业务事件，不进入模型 prompt</li>
 * </ul>
 */
public class ContextMessage {

    public enum Role {
        USER, ASSISTANT, TOOL, EVENT
    }

    /** Identifies who supplied a USER-role prompt without removing it from model context. */
    public enum UserOrigin {
        USER, TASK, CHANNEL, TALK_TO, SCHEDULE
    }

    private String messageId;
    private long revision;
    // The complete model reply is retained when the UI has separate text segments.
    private boolean uiHidden;
    public boolean isUiHidden() { return uiHidden; }
    public ContextMessage withUiHidden(boolean hidden) {
        uiHidden = hidden;
        return this;
    }
    public String getMessageId() { return messageId; }
    public long getRevision() { return revision; }
    public ContextMessage withIdentity(String id, long version) {
        messageId = id;
        revision = version;
        return this;
    }

    private final Role role;
    private final String content;
    private final UserOrigin userOrigin;
    /** Original file names attached to this USER prompt; content remains in the session files dir. */
    private final List<String> attachments;

    // ---- TOOL 专用字段 ----
    private final String toolCallId;
    private final String toolName;
    private final String status;
    private final JsonObject rawInput;
    private final JsonObject rawOutput;

    // ---- EVENT 专用字段 ----
    private final String eventType;
    private final JsonObject eventData;

    /** 构造 USER / ASSISTANT 消息 */
    public ContextMessage(Role role, String content) {
        this(role, content, role == Role.USER ? UserOrigin.USER : null);
    }

    /** Constructs a USER message whose internal origin remains available to UI projections. */
    public ContextMessage(Role role, String content, UserOrigin userOrigin) {
        this(role, content, userOrigin, Collections.emptyList());
    }

    /** Constructs a USER / ASSISTANT message with UI-restorable attachment metadata. */
    public ContextMessage(Role role, String content, UserOrigin userOrigin,
                          List<String> attachments) {
        this.role = role;
        this.content = content;
        this.userOrigin = role == Role.USER
                ? (userOrigin == null ? UserOrigin.USER : userOrigin) : null;
        this.attachments = role == Role.USER && attachments != null && !attachments.isEmpty()
                ? Collections.unmodifiableList(new ArrayList<>(attachments)) : null;
        this.toolCallId = null;
        this.toolName = null;
        this.status = null;
        this.rawInput = null;
        this.rawOutput = null;
        this.eventType = null;
        this.eventData = null;
    }

    /** 构造 TOOL 消息 */
    public ContextMessage(String toolCallId, String toolName, String status,
                          JsonObject rawInput, JsonObject rawOutput) {
        this.role = Role.TOOL;
        this.content = null;
        this.userOrigin = null;
        this.attachments = null;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.status = status;
        this.rawInput = rawInput;
        this.rawOutput = rawOutput;
        this.eventType = null;
        this.eventData = null;
    }

    private ContextMessage(String eventType, JsonObject eventData) {
        this.role = Role.EVENT;
        this.content = null;
        this.userOrigin = null;
        this.attachments = null;
        this.toolCallId = null;
        this.toolName = null;
        this.status = null;
        this.rawInput = null;
        this.rawOutput = null;
        this.eventType = eventType;
        this.eventData = eventData == null ? new JsonObject() : eventData.deepCopy();
    }

    public static ContextMessage event(String eventType, JsonObject eventData) {
        if (eventType == null || eventType.trim().isEmpty()) {
            throw new IllegalArgumentException("eventType is required");
        }
        return new ContextMessage(eventType.trim(), eventData);
    }

    public Role getRole() { return role; }
    public String getContent() { return content; }
    public UserOrigin getUserOrigin() { return userOrigin; }
    public List<String> getAttachments() {
        return attachments == null ? Collections.emptyList() : attachments;
    }
    public boolean isVisibleUserMessage() {
        return role != Role.USER || userOrigin == null || userOrigin == UserOrigin.USER;
    }
    public String getToolCallId() { return toolCallId; }
    public String getToolName() { return toolName; }
    public String getStatus() { return status; }
    public JsonObject getRawInput() { return rawInput; }
    public JsonObject getRawOutput() { return rawOutput; }
    public String getEventType() { return eventType; }
    public JsonObject getEventData() {
        return eventData == null ? null : eventData.deepCopy();
    }

    @Override
    public String toString() {
        if (role == Role.TOOL) {
            return String.format("ContextMessage{role=TOOL, toolName='%s', toolCallId='%s', status='%s'}",
                    toolName, toolCallId, status);
        }
        if (role == Role.EVENT) {
            return String.format("ContextMessage{role=EVENT, eventType='%s'}", eventType);
        }
        String preview = content != null && content.length() > 80
                ? content.substring(0, 80) + "..." : content;
        return String.format("ContextMessage{role=%s, content='%s'}", role, preview);
    }
}
