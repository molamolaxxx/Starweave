package com.mola.cmd.proxy.app.acp.task.mcp;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.task.model.TaskException;
import com.mola.cmd.proxy.app.acp.task.api.TaskRestHandler;
import com.mola.cmd.proxy.app.acp.task.service.TaskService;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stateless Streamable HTTP and legacy HTTP+SSE transports for Starweave tasks.
 *
 * <p>It intentionally exposes six business tools. Transport session IDs only bind a
 * POST response to an SSE stream and are not authentication credentials.</p>
 */
public final class TaskMcpServer implements AutoCloseable {
    public static final String SERVER_NAME = "starweave-tasks";
    public static final String PROTOCOL_VERSION = "2024-11-05";
    public static final String HTTP_PROTOCOL_VERSION = "2025-03-26";
    public static final String HTTP_PATH = "/task-mcp";
    public static final String SSE_PATH = "/task-mcp/sse";
    public static final String MESSAGES_PATH = "/task-mcp/messages";
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final int MAX_SESSIONS = 64;
    private static final int SESSION_QUEUE_CAPACITY = 64;
    private static final String CLOSE = "__TASK_MCP_CLOSE__";

    private final TaskMcpOperations operations;
    private final ConcurrentHashMap<String, TaskCaller> callers = new ConcurrentHashMap<>();
    private final HttpServer server;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, SseSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public TaskMcpServer(InetSocketAddress address, TaskMcpOperations operations)
            throws IOException {
        this(address, operations, null);
    }

    /** Production constructor: MCP tools and attachment HTTP routes share one TaskService. */
    public TaskMcpServer(InetSocketAddress address, TaskService service) throws IOException {
        this(address, new TaskServiceMcpOperations(service), new TaskRestHandler(service));
    }

    private TaskMcpServer(InetSocketAddress address, TaskMcpOperations operations,
                          HttpHandler taskRestHandler) throws IOException {
        this.operations = java.util.Objects.requireNonNull(operations, "operations");
        this.server = HttpServer.create(
                java.util.Objects.requireNonNull(address, "address"), 32);
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "starweave-task-mcp");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext(HTTP_PATH, new HttpRpcHandler());
        server.createContext(SSE_PATH, new SseHandler());
        server.createContext(MESSAGES_PATH, new MessagesHandler());
        if (taskRestHandler != null) {
            server.createContext(TaskRestHandler.PREFIX, taskRestHandler);
        }
    }

    public void start() {
        if (closed.get()) throw new IllegalStateException("task MCP server is closed");
        server.start();
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    /** ACP session/new/session/load descriptor for the stateless HTTP endpoint. */
    public static JsonObject acpServerDescriptor(String advertisedBaseUrl) {
        String base = required(advertisedBaseUrl, "advertisedBaseUrl");
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("name", SERVER_NAME);
        descriptor.addProperty("type", "http");
        descriptor.addProperty("url", base + HTTP_PATH);
        descriptor.add("headers", new JsonArray());
        return descriptor;
    }

    /** Per-client opaque credential; runtime routes must preserve this Header for remote Agents. */
    public JsonObject registerCaller(String advertisedBaseUrl, TaskCaller caller, java.util.function.Consumer<Runnable> onClose) {
        if(closed.get()||callers.size()>=4096)throw new IllegalStateException("Task MCP caller limit reached");
        String token=UUID.randomUUID().toString()+UUID.randomUUID();callers.put(token,caller);
        onClose.accept(() -> callers.remove(token,caller));
        JsonObject descriptor=acpServerDescriptor(advertisedBaseUrl),header=new JsonObject();
        header.addProperty("name","Authorization");header.addProperty("value","Bearer "+token);descriptor.getAsJsonArray("headers").add(header);return descriptor;
    }
    private TaskCaller caller(HttpExchange exchange) {
        String header=exchange.getRequestHeaders().getFirst("Authorization");
        return header==null?null:callers.get(header.replaceFirst("^Bearer ",""));
    }
    private boolean authenticate(HttpExchange exchange) throws IOException {
        if(exchange.getRequestHeaders().getFirst("Authorization")!=null&&caller(exchange)==null){sendPlain(exchange,401,"Task caller credential expired or invalid");return false;}
        return true;
    }
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        server.stop(0);
        for (SseSession session : sessions.values()) session.offer(CLOSE);
        sessions.clear();
        callers.clear();
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** No server-initiated requests: each POST returns JSON, notifications return empty 202. */
    private final class HttpRpcHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!authenticate(exchange))return;
            if (!validOrigin(exchange)) {
                sendPlain(exchange, 403, "Origin is not allowed");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (!HTTP_PATH.equals(path) && !SSE_PATH.equals(path)) {
                sendPlain(exchange, 404, "Unknown MCP endpoint");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                sendPlain(exchange, 405, "POST required");
                return;
            }
            if (closed.get()) {
                sendPlain(exchange, 503, "task MCP server is closed");
                return;
            }
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !"application/json".equalsIgnoreCase(contentType.split(";")[0].trim())) {
                sendPlain(exchange, 415, "application/json required");
                return;
            }
            Object body;
            try {
                body = JSON.parse(readBody(exchange.getRequestBody()));
                if (!(body instanceof JSONObject) && !(body instanceof JSONArray)) throw new IllegalArgumentException();
                if (body instanceof JSONArray && (((JSONArray) body).isEmpty()
                        || ((JSONArray) body).size() > SESSION_QUEUE_CAPACITY)) throw new IllegalArgumentException();
                if (body instanceof JSONArray) for (Object item : (JSONArray) body) {
                    if (!(item instanceof JSONObject)) throw new IllegalArgumentException();
                }
            } catch (RuntimeException invalid) {
                sendPlain(exchange, 400, "invalid JSON-RPC request");
                return;
            }
            Object result;
            if (body instanceof JSONArray) {
                JSONArray responses = new JSONArray();
                for (Object item : (JSONArray) body) {
                    JSONObject response = httpRpc((JSONObject) item, caller(exchange));
                    if (response != null) responses.add(response);
                }
                result = responses.isEmpty() ? null : responses;
            } else {
                result = httpRpc((JSONObject) body, caller(exchange));
            }
            if (result == null) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            byte[] bytes = JSON.toJSONString(result).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
            finally { exchange.close(); }
        }
    }

    private JSONObject httpRpc(JSONObject request, TaskCaller caller) {
        if (!"2.0".equals(request.getString("jsonrpc"))) return error(request.get("id"), -32600, "invalid JSON-RPC version");
        if (!request.containsKey("method") && (request.containsKey("result") || request.containsKey("error"))) return null;
        JSONObject response = handleRpc(request, caller);
        if ("initialize".equals(request.getString("method")) && response != null
                && response.getJSONObject("result") != null) {
            response.getJSONObject("result").put("protocolVersion", HTTP_PROTOCOL_VERSION);
        }
        return response;
    }

    private boolean validOrigin(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null) return true;
        try {
            URI uri = URI.create(origin);
            return "http".equals(uri.getScheme()) && uri.getUserInfo() == null
                    && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()))
                    && uri.getPort() == getPort();
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private final class SseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!authenticate(exchange))return;
            // Keep previously injected URLs usable by clients that POST initialization.
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                new HttpRpcHandler().handle(exchange);
                return;
            }
            if (!validOrigin(exchange)) { sendPlain(exchange, 403, "Origin is not allowed"); return; }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendPlain(exchange, 405, "GET required");
                return;
            }
            if (closed.get()) {
                sendPlain(exchange, 503, "task MCP server is closed");
                return;
            }
            if (sessions.size() >= MAX_SESSIONS) {
                sendPlain(exchange, 503, "too many task MCP sessions");
                return;
            }
            String sessionId = UUID.randomUUID().toString();
            SseSession session = new SseSession(caller(exchange));
            sessions.put(sessionId, session);
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/event-stream; charset=utf-8");
            headers.set("Cache-Control", "no-cache, no-transform");
            headers.set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                writeEvent(output, "endpoint", MESSAGES_PATH + "?sessionId=" + sessionId);
                while (!closed.get()) {
                    String message;
                    try {
                        message = session.queue.poll(15, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (CLOSE.equals(message)) break;
                    if (message == null) {
                        output.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                        output.flush();
                    } else {
                        writeEvent(output, "message", message);
                    }
                }
            } finally {
                sessions.remove(sessionId, session);
                exchange.close();
            }
        }
    }

    private final class MessagesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(!authenticate(exchange))return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendPlain(exchange, 405, "POST required");
                return;
            }
            if (!validOrigin(exchange)) { sendPlain(exchange, 403, "Origin is not allowed"); return; }
            String sessionId = query(exchange.getRequestURI()).get("sessionId");
            SseSession session = sessionId == null ? null : sessions.get(sessionId);
            if (session == null) {
                sendPlain(exchange, 404, "unknown or closed MCP session");
                return;
            }
            if(session.caller!=caller(exchange)){sendPlain(exchange,403,"MCP session belongs to another caller");return;}
            JSONObject request;
            try {
                request = JSON.parseObject(readBody(exchange.getRequestBody()));
                if (request == null) throw new IllegalArgumentException("JSON object required");
            } catch (RuntimeException invalid) {
                sendPlain(exchange, 400, "invalid JSON-RPC request");
                return;
            }
            JSONObject response = handleRpc(request, session.caller);
            if (response != null && !session.offer(response.toJSONString())) {
                sendPlain(exchange, 503, "MCP session response queue is full");
                return;
            }
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        }
    }

    private JSONObject handleRpc(JSONObject request, TaskCaller caller) {
        Object id = request.get("id");
        String method = request.getString("method");
        if (method == null || method.trim().isEmpty()) return error(id, -32600, "invalid request");
        // JSON-RPC notifications intentionally have no response.
        boolean notification = !request.containsKey("id");
        if ("notifications/initialized".equals(method)) return null;
        if (notification) return null;
        try {
            if ("initialize".equals(method)) return success(id, initializeResult());
            if ("ping".equals(method)) return success(id, new JSONObject(true));
            if ("tools/list".equals(method)) return success(id, toolsResult());
            if ("tools/call".equals(method)) {
                JSONObject params = request.getJSONObject("params");
                if (params == null) throw new IllegalArgumentException("params is required");
                return success(id, callTool(params, caller));
            }
            return error(id, -32601, "method not found: " + method);
        } catch (IllegalArgumentException invalid) {
            return error(id, -32602, safeMessage(invalid));
        } catch (RuntimeException failure) {
            return success(id, toolFailure(failure));
        }
    }

    private JSONObject callTool(JSONObject params, TaskCaller caller) {
        String name = required(params.getString("name"), "name");
        JSONObject args = params.getJSONObject("arguments");
        if (args == null) args = new JSONObject(true);
        JSONObject data;
        switch (name) {
            case "list_tasks": {
                data=operations.listTasks(args,caller);break;
            }
            case "publish_task": {
                data=operations.publishTask(args,caller);break;
            }
            case "claim_task": {
                data=operations.claimTask(args,caller);break;
            }
            case "get_task": {
                String taskId = required(args.getString("taskId"), "taskId");
                if (args.containsKey("revision") && args.containsKey("contentVersion")) {
                    throw new IllegalArgumentException(
                            "revision and contentVersion are mutually exclusive");
                }
                JSONObject query = copyFields(args, "revision", "contentVersion",
                        "commentsCursor", "historyCursor", "limit");
                data = operations.getTask(taskId, query);
                break;
            }
            case "update_task_status": {
                String taskId = required(args.getString("taskId"), "taskId");
                JSONObject request = copyFields(args, "status", "expectedRevision",
                        "observedContentVersion", "requestId", "actorName", "reason");
                request.put("reopen", false);
                data = operations.updateTaskStatus(taskId, request);
                break;
            }
            case "add_task_comment": {
                String taskId = required(args.getString("taskId"), "taskId");
                JSONObject request = copyFields(args, "contentMarkdown", "attachmentIds",
                        "observedContentVersion", "authorName", "requestId");
                request.put("authorType", "AGENT");
                data = operations.addTaskComment(taskId, request);
                break;
            }
            default:
                throw new IllegalArgumentException("unknown task tool: " + name);
        }
        return toolSuccess(data);
    }

    private static JSONObject initializeResult() {
        JSONObject result = new JSONObject(true);
        result.put("protocolVersion", PROTOCOL_VERSION);
        JSONObject capabilities = new JSONObject(true);
        capabilities.put("tools", new JSONObject(true));
        result.put("capabilities", capabilities);
        JSONObject serverInfo = new JSONObject(true);
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", "1.0.0");
        result.put("serverInfo", serverInfo);
        return result;
    }

    private static JSONObject toolsResult() {
        JSONArray tools = new JSONArray();
        tools.add(tool("get_task", "获取待办任务的最新详情、状态、版本、评论和历史。 Read the authoritative task snapshot, current versions, status, allowed transitions, comments and history. Call this before starting, resuming or completing work.",
                schema(properties(
                        property("taskId", "string", "待办任务 UUID"),
                        property("revision", "integer", "Optional snapshot revision"),
                        property("contentVersion", "integer", "Optional requirement version"),
                        property("commentsCursor", "string", "Optional comments cursor"),
                        property("historyCursor", "string", "Optional history cursor"),
                        property("limit", "integer", "Page size, at most 100")), "taskId")));
        tools.add(tool("update_task_status", "更新待办任务的执行状态。 Persist a task status transition using the latest optimistic versions. Normally set IN_PROGRESS when work starts and COMPLETED only after finishing and re-reading the latest task. A chat reply or comment does not change status; Agents cannot reopen terminal tasks.",
                schema(properties(
                        property("taskId", "string", "待办任务 UUID"),
                        enumProperty("status", "Target status. Legal values are START, IN_PROGRESS, COMPLETED, CANCELLED and SUSPENDED; use get_task.allowedTransitions for the current task.", "START", "IN_PROGRESS", "COMPLETED", "CANCELLED", "SUSPENDED"),
                        property("expectedRevision", "integer", "Expected current revision"),
                        property("observedContentVersion", "integer", "Content version processed"),
                        property("requestId", "string", "Persistent idempotency key"),
                        property("actorName", "string", "Agent display name"),
                        property("reason", "string", "Optional status-change reason")),
                        "taskId", "status", "expectedRevision", "observedContentVersion",
                        "requestId", "actorName")));
        tools.add(tool("add_task_comment", "为待办任务记录进度、阻塞或执行结果。 Append Agent progress, blockers or results to a task. A comment does not start, suspend, cancel or complete the task; use update_task_status for transitions. Markdown or at least one uploaded attachment is required.",
                schema(properties(
                        property("taskId", "string", "待办任务 UUID"),
                        property("contentMarkdown", "string", "Markdown comment"),
                        arrayProperty("attachmentIds", "Previously uploaded attachment IDs"),
                        property("observedContentVersion", "integer", "Observed content version"),
                        property("authorName", "string", "Agent display name"),
                        property("requestId", "string", "Persistent idempotency key")),
                        "taskId", "observedContentVersion", "authorName", "requestId")));
        tools.add(tool("list_tasks", "列出当前环境所有人的待办任务，默认排除 COMPLETED 和 CANCELLED。可查询待认领待办任务或本人待办任务；用 get_task 查看明细，未认领成功不得执行。", schema(properties(
            property("q","string","待办任务名称或 ID"), arrayProperty("statuses","指定状态集合；不能与 status/includeTerminal 同传"),
            enumProperty("status","单个状态","START","IN_PROGRESS","SUSPENDED","COMPLETED","CANCELLED"),
            property("includeTerminal","boolean","未指定状态时是否包含终态，默认 false"),
            enumProperty("assignmentState","分配情况","UNCLAIMED","PENDING_ASSIGNMENT","ASSIGNED"),
            property("onlyMine","boolean","仅查询当前调用 Agent 的待办任务，默认 false"),property("claimableOnly","boolean","仅查可认领待办任务，默认 false"),
            property("assigneeInstanceId","string","执行者所属实例"),property("assigneeAgentId","string","执行 Agent ID"),
            property("assigneeTeamId","string","团队 ID"),property("assigneeTeamMemberId","string","团队成员 ID"),property("assigneeName","string","执行者名称"),
            property("createdFrom","string","起始 UTC ISO 时间"),property("createdTo","string","截止 UTC ISO 时间"),
            property("page","integer","页码，默认 1"),property("pageSize","integer","单页条数，默认 20，最多 100")))));
        tools.add(tool("publish_task", "发布待办任务。默认进入无人认领待办任务池、不投递消息；可先调用 acp-harness.list_agents，将可分配目标的 agent_ref 原样传入 assignee_ref，指定普通 Agent 或团队成员，系统随后投递执行消息。发布者和当前来源待办任务由服务端自动记录，不能伪造；可通过 get_task 查看来源和派生待办任务。", schema(properties(
            property("assignee_ref","string","可选：list_agents 返回且 task_assignable=true 的 agent_ref；不要填写名称或自行构造 ID"),property("name","string","待办任务名称"),property("contentMarkdown","string","待办任务要求 Markdown"),arrayProperty("attachmentIds","已上传附件 ID"),
            property("requestId","string","持久幂等键，超时后用原键和原参数重试")),"name","requestId")));
        tools.add(tool("claim_task", "为当前调用 Agent 认领待办任务，成功后继续执行，可组织团队协作，无需等待额外通知，不重复投递执行消息。指定 taskId 时须先 get_task 并携带最新版本；省略 taskId 则原子领取最早可认领待办任务。失败不要执行；超时用原 requestId 和参数确认结果，不得换键另领。", schema(properties(
            property("taskId","string","可选；省略时自动领取下一条"),property("expectedRevision","integer","指定待办任务时必填：最新修订号"),
            property("observedContentVersion","integer","指定待办任务时必填：最新正文版本"),property("requestId","string","持久幂等键，重试必须复用")),"requestId")));
        JSONObject result = new JSONObject(true);
        result.put("tools", tools);
        return result;
    }

    private static JSONObject tool(String name, String description, JSONObject schema) {
        JSONObject tool = new JSONObject(true);
        tool.put("name", name);
        tool.put("description", description);
        tool.put("inputSchema", schema);
        return tool;
    }

    private static JSONObject schema(JSONObject properties, String... required) {
        JSONObject schema = new JSONObject(true);
        schema.put("type", "object");
        schema.put("properties", properties);
        JSONArray names = new JSONArray();
        Collections.addAll(names, required);
        schema.put("required", names);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static JSONObject properties(JSONObject... values) {
        JSONObject result = new JSONObject(true);
        for (JSONObject value : values) result.put(value.getString("name"), value.get("schema"));
        return result;
    }

    private static JSONObject property(String name, String type, String description) {
        JSONObject schema = new JSONObject(true);
        schema.put("type", type);
        schema.put("description", description);
        JSONObject wrapper = new JSONObject(true);
        wrapper.put("name", name);
        wrapper.put("schema", schema);
        return wrapper;
    }

    private static JSONObject arrayProperty(String name, String description) {
        JSONObject property = property(name, "array", description);
        JSONObject item = new JSONObject(true);
        item.put("type", "string");
        property.getJSONObject("schema").put("items", item);
        return property;
    }

    private static JSONObject enumProperty(String name, String description, String... values) {
        JSONObject property = property(name, "string", description);
        JSONArray names = new JSONArray();
        Collections.addAll(names, values);
        property.getJSONObject("schema").put("enum", names);
        return property;
    }

    private static JSONObject toolSuccess(JSONObject data) {
        JSONObject result = new JSONObject(true);
        JSONArray content = new JSONArray();
        JSONObject text = new JSONObject(true);
        text.put("type", "text");
        text.put("text", data == null ? "null" : data.toJSONString());
        content.add(text);
        result.put("content", content);
        result.put("structuredContent", data);
        result.put("isError", false);
        return result;
    }

    private static JSONObject toolFailure(RuntimeException failure) {
        JSONObject result = new JSONObject(true);
        JSONObject failureData = new JSONObject(true);
        if (failure instanceof TaskException) {
            TaskException task = (TaskException) failure;
            failureData.put("code", task.getCode());
            failureData.put("data", task.getData());
        } else {
            failureData.put("code", "TASK_OPERATION_FAILED");
        }
        failureData.put("message", safeMessage(failure));
        failureData.put("claimed",false);
        String code=failureData.getString("code");
        String next="操作未成功，请勿开始执行待办任务。";
        if("TASK_ALREADY_ASSIGNED".equals(code))next="待办任务已被其他执行者接走，请勿执行，可查询其他待认领待办任务。";
        else if("NO_CLAIMABLE_TASKS".equals(code))next="当前没有可认领待办任务，正常结束本次领取，无需立即循环重试。";
        else if("VERSION_CONFLICT".equals(code)||"LATEST_CONTENT_REQUIRED".equals(code))next="请先 get_task 读取最新要求与状态，再决定是否认领，不要基于旧版本执行。";
        else if("STORAGE_UNAVAILABLE".equals(code))next="结果暂未确认，请使用原 requestId 和原参数重试确认，确认成功前不要执行或另领待办任务。";
        failureData.put("instruction",next);
        JSONArray content = new JSONArray();
        JSONObject text = new JSONObject(true);
        text.put("type", "text");
        text.put("text", failureData.toJSONString());
        content.add(text);
        result.put("content", content);
        result.put("structuredContent", failureData);
        result.put("isError", !"NO_CLAIMABLE_TASKS".equals(failureData.getString("code")));
        return result;
    }

    private static JSONObject copyFields(JSONObject source, String... fields) {
        JSONObject copy = new JSONObject(true);
        for (String field : fields) if (source.containsKey(field)) copy.put(field, source.get(field));
        return copy;
    }

    private static JSONObject success(Object id, JSONObject result) {
        JSONObject response = base(id);
        response.put("result", result);
        return response;
    }

    private static JSONObject error(Object id, int code, String message) {
        JSONObject response = base(id);
        JSONObject error = new JSONObject(true);
        error.put("code", code);
        error.put("message", message);
        response.put("error", error);
        return response;
    }

    private static JSONObject base(Object id) {
        JSONObject response = new JSONObject(true);
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        return response;
    }

    private static void writeEvent(OutputStream output, String event, String data)
            throws IOException {
        output.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
        String normalized = data == null ? "" : data.replace("\r", "");
        for (String line : normalized.split("\n", -1)) {
            output.write(("data: " + line + "\n").getBytes(StandardCharsets.UTF_8));
        }
        output.write('\n');
        output.flush();
    }

    private static String readBody(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            total += count;
            if (total > MAX_REQUEST_BYTES) throw new IllegalArgumentException("request too large");
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> result = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) return result;
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            result.put(decode(key), decode(value));
        }
        return result;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void sendPlain(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String safeMessage(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? "task operation failed" : message.trim();
    }

    private static final class SseSession {
        final TaskCaller caller;
        SseSession(TaskCaller caller){this.caller=caller;}
        private final ArrayBlockingQueue<String> queue =
                new ArrayBlockingQueue<>(SESSION_QUEUE_CAPACITY);

        private boolean offer(String message) {
            return queue.offer(message);
        }
    }
}
