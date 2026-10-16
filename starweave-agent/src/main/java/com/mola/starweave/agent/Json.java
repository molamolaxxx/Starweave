package com.mola.starweave.agent;

import com.google.gson.*;

/** Internal JSON helpers; wire contracts stay plain JSON, independent of Starweave app classes. */
final class Json {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    static JsonObject obj(Object... pairs) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) o.add((String) pairs[i], value(pairs[i + 1]));
        return o;
    }
    static JsonElement value(Object v) { return v instanceof JsonElement ? (JsonElement) v : GSON.toJsonTree(v); }
    static JsonArray array(Object... values) { JsonArray a = new JsonArray(); for (Object v : values) a.add(value(v)); return a; }
    static String str(JsonObject o, String key) { return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : ""; }
    static int integer(JsonObject o, String key, int fallback) { return o != null && o.has(key) ? o.get(key).getAsInt() : fallback; }
    static JsonObject object(JsonObject o, String key) { return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject(); }
    static JsonArray list(JsonObject o, String key) { return o != null && o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray(); }
    static JsonObject parse(String s) { return JsonParser.parseString(s).getAsJsonObject(); }
    static JsonObject text(String s) { return obj("type", "text", "text", s); }
    private Json() { }
}
