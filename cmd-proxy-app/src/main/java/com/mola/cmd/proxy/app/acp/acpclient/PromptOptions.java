package com.mola.cmd.proxy.app.acp.acpclient;

import com.mola.cmd.proxy.app.acp.channel.model.ChannelTurnContext;
import com.mola.cmd.proxy.app.acp.mcpauth.AuthPrincipalContext;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToTrace;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

/**
 * sendPrompt 的扩展参数。
 * <p>
 * 用于在不修改 sendPrompt 方法签名的前提下，传递额外的上下文控制参数。
 * 每次顶层调用独立构造；同一逻辑 turn 的 follow-up prompt 共享其实例，
 * 以记录信道回复尝试和幂等关闭状态，不依赖全局变量。
 */
public class PromptOptions {
    private final String authTurnId = UUID.randomUUID().toString();

    /** 是否为定时任务触发的执行场景。能力上下文仍按普通首轮规则注入。 */
    private boolean scheduleExecution;
    /** Durable journal row correlated with this scheduled turn. */
    private String scheduleExecutionId;
    /** Non-null only when this turn originated from an external channel message. */
    private ChannelTurnContext channelTurnContext;
    /** Channel-neutral identity inherited by derived work. */
    private AuthPrincipalContext authPrincipalContext;
    /** Shared by all ACP prompt rounds that belong to one logical channel turn. */
    private final AtomicInteger channelReplyAttempts = new AtomicInteger();
    private final AtomicBoolean channelTurnClosed = new AtomicBoolean(false);
    /** True when an internal TalkTo return restored a previously suspended channel origin. */
    private boolean restoredChannelContinuation;
    /** True only for an internal TalkTo mailbox turn, never for an external user channel turn. */
    private boolean inboundTalkTo;
    /** All sends in a logical turn share a cascade, including forwards to a third member. */
    private TalkToTrace talkToParent;
    /** Stable Starweave task identity for precise suspend/cancel handling. */
    private volatile String taskId;
    private volatile String taskEventId;
    private volatile Long taskEventSeq;
    private volatile Long taskRevision;
    private volatile Long taskContentVersion;
    private volatile boolean taskControl;
    /** A task notification to its publisher, without execution ownership. */
    private boolean taskNotification;
    private String clientMessageId;
    public PromptOptions setClientMessageId(String id) { clientMessageId = id; return this; }
    public synchronized String getClientMessageId() { return clientMessageId; }
    public synchronized String takeClientMessageId() {
        String id = clientMessageId;
        clientMessageId = null;
        return id;
    }

    public PromptOptions() {
    }

    /** A new execution has fresh counters and IDs, while retaining its business origin. */
    public PromptOptions forNewSession() {
        PromptOptions next = new PromptOptions();
        next.setChannelTurnContext(channelTurnContext);
        next.setAuthPrincipalContext(authPrincipalContext);
        next.talkToParent = talkToParent;
        next.taskNotification = taskNotification;
        if (isTaskTurn()) {
            next.setTaskContext(taskId, taskEventId, taskEventSeq, taskRevision,
                    taskContentVersion, false);
        }
        next.setClientMessageId(UUID.randomUUID().toString());
        return next;
    }

    public boolean isScheduleExecution() {
        return scheduleExecution;
    }

    public PromptOptions setScheduleExecution(boolean scheduleExecution) {
        this.scheduleExecution = scheduleExecution;
        return this;
    }

    public String getScheduleExecutionId() { return scheduleExecutionId; }

    public PromptOptions setScheduleExecutionId(String executionId) {
        this.scheduleExecutionId = executionId == null || executionId.trim().isEmpty()
                ? null : executionId.trim();
        return this;
    }

    public ChannelTurnContext getChannelTurnContext() {
        return channelTurnContext;
    }

    public boolean hasChannelTurnContext() {
        return channelTurnContext != null;
    }

    public PromptOptions setChannelTurnContext(ChannelTurnContext channelTurnContext) {
        this.channelTurnContext = channelTurnContext;
        if (channelTurnContext != null) {
            this.authPrincipalContext = new AuthPrincipalContext(
                    channelTurnContext.getSenderId(), channelTurnContext.getSenderDisplayName(),
                    "WECOM", channelTurnContext.getChannelId());
        }
        return this;
    }

    public AuthPrincipalContext getAuthPrincipalContext() { return authPrincipalContext; }
    public String getAuthTurnId() {
        return channelTurnContext == null ? authTurnId : channelTurnContext.getTurnId();
    }

    public PromptOptions setAuthPrincipalContext(AuthPrincipalContext context) {
        this.authPrincipalContext = context;
        return this;
    }

    public void markChannelReplyAttempt() {
        channelReplyAttempts.incrementAndGet();
    }

    public boolean hasChannelReplyAttempt() {
        return channelReplyAttempts.get() > 0;
    }

    public int getChannelReplyAttempts() {
        return channelReplyAttempts.get();
    }

    public boolean closeChannelTurnOnce() {
        return channelTurnClosed.compareAndSet(false, true);
    }

    public boolean isRestoredChannelContinuation() {
        return restoredChannelContinuation;
    }

    public boolean isInboundTalkTo() { return inboundTalkTo; }

    public PromptOptions setInboundTalkTo(boolean inboundTalkTo) {
        this.inboundTalkTo = inboundTalkTo;
        return this;
    }

    public synchronized PromptOptions addTalkToParent(String target, TalkToTrace trace) {
        if (trace != null) {
            if (talkToParent != null
                    && !talkToParent.getCascadeId().equals(trace.getCascadeId())) {
                throw new IllegalArgumentException("A turn cannot discard a distinct TalkTo cascade");
            }
            if (talkToParent == null || trace.getHopCount() > talkToParent.getHopCount()) {
                talkToParent = trace;
            }
        }
        return this;
    }

    public synchronized TalkToTrace talkToParentFor(String target) {
        if (talkToParent == null) {
            // Root at hop zero: the first outbound message is hop one. Reuse this root
            // for every tool call in this turn so changing targets cannot reset budgets.
            talkToParent = new TalkToTrace(null, null, null, 0, System.currentTimeMillis());
        }
        return talkToParent;
    }

    public boolean isTaskTurn() { return taskId != null && !taskId.trim().isEmpty(); }
    public String getTaskId() { return taskId; }
    public String getTaskEventId() { return taskEventId; }
    public Long getTaskEventSeq() { return taskEventSeq; }
    public Long getTaskRevision() { return taskRevision; }
    public Long getTaskContentVersion() { return taskContentVersion; }
    public boolean isTaskControl() { return taskControl; }
    public boolean isTaskNotification() { return taskNotification; }

    public static PromptOptions forTaskNotification() {
        PromptOptions options = new PromptOptions();
        options.taskNotification = true;
        return options;
    }

    public PromptOptions setTaskContext(String taskId, String eventId, Long eventSeq,
                                        Long revision, Long contentVersion,
                                        boolean taskControl) {
        if (taskId == null || taskId.trim().isEmpty()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        this.taskEventId = eventId;
        this.taskEventSeq = eventSeq;
        this.taskRevision = revision;
        this.taskContentVersion = contentVersion;
        this.taskControl = taskControl;
        this.taskId = taskId.trim();
        return this;
    }

    /** 默认选项（普通用户对话） */
    public static PromptOptions defaults() {
        return new PromptOptions();
    }

    /** 定时任务执行场景的选项 */
    public static PromptOptions forScheduleExecution() {
        return new PromptOptions().setScheduleExecution(true);
    }

    public static PromptOptions forScheduleExecution(AuthPrincipalContext context) {
        return forScheduleExecution().setAuthPrincipalContext(context);
    }

    public static PromptOptions forScheduleExecution(AuthPrincipalContext authContext,
                                                     ChannelTurnContext channelContext) {
        PromptOptions options = forScheduleExecution();
        if (channelContext != null) options.setChannelTurnContext(channelContext);
        if (authContext != null) options.setAuthPrincipalContext(authContext);
        return options;
    }

    public static PromptOptions forDerivedWork(AuthPrincipalContext context) {
        return new PromptOptions().setAuthPrincipalContext(context);
    }

    public static PromptOptions forTask(String taskId, String eventId, Long eventSeq,
                                        Long revision, Long contentVersion,
                                        boolean taskControl) {
        return new PromptOptions().setTaskContext(taskId, eventId, eventSeq,
                revision, contentVersion, taskControl);
    }

    public static PromptOptions forChannelReply(ChannelTurnContext context) {
        return new PromptOptions().setChannelTurnContext(context);
    }

    public static PromptOptions forRestoredChannelReply(ChannelTurnContext context) {
        PromptOptions options = forChannelReply(context);
        options.restoredChannelContinuation = true;
        return options;
    }
}
