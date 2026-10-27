package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;

/** A complete JSON-RPC prompt error response, distinct from transport failure. */
final class AcpPromptResponseException extends IOException {
    private final boolean retryableRequestFailure;

    AcpPromptResponseException(JsonElement error) {
        super("ACP prompt 返回错误: " + error);
        retryableRequestFailure = isRetryable(error, 0);
    }

    boolean isRetryableRequestFailure() { return retryableRequestFailure; }

    private static boolean isRetryable(JsonElement value, int depth) {
        if (value == null || depth > 8 || value.isJsonNull()) return false;
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            for (String key : new String[]{"code", "status", "statusCode", "type"}) {
                JsonElement field = object.get(key);
                if (field == null || !field.isJsonPrimitive()) continue;
                String code = field.getAsString();
                if ("429".equals(code) || "500".equals(code) || "502".equals(code)
                        || "503".equals(code) || "504".equals(code)
                        || "SERVICE_UNAVAILABLE".equals(code)
                        || "RATE_LIMIT_EXCEEDED".equals(code)) return true;
            }
            for (java.util.Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (isRetryable(entry.getValue(), depth + 1)) return true;
            }
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String text = value.getAsString().trim();
            // Some ACP adapters embed the upstream error JSON in the message field.
            int start = text.indexOf('{');
            if (start >= 0 && text.endsWith("}")) {
                try { return isRetryable(JsonParser.parseString(text.substring(start)), depth + 1); }
                catch (RuntimeException ignored) { /* Unknown errors require runtime recovery. */ }
            }
        }
        return false;
    }
}
