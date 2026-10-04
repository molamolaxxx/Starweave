package com.mola.cmd.proxy.app.acp.configui;

import static org.junit.Assert.*;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.observation.*;
import com.mola.cmd.proxy.app.acp.registry.RegistryManager;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleOwnerKey;

import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** The test process is started with an explicit temporary CMD_PROXY_HOME. */
public class ObservationAdminHttpTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void managesTestsAndServesWhitelistedAssets() throws Exception {
        try (ObservationManager manager =
                new ObservationManager(temporary.newFolder("observations").toPath())) {
            ScheduleOwnerKey owner = ScheduleOwnerKey.main("assistant");
            manager.register(
                    this,
                    owner,
                    temporary.newFolder("workspace").getAbsolutePath(),
                    () -> true,
                    prompt -> false);
            ObservationAdminApiBridge.install(manager);
            ConfigUiServer server =
                    new ConfigUiServer(0, () -> {}, ignored -> {}) {
                        @Override
                        RegistryManager createRegistryManager(int boundPort) throws IOException {
                            return new RegistryManager(
                                    temporary.getRoot().toPath().resolve("registry"),
                                    boundPort,
                                    "observation-http-test");
                        }
                    };
            try {
                server.start();
                int port = server.getBoundPort();
                assertEquals(200, request(port, "GET", "/assets/js/observations.js", null).status);
                assertEquals(
                        200, request(port, "GET", "/assets/css/observations.css", null).status);
                assertTrue(
                        request(port, "GET", "/api/observations/v1/owners", null)
                                .body
                                .contains("assistant"));
                assertEquals(
                        400,
                        request(
                                        port,
                                        "POST",
                                        "/api/observations/v1/channels",
                                        "{\"name\":\"bad\",\"script\":\"x\",\"ownerPath\":\"unknown\"}")
                                .status);
                Response created =
                        request(
                                port,
                                "POST",
                                "/api/observations/v1/channels",
                                "{\"name\":\"Jira\",\"script\":\"module.exports=()=>"
                                        + " 'result'\",\"eventAction\":\"核验并汇总变更\",\"ownerPath\":\"assistant\"}");
                assertEquals(200, created.status);
                assertEquals("核验并汇总变更", JsonParser.parseString(created.body).getAsJsonObject()
                        .get("eventAction").getAsString());
                assertEquals(400, request(port, "POST", "/api/observations/v1/channels",
                        "{\"name\":\"missing-action\",\"script\":\"module.exports=()=> 'x'\",\"ownerPath\":\"assistant\"}").status);
                assertEquals("30s", JsonParser.parseString(created.body).getAsJsonObject()
                        .get("frequency").getAsString());
                String id =
                        JsonParser.parseString(created.body)
                                .getAsJsonObject()
                                .get("id")
                                .getAsString();
                Response list = request(port, "GET", "/api/observations/v1/channels", null);
                assertTrue(list.body.contains("Jira"));
                assertFalse(list.body.contains("module.exports"));
                assertFalse(list.body.contains("eventAction"));
                assertEquals(400, request(port, "PUT", "/api/observations/v1/channels?channelId=" + id,
                        "{\"eventAction\":\"\"}").status);
                assertEquals(200, request(port, "PUT", "/api/observations/v1/channels?channelId=" + id,
                        "{\"eventAction\":\"新的处理指令\"}").status);
                assertTrue(request(port, "GET", "/api/observations/v1/channels?channelId=" + id, null)
                        .body.contains("新的处理指令"));
                Response tested =
                        request(
                                port,
                                "POST",
                                "/api/observations/v1/test",
                                "{\"ownerPath\":\"assistant\",\"channel_id\":\"" + id + "\"}");
                assertEquals(200, tested.status);
                assertTrue(tested.body.contains("\"result\":\"result\""));
                assertFalse(manager.get(id, null).has("baseline"));
                assertTrue(
                        request(port, "GET", "/api/observations/v1/events", null)
                                .body
                                .contains("\"total\":0"));
                assertEquals(405, request(port, "POST", "/api/observations/v1/stats", "{}").status);
                assertEquals(
                        400,
                        request(
                                        port,
                                        "PUT",
                                        "/api/observations/v1/channels?channelId=" + id,
                                        "{\"frequency\":\"0s\"}")
                                .status);
                assertEquals(
                        200,
                        request(
                                        port,
                                        "PUT",
                                        "/api/observations/v1/channels?channelId=" + id,
                                        "{\"enabled\":false}")
                                .status);
                assertEquals("PAUSED", manager.get(id, null).get("status").getAsString());
                assertEquals(
                        200,
                        request(
                                        port,
                                        "DELETE",
                                        "/api/observations/v1/channels?channelId=" + id,
                                        null)
                                .status);
                assertEquals(0, manager.stats().get("total").getAsInt());
            } finally {
                server.stop();
                ObservationAdminApiBridge.clear(manager);
            }
        }
    }

    private static Response request(int port, String method, String path, String body)
            throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod(method);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in =
                status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
            if (in != null) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
        }
        connection.disconnect();
        return new Response(status, new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    private static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
