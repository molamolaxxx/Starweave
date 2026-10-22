package com.mola.cmd.proxy.app.acp.configui;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConfigUiStaticAssetHttpTest {
    private ConfigUiServer server;
    private String baseUrl;

    @Before
    public void startServer() throws Exception {
        server = new ConfigUiServer(0, () -> { }, ignored -> { });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getBoundPort();
    }

    @After
    public void stopServer() {
        if (server != null) server.stop();
    }

    @Test
    public void protectsHtmlAssetsApiAndErrorsAgainstFraming() throws Exception {
        String[] paths = {"/", "/assets/js/core.js", "/assets/unknown.woff2",
                "/api/instances", "/api/instances?instance=missing-frame-test",
                "/api/config?instance=missing-frame-test"};
        int[] statuses = {200, 200, 404, 200, 200, 404};
        for (int i = 0; i < paths.length; i++) {
            HttpURLConnection connection = open(paths[i]);
            try {
                assertEquals(paths[i], statuses[i], connection.getResponseCode());
                assertFrameProtection(connection);
            } finally {
                connection.disconnect();
            }
        }
        HttpURLConnection head = open("/assets/js/core.js");
        head.setRequestMethod("HEAD");
        try {
            assertEquals(200, head.getResponseCode());
            assertFrameProtection(head);
        } finally {
            head.disconnect();
        }
        HttpURLConnection post = open("/assets/js/core.js");
        post.setRequestMethod("POST");
        try {
            assertEquals(405, post.getResponseCode());
            assertFrameProtection(post);
        } finally {
            post.disconnect();
        }
    }

    private static void assertFrameProtection(HttpURLConnection connection) {
        assertEquals("DENY", connection.getHeaderField("X-Frame-Options"));
        assertEquals("frame-ancestors 'none';",
                connection.getHeaderField("Content-Security-Policy"));
    }

    @Test
    public void servesBundledMaterialIconsFontWithImmutableCaching() throws Exception {
        HttpURLConnection connection = open("/assets/MaterialIcons-Regular.woff2");

        assertEquals(200, connection.getResponseCode());
        assertEquals("font/woff2", connection.getHeaderField("Content-Type"));
        assertEquals("nosniff", connection.getHeaderField("X-Content-Type-Options"));
        assertEquals("public, max-age=31536000, immutable",
                connection.getHeaderField("Cache-Control"));
        byte[] body = readAll(connection.getInputStream());
        assertTrue(body.length > 100_000);
        assertArrayEquals("wOF2".getBytes(StandardCharsets.US_ASCII),
                new byte[]{body[0], body[1], body[2], body[3]});
        connection.disconnect();
    }

    @Test
    public void rejectsUnknownStaticAssets() throws Exception {
        HttpURLConnection connection = open("/assets/unknown.woff2");

        assertEquals(404, connection.getResponseCode());
        connection.disconnect();
    }

    @Test
    public void servesStylesAndScriptsWithSafeContentTypesAndRevalidation() throws Exception {
        HttpURLConnection stylesheet = open("/assets/css/base.css");
        assertEquals(200, stylesheet.getResponseCode());
        assertEquals("text/css; charset=utf-8", stylesheet.getHeaderField("Content-Type"));
        assertEquals("no-cache", stylesheet.getHeaderField("Cache-Control"));
        assertEquals("nosniff", stylesheet.getHeaderField("X-Content-Type-Options"));
        assertTrue(new String(readAll(stylesheet.getInputStream()), StandardCharsets.UTF_8)
                .contains("@font-face"));
        stylesheet.disconnect();

        HttpURLConnection script = open("/assets/js/app.js");
        assertEquals(200, script.getResponseCode());
        assertEquals("application/javascript; charset=utf-8",
                script.getHeaderField("Content-Type"));
        assertEquals("no-cache", script.getHeaderField("Cache-Control"));
        assertTrue(new String(readAll(script.getInputStream()), StandardCharsets.UTF_8)
                .contains("async function init"));
        script.disconnect();
    }

    @Test
    public void supportsHeadAndRejectsWritesAndUnlistedPaths() throws Exception {
        HttpURLConnection head = open("/assets/js/core.js");
        head.setRequestMethod("HEAD");
        assertEquals(200, head.getResponseCode());
        assertEquals("application/javascript; charset=utf-8",
                head.getHeaderField("Content-Type"));
        head.disconnect();

        HttpURLConnection post = open("/assets/js/core.js");
        post.setRequestMethod("POST");
        assertEquals(405, post.getResponseCode());
        post.disconnect();

        HttpURLConnection traversal = open("/assets/%2e%2e/index.html");
        assertEquals(404, traversal.getResponseCode());
        traversal.disconnect();
    }

    @Test
    public void exposesEditableProviderModelCatalogEndpoint() throws Exception {
        HttpURLConnection connection = open("/api/provider-models");
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream output = connection.getOutputStream()) {
            output.write(("{\"provider\":\"KIRO_CLI\","
                    + "\"workDir\":\"/workspace/model-catalog-http-test\"}")
                    .getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(200, connection.getResponseCode());
        String body = new String(readAll(connection.getInputStream()), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"success\":true"));
        assertTrue(body.contains("\"models\""));
        connection.disconnect();
    }

    private HttpURLConnection open(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(5_000);
        return connection;
    }

    private byte[] readAll(InputStream input) throws Exception {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = source.read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }
}
