package com.mola.cmd.proxy.app.acp.team.talkto;

import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseContentRenderer;
import com.mola.cmd.proxy.app.acp.channel.ChannelTalkToMessage;
import com.mola.cmd.proxy.app.acp.mcpauth.AuthPrincipalContext;
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher;
import com.mola.cmd.proxy.app.acp.talkto.TalkToCircuitBreaker;
import com.mola.cmd.proxy.app.acp.talkto.model.ContactRef;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToMessage;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToBatchMessage;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToRequest;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToTrace;
import com.mola.cmd.proxy.app.acp.team.TeamClientRegistry;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventSink;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventType;
import com.mola.cmd.proxy.app.acp.team.listener.TeamMemberStateObserver;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamContactRef;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberState;
import com.mola.cmd.proxy.app.acp.team.model.TeamState;
import com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime;
import com.google.gson.Gson;

import java.io.IOException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.LongSupplier;
import java.util.function.BooleanSupplier;

/**
 * 单个 Team 共享的严格队内 talkTo 路由器。
 *
 * <p>它完全绕开普通 robot registry/crossTalkTo，目标只接受当前 Team
 * 的不可变 teamMemberId。每个目标 member 有独立 FIFO inbox。</p>
 */
public final class TeamTalkToDispatcher extends TalkToDispatcher
        implements AutoCloseable {

    private static final Gson GSON = new Gson();

    public static final int INBOX_CAPACITY = 10;
    public static final long INBOX_TTL_MS = 30L * 60L * 1000L;
    public static final long DEDUP_WINDOW_MS = 60_000L;
    public static final int MAX_DEPTH = TalkToCircuitBreaker.MAX_HOPS;

    private final TeamRuntime runtime;
    private final TeamClientRegistry clientRegistry;
    private final TeamEventSink eventSink;
    private final TeamMemberStateObserver stateObserver;
    private final LongSupplier clock;
    private final long inboxTtlMillis;
    private final long dedupWindowMillis;
    private final BooleanSupplier grantActive;
    private final ConcurrentHashMap<String, LinkedBlockingQueue<QueuedMessage>> inboxes =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> recentMessages =
            new ConcurrentHashMap<>();
    private volatile boolean closed;

    public TeamTalkToDispatcher(TeamRuntime runtime,
                                TeamClientRegistry clientRegistry,
                                TeamEventSink eventSink) {
        this(runtime, clientRegistry, eventSink, TeamMemberStateObserver.NOOP);
    }

    public TeamTalkToDispatcher(TeamRuntime runtime,
                                TeamClientRegistry clientRegistry,
                                TeamEventSink eventSink,
                                TeamMemberStateObserver stateObserver) {
        this(runtime, clientRegistry, eventSink, System::currentTimeMillis,
                INBOX_TTL_MS, DEDUP_WINDOW_MS, stateObserver, () -> true);
    }

    public TeamTalkToDispatcher(TeamRuntime runtime,
                                TeamClientRegistry clientRegistry,
                                TeamEventSink eventSink,
                                TeamMemberStateObserver stateObserver,
                                BooleanSupplier grantActive) {
        this(runtime, clientRegistry, eventSink, System::currentTimeMillis,
                INBOX_TTL_MS, DEDUP_WINDOW_MS, stateObserver, grantActive);
    }

    TeamTalkToDispatcher(TeamRuntime runtime,
                         TeamClientRegistry clientRegistry,
                         TeamEventSink eventSink,
                         LongSupplier clock,
                         long inboxTtlMillis,
                         long dedupWindowMillis) {
        this(runtime, clientRegistry, eventSink, clock, inboxTtlMillis,
                dedupWindowMillis, TeamMemberStateObserver.NOOP, () -> true);
    }

    TeamTalkToDispatcher(TeamRuntime runtime,
                         TeamClientRegistry clientRegistry,
                         TeamEventSink eventSink,
                         LongSupplier clock,
                         long inboxTtlMillis,
                         long dedupWindowMillis,
                         TeamMemberStateObserver stateObserver) {
        this(runtime, clientRegistry, eventSink, clock, inboxTtlMillis,
                dedupWindowMillis, stateObserver, () -> true);
    }

    TeamTalkToDispatcher(TeamRuntime runtime,
                         TeamClientRegistry clientRegistry,
                         TeamEventSink eventSink,
                         LongSupplier clock,
                         long inboxTtlMillis,
                         long dedupWindowMillis,
                         TeamMemberStateObserver stateObserver,
                         BooleanSupplier grantActive) {
        super();
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.clientRegistry = Objects.requireNonNull(clientRegistry, "clientRegistry");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.stateObserver = Objects.requireNonNull(stateObserver, "stateObserver");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (inboxTtlMillis < 1L || dedupWindowMillis < 1L) {
            throw new IllegalArgumentException("talkTo TTL windows must be positive");
        }
        this.inboxTtlMillis = inboxTtlMillis;
        this.dedupWindowMillis = dedupWindowMillis;
        this.grantActive = Objects.requireNonNull(grantActive, "grantActive");
    }

    @Override
    public boolean managesTalkToEvents() {
        return true;
    }

    @Override
    public String deliver(TalkToRequest request, String senderName,
                          String ignoredSenderChatterId,
                          List<ContactRef> ignoredContacts) {
        return deliverTeam(request, senderName, null);
    }

    private String deliverTeam(TalkToRequest request, String senderName,
                               AuthPrincipalContext authPrincipalContext) {
        if (request == null) {
            return reject(null, senderName, null, "", 0,
                    "INVALID_REQUEST", "talk_to request is required");
        }
        String content = request.getContent() == null ? "" : request.getContent().trim();
        TeamDefinition team = runtime.getDefinition();
        TeamMemberDefinition sender = findMember(team, senderName);
        TeamMemberDefinition target = findMember(team, request.getTarget());
        TeamContactRef rosterTarget = findContact(team, request.getTarget());
        if (closed || !runtime.isAcceptingRequests()) {
            return reject(sender, senderName, target, content, request.getDepth(),
                    "TEAM_CLOSED", "Team is not accepting talk_to messages");
        }
        if (team.getState() != TeamState.READY) {
            return reject(sender, senderName, target, content, request.getDepth(),
                    "TEAM_NOT_READY", "Team is not READY");
        }
        if (!grantActive.getAsBoolean()) {
            return reject(sender, senderName, target, content, request.getDepth(),
                    "TEAM_GRANT_REVOKED", "remote Team sharing grant has been revoked");
        }
        if (sender == null) {
            return reject(null, senderName, target, content, request.getDepth(),
                    "SENDER_NOT_IN_TEAM", "sender is not a member of this Team");
        }
        if (request.getDepth() < 0) {
            return reject(sender, senderName, target, content, request.getDepth(),
                    "INVALID_DEPTH", "消息传递深度不能为负数");
        }
        if (target == null && rosterTarget == null) {
            return reject(sender, senderName, null, content, request.getDepth(),
                    "TARGET_NOT_IN_TEAM",
                    "target 必须是当前 Team 通讯录中的 teamMemberId");
        }
        if (sender.getTeamMemberId().equals(rosterTarget.getTargetTeamMemberId())) {
            return reject(sender, senderName, target, content, request.getDepth(),
                    "SELF_TARGET", "不能向自己发送 talk_to 消息");
        }
        if (!team.canCommunicate(sender.getTeamMemberId(),
                rosterTarget.getTargetTeamMemberId())) {
            return reject(sender, senderName, target, content, request.getDepth(),
                    "TEAM_COMMUNICATION_FORBIDDEN",
                    "队长模式下普通队员只能联系队长");
        }
        if (content.isEmpty()) {
            return reject(sender, senderName, target, content, request.getDepth(),
                    "EMPTY_CONTENT", "content 不能为空");
        }
        if (request.getParentTrace() != null && !circuitBreaker.canDeliver(request.getParentTrace())) {
            return rejectCircuit(sender, target, rosterTarget, content,
                    circuitBreaker.admit(request.getParentTrace(), sender.getTeamMemberId(),
                            rosterTarget.getTargetTeamMemberId()));
        }

        long now = clock.getAsLong();
        cleanExpiredDedup(now);
        String dedupKey = team.getTeamId() + "/" + sender.getTeamMemberId()
                + "→" + rosterTarget.getTargetTeamMemberId() + ":" + contentDigest(content);
        Long previous = recentMessages.putIfAbsent(dedupKey, now);
        if (previous != null && now - previous < dedupWindowMillis) {
            return reject(sender, senderName, target, content, request.getDepth(),
                    "DUPLICATE", "短时间内相同消息已被 Team talk_to 接收");
        }
        if (previous != null) {
            recentMessages.replace(dedupKey, previous, now);
        }

        String targetMemberId = rosterTarget.getTargetTeamMemberId();
        TalkToCircuitBreaker.Admission circuit = circuitBreaker.admit(
                request.getParentTrace(), sender.getTeamMemberId(), targetMemberId);
        if (!circuit.isAccepted()) {
            recentMessages.remove(dedupKey, now);
            if (circuit.isCircuitOpen()) {
                return rejectCircuit(sender, target, rosterTarget, content, circuit);
            }
            return reject(sender, senderName, target, content, request.getDepth(),
                    circuit.getReason(), "当前消息超过通信限额（current="
                            + circuit.getCurrent() + ", limit=" + circuit.getLimit()
                            + "），消息未投递，但通信链保持可用，cascadeId="
                            + circuit.getTrace().getCascadeId());
        }
        String messageId = circuit.getTrace().getMessageId();
        int nextDepth = circuit.getTrace().getHopCount();
        TeamTalkToMessage message = new TeamTalkToMessage(
                messageId, sender.getTeamMemberId(), content, nextDepth,
                authPrincipalContext, circuit.getTrace());
        if (target == null || team.isMixedPlacement()) {
            long expiresAt = now + inboxTtlMillis;
            Map<String, Object> route = new LinkedHashMap<>();
            route.put("messageId", messageId);
            route.put("teamId", team.getTeamId());
            route.put("senderTeamMemberId", sender.getTeamMemberId());
            route.put("senderAcpClientId", sender.getAcpClientId());
            route.put("senderDisplayName", sender.getDisplayName());
            route.put("targetTeamMemberId", rosterTarget.getTargetTeamMemberId());
            route.put("targetAcpClientId", rosterTarget.getTargetAcpClientId());
            route.put("targetDisplayName", rosterTarget.getDisplayName());
            route.put("content", content);
            route.put("depth", nextDepth);
            route.put("cascadeId", circuit.getTrace().getCascadeId());
            route.put("cascadeIds", circuit.getTrace().getCascadeIds());
            route.put("parentMessageId", circuit.getTrace().getParentMessageId());
            route.put("cascadeStartedAt", circuit.getTrace().getStartedAt());
            route.put("createdAt", now);
            route.put("expiresAt", expiresAt);
            route.put("delivery", "ROUTE_REQUESTED");
            if (authPrincipalContext != null) {
                route.put("authPrincipalId", authPrincipalContext.getPrincipalId());
                route.put("authPrincipalName", authPrincipalContext.getDisplayName());
                route.put("authSourceType", authPrincipalContext.getSourceType());
                route.put("authSourceId", authPrincipalContext.getSourceId());
            }
            TeamEventEnvelope routeEvent = TeamEventEnvelope.next(runtime,
                    sender.getTeamMemberId(), sender.getAcpClientId(),
                    TeamEventType.TALK_TO_ROUTE_REQUEST, route);
            if (!eventSink.tryPublish(routeEvent)) {
                recentMessages.remove(dedupKey, now);
                circuitBreaker.rollback(circuit, sender.getTeamMemberId(), targetMemberId);
                return "[talkTo 结果]\n发送失败：跨实例路由队列繁忙，消息未提交，"
                        + "请稍后重试。";
            }
            publish(sender, TeamEventType.TALK_TO_SEND, route);
            publishCard(sender, rosterTarget, TeamEventType.TALK_TO_SEND,
                    messageId, content, "ROUTE_REQUESTED", null);
            return "[talkTo 结果]\n跨实例消息已提交路由，目标成员 "
                    + rosterTarget.getDisplayName() + "（"
                    + rosterTarget.getTargetTeamMemberId() + "）。";
        }
        AcpClient targetClient = clientRegistry.get(
                team.getTeamId(), target.getTeamMemberId()).orElse(null);
        if (targetClient != null) {
            synchronized (targetClient) {
                if (targetClient.getState() == AbstractAcpClient.State.SLEEP
                        || targetClient.getState() == AbstractAcpClient.State.ERROR) {
                    try {
                        targetClient.prepareForMessage();
                        stateObserver.onState(team.getTeamId(), target.getTeamMemberId(),
                                TeamMemberState.READY, null);
                    } catch (IOException | RuntimeException wakeFailure) {
                        recentMessages.remove(dedupKey, now);
                        circuitBreaker.rollback(circuit, sender.getTeamMemberId(), targetMemberId);
                        return "[talkTo 结果]\n发送失败：目标成员恢复失败："
                                + wakeFailure.getMessage();
                    }
                }
                if ((target.getState() == TeamMemberState.READY
                        || target.getState() == TeamMemberState.SLEEP
                        || target.getState() == TeamMemberState.ERROR)
                        && targetClient.getState() == AbstractAcpClient.State.READY) {
                    stateObserver.onState(team.getTeamId(), target.getTeamMemberId(),
                            TeamMemberState.BUSY, null);
                    publish(sender, TeamEventType.TALK_TO_SEND,
                            eventData(messageId, sender, target, content, nextDepth,
                                    "DELIVERED", null, null, null));
                    publishCard(sender, target, TeamEventType.TALK_TO_SEND,
                            messageId, content, "DELIVERED", null);
                    publish(target, TeamEventType.TALK_TO_RECEIVE,
                            eventData(messageId, sender, target, content, nextDepth,
                                    "DELIVERED", null, null, null));
                    publishCard(target, sender, TeamEventType.TALK_TO_RECEIVE,
                            messageId, content, "DELIVERED", null);
                    sendInboundMessage(targetClient, message);
                    return "[talkTo 结果]\n已成功将消息发送给同队成员 "
                            + target.getDisplayName() + "（" + target.getTeamMemberId()
                            + "）。对方会处理你的请求，你可以继续当前工作。";
                }
            }
        }

        if (targetClient != null
                && (target.getState() == TeamMemberState.BUSY
                || targetClient.getState() == AbstractAcpClient.State.BUSY)) {
            LinkedBlockingQueue<QueuedMessage> inbox = inboxes.computeIfAbsent(
                    target.getTeamMemberId(),
                    ignored -> new LinkedBlockingQueue<>(INBOX_CAPACITY));
            purgeExpired(inbox, now);
            long expiresAt = now + inboxTtlMillis;
            if (inbox.offer(new QueuedMessage(
                    messageId, message, sender, null, target, expiresAt))) {
                int position = inbox.size();
                publish(sender, TeamEventType.TALK_TO_QUEUED,
                        eventData(messageId, sender, target, content, nextDepth,
                                "QUEUED", position, expiresAt, null));
                publishCard(sender, target, TeamEventType.TALK_TO_QUEUED,
                        messageId, content, "QUEUED", null);
                return "[talkTo 结果]\n" + target.getDisplayName()
                        + " 当前正忙，消息已放入其 Team inbox（第 "
                        + position + "/" + INBOX_CAPACITY
                        + " 条，30 分钟内有效）。对方空闲后会自动收到。";
            }
            recentMessages.remove(dedupKey, now);
            circuitBreaker.rollback(circuit, sender.getTeamMemberId(), targetMemberId);
            return reject(sender, senderName, target, content, request.getDepth(),
                    "INBOX_FULL", "目标 Team inbox 已满（"
                            + INBOX_CAPACITY + "/" + INBOX_CAPACITY + "）");
        }

        recentMessages.remove(dedupKey, now);
        circuitBreaker.rollback(circuit, sender.getTeamMemberId(), targetMemberId);
        return reject(sender, senderName, target, content, request.getDepth(),
                "TARGET_NOT_READY", "目标 Team member 当前不可投递且不处于可排队的 BUSY 状态");
    }

    @Override
    public String deliver(TalkToRequest request, String senderName,
                          String senderChatterId, String ignoredSenderGroupId,
                          List<ContactRef> contacts) {
        return deliver(request, senderName, senderChatterId, ignoredSenderGroupId,
                contacts, null);
    }

    @Override
    public String deliver(TalkToRequest request, String senderName,
                          String senderChatterId, String ignoredSenderGroupId,
                          List<ContactRef> contacts,
                          AuthPrincipalContext authPrincipalContext) {
        if (request != null && request.getTarget() != null
                && request.getTarget().startsWith("channel:")) {
            TeamDefinition team = runtime.getDefinition();
            if (!team.isBusinessEntryMember(senderName)) {
                return "[talkTo 结果]\n发送失败（TEAM_COMMUNICATION_FORBIDDEN）："
                        + "队长模式下只有队长可以联系外部信道。";
            }
            String senderKey = "team:" + team.getTeamId()
                    + ":" + senderName;
            String result = super.deliver(
                    request, senderName, senderChatterId, senderKey, contacts,
                    authPrincipalContext);
            pushExternalTalkToCard(senderName, "TALK_TO_SEND",
                    channelLabel(request.getTarget()), request.getContent());
            return result;
        }
        return deliverTeam(request, senderName, authPrincipalContext);
    }

    @Override
    public InboundDeliveryResult deliverInbound(String teamMemberId, AcpClient targetClient,
                                                 TalkToMessage message) {
        return deliverInbound(teamMemberId, targetClient, message,
                UUID.randomUUID().toString(), null);
    }

    private InboundDeliveryResult deliverInbound(
            String teamMemberId, AcpClient targetClient, TalkToMessage message,
            String messageId, TeamContactRef remoteSender) {
        if (teamMemberId == null || targetClient == null || message == null || closed) {
            return InboundDeliveryResult.rejected("invalid external Team delivery");
        }
        if (!canDeliverMessage(message)) {
            return InboundDeliveryResult.rejected("TalkTo cascade is closed or expired");
        }
        TeamMemberDefinition target = findMember(runtime.getDefinition(), teamMemberId);
        if (target == null || runtime.getDefinition().getState() != TeamState.READY) {
            return InboundDeliveryResult.rejected("Team member not ready");
        }
        synchronized (targetClient) {
            if (targetClient.getState() == AbstractAcpClient.State.SLEEP
                    || targetClient.getState() == AbstractAcpClient.State.ERROR) {
                try {
                    targetClient.prepareForMessage();
                    stateObserver.onState(runtime.getDefinition().getTeamId(), teamMemberId,
                            TeamMemberState.READY, null);
                } catch (IOException | RuntimeException wakeFailure) {
                    return InboundDeliveryResult.rejected(
                            "agent wake failed: " + wakeFailure.getMessage());
                }
            }
            if ((target.getState() == TeamMemberState.READY
                    || target.getState() == TeamMemberState.SLEEP
                        || target.getState() == TeamMemberState.ERROR)
                    && targetClient.getState() == AbstractAcpClient.State.READY) {
                stateObserver.onState(runtime.getDefinition().getTeamId(), teamMemberId,
                        TeamMemberState.BUSY, null);
                pushIncomingMessageCard(targetClient, message);
                sendInboundMessage(targetClient, message);
                return InboundDeliveryResult.direct();
            }
        }
        if (target.getState() != TeamMemberState.BUSY
                && targetClient.getState() != AbstractAcpClient.State.BUSY) {
            return InboundDeliveryResult.rejected("Team member not busy or ready");
        }
        long now = clock.getAsLong();
        LinkedBlockingQueue<QueuedMessage> inbox = inboxes.computeIfAbsent(
                teamMemberId, ignored -> new LinkedBlockingQueue<>(INBOX_CAPACITY));
        purgeExpired(inbox, now);
        return inbox.offer(new QueuedMessage(messageId, message,
                        null, remoteSender, target, now + inboxTtlMillis))
                ? InboundDeliveryResult.queued(inbox.size())
                : InboundDeliveryResult.rejected("inbox full");
    }

    public InboundDeliveryResult deliverRemoteInbound(String messageId,
                                                       String senderTeamMemberId,
                                                       String teamMemberId,
                                                       String content, int depth) {
        return deliverRemoteInbound(messageId, senderTeamMemberId, teamMemberId,
                content, depth, null);
    }

    public InboundDeliveryResult deliverRemoteInbound(String messageId,
                                                       String senderTeamMemberId,
                                                       String teamMemberId,
                                                       String content, int depth,
                                                       AuthPrincipalContext authPrincipalContext) {
        return deliverRemoteInbound(messageId, senderTeamMemberId, teamMemberId,
                content, depth, null, null, 0L, authPrincipalContext);
    }

    public InboundDeliveryResult deliverRemoteInbound(String messageId,
                                                       String senderTeamMemberId,
                                                       String teamMemberId,
                                                       String content, int depth,
                                                       String cascadeId,
                                                       String parentMessageId,
                                                       long cascadeStartedAt,
                                                       AuthPrincipalContext authPrincipalContext) {
        return deliverRemoteInbound(messageId, senderTeamMemberId, teamMemberId, content, depth,
                cascadeId, parentMessageId, cascadeStartedAt, null, authPrincipalContext);
    }

    public InboundDeliveryResult deliverRemoteInbound(String messageId, String senderTeamMemberId,
            String teamMemberId, String content, int depth, String cascadeId, String parentMessageId,
            long cascadeStartedAt, java.util.List<String> cascadeIds, AuthPrincipalContext authPrincipalContext) {
        TeamDefinition team = runtime.getDefinition();
        if (findContact(team, senderTeamMemberId) == null
                || findContact(team, teamMemberId) == null) {
            return InboundDeliveryResult.rejected("sender or target is outside Team roster");
        }
        if (!team.canCommunicate(senderTeamMemberId, teamMemberId)) {
            return InboundDeliveryResult.rejected(
                    "TEAM_COMMUNICATION_FORBIDDEN: captain Team members may communicate only with captain");
        }
        AcpClient targetClient = clientRegistry.get(
                team.getTeamId(), teamMemberId).orElse(null);
        if (targetClient == null) return InboundDeliveryResult.rejected("target client absent");
        TeamContactRef sender = findContact(
                team, senderTeamMemberId);
        TalkToTrace trace = new TalkToTrace(cascadeId, messageId,
                parentMessageId, depth, cascadeStartedAt).withCascadeIds(cascadeIds);
        InboundDeliveryResult result = deliverInbound(
                teamMemberId, targetClient,
                new TeamTalkToMessage(messageId, senderTeamMemberId, content, depth,
                        authPrincipalContext, trace),
                messageId, sender);
        if (result.getStatus() != InboundDeliveryResult.Status.REJECTED) {
            TeamMemberDefinition target = findMember(runtime.getDefinition(), teamMemberId);
            Map<String, Object> data = remoteEventData(
                    messageId, sender, senderTeamMemberId, target, content, depth,
                    result.getStatus().name(),
                    result.getQueuePosition() > 0
                            ? result.getQueuePosition() : null,
                    null);
            publish(target, result.getStatus() == InboundDeliveryResult.Status.QUEUED
                    ? TeamEventType.TALK_TO_QUEUED : TeamEventType.TALK_TO_RECEIVE, data);
            if (result.getStatus() == InboundDeliveryResult.Status.DIRECT) {
                publishCard(target, sender, TeamEventType.TALK_TO_RECEIVE,
                        messageId, content, "DIRECT", null);
            }
        }
        return result;
    }

    /** UI-only coordinator control. Never send a prompt for a terminated communication chain. */
    public void openRemoteCircuit(String localMemberId, String peerMemberId,
                                  TalkToTrace trace, String reason) {
        TeamMemberDefinition local = findMember(runtime.getDefinition(), localMemberId);
        TeamContactRef peer = findContact(runtime.getDefinition(), peerMemberId);
        TalkToCircuitBreaker.Admission opened = circuitBreaker.openCascade(trace, reason);
        if (local != null) rejectCircuit(local, null, peer,
                "通信链已由协调器终止，后续排队消息不再触发 Agent。", opened);
    }

    @Override
    public TalkToMessage pollInbox(String teamMemberId) {
        LinkedBlockingQueue<QueuedMessage> inbox = inboxes.get(teamMemberId);
        if (closed) return null;
        if (inbox == null) return null;
        long now = clock.getAsLong();
        purgeExpired(inbox, now);
        QueuedMessage queued = inbox.poll();
        while (queued != null && !canDeliverMessage(queued.message)) {
            queued = inbox.poll();
        }
        if (queued == null) return null;
        stateObserver.onState(runtime.getDefinition().getTeamId(),
                queued.target.getTeamMemberId(), TeamMemberState.BUSY, null);
        if (queued.sender != null) {
            publish(queued.target, TeamEventType.TALK_TO_RECEIVE,
                    eventData(queued.messageId, queued.sender, queued.target,
                            queued.message.getContent(), queued.message.getDepth(),
                            "DELIVERED_FROM_INBOX", null, queued.expiresAt, null));
            publishCard(queued.target, queued.sender, TeamEventType.TALK_TO_RECEIVE,
                    queued.messageId, queued.message.getContent(),
                    "DELIVERED_FROM_INBOX", null);
        } else if (queued.remoteSender != null) {
            publish(queued.target, TeamEventType.TALK_TO_RECEIVE,
                    remoteEventData(queued.messageId, queued.remoteSender,
                            queued.message.getSender(), queued.target,
                            queued.message.getContent(), queued.message.getDepth(),
                            "DELIVERED_FROM_INBOX", null, queued.expiresAt));
            publishCard(queued.target, queued.remoteSender,
                    TeamEventType.TALK_TO_RECEIVE, queued.messageId,
                    queued.message.getContent(), "DELIVERED_FROM_INBOX", null);
        }
        return queued.message;
    }

    @Override
    public TalkToMessage pollInboxBatch(String teamMemberId, String ignoredGroupId,
                                        int maxMessages) {
        TalkToMessage first = pollInbox(teamMemberId);
        if (first == null || maxMessages < 2 || !first.isBatchable()) return first;
        LinkedBlockingQueue<QueuedMessage> inbox = inboxes.get(teamMemberId);
        if (inbox == null) return first;
        java.util.ArrayList<TalkToMessage> batch = new java.util.ArrayList<>();
        batch.add(first);
        while (batch.size() < Math.min(maxMessages, INBOX_BATCH_SIZE)) {
            purgeExpired(inbox, clock.getAsLong());
            QueuedMessage candidate = inbox.peek();
            if (candidate == null
                    || !TalkToBatchMessage.canAppend(batch, candidate.message)) break;
            TalkToMessage drained = pollInbox(teamMemberId);
            if (drained == null) break;
            batch.add(drained);
        }
        return batch.size() == 1 ? first : new TalkToBatchMessage(batch);
    }

    @Override
    public void pushIncomingMessageCard(AcpClient targetClient,
                                        TalkToMessage message) {
        // 队内卡片已在 direct deliver / pollInbox 时由 eventSink 发布。
        // 外部信道没有 Team member sender，必须按目标 member envelope 单独发布。
        if (!(message instanceof ChannelTalkToMessage) || targetClient == null) {
            return;
        }
        ChannelTalkToMessage channelMessage = (ChannelTalkToMessage) message;
        AcpClientIdentity identity = targetClient.getClientIdentity();
        if (identity != null && identity.isTeam()) {
            pushExternalTalkToCard(identity.getTeamMemberId(), "TALK_TO_RECEIVE",
                    "channel:" + channelMessage.getChannelDisplayName(),
                    channelMessage.getContent());
        }
    }

    private void pushExternalTalkToCard(String teamMemberId, String eventType,
                                        String target, String content) {
        TeamMemberDefinition member = findMember(runtime.getDefinition(), teamMemberId);
        if (member == null) return;
        TeamEventType type = "TALK_TO_RECEIVE".equals(eventType)
                ? TeamEventType.TALK_TO_RECEIVE : TeamEventType.TALK_TO_SEND;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", eventType);
        event.put("robotName", target);
        event.put("content", content == null ? "" : content);
        event.put("cardType", "CHANNEL_TALK_TO");
        event.put("direction", type == TeamEventType.TALK_TO_RECEIVE
                ? "RECEIVE" : "SEND");
        event.put("channelTarget", target);
        publish(member, type, event);

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("content", AcpResponseContentRenderer.talkToCardContent(
                eventType, target, content));
        card.put("cardType", "CHANNEL_TALK_TO");
        card.put("direction", event.get("direction"));
        card.put("channelTarget", target);
        publish(member, TeamEventType.MESSAGE_CHUNK, card);
    }

    private static String channelLabel(String target) {
        if (target == null) return "channel:unknown";
        int routeIndex = target.indexOf(":r_", "channel:".length());
        return routeIndex < 0 ? target : target.substring(0, routeIndex);
    }

    public int inboxSize(String teamMemberId) {
        LinkedBlockingQueue<QueuedMessage> inbox = inboxes.get(teamMemberId);
        if (inbox == null) {
            return 0;
        }
        purgeExpired(inbox, clock.getAsLong());
        return inbox.size();
    }

    public void removeMember(String teamMemberId) {
        LinkedBlockingQueue<QueuedMessage> removed = inboxes.remove(teamMemberId);
        if (removed != null) removed.clear();
    }

    public int dedupSize() {
        cleanExpiredDedup(clock.getAsLong());
        return recentMessages.size();
    }

    @Override
    public void close() {
        closed = true;
        inboxes.clear();
        recentMessages.clear();
        circuitBreaker.clear();
    }

    private String reject(TeamMemberDefinition sender, String senderName,
                          TeamMemberDefinition target, String content, int depth,
                          String reason, String message) {
        if (sender != null) {
            String messageId = UUID.randomUUID().toString();
            publish(sender, TeamEventType.TALK_TO_REJECTED,
                    eventData(messageId, sender, target,
                            content, depth, "REJECTED", null, null, reason));
            if (target != null && !sender.getTeamMemberId().equals(
                    target.getTeamMemberId())) {
                publishCard(sender, target, TeamEventType.TALK_TO_REJECTED,
                        messageId, content,
                        "REJECTED", reason);
            }
        }
        return "[talkTo 结果]\n发送失败（" + reason + "）：" + message + "。";
    }

    private String rejectCircuit(TeamMemberDefinition sender,
                                 TeamMemberDefinition target,
                                 TeamContactRef rosterTarget,
                                 String content,
                                 TalkToCircuitBreaker.Admission admission) {
        String result = circuitOpenResult(admission, sender.getTeamMemberId(),
                rosterTarget == null ? "" : rosterTarget.getTargetTeamMemberId());
        if (!circuitBreaker.claimNotification(admission.getTrace().getCascadeId())) return result;
        Map<String, Object> data = eventData(admission.getTrace().getMessageId(),
                sender, target, content, admission.getTrace().getHopCount(),
                "CIRCUIT_OPEN", null, null, admission.getReason());
        data.put("cascadeId", admission.getTrace().getCascadeId());
        data.put("cascadeIds", admission.getTrace().getCascadeIds());
        data.put("parentMessageId", admission.getTrace().getParentMessageId());
        data.put("cascadeStartedAt", admission.getTrace().getStartedAt());
        if (target == null && rosterTarget != null) {
            data.put("targetTeamMemberId", rosterTarget.getTargetTeamMemberId());
            data.put("targetDisplayName", rosterTarget.getDisplayName());
        }
        publish(sender, TeamEventType.TALK_TO_CIRCUIT_OPENED, data);
        if (target != null) {
            publishCard(sender, target, TeamEventType.TALK_TO_CIRCUIT_OPENED,
                    admission.getTrace().getMessageId(), content,
                    "CIRCUIT_OPEN", admission.getReason());
        } else if (rosterTarget != null) {
            publishCard(sender, rosterTarget, TeamEventType.TALK_TO_CIRCUIT_OPENED,
                    admission.getTrace().getMessageId(), content,
                    "CIRCUIT_OPEN", admission.getReason());
        }
        return result;
    }

    private void purgeExpired(LinkedBlockingQueue<QueuedMessage> inbox,
                              long now) {
        QueuedMessage head;
        while ((head = inbox.peek()) != null && head.expiresAt <= now) {
            QueuedMessage expired = inbox.poll();
            if (expired != null && expired.sender != null) {
                publish(expired.sender, TeamEventType.TALK_TO_REJECTED,
                        eventData(expired.messageId, expired.sender,
                                expired.target, expired.message.getContent(),
                                expired.message.getDepth(), "EXPIRED", null,
                                expired.expiresAt, "TTL_EXPIRED"));
                publishCard(expired.sender, expired.target,
                        TeamEventType.TALK_TO_REJECTED,
                        expired.messageId,
                        expired.message.getContent(), "EXPIRED", "TTL_EXPIRED");
            }
        }
        // 不在这里移除空队列：deliver 可能刚通过 computeIfAbsent 创建它，
        // 随后仍要 offer。每 Team 最多 6 个 member，空队列由 close/delete 统一清理。
    }

    private void cleanExpiredDedup(long now) {
        recentMessages.entrySet().removeIf(
                entry -> now - entry.getValue() >= dedupWindowMillis);
    }

    private void publish(TeamMemberDefinition envelopeMember,
                         TeamEventType type, Map<String, Object> data) {
        if (envelopeMember == null || !runtime.isAcceptingRequests()) {
            return;
        }
        try {
            if (type == TeamEventType.TALK_TO_SEND
                    || type == TeamEventType.TALK_TO_RECEIVE
                    || type == TeamEventType.TALK_TO_QUEUED
                    || type == TeamEventType.TALK_TO_REJECTED
                    || type == TeamEventType.TALK_TO_CIRCUIT_OPENED) {
                clientRegistry.get(runtime.getDefinition().getTeamId(),
                                envelopeMember.getTeamMemberId())
                        .ifPresent(client -> client.getHistoryManager().addEventMessage(
                                type.name(), GSON.toJsonTree(data).getAsJsonObject()));
            }
            eventSink.publish(TeamEventEnvelope.next(runtime,
                    envelopeMember.getTeamMemberId(),
                    envelopeMember.getAcpClientId(), type, data));
        } catch (RuntimeException ignored) {
            // MolaChat 投影事件失败不改变 Team 内本地投递结果。
        }
    }

    private void publishCard(TeamMemberDefinition envelopeMember,
                             TeamMemberDefinition peer,
                             TeamEventType talkToType, String messageId,
                             String content, String delivery, String reason) {
        if (peer == null) return;
        publishCard(envelopeMember, peer.getTeamMemberId(), peer.getDisplayName(),
                talkToType, messageId, content, delivery, reason);
    }

    private void publishCard(TeamMemberDefinition envelopeMember,
                             TeamContactRef peer,
                             TeamEventType talkToType, String messageId,
                             String content, String delivery, String reason) {
        if (peer == null) return;
        publishCard(envelopeMember, peer.getTargetTeamMemberId(),
                peer.getDisplayName(), talkToType, messageId, content,
                delivery, reason);
    }

    private void publishCard(TeamMemberDefinition envelopeMember,
                             String peerTeamMemberId, String peerDisplayName,
                             TeamEventType talkToType, String messageId,
                             String content, String delivery, String reason) {
        if (envelopeMember == null || peerTeamMemberId == null
                || envelopeMember.getTeamMemberId().equals(peerTeamMemberId)) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", TeamTalkToCardRenderer.render(
                talkToType.name(), peerTeamMemberId,
                peerDisplayName, content, delivery, reason));
        data.put("cardType", "TEAM_TALK_TO");
        data.put("messageId", messageId);
        data.put("direction", talkToType == TeamEventType.TALK_TO_RECEIVE
                ? "RECEIVE" : "SEND");
        data.put("cardTargetTeamMemberId", peerTeamMemberId);
        data.put("delivery", delivery);
        if (reason != null) data.put("reason", reason);
        publish(envelopeMember, TeamEventType.MESSAGE_CHUNK, data);
    }

    private Map<String, Object> eventData(
            String messageId, TeamMemberDefinition sender,
            TeamMemberDefinition target, String content, int depth,
            String delivery, Integer queuePosition, Long expiresAt,
            String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", messageId);
        data.put("teamId", runtime.getDefinition().getTeamId());
        data.put("senderTeamMemberId",
                sender == null ? null : sender.getTeamMemberId());
        data.put("senderAcpClientId",
                sender == null ? null : sender.getAcpClientId());
        data.put("senderDisplayName",
                sender == null ? null : sender.getDisplayName());
        data.put("targetTeamMemberId",
                target == null ? null : target.getTeamMemberId());
        data.put("targetAcpClientId",
                target == null ? null : target.getAcpClientId());
        data.put("targetDisplayName",
                target == null ? null : target.getDisplayName());
        data.put("content", content);
        data.put("depth", depth);
        data.put("delivery", delivery);
        if (queuePosition != null) {
            data.put("queuePosition", queuePosition);
            data.put("queueCapacity", INBOX_CAPACITY);
        }
        if (expiresAt != null) {
            data.put("expiresAt", expiresAt);
        }
        if (reason != null) {
            data.put("reason", reason);
        }
        return data;
    }

    private Map<String, Object> remoteEventData(
            String messageId, TeamContactRef sender, String senderTeamMemberId,
            TeamMemberDefinition target, String content, int depth,
            String delivery, Integer queuePosition, Long expiresAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", messageId);
        data.put("teamId", runtime.getDefinition().getTeamId());
        data.put("senderTeamMemberId", sender == null
                ? senderTeamMemberId : sender.getTargetTeamMemberId());
        data.put("senderAcpClientId", sender == null
                ? null : sender.getTargetAcpClientId());
        data.put("senderDisplayName", sender == null
                ? null : sender.getDisplayName());
        data.put("targetTeamMemberId",
                target == null ? null : target.getTeamMemberId());
        data.put("targetAcpClientId",
                target == null ? null : target.getAcpClientId());
        data.put("targetDisplayName",
                target == null ? null : target.getDisplayName());
        data.put("content", content);
        data.put("depth", depth);
        data.put("delivery", delivery);
        if (queuePosition != null) {
            data.put("queuePosition", queuePosition);
            data.put("queueCapacity", INBOX_CAPACITY);
        }
        if (expiresAt != null) data.put("expiresAt", expiresAt);
        return data;
    }

    private static TeamMemberDefinition findMember(TeamDefinition team,
                                                   String teamMemberId) {
        if (teamMemberId == null) {
            return null;
        }
        for (TeamMemberDefinition member : team.getMembers()) {
            if (teamMemberId.equals(member.getTeamMemberId())) {
                return member;
            }
        }
        return null;
    }

    private static TeamContactRef findContact(TeamDefinition team, String teamMemberId) {
        if (teamMemberId == null) return null;
        for (TeamContactRef contact : team.getRoster()) {
            if (teamMemberId.equals(contact.getTargetTeamMemberId())) return contact;
        }
        return null;
    }

    private static String contentDigest(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static final class QueuedMessage {
        private final String messageId;
        private final TalkToMessage message;
        private final TeamMemberDefinition sender;
        private final TeamContactRef remoteSender;
        private final TeamMemberDefinition target;
        private final long expiresAt;

        private QueuedMessage(String messageId, TalkToMessage message,
                              TeamMemberDefinition sender,
                              TeamContactRef remoteSender,
                              TeamMemberDefinition target,
                              long expiresAt) {
            this.messageId = messageId;
            this.message = message;
            this.sender = sender;
            this.remoteSender = remoteSender;
            this.target = target;
            this.expiresAt = expiresAt;
        }
    }

    private static final class TeamTalkToMessage extends TalkToMessage {
        private final String messageId;

        private TeamTalkToMessage(String messageId, String sender,
                                  String content, int depth,
                                  AuthPrincipalContext authPrincipalContext) {
            this(messageId, sender, content, depth, authPrincipalContext,
                    new TalkToTrace(null, messageId, null, depth,
                            System.currentTimeMillis()));
        }

        private TeamTalkToMessage(String messageId, String sender,
                                  String content, int depth,
                                  AuthPrincipalContext authPrincipalContext,
                                  TalkToTrace trace) {
            super(sender, content, depth, java.util.Collections.emptyList(),
                    authPrincipalContext, trace);
            this.messageId = messageId;
        }

        @Override
        public String buildPrompt() {
            StringBuilder sb = new StringBuilder();
            sb.append("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("📨 [Fast Team 路由消息] 发送者 memberId: ")
                    .append(getSender()).append("\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");
            sb.append("以下消息由当前 Fast Team 的严格队内路由投递，发送者身份已经过验证：\n\n");
            sb.append(getContent()).append("\n\n");
            appendReplyRoute(sb, getSender());
            return sb.toString();
        }
    }
}
