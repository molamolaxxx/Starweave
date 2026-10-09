package com.mola.cmd.proxy.app.acp.channel;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Safely persists channel runtime discoveries without exposing or replacing secrets. */
public final class ChannelConfigFileStore {
    private static final Object LOCK = new Object();
    private static final int MAX_KNOWN_CHAT_TARGETS = 200;

    private ChannelConfigFileStore() { }

    /** Adds stable archive identities to legacy channel configs without touching secrets. */
    public static boolean ensureArchiveIds() throws IOException {
        return ensureArchiveIds(CmdProxyHome.resolve("acpConfig.json"));
    }

    static boolean ensureArchiveIds(Path path) throws IOException {
        synchronized (LOCK) {
            if (!Files.exists(path)) return false;
            JSONObject root = JSON.parseObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
            JSONArray channels = root.getJSONArray("channels");
            boolean changed = false;
            if (channels != null) {
                for (int i = 0; i < channels.size(); i++) {
                    JSONObject channel = channels.getJSONObject(i);
                    if (channel != null && isBlank(channel.getString("archiveId"))) {
                        channel.put("archiveId", java.util.UUID.randomUUID().toString());
                        changed = true;
                    }
                }
            }
            if (changed) writeAtomically(path, JSON.toJSONString(root,
                    SerializerFeature.PrettyFormat, SerializerFeature.SortField));
            return changed;
        }
    }

    /** Persists an inbound gate change without rewriting secrets from a masked UI model. */
    public static void setInboundEnabled(String channelId, boolean enabled) throws IOException {
        setInboundEnabled(CmdProxyHome.resolve("acpConfig.json"), channelId, enabled);
    }

    static void setInboundEnabled(Path configPath, String channelId, boolean enabled)
            throws IOException {
        setChannelBoolean(configPath, channelId, "inboundEnabled", enabled);
    }

    /** Persists the direct-message gate without restarting the channel connection. */
    public static void setPrivateChatEnabled(String channelId, boolean enabled) throws IOException {
        setPrivateChatEnabled(CmdProxyHome.resolve("acpConfig.json"), channelId, enabled);
    }

    static void setPrivateChatEnabled(Path configPath, String channelId, boolean enabled)
            throws IOException {
        setChannelBoolean(configPath, channelId, "privateChatEnabled", enabled);
    }

    /** Records a discovered conversation without changing user-configured outbound targets. */
    public static boolean recordKnownChatTarget(String channelId, String targetId,
                                                String displayName, String chatType)
            throws IOException {
        return recordKnownChatTarget(CmdProxyHome.resolve("acpConfig.json"), channelId,
                targetId, displayName, chatType);
    }

    public static boolean recordKnownChatTarget(String channelId, String targetId,
                                                String displayName, String chatType,
                                                String messagePreview, long seenAt)
            throws IOException {
        return recordKnownChatTarget(CmdProxyHome.resolve("acpConfig.json"), channelId,
                targetId, displayName, chatType, messagePreview, seenAt);
    }

    static boolean recordKnownChatTarget(Path configPath, String channelId, String targetId,
                                         String displayName, String chatType) throws IOException {
        return recordKnownChatTarget(configPath, channelId, targetId, displayName, chatType,
                null, 0L);
    }

    static boolean recordKnownChatTarget(Path configPath, String channelId, String targetId,
                                         String displayName, String chatType,
                                         String messagePreview, long seenAt) throws IOException {
        String cleanChannelId = requireText(channelId, "channelId");
        String cleanTargetId = requireText(targetId, "targetId");
        String cleanDisplayName = truncate(requireText(displayName, "displayName"), 100);
        String cleanChatType = requireText(chatType, "chatType");
        boolean previewProvided = messagePreview != null;
        String cleanPreview = truncate(messagePreview == null ? "" : messagePreview.trim(), 100);
        synchronized (LOCK) {
            JSONObject root = JSON.parseObject(new String(
                    Files.readAllBytes(configPath), StandardCharsets.UTF_8));
            JSONObject matched = findChannel(root, cleanChannelId);
            JSONArray targets = matched.getJSONArray("knownChatTargets");
            if (targets == null) {
                targets = new JSONArray();
                matched.put("knownChatTargets", targets);
            }
            for (int i = 0; i < targets.size(); i++) {
                JSONObject target = targets.getJSONObject(i);
                if (target == null || !cleanTargetId.equals(target.getString("id"))
                        || !cleanChatType.equals(target.getString("chatType"))) continue;
                boolean changed = !cleanDisplayName.equals(target.getString("displayName"));
                String priorPreview = target.getString("lastMessagePreview");
                if (previewProvided && !cleanPreview.equals(
                        priorPreview == null ? "" : priorPreview)) {
                    changed = true;
                }
                if (seenAt > 0L && seenAt != target.getLongValue("lastSeenAt")) changed = true;
                if (i == 0 && !changed) {
                    return false;
                }
                target.put("displayName", cleanDisplayName);
                if (previewProvided) {
                    if (cleanPreview.isEmpty()) target.remove("lastMessagePreview");
                    else target.put("lastMessagePreview", cleanPreview);
                }
                if (seenAt > 0L) target.put("lastSeenAt", seenAt);
                targets.remove(i);
                targets.add(0, target);
                writeAtomically(configPath, JSON.toJSONString(root,
                        SerializerFeature.PrettyFormat, SerializerFeature.SortField));
                return true;
            }
            JSONObject target = new JSONObject(true);
            target.put("id", cleanTargetId);
            target.put("displayName", cleanDisplayName);
            target.put("chatType", cleanChatType);
            if (previewProvided && !cleanPreview.isEmpty()) {
                target.put("lastMessagePreview", cleanPreview);
            }
            if (seenAt > 0L) target.put("lastSeenAt", seenAt);
            targets.add(0, target);
            while (targets.size() > MAX_KNOWN_CHAT_TARGETS) {
                targets.remove(targets.size() - 1);
            }
            writeAtomically(configPath, JSON.toJSONString(root,
                    SerializerFeature.PrettyFormat, SerializerFeature.SortField));
            return true;
        }
    }

    /** Atomically saves UI configuration while retaining runtime-owned discoveries and secrets. */
    public static void saveUiConfig(JSONObject submitted, String secretMask) throws IOException {
        saveUiConfig(CmdProxyHome.resolve("acpConfig.json"), submitted, secretMask);
    }

    static void saveUiConfig(Path configPath, JSONObject submitted, String secretMask)
            throws IOException {
        synchronized (LOCK) {
            JSONObject previous = Files.exists(configPath)
                    ? JSON.parseObject(new String(Files.readAllBytes(configPath),
                            StandardCharsets.UTF_8))
                    : new JSONObject();
            JSONArray channels = submitted.getJSONArray("channels");
            com.mola.cmd.proxy.app.acp.acpclient.agent.EmbeddedPiConfigStore.merge(submitted, previous, secretMask);
            JSONArray oldChannels = previous.getJSONArray("channels");
            if (channels != null) {
                for (int i = 0; i < channels.size(); i++) {
                    JSONObject channel = channels.getJSONObject(i);
                    if (channel == null) continue;
                    JSONObject old = findChannelOrNull(oldChannels, channel.getString("id"));
                    if (isBlank(channel.getString("archiveId"))) {
                        String oldArchiveId = old == null ? null : old.getString("archiveId");
                        channel.put("archiveId", isBlank(oldArchiveId)
                                ? java.util.UUID.randomUUID().toString() : oldArchiveId);
                    }
                    String secret = channel.getString("secret");
                    if (isBlank(secret) || secretMask.equals(secret)) {
                        String oldSecret = old == null ? null : old.getString("secret");
                        if (isBlank(oldSecret)) channel.remove("secret");
                        else channel.put("secret", oldSecret);
                    }
                    JSONArray targets = old == null ? null
                            : old.getJSONArray("knownChatTargets");
                    if (targets == null) channel.remove("knownChatTargets");
                    else channel.put("knownChatTargets", targets);
                }
            }
            preserveExternalTaskAuthCodes(submitted.getJSONArray("externalTaskApis"),
                    previous.getJSONArray("externalTaskApis"), secretMask);
            preserveAgentGatewayAuthCodes(submitted.getJSONArray("agentGateways"),
                    previous.getJSONArray("agentGateways"), secretMask);
            writeAtomically(configPath, JSON.toJSONString(submitted,
                    SerializerFeature.PrettyFormat, SerializerFeature.SortField));
        }
    }

    private static void preserveExternalTaskAuthCodes(JSONArray submitted, JSONArray previous,
                                                      String secretMask) {
        if (submitted == null) return;
        java.util.Set<String> authCodes = new java.util.HashSet<>();
        for (int i = 0; i < submitted.size(); i++) {
            JSONObject endpoint = submitted.getJSONObject(i);
            if (endpoint == null) continue;
            JSONObject old = findById(previous, endpoint.getString("id"));
            String authCode = endpoint.getString("authCode");
            if (isBlank(authCode) || secretMask.equals(authCode)) {
                String prior = old == null ? null : old.getString("authCode");
                if (isBlank(prior)) endpoint.remove("authCode");
                else endpoint.put("authCode", prior);
            }
            String resolved = endpoint.getString("authCode");
            if (isBlank(resolved)) {
                throw new IllegalArgumentException("external task endpoint auth code is required");
            }
            if (!authCodes.add(resolved)) {
                throw new IllegalArgumentException("duplicate external task endpoint auth code");
            }
        }
    }

    private static void preserveAgentGatewayAuthCodes(JSONArray submitted, JSONArray previous,
                                                       String secretMask) {
        if (submitted == null) return;
        java.util.Set<String> authCodes = new java.util.HashSet<>();
        for (int i = 0; i < submitted.size(); i++) {
            JSONObject gateway = submitted.getJSONObject(i);
            if (gateway == null) continue;
            JSONObject old = findById(previous, gateway.getString("id"));
            String authCode = gateway.getString("authCode");
            if (isBlank(authCode) || secretMask.equals(authCode)) {
                String prior = old == null ? null : old.getString("authCode");
                if (isBlank(prior)) gateway.remove("authCode");
                else gateway.put("authCode", prior);
            }
            String resolved = gateway.getString("authCode");
            if (isBlank(resolved)) {
                throw new IllegalArgumentException("Agent gateway auth code is required");
            }
            if (!authCodes.add(resolved)) {
                throw new IllegalArgumentException("duplicate Agent gateway auth code");
            }
        }
    }

    private static JSONObject findById(JSONArray items, String id) {
        if (items == null || isBlank(id)) return null;
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item != null && id.equals(item.getString("id"))) return item;
        }
        return null;
    }

    private static void setChannelBoolean(Path configPath, String channelId,
                                          String field, boolean enabled) throws IOException {
        String cleanChannelId = requireText(channelId, "channelId");
        synchronized (LOCK) {
            JSONObject root = JSON.parseObject(new String(
                    Files.readAllBytes(configPath), StandardCharsets.UTF_8));
            JSONObject matched = findChannel(root, cleanChannelId);
            matched.put(field, enabled);
            writeAtomically(configPath, JSON.toJSONString(root,
                    SerializerFeature.PrettyFormat, SerializerFeature.SortField));
        }
    }

    private static JSONObject findChannel(JSONObject root, String channelId) throws IOException {
        JSONArray channels = root.getJSONArray("channels");
        if (channels == null) throw new IOException("channels configuration is missing");
        JSONObject matched = findChannelOrNull(channels, channelId);
        if (matched != null) return matched;
        throw new IOException("channel configuration not found: " + channelId);
    }

    private static JSONObject findChannelOrNull(JSONArray channels, String channelId) {
        if (channels == null || isBlank(channelId)) return null;
        for (int i = 0; i < channels.size(); i++) {
            JSONObject channel = channels.getJSONObject(i);
            if (channel != null && channelId.equals(channel.getString("id"))) return channel;
        }
        return null;
    }

    private static void writeAtomically(Path configPath, String content) throws IOException {
        Path absolute = configPath.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent == null) throw new IOException("configuration parent directory is missing");
        Path temp = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String requireText(String value, String field) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return clean;
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
