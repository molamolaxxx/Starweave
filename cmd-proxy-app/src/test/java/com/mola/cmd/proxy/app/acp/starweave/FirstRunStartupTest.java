package com.mola.cmd.proxy.app.acp.starweave;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Real application entrypoint in isolated JVMs; no model or developer configuration is used. */
public class FirstRunStartupTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test(timeout = 90000)
    public void freshEnvironmentsExposeEmptyApisAndConnectThroughCenter() throws Exception {
        try (Node center = start("center", null); Node remote = start("remote", null)) {
            for (Node node : new Node[]{center, remote}) {
                assertEquals(200, request(node.port, "GET", "/api/starweave/v1/teams", null).status);
                assertEquals(200, request(node.port, "GET", "/api/starweave/v1/teams/sources", null).status);
                assertEquals(200, request(node.port, "GET", "/api/starweave/v1/sessions", null).status);
                assertEquals(200, request(node.port, "GET", "/api/observations/v1/channels", null).status);
                assertEquals(200, request(node.port, "GET", "/api/observations/v1/owners", null).status);
                assertEquals(200, request(node.port, "GET", "/api/starweave/v1/tasks", null).status);
            }
            int tunnelPort;
            try (ServerSocket socket = new ServerSocket(0)) { tunnelPort = socket.getLocalPort(); }
            assertEquals(202, request(center.port, "POST", "/api/registry/settings",
                    "{\"serverEnabled\":true,\"tunnelPort\":" + tunnelPort + "}").status);
            awaitStatus(center, "serverStatus", "RUNNING");
            assertEquals(202, request(remote.port, "POST", "/api/registry/settings",
                    "{\"clientEnabled\":true,\"centerUrl\":\"http://127.0.0.1:" + center.port + "\"}").status);
            awaitStatus(remote, "clientStatus", "REGISTERED");
            // Team discovery through the actual center must also accept an empty participant.
            assertEquals(200, request(center.port, "GET", "/api/starweave/v1/teams/sources", null).status);
            assertEquals(200, request(remote.port, "GET", "/api/starweave/v1/teams/sources", null).status);
        }
    }

    @Test(timeout = 60000)
    public void teamOnlyAndDisabledRobotsStillStartEnvironmentServices() throws Exception {
        String config = "{\"robots\":[{\"name\":\"Team\",\"enabled\":true,\"onlyTeamMember\":true},"
                + "{\"name\":\"Off\",\"enabled\":false}],\"chatterIds\":[],\"configUi\":{\"enabled\":true}}";
        try (Node node = start("team-only", config)) {
            assertEquals(200, request(node.port, "GET", "/api/starweave/v1/teams/sources", null).status);
            assertEquals(200, request(node.port, "GET", "/api/observations/v1/channels", null).status);
        }
        try (Node node = start("disabled", "{\"robots\":[{\"name\":\"Off\",\"enabled\":false}]}")) {
            assertEquals(200, request(node.port, "GET", "/api/starweave/v1/teams", null).status);
            assertEquals(200, request(node.port, "GET", "/api/observations/v1/channels", null).status);
        }
    }

    @Test(timeout = 60000)
    public void embeddedSdkRegistersIntoRealApplicationAndOpensRemoteSession() throws Exception {
        String initial = "{\"robots\":[],\"chatterIds\":[],\"remoteAcp\":{\"port\":0},\"configUi\":{\"enabled\":true}}";
        com.sun.net.httpserver.HttpServer model = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/chat/completions", exchange -> {
            while (exchange.getRequestBody().read() != -1) { }
            byte[] response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"done\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        }); model.start();
        try (Node node = start("sdk-server", initial);
             com.mola.starweave.sdk.StarcoreClient sdk = com.mola.starweave.sdk.StarcoreClient.builder()
                     .starweaveUrl("http://127.0.0.1:" + node.port).name("sdk-app")
                     .workspace(temporary.newFolder("business-workspace").toPath())
                     .dataDirectory(temporary.newFolder("business-data").toPath()).start()) {
            JSONObject configuration = null; long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < end) {
                configuration = JSON.parseObject(request(node.port, "GET", "/api/config", null).body);
                if (configuration.getJSONArray("robots").size() == 1) break;
                Thread.sleep(50);
            }
            assertNotNull(configuration); assertEquals(1, configuration.getJSONArray("robots").size());
            JSONObject robot = configuration.getJSONArray("robots").getJSONObject(0);
            assertEquals("REMOTE_STARCORE_ACP", robot.getString("agentProvider")); assertTrue(robot.getBooleanValue("enabled"));
            robot.put("model", "test-model"); robot.getJSONObject("embeddedPi").put("baseUrl", "http://127.0.0.1:" + model.getAddress().getPort()); robot.getJSONObject("embeddedPi").put("apiKey", "test-key");
            assertEquals(200, request(node.port, "POST", "/api/config", configuration.toJSONString()).status);
            assertTrue(sdk.awaitReady(15, TimeUnit.SECONDS));
            end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15); boolean discovered = false;
            while (System.nanoTime() < end) {
                if (request(node.port, "GET", "/api/starweave/v1/teams/sources", null).body.contains("sdk-app")) { discovered = true; break; }
                Thread.sleep(50);
            }
            assertTrue("remote source not registered in real runtime", discovered);
            Response opened = request(node.port, "POST", "/api/starweave/v1/sessions/open", "{\"robotName\":\"sdk-app\",\"requestId\":\"sdk-open\"}");
            assertEquals(opened.body, 200, opened.status); assertTrue(opened.body, JSON.parseObject(opened.body).getBooleanValue("accepted"));
            sdk.close(); end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < end) {
                configuration = JSON.parseObject(request(node.port, "GET", "/api/config", null).body);
                if (!configuration.getJSONArray("robots").getJSONObject(0).getBooleanValue("enabled")) break;
                Thread.sleep(50);
            }
            assertFalse(configuration.getJSONArray("robots").getJSONObject(0).getBooleanValue("enabled"));
            configuration.getJSONArray("robots").clear();
            assertEquals(200, request(node.port, "POST", "/api/config", configuration.toJSONString()).status);
            end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); boolean removed = false;
            while (System.nanoTime() < end) {
                if (!request(node.port, "GET", "/api/starweave/v1/teams/sources", null).body.contains("sdk-app")) { removed = true; break; }
                Thread.sleep(50);
            }
            assertTrue("deleted remote agent remained a team source", removed);
        } finally { model.stop(0); }
    }

    private Node start(String name, String config) throws Exception {
        Path home = temporary.newFolder(name).toPath();
        if (config != null) Files.write(home.resolve("acpConfig.json"), config.getBytes(StandardCharsets.UTF_8));
        Path log = home.resolve("startup.log");
        ProcessBuilder builder = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + home, "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                StartupProcess.class.getName());
        builder.directory(home.toFile());
        builder.environment().put("CMD_PROXY_HOME", home.toString());
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        Process process = builder.start();
        Node node = new Node(process, log);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try {
            while (process.isAlive() && System.nanoTime() < deadline) {
                String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
                for (String line : output.split("\\R")) if (line.startsWith("READY=")) {
                    node.port = Integer.parseInt(line.substring(6));
                    return node;
                }
                Thread.sleep(100);
            }
            throw new AssertionError("Application failed to become ready: " + new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        } catch (Throwable failure) { node.close(); throw failure; }
    }

    private void awaitStatus(Node node, String key, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
        JSONObject last = null;
        do {
            last = JSON.parseObject(request(node.port, "GET", "/api/registry/settings", null).body);
            if (expected.equals(last.getString(key))) return;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        fail("Registry did not reach " + expected + ": " + last);
    }

    private static Response request(int port, String method, String path, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(3000); connection.setReadTimeout(5000); connection.setRequestMethod(method);
        try {
            if (body != null) {
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream output = connection.getOutputStream()) { output.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (input != null) try (InputStream stream = input) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            Response response = new Response(); response.status = status;
            response.body = new String(output.toByteArray(), StandardCharsets.UTF_8); return response;
        } finally { connection.disconnect(); }
    }

    private static class Response { int status; String body; }
    private static class Node implements AutoCloseable {
        final Process process; final Path log; int port;
        Node(Process process, Path log) { this.process = process; this.log = log; }
        public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    public static class StartupProcess {
        public static void main(String[] args) throws Exception {
            com.mola.cmd.proxy.app.MainKt.main(new String[]{"acp"});
            Path config = Paths.get(System.getenv("CMD_PROXY_HOME"), "acpConfig.json");
            JSONObject settings = JSON.parseObject(new String(Files.readAllBytes(config), StandardCharsets.UTF_8));
            System.out.println("READY=" + settings.getJSONObject("configUi").getIntValue("port"));
        }
    }
}
