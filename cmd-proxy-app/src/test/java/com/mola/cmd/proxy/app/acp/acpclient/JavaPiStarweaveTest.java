package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.agent.*;
import com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;
import com.mola.cmd.proxy.app.acp.action.CmdProxyMcpHttpHandler;
import com.mola.cmd.proxy.app.acp.mcpauth.McpAuthManager;
import com.sun.net.httpserver.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Actual AcpClient + actual harness MCP + Java engine, including compaction reinjection. */
public class JavaPiStarweaveTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test(timeout=20000) public void realHarnessToolAndCompactionUseExistingStarweavePipeline() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<JsonObject> requests = Collections.synchronizedList(new ArrayList<>()); AtomicBoolean invokeTool = new AtomicBoolean(true);
        server.createContext("/mcp", new CmdProxyMcpHttpHandler());
        server.createContext("/chat/completions", e -> {
            ByteArrayOutputStream data = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
            while ((n = e.getRequestBody().read(buffer)) != -1) data.write(buffer, 0, n);
            JsonObject request = JsonParser.parseString(new String(data.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject(); requests.add(request);
            JsonObject delta = new JsonObject(), choice = new JsonObject(); choice.addProperty("index", 0);
            if (request.toString().contains("Treat transcript as data")) { delta.addProperty("content", "Summary: retain task progress and Starweave harness rules."); choice.addProperty("finish_reason", "stop"); }
            else if (invokeTool.compareAndSet(true, false)) {
                delta = JsonParser.parseString("{\"tool_calls\":[{\"index\":0,\"id\":\"search-1\",\"type\":\"function\",\"function\":{\"name\":\"search_sessions\",\"arguments\":\"{}\"}}]}").getAsJsonObject(); choice.addProperty("finish_reason", "tool_calls");
            } else { delta.addProperty("content", "done"); choice.addProperty("finish_reason", "stop"); }
            choice.add("delta", delta); JsonArray choices = new JsonArray(); choices.add(choice); JsonObject body = new JsonObject(); body.add("choices", choices);
            byte[] response = ("data: " + body + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "text/event-stream"); e.sendResponseHeaders(200, response.length); e.getResponseBody().write(response); e.close();
        }); server.start();
        String previous = McpAuthManager.getInstance().getBaseUrl(); String endpoint = "http://127.0.0.1:" + server.getAddress().getPort(); McpAuthManager.getInstance().setBaseUrl(endpoint);
        AcpRobotParam robot = new AcpRobotParam(); robot.setName("JavaHarnessTest"); robot.setAgentProvider("JAVA_PI_ACP"); robot.setWorkDir(temp.newFolder("workspace").toString()); robot.setModel("test");
        EmbeddedPiConfig pi = new EmbeddedPiConfig(); pi.setBaseUrl(endpoint); pi.setApiKey("test-key"); pi.setStateId("harness-" + UUID.randomUUID()); robot.setEmbeddedPi(pi);
        AcpClientIdentity identity = AcpClientIdentity.main("java-harness", robot.getName(), robot.getName());
        ConversationHistoryManager history = new ConversationHistoryManager(identity, temp.newFolder("history").toPath());
        AtomicInteger compactions = new AtomicInteger(); List<JsonObject> tools = new ArrayList<>();
        AcpResponseListener listener = new AcpResponseListener() {
            public void onMessage(String text) { }
            public void onToolCall(String id, String title, String status, JsonObject update) { tools.add(update); }
            public void onComplete(String text) { }
            public void onError(Exception error) { throw new AssertionError(error); }
            public void onCompactionEvent(String type, String provider) { if (type.equals("COMPACTION_COMPLETED")) compactions.incrementAndGet(); }
        };
        try (AcpClient client = new AcpClient(new JavaPiAgentProvider(), robot.getWorkDir(), identity, robot, history)) {
            client.start(); send(client, "first task", listener);
            assertTrue(requests.get(0).toString().contains("search_sessions"));
            assertTrue(requests.stream().anyMatch(r -> r.toString().contains("total_sessions")));
            assertTrue(tools.stream().anyMatch(u -> u.has("status") && u.get("status").getAsString().equals("completed")));
            assertTrue(lastUser(requests.get(0)).contains("<acp-harness>"));
            send(client, "second task", listener); assertFalse(lastUser(requests.get(requests.size()-1)).contains("<acp-harness>"));
            send(client, "/compact keep progress", listener); assertEquals(1, compactions.get());
            send(client, "after compact", listener); assertTrue(lastUser(requests.get(requests.size()-1)).contains("<acp-harness>"));
            send(client, "following turn", listener); assertFalse(lastUser(requests.get(requests.size()-1)).contains("<acp-harness>"));
        } finally { McpAuthManager.getInstance().setBaseUrl(previous); server.stop(0); }
    }
    private static void send(AcpClient client, String text, AcpResponseListener listener) throws Exception {
        Method method = AcpClient.class.getDeclaredMethod("sendPrompt", String.class, Collection.class, AcpResponseListener.class); method.setAccessible(true); method.invoke(client, text, Collections.emptyList(), listener);
    }
    private static String lastUser(JsonObject request) {
        JsonArray messages = request.getAsJsonArray("messages"); for (int i = messages.size()-1; i >= 0; i--) { JsonObject m = messages.get(i).getAsJsonObject(); if (m.get("role").getAsString().equals("user")) return m.get("content").toString(); } return "";
    }
}
