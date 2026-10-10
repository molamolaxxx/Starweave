package com.mola.cmd.proxy.app.acp.sessionquery;

import com.google.gson.*;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Searchable text projection only; never changes raw history or complete tool inputs. */
final class SearchText {
    private static final Pattern DATA_URL = Pattern.compile("(?i)data:[^\\s\"'<>]*?;base64,[A-Za-z0-9+/=_-]+");

    static String clean(String text) { return DATA_URL.matcher(text).replaceAll("[内嵌数据已省略]"); }

    static String extract(JsonElement value) {
        StringBuilder text = new StringBuilder();
        append(value, text);
        return text.toString();
    }

    private static void append(JsonElement value, StringBuilder text) {
        if (value == null || value.isJsonNull()) return;
        if (value.isJsonPrimitive()) { text.append(clean(value.getAsString())).append('\n'); return; }
        if (value.isJsonArray()) { for (JsonElement item : value.getAsJsonArray()) append(item, text); return; }
        JsonObject object = value.getAsJsonObject();
        String type = string(object, "type"), encoding = string(object, "encoding");
        String mime = string(object, "mimeType");
        if (mime.isEmpty()) mime = string(object, "mime_type");
        boolean encoded = "image".equals(type) || "audio".equals(type) || "binary".equals(type)
                || "base64".equals(type) || "base64".equals(encoding)
                || mime.startsWith("image/") || mime.startsWith("audio/") || "application/octet-stream".equals(mime);
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (key.contains("base64") || "bytes".equals(key) || "binary".equals(key) || "blob".equals(key)
                    || encoded && ("data".equals(key) || "content".equals(key) || "blob".equals(key))) continue;
            text.append(entry.getKey()).append('\n');
            append(entry.getValue(), text);
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString().toLowerCase(Locale.ROOT) : "";
    }
}
