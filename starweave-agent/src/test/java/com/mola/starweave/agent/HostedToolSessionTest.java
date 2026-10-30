package com.mola.starweave.agent;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class HostedToolSessionTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private AgentConfig config() { return new AgentConfig("http://localhost/v1", "key", "model", temp.getRoot().toPath(), 16000, 1000, Collections.emptyMap()); }
    @Test public void cancellationBeforeWorkerStartsPreventsMutationAndDuplicateNeverRunsAgain() throws Exception {
        Path target = temp.getRoot().toPath().resolve("note.txt");
        try (HostedToolSession tools = new HostedToolSession(config(), temp.getRoot().toPath(), new JsonArray())) {
            tools.cancel("cancelled");
            try { tools.execute("cancelled", "write", Json.obj("path", "note.txt", "content", "cancelled"), update -> {}); fail(); }
            catch (CancellationException expected) { }
            assertFalse(Files.exists(target));
            tools.execute("once", "write", Json.obj("path", "note.txt", "content", "first"), update -> {});
            try { tools.execute("once", "write", Json.obj("path", "note.txt", "content", "second"), update -> {}); fail(); }
            catch (java.io.IOException expected) { }
            assertEquals("first", new String(Files.readAllBytes(target), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    @Test public void binaryRelayUsesWorkspaceAndEnforcesAggregateLimitAndReadScope() throws Exception {
        Path workspace = temp.newFolder("workspace").toPath(); byte[] data = new byte[] {0, 1, (byte)255}; Files.write(workspace.resolve("data.bin"), data);
        AgentConfig config = new AgentConfig("http://localhost/v1", "key", "model", workspace, 16000, 1000, Collections.emptyMap(), AgentToolPolicy.workspace(workspace, workspace.resolve("skills"), true, true));
        try (HostedToolSession tools = new HostedToolSession(config, workspace, new JsonArray())) {
            JsonObject file = tools.readFiles(Json.array("data.bin")).getAsJsonArray("files").get(0).getAsJsonObject();
            assertEquals("data.bin", Json.str(file, "name")); assertArrayEquals(data, Base64.getDecoder().decode(Json.str(file, "dataBase64")));
            Path outside = temp.newFile("outside.txt").toPath();
            try { tools.readFiles(Json.array(outside.toString())); fail(); } catch (java.io.IOException expected) { }
            try (java.io.RandomAccessFile huge = new java.io.RandomAccessFile(workspace.resolve("huge.bin").toFile(), "rw")) { huge.setLength(8 * 1024 * 1024 + 1); }
            try { tools.readFiles(Json.array("huge.bin")); fail(); } catch (java.io.IOException expected) { }
        }
    }
    @Test(timeout=10000) public void closeCancelsMcpInitializationWithoutWaitingOnItsLock() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService pool = Executors.newCachedThreadPool(); server.setExecutor(pool);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        server.createContext("/mcp", e -> { entered.countDown(); try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } finally { e.close(); } }); server.start();
        JsonArray servers = Json.array(Json.obj("name", "blocked", "type", "http", "url", "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"));
        HostedToolSession tools = new HostedToolSession(config(), temp.getRoot().toPath(), servers);
        try {
            Future<JsonObject> preparing = pool.submit(tools::describe); assertTrue(entered.await(3, TimeUnit.SECONDS));
            long start = System.nanoTime(); tools.close();
            assertTrue("close blocked behind MCP initialization", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
            try { preparing.get(3, TimeUnit.SECONDS); fail(); } catch (ExecutionException expected) { }
        } finally { release.countDown(); tools.close(); server.stop(0); pool.shutdownNow(); }
    }
}
