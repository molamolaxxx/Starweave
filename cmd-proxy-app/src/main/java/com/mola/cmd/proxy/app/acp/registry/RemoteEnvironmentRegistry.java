package com.mola.cmd.proxy.app.acp.registry;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.common.InstanceRegistry;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 中心只保存路由元数据。网络核验在锁外执行，连接代次防止旧回调污染新连接。 */
public final class RemoteEnvironmentRegistry {
    public static final long LEASE_MILLIS = 45_000;
    private final RegistryConfigStore store;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final java.util.function.LongSupplier clock;
    public RemoteEnvironmentRegistry(RegistryConfigStore store) throws IOException { this(store, System::currentTimeMillis); }
    RemoteEnvironmentRegistry(RegistryConfigStore store, java.util.function.LongSupplier clock) throws IOException {
        this.store = store; this.clock = clock;
        Path file = store.directory().resolve("environments.json");
        if (Files.exists(file)) {
            List<Entry> saved = JSON.parseArray(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), Entry.class);
            for (Entry entry : saved) {
                if (!entry.id.matches("remote-[a-f0-9-]{36}") || entry.port < 20000 || entry.port > 29999)
                    throw new IOException("远程环境登记数据无效");
                entry.online = false; entry.lease = ""; entry.runId = ""; entries.put(entry.id, entry);
            }
        }
    }
    public synchronized Entry register(String nodeId, String sourceInstanceId, String name) throws IOException {
        if (nodeId == null || !nodeId.matches("[a-zA-Z0-9-]{1,80}")) throw new IllegalArgumentException("无效的节点身份");
        if (sourceInstanceId == null || sourceInstanceId.isEmpty() || sourceInstanceId.length() > 240) throw new IllegalArgumentException("无效的实例身份");
        if (name == null || name.length() > 120) throw new IllegalArgumentException("无效的环境名称");
        Entry entry = null;
        for (Entry value : entries.values()) if (nodeId.equals(value.nodeId)) { entry = value; break; }
        // 幂等注册返回同一租约；客户端遗失连接时，等待租约过期或显式注销后再替换。
        if (entry != null && !entry.lease.isEmpty() && clock.getAsLong() - entry.lastHeartbeat < LEASE_MILLIS) {
            if (!sourceInstanceId.equals(entry.sourceInstanceId)) throw new IllegalArgumentException("节点身份已由另一个实例使用");
            entry.name = name; persist(); return copy(entry);
        }
        if (entry == null) {
            if (entries.size() >= 100) throw new IllegalArgumentException("远程环境数量已达上限");
            entry = new Entry(); entry.id = "remote-" + UUID.randomUUID(); entry.nodeId = nodeId;
            entry.port = allocatePort(); entries.put(entry.id, entry);
        }
        entry.sourceInstanceId = sourceInstanceId; entry.name = name;
        entry.runId = ""; entry.lease = UUID.randomUUID().toString(); entry.lastHeartbeat = clock.getAsLong(); entry.online = false;
        persist(); return copy(entry);
    }
    public synchronized Entry heartbeat(String id, String lease) {
        Entry entry = authorized(id, lease); entry.lastHeartbeat = clock.getAsLong(); return copy(entry);
    }
    public synchronized void unregister(String id, String lease) throws IOException {
        Entry entry = authorized(id, lease); entry.lease = ""; entry.online = false; entry.lastHeartbeat = 0; persist();
    }
    public synchronized void clearOnline() {
        for (Entry entry : entries.values()) entry.online = false;
    }
    public synchronized List<Entry> candidates() {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (clock.getAsLong() - entry.lastHeartbeat >= LEASE_MILLIS || entry.lease.isEmpty()) {
                entry.online = false;
            } else {
                if (clock.getAsLong() - entry.lastVerified >= LEASE_MILLIS) entry.online = false;
                result.add(copy(entry));
            }
        }
        return result;
    }
    public synchronized void verified(String id, String lease, boolean online) {
        Entry entry = entries.get(id);
        if (entry != null) verified(id, lease, entry.runId, online);
    }
    public synchronized void verified(String id, String lease, String runId, boolean online) {
        Entry entry = entries.get(id);
        if (entry != null && equal(entry.lease, lease) && equal(entry.runId, runId)) {
            entry.online = online && clock.getAsLong() - entry.lastHeartbeat < LEASE_MILLIS;
            entry.lastVerified = clock.getAsLong();
        }
    }
    public synchronized List<InstanceRegistry.InstanceInfo> list() {
        candidates();
        List<InstanceRegistry.InstanceInfo> result = new ArrayList<>();
        for (Entry entry : entries.values()) {
            InstanceRegistry.InstanceInfo info = new InstanceRegistry.InstanceInfo();
            info.instanceId = entry.id; info.home = entry.name; info.displayName = entry.name;
            info.configUiPort = entry.port; info.remote = true; info.online = entry.online;
            info.sourceInstanceId = entry.sourceInstanceId; result.add(info);
        }
        return result;
    }
    public synchronized int resolve(String id) {
        candidates(); Entry entry = entries.get(id);
        if (entry == null) return 0;
        if (!entry.online) throw new IllegalStateException("目标环境离线");
        return entry.port;
    }
    public synchronized boolean tunnelAllowed(String id, String lease, int port, String runId) {
        Entry entry;
        try { entry = authorized(id, lease); }
        catch (IllegalArgumentException e) { return false; }
        return entry.port == port && (runId == null || equal(entry.runId, runId));
    }
    public synchronized void tunnelConnected(String id, String lease, String runId) {
        Entry entry = authorized(id, lease); entry.online = false; entry.runId = runId;
    }
    public synchronized void tunnelDisconnected(String id, String lease, String runId) {
        Entry entry = entries.get(id);
        if (entry != null && equal(entry.lease, lease) && equal(entry.runId, runId)) {
            entry.online = false; entry.runId = "";
        }
    }
    private Entry authorized(String id, String lease) {
        Entry entry = entries.get(id);
        if (entry == null || entry.lease.isEmpty() || !equal(entry.lease, lease)
                || clock.getAsLong() - entry.lastHeartbeat >= LEASE_MILLIS) throw new IllegalArgumentException("注册连接已失效，请重新注册");
        return entry;
    }
    /** Return verified connection identity, never a caller-supplied instance ID. */
    public synchronized String authorizeParticipant(String id, String lease) {
        Entry entry = authorized(id, lease);
        if (!entry.online) throw new IllegalArgumentException("注册环境尚未在线");
        return entry.sourceInstanceId;
    }

    public synchronized List<Entry> onlineEntries() {
        candidates();
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries.values()) if (entry.online) result.add(copy(entry));
        return result;
    }
    private int allocatePort() throws IOException {
        Set<Integer> used = new HashSet<>(); for (Entry entry : entries.values()) used.add(entry.port);
        for (int port = 20000; port <= 29999; port++) {
            if (used.contains(port)) continue;
            try (ServerSocket socket = new ServerSocket()) { socket.bind(new InetSocketAddress("127.0.0.1", port)); return port; }
            catch (IOException ignored) { }
        }
        throw new IOException("没有可用的内部代理端口");
    }
    private void persist() throws IOException {
        List<Entry> records = new ArrayList<>();
        for (Entry entry : entries.values()) { Entry saved = copy(entry); saved.lease = ""; saved.runId = ""; saved.online = false; records.add(saved); }
        store.write("environments.json", JSON.toJSONString(records, true));
    }
    private static Entry copy(Entry entry) { return JSON.parseObject(JSON.toJSONString(entry), Entry.class); }
    public static boolean equal(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
    public static final class Entry {
        public String id, nodeId, sourceInstanceId, name, lease = "", runId = "";
        public int port;
        public long lastHeartbeat, lastVerified;
        public boolean online;
    }
}
