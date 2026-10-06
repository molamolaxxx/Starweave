package com.mola.cmd.proxy.app.acp.team.coordinator;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.registry.RegistryManager;
import com.mola.cmd.proxy.app.acp.registry.RemoteEnvironmentRegistry;
import com.mola.cmd.proxy.app.acp.starweave.StarweaveIdentity;
import com.sun.net.httpserver.HttpExchange;
import okhttp3.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Registry-hosted coordination with lease-authenticated participant HTTP transport. */
public final class RegistryTeamCoordinator implements AutoCloseable, MixedTeamCoordinator.Transport {
    public static final String CENTER_PATH = "/api/registry/team";
    public static final String PARTICIPANT_PATH = "/api/registry/team-participant";
    private static final String LEASE_HEADER = "X-Starweave-Team-Lease";
    private final RegistryManager registry;
    private final CoordinationStore store;
    private final MixedTeamCoordinator coordinator;
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "registry-team-coordination"); thread.setDaemon(true); return thread;
    });
    private final ScheduledExecutorService clientProjector = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "registry-team-client-projection"); thread.setDaemon(true); return thread;
    });
    private volatile boolean closed;
    private final Map<String, Object> participantLocks = new ConcurrentHashMap<>();
    private final Map<String, JSONObject> bindings = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor[] clientDelivery = new ThreadPoolExecutor[4];
    private final Set<String> pendingClientDeliveries = ConcurrentHashMap.newKeySet();
    private final TeamStreamProjection streamProjection;
    private final java.util.function.BiConsumer<String, JSONObject> clientSender;
    private final java.util.concurrent.atomic.AtomicInteger clientProjectionBytes = new java.util.concurrent.atomic.AtomicInteger();
    public RegistryTeamCoordinator(RegistryManager registry, Path directory) {
        this(registry, directory, (owner, event) -> TeamCoordinationBridge.project(registry.localInstanceId(), owner, event));
    }
    RegistryTeamCoordinator(RegistryManager registry, Path directory,
                            java.util.function.BiConsumer<String, JSONObject> clientSender) {
        this.registry = registry; this.store = new CoordinationStore(directory);
        this.clientSender = clientSender;
        this.coordinator = new MixedTeamCoordinator(store, this);
        this.streamProjection = new TeamStreamProjection(store);
        for (int i = 0; i < clientDelivery.length; i++) {
            final int stripe = i;
            clientDelivery[i] = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(256), r -> {
                        Thread t = new Thread(r, "registry-team-client-delivery-" + stripe);
                        t.setDaemon(true); return t;
                    });
        }
    }
    public void start() {
        TeamCoordinationBridge.installService(this);
        registry.teamRealtimeReceiver(this::receiveRealtime);
        worker.scheduleWithFixedDelay(this::tickSafely, 200, 500, TimeUnit.MILLISECONDS);
        clientProjector.scheduleWithFixedDelay(this::projectClientsSafely, 200, 500, TimeUnit.MILLISECONDS);
    }
    public JSONObject request(String owner, String operation, JSONObject payload) {
        if (closed) throw new CoordinationException("COORDINATOR_UNAVAILABLE", "团队协调器已停止");
        String home = registry.localInstanceId();
        validateOwner(owner, home, TeamCoordinationBridge.participant().describe());
        if (registry.isCenterEnabled()) return coordinator.execute(owner, home, operation, payload);
        JSONObject connection = registry.teamCenterConnection();
        if (connection == null) {
            // Local source discovery and local teams do not require a center.
            if ("sources".equals(operation)) return coordinator.sources(owner, home);
            if ("list".equals(operation)) return MixedTeamCoordinator.object("teams", new JSONArray());
            throw new CoordinationException("COORDINATOR_UNAVAILABLE", "请启用注册中心或接入一个注册中心后使用混合队伍");
        }
        JSONObject request = MixedTeamCoordinator.object("operation", operation);
        request.put("ownerChatterId", owner); request.put("payload", payload);
        request.put("environmentId", connection.getString("environmentId"));
        return post(connection.getString("centerUrl") + CENTER_PATH, connection.getString("lease"), request);
    }
    public void handleCenter(HttpExchange exchange) throws IOException {
        handle(exchange, () -> {
            JSONObject request = body(exchange);
            String home;
            try { home = registry.authorizeTeamCaller(request.getString("environmentId"), exchange.getRequestHeaders().getFirst(LEASE_HEADER)); }
            catch (IllegalArgumentException invalid) { throw new CoordinationException("UNAUTHORIZED", "注册连接身份校验失败"); }
            String operation = request.getString("operation");
            if ("event".equals(operation)) {
                coordinator.acceptEvent(home, request.getJSONObject("payload"));
                return MixedTeamCoordinator.object("accepted", true);
            }
            String owner = request.getString("ownerChatterId");
            JSONObject descriptor = describeParticipants().get(home);
            if (descriptor == null) throw new CoordinationException("TEAM_NOT_READY", "发起环境当前不可达");
            validateOwner(owner, home, descriptor);
            return coordinator.execute(owner, home, operation, request.getJSONObject("payload"));
        });
    }
    public void handleParticipant(HttpExchange exchange) throws IOException {
        handle(exchange, () -> {
            if (!registry.acceptsCenterLease(exchange.getRequestHeaders().getFirst(LEASE_HEADER)))
                throw new CoordinationException("UNAUTHORIZED", "协调中心连接身份校验失败");
            JSONObject request = body(exchange);
            String operation = request.getString("operation");
            JSONObject payload = request.getJSONObject("payload");
            if ("describe".equals(operation)) return TeamCoordinationBridge.participant().describe();
            if ("project".equals(operation)) {
                acceptProjection(request.getString("ownerChatterId"), payload);
                return MixedTeamCoordinator.object("accepted", true);
            }
            return localCommand(operation, payload, false);
        });
    }
    public boolean capture(JSONObject event) {
        String teamId = event.getString("teamId");
        if (teamId == null || binding(teamId) == null) return false;
        if (!StarweaveIdentity.ownerId(binding(teamId).getString("homeInstanceId"))
                .equals(binding(teamId).getString("ownerChatterId"))) streamProjection.capture(event);
        if (isRealtimeEvent(event)) {
            boolean accepted = registry.isCenterEnabled() ? routeRealtime(registry.localInstanceId(), event)
                    : registry.sendTeamRealtime(null, MixedTeamCoordinator.object("operation", "event", "payload", event));
            if (!accepted) org.slf4j.LoggerFactory.getLogger(getClass()).warn("团队实时事件未接收，连接离线或队列已满: teamId={}, eventId={}", teamId, event.getString("eventId"));
            // Session rotation must reach the UI before new-session text, and still update
            // the durable coordinator record even if its immediate projection was admitted.
            if (!Arrays.asList("MEMBER_SESSION_CHANGED", "MESSAGE_COMPLETE", "MESSAGE_ERROR").contains(event.getString("type"))) return accepted;
        }
        try {
            // Persist before returning admission success, without performing network I/O.
            store.enqueue("outbox", event.getString("eventId"), event, 10000);
            return true;
        } catch (RuntimeException failure) {
            // An assigned team must never fall through to the old MolaChat sink.
            throw failure;
        }
    }
    public boolean isAssigned(String teamId) { return teamId != null && binding(teamId) != null; }
    private JSONObject binding(String teamId) {
        return bindings.computeIfAbsent(teamId, id -> store.find("bindings", id));
    }
    public boolean authorizesProjection(String owner, JSONObject event) {
        JSONObject binding = binding(event.getString("teamId"));
        if (binding == null || !Objects.equals(owner, binding.getString("ownerChatterId"))
                || !registry.localInstanceId().equals(binding.getString("homeInstanceId"))) return false;
        String member = event.getString("teamMemberId");
        if (member == null || member.isEmpty()) return true;
        JSONArray roster = binding.getJSONArray("roster");
        if (roster != null) for (Object value : roster) if (member.equals(((JSONObject) value).getString("teamMemberId"))) return true;
        return false;
    }
    private void bind(JSONObject payload, boolean localCenter) {
        String teamId = payload.getString("teamId");
        JSONObject binding = MixedTeamCoordinator.object("homeInstanceId", payload.getString("coordinatorHomeInstanceId"));
        binding.put("ownerChatterId", payload.getString("ownerChatterId"));
        binding.put("roster", payload.getJSONArray("roster") == null ? payload.getJSONArray("members") : payload.getJSONArray("roster"));
        JSONObject connection = registry.teamCenterConnection();
        binding.put("centerUrl", localCenter ? "LOCAL" : connection == null ? null : connection.getString("centerUrl"));
        JSONObject old = store.find("bindings", teamId);
        if (old != null && (!Objects.equals(old.getString("ownerChatterId"), binding.getString("ownerChatterId"))
                || !Objects.equals(old.getString("centerUrl"), binding.getString("centerUrl"))))
            throw new CoordinationException("COORDINATOR_CONFLICT", "队伍已属于另一个协调中心");
        if (old != null && old.getBooleanValue("creationBlocked"))
            throw new CoordinationException("TEAM_DELETING", "队伍删除已接受，不能重新创建");
        store.save("bindings", teamId, binding);
        bindings.put(teamId, MixedTeamCoordinator.copy(binding));
    }
    @Override public Map<String, JSONObject> describeParticipants() {
        Map<String, JSONObject> result = new LinkedHashMap<>();
        for (JSONObject saved : store.list("discovery")) {
            saved.put("coordinationOnline", false);
            result.put(saved.getString("cmdProxyInstanceId"), saved);
        }
        try { result.put(registry.localInstanceId(), TeamCoordinationBridge.participant().describe()); } catch (CoordinationException ignored) { }
        if (registry.isCenterEnabled()) for (RemoteEnvironmentRegistry.Entry peer : registry.teamPeers()) {
            try {
                JSONObject descriptor = post("http://127.0.0.1:" + peer.port + PARTICIPANT_PATH, peer.lease,
                        MixedTeamCoordinator.object("operation", "describe"));
                if (peer.sourceInstanceId.equals(descriptor.getString("cmdProxyInstanceId"))) {
                    descriptor.put("coordinationOnline", true);
                    store.save("discovery", peer.sourceInstanceId, descriptor);
                    result.put(peer.sourceInstanceId, descriptor);
                }
            } catch (RuntimeException offline) { }
        }
        return result;
    }
    @Override public JSONObject command(String instance, String operation, JSONObject payload) {
        if (registry.localInstanceId().equals(instance)) {
            return localCommand(operation, payload, true);
        }
        RemoteEnvironmentRegistry.Entry peer = peer(instance);
        JSONObject request = MixedTeamCoordinator.object("operation", operation); request.put("payload", payload);
        return post("http://127.0.0.1:" + peer.port + PARTICIPANT_PATH, peer.lease, request);
    }
    private JSONObject localCommand(String operation, JSONObject payload, boolean localCenter) {
        String teamId = payload.getString("teamId");
        if (teamId == null) return TeamCoordinationBridge.participant().command(operation, payload);
        // Local serialization contains no network calls. Persist deletion intent even
        // when the timed-out create has not reached this participant yet.
        synchronized (participantLocks.computeIfAbsent(teamId, ignored -> new Object())) {
            if ("create".equals(operation)) bind(payload, localCenter);
            if ("delete".equals(operation)) {
                JSONObject binding = store.find("bindings", teamId);
                if (binding == null) { bind(payload, localCenter); binding = store.find("bindings", teamId); }
                if (!Objects.equals(binding.getString("ownerChatterId"), payload.getString("ownerChatterId")))
                    throw new CoordinationException("UNAUTHORIZED", "删除队伍 owner 不匹配");
                binding.put("creationBlocked", true); store.save("bindings", teamId, binding);
            }
            return TeamCoordinationBridge.participant().command(operation, payload);
        }
    }
    @Override public void project(String home, String owner, JSONObject event) {
        if (registry.localInstanceId().equals(home)) { acceptProjection(owner, event); return; }
        RemoteEnvironmentRegistry.Entry peer = peer(home);
        JSONObject request = MixedTeamCoordinator.object("operation", "project");
        request.put("ownerChatterId", owner); request.put("payload", event);
        post("http://127.0.0.1:" + peer.port + PARTICIPANT_PATH, peer.lease, request);
    }
    private void acceptProjection(String owner, JSONObject event) {
        if (!authorizesProjection(owner, event)) throw new CoordinationException("UNAUTHORIZED", "协调事件不属于当前队伍或成员");
        if (StarweaveIdentity.ownerId(registry.localInstanceId()).equals(owner)) {
            TeamCoordinationBridge.project(registry.localInstanceId(), owner, event);
        } else {
            // Persist and acknowledge independently of the optional MolaChat process.
            if (store.find("projection-receipts", event.getString("eventId")) != null) return;
            JSONObject pending = MixedTeamCoordinator.copy(event); pending.put("projectionOwner", owner);
            store.enqueue("projections", event.getString("eventId"), pending, 10000);
        }
    }
    static boolean isRealtimeEvent(JSONObject event) {
        return event != null && Arrays.asList("MESSAGE_UPDATED", "USER_MESSAGE_ACCEPTED", "MESSAGE_CHUNK",
                "MESSAGE_COMPLETE", "MESSAGE_ERROR", "TOOL_CALL", "TALK_TO_SEND", "TALK_TO_RECEIVE",
                "TALK_TO_QUEUED", "TALK_TO_REJECTED", "SCHEDULE_EVENT", "SUB_AGENT_EVENT", "COMPACTION_EVENT",
                "TASK_EVENT", "LIFECYCLE_EVENT", "MEMBER_SESSION_CHANGED").contains(event.getString("type"));
    }
    private boolean receiveRealtime(String source, JSONObject message) {
        try { return dispatchRealtime(source, message); }
        catch (CoordinationException rejected) {
            if (!Arrays.asList("UNAUTHORIZED", "NOT_FOUND", "TEAM_DELETED").contains(rejected.getCode())) throw rejected;
            // A retired team's pending frame must not block later frames for other teams.
            // Acknowledge permanent rejection without projecting any untrusted content.
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("团队实时事件已拒绝: source={}, code={}", source, rejected.getCode());
            return true;
        }
    }
    private boolean dispatchRealtime(String source, JSONObject message) {
        if (closed || message == null) return false;
        JSONObject event = message.getJSONObject("payload");
        if (!isRealtimeEvent(event)) throw new CoordinationException("UNAUTHORIZED", "实时通道不能投递控制事件");
        if (source != null && "event".equals(message.getString("operation"))) return routeRealtime(source, event);
        if (source == null && "project".equals(message.getString("operation"))) {
            String owner = message.getString("ownerChatterId");
            if (!authorizesProjection(owner, event)) throw new CoordinationException("UNAUTHORIZED", "实时事件不属于当前队伍");
            return projectRealtime(owner, event);
        }
        throw new CoordinationException("UNAUTHORIZED", "实时事件方向无效");
    }
    private boolean routeRealtime(String source, JSONObject event) {
        JSONObject destination = coordinator.realtimeDestination(source, event);
        if (destination.getBooleanValue("ignored")) return true;
        String home = destination.getString("homeInstanceId"), owner = destination.getString("ownerChatterId");
        if (registry.localInstanceId().equals(home)) {
            if (!authorizesProjection(owner, event)) throw new CoordinationException("UNAUTHORIZED", "实时事件不属于当前队伍");
            return projectRealtime(owner, event);
        }
        JSONObject frame = MixedTeamCoordinator.object("operation", "project", "payload", event);
        frame.put("ownerChatterId", owner);
        return registry.sendTeamRealtime(home, frame);
    }
    private boolean projectRealtime(String owner, JSONObject event) {
        if (StarweaveIdentity.ownerId(registry.localInstanceId()).equals(owner)) {
            TeamCoordinationBridge.project(registry.localInstanceId(), owner, event); return true;
        }
        // Optional MolaChat callbacks cannot block a participant's real-time TLS channel.
        // An offline client reconstructs display from authoritative session history on reconnect.
        if (!com.mola.cmd.proxy.client.provider.CmdReceiver.INSTANCE.hasCallbackConsumer("team-acp-" + registry.localInstanceId())) return true;
        return enqueueClientProjection(owner, event, false);
    }
    boolean enqueueClientProjection(String owner, JSONObject event, boolean durable) {
        // MolaChat consumes incremental stream events, not Starweave's full-message
        // revision snapshots. Sending both doubles callback work and delays chunks.
        if ("MESSAGE_UPDATED".equals(event.getString("type"))) return true;
        String id = event.getString("eventId");
        if (!pendingClientDeliveries.add(id)) return true;
        int bytes = event.toJSONString().getBytes(StandardCharsets.UTF_8).length;
        if (clientProjectionBytes.addAndGet(bytes) > 32 * 1024 * 1024) {
            clientProjectionBytes.addAndGet(-bytes); pendingClientDeliveries.remove(id); return false;
        }
        try {
            String key = event.getString("teamId") + ":" + event.getString("teamMemberId");
            clientDelivery[(key.hashCode() & Integer.MAX_VALUE) % clientDelivery.length].execute(() -> {
                try {
                    streamProjection.project(event, projected ->
                            clientSender.accept(owner, projected));
                    if (durable) {
                        store.save("projection-receipts", id, MixedTeamCoordinator.object("timestamp", System.currentTimeMillis()));
                        store.delete("projections", id);
                    }
                }
                catch (RuntimeException unavailable) { org.slf4j.LoggerFactory.getLogger(getClass()).debug("实时客户端投影已断开: {}", unavailable.getMessage()); }
                finally { clientProjectionBytes.addAndGet(-bytes); pendingClientDeliveries.remove(id); }
            });
            return true;
        } catch (RejectedExecutionException full) {
            clientProjectionBytes.addAndGet(-bytes); pendingClientDeliveries.remove(id); return false;
        }
    }
    private void projectClientsSafely() {
        if (closed) return;
        try {
            for (JSONObject pending : store.list("projections")) {
                if (store.find("projection-receipts", pending.getString("eventId")) != null) {
                    store.delete("projections", pending.getString("eventId")); continue;
                }
                String owner = pending.getString("projectionOwner"); pending.remove("projectionOwner");
                if (!enqueueClientProjection(owner, pending, true)) break;
            }
        } catch (RuntimeException retryOnReconnect) { /* Durable client projection is retried on reconnect. */ }
    }
    private RemoteEnvironmentRegistry.Entry peer(String instance) {
        for (RemoteEnvironmentRegistry.Entry peer : registry.teamPeers()) if (instance.equals(peer.sourceInstanceId)) return peer;
        throw new CoordinationException("TEAM_NOT_READY", "参与实例当前离线");
    }
    private long lastReconcile;
    private void tickSafely() {
        if (closed) return;
        try {
            for (JSONObject event : store.list("outbox")) {
                JSONObject binding = store.find("bindings", event.getString("teamId"));
                if (binding == null) continue;
                try {
                    if ("LOCAL".equals(binding.getString("centerUrl")) && registry.isCenterEnabled()) coordinator.acceptEvent(registry.localInstanceId(), event);
                    else {
                        JSONObject connection = registry.teamCenterConnection();
                        if (connection == null || !Objects.equals(connection.getString("centerUrl"), binding.getString("centerUrl"))) continue;
                        JSONObject request = MixedTeamCoordinator.object("operation", "event"); request.put("payload", event);
                        request.put("environmentId", connection.getString("environmentId"));
                        post(connection.getString("centerUrl") + CENTER_PATH, connection.getString("lease"), request);
                    }
                    store.delete("outbox", event.getString("eventId"));
                } catch (RuntimeException retryLater) { break; }
            }
            if (registry.isCenterEnabled()) {
                coordinator.drainEvents();
                if (System.currentTimeMillis() - lastReconcile > 5000) {
                    coordinator.reconcileAll(); lastReconcile = System.currentTimeMillis();
                }
            }
        } catch (RuntimeException failure) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("团队协调后台处理暂未完成: {}", failure.getMessage()); }
    }
    private static void validateOwner(String owner, String home, JSONObject descriptor) {
        if (StarweaveIdentity.ownerId(home).equals(owner)) return;
        JSONArray sources = descriptor.getJSONArray("teamMemberSources");
        if (sources != null) for (Object source : sources) if (Objects.equals(owner, ((JSONObject) source).getString("ownerChatterId"))) return;
        throw new CoordinationException("UNAUTHORIZED", "发起实例不能代理该队伍 owner");
    }
    private JSONObject post(String url, String lease, JSONObject request) {
        Request.Builder builder = new Request.Builder().url(url).post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), request.toJSONString()));
        if (lease != null) builder.header(LEASE_HEADER, lease);
        // Discovery must not hold a user request behind a slow participant for two minutes.
        OkHttpClient client = "describe".equals(request.getString("operation"))
                ? http.newBuilder().readTimeout(5, TimeUnit.SECONDS).build() : http;
        try (Response response = client.newCall(builder.build()).execute()) {
            if (response.body() == null) throw new IOException("empty response");
            JSONObject value = JSON.parseObject(response.body().string());
            if (!response.isSuccessful() || value == null || !value.getBooleanValue("accepted"))
                throw new CoordinationException(value == null ? "TRANSPORT_FAILED" : value.getString("code"), value == null ? "团队协调响应为空" : value.getString("message"));
            JSONObject data = value.getJSONObject("data"); return data == null ? new JSONObject(true) : data;
        } catch (IOException failure) { throw new CoordinationException("TRANSPORT_FAILED", "团队协调连接失败或提交结果未知，请使用原请求 ID 查询或重试"); }
    }
    private static JSONObject body(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) >= 0) {
                if (output.size() + n > 280L * 1024L * 1024L) throw new CoordinationException("PAYLOAD_TOO_LARGE", "团队请求超过大小限制");
                output.write(buffer, 0, n);
            }
            JSONObject value = JSON.parseObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            if (value == null) throw new CoordinationException("VALIDATION_ERROR", "请求不能为空"); return value;
        }
    }
    private void handle(HttpExchange exchange, Operation operation) throws IOException {
        JSONObject response = new JSONObject(true); int status = 200;
        try {
            if (!"POST".equals(exchange.getRequestMethod())) throw new CoordinationException("METHOD_NOT_ALLOWED", "仅支持 POST");
            // Business identity is lease-authenticated. Browser cookies never authorize this endpoint.
            if (exchange.getRequestHeaders().getFirst("Origin") != null) throw new CoordinationException("UNAUTHORIZED", "此接口仅供实例通信");
            response.put("data", operation.run()); response.put("accepted", true); response.put("code", "OK");
        } catch (RuntimeException failure) {
            String code = failure instanceof CoordinationException ? ((CoordinationException) failure).getCode() : "INTERNAL_ERROR";
            status = "UNAUTHORIZED".equals(code) ? 403 : 422;
            response.put("accepted", false); response.put("code", code); response.put("message", failure.getMessage());
        }
        byte[] bytes = response.toJSONString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store"); exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); } finally { exchange.close(); }
    }
    @FunctionalInterface private interface Operation { JSONObject run() throws IOException; }
    @Override public void close() {
        closed = true; TeamCoordinationBridge.clearService(this); worker.shutdownNow(); clientProjector.shutdownNow();
        registry.teamRealtimeReceiver(null);
        for (ThreadPoolExecutor delivery : clientDelivery) delivery.shutdownNow();
        http.dispatcher().cancelAll(); http.connectionPool().evictAll(); http.dispatcher().executorService().shutdownNow();
    }
}
