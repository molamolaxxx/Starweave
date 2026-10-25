package com.mola.cmd.proxy.app.acp.task.mcp;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TaskMcpServerTest {
    private TaskMcpServer server;
    private HttpURLConnection sse;
    private BufferedReader events;
    private String messageUrl;

    @Before
    public void startServer() throws Exception {
        server = new TaskMcpServer(new InetSocketAddress("127.0.0.1", 0), new Operations());
        server.start();
        sse = (HttpURLConnection) new URL("http://127.0.0.1:" + server.getPort()
                + TaskMcpServer.SSE_PATH).openConnection();
        sse.setRequestProperty("Accept", "text/event-stream");
        assertEquals(200, sse.getResponseCode());
        events = new BufferedReader(new InputStreamReader(
                sse.getInputStream(), StandardCharsets.UTF_8));
        assertEquals("event: endpoint", events.readLine());
        String endpoint = events.readLine();
        events.readLine();
        messageUrl = "http://127.0.0.1:" + server.getPort()
                + endpoint.substring("data: ".length());
    }

    @After
    public void stopServer() throws Exception {
        if (events != null) events.close();
        if (sse != null) sse.disconnect();
        if (server != null) server.close();
    }

    @Test
    public void initializesAndListsSixToolsOverSse() throws Exception {
        JSONObject initialize = request(1, "initialize", new JSONObject(true));
        assertEquals(202, post(initialize));
        JSONObject initialized = nextMessage();
        assertEquals(TaskMcpServer.PROTOCOL_VERSION,
                initialized.getJSONObject("result").getString("protocolVersion"));

        assertEquals(202, post(request(2, "tools/list", new JSONObject(true))));
        JSONObject listed = nextMessage();
        com.alibaba.fastjson.JSONArray tools=listed.getJSONObject("result").getJSONArray("tools");
        assertEquals(6, tools.size());
        JSONObject status=tools.getJSONObject(1).getJSONObject("inputSchema")
                .getJSONObject("properties").getJSONObject("status");
        assertEquals(5,status.getJSONArray("enum").size());
        assertEquals("COMPLETED",status.getJSONArray("enum").getString(2));
        assertTrue(tools.getJSONObject(1).getString("description")
                .contains("comment does not change status"));
    }

    @Test
    public void callsTaskOperationAndForcesAgentIdentityFields() throws Exception {
        JSONObject arguments = new JSONObject(true);
        arguments.put("taskId", "task-1");
        arguments.put("status", "IN_PROGRESS");
        arguments.put("expectedRevision", 1L);
        arguments.put("observedContentVersion", 1L);
        arguments.put("requestId", "request-1");
        arguments.put("actorName", "Agent A");
        JSONObject params = new JSONObject(true);
        params.put("name", "update_task_status");
        params.put("arguments", arguments);

        assertEquals(202, post(request(3, "tools/call", params)));
        JSONObject result = nextMessage().getJSONObject("result");

        assertFalse(result.getBooleanValue("isError"));
        assertEquals("AGENT_STATUS", result.getJSONObject("structuredContent")
                .getString("operation"));
        assertFalse(result.getJSONObject("structuredContent").getBooleanValue("reopen"));
    }

    @Test
    public void httpInitializationAndToolsWorkOnNewAndPreviouslyInjectedUrls() throws Exception {
        for (String path : new String[]{TaskMcpServer.HTTP_PATH, TaskMcpServer.SSE_PATH}) {
            JSONObject initialized = httpPost(path, request(10, "initialize", new JSONObject()), 200, null);
            assertEquals(TaskMcpServer.HTTP_PROTOCOL_VERSION, initialized.getJSONObject("result").getString("protocolVersion"));
            JSONObject listed = httpPost(path, request(11, "tools/list", new JSONObject()), 200, null);
            assertEquals(6, listed.getJSONObject("result").getJSONArray("tools").size());
            JSONObject params = JSON.parseObject("{name:'get_task',arguments:{taskId:'one'}}");
            JSONObject called = httpPost(path, request(12, "tools/call", params), 200, null);
            assertFalse(called.getJSONObject("result").getBooleanValue("isError"));
            assertEquals("GET", called.getJSONObject("result").getJSONObject("structuredContent").getString("operation"));
            JSONObject notification = JSON.parseObject("{jsonrpc:'2.0',method:'notifications/initialized'}");
            httpPost(path, notification, 202, null);
        }
        assertEquals("http://127.0.0.1:" + server.getPort() + TaskMcpServer.HTTP_PATH,
                TaskMcpServer.acpServerDescriptor("http://127.0.0.1:" + server.getPort()).get("url").getAsString());
    }

    @Test
    public void httpRejectsForeignOriginsBeforeExecutingTools() throws Exception {
        httpPost(TaskMcpServer.HTTP_PATH, request(13, "tools/list", new JSONObject()), 403, "https://untrusted.example");
    }

    private JSONObject httpPost(String path, JSONObject body, int expected, String origin) throws Exception {
        // OkHttp permits Origin; HttpURLConnection silently suppresses this restricted header.
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                .url("http://127.0.0.1:" + server.getPort() + path)
                .header("Accept", "application/json, text/event-stream")
                .post(okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json"), body.toJSONString()));
        if (origin != null) builder.header("Origin", origin);
        try (okhttp3.Response response = client.newCall(builder.build()).execute()) {
            assertEquals(expected, response.code());
            return expected == 200 ? JSON.parseObject(response.body().string()) : null;
        } finally { client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown(); }
    }

    private int post(JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(messageUrl).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        byte[] bytes = body.toJSONString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
        int status = connection.getResponseCode();
        connection.disconnect();
        return status;
    }

    private JSONObject nextMessage() throws Exception {
        assertEquals("event: message", events.readLine());
        String data = events.readLine();
        events.readLine();
        return JSON.parseObject(data.substring("data: ".length()));
    }

    private static JSONObject request(int id, String method, JSONObject params) {
        JSONObject request = new JSONObject(true);
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);
        return request;
    }

    private static final class Operations implements TaskMcpOperations {
        @Override
        public JSONObject getTask(String taskId, JSONObject query) {
            return value("GET", false);
        }

        @Override
        public JSONObject updateTaskStatus(String taskId, JSONObject request) {
            return value("AGENT_STATUS", request.getBooleanValue("reopen"));
        }

        @Override
        public JSONObject addTaskComment(String taskId, JSONObject request) {
            return value(request.getString("authorType"), false);
        }

        private JSONObject value(String operation, boolean reopen) {
            JSONObject value = new JSONObject(true);
            value.put("operation", operation);
            value.put("reopen", reopen);
            return value;
        }
    }
}
