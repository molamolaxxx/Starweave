package com.mola.cmd.proxy.app.acp.configui;

import com.mola.cmd.proxy.app.acp.registry.RegistryManager;
import com.mola.cmd.proxy.app.acp.registry.RegistryEnvironmentAccess;
import com.mola.cmd.proxy.app.acp.registry.model.RegistryConfig;
import com.sun.net.httpserver.HttpServer;
import okhttp3.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import static org.junit.Assert.*;

public class RegistryEnvironmentAccessHttpTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void protectedRemoteApisRequirePasswordAndForwardSevenDayCookie() throws Exception {
        java.nio.file.Path registryDirectory = temporary.newFolder("registry").toPath();
        ConfigUiServer target = new ConfigUiServer(0, () -> { }, ignored -> { }) {
            @Override RegistryManager createRegistryManager(int boundPort) throws IOException {
                return new RegistryManager(registryDirectory, boundPort, "password-proxy-test");
            }
        };
        HttpServer center = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        EnvironmentHttpProxy proxy = new EnvironmentHttpProxy(); OkHttpClient browser = new OkHttpClient();
        target.start();
        // 只替换测试进程中的内存快照；不写入应用数据目录。
        Field managerField = ConfigUiServer.class.getDeclaredField("registryManager"); managerField.setAccessible(true);
        RegistryManager manager = (RegistryManager) managerField.get(target);
        Field configField = RegistryManager.class.getDeclaredField("config"); configField.setAccessible(true);
        RegistryConfig original = (RegistryConfig) configField.get(manager), config = new RegistryConfig();
        RegistryEnvironmentAccess.setPassword(config, "secret"); configField.set(manager, config);
        center.createContext("/", exchange -> proxy.forward(exchange, target.getBoundPort())); center.start();
        String base = "http://127.0.0.1:" + center.getAddress().getPort();
        try {
            for (String path : new String[]{"/api/config", "/api/starweave/v1/sessions", "/api/registry/settings", "/api/starweave/v1/sessions/resources/download", "/api/starweave/v1/sessions/stream", "/api/starweave/v1/teams/stream"}) {
                try (Response response = browser.newCall(new Request.Builder().url(base + path + "?instance=remote-test").build()).execute()) {
                    assertEquals(path, 401, response.code()); assertTrue(response.body().string().contains("ENVIRONMENT_LOCKED"));
                }
            }
            String direct = "http://127.0.0.1:" + target.getBoundPort();
            for (String path : new String[]{"/api/config", "/api/registry/settings", "/api/starweave/v1/sessions", "/api/starweave/v1/sessions/resources/download", "/api/starweave/v1/sessions/stream", "/api/starweave/v1/teams/stream"}) {
                try (Response response = browser.newCall(new Request.Builder().url(direct + path).build()).execute()) {
                    assertEquals("direct " + path, 401, response.code());
                }
            }
            try (Response response = browser.newCall(new Request.Builder().url(direct + "/api/config?instance=" + com.mola.cmd.proxy.app.utils.CmdProxyHome.instanceId())
                    .header("X-Cmd-Proxy-Proxied", "1").build()).execute()) {
                assertEquals("explicit local identity and proxy header cannot bypass password", 401, response.code());
            }
            try (Response response = browser.newCall(new Request.Builder().url(direct + "/api/registry/access")
                    .post(RequestBody.create(MediaType.parse("application/json"), "{\"password\":\"wrong\"}")).build()).execute()) {
                assertEquals("wrong local password", 401, response.code());
            }
            try (Response response = browser.newCall(new Request.Builder().url(direct + "/api/registry/settings")
                    .post(RequestBody.create(MediaType.parse("application/json"), "{\"accessPassword\":\"\"}")).build()).execute()) {
                assertEquals("anonymous cannot remove local password", 401, response.code());
            }
            try (Response response = browser.newCall(new Request.Builder().url(direct + "/api/registry/access").build()).execute()) {
                assertTrue(response.body().string().contains("\"authenticated\":false"));
            }
            try (Response response = browser.newCall(login(base, "wrong")).execute()) {
                assertEquals(401, response.code()); assertNull(response.header("Set-Cookie"));
            }
            try (Response response = browser.newCall(new Request.Builder().url(base + "/api/starweave/v1/sessions/open?instance=remote-test")
                    .post(RequestBody.create(MediaType.parse("application/json"), "{}")).build()).execute()) {
                assertEquals(401, response.code());
            }
            String cookie;
            try (Response response = browser.newCall(login(base, "secret")).execute()) {
                assertEquals(200, response.code()); cookie = response.header("Set-Cookie");
                assertTrue(cookie.contains("Max-Age=604800")); assertTrue(cookie.contains("HttpOnly"));
                assertTrue(cookie.contains("SameSite=Strict")); cookie = cookie.split(";", 2)[0];
            }
            try (Response response = browser.newCall(new Request.Builder().url(direct + "/api/registry/settings").header("Cookie", cookie).build()).execute()) {
                assertEquals("local login permits local settings", 200, response.code());
                String settings = response.body().string(); assertFalse(settings.contains("accessPasswordHash")); assertFalse(settings.contains("accessSessionKey"));
            }
            Request request = new Request.Builder().url(base + "/api/starweave/v1/sessions?instance=remote-test").header("Cookie", cookie).build();
            try (Response response = browser.newCall(request).execute()) { assertEquals(200, response.code()); }
            try (Response response = browser.newCall(new Request.Builder().url(base + "/api/registry/access?instance=remote-test").header("Cookie", cookie).build()).execute()) {
                assertTrue(response.body().string().contains("\"authenticated\":true"));
            }
            try (Response response = browser.newCall(login(base, "secret").newBuilder().header("Origin", "https://attacker.invalid").build()).execute()) {
                assertEquals(403, response.code()); assertNull(response.header("Set-Cookie"));
            }
            try (Response response = browser.newCall(request.newBuilder().header("Origin", "https://attacker.invalid").build()).execute()) {
                assertEquals(403, response.code());
            }
            try (Response response = browser.newCall(login(base, "secret").newBuilder().header("Origin", base.replace("http://", "https://")).build()).execute()) {
                assertEquals(200, response.code()); assertTrue(response.header("Set-Cookie").contains("; Secure"));
            }
            RegistryConfig changed = new RegistryConfig(); RegistryEnvironmentAccess.setPassword(changed, "changed"); configField.set(manager, changed);
            try (Response response = browser.newCall(request).execute()) { assertEquals(401, response.code()); }
            RegistryEnvironmentAccess.setPassword(changed, "");
            try (Response response = browser.newCall(request.newBuilder().removeHeader("Cookie").build()).execute()) { assertEquals(200, response.code()); }
        } finally {
            configField.set(manager, original); proxy.close(); center.stop(0); target.stop();
            browser.connectionPool().evictAll(); browser.dispatcher().executorService().shutdownNow();
        }
    }
    private Request login(String base, String password) {
        return new Request.Builder().url(base + "/api/registry/access?instance=remote-test").header("Origin", base)
                .post(RequestBody.create(MediaType.parse("application/json"), "{\"password\":\"" + password + "\"}")).build();
    }
}
