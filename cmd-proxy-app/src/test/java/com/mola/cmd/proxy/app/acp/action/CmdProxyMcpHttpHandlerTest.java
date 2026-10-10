package com.mola.cmd.proxy.app.acp.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.junit.Assert.*;

public class CmdProxyMcpHttpHandlerTest {
    private HttpServer server;
    private String endpoint;

    @Before
    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(CmdProxyMcpHttpHandler.PATH, new CmdProxyMcpHttpHandler());
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort()
                + CmdProxyMcpHttpHandler.PATH;
    }

    @After
    public void stop() {
        if (server != null) server.stop(0);
        ActionRuntimeRegistry.getInstance().unregister("test-session");
        ActionRuntimeRegistry.getInstance().unregister("list-session");
    }

    @Test
    public void advertisesMinimalSessionQuerySchemasAndPreservesStructuredErrors() throws Exception {
        JsonObject search = findTool(CmdProxyMcpHttpHandler.tools(), "search_sessions").getAsJsonObject("inputSchema");
        assertEquals(9, search.getAsJsonObject("properties").size());
        assertEquals("all", search.getAsJsonObject("properties").getAsJsonObject("scope").get("default").getAsString());
        JsonObject days = search.getAsJsonObject("properties").getAsJsonObject("days");
        assertEquals("integer", days.get("type").getAsString());
        assertEquals(7, days.get("default").getAsInt());
        assertEquals(0, days.get("minimum").getAsInt());
        assertEquals(50, search.getAsJsonObject("properties").getAsJsonObject("limit").get("default").getAsInt());
        assertEquals(0, search.getAsJsonArray("required").size());
        JsonObject read = findTool(CmdProxyMcpHttpHandler.tools(), "read_session_history").getAsJsonObject("inputSchema");
        assertEquals(1, read.getAsJsonObject("properties").size());
        assertEquals("session_refs", read.getAsJsonArray("required").get(0).getAsString());
        assertEquals("array", read.getAsJsonObject("properties").getAsJsonObject("session_refs").get("type").getAsString());
        assertFalse(CmdProxyMcpHttpHandler.tools().toString().contains("read_session_contexts"));
        ActionRuntimeRegistry.getInstance().register("test-session", (name, arguments) -> {
            throw com.mola.cmd.proxy.app.acp.sessionquery.ConversationQueryService.failure("SESSION_NOT_FOUND", "会话不存在");
        }, () -> java.util.Collections.singleton("read_session_history"));
        JsonObject response = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"read_session_history\",\"arguments\":{\"session_refs\":[\"missing\"]}}}",
                "application/json", "test-session");
        JsonObject result = response.getAsJsonObject("result");
        assertTrue(result.get("isError").getAsBoolean());
        JsonObject body = JsonParser.parseString(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject();
        assertEquals("SESSION_NOT_FOUND", body.getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    public void exposesObservationActionAsIndependentChannelField() {
        JsonObject tool = findTool(CmdProxyMcpHttpHandler.tools(), "manage_observation_channels");
        JsonObject properties = tool.getAsJsonObject("inputSchema").getAsJsonObject("properties");
        assertEquals("string", properties.getAsJsonObject("eventAction").get("type").getAsString());
        String description = properties.getAsJsonObject("eventAction").get("description").getAsString();
        assertTrue(description.contains("create 必填"));
        assertTrue(description.contains("新会话"));
        assertTrue(description.contains("快照"));
        assertTrue(properties.has("action"));
    }

    @Test
    public void exposesAcpHarnessServerIdentity() throws Exception {
        JsonObject response = post("{\"jsonrpc\":\"2.0\",\"id\":0,"
                        + "\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2025-06-18\"}}",
                "application/json", null);

        assertEquals("acp-harness-runtime", response.getAsJsonObject("result")
                .getAsJsonObject("serverInfo").get("name").getAsString());
    }

    @Test
    public void listsOnlyToolsAvailableToAuthSession() throws Exception {
        ActionRuntimeRegistry.getInstance().register("list-session", (name, arguments) -> "ok",
                () -> new LinkedHashSet<>(Arrays.asList("schedule_task", "manage_schedule")));
        JsonObject response = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
                "application/json", "list-session");
        JsonArray tools = response.getAsJsonObject("result").getAsJsonArray("tools");
        assertEquals(2, tools.size());
        assertEquals("schedule_task", tools.get(0).getAsJsonObject().get("name").getAsString());
        assertTrue(tools.get(0).getAsJsonObject().has("inputSchema"));
        String groupDescription = tools.get(0).getAsJsonObject()
                .getAsJsonObject("inputSchema").getAsJsonObject("properties")
                .getAsJsonObject("groupName").get("description").getAsString();
        assertTrue(groupDescription.contains("默认省略"));
        assertTrue(groupDescription.contains("daily-{yyyyMMdd}"));
    }

    @Test
    public void exposesDetailedToolContractsWithoutModelVisibleDepth() {
        JsonArray tools = CmdProxyMcpHttpHandler.tools();

        JsonObject dispatch = findTool(tools, "dispatch_subagent");
        assertTrue(dispatch.get("description").getAsString().contains("聚合返回结果"));
        JsonObject dispatchProperties = dispatch.getAsJsonObject("inputSchema")
                .getAsJsonObject("properties");
        assertTrue(dispatchProperties.getAsJsonObject("tasks")
                .get("description").getAsString().contains("独立任务实例"));
        JsonObject dispatchItemProperties = dispatchProperties.getAsJsonObject("tasks")
                .getAsJsonObject("items").getAsJsonObject("properties");
        assertTrue(dispatchItemProperties.getAsJsonObject("agent").has("description"));
        assertTrue(dispatchItemProperties.getAsJsonObject("title")
                .get("description").getAsString().contains("2～6"));

        JsonObject schedule = findTool(tools, "schedule_task");
        JsonObject scheduleProperties = schedule.getAsJsonObject("inputSchema")
                .getAsJsonObject("properties");
        JsonObject scheduleFields = scheduleProperties.getAsJsonObject("tasks")
                .getAsJsonObject("items").getAsJsonObject("properties")
                .getAsJsonObject("schedule").getAsJsonObject("properties");
        assertEquals("cron", scheduleFields.getAsJsonObject("type")
                .getAsJsonArray("enum").get(0).getAsString());
        assertTrue(scheduleFields.getAsJsonObject("expr")
                .get("description").getAsString().contains("标准五位 cron"));

        JsonObject manage = findTool(tools, "manage_schedule");
        JsonArray operations = manage.getAsJsonObject("inputSchema")
                .getAsJsonObject("properties").getAsJsonObject("operation")
                .getAsJsonArray("enum");
        assertEquals(3, operations.size());
        assertEquals("update", operations.get(2).getAsString());

        JsonObject talkTo = findTool(tools, "talk_to");
        JsonObject talkToProperties = talkTo.getAsJsonObject("inputSchema")
                .getAsJsonObject("properties");
        assertTrue(talkTo.get("description").getAsString().contains("路由层已经接收"));
        assertTrue(talkToProperties.getAsJsonObject("target").has("description"));
        assertTrue(talkToProperties.getAsJsonObject("content").has("description"));
        assertFalse(talkToProperties.has("_depth"));
        JsonObject newSessionSchema = findTool(tools, "new_session").getAsJsonObject("inputSchema");
        assertEquals(1, newSessionSchema.getAsJsonObject("properties").size());
        assertEquals("prompt", newSessionSchema.getAsJsonArray("required").get(0).getAsString());
        assertFalse(newSessionSchema.get("additionalProperties").getAsBoolean());
    }

    @Test
    public void routesToolCallByAuthSessionHeader() throws Exception {
        ActionRuntimeRegistry.getInstance().register("test-session",
                (name, arguments) -> name + ":" + arguments.get("target").getAsString(),
                () -> java.util.Collections.singleton("talk_to"));
        JsonObject response = post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"talk_to\",\"arguments\":{\"target\":\"Agent B\",\"content\":\"hi\"}}}",
                "application/json", "test-session");
        JsonObject result = response.getAsJsonObject("result");
        assertFalse(result.get("isError").getAsBoolean());
        assertEquals("talk_to:Agent B", result.getAsJsonArray("content").get(0)
                .getAsJsonObject().get("text").getAsString());
    }

    @Test
    public void missingAuthContextReturnsToolError() throws Exception {
        JsonObject response = post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"talk_to\",\"arguments\":{}}}",
                "application/json", null);
        assertTrue(response.getAsJsonObject("result").get("isError").getAsBoolean());
    }

    @Test
    public void routesNewSessionPromptToOwningRuntime() throws Exception {
        ActionRuntimeRegistry.getInstance().register("test-session",
                (name, arguments) -> name + ":" + arguments.get("prompt").getAsString(),
                () -> java.util.Collections.singleton("new_session"));
        JsonObject response = post("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"new_session\",\"arguments\":{\"prompt\":\"continue\"}}}",
                "application/json", "test-session");
        JsonObject result = response.getAsJsonObject("result");
        assertFalse(result.get("isError").getAsBoolean());
        assertEquals("new_session:continue", result.getAsJsonArray("content").get(0)
                .getAsJsonObject().get("text").getAsString());
    }

    @Test
    public void supportsSseResponseEnvelope() throws Exception {
        HttpURLConnection connection = open("text/event-stream", null);
        write(connection, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}");
        String body = read(connection.getInputStream());
        assertEquals("text/event-stream; charset=utf-8", connection.getContentType());
        assertTrue(body.startsWith("event: message\ndata: "));
    }

    @Test
    public void rejectsOversizedRequestBody() throws Exception {
        HttpURLConnection connection = open("application/json", null);
        char[] payload = new char[1024 * 1024 + 1];
        java.util.Arrays.fill(payload, 'x');
        write(connection, new String(payload));
        assertEquals(413, connection.getResponseCode());
        JsonObject response = JsonParser.parseString(read(connection.getErrorStream()))
                .getAsJsonObject();
        assertEquals(-32600, response.getAsJsonObject("error").get("code").getAsInt());
    }

    private JsonObject post(String body, String accept, String authSessionId) throws Exception {
        HttpURLConnection connection = open(accept, authSessionId);
        write(connection, body);
        return JsonParser.parseString(read(connection.getInputStream())).getAsJsonObject();
    }

    private static JsonObject findTool(JsonArray tools, String name) {
        for (int i = 0; i < tools.size(); i++) {
            JsonObject tool = tools.get(i).getAsJsonObject();
            if (name.equals(tool.get("name").getAsString())) return tool;
        }
        fail("Missing tool: " + name);
        return null;
    }

    private HttpURLConnection open(String accept, String authSessionId) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", accept);
        if (authSessionId != null) {
            connection.setRequestProperty(CmdProxyMcpHttpHandler.AUTH_SESSION_HEADER, authSessionId);
        }
        return connection;
    }

    private static void write(HttpURLConnection connection, String body) throws Exception {
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String read(InputStream input) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
