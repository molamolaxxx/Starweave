package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager;
import com.mola.cmd.proxy.app.acp.action.ActionRuntimeRegistry;
import com.mola.cmd.proxy.app.acp.action.CmdProxyMcpHttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static org.junit.Assert.*;

public class AcpClientSessionQueryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void authenticatedHttpCallsReachClientHistoryAndCloseRevokesAccess() throws Exception {
        Path root = temporary.newFolder("session").toPath();
        AcpClientIdentity identity = AcpClientIdentity.main("session-query-test", "QueryAgent", "QueryAgent");
        ConversationHistoryManager history = new ConversationHistoryManager(identity, root);
        history.addUserMessage("检索测试"); history.addAssistantMessage("可读取的回复"); history.flushTurn("s1");
        AcpClient client = new AcpClient(new TestProvider(), temporary.getRoot().getAbsolutePath(), identity,
                new AcpRobotParam(), history);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", new CmdProxyMcpHttpHandler()); server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        String auth = client.getAuthSessionId();
        try {
            client.setSessionId("s1");
            assertTrue(client.availableActionTools().contains("search_sessions"));
            assertTrue(client.availableActionTools().contains("read_session_history"));
            assertTrue(client.availableActionTools().contains("read_session_contexts"));
            JsonObject search = call(url, auth, "search_sessions", args("keyword", "检索"));
            String ref = search.getAsJsonArray("sessions").get(0).getAsJsonObject().get("session_ref").getAsString();
            JsonObject read = call(url, auth, "read_session_history", args("session_ref", ref));
            assertEquals(2, read.getAsJsonArray("messages").size());
            assertEquals("可读取的回复", read.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString());
            assertFalse(read.toString().contains(root.toString()));
            JsonObject item = args("session_ref", ref); item.addProperty("message_id", read.getAsJsonArray("messages")
                    .get(0).getAsJsonObject().get("message_id").getAsString());
            JsonArray items = new JsonArray(); items.add(item);
            JsonObject batch = new JsonObject(); batch.add("items", items);
            assertEquals(2, call(url, auth, "read_session_contexts", batch).getAsJsonArray("contexts")
                    .get(0).getAsJsonObject().getAsJsonArray("messages").size());
        } finally {
            client.close(); server.stop(0);
        }
        try { ActionRuntimeRegistry.getInstance().execute(auth, "search_sessions", new JsonObject()); fail(); }
        catch (IllegalStateException expected) { assertEquals("AUTH_SESSION_NOT_FOUND", expected.getMessage()); }
    }

    private static JsonObject call(String url, String auth, String tool, JsonObject args) throws Exception {
        JsonObject request = new JsonObject(); request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 1); request.addProperty("method", "tools/call");
        JsonObject params = new JsonObject(); params.addProperty("name", tool); params.add("arguments", args); request.add("params", params);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(3000); connection.setReadTimeout(3000); connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty(CmdProxyMcpHttpHandler.AUTH_SESSION_HEADER, auth); connection.setDoOutput(true);
        try {
            try (OutputStream out = connection.getOutputStream()) { out.write(request.toString().getBytes(StandardCharsets.UTF_8)); }
            assertEquals(200, connection.getResponseCode());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = in.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            }
            JsonObject result = JsonParser.parseString(new String(bytes.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("result");
            assertFalse(result.get("isError").getAsBoolean());
            return JsonParser.parseString(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject();
        } finally { connection.disconnect(); }
    }
    private static JsonObject args(String key, String value) { JsonObject result = new JsonObject(); result.addProperty(key, value); return result; }
    private static final class TestProvider implements AgentProvider {
        public String getName() { return "session-query-test"; }
        public String getCommand() { return "unused"; }
        public String[] getArgs() { return new String[0]; }
        public List<Path> getMcpConfigPaths(String workspace) { return Collections.emptyList(); }
    }
}
