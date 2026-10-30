package com.mola.cmd.proxy.app.acp.remote;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.channel.ChannelConfigFileStore;
import com.mola.cmd.proxy.app.acp.memory.MemoryFileStore;
import com.mola.starweave.agent.transport.AcpTransport;
import com.mola.starweave.sdk.*;
import com.mola.starweave.sdk.sync.*;
import com.sun.net.httpserver.HttpServer;
import okhttp3.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real discovery -> WebSocket -> shared remote manager -> multiplexed ACP transport. */
public class RemoteBrowserIntegrationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private RemoteAcpManager manager;
    private HttpServer http;
    private final OkHttpClient client = new OkHttpClient();
    private Path config;
    private String base, wsUrl;
    private Peer peer;
    @Before public void start() throws Exception {
        config = temp.newFile("config.json").toPath();
        Files.write(config, "{\"robots\":[],\"remoteAcp\":{\"port\":0,\"browserPort\":0,\"bindHost\":\"127.0.0.1\",\"accessToken\":\"browser-test\"}}".getBytes(StandardCharsets.UTF_8));
        manager = new RemoteAcpManager(config, (a,b) -> {});
        http = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); http.createContext("/api/remote-acp/discover", manager::handleDiscovery); http.start();
        base = "http://127.0.0.1:" + http.getAddress().getPort();
        HttpURLConnection request = (HttpURLConnection)new URL(base + "/api/remote-acp/discover?transport=websocket").openConnection();
        request.setRequestProperty("Authorization", "Bearer browser-test");
        JsonObject info;
        try (Reader in = new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8)) { info = JsonParser.parseReader(in).getAsJsonObject(); }
        finally { request.disconnect(); }
        wsUrl = "ws://127.0.0.1:" + info.get("webSocketPort").getAsInt() + info.get("webSocketPath").getAsString();
        peer = new Peer("browser-identity-stable-123"); peer.connect(); peer.take("registered"); peer.take("config");
    }
    @After public void stop() {
        if (peer != null && peer.socket != null) peer.socket.cancel();
        if (manager != null) manager.close(); if (http != null) http.stop(0);
        client.dispatcher().executorService().shutdownNow(); client.connectionPool().evictAll();
    }
    private AcpRobotParam robot() throws IOException { return JSON.parseObject(new String(Files.readAllBytes(config), StandardCharsets.UTF_8)).getJSONArray("robots").getJSONObject(0).toJavaObject(AcpRobotParam.class); }
    @Test(timeout=15000) public void browserRegistrationConfigurationAcpAndDisconnect() throws Exception {
        assertTrue(RemoteAcpManager.online("browser-test"));
        JSONObject saved = JSON.parseObject(new String(Files.readAllBytes(config), StandardCharsets.UTF_8)).getJSONArray("robots").getJSONObject(0);
        assertEquals("browser", saved.getString("remoteHostKind")); assertEquals("BROWSER", saved.getString("remotePlatform"));
        ChannelConfigFileStore.updateConfig(config, root -> { JSONObject r = root.getJSONArray("robots").getJSONObject(0); r.put("model", "test-model"); r.put("workDir", temp.getRoot().toPath().toString()); r.getJSONObject("embeddedPi").put("apiKey", "secret"); });
        JsonObject updated = peer.take("config"); assertEquals("test-model", updated.getAsJsonObject("config").get("model").getAsString());
        AcpTransport transport = manager.open(robot());
        JsonObject message = JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session/new\",\"params\":{\"cwd\":\"/central/wrong\",\"mcpServers\":[]}}").getAsJsonObject();
        transport.output().write((message + "\n").getBytes(StandardCharsets.UTF_8));
        JsonObject forwarded = peer.take("acp"), params = forwarded.getAsJsonObject("message").getAsJsonObject("params");
        assertEquals(temp.getRoot().toPath().toString(), params.get("cwd").getAsString()); assertEquals("browser", params.get("_starweaveRole").getAsString());
        JsonObject response = Wire.frame("acp", Wire.str(forwarded,"runtimeId")); response.add("message", JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"sessionId\":\"browser-session\"}}")); peer.send(response);
        assertTrue(new BufferedReader(new InputStreamReader(transport.input(),StandardCharsets.UTF_8)).readLine().contains("browser-session"));
        peer.socket.cancel(); long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (transport.isAlive() && System.nanoTime() < end) Thread.sleep(10);
        assertFalse(transport.isAlive());
    }
    private JsonObject newToolSession(JsonArray servers) throws Exception {
        ChannelConfigFileStore.updateConfig(config, root -> {
            JSONObject r = root.getJSONArray("robots").getJSONObject(0); r.put("model", "test-model"); r.put("workDir", temp.getRoot().toString());
            r.getJSONObject("embeddedPi").put("apiKey", "secret");
        });
        AcpTransport transport = manager.open(robot());
        JsonObject message = JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session/new\",\"params\":{\"cwd\":\"/wrong\"}}").getAsJsonObject();
        message.getAsJsonObject("params").add("mcpServers", servers);
        transport.output().write((message + "\n").getBytes(StandardCharsets.UTF_8));
        JsonObject f = peer.take("acp"), result = new JsonObject();
        result.addProperty("runtimeId", Wire.str(f, "runtimeId"));
        result.addProperty("toolSession", Wire.str(f.getAsJsonObject("message").getAsJsonObject("params"), "_starweaveToolSession"));
        assertEquals(0, f.getAsJsonObject("message").getAsJsonObject("params").getAsJsonArray("mcpServers").size());
        return result;
    }
    private JsonObject tool(JsonObject session, String operation, String id, String name, JsonObject arguments) throws Exception {
        JsonObject request = session.deepCopy(); request.addProperty("type", "browser_tool"); request.addProperty("requestId", "request-" + java.util.UUID.randomUUID());
        request.addProperty("operation", operation); request.addProperty("callId", id); request.addProperty("name", name); request.add("arguments", arguments); peer.send(request);
        return peer.take("browser_tool_result");
    }
    @Test(timeout=20000) public void standardToolsUseConfiguredWorkspaceAndRealSkillMemoryPaths() throws Exception {
        JsonObject session = newToolSession(new JsonArray());
        Path skills = temp.getRoot().toPath().resolve(".agents/skills/orders");
        ResourceFiles.write(skills.resolve("SKILL.md"), "---\nname: orders\ndescription: Query orders\n---\nRead references/status.md".getBytes(StandardCharsets.UTF_8));
        ResourceFiles.write(skills.resolve("references/status.md"), "pending means waiting".getBytes(StandardCharsets.UTF_8));
        JsonObject prepared = tool(session, "prepare", "", "", new JsonObject()).getAsJsonObject("result");
        assertEquals(temp.getRoot().toString(), Wire.str(prepared, "cwd"));
        assertTrue(prepared.get("systemPrompt").getAsString().contains(skills.resolve("SKILL.md").toString()));
        assertEquals(4, prepared.getAsJsonArray("tools").size());
        JsonObject args = new JsonObject(); args.addProperty("path", "note.txt"); args.addProperty("content", "original");
        assertFalse(tool(session, "call", "write-1", "write", args).has("error"));
        args.remove("content"); args.addProperty("oldText", "original"); args.addProperty("newText", "edited");
        assertFalse(tool(session, "call", "edit-1", "edit", args).has("error"));
        assertEquals("edited", new String(Files.readAllBytes(temp.getRoot().toPath().resolve("note.txt")), StandardCharsets.UTF_8));
        assertTrue(tool(session, "call", "edit-1", "edit", args).has("error"));
        args = new JsonObject(); args.addProperty("path", skills.resolve("references/status.md").toString());
        assertTrue(tool(session, "call", "read-skill", "read", args).toString().contains("pending means waiting"));
        Path memory = Paths.get(new RemoteResources(robot()).memorySource()).resolve("memories/detail.md"); ResourceFiles.write(memory, "owned-memory".getBytes(StandardCharsets.UTF_8));
        args.addProperty("path", memory.toString()); assertTrue(tool(session, "call", "read-memory", "read", args).toString().contains("owned-memory"));
        args = new JsonObject(); args.addProperty("command", "pwd");
        assertTrue(tool(session, "call", "shell-1", "shell", args).toString().contains(temp.getRoot().toString()));
        JsonObject other = session.deepCopy(); other.addProperty("runtimeId", "another-runtime");
        assertTrue(tool(other, "call", "unauthorized", "shell", args).has("error"));
        assertFalse(tool(session, "close", "", "", new JsonObject()).has("error"));
        assertTrue(tool(session, "prepare", "", "", new JsonObject()).has("error"));
    }
    @Test(timeout=20000) public void stdioMcpIsDiscoveredAndInvokedOnCenter() throws Exception {
        JsonObject spec = new JsonObject(); spec.addProperty("name", "stdio"); spec.addProperty("command", Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        JsonArray argv = new JsonArray(); argv.add("-cp"); argv.add(System.getProperty("java.class.path")); argv.add(StdioFixture.class.getName()); spec.add("args", argv);
        JsonArray servers = new JsonArray(); servers.add(spec); JsonObject session = newToolSession(servers);
        assertEquals(5, tool(session, "prepare", "", "", new JsonObject()).getAsJsonObject("result").getAsJsonArray("tools").size());
        JsonObject args = new JsonObject(); args.addProperty("text", "host-stdio-ok");
        assertTrue(tool(session, "call", "mcp-1", "stdio_echo", args).toString().contains("host-stdio-ok"));
    }
    @Test(timeout=15000) public void cancellationStopsHostCommandAndNextCallStillWorks() throws Exception {
        JsonObject session = newToolSession(new JsonArray()); tool(session, "prepare", "", "", new JsonObject());
        JsonObject call = session.deepCopy(); call.addProperty("type", "browser_tool"); call.addProperty("operation", "call"); call.addProperty("requestId", "long-call"); call.addProperty("callId", "long-shell"); call.addProperty("name", "shell");
        JsonObject args = new JsonObject(); args.addProperty("command", "printf ready; sleep 30"); call.add("arguments", args); peer.send(call);
        peer.take("browser_tool_update");
        JsonObject cancel = session.deepCopy(); cancel.addProperty("type", "browser_tool"); cancel.addProperty("operation", "cancel"); cancel.addProperty("callId", "long-shell"); peer.send(cancel);
        assertTrue(peer.take("browser_tool_result").has("error"));
        args.addProperty("command", "printf still-ready"); assertTrue(tool(session, "call", "next-shell", "shell", args).toString().contains("still-ready"));
    }
    @Test public void browserWorkspaceIsEditableAndUsesOrdinarySkillAndMcpPaths() throws Exception {
        JSONObject before = JSON.parseObject(new String(Files.readAllBytes(config), StandardCharsets.UTF_8)); JSONObject next = JSON.parseObject(before.toJSONString());
        Path cwd = temp.newFolder("workspace").toPath(); next.getJSONArray("robots").getJSONObject(0).put("workDir", cwd.toString()); RemoteAcpManager.validateSave(next, before);
        AcpRobotParam browser = next.getJSONArray("robots").getJSONObject(0).toJavaObject(AcpRobotParam.class);
        assertEquals(cwd.toString(), browser.getWorkDir());
        com.mola.cmd.proxy.app.acp.acpclient.agent.RemoteStarcoreAgentProvider provider = new com.mola.cmd.proxy.app.acp.acpclient.agent.RemoteStarcoreAgentProvider();
        assertTrue(provider.getSkillPaths(cwd.toString(), browser).contains(cwd.resolve(".agents/skills")));
        assertTrue(provider.getMcpConfigPaths(cwd.toString(), browser).size() >= 1);
        next.getJSONArray("robots").getJSONObject(0).put("workDir", "relative/path");
        try { RemoteAcpManager.validateSave(next, before); fail("relative working directory accepted"); } catch (IllegalArgumentException expected) { }
    }
    public static class StdioFixture {
        public static void main(String[] args) throws Exception {
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)); PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true); String line;
            while ((line = input.readLine()) != null) {
                JsonObject request = JsonParser.parseString(line).getAsJsonObject(); if (!request.has("id")) continue;
                String method = Wire.str(request, "method"); JsonObject result = JsonParser.parseString(method.equals("initialize") ? "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}" : method.equals("tools/list") ? "{\"tools\":[{\"name\":\"stdio_echo\",\"inputSchema\":{\"type\":\"object\"}}]}" : "{\"content\":[{\"type\":\"text\",\"text\":\"host-stdio-ok\"}]}").getAsJsonObject();
                JsonObject reply = new JsonObject(); reply.addProperty("jsonrpc", "2.0"); reply.add("id", request.get("id")); reply.add("result", result); out.println(reply);
            }
        }
    }
    @Test(timeout=15000) public void backsUpBrowserSnapshotWithConflictAndPull() throws Exception {
        JsonObject snapshot=JsonParser.parseString("{\"version\":1,\"engine\":\"pi-browser-0.75.3\",\"sessionId\":\"session-one\",\"cwd\":\"/browser/browser-identity-stable-123\",\"messages\":[],\"history\":[]}").getAsJsonObject();
        byte[] bytes=snapshot.toString().getBytes(StandardCharsets.UTF_8);
        JsonObject request=Wire.frame("sessions_request",null);request.addProperty("requestId","upload");request.addProperty("sessionId","session-one");request.addProperty("base","");SessionSnapshots.content(request,bytes);peer.send(request);
        assertFalse(peer.take("sessions_response").has("error"));
        snapshot.addProperty("model","changed");request.remove("hash");request.remove("data");SessionSnapshots.content(request,snapshot.toString().getBytes(StandardCharsets.UTF_8));peer.send(request);
        assertTrue(peer.take("sessions_response").get("conflict").getAsBoolean());
        JsonObject pull=Wire.frame("sessions_request",null);pull.addProperty("requestId","pull");pull.addProperty("sessionId","session-one");pull.addProperty("pull",true);peer.send(pull);
        assertArrayEquals(bytes, SessionSnapshots.decode(peer.take("sessions_response")));
        assertTrue(Files.exists(RemoteResources.skillRoot(robot()).getParent().resolve("browser-sessions/session-one.json")));
        assertFalse(Files.exists(RemoteResources.skillRoot(robot()).getParent().resolve("sessions/session-one.json")));
    }
    @Test(timeout=15000) public void rejectsSameNameDifferentIdentityAndWebsiteOrigins() throws Exception {
        Peer other=new Peer("different-browser-identity-123");other.connect();
        assertNotNull(other.ended.poll(5,TimeUnit.SECONDS)); assertTrue(RemoteAcpManager.online("browser-test"));
        CountDownLatch rejected=new CountDownLatch(1);
        WebSocket bad=client.newWebSocket(new Request.Builder().url(wsUrl).header("Origin","https://untrusted.example").build(),new WebSocketListener(){ public void onFailure(WebSocket ws,Throwable t,Response response){ if(response!=null&&response.code()==403)rejected.countDown(); }});
        assertTrue(rejected.await(5,TimeUnit.SECONDS));bad.cancel();
    }
    private class Peer extends WebSocketListener {
        final String identity; WebSocket socket;
        final BlockingQueue<JsonObject> messages=new LinkedBlockingQueue<>(); final BlockingQueue<Boolean> ended=new LinkedBlockingQueue<>();
        Peer(String identity){this.identity=identity;}
        void connect(){socket=client.newWebSocket(new Request.Builder().url(wsUrl).header("Origin","chrome-extension://test").build(),this);}
        public void onOpen(WebSocket ws,Response response){socket=ws;JsonObject hello=Wire.frame("register",null);hello.addProperty("protocolVersion",1);hello.addProperty("name","browser-test");hello.addProperty("identity",identity);hello.addProperty("workspace","/browser/"+identity);hello.addProperty("platform","BROWSER");hello.addProperty("starweaveUrl",base);hello.addProperty("accessToken","browser-test");hello.addProperty("sessionSyncVersion",1);send(hello);}
        public void onMessage(WebSocket ws,String text){JsonObject frame=JsonParser.parseString(text).getAsJsonObject();String type=Wire.str(frame,"type");
            if(type.equals("config")){JsonObject ack=Wire.frame("config_ack",null);ack.add("version",frame.get("version"));ack.addProperty("ready",frame.getAsJsonObject("config").has("model")&&!Wire.str(frame.getAsJsonObject("config"),"model").isEmpty());send(ack);}
            if(type.equals("open")){send(Wire.frame("opened",Wire.str(frame,"runtimeId")));return;}
            messages.offer(frame);
        }
        public void onFailure(WebSocket ws,Throwable t,Response response){ended.offer(true);}
        public void onClosing(WebSocket ws,int code,String reason){ws.close(code,reason);ended.offer(true);}
        public void onClosed(WebSocket ws,int code,String reason){ended.offer(true);}
        void send(JsonObject frame){assertTrue(socket.send(frame.toString()));}
        JsonObject take(String type)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(System.nanoTime()<end){JsonObject f=messages.poll(100,TimeUnit.MILLISECONDS);if(f!=null&&type.equals(Wire.str(f,"type")))return f;}throw new AssertionError("Missing frame: "+type);}
    }
}
