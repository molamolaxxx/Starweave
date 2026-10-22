package com.mola.starweave.sdk;

import com.google.gson.*;
import com.mola.starweave.agent.*;
import com.mola.starweave.sdk.sync.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger LOG = LoggerFactory.getLogger(StarcoreClient.class);
    public enum State { CONNECTING, CONNECTED, READY, CLOSED }
    private final Builder options;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrentMap<String, Runtime> runtimes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "starcore-heartbeat"); t.setDaemon(true); return t; });
    private final ExecutorService lifecycle = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), r -> { Thread t = new Thread(r, "starcore-runtime-lifecycle"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());
    private final ExecutorService observationWorkers = new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> { Thread t = new Thread(r, "starcore-observation"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());
    private final ConcurrentMap<String, FutureTask<Void>> observations = new ConcurrentHashMap<>();
    private volatile FramedConnection link;
    private volatile State state = State.CONNECTING;
    private volatile String lastError = "";
    private volatile long lastReceived;
    private final String identity;
    private final ResourceReplica resources;
    private final SessionReplica sessions;
    private final ScheduledExecutorService sessionWorker = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "starcore-session-sync"); t.setDaemon(true); return t; });
    private final ConcurrentMap<String, CompletableFuture<JsonObject>> sessionRequests = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Runtime> sessionOwners = new ConcurrentHashMap<>();
    private final Set<Runtime> liveRuntimes = ConcurrentHashMap.newKeySet();
    private final Semaphore sessionPrepareSlots = new Semaphore(16);
    private final AtomicBoolean sessionUploadQueued = new AtomicBoolean();
    private final Set<String> dirtySessions = ConcurrentHashMap.newKeySet();
    private volatile boolean sessionSyncSupported, sessionsBound;
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
            sessions = new SessionReplica(b.dataDirectory);
        } catch (IOException e) { identityLock.release(); identityChannel.close(); throw e; }
        timer.scheduleAtFixedRate(this::heartbeat, 1, 10, TimeUnit.SECONDS);
        resourceWorker.scheduleWithFixedDelay(this::syncResources, 1, 3, TimeUnit.SECONDS);
        sessionWorker.scheduleWithFixedDelay(this::syncSessions, 1, 3, TimeUnit.SECONDS);
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
            sessionSyncSupported = false; sessionsBound = false;
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
                hello.addProperty("observationVersion", 1);
                hello.addProperty("observationAllowed", options.allowShell);
                hello.addProperty("resourceSyncVersion", 1); hello.addProperty("managedRoot", resources.root().toString());
                hello.addProperty("sessionSyncVersion", 1);
                hello.addProperty("sdkVersion", "1.0.0"); hello.addProperty("platform", System.getProperty("os.name"));
                hello.addProperty("starweaveUrl", options.url.toString());
                if (!options.accessToken.isEmpty()) hello.addProperty("accessToken", options.accessToken);
                connection.send(hello);
                while (!closed.get() && connection.isAlive()) { JsonObject f = connection.read(); lastReceived = System.nanoTime(); receive(connection, f); backoff = 1000; }
            } catch (Exception e) { lastError = "StarCore connection unavailable (" + e.getClass().getSimpleName() + ")"; }
            finally { FramedConnection connection = link; link = null; if (connection != null) connection.close(); failSessionRequests(); if (pendingSocket != null) try { pendingSocket.close(); } catch (IOException ignored) { } clearObservations(); clearRuntimes(); if (!closed.get()) setState(State.CONNECTING); }
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
            sessionSyncSupported = f.has("sessionSyncVersion") && f.get("sessionSyncVersion").getAsInt() == 1;
            if (sessionSyncSupported) sessionWorker.execute(() -> {
                try { if (connection != link) return; sessions.bind(Wire.str(f, "resourceSetId")); sessionsBound = true; syncSessions(); }
                catch (IOException | RuntimeException e) { sessionFailure(connection, e); connection.close(); }
            });
            resourceSyncSupported = f.has("resourceSyncVersion") && f.get("resourceSyncVersion").getAsInt() == 1;
            if (resourceSyncSupported) { resourceWorker.execute(() -> {
                try { if (connection != link) return; resources.bind(Wire.str(f, "resourceSetId")); syncResources(); }
                catch (IOException e) { logResourceSyncFailure("bind-resource-set", e); lastError = "Resource set does not match local data directory"; connection.close(); }
            }); }
            return;
        }
        if (type.equals("sessions_response")) {
            CompletableFuture<JsonObject> future = sessionRequests.remove(Wire.str(f, "requestId"));
            if (future != null) future.complete(f);
            return;
        }
        if (type.equals("observation_execute")) { executeObservation(connection, f); return; }
        if (type.equals("observation_cancel")) {
            FutureTask<Void> task = observations.remove(Wire.str(f, "requestId"));
            if (task != null) task.cancel(true);
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
                } catch (IOException | RuntimeException e) { logResourceSyncFailure("apply-response", e); resourcesReady = false; lastError = "Resource synchronization failed: " + e.getClass().getSimpleName(); ack.addProperty("ready", false); ack.addProperty("error", lastError); }
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
            JsonObject message = f.getAsJsonObject("message"); String method = Wire.str(message, "method");
            if (sessionSyncSupported && (method.equals("session/load") || method.equals("session/resume"))) {
                if (!sessionPrepareSlots.tryAcquire()) { acpFailure(connection, id, message, "会话恢复队列已满，请稍后重试"); return; }
                try { sessionWorker.execute(() -> { try { prepareSession(connection, r, message); } finally { sessionPrepareSlots.release(); } }); }
                catch (RejectedExecutionException e) { sessionPrepareSlots.release(); acpFailure(connection, id, message, "会话同步服务已关闭"); }
                return;
            }
            // Embedded input dispatch is nonblocking; model/MCP work runs on its own worker.
            try { forward(r, message); }
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
    private void forward(Runtime runtime, JsonObject message) throws IOException {
        if (message.has("id") && message.has("method")) runtime.requests.put(message.get("id").toString(), message.deepCopy());
        byte[] bytes = (message + "\n").getBytes(StandardCharsets.UTF_8);
        runtime.process.getOutputStream().write(bytes); runtime.process.getOutputStream().flush();
    }
    private void prepareSession(FramedConnection connection, Runtime runtime, JsonObject message) {
        String target = ""; boolean reserved = false;
        try {
            if (connection != link || !runtime.process.isAlive() || runtimes.get(runtime.id) != runtime) return;
            if (!sessionsBound) throw new IOException("会话同步尚未就绪，请稍后重试");
            target = SessionSnapshots.id(Wire.str(message.getAsJsonObject("params"), "sessionId"));
            Runtime owner = sessionOwners.putIfAbsent(target, runtime);
            if (owner != null && owner != runtime) throw new IOException("目标会话仍由其他运行实例使用，请先关闭该会话");
            reserved = owner == null;
            if (reserved) sessions.prepare(target, request -> exchangeSession(connection, request));
            if (connection != link || runtimes.get(runtime.id) != runtime) throw new IOException("会话恢复期间连接或运行实例已关闭");
            forward(runtime, message);
        } catch (IOException | RuntimeException e) {
            if (reserved) sessionOwners.remove(target, runtime);
            sessionFailure(connection, e); acpFailure(connection, runtime.id, message, e.getMessage());
        }
    }
    private static void acpFailure(FramedConnection connection, String id, JsonObject request, String error) {
        if (!request.has("id")) return;
        JsonObject message = new JsonObject(); message.addProperty("jsonrpc", "2.0"); message.add("id", request.get("id"));
        JsonObject failure = new JsonObject(); failure.addProperty("code", -32603); failure.addProperty("message", error); message.add("error", failure);
        JsonObject frame = Wire.frame("acp", id); frame.add("message", message);
        try { connection.send(frame); } catch (IOException ignored) { }
    }
    private JsonObject exchangeSession(FramedConnection connection, JsonObject request) throws IOException {
        if (link != connection || !connection.isAlive() || closed.get()) throw new IOException("会话同步连接已断开");
        String id = UUID.randomUUID().toString(); CompletableFuture<JsonObject> future = new CompletableFuture<>(); sessionRequests.put(id, future);
        try {
            request.addProperty("type", "sessions_request"); request.addProperty("requestId", id); connection.send(request);
            return future.get(25, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // A timed-out request may still be committing: fence it before retrying on another connection.
            connection.close(); throw new IOException("会话同步未完成，请重试", e);
        } finally { sessionRequests.remove(id); }
    }
    private void syncSessions() {
        FramedConnection connection = link;
        if (!sessionSyncSupported || !sessionsBound || connection == null || !connection.isAlive() || closed.get()) return;
        try {
            IOException failure = null; int count = 0;
            for (String id : dirtySessions) {
                if (++count > 16) break;
                dirtySessions.remove(id);
                try { sessions.uploadOne(id, request -> exchangeSession(connection, request)); }
                catch (IOException e) { dirtySessions.add(id); failure = e; }
            }
            sessions.uploadBatch(request -> exchangeSession(connection, request));
            if (failure != null) throw failure;
            JsonObject status = Wire.frame("sessions_status", null); status.addProperty("error", ""); connection.send(status);
        } catch (IOException | RuntimeException e) { sessionFailure(connection, e); }
    }
    private void sessionFailure(FramedConnection connection, Exception e) {
        if (closed.get() || connection != link) return;
        LOG.warn("Starcore session sync failed; agent=" + options.name + "; dataDirectory=" + options.dataDirectory, e);
        JsonObject status = Wire.frame("sessions_status", null); status.addProperty("error", e.getMessage());
        try { connection.send(status); } catch (IOException ignored) { }
    }
    private void queueSessionUpload() {
        if (!sessionSyncSupported || closed.get() || !sessionUploadQueued.compareAndSet(false, true)) return;
        try { sessionWorker.execute(() -> { sessionUploadQueued.set(false); syncSessions(); }); }
        catch (RejectedExecutionException e) { sessionUploadQueued.set(false); }
    }
    private void queueSessionUpload(String id) {
        if (!id.isEmpty() && sessionSyncSupported && !closed.get()) dirtySessions.add(id);
        queueSessionUpload();
    }
    private void failSessionRequests() {
        for (CompletableFuture<JsonObject> future : sessionRequests.values()) future.completeExceptionally(new IOException("会话同步连接已断开"));
        sessionRequests.clear();
    }
    private void onRuntimeMessage(Runtime runtime, JsonObject message) {
        if (!message.has("id") || message.has("method")) return;
        JsonObject request = runtime.requests.remove(message.get("id").toString()); if (request == null) return;
        String method = Wire.str(request, "method"); String target = request.has("params") ? Wire.str(request.getAsJsonObject("params"), "sessionId") : "";
        if (message.has("error")) {
            if ((method.equals("session/load") || method.equals("session/resume")) && !runtime.loaded.contains(target)) sessionOwners.remove(target, runtime);
            return;
        }
        if (method.equals("session/new")) target = Wire.str(message.getAsJsonObject("result"), "sessionId");
        if (method.equals("session/new") || method.equals("session/load") || method.equals("session/resume")) {
            sessionOwners.putIfAbsent(target, runtime); runtime.loaded.add(target);
        }
        if (method.equals("session/close") || method.equals("session/end") || method.equals("_session/end")) {
            runtime.loaded.remove(target); sessionOwners.remove(target, runtime);
        }
        queueSessionUpload(target);
    }
    private void executeObservation(FramedConnection connection, JsonObject request) throws IOException {
        String requestId = Wire.str(request, "requestId");
        if (requestId.isEmpty() || requestId.length() > 80) throw new IOException("Invalid observation request id");
        if (!options.allowShell) {
            sendObservation(connection, requestId, observationFailure("远端宿主未开放 Shell 权限，无法执行观测脚本"));
            return;
        }
        FutureTask<Void> task = new FutureTask<Void>(() -> {
            try {
                if (connection != link || !connection.isAlive()) return null;
                Map<String, String> environment = new HashMap<>();
                if (request.has("environment")) for (Map.Entry<String, JsonElement> e : request.getAsJsonObject("environment").entrySet())
                    environment.put(e.getKey(), e.getValue().getAsString());
                JsonObject result = new NodeObservationRunner().execute(Wire.str(request, "script"), options.workspace, environment);
                if (!Thread.currentThread().isInterrupted() && connection == link && connection.isAlive())
                    sendObservation(connection, requestId, result);
            } catch (RuntimeException error) {
                sendObservation(connection, requestId, observationFailure("观测执行失败：" + error.getMessage()));
            } finally { observations.remove(requestId); }
            return null;
        });
        if (observations.putIfAbsent(requestId, task) != null) throw new IOException("Duplicate observation request");
        try { observationWorkers.execute(task); }
        catch (RejectedExecutionException error) {
            observations.remove(requestId, task);
            sendObservation(connection, requestId, observationFailure("远端观测执行繁忙，请稍后重试"));
        }
    }
    private static JsonObject observationFailure(String error) {
        JsonObject result = new JsonObject(); result.addProperty("success", false);
        result.addProperty("error", error); result.addProperty("durationMillis", 0); return result;
    }
    private static void sendObservation(FramedConnection connection, String requestId, JsonObject result) {
        JsonObject response = Wire.frame("observation_result", null);
        response.addProperty("requestId", requestId); response.add("result", result);
        try { connection.send(response); } catch (IOException ignored) { }
    }
    private void clearObservations() {
        for (FutureTask<Void> task : observations.values()) task.cancel(true);
        observations.clear();
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
            Runtime runtime = new Runtime(id, process); if (runtimes.putIfAbsent(id, runtime) != null) { process.destroy(); throw new IOException("Duplicate runtime"); }
            liveRuntimes.add(runtime);
            if (connection != link || !connection.isAlive()) { runtimes.remove(id, runtime); process.destroy(); liveRuntimes.remove(runtime); return; }
            connection.send(Wire.frame("opened", id));
            FramedConnection.daemon("runtime-output", () -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) { JsonObject message = JsonParser.parseString(line).getAsJsonObject(); onRuntimeMessage(runtime, message); JsonObject event = Wire.frame("acp", id); event.add("message", message); connection.send(event); }
                } catch (Exception e) { process.destroy(); }
                finally {
                    process.destroy();
                    try { process.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    runtimes.remove(id, runtime); sessionOwners.entrySet().removeIf(entry -> entry.getValue() == runtime);
                    liveRuntimes.remove(runtime);
                    for (String session : runtime.loaded) if (sessionSyncSupported && !closed.get()) dirtySessions.add(session);
                    queueSessionUpload(); try { connection.send(Wire.frame("closed", id)); } catch (IOException ignored) { }
                }
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
            logResourceSyncFailure("scan-or-send-request", e);
            pendingResourceRequest = ""; resourcesReady = false; lastError = "Resource synchronization failed: " + e.getClass().getSimpleName();
            JsonObject ack = Wire.frame("resources_ack", null); ack.addProperty("ready", false); ack.addProperty("error", lastError); ack.addProperty("barrier", requestedBarrier.getAndSet(""));
            try { connection.send(ack); } catch (IOException ignored) { }
        }
    }
    private void logResourceSyncFailure(String stage, Exception e) {
        LOG.warn("Starcore resource sync failed; stage=" + stage
                + "; agent=" + options.name
                + "; workspace=" + options.workspace
                + "; dataDirectory=" + options.dataDirectory, e);
    }
    private void heartbeat() {
        FramedConnection connection = link; if (connection == null) return;
        if (System.nanoTime() - lastReceived > TimeUnit.SECONDS.toNanos(35)) { connection.close(); return; }
        try { connection.send(Wire.frame("ping", null)); } catch (IOException ignored) { }
    }
    private void clearRuntimes() { for (Runtime r : liveRuntimes) r.process.destroy(); runtimes.clear(); }
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        FramedConnection c = link; if (c != null) c.close(); failSessionRequests(); clearObservations(); observationWorkers.shutdownNow(); clearRuntimes(); timer.shutdownNow(); lifecycle.shutdownNow(); resourceWorker.shutdownNow(); sessionWorker.shutdownNow();
        // Keep the data-directory lock until old runtimes have finished their final snapshot writes.
        Runtime[] finishing = liveRuntimes.toArray(new Runtime[0]);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); boolean finished = true;
        for (Runtime runtime : finishing) try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !runtime.process.waitFor(remaining, TimeUnit.NANOSECONDS)) { finished = false; break; }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); finished = false; break; }
        if (finished) releaseIdentity();
        else FramedConnection.daemon("session-writer-shutdown", () -> {
            try { for (Runtime runtime : finishing) runtime.process.waitFor(); releaseIdentity(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        setState(State.CLOSED);
    }
    private void releaseIdentity() { try { identityLock.release(); identityChannel.close(); } catch (IOException ignored) { } }
    private static final class Runtime {
        final String id; final EmbeddedAgentRuntime process;
        final ConcurrentMap<String, JsonObject> requests = new ConcurrentHashMap<>();
        final Set<String> loaded = ConcurrentHashMap.newKeySet();
        Runtime(String id, EmbeddedAgentRuntime p) { this.id = id; process = p; }
    }
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
