package com.mola.cmd.proxy.app.acp.team.coordinator;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Two independent JVMs, production HTTP routes and real Netty reverse tunnel. */
public class RegistryMixedTeamIntegrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void mixedTeamWorksThroughRegistryWithoutMolaChat() throws Exception {
        int centerPort = port(), remotePort = port(), tunnelPort = port();
        Path centerHome = temporary.newFolder("center").toPath(), remoteHome = temporary.newFolder("remote").toPath();
        Process center = start("center", centerPort, String.valueOf(tunnelPort), centerHome);
        Process remote = null;
        try {
            await(() -> request(centerPort, "GET", "/api/registry/settings", null).getString("serverStatus").equals("RUNNING"), 20000);
            remote = start("remote", remotePort, "http://127.0.0.1:" + centerPort, remoteHome);
            await(() -> sources(centerPort).size() >= 3, 45000);
            assertEquals("UNAUTHORIZED", request(centerPort, "POST", RegistryTeamCoordinator.CENTER_PATH,
                    object("operation", "sources")).getString("code"));
            assertEquals("UNAUTHORIZED", request(remotePort, "POST", RegistryTeamCoordinator.PARTICIPANT_PATH,
                    object("operation", "describe")).getString("code"));
            JSONArray sources = sources(centerPort); JSONObject local = null, distant = null;
            for (Object value : sources) {
                JSONObject source = (JSONObject) value;
                if ("remote".equals(source.getString("cmdProxyInstanceId"))) distant = source;
                if ("center".equals(source.getString("cmdProxyInstanceId")) && !source.getBooleanValue("coordinated")) local = source;
            }
            assertNotNull(local); assertNotNull(distant);
            JSONObject create = object("requestId", "integration-create"); create.put("name", "Registry team");
            local.put("teamMemberId", "m1"); distant.put("teamMemberId", "m2");
            JSONArray members = new JSONArray(); members.add(local); members.add(distant); create.put("members", members);
            JSONObject created = request(centerPort, "POST", "/api/starweave/v1/teams/create", create);
            assertTrue(created.toJSONString(), created.getBooleanValue("accepted"));
            String teamId = created.getJSONObject("data").getJSONObject("data").getJSONObject("team").getString("teamId");
            await(() -> "READY".equals(team(centerPort, teamId).getString("state")), 20000);
            JSONObject send = object("teamId", teamId); send.put("teamMemberId", "m2"); send.put("coordinated", true);
            send.put("sessionId", team(centerPort, teamId).getJSONArray("members").getJSONObject(1).getString("sessionId"));
            send.put("action", "send"); send.put("requestId", "integration-send"); send.put("message", "hello-remote");
            String originalSession = send.getString("sessionId");
            JSONObject upload = MixedTeamCoordinator.copy(send); upload.put("fileName", "hello.txt"); upload.put("contentBase64", "SGVsbG8=");
            JSONObject staged = request(centerPort, "POST", "/api/starweave/v1/teams/uploads", upload);
            assertTrue(staged.toJSONString(), staged.getBooleanValue("accepted"));
            JSONArray uploadIds = new JSONArray(); uploadIds.add(staged.getJSONObject("data").getString("uploadId")); send.put("uploadIds", uploadIds);
            assertTrue(request(centerPort, "POST", "/api/starweave/v1/teams/member", send).getBooleanValue("accepted"));
            await(() -> {
                String delivered = events(centerPort, teamId).toJSONString();
                return delivered.contains("echo:hello-remote") && delivered.contains("tool-test")
                        && delivered.contains("attachments:hello.txt=SGVsbG8=") && delivered.contains("MESSAGE_COMPLETE");
            }, 15000);
            JSONObject status = object("teamId", teamId); status.put("teamMemberId", "m2"); status.put("coordinated", true); status.put("action", "status");
            assertTrue(request(centerPort, "POST", "/api/starweave/v1/teams/member", status).getBooleanValue("accepted"));
            status.put("action", "history");
            assertTrue(request(centerPort, "POST", "/api/starweave/v1/teams/member", status).toJSONString().contains("echo:hello-remote"));
            status.put("action", "context");
            assertTrue(request(centerPort, "POST", "/api/starweave/v1/teams/member", status).getBooleanValue("accepted"));
            JSONObject talk = object("teamId", teamId); talk.put("teamMemberId", "m1"); talk.put("coordinated", true);
            talk.put("sessionId", team(centerPort, teamId).getJSONArray("members").getJSONObject(0).getString("sessionId"));
            talk.put("action", "send"); talk.put("requestId", "integration-talk"); talk.put("message", "talk:Agent");
            // Members share display names; the target is selected by stable member ID below.
            talk.put("message", "talk:m2");
            assertTrue(request(centerPort, "POST", "/api/starweave/v1/teams/member", talk).getBooleanValue("accepted"));
            await(() -> events(centerPort, teamId).toJSONString().contains("coordination-test"), 15000);
            JSONObject rotate = object("teamId", teamId); rotate.put("teamMemberId", "m2"); rotate.put("coordinated", true); rotate.put("action", "newSession");
            assertTrue(request(centerPort, "POST", "/api/starweave/v1/teams/member", rotate).getBooleanValue("accepted"));
            await(() -> !originalSession.equals(team(centerPort, teamId).getJSONArray("members").getJSONObject(1).getString("sessionId")), 15000);
            status.put("action", "listSessions");
            assertTrue(request(centerPort, "POST", "/api/starweave/v1/teams/member", status).toJSONString().contains(originalSession));
            JSONObject restore = MixedTeamCoordinator.copy(rotate); restore.put("action", "restore"); restore.put("sessionId", originalSession);
            assertTrue(request(centerPort, "POST", "/api/starweave/v1/teams/member", restore).getBooleanValue("accepted"));
            await(() -> originalSession.equals(team(centerPort, teamId).getJSONArray("members").getJSONObject(1).getString("sessionId")), 15000);
            stop(remote); remote = null;
            await(() -> "RECOVERING".equals(team(centerPort, teamId).getString("state")), 20000);
            remote = start("remote", remotePort, "http://127.0.0.1:" + centerPort, remoteHome);
            await(() -> "READY".equals(team(centerPort, teamId).getString("state")), 45000);
            stop(center); center = start("center", centerPort, String.valueOf(tunnelPort), centerHome);
            await(() -> "READY".equals(team(centerPort, teamId).getString("state")), 45000);
            JSONObject deletion = object("teamId", teamId); deletion.put("coordinated", true); deletion.put("requestId", "integration-delete");
            assertTrue(request(centerPort, "POST", "/api/starweave/v1/teams/delete", deletion).getBooleanValue("accepted"));
            await(() -> team(centerPort, teamId) == null, 20000);

            // The initiating home may itself be a remote registry participant.
            await(() -> sources(remotePort).size() >= 3, 20000);
            JSONObject fromRemote = object("requestId", "remote-home-create"); fromRemote.put("name", "Remote home team");
            JSONArray reversed = new JSONArray();
            for (Object value : sources(remotePort)) {
                JSONObject source = (JSONObject) value;
                if ("remote".equals(source.getString("cmdProxyInstanceId")) && !source.getBooleanValue("coordinated")) {
                    source.put("teamMemberId", "r1"); reversed.add(0, source);
                } else if ("center".equals(source.getString("cmdProxyInstanceId"))) {
                    source.put("teamMemberId", "r2"); reversed.add(source);
                }
            }
            assertEquals(2, reversed.size()); fromRemote.put("members", reversed);
            JSONObject remoteCreated = request(remotePort, "POST", "/api/starweave/v1/teams/create", fromRemote);
            assertTrue(remoteCreated.toJSONString(), remoteCreated.getBooleanValue("accepted"));
            String remoteTeamId = remoteCreated.getJSONObject("data").getJSONObject("data").getJSONObject("team").getString("teamId");
            await(() -> "READY".equals(team(remotePort, remoteTeamId).getString("state")), 20000);
            JSONObject remoteSend = object("teamId", remoteTeamId); remoteSend.put("teamMemberId", "r2");
            remoteSend.put("sessionId", team(remotePort, remoteTeamId).getJSONArray("members").getJSONObject(1).getString("sessionId"));
            remoteSend.put("action", "send"); remoteSend.put("requestId", "remote-home-send"); remoteSend.put("message", "from-remote-home");
            // Deliberately omit the client hint: binding determines coordination server-side.
            assertTrue(request(remotePort, "POST", "/api/starweave/v1/teams/member", remoteSend).getBooleanValue("accepted"));
            await(() -> events(remotePort, remoteTeamId).toJSONString().contains("echo:from-remote-home"), 15000);
            assertTrue(request(remotePort, "POST", "/api/starweave/v1/teams/delete", object("teamId", remoteTeamId)).getBooleanValue("accepted"));
            await(() -> team(remotePort, remoteTeamId) == null, 20000);
        } finally {
            Path diagnostics = java.nio.file.Paths.get("/tmp/starweave-registry-integration-last");
            java.nio.file.Files.createDirectories(diagnostics);
            java.nio.file.Files.write(diagnostics.resolve("classpath.txt"), System.getProperty("java.class.path").getBytes(StandardCharsets.UTF_8));
            for (Path home : new Path[]{centerHome, remoteHome}) {
                Path log = home.resolve("node.log");
                if (java.nio.file.Files.exists(log)) java.nio.file.Files.copy(log,
                        diagnostics.resolve(home.getFileName().toString() + ".log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            stop(remote); stop(center);
        }
    }
    private Process start(String id, int port, String target, Path home) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp", System.getProperty("java.class.path"),
                RegistryTeamTestNode.class.getName(), String.valueOf(port), target);
        builder.environment().put("CMD_PROXY_HOME", home.toString()); builder.environment().put("CMD_PROXY_INSTANCE_ID", id);
        builder.redirectErrorStream(true); builder.redirectOutput(home.resolve("node.log").toFile()); return builder.start();
    }
    private static void stop(Process process) throws Exception {
        if (process == null) return; process.destroy(); if (!process.waitFor(8, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
    }
    private static int port() throws IOException { try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static JSONArray sources(int port) throws Exception { return request(port, "GET", "/api/starweave/v1/teams/sources", null).getJSONObject("data").getJSONArray("sources"); }
    private static JSONObject team(int port, String id) throws Exception {
        JSONArray teams = request(port, "GET", "/api/starweave/v1/teams", null).getJSONObject("data").getJSONObject("data").getJSONArray("teams");
        for (Object value : teams) if (id.equals(((JSONObject) value).getString("teamId"))) return (JSONObject) value;
        return null;
    }
    private static JSONArray events(int port, String team) throws Exception { return request(port, "GET", "/api/starweave/v1/teams/events?teamId=" + team, null).getJSONObject("data").getJSONArray("events"); }
    private static JSONObject request(int port, String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(1000); connection.setReadTimeout(15000); connection.setRequestMethod(method);
        try {
            if (body != null) { connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json"); try (OutputStream output = connection.getOutputStream()) { output.write(body.toJSONString().getBytes(StandardCharsets.UTF_8)); } }
            InputStream input = connection.getResponseCode() < 400 ? connection.getInputStream() : connection.getErrorStream();
            try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) { byte[] bytes = new byte[8192]; int n; while ((n = source.read(bytes)) >= 0) output.write(bytes, 0, n); return JSON.parseObject(new String(output.toByteArray(), StandardCharsets.UTF_8)); }
        } finally { connection.disconnect(); }
    }
    private static JSONObject object(String key, Object value) { return MixedTeamCoordinator.object(key, value); }
    private interface Check { boolean test() throws Exception; }
    private static void await(Check check, long timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout; Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try { if (check.test()) return; } catch (Exception retry) { last = retry; }
            Thread.sleep(100);
        }
        throw new AssertionError("condition timed out", last);
    }
}
