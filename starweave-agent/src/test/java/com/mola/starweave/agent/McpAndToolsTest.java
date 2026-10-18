package com.mola.starweave.agent;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class McpAndToolsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private AgentConfig config() { return new AgentConfig("http://127.0.0.1/v1", "key", "model", temp.getRoot().toPath(), 16000, 1024, System.getenv()); }
    @Test(timeout=10000) public void stdioDiscoveryInvocationAndClose() throws Exception {
        JsonObject server = Json.obj("name", "stdio", "command", Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "args", Json.array("-cp", System.getProperty("java.class.path"), StdioFixture.class.getName()), "env", Json.array());
        try (McpConnection connection = new McpConnection(config(), server, temp.getRoot().toPath(), new Cancellation())) {
            assertEquals("stdio_echo", Json.str(connection.discover(new Cancellation()).get(0).getAsJsonObject(), "name"));
            assertTrue(connection.invoke("stdio_echo", Json.obj("text", "中文"), new Cancellation()).toString().contains("中文"));
        }
    }
    public static class StdioFixture {
        public static void main(String[] args) throws Exception {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)); String line;
            PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
            while ((line = in.readLine()) != null) {
                JsonObject request = Json.parse(line); if (!request.has("id")) continue;
                String method = Json.str(request, "method"); JsonObject result;
                if (method.equals("initialize")) result = Json.obj("protocolVersion", "2025-06-18", "capabilities", Json.obj());
                else if (method.equals("tools/list")) result = Json.obj("tools", Json.array(Json.obj("name", "stdio_echo", "inputSchema", Json.obj("type", "object"))));
                else result = Tools.result(Json.str(Json.object(Json.object(request, "params"), "arguments"), "text"));
                out.println(Json.GSON.toJson(Json.obj("jsonrpc", "2.0", "id", request.get("id"), "result", result)));
            }
        }
    }
    @Test(timeout=10000) public void legacySseUsesHeadersAndEndpointAndCloses() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); ExecutorService pool = Executors.newCachedThreadPool(); server.setExecutor(pool);
        BlockingQueue<OutputStream> streams = new ArrayBlockingQueue<>(1); CountDownLatch done = new CountDownLatch(1);
        server.createContext("/sse", e -> {
            assertEquals("sse-key", e.getRequestHeaders().getFirst("X-Test")); e.getResponseHeaders().set("Content-Type", "text/event-stream"); e.sendResponseHeaders(200, 0);
            try { OutputStream out = e.getResponseBody(); out.write("event: endpoint\ndata: /messages\n\n".getBytes(StandardCharsets.UTF_8)); out.flush(); streams.put(out); done.await(8, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } finally { e.close(); }
        });
        server.createContext("/messages", e -> {
            assertEquals("sse-key", e.getRequestHeaders().getFirst("X-Test")); JsonObject request = Json.parse(Http.read(e.getRequestBody(), 1000000));
            if (request.has("id")) {
                JsonObject result = Json.str(request, "method").equals("initialize") ? Json.obj("protocolVersion", "2024-11-05", "capabilities", Json.obj())
                        : Json.str(request, "method").equals("tools/list") ? Json.obj("tools", Json.array(Json.obj("name", "sse_echo", "inputSchema", Json.obj("type", "object")))) : Tools.result("SSE result");
                try { OutputStream out = streams.peek(); assertNotNull(out); synchronized (out) { out.write(("event: message\ndata: " + Json.GSON.toJson(Json.obj("jsonrpc", "2.0", "id", request.get("id"), "result", result)) + "\n\n").getBytes(StandardCharsets.UTF_8)); out.flush(); } }
                catch (Exception error) { throw new IOException(error); }
            }
            e.sendResponseHeaders(202, -1); e.close();
        });
        server.start();
        try {
            JsonObject spec = Json.obj("name", "sse", "type", "sse", "url", "http://127.0.0.1:" + server.getAddress().getPort() + "/sse", "headers", Json.array(Json.obj("name", "X-Test", "value", "sse-key")));
            try (McpConnection connection = new McpConnection(config(), spec, temp.getRoot().toPath(), new Cancellation())) { assertEquals(1, connection.discover(new Cancellation()).size()); assertTrue(connection.invoke("sse_echo", Json.obj(), new Cancellation()).toString().contains("SSE result")); done.countDown(); }
        } finally { done.countDown(); server.stop(0); pool.shutdownNow(); }
    }
    @Test(timeout=10000) public void shellTimeoutAndCancellationAreBounded() throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        try (Tools tools = new Tools(config(), temp.getRoot().toPath(), Json.array(), new Cancellation())) {
            Tools.Execute shell = tools.tools.get("shell").execute;
            JsonObject result = shell.run(Json.obj("command", windows ? "Write-Output '中文'; Start-Sleep -Seconds 60" : "printf '中文'; sleep 60", "timeout", 1), new Cancellation(), update -> { });
            assertTrue(Json.object(result, "details").get("timedOut").getAsBoolean()); assertTrue(result.toString().contains("中文"));
            Cancellation cancel = new Cancellation(); ExecutorService executor = Executors.newSingleThreadExecutor(); CountDownLatch started = new CountDownLatch(1);
            try {
                Future<JsonObject> pending = executor.submit(() -> shell.run(Json.obj("command", windows ? "Write-Output started; Start-Sleep -Seconds 60" : "echo started; sleep 60"), cancel, update -> started.countDown()));
                assertTrue(started.await(2, TimeUnit.SECONDS)); cancel.cancel();
                try { pending.get(3, TimeUnit.SECONDS); fail(); } catch (ExecutionException e) { assertTrue(e.getCause() instanceof CancellationException); }
            } finally { cancel.cancel(); executor.shutdownNow(); }
        }
    }
    @Test(timeout=5000) public void shellReturnsEvenWhenKillAndPipeCloseBlock() throws Exception {
        for (int mode : new int[] { 0, 1, 2 }) {
            final boolean abort = mode == 1, exited = mode == 2;
            CountDownLatch release = new CountDownLatch(1), reading = new CountDownLatch(1);
            Process stuck = new Process() {
                final InputStream input = new InputStream() {
                    @Override public int read() { reading.countDown(); awaitRelease(); return -1; }
                    @Override public void close() { awaitRelease(); }
                };
                void awaitRelease() { try { release.await(); } catch (InterruptedException ignored) { } }
                @Override public InputStream getInputStream() { return input; }
                @Override public InputStream getErrorStream() { return input; }
                @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
                @Override public int waitFor() { awaitRelease(); return 0; }
                @Override public boolean waitFor(long n, TimeUnit unit) throws InterruptedException { return exited || release.await(n, unit); }
                @Override public int exitValue() { if (!exited && release.getCount() > 0) throw new IllegalThreadStateException(); return 0; }
                @Override public void destroy() { awaitRelease(); }
            };
            Cancellation cancel = new Cancellation(); ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<JsonObject> pending = pool.submit(() -> Tools.executeShell(stuck, 1, cancel, update -> { }, false, null));
                assertTrue(reading.await(1, TimeUnit.SECONDS));
                if (abort) {
                    long start = System.nanoTime(); cancel.cancel();
                    assertTrue("cancel caller must not wait for process cleanup", System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(500));
                    try { pending.get(1, TimeUnit.SECONDS); fail("expected cancellation"); }
                    catch (ExecutionException e) { assertTrue(e.getCause() instanceof CancellationException); }
                } else {
                    JsonObject details = Json.object(pending.get(exited ? 1 : 2, TimeUnit.SECONDS), "details");
                    assertEquals(!exited, details.get("timedOut").getAsBoolean());
                }
            } finally { release.countDown(); cancel.cancel(); pool.shutdownNow(); }
        }
    }
    @Test public void windowsShellEncodingAndProxyIsolation() throws Exception {
        String command = "Write-Output \"中文 $x `test`\"";
        List<String> argv = Tools.shellInvocation(command, true, Collections.singletonMap("SystemRoot", "C:\\Windows"));
        assertTrue(new String(Base64.getDecoder().decode(argv.get(argv.size()-1)), StandardCharsets.UTF_16LE).endsWith(command));
        Map<String, String> env = new HashMap<>(); env.put("HTTPS_PROXY", "http://proxy.example:8080"); env.put("NO_PROXY", ".internal");
        AgentConfig c = new AgentConfig("https://example.com", "key", "model", temp.getRoot().toPath(), 16000, 1024, env);
        assertEquals(Proxy.NO_PROXY, c.proxy(new URL("https://127.0.0.1/test"))); assertEquals(Proxy.NO_PROXY, c.proxy(new URL("https://service.internal/test")));
        assertEquals(Proxy.Type.HTTP, c.proxy(new URL("https://external.example/test")).type());
    }
}
