package com.mola.cmd.proxy.app.acp.remote;

import com.alibaba.fastjson.*;
import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.agent.EmbeddedPiConfig;
import com.mola.cmd.proxy.app.acp.channel.ChannelConfigFileStore;
import com.mola.cmd.proxy.app.acp.mcpauth.McpAuthManager;
import com.mola.starweave.sdk.*;
import com.mola.starweave.agent.transport.AcpTransport;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiConsumer;

/** Owns remote agent leases; business callbacks never run on TCP readers or under config locks. */
public final class RemoteAcpManager implements AutoCloseable {
    public static final String PROVIDER = "REMOTE_STARCORE_ACP";
    private static volatile RemoteAcpManager instance;
    private final Path configPath;
    private final BiConsumer<String, String> refresh;
    private final ConcurrentMap<String, Peer> peers = new ConcurrentHashMap<>();
    private final Set<String> registeredNames = ConcurrentHashMap.newKeySet();
    private final Set<FramedConnection> connections = ConcurrentHashMap.newKeySet();
    private final ExecutorService readers = new ThreadPoolExecutor(0, 64, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> { Thread t = new Thread(r, "remote-acp-reader"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());
    private final ExecutorService callbacks = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128), r -> { Thread t = new Thread(r, "remote-acp-lifecycle"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "remote-acp-heartbeat"); t.setDaemon(true); return t; });
    private final RemoteMcpProxy mcpProxy = new RemoteMcpProxy();
    public void handleMcp(com.sun.net.httpserver.HttpExchange exchange) { mcpProxy.handle(exchange); }
    private volatile ServerSocket listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    public RemoteAcpManager(Path path, BiConsumer<String, String> refresh) throws IOException {
        this.configPath = path; this.refresh = refresh;
        ChannelConfigFileStore.updateConfig(path, root -> { JSONArray robots = root.getJSONArray("robots"); if (robots != null) for (int i = 0; i < robots.size(); i++) { JSONObject r = robots.getJSONObject(i); if (isRemote(r)) { registeredNames.add(r.getString("name")); r.put("enabled", false); } } });
        instance = this; timer.scheduleWithFixedDelay(this::tick, 2, 2, TimeUnit.SECONDS);
    }
    public static RemoteAcpManager getInstance() { RemoteAcpManager current = instance; if (current == null) throw new IllegalStateException("Remote ACP service unavailable"); return current; }
    public static boolean isRemote(JSONObject robot) { return robot != null && PROVIDER.equals(robot.getString("agentProvider")); }
    public static boolean wasRegistered(String name) { RemoteAcpManager m = instance; return m != null && m.registeredNames.contains(name); }
    public static boolean online(String name) { RemoteAcpManager m = instance; Peer p = m == null ? null : m.peers.get(name); return p != null && p.alive(); }
    private JSONObject readConfig() throws IOException { return Files.exists(configPath) ? JSON.parseObject(new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8)) : new JSONObject(); }
    private synchronized int listen(JSONObject settings) throws IOException {
        if (closed.get()) throw new IOException("Remote ACP stopped");
        if (listener != null) return listener.getLocalPort();
        int port = settings.containsKey("port") ? settings.getIntValue("port") : 13530;
        String bind = settings.getString("bindHost"); if (bind == null || bind.trim().isEmpty()) bind = "0.0.0.0";
        ServerSocket server;
        String keyStore = settings.getString("tlsKeyStore");
        if (keyStore != null && !keyStore.trim().isEmpty()) {
            try {
                char[] password = String.valueOf(settings.getOrDefault("tlsKeyStorePassword", "")).toCharArray();
                java.security.KeyStore keys = java.security.KeyStore.getInstance("PKCS12");
                try (InputStream in = Files.newInputStream(Paths.get(keyStore))) { keys.load(in, password); }
                javax.net.ssl.KeyManagerFactory km = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()); km.init(keys, password);
                javax.net.ssl.SSLContext ssl = javax.net.ssl.SSLContext.getInstance("TLS"); ssl.init(km.getKeyManagers(), null, null);
                server = ssl.getServerSocketFactory().createServerSocket();
            } catch (Exception e) { throw new IOException("Remote ACP TLS initialization failed", e); }
        } else server = new ServerSocket();
        try { server.bind(new InetSocketAddress(bind, port)); } catch (IOException e) { server.close(); throw e; }
        listener = server;
        FramedConnection.daemon("accept", () -> {
            while (!closed.get()) {
                try { Socket socket = server.accept(); socket.setSoTimeout(30000);
                    try { readers.execute(() -> accept(socket)); } catch (RejectedExecutionException e) { socket.close(); }
                } catch (IOException e) { if (closed.get()) break; }
            }
        });
        return server.getLocalPort();
    }
    public void handleDiscovery(HttpExchange exchange) throws IOException {
        JSONObject result = new JSONObject(true); int status = 200;
        try {
            if (!"GET".equals(exchange.getRequestMethod())) { status = 405; throw new IOException("GET required"); }
            JSONObject settings = readConfig().getJSONObject("remoteAcp"); if (settings == null) settings = new JSONObject();
            if (Boolean.FALSE.equals(settings.getBoolean("enabled"))) { status = 503; throw new IOException("Remote ACP disabled"); }
            String access = settings.getString("accessToken");
            if (access != null && !access.isEmpty() && !constant(access, String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")).replaceFirst("^Bearer ", ""))) { status = 401; throw new IOException("Access denied"); }
            result.put("protocolVersion", Wire.VERSION); int boundPort = listen(settings); result.put("port", settings.containsKey("publicPort") ? settings.getIntValue("publicPort") : boundPort);
            result.put("host", settings.getString("publicHost")); result.put("tls", listener instanceof javax.net.ssl.SSLServerSocket);
        } catch (Exception e) { if (status == 200) status = 503; result.put("error", "Remote ACP discovery unavailable"); }
        byte[] body = result.toJSONString().getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
    }
    public void handleStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
        JSONObject root = readConfig(); project(root);
        com.mola.cmd.proxy.app.acp.acpclient.agent.EmbeddedPiConfigStore.mask(root, "********");
        JSONArray remotes = new JSONArray(), robots = root.getJSONArray("robots");
        if (robots != null) for (int i = 0; i < robots.size(); i++) if (isRemote(robots.getJSONObject(i))) remotes.add(robots.getJSONObject(i));
        JSONObject result = new JSONObject(true); result.put("robots", remotes);
        byte[] body = result.toJSONString().getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
    }
    private void accept(Socket socket) {
        Peer peer = null; FramedConnection link = null;
        try {
            link = new FramedConnection(socket); connections.add(link);
            JsonObject hello = link.read();
            if (!"register".equals(Wire.str(hello, "type")) || !hello.has("protocolVersion") || hello.get("protocolVersion").getAsInt() != Wire.VERSION) throw new IOException("Unsupported protocol");
            String name = Wire.str(hello, "name"), identity = Wire.str(hello, "identity"), workspace = Wire.str(hello, "workspace");
            if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}") || identity.length() < 16 || identity.length() > 256 || workspace.isEmpty() || workspace.length() > 4096) throw new IOException("Invalid registration");
            URI base = URI.create(Wire.str(hello, "starweaveUrl"));
            if (base.getHost() == null || !Arrays.asList("http", "https").contains(base.getScheme())) throw new IOException("Invalid Starweave address");
            JSONObject remoteSettings = readConfig().getJSONObject("remoteAcp");
            String publicBase = remoteSettings == null ? null : remoteSettings.getString("publicMcpBaseUrl");
            if (publicBase == null || publicBase.trim().isEmpty()) publicBase = base.toString();
            Peer registered = new Peer(name, workspace, publicBase.replaceAll("/+$", ""), link);
            AtomicReference<Peer> replaced = new AtomicReference<>();
            ChannelConfigFileStore.updateConfig(configPath, root -> {
                JSONObject settings = root.getJSONObject("remoteAcp");
                if (settings != null) {
                    if (Boolean.FALSE.equals(settings.getBoolean("enabled"))) throw new IllegalArgumentException("Remote ACP disabled");
                    String access = settings.getString("accessToken"); if (access != null && !access.isEmpty() && !constant(access, Wire.str(hello, "accessToken"))) throw new IllegalArgumentException("Access denied");
                }
                JSONArray robots = root.getJSONArray("robots"); if (robots == null) { robots = new JSONArray(); root.put("robots", robots); }
                JSONObject robot = find(robots, name);
                if (robot != null && (!isRemote(robot) || !constant(robot.getString("remoteIdentityHash"), hash(identity)))) throw new IllegalArgumentException("Remote identity conflict");
                if (robot == null) {
                    robot = new JSONObject(true); robot.put("name", name); robot.put("agentProvider", PROVIDER); robot.put("remoteIdentityHash", hash(identity));
                    JSONObject pi = new JSONObject(true); pi.put("stateId", UUID.randomUUID().toString()); pi.put("baseUrl", "https://api.deepseek.com"); pi.put("contextWindow", 1000000); pi.put("maxTokens", 64000); robot.put("embeddedPi", pi);
                    robot.put("abilityAutoRefresh", false); JSONObject memory = new JSONObject(true); memory.put("readEnabled", true); memory.put("writeEnabled", true); memory.put("scope", "robot"); robot.put("memory", memory); robots.add(robot);
                }
                robot.put("enabled", true); robot.put("workDir", workspace); robot.put("remoteSdkVersion", Wire.str(hello, "sdkVersion")); robot.put("remotePlatform", Wire.str(hello, "platform"));
            }, () -> { registeredNames.add(name); replaced.set(peers.put(name, registered)); });
            peer = registered; if (replaced.get() != null) replaced.get().disconnect();
            link.send(Wire.frame("registered", null)); sendConfig(peer); scheduleRefresh(peer);
            while (peer.alive()) {
                JsonObject f = link.read();
                if (!peer.alive()) throw new IOException("Remote ACP lease expired");
                peer.lastHeartbeat = System.nanoTime();
                String type = Wire.str(f, "type"), id = Wire.str(f, "runtimeId");
                if (type.equals("ping")) link.send(Wire.frame("pong", null));
                else if (type.equals("config_ack")) {
                    if (f.has("version") && Wire.str(f, "version").equals(peer.version)) {
                        boolean ready = f.has("ready") && f.get("ready").getAsBoolean(); boolean changed = ready && !peer.ready; peer.ready = ready;
                        peer.error = ready ? "" : "待配置：请填写模型和 API Key"; if (changed) scheduleRefresh(peer);
                    }
                } else if (type.equals("uploaded")) {
                    CompletableFuture<String> future = peer.uploading.remove(Wire.str(f, "uploadId"));
                    if (future != null) { if (f.has("error")) future.completeExceptionally(new IOException(Wire.str(f, "error"))); else future.complete(Wire.str(f, "path")); }
                } else if (type.equals("opened") || type.equals("runtime_error")) {
                    CompletableFuture<Void> future = peer.opening.remove(id); if (future != null) { if (type.equals("opened")) future.complete(null); else future.completeExceptionally(new IOException(Wire.str(f, "error"))); }
                    if (type.equals("runtime_error")) { StreamAcpTransport runtime = peer.runtimes.remove(id); if (runtime != null) runtime.fail("远程星核启动失败"); }
                } else if (type.equals("acp")) {
                    JsonObject message = f.getAsJsonObject("message");
                    if (message.has("id") && !message.has("method")) peer.prompts.remove(id + ":" + message.get("id").toString());
                    StreamAcpTransport runtime = peer.runtimes.get(id); if (runtime != null) runtime.accept(message);
                } else if (type.equals("closed")) {
                    StreamAcpTransport runtime = peer.runtimes.remove(id); if (runtime != null) runtime.fail("远程星核运行实例已关闭");
                    final String prefix = id + ":"; peer.prompts.removeIf(key -> key.startsWith(prefix));
                }
            }
        } catch (Exception e) { if (link != null) { try { link.send(Wire.frame("error", null)); } catch (IOException ignored) { } } }
        finally {
            if (link != null) { connections.remove(link); link.close(); }
            if (peer != null) {
                peer.disconnect(); Peer departed = peer;
                try { ChannelConfigFileStore.updateConfig(configPath, root -> { if (instance == this && peers.remove(departed.name, departed)) { JSONObject r = find(root.getJSONArray("robots"), departed.name); if (isRemote(r)) r.put("enabled", false); } }); }
                catch (IOException ignored) { }
                try { callbacks.execute(() -> { if (!online(departed.name) && !closed.get()) try { refresh.accept(departed.name, departed.name); } catch (RuntimeException ignored) { } }); }
                catch (RejectedExecutionException ignored) { }
            }
        }
    }
    private void sendConfig(Peer peer) throws IOException {
        JSONObject r = find(readConfig().getJSONArray("robots"), peer.name); if (!isRemote(r)) return;
        JsonObject config = peerConfig(r.toJavaObject(AcpRobotParam.class), peer); JSONObject versioned = JSON.parseObject(r.toJSONString()); versioned.remove("enabled"); String version = hash(versioned.toJSONString());
        if (version.equals(peer.version)) return;
        peer.version = version; peer.ready = false;
        JsonObject frame = Wire.frame("config", null); frame.addProperty("version", version); frame.add("config", config); peer.link.send(frame);
        peer.needsRefresh = true;
    }
    private void scheduleRefresh(Peer peer) {
        if (!peer.alive() || !peer.ready || !peer.prompts.isEmpty() || System.nanoTime() < peer.retryAfter || !peer.refreshing.compareAndSet(false, true)) return;
        peer.needsRefresh = false;
        try { callbacks.execute(() -> { try { if (peers.get(peer.name) == peer && peer.alive()) refresh.accept(peer.name, peer.name); } catch (RuntimeException e) { peer.error = "远程配置已保存，智能体刷新失败"; peer.needsRefresh = true; peer.retryAfter = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); } finally { peer.refreshing.set(false); } }); }
        catch (RejectedExecutionException e) { peer.refreshing.set(false); peer.needsRefresh = true; }
    }
    private void tick() {
        mcpProxy.cleanup();
        for (Peer p : peers.values()) {
            if (!p.alive()) { p.disconnect(); continue; }
            try { sendConfig(p); if (p.needsRefresh) scheduleRefresh(p); }
            catch (Exception e) { p.error = "远程配置同步失败"; }
            p.runtimes.entrySet().removeIf(e -> !e.getValue().isAlive());
        }
    }
    public AcpTransport open(AcpRobotParam robot) throws IOException {
        Peer peer = peers.get(robot.getName()); if (peer == null || !peer.alive()) throw new IOException("远程星核已失效");
        String id = UUID.randomUUID().toString();
        StreamAcpTransport transport = new StreamAcpTransport(id, peer.link, message -> {
            String method = Wire.str(message, "method");
            if (method.equals("session/prompt") && message.has("id")) peer.prompts.add(id + ":" + message.get("id").toString());
            if (message.has("params")) {
                JsonObject params = message.getAsJsonObject("params");
                if (params.has("cwd")) params.addProperty("cwd", peer.workspace);
                if (params.has("mcpServers")) rewriteMcp(params.getAsJsonArray("mcpServers"), peer, id);
            }
            return message;
        });
        java.util.Map<String, String> staged = new ConcurrentHashMap<>();
        transport.setFileStager(paths -> {
            Map<String, String> result = new LinkedHashMap<>();
            for (String path : paths) {
                if (!staged.containsKey(path)) staged.put(path, upload(peer, id, path));
                result.put(path, staged.get(path));
            }
            return result;
        });
        CompletableFuture<Void> opened = new CompletableFuture<>(); peer.opening.put(id, opened); peer.runtimes.put(id, transport);
        try {
            JsonObject frame = Wire.frame("open", id); frame.add("config", peerConfig(robot, peer)); peer.link.send(frame); opened.get(20, TimeUnit.SECONDS); return transport;
        } catch (Exception e) { peer.runtimes.remove(id); transport.close(); if (e instanceof InterruptedException) Thread.currentThread().interrupt(); throw new IOException("远程星核启动失败", e); }
        finally { peer.opening.remove(id); }
    }
    private String upload(Peer peer, String runtimeId, String source) throws IOException {
        Path path = Paths.get(source);
        if (!Files.isRegularFile(path) || Files.size(path) > 16 * 1024 * 1024) throw new IOException("远程附件必须是普通文件且不超过 16 MiB");
        String uploadId = UUID.randomUUID().toString(); CompletableFuture<String> future = new CompletableFuture<>(); peer.uploading.put(uploadId, future);
        try {
            JsonObject frame = Wire.frame("upload", runtimeId); frame.addProperty("uploadId", uploadId); frame.addProperty("fileName", path.getFileName().toString());
            frame.addProperty("data", Base64.getEncoder().encodeToString(Files.readAllBytes(path))); peer.link.send(frame); return future.get(20, TimeUnit.SECONDS);
        } catch (Exception e) { if (e instanceof InterruptedException) Thread.currentThread().interrupt(); throw new IOException("远程附件传输失败", e); }
        finally { peer.uploading.remove(uploadId); }
    }
    private void rewriteMcp(JsonArray servers, Peer peer, String runtimeId) {
        String internal = McpAuthManager.getInstance().getBaseUrl();
        for (JsonElement item : servers) {
            JsonObject server = item.getAsJsonObject(); String url = Wire.str(server, "url");
            if (url.isEmpty()) throw new IllegalArgumentException("远程星核不执行中心机器的 stdio MCP，请配置网络 MCP");
            URI uri = URI.create(url); String host = uri.getHost();
            boolean loopback = Arrays.asList("localhost", "127.0.0.1", "::1", "[::1]").contains(host);
            if (loopback && (url.startsWith(internal + "/") || hasAuthContext(server) || "starweave-tasks".equals(Wire.str(server, "name")))) {
                server.addProperty("url", mcpProxy.expose(peer.mcpBase, url, () -> {
                    StreamAcpTransport runtime = peer.runtimes.get(runtimeId); return peer.alive() && runtime != null && runtime.isAlive();
                }));
            }
        }
    }
    private static boolean hasAuthContext(JsonObject server) {
        if (!server.has("headers")) return false;
        for (JsonElement e : server.getAsJsonArray("headers")) if (McpAuthManager.AUTH_SESSION_HEADER.equalsIgnoreCase(Wire.str(e.getAsJsonObject(), "name"))) return true;
        return false;
    }
    private JsonObject peerConfig(AcpRobotParam robot, Peer peer) {
        JsonObject config = runtimeConfig(robot), env = config.getAsJsonObject("environment");
        String prior = Wire.str(env, "NO_PROXY"), host = URI.create(peer.mcpBase).getHost();
        String bypass = prior.isEmpty() ? host : prior + "," + host;
        env.addProperty("NO_PROXY", bypass); env.addProperty("no_proxy", bypass); return config;
    }
    public static JsonObject runtimeConfig(AcpRobotParam robot) {
        EmbeddedPiConfig pi = robot.getEmbeddedPi(); JsonObject c = new JsonObject();
        c.addProperty("baseUrl", pi == null ? "https://api.deepseek.com" : pi.getBaseUrl()); c.addProperty("apiKey", pi == null ? "" : pi.getApiKey()); c.addProperty("model", robot.getModel());
        c.addProperty("contextWindow", pi == null ? 1000000 : pi.getContextWindow()); c.addProperty("maxTokens", pi == null ? 64000 : pi.getMaxTokens());
        JsonObject env = new JsonObject();
        if (robot.isProxyEnabled() && robot.getHttpProxy() != null && !robot.getHttpProxy().trim().isEmpty()) {
            String proxy = robot.getHttpProxy().contains("://") ? robot.getHttpProxy() : "http://" + robot.getHttpProxy();
            env.addProperty("HTTP_PROXY", proxy); env.addProperty("HTTPS_PROXY", proxy); env.addProperty("http_proxy", proxy); env.addProperty("https_proxy", proxy);
        }
        if (robot.getNoProxy() != null) { env.addProperty("NO_PROXY", robot.getNoProxy()); env.addProperty("no_proxy", robot.getNoProxy()); }
        c.add("environment", env); return c;
    }
    /** Called inside the same store lock used for registration and deletion. */
    public static void validateSave(JSONObject submitted, JSONObject previous) {
        JSONObject settings = submitted.getJSONObject("remoteAcp"), oldSettings = previous.getJSONObject("remoteAcp");
        if (settings != null && oldSettings != null) for (String key : Arrays.asList("accessToken", "tlsKeyStorePassword"))
            if ("********".equals(settings.getString(key))) settings.put(key, oldSettings.getString(key));
        JSONArray old = previous.getJSONArray("robots"), next = submitted.getJSONArray("robots");
        if (old != null) for (int i = 0; i < old.size(); i++) {
            JSONObject r = old.getJSONObject(i); if (!isRemote(r)) continue;
            JSONObject updated = find(next, r.getString("name"));
            if (updated == null) { if (online(r.getString("name"))) throw new IllegalArgumentException("生效的远程星核不能删除"); continue; }
            if (!isRemote(updated)) throw new IllegalArgumentException("远程星核不能切换运行引擎");
            updated.put("remoteIdentityHash", r.getString("remoteIdentityHash")); updated.put("workDir", r.getString("workDir"));
            updated.put("enabled", online(r.getString("name"))); updated.remove("remoteStatus");
            for (String field : Arrays.asList("remoteSdkVersion", "remotePlatform")) updated.put(field, r.getString(field));
        }
        if (next != null) for (int i = 0; i < next.size(); i++) {
            JSONObject r = next.getJSONObject(i);
            if (isRemote(r) && !isRemote(find(old, r.getString("name")))) throw new IllegalArgumentException("远程星核只能通过 SDK 注册");
            if (!isRemote(r)) r.remove("remoteIdentityHash");
        }
    }
    public void project(JSONObject root) {
        JSONObject settings = root.getJSONObject("remoteAcp");
        if (settings != null) for (String key : Arrays.asList("accessToken", "tlsKeyStorePassword"))
            if (settings.getString(key) != null && !settings.getString(key).isEmpty()) settings.put(key, "********");
        JSONArray robots = root.getJSONArray("robots"); if (robots == null) return;
        for (int i = 0; i < robots.size(); i++) { JSONObject r = robots.getJSONObject(i); r.remove("remoteIdentityHash"); if (!isRemote(r)) continue;
            Peer p = peers.get(r.getString("name")); boolean alive = p != null && p.alive(); r.put("enabled", alive);
            JSONObject status = new JSONObject(true); status.put("online", alive); status.put("ready", alive && p.ready); status.put("error", p == null ? "" : p.error); status.put("version", p == null ? "" : p.version); status.put("applying", alive && p.needsRefresh); r.put("remoteStatus", status);
        }
    }
    public static JSONObject find(JSONArray robots, String name) { if (robots != null) for (int i = 0; i < robots.size(); i++) { JSONObject r = robots.getJSONObject(i); if (r != null && Objects.equals(name, r.getString("name"))) return r; } return null; }
    private static boolean constant(String a, String b) { return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
    private static String hash(String value) { try { byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format("%02x", b)); return out.toString(); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (instance == this) instance = null;
        try { if (listener != null) listener.close(); } catch (IOException ignored) { }
        for (FramedConnection link : connections) link.close(); for (Peer peer : peers.values()) peer.disconnect();
        mcpProxy.close(); timer.shutdownNow(); readers.shutdownNow(); callbacks.shutdownNow();
    }
    private static final class Peer {
        final String name, workspace, mcpBase; final FramedConnection link;
        final ConcurrentMap<String, StreamAcpTransport> runtimes = new ConcurrentHashMap<>();
        final ConcurrentMap<String, CompletableFuture<Void>> opening = new ConcurrentHashMap<>();
        final ConcurrentMap<String, CompletableFuture<String>> uploading = new ConcurrentHashMap<>();
        final Set<String> prompts = ConcurrentHashMap.newKeySet(); final AtomicBoolean refreshing = new AtomicBoolean();
        volatile long retryAfter; volatile long lastHeartbeat = System.nanoTime(); volatile boolean ready, needsRefresh; volatile String version = "", error = "";
        Peer(String name, String workspace, String base, FramedConnection link) { this.name = name; this.workspace = workspace; this.mcpBase = base; this.link = link; }
        boolean alive() { return link.isAlive() && System.nanoTime() - lastHeartbeat < TimeUnit.SECONDS.toNanos(30); }
        void disconnect() { link.close(); for (StreamAcpTransport r : runtimes.values()) r.fail("远程星核连接中断，当前执行已终止"); for (CompletableFuture<Void> f : opening.values()) f.completeExceptionally(new IOException("Remote ACP disconnected")); for (CompletableFuture<String> f : uploading.values()) f.completeExceptionally(new IOException("Remote ACP disconnected")); runtimes.clear(); opening.clear(); uploading.clear(); prompts.clear(); }
    }
}
