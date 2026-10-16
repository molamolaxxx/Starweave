package com.mola.cmd.proxy.app.acp.remote;

import com.google.gson.*;
import com.mola.starweave.sdk.*;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Runtime-scoped routes expose only explicitly injected loopback MCP endpoints. */
final class RemoteMcpProxy implements AutoCloseable {
    static final String PREFIX = "/api/remote-acp/mcp/";
    private final ConcurrentMap<String, Route> routes = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(0, 64, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> { Thread t = new Thread(r, "remote-mcp-proxy"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());
    String expose(String publicBase, String target, BooleanSupplier alive) {
        URI uri = URI.create(target); String token = UUID.randomUUID().toString();
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        routes.put(token, new Route(origin, uri.getPath(), alive));
        return publicBase + PREFIX + token + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }
    void handle(HttpExchange exchange) {
        try { executor.execute(() -> forward(exchange)); }
        catch (RejectedExecutionException e) { try { exchange.sendResponseHeaders(503, -1); } catch (IOException ignored) { } exchange.close(); }
    }
    private void forward(HttpExchange exchange) {
        HttpURLConnection connection = null; Route route = null;
        try {
            if (!Arrays.asList("GET", "POST", "DELETE").contains(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            String path = exchange.getRequestURI().getRawPath(); String rest = path.substring(PREFIX.length()); int slash = rest.indexOf('/');
            String token = slash < 0 ? rest : rest.substring(0, slash); route = routes.get(token);
            if (route == null || !route.alive.getAsBoolean()) { exchange.sendResponseHeaders(410, -1); return; }
            String suffix = slash < 0 ? "/" : rest.substring(slash);
            URI target = URI.create(route.origin + suffix + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery()));
            if (!(target.getScheme() + "://" + target.getRawAuthority()).equals(route.origin)) { exchange.sendResponseHeaders(400, -1); return; }
            if (!route.paths.contains(normalized(target.getPath()))) { exchange.sendResponseHeaders(403, -1); return; }
            connection = (HttpURLConnection) target.toURL().openConnection(Proxy.NO_PROXY); route.active.add(connection);
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(5000); connection.setReadTimeout(125000); connection.setRequestMethod(exchange.getRequestMethod());
            for (Map.Entry<String, List<String>> h : exchange.getRequestHeaders().entrySet()) if (allowedHeader(h.getKey())) for (String value : h.getValue()) connection.addRequestProperty(h.getKey(), value);
            if (Arrays.asList("POST", "PUT", "PATCH").contains(exchange.getRequestMethod())) {
                connection.setDoOutput(true); connection.setChunkedStreamingMode(8192);
                try (InputStream in = exchange.getRequestBody(); OutputStream out = connection.getOutputStream()) { copy(in, out, 32L * 1024 * 1024); }
            }
            int status = connection.getResponseCode(); String contentType = connection.getContentType();
            for (Map.Entry<String, List<String>> h : connection.getHeaderFields().entrySet()) if (h.getKey() != null && allowedHeader(h.getKey())) exchange.getResponseHeaders().put(h.getKey(), h.getValue());
            if (status == 204 || status == 202) { exchange.sendResponseHeaders(status, -1); return; }
            exchange.sendResponseHeaders(status, 0);
            InputStream response = status >= 400 ? connection.getErrorStream() : connection.getInputStream(); if (response == null) return;
            try (InputStream in = response; OutputStream out = exchange.getResponseBody()) {
                if (contentType != null && contentType.contains("text/event-stream")) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)); String line; boolean endpoint = false;
                    while ((line = reader.readLine()) != null && route.alive.getAsBoolean()) {
                        if (line.startsWith("event:")) endpoint = line.substring(6).trim().equals("endpoint");
                        if (endpoint && line.startsWith("data:")) {
                            URI location = target.resolve(line.substring(5).trim());
                            if (!(location.getScheme() + "://" + location.getRawAuthority()).equals(route.origin)) throw new IOException("MCP endpoint changed origin");
                            route.paths.add(normalized(location.getPath()));
                            line = "data: " + PREFIX + token + location.getRawPath() + (location.getRawQuery() == null ? "" : "?" + location.getRawQuery());
                        }
                        if (line.isEmpty()) endpoint = false;
                        out.write((line + "\n").getBytes(StandardCharsets.UTF_8)); out.flush();
                    }
                } else copy(in, out, 32L * 1024 * 1024);
            }
        } catch (Exception e) { try { exchange.sendResponseHeaders(502, -1); } catch (IOException ignored) { } }
        finally { if (connection != null) { connection.disconnect(); if (route != null) route.active.remove(connection); } exchange.close(); }
    }
    void cleanup() { routes.entrySet().removeIf(e -> { Route r = e.getValue(); if (r.alive.getAsBoolean()) return false; for (HttpURLConnection c : r.active) c.disconnect(); return true; }); }
    private static String normalized(String path) { try { return new URI(null, null, path, null).normalize().getPath(); } catch (URISyntaxException e) { throw new IllegalArgumentException("Invalid MCP path"); } }
    private static boolean allowedHeader(String name) { return !Arrays.asList("host", "connection", "transfer-encoding", "content-length", "keep-alive", "upgrade", "proxy-authorization", "proxy-authenticate").contains(name.toLowerCase(Locale.ROOT)); }
    private static void copy(InputStream in, OutputStream out, long maximum) throws IOException { byte[] b = new byte[8192]; long total = 0; int n; while ((n = in.read(b)) != -1) { total += n; if (total > maximum) throw new IOException("MCP payload exceeds limit"); out.write(b, 0, n); out.flush(); } }
    public void close() { for (Route r : routes.values()) for (HttpURLConnection c : r.active) c.disconnect(); routes.clear(); executor.shutdownNow(); }
    private static final class Route { final String origin; final BooleanSupplier alive; final Set<HttpURLConnection> active = ConcurrentHashMap.newKeySet(); final Set<String> paths = ConcurrentHashMap.newKeySet(); Route(String origin, String path, BooleanSupplier alive) { this.origin = origin; this.alive = alive; paths.add(normalized(path)); } }
}
