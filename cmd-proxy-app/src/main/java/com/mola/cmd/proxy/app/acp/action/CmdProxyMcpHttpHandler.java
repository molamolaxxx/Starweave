package com.mola.cmd.proxy.app.acp.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.sessionquery.ConversationQueryService;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.util.Set;

/** Minimal MCP Streamable HTTP endpoint for cmd-proxy-owned action tools. */
public final class CmdProxyMcpHttpHandler implements HttpHandler {

    public static final String PATH = "/mcp";
    public static final String SERVER_NAME = "acp-harness-runtime";
    public static final String AUTH_SESSION_HEADER = "X-Cmd-Proxy-Auth-Session-Id";
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        InetAddress remote = exchange.getRemoteAddress() == null
                ? null : exchange.getRemoteAddress().getAddress();
        if (remote == null || !remote.isLoopbackAddress()) {
            send(exchange, 403, error(null, -32001, "Loopback access only"));
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            send(exchange, 405, error(null, -32600, "Only POST is supported"));
            return;
        }

        JsonObject request;
        try {
            byte[] body = readAll(exchange);
            request = JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (RequestTooLargeException e) {
            send(exchange, 413, error(null, -32600, "Request body too large"));
            return;
        } catch (RuntimeException e) {
            send(exchange, 400, error(null, -32700, "Invalid JSON"));
            return;
        }

        JsonElement id = request.get("id");
        String method = string(request, "method");
        if ("notifications/initialized".equals(method)) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }

        JsonObject response;
        try {
            switch (method) {
                case "initialize":
                    response = success(id, initializeResult(request));
                    break;
                case "tools/list":
                    JsonObject list = new JsonObject();
                    list.add("tools", tools(ActionRuntimeRegistry.getInstance().availableTools(
                            exchange.getRequestHeaders().getFirst(AUTH_SESSION_HEADER))));
                    response = success(id, list);
                    break;
                case "tools/call":
                    response = success(id, callTool(exchange, request));
                    break;
                case "ping":
                    response = success(id, new JsonObject());
                    break;
                default:
                    response = error(id, -32601, "Method not found: " + method);
            }
        } catch (ConversationQueryService.QueryException e) {
            JsonObject body = new JsonObject();
            JsonObject detail = new JsonObject();
            detail.addProperty("code", e.code); detail.addProperty("message", e.getMessage());
            body.add("error", detail);
            response = success(id, toolError(body.toString()));
        } catch (Exception e) {
            response = success(id, toolError(e.getMessage()));
        }
        send(exchange, 200, response);
    }

    private JsonObject initializeResult(JsonObject request) {
        JsonObject params = request.getAsJsonObject("params");
        String requested = params == null ? "" : string(params, "protocolVersion");
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", requested.isEmpty() ? PROTOCOL_VERSION : requested);
        JsonObject capabilities = new JsonObject();
        JsonObject toolCapabilities = new JsonObject();
        toolCapabilities.addProperty("listChanged", false);
        capabilities.add("tools", toolCapabilities);
        result.add("capabilities", capabilities);
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", SERVER_NAME);
        serverInfo.addProperty("version", "1.0.0");
        result.add("serverInfo", serverInfo);
        return result;
    }

    private JsonObject callTool(HttpExchange exchange, JsonObject request) throws Exception {
        JsonObject params = request.getAsJsonObject("params");
        if (params == null) return toolError("Missing params");
        String name = string(params, "name");
        if (!isActionTool(name)) return toolError("Unknown tool: " + name);
        JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();
        String authSessionId = exchange.getRequestHeaders().getFirst(AUTH_SESSION_HEADER);
        String result = ActionRuntimeRegistry.getInstance()
                .execute(authSessionId, name, arguments);
        JsonObject payload = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", result == null ? "" : result);
        content.add(text);
        payload.add("content", content);
        payload.addProperty("isError", false);
        return payload;
    }

    public static JsonArray tools() {
        return tools(new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                "dispatch_subagent", "schedule_task", "manage_schedule", "talk_to", "new_session",
                "manage_observation_channels", "test_observation_script", "query_observation_events",
                "search_sessions", "read_session_history")));
    }

    public static JsonArray tools(Set<String> availableTools) {
        JsonArray tools = new JsonArray();
        if (availableTools.contains("dispatch_subagent")) tools.add(tool("dispatch_subagent",
                "将一个或多个相互独立的任务派发给可用子 Agent。tasks 中的任务可并行执行，"
                        + "同一 Agent 可被派发多个独立任务；调用将在全部任务结束后聚合返回结果。",
                objectSchema("tasks", described(arrayOf(objectWithRequired(
                        new String[]{"agent", "title", "prompt"},
                        objectProperty("agent", described(stringSchema(),
                                "目标子 Agent 的准确名称，必须使用系统上下文提供的可用 Agent 名称。")),
                        objectProperty("title", described(stringSchema(),
                                "任务的简短标题，用于区分并行任务，建议使用 2～6 个字。")),
                        objectProperty("prompt", described(stringSchema(),
                                "交给子 Agent 的完整任务说明，应包含目标、必要上下文、输出要求和约束。")))),
                        "要派发的任务列表。每个数组元素会创建一个独立任务实例。"))));

        JsonObject scheduleTask = objectWithRequired(new String[]{"title", "prompt", "schedule"},
                objectProperty("title", described(stringSchema(),
                        "任务标题，用于识别和管理定时任务。")),
                objectProperty("prompt", described(stringSchema(),
                        "任务触发时提交给 Agent 的完整执行要求。")),
                objectProperty("schedule", described(scheduleSchema(),
                        "任务的调度配置。")));
        JsonObject createSchema = objectSchema("tasks", described(arrayOf(scheduleTask),
                "要创建的任务列表。一次调用可以创建多个独立定时任务。"));
        JsonObject groupNameSchema = stringSchema();
        groupNameSchema.addProperty("description",
                "可选的会话分组，应用于本次 tasks 数组内的全部任务。默认省略；"
                        + "仅当用户明确要求多个任务或多次执行共享会话时设置，禁止自行生成。"
                        + "省略时每次执行使用新会话；固定值会跨任务、跨日期和进程重启持续复用同一会话。"
                        + "支持日期模板，例如 daily-{yyyyMMdd} 表示同一自然日的执行共享一个会话，"
                        + "跨日自动使用新会话；模板在每次计划触发时按系统时区解析。");
        addProperty(createSchema, "groupName", groupNameSchema);
        if (availableTools.contains("schedule_task")) {
            tools.add(tool("schedule_task",
                    "创建一个或多个定时任务。tasks 中每项分别提供 title、prompt 和 schedule，"
                            + "并分别保存和执行；可选 groupName 应用于本次调用中的全部任务。",
                    createSchema));
        }

        JsonObject manage = objectSchema("operation", enumStringSchema(
                new String[]{"list", "cancel", "update"},
                "操作类型：list 查询任务，cancel 取消任务，update 更新任务。"));
        addProperty(manage, "taskId", described(stringSchema(),
                "目标任务 ID。cancel 和 update 时必填，list 时省略。"));
        JsonObject updates = new JsonObject();
        updates.addProperty("type", "object");
        JsonObject updateProps = new JsonObject();
        updateProps.add("title", described(stringSchema(), "新的任务标题。"));
        updateProps.add("prompt", described(stringSchema(), "新的任务执行要求。"));
        updateProps.add("schedule", described(scheduleSchema(),
                "新的调度配置，必须同时提供 type 和 expr。"));
        updates.add("properties", updateProps);
        updates.addProperty("additionalProperties", false);
        addProperty(manage, "updates", described(updates,
                "update 操作要修改的字段，只需传入需要变更的部分。"));
        if (availableTools.contains("manage_schedule")) {
            tools.add(tool("manage_schedule",
                    "查询、取消或更新当前 Agent 所属的定时任务。operation 决定具体操作。",
                    manage));
        }

        JsonObject talkTo = objectWithRequired(new String[]{"target", "content"},
                objectProperty("target", described(stringSchema(),
                        "消息目标。必须使用系统上下文中列出的准确 target；回复绑定信道时使用上下文明确提供的目标。"
                                + "禁止自行猜测名称、ID 或路由。")),
                objectProperty("content", described(stringSchema(),
                        "要发送的完整消息内容。")));
        if (availableTools.contains("talk_to")) {
            tools.add(tool("talk_to",
                    "向系统上下文列出的 Agent、Team 成员或当前绑定的信道回复目标异步发送消息。"
                            + "目标忙碌时消息可能进入队列；工具返回“已发送”或“已入队”只表示路由层已经接收，"
                            + "不表示目标已处理，也不保证当前 turn 内获得回复。发送后可以继续当前工作。",
                    talkTo));
        }
        if (availableTools.contains("new_session")) {
            tools.add(tool("new_session",
                    "请求当前 Agent 在本轮结束后创建全新会话，并以传入的提示词自动开始运行。"
                            + "返回结果表示请求已接收，执行结果通过后续会话事件反馈。",
                    objectSchema("prompt", described(stringSchema(), "新会话的第一条输入提示词。"))));
        }
        JsonObject observation = objectSchema("action", enumStringSchema(
                new String[]{"list", "get", "create", "update", "delete"}, "操作类型。"));
        addProperty(observation, "channel_id", described(stringSchema(), "通道 ID，get、update、delete 必填。"));
        addProperty(observation, "name", described(stringSchema(), "通道名称，create 必填。"));
        addProperty(observation, "eventAction", described(stringSchema(),
                "事件处理指令，create 必填，最多 8192 个字符。写明处理目标、判断条件及结果去向，使新会话也能独立执行；update 未传则保留原指令。事件产生时保存快照，投递和重试使用该快照。"));
        addProperty(observation, "script", described(stringSchema(), "JavaScript 脚本，通过 module.exports 导出返回字符串的函数，create 必填。"));
        addProperty(observation, "frequency", described(stringSchema(),
                "观测频率，默认 30s，支持 s、min、h。除非用户明确要求，否则建议使用默认值，"
                        + "或在修改已有通道时保持原频率，不主动调整。"));
        JsonObject booleanSchema = new JsonObject(); booleanSchema.addProperty("type", "boolean");
        addProperty(observation, "enabled", described(booleanSchema, "是否启用通道，默认 true。"));
        addObservationPageProperties(observation);
        if (availableTools.contains("manage_observation_channels")) tools.add(tool("manage_observation_channels", "管理当前 Agent 自己的观测通道。", observation));
        JsonObject test = objectWithRequired(new String[]{});
        addProperty(test, "script", described(stringSchema(), "要测试的脚本草稿，与 channel_id 至少提供一个。"));
        addProperty(test, "channel_id", described(stringSchema(), "要测试的已保存通道 ID。"));
        if (availableTools.contains("test_observation_script")) tools.add(tool("test_observation_script", "执行观测脚本，返回结果或错误堆栈，不修改基线或产生事件。", test));
        JsonObject events = objectSchema("channel_id", described(stringSchema(), "观测通道 ID。"));
        addProperty(events, "event_id", described(stringSchema(), "事件 ID，提供时返回完整明细，否则返回分页预览。"));
        addObservationPageProperties(events);
        if (availableTools.contains("query_observation_events")) tools.add(tool("query_observation_events", "查询当前 Agent 的观测事件及完整明细。", events));
        JsonObject search = objectWithRequired(new String[]{});
        addProperty(search, "keyword", described(stringSchema(), "搜索标题和消息正文；省略或为空时列出最近会话。多个空白分隔关键词须全部匹配。"));
        addProperty(search, "agent", described(stringSchema(), "self 查询自身历次会话（默认），all 查询本实例普通及团队成员会话，也可填写准确 Agent 名称。"));
        JsonObject days = new JsonObject(); days.addProperty("type", "integer");
        days.addProperty("minimum", 0); days.addProperty("maximum", Integer.MAX_VALUE); days.addProperty("default", 7);
        addProperty(search, "days", described(days, "按会话最后更新时间搜索最近多少天；默认 7，0 表示全部历史。活动会话仍参与搜索，避免遗漏未落盘消息。"));
        addProperty(search, "cursor", described(stringSchema(), "原样传入 next_cursor，翻页时保持 keyword、agent 和 days 不变。"));
        if (availableTools.contains("search_sessions")) tools.add(tool("search_sessions", "搜索本实例历史会话，返回 session_ref 和命中 message_id；每页最多 10 个会话，不暴露存储路径。", search));
        JsonObject read = objectSchema("session_ref", described(stringSchema(), "搜索返回的稳定会话引用。"));
        addProperty(read, "message_id", described(stringSchema(), "读取指定消息及前后上下文；省略时读取最近一页，与 cursor 互斥。"));
        addProperty(read, "cursor", described(stringSchema(), "原样传入 before_cursor 或 after_cursor，与 message_id 互斥。"));
        if (availableTools.contains("read_session_history")) tools.add(tool("read_session_history", "分页读取会话历史，每页最多 30 条。工具调用输入完整返回，结果过大时缩略；普通长消息可通过游标继续读取。", read));
        return tools;
    }

    private static void addObservationPageProperties(JsonObject schema) {
        JsonObject integer = new JsonObject(); integer.addProperty("type", "integer"); integer.addProperty("minimum", 1);
        addProperty(schema, "page", described(integer.deepCopy(), "页码，默认 1。"));
        addProperty(schema, "page_size", described(integer.deepCopy(), "每页条数，默认 10，最多 100。"));
        addProperty(schema, "status", described(stringSchema(), "状态筛选。"));
        addProperty(schema, "query", described(stringSchema(), "名称搜索。"));
    }

    private static boolean isActionTool(String name) {
        return "dispatch_subagent".equals(name) || "schedule_task".equals(name)
                || "manage_schedule".equals(name) || "talk_to".equals(name)
                || "new_session".equals(name) || "manage_observation_channels".equals(name)
                || "test_observation_script".equals(name) || "query_observation_events".equals(name)
                || "search_sessions".equals(name) || "read_session_history".equals(name);
    }

    private static JsonObject tool(String name, String description, JsonObject schema) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        tool.add("inputSchema", schema);
        return tool;
    }

    private static JsonObject objectSchema(String requiredName, JsonObject requiredSchema) {
        return objectWithRequired(new String[]{requiredName},
                objectProperty(requiredName, requiredSchema));
    }

    private static JsonObject objectWithRequired(String[] required, JsonObject... properties) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        for (JsonObject property : properties) {
            for (java.util.Map.Entry<String, JsonElement> entry : property.entrySet()) {
                props.add(entry.getKey(), entry.getValue());
            }
        }
        schema.add("properties", props);
        JsonArray req = new JsonArray();
        for (String value : required) req.add(value);
        schema.add("required", req);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static JsonObject objectProperty(String name, JsonObject value) {
        JsonObject property = new JsonObject();
        property.add(name, value);
        return property;
    }

    private static JsonObject schema(String type) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", type);
        return schema;
    }

    private static JsonObject stringSchema() {
        return schema("string");
    }

    private static JsonObject described(JsonObject schema, String description) {
        schema.addProperty("description", description);
        return schema;
    }

    private static JsonObject enumStringSchema(String[] values, String description) {
        JsonObject schema = described(stringSchema(), description);
        JsonArray allowed = new JsonArray();
        for (String value : values) allowed.add(value);
        schema.add("enum", allowed);
        return schema;
    }

    private static JsonObject scheduleSchema() {
        return objectWithRequired(new String[]{"type", "expr"},
                objectProperty("type", enumStringSchema(new String[]{"cron", "once"},
                        "调度类型。cron 表示周期任务，once 表示一次性任务。")),
                objectProperty("expr", described(stringSchema(),
                        "调度表达式。cron 使用标准五位 cron 表达式；once 使用 ISO 时间戳或 "
                                + "+30s、+30m、+2h、+1d 等相对时间。")));
    }

    private static JsonObject arrayOf(JsonObject item) {
        JsonObject schema = schema("array");
        schema.add("items", item);
        schema.addProperty("minItems", 1);
        return schema;
    }

    private static void addProperty(JsonObject objectSchema, String name, JsonObject schema) {
        objectSchema.getAsJsonObject("properties").add(name, schema);
    }

    private static JsonObject toolError(String message) {
        JsonObject payload = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", message == null ? "Tool execution failed" : message);
        content.add(text);
        payload.add("content", content);
        payload.addProperty("isError", true);
        return payload;
    }

    private static JsonObject success(JsonElement id, JsonObject result) {
        JsonObject response = base(id);
        response.add("result", result);
        return response;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject response = base(id);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        return response;
    }

    private static JsonObject base(JsonElement id) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id.deepCopy());
        return response;
    }

    private static String string(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonPrimitive()
                ? object.get(name).getAsString() : "";
    }

    private static byte[] readAll(HttpExchange exchange) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = exchange.getRequestBody().read(buffer)) >= 0) {
            if (out.size() + read > MAX_REQUEST_BYTES) {
                throw new RequestTooLargeException();
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void send(HttpExchange exchange, int status, JsonObject response) throws IOException {
        boolean sse = acceptsSse(exchange.getRequestHeaders());
        String body = response.toString();
        String encoded = sse ? "event: message\ndata: " + body + "\n\n" : body;
        byte[] bytes = encoded.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",
                sse ? "text/event-stream; charset=utf-8" : "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static boolean acceptsSse(Headers headers) {
        String accept = headers.getFirst("Accept");
        return accept != null && accept.contains("text/event-stream")
                && !accept.contains("application/json");
    }

    private static final class RequestTooLargeException extends IOException {
    }
}
