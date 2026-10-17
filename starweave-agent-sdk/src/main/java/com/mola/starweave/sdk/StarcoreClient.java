package com.mola.starweave.sdk;

import com.google.gson.*;
import com.mola.starweave.agent.*;
import com.mola.starweave.sdk.sync.*;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Java 8 embedded StarCore connector. No business-side prompt API. */
public final class StarcoreClient implements AutoCloseable {
    public enum State { CONNECTING, CONNECTED, READY, CLOSED }
    private final Builder options;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrentMap<String, Runtime> runtimes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "starcore-heartbeat"); t.setDaemon(true); return t; });
    private final ExecutorService lifecycle = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), r -> { Thread t = new Thread(r, "starcore-runtime-lifecycle"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());
    private volatile FramedConnection link;
    private volatile State state = State.CONNECTING;
    private volatile String lastError = "";
    private volatile long lastReceived;
    private final String identity;
    private final ResourceReplica resources;
    private final ScheduledExecutorService resourceWorker = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "starcore-resource-sync"); t.setDaemon(true); return t; });
    private volatile boolean resourceSyncSupported, resourcesReady, modelReady;
    private volatile String pendingResourceRequest = "";
    private final java.util.concurrent.atomic.AtomicReference<String> requestedBarrier = new java.util.concurrent.atomic.AtomicReference<>("");
    private volatile long resourceRequestAt;

    private final FileChannel identityChannel;
    private final FileLock identityLock;
    private StarcoreClient(Builder b) throws IOException {
        options = b;
        Files.createDirectories(b.dataDirectory);
        identityChannel = FileChannel.open(b.dataDirectory.resolve("sdk.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = identityChannel.tryLock(); }
        catch (RuntimeException e) { identityChannel.close(); throw new IOException("StarCore data directory already in use", e); }
        if (acquired == null) { identityChannel.close(); throw new IOException("StarCore data directory already in use"); }
        identityLock = acquired;
        try {
            Path file = b.dataDirectory.resolve("identity");
            if (!Files.exists(file)) { Files.write(file, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW); }
            identity = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            if (identity.length() < 16) throw new IOException("Invalid StarCore identity file");
            resources = new ResourceReplica(b.dataDirectory, b.workspace);
        } catch (IOException e) { identityLock.release(); identityChannel.close(); throw e; }
        timer.scheduleAtFixedRate(this::heartbeat, 1, 10, TimeUnit.SECONDS);
        resourceWorker.scheduleWithFixedDelay(this::syncResources, 1, 3, TimeUnit.SECONDS);
        FramedConnection.daemon("connector", this::connectLoop);
    }
    public static StarcoreClient start(String starweaveUrl, String name) throws IOException { return builder().starweaveUrl(starweaveUrl).name(name).start(); }
    public static Builder builder() { return new Builder(); }
    public State getState() { return state; }
    public String getLastError() { return lastError; }
    public boolean awaitReady(long timeout, TimeUnit unit) throws InterruptedException {
        long end = System.nanoTime() + unit.toNanos(timeout);
        synchronized (this) { while (!closed.get() && state != State.READY) { long remaining = end - System.nanoTime(); if (remaining <= 0) return false; TimeUnit.NANOSECONDS.timedWait(this, remaining); } }
        return state == State.READY;
    }
    private void setState(State value) { if (closed.get() && value != State.CLOSED) return; state = value; synchronized (this) { notifyAll(); } try { options.listener.accept(value); } catch (RuntimeException ignored) { } }
    private void connectLoop() {
        long backoff = 1000;
        while (!closed.get()) {
            Socket pendingSocket = null;
            setState(State.CONNECTING);
            resourceSyncSupported = false; resourcesReady = false; modelReady = false; pendingResourceRequest = ""; requestedBarrier.set("");
            try {
                JsonObject info = discover(); if (closed.get()) return;
                String host = Wire.str(info, "host"); if (host.isEmpty()) host = options.url.getHost();
                Socket socket = info.has("tls") && info.get("tls").getAsBoolean() ? javax.net.ssl.SSLSocketFactory.getDefault().createSocket() : new Socket();
                pendingSocket = socket;
                socket.connect(new InetSocketAddress(host, info.get("port").getAsInt()), 10000); socket.setSoTimeout(35000);
                if (socket instanceof javax.net.ssl.SSLSocket) {
                    javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) socket;
                    javax.net.ssl.SSLParameters parameters = ssl.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS"); ssl.setSSLParameters(parameters); ssl.startHandshake();
                }
                if (closed.get()) return;
                FramedConnection connection = new FramedConnection(socket); link = connection; lastReceived = System.nanoTime();
                JsonObject hello = Wire.frame("register", null); hello.addProperty("protocolVersion", Wire.VERSION);
                hello.addProperty("name", options.name); hello.addProperty("identity", identity); hello.addProperty("workspace", options.workspace.toString());
                hello.addProperty("resourceSyncVersion", 1); hello.addProperty("managedRoot", resources.root().toString());
                hello.addProperty("sdkVersion", "1.0.0"); hello.addProperty("platform", System.getProperty("os.name"));
                hello.addProperty("starweaveUrl", options.url.toString());
                if (!options.accessToken.isEmpty()) hello.addProperty("accessToken", options.accessToken);
                connection.send(hello);
                while (!closed.get() && connection.isAlive()) { JsonObject f = connection.read(); lastReceived = System.nanoTime(); receive(connection, f); backoff = 1000; }
            } catch (Exception e) { lastError = "StarCore connection unavailable (" + e.getClass().getSimpleName() + ")"; }
            finally { FramedConnection connection = link; link = null; if (connection != null) connection.close(); if (pendingSocket != null) try { pendingSocket.close(); } catch (IOException ignored) { } clearRuntimes(); if (!closed.get()) setState(State.CONNECTING); }
            synchronized (this) {
                if (!closed.get()) try { wait(backoff + ThreadLocalRandom.current().nextLong(250)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            backoff = Math.min(30000, backoff * 2);
        }
    }
    private JsonObject discover() throws IOException {
        URL endpoint = new URL(options.url.toString().replaceAll("/+$", "") + "/api/remote-acp/discover");
        HttpURLConnection c = (HttpURLConnection) endpoint.openConnection(); c.setConnectTimeout(10000); c.setReadTimeout(10000);
        if (!options.accessToken.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + options.accessToken);
        try {
            if (c.getResponseCode() != 200) throw new IOException("StarCore discovery rejected");
            try (Reader in = new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8)) { return JsonParser.parseReader(in).getAsJsonObject(); }
        } finally { c.disconnect(); }
    }
    private void receive(FramedConnection connection, JsonObject f) throws IOException {
        String type = Wire.str(f, "type"), id = Wire.str(f, "runtimeId");
        if (type.equals("error")) throw new IOException("StarCore registration rejected");
        if (type.equals("registered")) {
            lastError = ""; setState(State.CONNECTED);
            resourceSyncSupported = f.has("resourceSyncVersion") && f.get("resourceSyncVersion").getAsInt() == 1;
            if (resourceSyncSupported) { resourceWorker.execute(() -> {
                try { if (connection != link) return; resources.bind(Wire.str(f, "resourceSetId")); syncResources(); }
                catch (IOException e) { lastError = "Resource set does not match local data directory"; connection.close(); }
            }); }
            return;
        }
        if (type.equals("resources_poll")) { requestedBarrier.set(Wire.str(f, "barrier")); resourceWorker.execute(this::syncResources); return; }
        if (type.equals("resources_response")) {
            resourceWorker.execute(() -> {
                if (link != connection || !Wire.str(f, "requestId").equals(pendingResourceRequest)) return;
                JsonObject ack = Wire.frame("resources_ack", null); ack.addProperty("barrier", Wire.str(f, "barrier"));
                try {
                    if (!resources.apply(f)) return;
                    resourcesReady = true; lastError = ""; ack.addProperty("ready", true); ack.addProperty("conflicts", resources.conflictCount()); ack.addProperty("version", Wire.str(f, "version"));
                    ack.addProperty("managedRoot", resources.root().toString());
                    if (modelReady) setState(State.READY);
                } catch (IOException | RuntimeException e) { resourcesReady = false; lastError = "Resource synchronization failed: " + e.getClass().getSimpleName(); ack.addProperty("ready", false); ack.addProperty("error", lastError); }
                finally { pendingResourceRequest = ""; }
                try { connection.send(ack); } catch (IOException ignored) { }
                if (!requestedBarrier.get().isEmpty()) syncResources();
            }); return;
        }
        if (type.equals("config")) {
            JsonObject ack = Wire.frame("config_ack", null); ack.add("version", f.get("version"));
            try { config(f.getAsJsonObject("config")); ack.addProperty("ready", true); modelReady = true; setState(!resourceSyncSupported || resourcesReady ? State.READY : State.CONNECTED); }
            catch (RuntimeException e) { modelReady = false; ack.addProperty("ready", false); ack.addProperty("error", "请配置有效的模型、API Key 和上下文预算"); setState(State.CONNECTED); }
            connection.send(ack); return;
        }
        if (type.equals("upload")) {
            lifecycle.execute(() -> {
                JsonObject reply = Wire.frame("uploaded", id); reply.add("uploadId", f.get("uploadId"));
                try {
                    if (!runtimes.containsKey(id)) throw new IOException("Runtime closed");
                    String encoded = Wire.str(f, "data"); if (encoded.length() > 24 * 1024 * 1024) throw new IOException("Attachment exceeds limit");
                    byte[] bytes = Base64.getDecoder().decode(encoded); if (bytes.length > 16 * 1024 * 1024) throw new IOException("Attachment exceeds limit");
                    String name = Wire.str(f, "fileName").replaceAll("[^a-zA-Z0-9._-]", "_"); if (name.length() > 100) name = name.substring(name.length() - 100);
                    Path uploads = options.dataDirectory.resolve("uploads"); Files.createDirectories(uploads);
                    Path target = uploads.resolve(UUID.randomUUID().toString() + "-" + name);
                    Files.write(target, bytes, StandardOpenOption.CREATE_NEW); reply.addProperty("path", target.toString());
                } catch (Exception e) { reply.addProperty("error", "无法暂存附件，请检查文件大小和数据目录"); }
                try { connection.send(reply); } catch (IOException ignored) { }
            }); return;
        }
        if (type.equals("open")) {
            try { lifecycle.execute(() -> open(connection, f)); }
            catch (RejectedExecutionException e) { JsonObject reply = Wire.frame("runtime_error", id); reply.addProperty("error", "Runtime lifecycle queue full"); connection.send(reply); }
            return;
        }
        if (type.equals("acp")) {
            Runtime r = runtimes.get(id); if (r == null) return;
            // Embedded input dispatch is nonblocking; model/MCP work runs on its own worker.
            byte[] bytes = (f.get("message").toString() + "\n").getBytes(StandardCharsets.UTF_8);
            try { r.process.getOutputStream().write(bytes); r.process.getOutputStream().flush(); }
            catch (IOException | RuntimeException e) {
                runtimes.remove(id, r); r.process.destroy();
                JsonObject failure = Wire.frame("runtime_error", id); failure.addProperty("error", "远程执行实例已关闭或不再接收消息"); connection.send(failure);
            }
            return;
        }
        if (type.equals("close")) {
            // Detach before scheduling cleanup: a queued ACP message for this id must never kill the shared link.
            Runtime r = runtimes.remove(id);
            if (r != null) try { lifecycle.execute(r.process::destroy); } catch (RejectedExecutionException e) { r.process.destroy(); }
        }
    }
    private AgentConfig config(JsonObject c) {
        Map<String, String> env = new HashMap<>(System.getenv());
        if (c.has("environment")) for (Map.Entry<String, JsonElement> e : c.getAsJsonObject("environment").entrySet()) env.put(e.getKey(), e.getValue().getAsString());
        AgentToolPolicy policy = AgentToolPolicy.workspace(options.workspace, options.dataDirectory.resolve("skills"), options.allowWrite, options.allowShell)
                .withManagedResources(resourceSyncSupported ? resources.root() : null);
        if (options.allowJvm) policy = policy.withJvmExecution(options.jvmBindings);
        return new AgentConfig(Wire.str(c, "baseUrl"), Wire.str(c, "apiKey"), Wire.str(c, "model"), options.dataDirectory,
                c.get("contextWindow").getAsInt(), c.get("maxTokens").getAsInt(), env, policy);
    }
    private void open(FramedConnection connection, JsonObject f) {
        String id = Wire.str(f, "runtimeId");
        try {
            if (connection != link || !connection.isAlive()) return;
            if (runtimes.size() >= options.maxRuntimes) throw new IOException("Remote runtime limit reached");
            EmbeddedAgentRuntime process = new EmbeddedAgentRuntime(config(f.getAsJsonObject("config")));
            Runtime runtime = new Runtime(process); if (runtimes.putIfAbsent(id, runtime) != null) { process.destroy(); throw new IOException("Duplicate runtime"); }
            if (connection != link || !connection.isAlive()) { runtimes.remove(id, runtime); process.destroy(); return; }
            connection.send(Wire.frame("opened", id));
            FramedConnection.daemon("runtime-output", () -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) { JsonObject event = Wire.frame("acp", id); event.add("message", JsonParser.parseString(line)); connection.send(event); }
                } catch (Exception e) { process.destroy(); }
                finally { runtimes.remove(id, runtime); try { connection.send(Wire.frame("closed", id)); } catch (IOException ignored) { } }
            });
        } catch (Exception e) {
            JsonObject reply = Wire.frame("runtime_error", id); reply.addProperty("error", "无法启动远程星核，请检查配置或实例上限");
            try { connection.send(reply); } catch (IOException ignored) { }
        }
    }
    private void syncResources() {
        FramedConnection connection = link;
        if (!resourceSyncSupported || connection == null || !connection.isAlive() || closed.get()) return;
        if (!pendingResourceRequest.isEmpty()) {
            if (System.nanoTime() - resourceRequestAt < TimeUnit.SECONDS.toNanos(20)) return;
            resources.resetRequest(); pendingResourceRequest = "";
        }
        try {
            JsonObject request = resources.request(); request.addProperty("barrier", requestedBarrier.getAndSet(""));
            pendingResourceRequest = Wire.str(request, "requestId"); resourceRequestAt = System.nanoTime(); connection.send(request);
        } catch (IOException | RuntimeException e) {
            pendingResourceRequest = ""; resourcesReady = false; lastError = "Resource synchronization failed: " + e.getClass().getSimpleName();
            JsonObject ack = Wire.frame("resources_ack", null); ack.addProperty("ready", false); ack.addProperty("error", lastError); ack.addProperty("barrier", requestedBarrier.getAndSet(""));
            try { connection.send(ack); } catch (IOException ignored) { }
        }
    }
    private void heartbeat() {
        FramedConnection connection = link; if (connection == null) return;
        if (System.nanoTime() - lastReceived > TimeUnit.SECONDS.toNanos(35)) { connection.close(); return; }
        try { connection.send(Wire.frame("ping", null)); } catch (IOException ignored) { }
    }
    private void clearRuntimes() { for (Runtime r : runtimes.values()) r.process.destroy(); runtimes.clear(); }
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        FramedConnection c = link; if (c != null) c.close(); clearRuntimes(); timer.shutdownNow(); lifecycle.shutdownNow(); resourceWorker.shutdownNow();
        try { identityLock.release(); identityChannel.close(); } catch (IOException ignored) { }
        setState(State.CLOSED);
    }
    private static final class Runtime { final EmbeddedAgentRuntime process; Runtime(EmbeddedAgentRuntime p) { process = p; } }
    public static final class Builder {
        private URI url; private String name, accessToken = "";
        private Path workspace = Paths.get(System.getProperty("user.dir")), dataDirectory;
        private int maxRuntimes = 8;
        private boolean allowWrite, allowShell, allowJvm;
        private Map<String, Object> jvmBindings = new HashMap<>();
        private Consumer<State> listener = ignored -> { };
        public Builder starweaveUrl(String value) { url = URI.create(value); return this; }
        public Builder name(String value) { name = value; return this; }
        public Builder workspace(Path value) { workspace = value; return this; }
        public Builder dataDirectory(Path value) { dataDirectory = value; return this; }
        public Builder allowFileWrites(boolean value) { allowWrite = value; return this; }
        public Builder allowShell(boolean value) { allowShell = value; return this; }
        public Builder allowJvmExecution(boolean value) { allowJvm = value; return this; }
        public Builder jvmBindings(Map<String, Object> value) { jvmBindings = new HashMap<>(value); return this; }
        public Builder maxRuntimes(int value) { maxRuntimes = value; return this; }
        public Builder accessToken(String value) { accessToken = value; return this; }
        public Builder onStateChange(Consumer<State> value) { listener = value; return this; }
        public StarcoreClient start() throws IOException {
            if (url == null || url.getHost() == null || !Arrays.asList("http", "https").contains(url.getScheme()) || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null) throw new IllegalArgumentException("Invalid Starweave URL");
            if (name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}")) throw new IllegalArgumentException("name must contain 1-80 letters, digits, dots, underscores or hyphens");
            workspace = workspace.toAbsolutePath().normalize(); if (!Files.isDirectory(workspace)) throw new IllegalArgumentException("Workspace does not exist");
            if (dataDirectory == null) dataDirectory = Paths.get(System.getProperty("user.home"), ".starweave", "agents", name);
            dataDirectory = dataDirectory.toAbsolutePath().normalize(); if (maxRuntimes < 1 || maxRuntimes > 128) throw new IllegalArgumentException("Invalid maxRuntimes");
            return new StarcoreClient(this);
        }
    }
}
