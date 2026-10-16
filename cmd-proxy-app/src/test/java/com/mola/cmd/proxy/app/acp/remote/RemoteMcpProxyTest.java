package com.mola.cmd.proxy.app.acp.remote;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class RemoteMcpProxyTest {
    @Test(timeout=10000) public void rewritesSsePostEndpointRestrictsPathsAndRevokesClosedRuntime() throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer front = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService threads = Executors.newCachedThreadPool(); target.setExecutor(threads); front.setExecutor(threads);
        AtomicBoolean alive = new AtomicBoolean(true); RemoteMcpProxy proxy = new RemoteMcpProxy();
        target.createContext("/sse", e -> {
            byte[] b = "event: endpoint\ndata: /messages?session=one\n\n".getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "text/event-stream"); e.sendResponseHeaders(200, b.length); e.getResponseBody().write(b); e.close();
        });
        target.createContext("/messages", e -> { assertEquals("session=one", e.getRequestURI().getRawQuery()); assertEquals("Bearer scoped", e.getRequestHeaders().getFirst("Authorization")); byte[] b = "posted".getBytes(StandardCharsets.UTF_8); e.sendResponseHeaders(200, b.length); e.getResponseBody().write(b); e.close(); });
        front.createContext(RemoteMcpProxy.PREFIX, proxy::handle); target.start(); front.start();
        try {
            String base = "http://127.0.0.1:" + front.getAddress().getPort();
            String url = proxy.expose(base, "http://127.0.0.1:" + target.getAddress().getPort() + "/sse", alive::get);
            HttpURLConnection get = (HttpURLConnection) new URL(url).openConnection(); get.setReadTimeout(3000);
            String endpoint;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(get.getInputStream(), StandardCharsets.UTF_8))) {
                assertEquals("event: endpoint", reader.readLine()); endpoint = reader.readLine().substring("data: ".length()); assertTrue(endpoint.startsWith(RemoteMcpProxy.PREFIX));
            } finally { get.disconnect(); }
            HttpURLConnection post = (HttpURLConnection) new URL(base + endpoint).openConnection(); post.setRequestMethod("POST"); post.setRequestProperty("Authorization", "Bearer scoped"); post.setDoOutput(true); post.setReadTimeout(3000);
            post.getOutputStream().close(); assertEquals(200, post.getResponseCode()); post.getInputStream().close(); post.disconnect();
            String privateUrl = url.substring(0, url.length() - 4) + "/private";
            HttpURLConnection denied = (HttpURLConnection) new URL(privateUrl).openConnection(); assertEquals(403, denied.getResponseCode()); denied.disconnect();
            alive.set(false); proxy.cleanup();
            HttpURLConnection revoked = (HttpURLConnection) new URL(url).openConnection(); assertEquals(410, revoked.getResponseCode()); revoked.disconnect();
        } finally { proxy.close(); target.stop(0); front.stop(0); threads.shutdownNow(); }
    }
}
