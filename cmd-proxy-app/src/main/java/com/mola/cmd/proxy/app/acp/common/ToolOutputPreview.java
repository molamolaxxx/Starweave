package com.mola.cmd.proxy.app.acp.common;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Bounded presentation copies; never edits the provider's original tool result. */
public final class ToolOutputPreview {
    public static final int MAX_BYTES = 64 * 1024;
    private static final int MAX_TEXT = 8192;
    private static final String OMITTED = "[工具输出已截断，完整内容保留在原始会话历史中]";

    private ToolOutputPreview() { }

    /** Accepts session events, Team events and legacy tool history rows. */
    public static JSONObject row(JSONObject source) {
        JSONObject result = new JSONObject(source);
        String type = source.getString("type");
        if ("TOOL_CALL_UPDATED".equals(type)) {
            result.put("payload", payload(source.getJSONObject("payload")));
        } else if ("TOOL_CALL".equals(type)) {
            String key = source.containsKey("data") ? "data" : "payload";
            result.put(key, payload(source.getJSONObject(key)));
        } else if ("TOOL_CALL".equals(source.getString("eventType"))) {
            result.put("payload", payload(source.getJSONObject("payload")));
        } else if ("TOOL".equals(source.getString("role"))) {
            JSONObject tool = new JSONObject(true);
            tool.put("rawInput", source.get("rawInput"));
            tool.put("rawOutput", source.get("rawOutput"));
            JSONObject preview = payload(tool);
            result.put("rawInput", preview.get("rawInput"));
            result.put("rawOutput", preview.get("rawOutput"));
            if (preview.containsKey("outputTruncated")) {
                result.put("outputTruncated", true);
                result.put("outputNotice", OMITTED);
            }
        }
        return result;
    }

    public static JSONObject payload(JSONObject source) {
        if (source == null) return null;
        Budget budget = new Budget();
        JSONObject ordered = new JSONObject(true);
        // Keep merge identity/status ahead of potentially huge nested results.
        for (String key : new String[]{"toolCallId", "title", "status", "revision", "messageId", "structuredMessages"}) {
            if (source.containsKey(key)) ordered.put(key, source.get(key));
        }
        ordered.putAll(source);
        JSONObject result = (JSONObject) copy(ordered, budget, 0);
        if (budget.truncated) {
            result.put("outputTruncated", true);
            result.put("outputNotice", OMITTED);
        }
        String json = result.toJSONString();
        if (json.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES) return result;
        // Escaped control characters or oversized keys can exceed the character budget.
        JSONObject fallback = new JSONObject(true);
        for (String key : new String[]{"toolCallId", "title", "status", "revision", "messageId", "structuredMessages"}) {
            Object value = source.get(key);
            if (value instanceof String) value = abbreviate((String) value, 1024);
            if (value instanceof String || value instanceof Number || value instanceof Boolean) fallback.put(key, value);
        }
        JSONObject output = new JSONObject(true);
        output.put("preview", abbreviate(json, 4096));
        if (source.containsKey("update")) {
            JSONObject update = new JSONObject(true);
            update.put("rawOutput", output);
            fallback.put("update", update);
        } else {
            fallback.put("rawOutput", output);
        }
        fallback.put("outputTruncated", true);
        fallback.put("outputNotice", OMITTED);
        return fallback;
    }

    private static Object copy(Object value, Budget budget, int depth) {
        if (++budget.nodes > 256 || depth > 12) {
            budget.truncated = true;
            return OMITTED;
        }
        if (value instanceof String) {
            String text = (String) value;
            int available = Math.min(MAX_TEXT, budget.characters);
            if (text.length() <= available) {
                budget.characters -= text.length();
                return text;
            }
            budget.truncated = true;
            budget.characters -= available;
            return abbreviate(text, available);
        }
        if (value instanceof Map) {
            Map<?, ?> object = (Map<?, ?>) value;
            if (("image".equals(object.get("type")) || "audio".equals(object.get("type")))
                    && object.containsKey("data")) {
                budget.truncated = true;
                JSONObject notice = new JSONObject(true);
                notice.put("type", "text");
                notice.put("text", "[内嵌" + ("image".equals(object.get("type")) ? "图片" : "音频")
                        + "数据已省略，完整内容保留在原始会话历史中]");
                return notice;
            }
            JSONObject result = new JSONObject(true);
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                if (budget.nodes >= 256) {
                    budget.truncated = true;
                    result.put("outputNotice", OMITTED);
                    break;
                }
                String key = String.valueOf(entry.getKey());
                if (key.length() > 256) {
                    budget.truncated = true;
                    continue;
                }
                result.put(key, copy(entry.getValue(), budget, depth + 1));
            }
            return result;
        }
        if (value instanceof Iterable) {
            JSONArray result = new JSONArray();
            for (Object item : (Iterable<?>) value) {
                if (budget.nodes >= 256) {
                    budget.truncated = true;
                    result.add(OMITTED);
                    break;
                }
                result.add(copy(item, budget, depth + 1));
            }
            return result;
        }
        return value;
    }

    private static String abbreviate(String text, int limit) {
        if (text.length() <= limit) return text;
        String marker = "\n" + OMITTED + "\n";
        int remaining = Math.max(0, limit - marker.length());
        int head = remaining * 3 / 4;
        int tail = remaining - head;
        // Avoid cutting a UTF-16 surrogate pair at either boundary.
        if (head > 0 && Character.isHighSurrogate(text.charAt(head - 1))) head--;
        int tailStart = text.length() - tail;
        if (tailStart < text.length() && Character.isLowSurrogate(text.charAt(tailStart))) tailStart++;
        return text.substring(0, head) + marker + text.substring(tailStart);
    }

    private static final class Budget {
        private int characters = 24 * 1024;
        private int nodes;
        private boolean truncated;
    }
}
