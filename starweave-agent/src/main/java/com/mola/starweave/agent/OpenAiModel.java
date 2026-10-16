package com.mola.starweave.agent;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** Port of Pi's streaming completion/agent message boundary, scoped to OpenAI compatible APIs. */
final class OpenAiModel {
    interface Delta { void accept(boolean thought, String text) throws Exception; }
    static final class Completion {
        final JsonObject message; final String finish; final int tokens;
        Completion(JsonObject message, String finish, int tokens) { this.message = message; this.finish = finish; this.tokens = tokens; }
    }
    private final AgentConfig config;
    OpenAiModel(AgentConfig config) { this.config = config; }
    Completion complete(String model, JsonArray messages, JsonArray tools, Cancellation cancel, Delta delta) throws Exception {
        cancel.check();
        JsonObject request = Json.obj("model", model, "messages", messages, "max_tokens", config.maxTokens, "stream", true,
                "stream_options", Json.obj("include_usage", true));
        if (tools.size() > 0) request.add("tools", tools);
        Map<String, String> headers = new HashMap<>(); headers.put("Authorization", "Bearer " + config.apiKey);
        headers.put("Accept", "text/event-stream, application/json");
        String endpoint = config.baseUrl.endsWith("/chat/completions") ? config.baseUrl : config.baseUrl + "/chat/completions";
        HttpURLConnection connection = Http.open(config, endpoint, "POST", headers, 120000);
        try (AutoCloseable hook = cancel.onCancel(connection::disconnect)) {
            Http.send(connection, request);
            InputStream in = Http.checked(connection);
            Accumulator accumulator = new Accumulator(delta);
            if (String.valueOf(connection.getContentType()).toLowerCase(Locale.ROOT).contains("text/event-stream")) {
                Http.sse(in, cancel, (event, data) -> { if (!"[DONE]".equals(data)) accumulator.add(Json.parse(data)); });
            } else accumulator.add(Json.parse(Http.read(in, 32 * 1024 * 1024)));
            cancel.check();
            return accumulator.result();
        } finally { connection.disconnect(); }
    }
    private static final class Accumulator {
        final Delta sink;
        final StringBuilder content = new StringBuilder(), thought = new StringBuilder();
        final TreeMap<Integer, JsonObject> calls = new TreeMap<>();
        final Map<Integer, StringBuilder> args = new HashMap<>();
        String finish; int tokens;
        Accumulator(Delta sink) { this.sink = sink; }
        void add(JsonObject chunk) throws Exception {
            if (chunk.has("error")) throw new IOException(Json.GSON.toJson(chunk.get("error")));
            JsonObject usage = Json.object(chunk, "usage");
            if (usage.has("total_tokens")) tokens = usage.get("total_tokens").getAsInt();
            for (JsonElement e : Json.list(chunk, "choices")) {
                JsonObject choice = e.getAsJsonObject(); if (Json.integer(choice, "index", 0) != 0) continue;
                JsonObject d = choice.has("delta") ? Json.object(choice, "delta") : Json.object(choice, "message");
                String text = Json.str(d, "content");
                if (!text.isEmpty()) { content.append(text); sink.accept(false, text); }
                String reasoning = Json.str(d, "reasoning_content");
                if (reasoning.isEmpty()) reasoning = Json.str(d, "reasoning");
                if (!reasoning.isEmpty()) { thought.append(reasoning); sink.accept(true, reasoning); }
                int ordinal = 0;
                for (JsonElement tool : Json.list(d, "tool_calls")) {
                    JsonObject part = tool.getAsJsonObject(); int index = Json.integer(part, "index", ordinal++);
                    JsonObject call = calls.computeIfAbsent(index, k -> Json.obj("id", "", "type", "function", "function", Json.obj("name", "", "arguments", "")));
                    if (!Json.str(part, "id").isEmpty()) call.addProperty("id", Json.str(call, "id") + Json.str(part, "id"));
                    JsonObject f = Json.object(part, "function"), out = call.getAsJsonObject("function");
                    if (!Json.str(f, "name").isEmpty()) out.addProperty("name", Json.str(out, "name") + Json.str(f, "name"));
                    StringBuilder a = args.computeIfAbsent(index, k -> new StringBuilder()); a.append(Json.str(f, "arguments"));
                    if (a.length() > 16 * 1024 * 1024) throw new IOException("工具参数过大");
                    out.addProperty("arguments", a.toString());
                }
                if (!Json.str(choice, "finish_reason").isEmpty()) finish = Json.str(choice, "finish_reason");
                if (content.length() + thought.length() > 32 * 1024 * 1024) throw new IOException("模型回复过大");
            }
        }
        Completion result() throws IOException {
            if (finish == null) throw new IOException("模型流未正常结束");
            JsonObject message = Json.obj("role", "assistant", "content", content.toString());
            if (thought.length() > 0) message.addProperty("reasoning_content", thought.toString());
            if (!calls.isEmpty()) {
                JsonArray tools = new JsonArray();
                for (JsonObject call : calls.values()) {
                    if (Json.str(call, "id").isEmpty() || Json.str(call.getAsJsonObject("function"), "name").isEmpty()) throw new IOException("模型返回无效工具调用");
                    tools.add(call);
                }
                message.add("tool_calls", tools);
            }
            return new Completion(message, finish, tokens);
        }
    }
}
