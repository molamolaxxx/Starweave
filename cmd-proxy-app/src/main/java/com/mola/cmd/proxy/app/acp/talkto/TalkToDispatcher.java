package com.mola.cmd.proxy.app.acp.talkto;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;
import com.mola.cmd.proxy.app.acp.acpclient.PromptOptions;
import com.mola.cmd.proxy.app.acp.mcpauth.AuthPrincipalContext;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelDeliveryContext;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelTurnContext;
import com.mola.cmd.proxy.app.acp.channel.ChannelTalkToMessage;
import com.mola.cmd.proxy.app.acp.talkto.model.ContactRef;
import com.mola.cmd.proxy.app.acp.talkto.model.ExternalTalkToContact;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToMessage;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToBatchMessage;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToRequest;
import com.mola.cmd.proxy.client.provider.CmdReceiver;
import com.mola.cmd.proxy.client.resp.CmdResponseContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * TalkTo 消息投递器，负责：
 * 投递 talk_to 消息到目标 robot，并管理每个 robot 的 inbox 队列。
 */
public class TalkToDispatcher implements ExternalTalkToContactProvider {

    @FunctionalInterface
    public interface StarweaveSessionStarter {
        void open(String robotName) throws Exception;
    }

    private static final Logger logger = LoggerFactory.getLogger(TalkToDispatcher.class);

    /** inbox 容量上限 */
    private static final int INBOX_CAPACITY = 10;

    /** Maximum compatible messages consumed by one ACP inbox turn. */
    public static final int INBOX_BATCH_SIZE = 8;

    /** 短时间重复检测窗口（毫秒） */
    private static final long DEDUP_WINDOW_MS = 60_000;

    private final Map<String, AcpRobotParam> robotRegistry;
    private final AcpClientRegistry clientRegistry;
    private final Map<String, String> robotToGroupId;
    private volatile StarweaveSessionStarter starweaveSessionStarter;

    /** 普通 MAIN dispatcher 可注册的外部端点；Team dispatcher 不注册。 */
    private final List<ExternalTalkToGateway> externalGateways = new CopyOnWriteArrayList<>();

    /** 每个 robot 的 inbox 队列，key 为 robotName */
    private final ConcurrentHashMap<String, LinkedBlockingQueue<TalkToMessage>> inboxes =
            new ConcurrentHashMap<>();

    /** 短时间重复检测，key 为 messageKey，value 为时间戳 */
    private final ConcurrentHashMap<String, Long> recentMessages = new ConcurrentHashMap<>();
    /** Server-owned causal budget; model-provided _depth is compatibility metadata only. */
    protected final TalkToCircuitBreaker circuitBreaker = new TalkToCircuitBreaker();

    public TalkToDispatcher(Map<String, AcpRobotParam> robotRegistry,
                            AcpClientRegistry clientRegistry,
                            Map<String, String> robotToGroupId) {
        this.robotRegistry = robotRegistry;
        this.clientRegistry = clientRegistry;
        this.robotToGroupId = robotToGroupId;
    }

    /**
     * Team 等严格隔离实现的扩展构造器。子类必须覆盖 deliver/inbox 方法，
     * 这里的普通 registry 仅用于满足基类不可空字段，不参与实际路由。
     */
    protected TalkToDispatcher() {
        this(java.util.Collections.emptyMap(), AcpClientRegistry.getInstance(),
                java.util.Collections.emptyMap());
    }

    /**
     * 是否由 dispatcher 自己发布完整的发送/排队/拒绝/接收事件。
     * 普通模式继续由 AcpClient 发布兼容事件；Team dispatcher 返回 true，
     * 避免发送事件被重复或将拒绝误报为发送成功。
     */
    public boolean managesTalkToEvents() {
        return false;
    }

    /** Enables MAIN Starweave senders to lazily open a missing local target session. */
    public void setStarweaveSessionStarter(StarweaveSessionStarter starter) {
        this.starweaveSessionStarter = starter;
    }

    // ==================== 消息投递 ====================

    /**
     * 执行消息投递。
     * 根据 target 在通讯录中的配置判断走本地投递还是跨 chatter 投递。
     *
     * @param request         解析出的 talkTo 请求
     * @param senderName      发送方 robot 名称
     * @param senderChatterId 发送方 chatterId（跨 chatter 时需要）
     * @param contacts        发送方 robot 的通讯录配置
     * @return 执行结果文本，作为 follow-up prompt 回注发送方
     */
    public String deliver(TalkToRequest request, String senderName, String senderChatterId, List<ContactRef> contacts) {
        return deliver(request, senderName, senderChatterId, null, contacts);
    }

    public String deliver(TalkToRequest request, String senderName, String senderChatterId,
                          String senderGroupId, List<ContactRef> contacts) {
        return deliver(request, senderName, senderChatterId, senderGroupId, contacts, null);
    }

    public String deliver(TalkToRequest request, String senderName, String senderChatterId,
                          String senderGroupId, List<ContactRef> contacts,
                          AuthPrincipalContext authPrincipalContext) {
        String target = request.getTarget();

        // 外部端点必须先于包含冒号的跨 chatter 兼容格式判断。
        for (ExternalTalkToGateway gateway : externalGateways) {
            if (gateway.supports(target)) {
                return gateway.deliver(request, senderName, senderGroupId);
            }
        }

        // 查找通讯录中是否有该 target 的远程配置
        ContactRef remoteContact = findRemoteContact(target, contacts);
        if (remoteContact != null) {
            return deliverCrossChatter(remoteContact, request, senderName, senderChatterId,
                    authPrincipalContext, senderGroupId);
        }

        // 如果 target 包含冒号（"chatterId:robotName" 格式），说明是跨 chatter 回复场景
        // 即使通讯录中没有配置，也走跨 chatter 投递
        if (target.contains(":")) {
            int colonIdx = target.indexOf(':');
            String targetChatterId = target.substring(0, colonIdx).trim();
            String targetRobotName = target.substring(colonIdx + 1).trim();
            if (!targetChatterId.isEmpty() && !targetRobotName.isEmpty()) {
                ContactRef adhocRemote = new ContactRef(targetRobotName, "");
                adhocRemote.setChatterId(targetChatterId);
                adhocRemote.setRemote(true);
                return deliverCrossChatter(adhocRemote, request, senderName, senderChatterId,
                        authPrincipalContext, senderGroupId);
            }
        }

        // 本地投递
        return deliverLocal(request, senderName, senderChatterId, senderGroupId,
                authPrincipalContext);
    }

    public void registerExternalGateway(ExternalTalkToGateway gateway) {
        if (gateway != null && !externalGateways.contains(gateway)) {
            externalGateways.add(gateway);
        }
    }

    public void unregisterExternalGateway(ExternalTalkToGateway gateway) {
        externalGateways.remove(gateway);
    }

    public void releaseExternalTarget(String target) {
        if (target == null) return;
        for (ExternalTalkToGateway gateway : externalGateways) {
            if (gateway.supports(target)) gateway.release(target);
        }
    }

    public ChannelTurnContext restoreChannelTurn(ChannelDeliveryContext context,
                                                 String senderGroupId) {
        if (context == null) return null;
        for (ExternalTalkToGateway gateway : externalGateways) {
            ChannelTurnContext restored = gateway.restoreChannelTurn(context, senderGroupId);
            if (restored != null) return restored;
        }
        return null;
    }

    @Override
    public List<ExternalTalkToContact> contactsForGroup(String groupId) {
        List<ExternalTalkToContact> contacts = new java.util.ArrayList<>();
        for (ExternalTalkToGateway gateway : externalGateways) {
            if (gateway instanceof ExternalTalkToContactProvider) {
                List<ExternalTalkToContact> provided =
                        ((ExternalTalkToContactProvider) gateway).contactsForGroup(groupId);
                if (provided != null) contacts.addAll(provided);
            }
        }
        contacts.sort(java.util.Comparator.comparing(ExternalTalkToContact::getTarget));
        return java.util.Collections.unmodifiableList(contacts);
    }

    @Override
    public boolean hasEnabledChannelForGroup(String groupId) {
        for (ExternalTalkToGateway gateway : externalGateways) {
            if (gateway instanceof ExternalTalkToContactProvider
                    && ((ExternalTalkToContactProvider) gateway)
                    .hasEnabledChannelForGroup(groupId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本地消息投递（原有逻辑）。
     */
    private String deliverLocal(TalkToRequest request, String senderName,
                                String senderChatterId,
                                String senderGroupId,
                                AuthPrincipalContext authPrincipalContext) {
        String target = request.getTarget();
        String content = request.getContent();
        if (request.getParentTrace() != null && !circuitBreaker.canDeliver(request.getParentTrace())) {
            return circuitOpenResult(circuitBreaker.admit(request.getParentTrace(),
                    senderGroupId == null ? senderName : senderGroupId, target), senderName, target);
        }

        // 2. 防循环：短时间重复检测
        String dedupKey = (senderGroupId == null ? senderName : senderGroupId)
                + "→" + target + ":" + content.hashCode();
        Long lastSent = recentMessages.get(dedupKey);
        long now = System.currentTimeMillis();
        if (lastSent != null && (now - lastSent) < DEDUP_WINDOW_MS) {
            logger.warn("talkTo 短时间重复: key={}", dedupKey);
            return "[talkTo 结果]\n发送失败：短时间内向 " + target + " 发送了相同内容，已阻止重复发送。";
        }

        // 3. 校验目标 robot 存在
        if (!robotRegistry.containsKey(target)) {
            return "[talkTo 结果]\n发送失败：robot '" + target + "' 不存在。请检查名称是否正确。";
        }

        // 4. 查找目标 groupId
        String targetGroupId;
        AcpClient senderClient = senderGroupId == null
                ? null : clientRegistry.getClient(senderGroupId);
        if (senderClient != null && senderClient.getClientIdentity() != null) {
            targetGroupId = resolveLocalTargetGroupId(robotToGroupId,
                    senderClient.getClientIdentity(), senderChatterId, target);
            if (senderClient.getClientIdentity().isStarweave()
                    && (targetGroupId == null
                    || clientRegistry.getClient(targetGroupId) == null)
                    && starweaveSessionStarter != null) {
                try {
                    starweaveSessionStarter.open(target);
                    targetGroupId = resolveLocalTargetGroupId(robotToGroupId,
                            senderClient.getClientIdentity(), senderChatterId, target);
                } catch (Exception error) {
                    logger.warn("Starweave talkTo 自动开启目标会话失败: sender={}, target={}",
                            senderName, target, error);
                    String reason = error.getMessage();
                    if (reason == null || reason.trim().isEmpty()) {
                        reason = error.getClass().getSimpleName();
                    }
                    return "[talkTo 结果]\n发送失败：无法为 robot '" + target
                            + "' 自动建立 Starweave 会话：" + reason;
                }
            }
            if (targetGroupId == null) {
                return "[talkTo 结果]\n发送失败：robot '" + target
                        + "' 未在当前 Chatter ID（" + senderChatterId
                        + "）下启动。本地 talkTo 不允许跨 Chatter ID；如需跨用户通信，"
                        + "请配置远程通讯并明确指定目标 Chatter ID。";
            }
        } else {
            // 兼容没有发送方 client 上下文的旧入口；正常 MAIN 调用必须走上面的精确 owner 路由。
            targetGroupId = robotToGroupId.get(target);
        }
        if (targetGroupId == null) {
            return "[talkTo 结果]\n发送失败：robot '" + target + "' 未启动（无对应的 client）。";
        }

        // 5. 获取目标 client
        AcpClient targetClient = clientRegistry.getClient(targetGroupId);
        if (targetClient == null) {
            return "[talkTo 结果]\n发送失败：robot '" + target + "' 的 client 不存在。";
        }

        TalkToCircuitBreaker.Admission circuit = circuitBreaker.admit(
                request.getParentTrace(), senderGroupId == null ? senderName : senderGroupId,
                targetGroupId);
        if (!circuit.isAccepted()) {
            return circuit.isCircuitOpen()
                    ? circuitOpenResult(circuit, senderName, target)
                    : admissionRejectedResult(circuit, senderName, target);
        }

        // 6. 记录发送记录（用于去重）
        recentMessages.put(dedupKey, now);
        cleanExpiredDedup();

        // 7. 构造消息
        TalkToMessage message = new TalkToMessage(senderName, content,
                circuit.getTrace().getHopCount(), Collections.emptyList(),
                authPrincipalContext, circuit.getTrace());

        // 8. 检查目标状态并投递
        if (targetClient.getState() == AbstractAcpClient.State.SLEEP
                    || targetClient.getState() == AbstractAcpClient.State.ERROR) {
            try {
                targetClient.prepareForMessage();
            } catch (IOException | RuntimeException wakeFailure) {
                recentMessages.remove(dedupKey, now);
                circuitBreaker.rollback(circuit,
                        senderGroupId == null ? senderName : senderGroupId, targetGroupId);
                return "[talkTo 结果]\n发送失败：唤醒 " + target + " 失败："
                        + wakeFailure.getMessage();
            }
        }
        if (targetClient.getState() == AbstractAcpClient.State.READY) {
            // 直接投递：先推送来信卡片到目标前端
            pushIncomingMessageCard(targetClient, senderName, content);
            sendInboundMessage(targetClient, message);
            logger.info("talkTo 直接投递: {} → {}", senderName, target);
            return "[talkTo 结果]\n已成功将消息发送给 " + target + "。对方会处理你的请求，你可以继续当前工作。";
        } else {
            if (targetClient.getState() != AbstractAcpClient.State.BUSY) {
                recentMessages.remove(dedupKey, now);
                circuitBreaker.rollback(circuit,
                        senderGroupId == null ? senderName : senderGroupId, targetGroupId);
                return "[talkTo 结果]\n发送失败：目标智能体当前不可用，请稍后重试。";
            }
            // 放入 inbox
            // MAIN 的忙碌队列也必须绑定精确 client，不能退回 robotName 后跨 Chatter 串队列。
            LinkedBlockingQueue<TalkToMessage> inbox = inboxes.computeIfAbsent(
                    targetGroupId, k -> new LinkedBlockingQueue<>(INBOX_CAPACITY));

            if (inbox.offer(message)) {
                int queueSize = inbox.size();
                logger.info("talkTo 入队: {} → {}, 队列位置={}/{}", senderName, target, queueSize, INBOX_CAPACITY);
                return "[talkTo 结果]\n" + target + " 当前正忙，消息已放入对方的待处理队列（第 "
                        + queueSize + "/" + INBOX_CAPACITY + " 条）。对方空闲后会自动收到。";
            } else {
                logger.warn("talkTo inbox 已满: {} → {}", senderName, target);
                recentMessages.remove(dedupKey, now);
                circuitBreaker.rollback(circuit,
                        senderGroupId == null ? senderName : senderGroupId, targetGroupId);
                return "[talkTo 结果]\n发送失败：" + target + " 的消息队列已满（"
                        + INBOX_CAPACITY + "/" + INBOX_CAPACITY + "），无法接收新消息。"
                        + "你可以稍后再试，或使用 dispatch_subagent 创建独立子进程执行。";
            }
        }
    }

    static String localRouteKey(
            com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity senderIdentity,
            String senderChatterId, String targetRobotName) {
        String owner = senderIdentity.getOwnerId();
        if (owner == null || owner.trim().isEmpty()) owner = senderChatterId;
        return senderIdentity.getSurface().name() + ":" + owner + ":" + targetRobotName;
    }

    static String resolveLocalTargetGroupId(
            Map<String, String> routes,
            com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity senderIdentity,
            String senderChatterId, String targetRobotName) {
        return routes.get(localRouteKey(senderIdentity, senderChatterId, targetRobotName));
    }

    // ==================== 跨 Chatter 投递 ====================

    /**
     * 跨 chatter 消息投递（火烧即忘）。
     * 通过 CmdReceiver.callback 推送到 MolaChat 网关。
     */
    private String deliverCrossChatter(ContactRef remoteContact, TalkToRequest request,
                                       String senderName, String senderChatterId,
                                       AuthPrincipalContext authPrincipalContext, String senderGroupId) {
        String target = remoteContact.getName();
        String targetChatterId = remoteContact.getChatterId();
        String content = request.getContent();
        String budgetSender = senderGroupId == null ? senderName : senderGroupId;

        TalkToCircuitBreaker.Admission circuit = circuitBreaker.admit(
                request.getParentTrace(), budgetSender,
                targetChatterId + ":" + target);
        if (!circuit.isAccepted()) {
            if (circuit.isCircuitOpen()
                    && circuitBreaker.claimRelay(circuit.getTrace().getCascadeId())) {
                relayCrossCircuit(circuit, senderName, senderChatterId, target, targetChatterId);
            }
            return circuit.isCircuitOpen()
                    ? circuitOpenResult(circuit, senderName, target)
                    : admissionRejectedResult(circuit, senderName, target);
        }

        // 2. 防循环：短时间重复检测
        String dedupKey = senderName + "→" + target + "@" + targetChatterId + ":" + content.hashCode();
        Long lastSent = recentMessages.get(dedupKey);
        long now = System.currentTimeMillis();
        if (lastSent != null && (now - lastSent) < DEDUP_WINDOW_MS) {
            logger.warn("crossTalkTo 短时间重复: key={}", dedupKey);
            circuitBreaker.rollback(circuit, budgetSender,
                    targetChatterId + ":" + target);
            return "[talkTo 结果]\n发送失败：短时间内向 " + target + " 发送了相同内容，已阻止重复发送。";
        }

        // 3. 记录发送记录
        recentMessages.put(dedupKey, now);
        cleanExpiredDedup();

        // 4. 通过 CmdReceiver.callback 推送到 MolaChat 网关（火烧即忘）
        try {
            Map<String, String> resultMap = new HashMap<>();
            resultMap.put("targetChatterId", targetChatterId);
            resultMap.put("targetRobotName", target);
            resultMap.put("senderChatterId", senderChatterId);
            resultMap.put("senderRobotName", senderName);
            resultMap.put("content", content);
            resultMap.put("depth", String.valueOf(circuit.getTrace().getHopCount()));
            resultMap.put("cascadeId", circuit.getTrace().getCascadeId());
            resultMap.put("cascadeIds", new com.google.gson.Gson().toJson(circuit.getTrace().getCascadeIds()));
            resultMap.put("messageId", circuit.getTrace().getMessageId());
            resultMap.put("parentMessageId", circuit.getTrace().getParentMessageId() == null
                    ? "" : circuit.getTrace().getParentMessageId());
            resultMap.put("cascadeStartedAt",
                    String.valueOf(circuit.getTrace().getStartedAt()));
            if (authPrincipalContext != null) {
                resultMap.put("authPrincipalId", authPrincipalContext.getPrincipalId());
                resultMap.put("authPrincipalName", authPrincipalContext.getDisplayName());
                resultMap.put("authSourceType", authPrincipalContext.getSourceType());
                resultMap.put("authSourceId", authPrincipalContext.getSourceId());
            }

            CmdResponseContent response = new CmdResponseContent(
                    UUID.randomUUID().toString(), resultMap
            );
            CmdReceiver.INSTANCE.callback("crossTalkTo", "crossTalkTo", response);

            logger.info("crossTalkTo 已发送: {} → {}@{}", senderName, target, targetChatterId);
            return "[talkTo 结果]\n已成功将消息发送给 " + target
                    + "（跨服务器）。对方会处理你的请求，你可以继续当前工作。";
        } catch (Exception e) {
            circuitBreaker.rollback(circuit, budgetSender,
                    targetChatterId + ":" + target);
            logger.error("crossTalkTo callback 发送失败", e);
            return "[talkTo 结果]\n发送失败：网关通信异常 - " + e.getMessage();
        }
    }

    private void relayCrossCircuit(TalkToCircuitBreaker.Admission admission, String sender,
                                    String chatter, String target, String targetChatter) {
        Map<String, String> data = new HashMap<>();
        data.put("eventType", "TALK_TO_CIRCUIT_OPENED");
        data.put("targetChatterId", targetChatter);
        data.put("targetRobotName", target);
        data.put("senderChatterId", chatter);
        data.put("senderRobotName", sender);
        data.put("cascadeId", admission.getTrace().getCascadeId());
        data.put("cascadeIds", new com.google.gson.Gson().toJson(admission.getTrace().getCascadeIds()));
        data.put("messageId", admission.getTrace().getMessageId());
        data.put("parentMessageId", admission.getTrace().getParentMessageId() == null
                ? "" : admission.getTrace().getParentMessageId());
        data.put("depth", String.valueOf(admission.getTrace().getHopCount()));
        data.put("cascadeStartedAt", String.valueOf(admission.getTrace().getStartedAt()));
        data.put("reason", admission.getReason());
        try {
            CmdReceiver.INSTANCE.callback("crossTalkTo", "crossTalkTo",
                    new CmdResponseContent(UUID.randomUUID().toString(), data));
        } catch (RuntimeException failure) {
            logger.warn("Cross-Chatter circuit notification failed: cascadeId={}",
                    admission.getTrace().getCascadeId(), failure);
        }
    }

    public void openRemoteCircuit(AcpClient target, com.mola.cmd.proxy.app.acp.talkto.model.TalkToTrace trace,
                                  String reason) {
        TalkToCircuitBreaker.Admission admission = circuitBreaker.openCascade(trace, reason);
        // The callback is a UI-only control, never a mailbox message or session/prompt.
        target.onTalkToCircuitOpened(circuitOpenResult(admission, "coordinator", "local-session"));
    }

    /**
     * 从通讯录中查找远程联系人配置。
     *
     * @param targetName 目标 robot 名称
     * @param contacts   发送方的通讯录
     * @return 远程联系人配置，未找到或非远程时返回 null
     */
    private ContactRef findRemoteContact(String targetName, List<ContactRef> contacts) {
        if (contacts == null) return null;
        for (ContactRef contact : contacts) {
            if (contact.isRemote() && targetName.equals(contact.getName())) {
                return contact;
            }
        }
        return null;
    }

    /**
     * 将消息放入目标 robot 的 inbox（供 crossTalkToDeliver 命令处理器调用）。
     *
     * @param robotName 目标 robot 名称
     * @param message   待投递的消息
     * @return true 入队成功，false inbox 已满
     */
    public boolean offerToInbox(String robotName, TalkToMessage message) {
        if (message == null || !canDeliverMessage(message)) return false;
        LinkedBlockingQueue<TalkToMessage> inbox = inboxes.computeIfAbsent(
                robotName, k -> new LinkedBlockingQueue<>(INBOX_CAPACITY));
        return inbox.offer(message);
    }

    /**
     * 将已经解析到明确 MAIN client 的来信复用普通 TalkTo 队列语义。
     * routingKey 应使用稳定 groupId，避免同名 robot 的多个 client 串队列。
     */
    public InboundDeliveryResult deliverInbound(String routingKey, AcpClient targetClient,
                                                 TalkToMessage message) {
        if (routingKey == null || routingKey.trim().isEmpty()
                || targetClient == null || message == null) {
            return InboundDeliveryResult.rejected("invalid inbound delivery arguments");
        }
        if (!canDeliverMessage(message)) {
            return InboundDeliveryResult.rejected("TalkTo cascade is closed or expired");
        }
        synchronized (targetClient) {
            if (targetClient.getState() == AbstractAcpClient.State.SLEEP
                    || targetClient.getState() == AbstractAcpClient.State.ERROR) {
                try {
                    targetClient.prepareForMessage();
                } catch (IOException | RuntimeException wakeFailure) {
                    return InboundDeliveryResult.rejected(
                            "agent wake failed: " + wakeFailure.getMessage());
                }
            }
            if (targetClient.getState() == AbstractAcpClient.State.READY) {
                pushIncomingMessageCard(targetClient, message);
                sendInboundMessage(targetClient, message);
                logger.info("talkTo external direct delivery: route={}, sender={}",
                        routingKey, message.getSender());
                return InboundDeliveryResult.direct();
            }
        }
        if (targetClient.getState() != AbstractAcpClient.State.BUSY) {
            return InboundDeliveryResult.rejected("agent is not ready: " + targetClient.getState());
        }
        LinkedBlockingQueue<TalkToMessage> inbox = inboxes.computeIfAbsent(
                routingKey, key -> new LinkedBlockingQueue<>(INBOX_CAPACITY));
        if (message instanceof ChannelTalkToMessage && inbox.size() >= 5) {
            return InboundDeliveryResult.rejected("inbox full");
        }
        if (inbox.offer(message)) {
            return InboundDeliveryResult.queued(inbox.size());
        }
        logger.warn("talkTo external inbox full: route={}, sender={}",
                routingKey, message.getSender());
        return InboundDeliveryResult.rejected("inbox full");
    }

    // ==================== Inbox 管理 ====================

    /**
     * 从目标 robot 的 inbox 中取出下一条待处理消息。
     * 在 AcpClient turn 结束后调用。
     *
     * @param robotName 目标 robot 名称
     * @return 待投递的消息，inbox 为空时返回 null
     */
    public TalkToMessage pollInbox(String robotName) {
        if (robotName == null) return null;
        LinkedBlockingQueue<TalkToMessage> inbox = inboxes.get(robotName);
        if (inbox == null) return null;
        TalkToMessage message;
        while ((message = inbox.poll()) != null) {
            if (canDeliverMessage(message)) return message;
            logger.info("TalkTo queued message discarded: cascadeId={}, messageId={}",
                    message.getTrace().getCascadeId(), message.getTrace().getMessageId());
        }
        return null;
    }

    /** groupId 精确队列优先，随后兼容原有 robotName 队列。 */
    public TalkToMessage pollInbox(String routingName, String groupId) {
        TalkToMessage message = pollInbox(groupId);
        return message != null ? message : pollInbox(routingName);
    }

    /**
     * Drains a FIFO batch without crossing sender or authenticated-principal boundaries.
     * The legacy single-message poll methods remain unchanged for diagnostics and callers that
     * explicitly need one item.
     */
    public TalkToMessage pollInboxBatch(String routingName, String groupId, int maxMessages) {
        TalkToMessage message = pollInboxBatch(groupId, maxMessages);
        return message != null ? message : pollInboxBatch(routingName, maxMessages);
    }

    protected TalkToMessage pollInboxBatch(String routingKey, int maxMessages) {
        if (routingKey == null) return null;
        LinkedBlockingQueue<TalkToMessage> inbox = inboxes.get(routingKey);
        if (inbox == null) return null;
        TalkToMessage first = pollInbox(routingKey);
        if (first == null || maxMessages < 2 || !first.isBatchable()) return first;
        java.util.ArrayList<TalkToMessage> batch = new java.util.ArrayList<>();
        batch.add(first);
        while (batch.size() < Math.min(maxMessages, INBOX_BATCH_SIZE)) {
            TalkToMessage candidate = inbox.peek();
            if (!TalkToBatchMessage.canAppend(batch, candidate)) break;
            TalkToMessage drained = inbox.poll();
            if (drained == null) break;
            batch.add(drained);
        }
        return batch.size() == 1 ? first : new TalkToBatchMessage(batch);
    }

    public static void sendInboundMessage(AcpClient targetClient, TalkToMessage message) {
        PromptOptions options = targetClient.promptOptionsForInboundTalkTo(message);
        if (options.getAuthPrincipalContext() == null
                && message.getAuthPrincipalContext() != null) {
            options.setAuthPrincipalContext(message.getAuthPrincipalContext());
        }
        String prompt = message.buildPrompt();
        if (options.isRestoredChannelContinuation()) {
            prompt += "\n[信道续接]\n"
                    + "这条内部回信关联到你此前收到的原始外部信道消息。"
                    + "原始会话仍绑定为“回复”；请结合回信内容完成原任务，"
                    + "并按 ACP harness 的 <external-channel> 规则处理。\n";
        }
        if (message.getLocalAttachments().isEmpty()) {
            targetClient.send(prompt, null, options);
        } else {
            targetClient.sendLocalFiles(prompt, message.getLocalAttachments(), options);
        }
    }

    public boolean canDeliverMessage(TalkToMessage message) {
        // External user turns have their own lifecycle and never consume agent-chain budgets.
        return message instanceof ChannelTalkToMessage || circuitBreaker.canDeliver(message.getTrace());
    }

    public boolean claimCircuitNotification(String result) {
        if (result == null) return false;
        int start = result.indexOf("cascadeId=");
        int end = result.indexOf(", reason=", start);
        return start >= 0 && end > start
                && circuitBreaker.claimNotification(result.substring(start + 10, end));
    }

    public static final class InboundDeliveryResult {
        public enum Status { DIRECT, QUEUED, SAVED, REJECTED }

        private final Status status;
        private final int queuePosition;
        private final String reason;

        private InboundDeliveryResult(Status status, int queuePosition, String reason) {
            this.status = status;
            this.queuePosition = queuePosition;
            this.reason = reason;
        }

        public static InboundDeliveryResult direct() {
            return new InboundDeliveryResult(Status.DIRECT, 0, null);
        }

        public static InboundDeliveryResult queued(int queuePosition) {
            return new InboundDeliveryResult(Status.QUEUED, queuePosition, null);
        }

        public static InboundDeliveryResult saved() {
            return new InboundDeliveryResult(Status.SAVED, 0, null);
        }

        public static InboundDeliveryResult rejected(String reason) {
            return new InboundDeliveryResult(Status.REJECTED, 0, reason);
        }

        public Status getStatus() { return status; }
        public int getQueuePosition() { return queuePosition; }
        public String getReason() { return reason; }
    }

    /**
     * 清理过期的去重记录。
     */
    private void cleanExpiredDedup() {
        long now = System.currentTimeMillis();
        recentMessages.entrySet().removeIf(entry -> (now - entry.getValue()) > DEDUP_WINDOW_MS);
    }

    protected String circuitOpenResult(TalkToCircuitBreaker.Admission admission,
                                       String sender, String target) {
        logger.warn("TalkTo circuit opened: cascadeId={}, sender={}, target={}, hop={}, reason={}",
                admission.getTrace().getCascadeId(), sender, target,
                admission.getTrace().getHopCount(), admission.getReason());
        return "[TALK_TO_CIRCUIT_OPENED]\n通信链已由服务端终止，不要重试或发送确认消息。"
                + " cascadeId=" + admission.getTrace().getCascadeId()
                + ", reason=" + admission.getReason() + "。";
    }

    protected String admissionRejectedResult(TalkToCircuitBreaker.Admission admission,
                                             String sender, String target) {
        logger.warn("TalkTo message rejected without opening circuit: cascadeId={}, sender={}, "
                        + "target={}, hop={}, reason={}, current={}, limit={}",
                admission.getTrace().getCascadeId(), sender, target,
                admission.getTrace().getHopCount(), admission.getReason(),
                admission.getCurrent(), admission.getLimit());
        return "[talkTo 结果]\n发送失败（" + admission.getReason() + "）：当前消息超过通信限额，"
                + "消息未投递，但通信链保持可用。"
                + " cascadeId=" + admission.getTrace().getCascadeId()
                + ", current=" + admission.getCurrent()
                + ", limit=" + admission.getLimit() + "。";
    }

    /**
     * 向目标 client 的前端推送"来信卡片"，让用户知道即将收到的消息来自哪个 robot。
     */
    private void pushIncomingMessageCard(AcpClient targetClient, String senderName, String content) {
        AcpResponseListener listener = targetClient.getLiveOutputListener();
        if (listener == null) return;
        listener.onTalkToEvent("TALK_TO_RECEIVE", senderName, content);
    }

    /**
     * 从 inbox 投递消息时推送来信卡片。
     * 供 AcpClient.checkAndDeliverInbox() 调用。
     */
    public void pushIncomingMessageCard(AcpClient targetClient, TalkToMessage message) {
        if (message instanceof TalkToBatchMessage) {
            for (TalkToMessage item : ((TalkToBatchMessage) message).getMessages()) {
                pushIncomingMessageCard(targetClient, item.getSender(), item.getContent());
            }
            return;
        }
        pushIncomingMessageCard(targetClient, message.getSender(), message.getContent());
    }
}
