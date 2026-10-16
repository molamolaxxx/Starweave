package com.mola.starweave.agent;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal MCP client for the transports used by the existing Pi integration. */
final class McpConnection implements AutoCloseable {
    private final AgentConfig config;
    private final JsonObject server;
    private final Map<String, String> headers = new HashMap<>();
    private final ConcurrentMap<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final Cancellation lifetime = new Cancellation();
    private final String type;
    private volatile String endpoint, sessionHeader, protocol = "2025-06-18";
    private Process process;
    private BufferedWriter writer;
    private volatile boolean closed;
    private volatile Exception transportFailure;
    McpConnection(AgentConfig config, JsonObject server, Path cwd, Cancellation cancel) throws Exception {
        this.config = config; this.server = server; type = Json.str(server, "type"); endpoint = Json.str(server, "url");
        for (JsonElement h : Json.list(server, "headers")) { JsonObject o = h.getAsJsonObject(); headers.put(Json.str(o, "name"), Json.str(o, "value")); }
        try {
            if (type.equals("sse")) startSse(cancel);
            else if (!type.equals("http")) startStdio(cwd);
            JsonObject init = call("initialize", Json.obj("protocolVersion", protocol, "capabilities", Json.obj(),
                    "clientInfo", Json.obj("name", "starweave-java-agent", "version", "1.0.0")), cancel, 15000);
            String negotiated = Json.str(init, "protocolVersion"); if (!negotiated.isEmpty()) protocol = negotiated;
            notify("notifications/initialized", Json.obj(), cancel);
        } catch (Exception e) { close(); throw e; }
    }
    JsonArray discover(Cancellation cancel) throws Exception {
        JsonArray tools = new JsonArray(); String cursor = ""; Set<String> cursors = new HashSet<>();
        do {
            JsonObject page = call("tools/list", cursor.isEmpty() ? Json.obj() : Json.obj("cursor", cursor), cancel, 15000);
            tools.addAll(Json.list(page, "tools")); cursor = Json.str(page, "nextCursor");
            if (!cursor.isEmpty() && !cursors.add(cursor)) throw new IOException("MCP pagination cursor repeated");
            if (tools.size() > 10000) throw new IOException("MCP tools exceed limit");
        } while (!cursor.isEmpty());
        return tools;
    }
    JsonObject invoke(String name, JsonObject arguments, Cancellation cancel) throws Exception {
        JsonObject result = call("tools/call", Json.obj("name", name, "arguments", arguments), cancel, 120000);
        if (result.has("isError") && result.get("isError").getAsBoolean()) throw new IOException(Json.GSON.toJson(result));
        return result;
    }
    private JsonObject call(String method, JsonObject params, Cancellation cancel, int timeout) throws Exception {
        cancel.check(); if (closed) throw new IOException("MCP connection closed");
        if (transportFailure != null) throw new IOException("MCP transport disconnected; recreate the session to reconnect", transportFailure);
        String id = Integer.toString(ids.incrementAndGet());
        CompletableFuture<JsonObject> future = new CompletableFuture<>(); pending.put(id, future);
        try (AutoCloseable hook = cancel.onCancel(() -> future.completeExceptionally(new CancellationException("MCP cancelled")))) {
            send(Json.obj("jsonrpc", "2.0", "id", id, "method", method, "params", params), cancel, timeout);
            JsonObject response = future.get(timeout, TimeUnit.MILLISECONDS); cancel.check();
            if (response.has("error")) throw new IOException("MCP: " + Json.GSON.toJson(response.get("error")));
            return Json.object(response, "result");
        } catch (TimeoutException | CancellationException e) {
            try { notify("notifications/cancelled", Json.obj("requestId", id, "reason", "cancelled"), lifetime); } catch (Exception ignored) { }
            throw e;
        } finally { pending.remove(id); }
    }
    private void notify(String method, JsonObject params, Cancellation cancel) throws Exception { send(Json.obj("jsonrpc", "2.0", "method", method, "params", params), cancel, 15000); }
    private void send(JsonObject message, Cancellation cancel, int timeout) throws Exception {
        if (closed) throw new IOException("MCP connection closed");
        if (writer != null) { synchronized (writer) { writer.write(Json.GSON.toJson(message)); writer.newLine(); writer.flush(); } return; }
        Map<String, String> h = new HashMap<>(headers); h.put("Accept", "application/json, text/event-stream"); h.put("MCP-Protocol-Version", protocol);
        if (sessionHeader != null) h.put("Mcp-Session-Id", sessionHeader);
        HttpURLConnection c = Http.open(config, endpoint, "POST", h, timeout);
        try (AutoCloseable hook = cancel.onCancel(c::disconnect); AutoCloseable life = lifetime.onCancel(c::disconnect)) {
            Http.send(c, message); InputStream input = Http.checked(c);
            String sid = c.getHeaderField("Mcp-Session-Id"); if (sid != null) sessionHeader = sid;
            if (c.getResponseCode() == 202 || c.getResponseCode() == 204) { input.close(); return; }
            if (String.valueOf(c.getContentType()).contains("text/event-stream")) Http.sse(input, cancel, (event, data) -> receive(Json.parse(data)));
            else { String body = Http.read(input, 32 * 1024 * 1024); if (!body.trim().isEmpty()) receive(Json.parse(body)); }
        } finally { c.disconnect(); }
    }
    private void startStdio(Path cwd) throws IOException {
        List<String> command = new ArrayList<>(); command.add(Json.str(server, "command"));
        for (JsonElement arg : Json.list(server, "args")) command.add(arg.getAsString());
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()); builder.environment().putAll(config.environment);
        for (JsonElement e : Json.list(server, "env")) { JsonObject env = e.getAsJsonObject(); builder.environment().put(Json.str(env, "name"), Json.str(env, "value")); }
        process = builder.start(); writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        daemon("mcp-stdio", () -> {
            try (Reader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = Http.boundedLine(in, 32 * 1024 * 1024)) != null) if (!line.trim().isEmpty()) receive(Json.parse(line));
                fail(new IOException("MCP process exited"));
            } catch (Exception e) { fail(e); }
        });
        daemon("mcp-stderr", () -> { try (InputStream in = process.getErrorStream()) { byte[] b = new byte[4096]; while (in.read(b) != -1) { /* drain without leaking server credentials */ } } catch (IOException ignored) { } });
    }
    private void startSse(Cancellation cancel) throws Exception {
        CompletableFuture<String> ready = new CompletableFuture<>(); String url = endpoint;
        HttpURLConnection c = Http.open(config, url, "GET", headers, 120000); c.setRequestProperty("Accept", "text/event-stream");
        lifetime.onCancel(c::disconnect);
        daemon("mcp-sse", () -> {
            try {
                Http.sse(Http.checked(c), lifetime, (event, data) -> {
                    if (event.equals("endpoint")) {
                        URI origin = URI.create(url), resolved = origin.resolve(data);
                        if (!Objects.equals(origin.getScheme(), resolved.getScheme()) || !Objects.equals(origin.getAuthority(), resolved.getAuthority())) throw new IOException("MCP SSE endpoint changed origin");
                        endpoint = resolved.toString(); ready.complete(endpoint);
                    } else if (event.equals("message")) receive(Json.parse(data));
                });
                IOException eof = new IOException("MCP SSE disconnected"); ready.completeExceptionally(eof); fail(eof);
            } catch (Exception e) { ready.completeExceptionally(e); fail(e); } finally { c.disconnect(); }
        });
        try (AutoCloseable hook = cancel.onCancel(() -> { c.disconnect(); ready.cancel(false); })) { ready.get(15000, TimeUnit.MILLISECONDS); }
    }
    private void receive(JsonObject response) throws Exception {
        if (response.has("id") && !response.has("method")) {
            CompletableFuture<JsonObject> future = pending.get(response.get("id").getAsString()); if (future != null) future.complete(response);
        } else if (response.has("id")) {
            String method = Json.str(response, "method");
            JsonObject reply = Json.obj("jsonrpc", "2.0", "id", response.get("id"));
            if (method.equals("ping")) reply.add("result", Json.obj());
            else reply.add("error", Json.obj("code", -32601, "message", "Unsupported MCP client method"));
            send(reply, lifetime, 15000);
        }
    }
    private void fail(Exception error) { transportFailure = error; for (CompletableFuture<JsonObject> f : pending.values()) f.completeExceptionally(error); }
    static Thread daemon(String name, Runnable task) { Thread t = new Thread(task, "starweave-agent-" + name); t.setDaemon(true); t.start(); return t; }
    @Override public void close() {
        if (closed) return; closed = true; lifetime.cancel(); fail(new IOException("MCP closed"));
        if (process != null) { try { process.getOutputStream().close(); } catch (IOException ignored) { } process.destroy(); if (process.isAlive()) process.destroyForcibly(); }
        if (type.equals("http") && sessionHeader != null) {
            Map<String, String> h = new HashMap<>(headers); h.put("Mcp-Session-Id", sessionHeader); h.put("MCP-Protocol-Version", protocol);
            try { HttpURLConnection c = Http.open(config, endpoint, "DELETE", h, 1000); try { c.getResponseCode(); } finally { c.disconnect(); } }
            catch (IOException ignored) { /* server may not expose explicit termination */ }
        }
    }
}
