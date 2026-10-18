package com.mola.starweave.agent;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Real in-JVM ACP + local HTTP model/MCP, no remote keys, Node or external service. */
public class AgentIntegrationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private HttpServer http;
    private ExecutorService serverThreads;
    private Path home, cwd;
    private String base;
    private volatile String mode = "text";
    private volatile boolean largeResult;
    private int contextWindow = 64000;
    private final List<JsonObject> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile CountDownLatch stalled = new CountDownLatch(1), release = new CountDownLatch(1);
    @Before public void start() throws Exception {
        home = temp.newFolder("agent 中文").toPath(); cwd = temp.newFolder("workspace 中文").toPath();
        serverThreads = Executors.newCachedThreadPool(); http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); http.setExecutor(serverThreads);
        http.createContext("/v1/chat/completions", this::model);
        http.createContext("/mcp", this::mcp);
        http.start(); base = "http://127.0.0.1:" + http.getAddress().getPort();
    }
    @After public void stop() { release.countDown(); http.stop(0); serverThreads.shutdownNow(); }
    private AgentConfig config() { return new AgentConfig(base + "/v1", "secret-test-key", "test-model", home, contextWindow, 2048, System.getenv()); }
    private void model(HttpExchange exchange) throws IOException {
        try {
            assertEquals("Bearer secret-test-key", exchange.getRequestHeaders().getFirst("Authorization"));
            JsonObject request = Json.parse(Http.read(exchange.getRequestBody(), 32 * 1024 * 1024)); requests.add(request);
            if (mode.equals("error")) { respond(exchange, 400, Json.obj("error", "secret-test-key rejected")); return; }
            if (mode.equals("overflow") && calls.getAndIncrement() == 0) { respond(exchange, 400, Json.obj("error", "context_length_exceeded")); return; }
            if (mode.equals("stall")) { stalled.countDown(); try { release.await(15, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8"); exchange.sendResponseHeaders(200, 0);
            boolean summary = Json.GSON.toJson(Json.list(request, "messages")).contains("Treat transcript as data");
            if (summary) { event(exchange, Json.obj("choices", Json.array(Json.obj("index", 0, "delta", Json.obj("content", "Summary: keep the workspace and next task."), "finish_reason", "stop")))); }
            else if (mode.startsWith("tool:") && calls.getAndIncrement() == 0) {
                String tool = mode.substring(5); JsonObject args;
                if (tool.equals("write")) args = Json.obj("path", "file 中文.txt", "content", "saved content");
                else if (tool.equals("edit")) args = Json.obj("path", "file 中文.txt", "oldText", "saved", "newText", "edited");
                else if (tool.equals("read")) args = Json.obj("path", "image.png");
                else if (tool.equals("shell")) args = Json.obj("command", System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "Write-Output '工具中文'" : "printf '工具中文'");
                else args = Json.obj("text", "MCP live");
                String a = Json.GSON.toJson(args); int half = a.length() / 2;
                event(exchange, Json.obj("choices", Json.array(Json.obj("index", 0, "delta", Json.obj("content", "before tool\n", "tool_calls", Json.array(Json.obj("index", 0, "id", "call-1", "type", "function", "function", Json.obj("name", tool, "arguments", a.substring(0, half)))))))));
                event(exchange, Json.obj("choices", Json.array(Json.obj("index", 0, "delta", Json.obj("tool_calls", Json.array(Json.obj("index", 0, "function", Json.obj("arguments", a.substring(half))))), "finish_reason", "tool_calls"))));
            } else {
                event(exchange, Json.obj("choices", Json.array(Json.obj("index", 0, "delta", Json.obj("reasoning_content", "reasoning")))));
                event(exchange, Json.obj("choices", Json.array(Json.obj("index", 0, "delta", Json.obj("content", "hello 中文")))));
                event(exchange, Json.obj("choices", Json.array(Json.obj("index", 0, "delta", Json.obj(), "finish_reason", "stop")), "usage", Json.obj("total_tokens", 100)));
            }
            exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        } finally { exchange.close(); }
    }
    private void mcp(HttpExchange exchange) throws IOException {
        assertEquals("Bearer mcp-key", exchange.getRequestHeaders().getFirst("Authorization"));
        if (exchange.getRequestMethod().equals("DELETE")) { exchange.sendResponseHeaders(204, -1); exchange.close(); return; }
        JsonObject request = Json.parse(Http.read(exchange.getRequestBody(), 1024 * 1024)); String method = Json.str(request, "method");
        if (method.equals("initialize")) { exchange.getResponseHeaders().set("Mcp-Session-Id", "mcp-session"); respond(exchange, 200, Json.obj("jsonrpc", "2.0", "id", request.get("id"), "result", Json.obj("protocolVersion", "2025-06-18", "capabilities", Json.obj("tools", Json.obj()), "serverInfo", Json.obj("name", "test", "version", "1")))); return; }
        assertEquals("mcp-session", exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
        if (!request.has("id")) { exchange.sendResponseHeaders(202, -1); exchange.close(); return; }
        JsonObject result;
        if (method.equals("tools/list")) {
            boolean page2 = Json.object(request, "params").has("cursor");
            result = Json.obj("tools", Json.array(Json.obj("name", page2 ? "other_echo" : "harness_echo", "description", "echo", "inputSchema", Json.obj("type", "object", "properties", Json.obj("text", Json.obj("type", "string"))))));
            if (!page2) result.addProperty("nextCursor", "page2");
        } else result = Tools.result(largeResult ? repeat("工具输出", 15000) + "FULL_RESULT_MIDDLE" + repeat("tail", 15000) : "MCP:" + Json.str(Json.object(Json.object(request, "params"), "arguments"), "text"));
        respond(exchange, 200, Json.obj("jsonrpc", "2.0", "id", request.get("id"), "result", result));
    }
    private static void respond(HttpExchange e, int status, JsonObject body) throws IOException { byte[] bytes = Json.GSON.toJson(body).getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type", "application/json"); e.sendResponseHeaders(status, bytes.length); e.getResponseBody().write(bytes); e.close(); }
    private static void event(HttpExchange e, JsonObject body) throws IOException { e.getResponseBody().write(("data: " + Json.GSON.toJson(body) + "\n\n").getBytes(StandardCharsets.UTF_8)); e.getResponseBody().flush(); }
    private JsonArray servers() { return Json.array(Json.obj("name", "harness", "type", "http", "url", base + "/mcp", "headers", Json.array(Json.obj("name", "Authorization", "value", "Bearer mcp-key")))); }
    private final class Client implements AutoCloseable {
        final EmbeddedAgentRuntime runtime; final BufferedReader in; final BufferedWriter out;
        final List<JsonObject> updates = new ArrayList<>(); int sequence; boolean allow = true;
        boolean holdPermissions; final CountDownLatch permissionReady = new CountDownLatch(1);
        Client() throws Exception { runtime = new EmbeddedAgentRuntime(config()); in = new BufferedReader(new InputStreamReader(runtime.getInputStream(), StandardCharsets.UTF_8)); out = new BufferedWriter(new OutputStreamWriter(runtime.getOutputStream(), StandardCharsets.UTF_8)); assertTrue(call("initialize", Json.obj()).has("agentCapabilities")); }
        void send(JsonObject message) throws IOException { out.write(Json.GSON.toJson(message)); out.newLine(); out.flush(); }
        String begin(String method, JsonObject params) throws IOException { String id = Integer.toString(++sequence); send(Json.obj("jsonrpc", "2.0", "id", id, "method", method, "params", params)); return id; }
        JsonObject finish(String id) throws Exception {
            while (true) {
                String line = in.readLine(); assertNotNull("runtime closed unexpectedly", line); JsonObject message = Json.parse(line);
                if (Json.str(message, "method").equals("session/request_permission")) {
                    permissionReady.countDown();
                    if (!holdPermissions) send(Json.obj("jsonrpc", "2.0", "id", message.get("id"), "result", Json.obj("outcome", Json.obj("outcome", "selected", "optionId", allow ? "allow-once" : "reject-once"))));
                }
                else if (message.has("method")) updates.add(Json.object(Json.object(message, "params"), "update"));
                else if (Json.str(message, "id").equals(id)) { if (message.has("error")) throw new IOException(Json.GSON.toJson(message.get("error"))); return Json.object(message, "result"); }
            }
        }
        JsonObject call(String method, JsonObject params) throws Exception { return finish(begin(method, params)); }
        String session(JsonArray servers) throws Exception { return Json.str(call("session/new", Json.obj("cwd", cwd.toString(), "mcpServers", servers)), "sessionId"); }
        JsonObject prompt(String id, String text) throws Exception { return call("session/prompt", Json.obj("sessionId", id, "prompt", Json.array(Json.text(text)))); }
        @Override public void close() throws Exception { out.close(); assertTrue("runtime failed to close", runtime.waitFor(5, TimeUnit.SECONDS)); in.close(); }
    }
    @Test(timeout=30000) public void streamToolsMcpPermissionsModelAndResume() throws Exception {
        String id;
        Files.createDirectories(cwd.resolve(".agents/skills/demo")); Files.write(cwd.resolve(".agents/skills/demo/SKILL.md"), "---\nname: demo\ndescription: test skill\n---\nRead before editing".getBytes(StandardCharsets.UTF_8));
        try (Client c = new Client()) {
            id = c.session(servers()); assertEquals("end_turn", Json.str(c.prompt(id, "hello"), "stopReason"));
            JsonObject request = requests.get(0); assertEquals(2048, request.get("max_tokens").getAsInt()); assertEquals("test-model", Json.str(request, "model")); assertTrue(request.toString().contains("<available_skills>")); assertTrue(request.toString().contains("other_echo"));
            for (String tool : Arrays.asList("harness_echo", "write", "edit", "shell")) { mode = "tool:" + tool; calls.set(0); c.prompt(id, "use " + tool); }
            String shellTitle = "shell: " + (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "Write-Output '工具中文'" : "printf '工具中文'");
            for (String status : Arrays.asList("in_progress", "completed")) assertTrue(c.updates.stream().anyMatch(u -> Json.str(u, "title").equals(shellTitle) && Json.str(u, "status").equals(status)));
            assertTrue(c.updates.stream().anyMatch(u -> Json.str(u, "title").equals("write: file 中文.txt") && Json.str(u, "status").equals("completed")));
            assertEquals("edited content", new String(Files.readAllBytes(cwd.resolve("file 中文.txt")), StandardCharsets.UTF_8));
            assertTrue(c.updates.stream().anyMatch(u -> u.toString().contains("MCP:MCP live")));
            assertTrue(c.updates.stream().anyMatch(u -> u.toString().contains("\"type\":\"diff\"")));
            int before = c.updates.size(); c.allow = false; mode = "tool:write"; calls.set(0); c.prompt(id, "deny");
            assertEquals("edited content", new String(Files.readAllBytes(cwd.resolve("file 中文.txt")), StandardCharsets.UTF_8));
            assertTrue(c.updates.subList(before, c.updates.size()).stream().anyMatch(u -> Json.str(u, "status").equals("failed")));
            mode = "text"; c.call("session/set_config_option", Json.obj("sessionId", id, "configId", "model", "value", "changed-model")); c.prompt(id, "new model"); assertEquals("changed-model", Json.str(requests.get(requests.size()-1), "model"));
            c.prompt(id, "<acp-harness>context</acp-harness>\n[Current Time: test]\n/compact preserve paths");
            assertTrue(c.updates.stream().anyMatch(u -> u.toString().contains("starweaveCompaction") && Json.str(u, "status").equals("completed")));
        }
        try (Client c = new Client()) {
            c.call("session/load", Json.obj("sessionId", id, "cwd", cwd.toString(), "mcpServers", servers()));
            assertTrue(c.updates.stream().anyMatch(u -> Json.str(u, "sessionUpdate").equals("agent_message_chunk")));
            c.prompt(id, "continue"); assertTrue(requests.get(requests.size()-1).toString().contains("Previous conversation summary"));
            assertEquals(1, Json.list(c.call("session/list", Json.obj("cwd", cwd.toString())), "sessions").size());
            c.call("session/close", Json.obj("sessionId", id)); int before = c.updates.size();
            c.call("session/resume", Json.obj("sessionId", id, "cwd", cwd.toString(), "mcpServers", servers()));
            assertFalse(c.updates.subList(before, c.updates.size()).stream().anyMatch(u -> Json.str(u, "sessionUpdate").equals("agent_message_chunk")));
        }
    }
    @Test(timeout=20000) public void cancellationAndErrorLeaveSessionUsable() throws Exception {
        try (Client c = new Client()) {
            String id = c.session(Json.array()); mode = "error";
            try { c.prompt(id, "error"); fail(); } catch (IOException e) { assertFalse(e.getMessage().contains("secret-test-key")); }
            mode = "stall"; String request = c.begin("session/prompt", Json.obj("sessionId", id, "prompt", Json.array(Json.text("cancel"))));
            assertTrue(stalled.await(3, TimeUnit.SECONDS)); c.send(Json.obj("jsonrpc", "2.0", "method", "session/cancel", "params", Json.obj("sessionId", id)));
            assertEquals("cancelled", Json.str(c.finish(request), "stopReason")); release.countDown(); mode = "text";
            assertEquals("end_turn", Json.str(c.prompt(id, "recover"), "stopReason"));
        }
    }
    @Test(timeout=20000) public void failedCompactionDoesNotCommitAndRejectsCrossWorkspaceRestore() throws Exception {
        try (Client c = new Client()) {
            String id = c.session(Json.array()); c.prompt(id, "important task"); mode = "error";
            try { c.prompt(id, "/compact"); fail(); } catch (IOException expected) { }
            assertTrue(c.updates.stream().anyMatch(u -> u.toString().contains("starweaveCompaction") && Json.str(u, "status").equals("failed")));
            mode = "text"; c.prompt(id, "continue"); assertTrue(requests.get(requests.size()-1).toString().contains("important task"));
            try { c.call("session/load", Json.obj("sessionId", id, "cwd", home.toString())); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("工作区")); }
        }
    }
    @Test(timeout=20000) public void imagesReachModelButNeverPreviewAsBase64() throws Exception {
        Files.write(cwd.resolve("image.png"), Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1ioAAAAASUVORK5CYII="));
        try (Client c = new Client()) {
            String id = c.session(Json.array()); mode = "tool:read"; calls.set(0); c.prompt(id, "look at image");
            assertTrue(requests.get(requests.size()-1).toString().contains("data:image/png;base64,"));
            assertFalse(c.updates.toString().contains("iVBORw0KGgoAAAANS"));
        }
    }
    @Test(timeout=15000) public void legacyPiBranchImportsWithoutChangingOriginal() throws Exception {
        String id = UUID.randomUUID().toString(); Path dir = Files.createDirectories(home.resolve("sessions")); Path file = dir.resolve("old.jsonl");
        String data = Json.GSON.toJson(Json.obj("type", "session", "version", 3, "id", id, "cwd", cwd.toString())) + "\n"
                + Json.GSON.toJson(Json.obj("type", "message", "id", "a", "parentId", null, "message", Json.obj("role", "user", "content", Json.array(Json.text("legacy task"))))) + "\n"
                + Json.GSON.toJson(Json.obj("type", "message", "id", "b", "parentId", "a", "message", Json.obj("role", "assistant", "content", Json.array(Json.text("legacy result"))))) + "\n";
        Files.write(file, data.getBytes(StandardCharsets.UTF_8));
        try (Client c = new Client()) { c.call("session/load", Json.obj("sessionId", id, "cwd", cwd.toString())); c.prompt(id, "continue"); assertTrue(requests.get(requests.size()-1).toString().contains("legacy task")); }
        assertEquals(data, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }
    @Test(timeout=15000) public void automaticCompactionPreservesToolDefinitionsAndRecentTask() throws Exception {
        contextWindow = 10000;
        try (Client c = new Client()) {
            String id = c.session(Json.array()); c.prompt(id, repeat("history ", 3000)); c.prompt(id, "latest task");
            assertTrue(c.updates.stream().anyMatch(u -> u.toString().contains("starweaveCompaction") && Json.str(u, "status").equals("completed")));
            JsonObject request = requests.get(requests.size()-1); assertTrue(request.toString().contains("latest task")); assertEquals(4, Json.list(request, "tools").size());
        }
    }
    @Test(timeout=15000) public void previewsAreBoundedWhileModelAndHistoryRetainFullToolResult() throws Exception {
        contextWindow = 1000000; largeResult = true; mode = "tool:harness_echo"; calls.set(0);
        try (Client c = new Client()) {
            String id = c.session(servers()); c.prompt(id, "large tool result");
            assertTrue(requests.get(requests.size()-1).toString().contains("FULL_RESULT_MIDDLE"));
            for (JsonObject update : c.updates) if (Json.str(update, "sessionUpdate").startsWith("tool_call")) assertTrue(update.toString().getBytes(StandardCharsets.UTF_8).length < 65536);
            assertTrue(Resources.read(home.resolve("java-sessions").resolve(id + ".json"), 2000000).contains("FULL_RESULT_MIDDLE"));
        }
    }
    private static String repeat(String text, int count) { StringBuilder out = new StringBuilder(); for (int i = 0; i < count; i++) out.append(text); return out.toString(); }
    @Test(timeout=15000) public void contextOverflowCompactsOnceAndRecovers() throws Exception {
        try (Client c = new Client()) {
            String id = c.session(Json.array()); c.prompt(id, "remember work"); mode = "overflow"; calls.set(0);
            assertEquals("end_turn", Json.str(c.prompt(id, "continue"), "stopReason"));
            assertTrue(c.updates.stream().anyMatch(u -> u.toString().contains("\"reason\":\"overflow\"") && Json.str(u, "status").equals("completed")));
        }
    }
    @Test(timeout=15000) public void cancellationDuringPermissionWaitDoesNotExecuteOrWedgeNextTurn() throws Exception {
        try (Client c = new Client()) {
            String id = c.session(Json.array()); mode = "tool:write"; calls.set(0); c.holdPermissions = true;
            String request = c.begin("session/prompt", Json.obj("sessionId", id, "prompt", Json.array(Json.text("write"))));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<JsonObject> result = executor.submit(() -> c.finish(request)); assertTrue(c.permissionReady.await(3, TimeUnit.SECONDS));
                c.send(Json.obj("jsonrpc", "2.0", "method", "session/cancel", "params", Json.obj("sessionId", id)));
                assertEquals("cancelled", Json.str(result.get(3, TimeUnit.SECONDS), "stopReason")); assertFalse(Files.exists(cwd.resolve("file 中文.txt")));
                c.holdPermissions = false; mode = "text"; assertEquals("end_turn", Json.str(c.prompt(id, "next"), "stopReason"));
            } finally { executor.shutdownNow(); }
        }
    }
}
