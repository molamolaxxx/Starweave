package com.mola.starweave.sdk;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Versioned, length-prefixed UTF-8 frames. Never logs payloads or model credentials. */
public final class Wire {
    public static final int VERSION = 1, MAX_FRAME = 32 * 1024 * 1024;
    private Wire() { }
    public static JsonObject frame(String type, String runtimeId) {
        JsonObject o = new JsonObject(); o.addProperty("type", type);
        if (runtimeId != null) o.addProperty("runtimeId", runtimeId); return o;
    }
    public static String str(JsonObject o, String key) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : ""; }
    public static void write(OutputStream out, JsonObject frame) throws IOException {
        byte[] bytes = frame.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FRAME) throw new IOException("Remote ACP frame exceeds limit");
        synchronized (out) { DataOutputStream d = new DataOutputStream(out); d.writeInt(bytes.length); d.write(bytes); d.flush(); }
    }
    public static JsonObject read(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in); int n = d.readInt();
        if (n < 2 || n > MAX_FRAME) throw new IOException("Invalid remote ACP frame length");
        byte[] b = new byte[n]; d.readFully(b);
        try { return JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException("Invalid remote ACP frame", e); }
    }
}
