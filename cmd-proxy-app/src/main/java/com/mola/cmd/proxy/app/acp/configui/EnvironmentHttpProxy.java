package com.mola.cmd.proxy.app.acp.configui;

import com.sun.net.httpserver.HttpExchange;
import okhttp3.*;
import okio.BufferedSink;
import java.io.*;
import java.net.URLDecoder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** 本机与注册环境共用的字节流代理，不解释业务内容、不重放修改请求。 */
public final class EnvironmentHttpProxy implements AutoCloseable {
    public static final String PROXY_HEADER = "X-Cmd-Proxy-Proxied";
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).build();
    private final Set<Call> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public void forward(HttpExchange exchange, int port) throws IOException {
        String query = stripInstanceParam(exchange.getRequestURI().getRawQuery());
        String url = "http://127.0.0.1:" + port + exchange.getRequestURI().getRawPath() + (query.isEmpty() ? "" : "?" + query);
        Request.Builder request = new Request.Builder().url(url);
        Set<String> removed = hopHeaders(exchange.getRequestHeaders());
        // 保留浏览器访问的 Host，使经过本机或远程隧道转发的 Origin 校验仍匹配。
        removed.add("content-length"); removed.add(PROXY_HEADER.toLowerCase(Locale.ROOT));
        for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
            if (!removed.contains(header.getKey().toLowerCase(Locale.ROOT)))
                for (String value : header.getValue()) request.addHeader(header.getKey(), value);
        }
        request.header(PROXY_HEADER, "1");
        // 环境进程或隧道重启后端口不变；每次使用新 HTTP 连接，避免复用已失效连接。
        // 写请求仍不重试，不能用自动重放掩盖连接失效。
        request.header("Connection", "close");
        // 禁用 OkHttp 自动 gzip 解压，原样保留响应的字节与编码。
        if (exchange.getRequestHeaders().getFirst("Accept-Encoding") == null) request.header("Accept-Encoding", "identity");
        String method = exchange.getRequestMethod();
        RequestBody body = null;
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            body = new RequestBody() {
                @Override public MediaType contentType() {
                    String type = exchange.getRequestHeaders().getFirst("Content-Type"); return type == null ? null : MediaType.parse(type);
                }
                @Override public long contentLength() {
                    String length = exchange.getRequestHeaders().getFirst("Content-Length");
                    try { return length == null ? -1 : Long.parseLong(length); } catch (NumberFormatException e) { return -1; }
                }
                @Override public void writeTo(BufferedSink sink) throws IOException {
                    try (InputStream input = exchange.getRequestBody()) { byte[] buffer = new byte[8192]; int n; while ((n = input.read(buffer)) >= 0) sink.write(buffer, 0, n); }
                }
            };
        }
        Call call = client.newCall(request.method(method, body).build());
        active.add(call);
        boolean headersSent = false;
        try {
            if (closed) throw new IOException("环境代理已停止");
            try (Response response = call.execute()) {
                Map<String, List<String>> responseHeaders = response.headers().toMultimap();
                Set<String> skip = hopHeaders(responseHeaders); skip.add("content-length");
                for (Map.Entry<String, List<String>> header : responseHeaders.entrySet())
                    if (!skip.contains(header.getKey().toLowerCase(Locale.ROOT))) exchange.getResponseHeaders().put(header.getKey(), new ArrayList<>(header.getValue()));
                // Upstream headers must not weaken this public server's frame protection.
                FrameProtectionHeaders.apply(exchange);
                ResponseBody responseBody = response.body();
                boolean empty = "HEAD".equals(method) || response.code() == 204 || response.code() == 304 || responseBody == null || responseBody.contentLength() == 0;
                if ("HEAD".equals(method) && response.header("Content-Length") != null)
                    exchange.getResponseHeaders().set("Content-Length", response.header("Content-Length"));
                exchange.sendResponseHeaders(response.code(), empty ? -1 : 0); headersSent = true;
                if (!empty) {
                    boolean stream = response.header("Content-Type", "").toLowerCase(Locale.ROOT).startsWith("text/event-stream");
                    try (InputStream input = responseBody.byteStream(); OutputStream output = exchange.getResponseBody()) {
                        byte[] buffer = new byte[8192]; int n;
                        while ((n = input.read(buffer)) >= 0) { output.write(buffer, 0, n); if (stream) output.flush(); }
                    }
                }
            }
        } catch (IOException e) {
            if (!headersSent) {
                byte[] error = "{\"ok\":false,\"error\":\"目标环境连接失败或已离线\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(502, error.length);
                try (OutputStream output = exchange.getResponseBody()) { output.write(error); }
            }
        } finally { active.remove(call); call.cancel(); exchange.close(); }
    }
    public static String stripInstanceParam(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return "";
        StringJoiner result = new StringJoiner("&");
        for (String pair : rawQuery.split("&")) {
            String key = pair.split("=", 2)[0];
            try { if ("instance".equals(URLDecoder.decode(key, "UTF-8"))) continue; }
            catch (UnsupportedEncodingException e) { throw new AssertionError(e); }
            if (!pair.isEmpty()) result.add(pair);
        }
        return result.toString();
    }
    private static Set<String> hopHeaders(Map<String, List<String>> headers) {
        Set<String> result = new HashSet<>(Arrays.asList("connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade"));
        for (Map.Entry<String, List<String>> header : headers.entrySet()) if ("connection".equalsIgnoreCase(header.getKey()))
            for (String values : header.getValue()) for (String value : values.split(",")) result.add(value.trim().toLowerCase(Locale.ROOT));
        return result;
    }
    @Override public void close() {
        closed = true; for (Call call : active) call.cancel();
        client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow();
    }
}
