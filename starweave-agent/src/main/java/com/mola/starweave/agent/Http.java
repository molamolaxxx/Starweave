package com.mola.starweave.agent;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** JDK-only HTTP/SSE. Connections and cancellation belong to a request, never JVM globals. */
final class Http {
    interface Event { void accept(String event, String data) throws Exception; }
    static HttpURLConnection open(AgentConfig config, String url, String method, Map<String, String> headers, int timeout) throws IOException {
        URL target = new URL(url);
        if (!target.getProtocol().equals("http") && !target.getProtocol().equals("https")) throw new IOException("Unsupported HTTP URL");
        HttpURLConnection c = (HttpURLConnection) target.openConnection(config.proxy(target));
        c.setRequestMethod(method); c.setConnectTimeout(Math.min(timeout, 15000)); c.setReadTimeout(timeout);
        c.setInstanceFollowRedirects(false);
        for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        return c;
    }
    static void send(HttpURLConnection c, JsonObject body) throws IOException {
        byte[] bytes = Json.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json"); c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
    }
    static InputStream checked(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            InputStream error = c.getErrorStream();
            throw new IOException("HTTP " + code + ": " + (error == null ? "请求失败" : read(error, 65536)));
        }
        return c.getInputStream();
    }
    static String read(InputStream stream, int limit) throws IOException {
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) >= 0) { if (out.size() + n > limit) throw new IOException("响应超过大小上限"); out.write(buffer, 0, n); }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    static void sse(InputStream stream, Cancellation cancel, Event consumer) throws Exception {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String event = "message"; StringBuilder data = new StringBuilder(); String line;
            while ((line = boundedLine(in, 16 * 1024 * 1024)) != null) {
                cancel.check();
                if (line.isEmpty()) {
                    if (data.length() > 0) consumer.accept(event, data.substring(0, data.length() - 1));
                    data.setLength(0); event = "message";
                } else if (line.startsWith("data:")) {
                    String part = line.substring(5); if (part.startsWith(" ")) part = part.substring(1);
                    if (data.length() + part.length() > 16 * 1024 * 1024) throw new IOException("SSE event too large");
                    data.append(part).append('\n');
                } else if (line.startsWith("event:")) event = line.substring(6).trim();
            }
            if (data.length() > 0) consumer.accept(event, data.substring(0, data.length() - 1));
        }
    }
    static String boundedLine(Reader in, int limit) throws IOException {
        StringBuilder s = new StringBuilder(); int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (s.length() >= limit) throw new IOException("协议行超过大小上限");
            s.append((char) c);
        }
        if (c == -1 && s.length() == 0) return null;
        if (s.length() > 0 && s.charAt(s.length() - 1) == '\r') s.setLength(s.length() - 1);
        return s.toString();
    }
    private Http() { }
}
