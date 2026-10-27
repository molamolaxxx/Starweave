package com.mola.cmd.proxy.app.acp.channel;

import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelConfig;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelEvent;
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToRequest;

import java.util.Map;
import java.util.Collections;
import java.util.List;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Resolves the current bound MAIN client for every event and enters the shared TalkTo inbox. */
public final class ChannelTalkToBridge {
    private static final Logger logger = LoggerFactory.getLogger(ChannelTalkToBridge.class);
    public interface ClientResolver {
        AcpClient getClient(String groupId);
    }

    private final Map<String, ChannelConfig> configs;
    private final ChannelBindingResolver bindingResolver;
    private final ChannelTalkToGateway gateway;
    private final WorkspaceAttachmentStore attachmentStore = new WorkspaceAttachmentStore();
    private final ReentrantReadWriteLock inboundGate = new ReentrantReadWriteLock();

    public ChannelTalkToBridge(Map<String, ChannelConfig> configs,
                               AcpClientRegistry clientRegistry,
                               TalkToDispatcher dispatcher,
                               ChannelTalkToGateway gateway) {
        this.configs = configs;
        this.bindingResolver = new DefaultChannelBindingResolver(
                clientRegistry, dispatcher, null);
        this.gateway = gateway;
    }

    public ChannelTalkToBridge(Map<String, ChannelConfig> configs,
                               ChannelBindingResolver bindingResolver,
                               ChannelTalkToGateway gateway) {
        this.configs = configs;
        this.bindingResolver = bindingResolver;
        this.gateway = gateway;
    }

    ChannelTalkToBridge(Map<String, ChannelConfig> configs, ClientResolver clientResolver,
                        TalkToDispatcher dispatcher, ChannelTalkToGateway gateway) {
        this.configs = configs;
        this.bindingResolver = binding -> {
            AcpClient client = clientResolver.getClient(binding.getGroupId());
            return client == null ? null : new ChannelBoundTarget(
                    client, dispatcher, binding.getGroupId(), binding.getGroupId());
        };
        this.gateway = gateway;
    }

    public TalkToDispatcher.InboundDeliveryResult onEvent(ChannelEvent event) {
        inboundGate.readLock().lock();
        try {
            ChannelConfig config = configs.get(event.getChannelId());
            if (config == null || config.getBinding() == null) {
                return TalkToDispatcher.InboundDeliveryResult.rejected("channel binding not found");
            }
            if (!config.isInboundEnabled()) {
                return TalkToDispatcher.InboundDeliveryResult.rejected("channel inbound disabled");
            }
            String chatType = event.getReplyRoute().getChatType();
            if (!isKnownChatType(chatType)) {
                return TalkToDispatcher.InboundDeliveryResult.rejected(
                        "unsupported channel chat type");
            }
            if (isPrivateChat(chatType) && !config.isPrivateChatEnabled()) {
                return TalkToDispatcher.InboundDeliveryResult.rejected(
                        "channel private chat disabled");
            }
            boolean mapped = config.getBinding().usesConversationMapping();
            if (mapped && config.getBinding().findConversationMapping(chatType,
                    DefaultChannelBindingResolver.conversationAddress(event)) == null) {
                String replyTarget = gateway.createRoute(event.getChannelId(), event.getReplyRoute(), null);
                replyFailureAsync(replyTarget, "当前企微会话尚未绑定智能体，请联系管理员配置会话路由。");
                return TalkToDispatcher.InboundDeliveryResult.rejected("conversation binding not configured");
            }
            ChannelBoundTarget target = bindingResolver.resolve(config.getBinding(), event);
            AcpClient client = target == null ? null : target.getClient();
            if (!isMainClient(client)) {
                String replyTarget = gateway.createRoute(event.getChannelId(),
                        event.getReplyRoute(), null);
                replyFailureAsync(replyTarget, mapped
                        ? "当前会话绑定的智能体暂不可用，请联系管理员检查配置。"
                        : "信道当前未绑定到可用的 ACP，请检查配置。");
                return TalkToDispatcher.InboundDeliveryResult.rejected("ACP binding not found");
            }

            List<String> localAttachments = Collections.emptyList();
            try {
                localAttachments = attachmentStore.save(client.getWorkspacePath(),
                        event.getChannelId(), event.getEventId(), event.getAttachments());
            } catch (IOException e) {
                String replyTarget = gateway.createRoute(event.getChannelId(),
                        event.getReplyRoute(), target.getSenderKey());
                replyFailureAsync(replyTarget, "附件下载或保存失败，请稍后重试。");
                return TalkToDispatcher.InboundDeliveryResult.rejected(
                        "attachment staging failed: " + e.getMessage());
            }
            if (!event.shouldInvokeAcp()) {
                return TalkToDispatcher.InboundDeliveryResult.saved();
            }

            String ownerKey = target.getSenderKey();
            String replyTarget = gateway.createRoute(
                    event.getChannelId(), event.getReplyRoute(), ownerKey);

            ChannelTalkToMessage message = new ChannelTalkToMessage(
                    replyTarget, config.getId(), event.getSenderDisplayName(), event.getSenderId(),
                    event.getReplyRoute().getChatType(), event.getReplyRoute().getChatId(),
                    event.getMessageType(), event.getContent(), event.getQuotedMessage(),
                    localAttachments, ownerKey);
            TalkToDispatcher.InboundDeliveryResult result = deliverInbound(
                    config, target, client, message);
            if (result.getStatus() != TalkToDispatcher.InboundDeliveryResult.Status.REJECTED
                    && client.getClientIdentity().isStarweave()) {
                try {
                    com.alibaba.fastjson.JSONObject metadata =
                            new com.alibaba.fastjson.JSONObject(true);
                    metadata.put("channelId", message.getChannelDisplayName());
                    metadata.put("senderDisplayName", message.getSenderDisplayName());
                    metadata.put("senderId", message.getSenderId());
                    metadata.put("chatType", message.getChatType());
                    metadata.put("chatId", message.getChatId());
                    metadata.put("messageType", message.getMessageType());
                    com.alibaba.fastjson.JSONArray attachments =
                            new com.alibaba.fastjson.JSONArray();
                    for (String path : message.getLocalAttachments()) {
                        com.alibaba.fastjson.JSONObject attachment =
                                new com.alibaba.fastjson.JSONObject(true);
                        attachment.put("fileName", java.nio.file.Paths.get(path)
                                .getFileName().toString());
                        attachments.add(attachment);
                    }
                    metadata.put("attachments", attachments);
                    com.mola.cmd.proxy.app.acp.starweave.StarweaveSessionApiBridge
                            .publishInbound(target.getRoutingKey(), client.getSessionId(),
                                    message.getContent(), "CHANNEL", metadata);
                } catch (RuntimeException projectionError) {
                    logger.warn("Starweave channel event projection failed: groupId={}",
                            target.getRoutingKey(), projectionError);
                }
            }
            if (result.getStatus() == TalkToDispatcher.InboundDeliveryResult.Status.REJECTED) {
                replyFailureAsync(replyTarget, "inbox full".equals(result.getReason())
                        ? "当前处理队列已满，请稍后重试。"
                        : "消息未能提交，智能体恢复失败或当前不可用，请稍后重试。");
            }
            return result;
        } finally {
            inboundGate.readLock().unlock();
        }
    }

    private TalkToDispatcher.InboundDeliveryResult deliverInbound(
            ChannelConfig config, ChannelBoundTarget target, AcpClient client,
            ChannelTalkToMessage message) {
        synchronized (client) {
            if (ChannelConfig.USER_BEHAVIOR_INTERRUPT.equals(config.getUserBehavior())
                    && client.getState() == AbstractAcpClient.State.BUSY) {
                try {
                    client.cancel();
                    logger.info("channel inbound interrupted active ACP turn: channelId={}, route={}",
                            config.getId(), target.getRoutingKey());
                } catch (IOException e) {
                    // Preserve delivery semantics: the new message can still wait in the inbox.
                    logger.warn("channel inbound failed to cancel active ACP turn; message will still be delivered: "
                                    + "channelId={}, route={}",
                            config.getId(), target.getRoutingKey(), e);
                }
            }
            return target.getDispatcher().deliverInbound(
                    target.getRoutingKey(), client, message);
        }
    }

    /**
     * Changes the gate under a write lock. Once this method returns after disabling,
     * no event can still be between the gate check and ACP delivery.
     *
     * @return the previous value
     */
    public boolean setInboundEnabled(String channelId, boolean enabled) {
        inboundGate.writeLock().lock();
        try {
            ChannelConfig config = configs.get(channelId);
            if (config == null) {
                throw new IllegalArgumentException("channel not found: " + channelId);
            }
            boolean previous = config.isInboundEnabled();
            config.setInboundEnabled(enabled);
            return previous;
        } finally {
            inboundGate.writeLock().unlock();
        }
    }

    public boolean isInboundEnabled(String channelId) {
        inboundGate.readLock().lock();
        try {
            ChannelConfig config = configs.get(channelId);
            return config != null && config.getBinding() != null && config.isInboundEnabled();
        } finally {
            inboundGate.readLock().unlock();
        }
    }

    public boolean setPrivateChatEnabled(String channelId, boolean enabled) {
        inboundGate.writeLock().lock();
        try {
            ChannelConfig config = configs.get(channelId);
            if (config == null) {
                throw new IllegalArgumentException("channel not found: " + channelId);
            }
            boolean previous = config.isPrivateChatEnabled();
            config.setPrivateChatEnabled(enabled);
            return previous;
        } finally {
            inboundGate.writeLock().unlock();
        }
    }

    /** Adapter-side preflight; {@link #onEvent(ChannelEvent)} remains authoritative. */
    public boolean isInboundAllowed(String channelId, String chatType) {
        inboundGate.readLock().lock();
        try {
            ChannelConfig config = configs.get(channelId);
            return config != null && config.getBinding() != null
                    && config.isInboundEnabled() && isKnownChatType(chatType)
                    && (!isPrivateChat(chatType) || config.isPrivateChatEnabled());
        } finally {
            inboundGate.readLock().unlock();
        }
    }

    private void replyFailureAsync(String replyTarget, String content) {
        java.util.concurrent.CompletableFuture.runAsync(() -> gateway.deliverSystem(
                new TalkToRequest(replyTarget, content, 0)));
    }

    private static boolean isMainClient(AcpClient client) {
        return client != null && client.getClientIdentity() != null
                && (client.getClientIdentity().getScope() == AcpClientIdentity.Scope.MAIN
                    || client.getClientIdentity().getScope() == AcpClientIdentity.Scope.TEAM)
                && (client.getRobotParam() == null || !client.getRobotParam().isOnlySubAgent());
    }

    private static boolean isKnownChatType(String chatType) {
        return "single".equals(chatType) || "group".equals(chatType);
    }

    private static boolean isPrivateChat(String chatType) {
        return "single".equals(chatType);
    }

}
