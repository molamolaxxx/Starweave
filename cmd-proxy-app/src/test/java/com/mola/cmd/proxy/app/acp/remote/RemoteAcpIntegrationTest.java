package com.mola.cmd.proxy.app.acp.remote;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.agent.RemoteStarcoreAgentProvider;
import com.mola.cmd.proxy.app.acp.channel.ChannelConfigFileStore;
import com.mola.cmd.proxy.app.acp.mcpauth.McpAuthManager;
import com.mola.starweave.sdk.*;
import com.mola.starweave.agent.transport.AcpTransport;
import com.sun.net.httpserver.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Real SDK -> TCP -> existing ACP client, with independent HTTP model and MCP endpoints. */
public class RemoteAcpIntegrationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private HttpServer http;
    private ExecutorService httpThreads;
    private RemoteAcpManager manager;
    private StarcoreClient sdk;
    private Path config, workspace, data;
    private String base, previousMcp, previousHome;
    private final AtomicBoolean callMcp = new AtomicBoolean(), callJvm = new AtomicBoolean();
    private final AtomicInteger jvmCalls = new AtomicInteger();
    private final AtomicInteger actualToolCalls = new AtomicInteger();
    private final AtomicInteger refreshes = new AtomicInteger(), mcpCalls = new AtomicInteger();
    @Before public void setup() throws Exception {
        previousHome = System.getProperty("cmd.proxy.home"); System.setProperty("cmd.proxy.home", temp.newFolder("home").toString());
        config = temp.newFile("config.json").toPath(); workspace = temp.newFolder("workspace").toPath(); data = temp.newFolder("sdk").toPath();
        Files.write(config, "{\"robots\":[],\"remoteAcp\":{\"port\":0}}".getBytes(StandardCharsets.UTF_8));
        manager = new RemoteAcpManager(config, (oldName, name) -> refreshes.incrementAndGet());
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpThreads = Executors.newCachedThreadPool(); http.setExecutor(httpThreads);
        http.createContext("/api/remote-acp/discover", manager::handleDiscovery);
        http.createContext("/api/remote-acp/mcp/", manager::handleMcp);
        http.createContext("/mcp", new com.mola.cmd.proxy.app.acp.action.CmdProxyMcpHttpHandler());
        http.createContext("/chat/completions", e -> {
            JsonObject request = JsonParser.parseReader(new InputStreamReader(e.getRequestBody(), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("remote-model", request.get("model").getAsString());
            assertEquals("Bearer test-key", e.getRequestHeaders().getFirst("Authorization"));
            JsonObject message = new JsonObject(); message.addProperty("role", "assistant"); message.addProperty("content", "remote response");
            JsonObject choice = new JsonObject(); choice.add("message", message); choice.addProperty("finish_reason", "stop");
            if (callMcp.compareAndSet(true, false)) {
                message.remove("content"); JsonObject tool = new JsonObject(); tool.addProperty("id", "echo-1"); tool.addProperty("type", "function");
                JsonObject function = new JsonObject(); function.addProperty("name", "remote_echo"); function.addProperty("arguments", "{}"); tool.add("function", function);
                JsonArray calls = new JsonArray(); calls.add(tool); message.add("tool_calls", calls); choice.addProperty("finish_reason", "tool_calls");
            }
            if (callJvm.compareAndSet(true, false)) {
                message.remove("content"); JsonObject tool = new JsonObject(); tool.addProperty("id", "jvm-1"); tool.addProperty("type", "function");
                JsonObject function = new JsonObject(); function.addProperty("name", "jvm_exec"); function.addProperty("arguments", "{\"script\":\"return counter.incrementAndGet()\"}"); tool.add("function", function);
                JsonArray calls = new JsonArray(); calls.add(tool); message.add("tool_calls", calls); choice.addProperty("finish_reason", "tool_calls");
            }
            JsonObject result = new JsonObject(); JsonArray choices = new JsonArray(); choices.add(choice); result.add("choices", choices); respond(e, result);
        });
        http.createContext("/test-mcp", e -> {
            if (e.getRequestMethod().equals("DELETE")) { e.sendResponseHeaders(204, -1); e.close(); return; }
            JsonObject request = JsonParser.parseReader(new InputStreamReader(e.getRequestBody(), StandardCharsets.UTF_8)).getAsJsonObject();
            mcpCalls.incrementAndGet();
            if (!request.has("id")) { e.sendResponseHeaders(202, -1); e.close(); return; }
            JsonObject result = new JsonObject();
            if (Wire.str(request, "method").equals("initialize")) { result.addProperty("protocolVersion", "2025-06-18"); result.add("capabilities", new JsonObject()); JsonObject info = new JsonObject(); info.addProperty("name", "test"); info.addProperty("version", "1"); result.add("serverInfo", info); }
            else if (Wire.str(request, "method").equals("tools/list")) {
                JsonArray tools = new JsonArray(); JsonObject tool = new JsonObject(); tool.addProperty("name", "remote_echo"); tool.addProperty("description", "echo"); JsonObject schema = new JsonObject(); schema.addProperty("type", "object"); schema.add("properties", new JsonObject()); tool.add("inputSchema", schema); tools.add(tool); result.add("tools", tools);
            } else if (Wire.str(request, "method").equals("tools/call")) {
                actualToolCalls.incrementAndGet(); JsonArray content = new JsonArray(); JsonObject text = new JsonObject(); text.addProperty("type", "text"); text.addProperty("text", "MCP executed"); content.add(text); result.add("content", content);
            }
            JsonObject response = new JsonObject(); response.addProperty("jsonrpc", "2.0"); response.add("id", request.get("id")); response.add("result", result); respond(e, response);
        });
        http.start(); base = "http://127.0.0.1:" + http.getAddress().getPort();
        previousMcp = McpAuthManager.getInstance().getBaseUrl(); McpAuthManager.getInstance().setBaseUrl(base);
        sdk = StarcoreClient.builder().starweaveUrl(base).name("test-remote").workspace(workspace).dataDirectory(data).allowFileWrites(true).allowShell(true).allowJvmExecution(true).jvmBindings(java.util.Collections.singletonMap("counter", jvmCalls)).start();
        until(() -> RemoteAcpManager.online("test-remote"));
    }
    private void configure() throws Exception {
        ChannelConfigFileStore.updateConfig(config, root -> { JSONObject r = root.getJSONArray("robots").getJSONObject(0); r.put("model", "remote-model"); r.getJSONObject("embeddedPi").put("apiKey", "test-key"); r.getJSONObject("embeddedPi").put("baseUrl", base); });
        assertTrue(sdk.awaitReady(10, TimeUnit.SECONDS));
    }
    private AcpRobotParam robot() throws Exception { return JSON.parseObject(new String(Files.readAllBytes(config), StandardCharsets.UTF_8)).getJSONArray("robots").getJSONObject(0).toJavaObject(AcpRobotParam.class); }
    @After public void stop() { if (sdk != null) sdk.close(); if (manager != null) manager.close(); if (http != null) http.stop(0); if (httpThreads != null) httpThreads.shutdownNow(); McpAuthManager.getInstance().setBaseUrl(previousMcp); if (previousHome == null) System.clearProperty("cmd.proxy.home"); else System.setProperty("cmd.proxy.home", previousHome); }

    @Test(timeout=25000) public void registersWithoutModelThenExistingClientChatsAndRuntimeClosePreservesConnection() throws Exception {
        until(() -> sdk.getState() == StarcoreClient.State.CONNECTED);
        configure();
        try (Client client = new Client(robot(), true)) { client.start(); assertEquals("remote response", client.prompt()); assertEquals(AbstractAcpClient.State.READY, client.getState()); }
        assertTrue(RemoteAcpManager.online("test-remote")); assertTrue(mcpCalls.get() >= 3);
        try (Client client = new Client(robot(), false)) { client.start(); assertEquals("remote response", client.prompt()); }
        assertEquals(1, JSON.parseObject(new String(Files.readAllBytes(config), StandardCharsets.UTF_8)).getJSONArray("robots").size());
    }
    @Test(timeout=25000) public void executesGroovyInSdkHostThroughRemoteAcp() throws Exception {
        configure(); callJvm.set(true);
        try (Client client = new Client(robot(), true)) {
            client.start(); assertEquals("remote response", client.prompt()); assertEquals(1, jvmCalls.get());
        }
    }
    @Test(timeout=25000) public void multiplexesIsolatedSessionsAndDisconnectFailsAllRuntimes() throws Exception {
        configure();
        AcpTransport first = manager.open(robot()), second = manager.open(robot());
        try {
            JsonObject init = new JsonObject(); init.addProperty("jsonrpc", "2.0"); init.addProperty("id", 1); init.addProperty("method", "initialize"); init.add("params", new JsonObject());
            first.output().write((init + "\n").getBytes(StandardCharsets.UTF_8));
            assertTrue(new BufferedReader(new InputStreamReader(first.input(), StandardCharsets.UTF_8)).readLine().contains("protocolVersion"));
            first.close(); assertTrue(second.isAlive()); assertTrue(RemoteAcpManager.online("test-remote"));
            sdk.close(); until(() -> !second.isAlive()); until(() -> !RemoteAcpManager.online("test-remote"));
        } finally { first.close(); second.close(); }
    }
    @Test(timeout=25000) public void onlineDeletionRejectedAndIdentityIsHiddenAndPreserved() throws Exception {
        JSONObject previous = JSON.parseObject(new String(Files.readAllBytes(config), StandardCharsets.UTF_8));
        JSONObject submitted = JSON.parseObject(previous.toJSONString()); submitted.put("robots", new JSONArray());
        try { RemoteAcpManager.validateSave(submitted, previous); fail("online deletion accepted"); } catch (IllegalArgumentException expected) { }
        JSONObject projected = JSON.parseObject(previous.toJSONString()); manager.project(projected);
        assertFalse(projected.toJSONString().contains("remoteIdentityHash"));
        projected.getJSONArray("robots").getJSONObject(0).put("enabled", false);
        RemoteAcpManager.validateSave(projected, previous);
        assertTrue(projected.getJSONArray("robots").getJSONObject(0).getBooleanValue("enabled"));
        assertEquals(previous.getJSONArray("robots").getJSONObject(0).getString("remoteIdentityHash"), projected.getJSONArray("robots").getJSONObject(0).getString("remoteIdentityHash"));
        sdk.close(); until(() -> !RemoteAcpManager.online("test-remote"));
        RemoteAcpManager.validateSave(submitted, previous);
    }
    @Test(timeout=25000) public void reconnectKeepsConfigurationAndRejectsDifferentIdentity() throws Exception {
        configure(); sdk.close(); until(() -> !RemoteAcpManager.online("test-remote"));
        sdk = StarcoreClient.builder().starweaveUrl(base).name("test-remote").workspace(workspace).dataDirectory(data).start();
        assertTrue(sdk.awaitReady(10, TimeUnit.SECONDS)); assertEquals("remote-model", robot().getModel());
        try (StarcoreClient intruder = StarcoreClient.builder().starweaveUrl(base).name("test-remote").workspace(workspace).dataDirectory(temp.newFolder("intruder").toPath()).start()) {
            assertFalse(intruder.awaitReady(2, TimeUnit.SECONDS)); assertTrue(RemoteAcpManager.online("test-remote")); assertEquals(StarcoreClient.State.READY, sdk.getState());
        }
    }
    @Test(timeout=45000) public void missingHeartbeatExpiresLeaseAndLateTrafficCannotReviveIt() throws Exception {
        sdk.close(); until(() -> !RemoteAcpManager.online("test-remote"));
        JsonObject discovery;
        try (Reader reader = new InputStreamReader(new URL(base + "/api/remote-acp/discover").openStream(), StandardCharsets.UTF_8)) { discovery = JsonParser.parseReader(reader).getAsJsonObject(); }
        try (Socket socket = new Socket("127.0.0.1", discovery.get("port").getAsInt())) {
            socket.setSoTimeout(5000); JsonObject hello = Wire.frame("register", null); hello.addProperty("protocolVersion", Wire.VERSION); hello.addProperty("name", "test-remote");
            hello.addProperty("identity", new String(Files.readAllBytes(data.resolve("identity")), StandardCharsets.UTF_8)); hello.addProperty("workspace", workspace.toString()); hello.addProperty("starweaveUrl", base);
            Wire.write(socket.getOutputStream(), hello); assertEquals("registered", Wire.str(Wire.read(socket.getInputStream()), "type"));
            assertEquals("config", Wire.str(Wire.read(socket.getInputStream()), "type")); assertTrue(RemoteAcpManager.online("test-remote"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
            while (RemoteAcpManager.online("test-remote") && System.nanoTime() < deadline) Thread.sleep(50);
            assertFalse("lease did not expire", RemoteAcpManager.online("test-remote"));
            try { Wire.write(socket.getOutputStream(), Wire.frame("ping", null)); } catch (IOException expected) { }
            until(() -> !robotUnchecked().isEnabled()); assertFalse(RemoteAcpManager.online("test-remote"));
        }
    }
    private AcpRobotParam robotUnchecked() { try { return robot(); } catch (Exception e) { throw new AssertionError(e); } }

    @Test(timeout=25000) public void invokesMcpToolThroughRemoteProxyAndStagesFiles() throws Exception {
        configure(); callMcp.set(true);
        try (Client client = new Client(robot(), true)) { client.start(); assertEquals("remote response", client.prompt()); assertEquals(1, actualToolCalls.get()); }
        Path attachment = temp.newFile("attachment.txt").toPath(); Files.write(attachment, "file-content".getBytes(StandardCharsets.UTF_8));
        try (AcpTransport transport = manager.open(robot())) {
            String remote = transport.stageFiles(java.util.Collections.singleton(attachment.toString())).get(attachment.toString());
            assertNotEquals(attachment.toString(), remote); assertTrue(Paths.get(remote).startsWith(data.resolve("uploads")));
            assertEquals("file-content", new String(Files.readAllBytes(Paths.get(remote)), StandardCharsets.UTF_8));
        }
    }
    @Test(timeout=30000) public void normalAndTeamAcpClientsUseIndependentRemoteInstances() throws Exception {
        configure();
        AcpRobotParam configured = robot();
        com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity teamIdentity = com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity.team(
                "team-client", "team-transport", "team/test/member", "owner", "test-team", "member", configured.getName());
        try (com.mola.cmd.proxy.app.acp.acpclient.AcpClient team = isolatedTeamClient(configured, teamIdentity);
             Client normal = new Client(configured, false)) {
            team.start(); normal.start(); assertNotEquals(team.getSessionId(), normal.getSessionId());
            AtomicReference<String> answer = new AtomicReference<>();
            com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener listener = new com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener() {
                public void onMessage(String text) { }
                public void onToolCall(String id, String title, String status, JsonObject update) { }
                public void onComplete(String text) { answer.set(text); }
                public void onError(Exception error) { throw new AssertionError(error); }
            };
            java.lang.reflect.Method send = com.mola.cmd.proxy.app.acp.acpclient.AcpClient.class.getDeclaredMethod("sendPrompt", String.class, java.util.Collection.class, com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener.class);
            send.setAccessible(true); send.invoke(team, "team task", java.util.Collections.emptyList(), listener);
            assertEquals("remote response", answer.get()); assertEquals("remote response", normal.prompt());
            team.close(); assertEquals("remote response", normal.prompt()); assertTrue(RemoteAcpManager.online(configured.getName()));
        }
    }
    private com.mola.cmd.proxy.app.acp.acpclient.AcpClient isolatedTeamClient(AcpRobotParam robot,
            com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity identity) throws Exception {
        java.lang.reflect.Constructor<com.mola.cmd.proxy.app.acp.acpclient.AcpClient> constructor = com.mola.cmd.proxy.app.acp.acpclient.AcpClient.class.getDeclaredConstructor(
                com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider.class, String.class,
                com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity.class, AcpRobotParam.class,
                com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager.class);
        constructor.setAccessible(true);
        com.mola.cmd.proxy.app.acp.acpclient.AcpClient client = constructor.newInstance(new RemoteStarcoreAgentProvider(), robot.getWorkDir(), identity, robot,
                new com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager(identity, temp.newFolder("team-history").toPath()));
        // CmdProxyHome is cached by earlier suites; never load developer MCPs into this fixture.
        java.lang.reflect.Field paths = com.mola.cmd.proxy.app.acp.acpclient.AcpClient.class.getDeclaredField("mcpConfigPaths"); paths.setAccessible(true); paths.set(client, java.util.Collections.emptyList());
        return client;
    }
    private static void respond(HttpExchange e, JsonObject o) throws IOException { byte[] b = o.toString().getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type", "application/json"); e.sendResponseHeaders(200, b.length); e.getResponseBody().write(b); e.close(); }
    private static void until(java.util.function.BooleanSupplier test) throws Exception { long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); while (!test.getAsBoolean()) { if (System.nanoTime() > end) fail("condition timed out"); Thread.sleep(20); } }
    private static final class Client extends AbstractAcpClient {
        private final boolean mcp;
        Client(AcpRobotParam robot, boolean mcp) { super(new RemoteStarcoreAgentProvider(), "/not/a/local/workspace", "remote-test", robot); this.mcp = mcp; }
        protected void createSession() throws IOException {
            JsonObject params = new JsonObject(); params.addProperty("cwd", workspacePath); JsonArray servers = new JsonArray();
            if (mcp) { JsonObject server = new JsonObject(); server.addProperty("name", "harness"); server.addProperty("type", "http"); server.addProperty("url", McpAuthManager.getInstance().getBaseUrl() + "/test-mcp"); servers.add(server); }
            params.add("mcpServers", servers); sessionId = sendRequest("session/new", params).getAsJsonObject("result").get("sessionId").getAsString();
        }
        String prompt() throws IOException { return doSendPromptSync("hello", "test"); }
    }
}
