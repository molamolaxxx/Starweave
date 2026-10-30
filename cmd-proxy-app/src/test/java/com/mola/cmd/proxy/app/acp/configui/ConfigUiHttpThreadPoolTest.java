package com.mola.cmd.proxy.app.acp.configui;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.Assert.*;

public class ConfigUiHttpThreadPoolTest {
    @Test
    public void missingSettingKeepsDefaultAndExplicitSizesAreAccepted() {
        assertEquals(32, ConfigUiServer.httpThreadPoolSize(null));
        assertEquals(32, ConfigUiServer.httpThreadPoolSize(JSON.parseObject("{}")));
        assertEquals(32, ConfigUiServer.httpThreadPoolSize(JSON.parseObject("{\"configUi\":{}}")));
        for (int size : new int[]{1, 32, 128, 1024}) {
            assertEquals(size, ConfigUiServer.httpThreadPoolSize(config(size)));
        }
    }

    @Test
    public void rejectsInvalidSizesInsteadOfSilentlyChangingCapacity() {
        for (String value : new String[]{"0", "-1", "1025", "1.5", "\"128\"", "true", "2147483648"}) {
            try {
                ConfigUiServer.httpThreadPoolSize(JSON.parseObject(
                        "{\"configUi\":{\"httpThreadPoolSize\":" + value + "}}"));
                fail("accepted " + value);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("httpThreadPoolSize"));
            }
        }
    }

    @Test
    public void startupReadsPersistedCapacityAndKeepsStreamPoolIndependent() throws Exception {
        Path file = CmdProxyHome.resolve("acpConfig.json");
        byte[] previous = Files.exists(file) ? Files.readAllBytes(file) : null;
        ConfigUiServer server = new ConfigUiServer(0, () -> {}, ignored -> {});
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, config(128).toJSONString().getBytes(StandardCharsets.UTF_8));
            server.start();
            ThreadPoolExecutor http = executor(server, "executor");
            assertEquals(128, http.getCorePoolSize());
            assertEquals(128, http.getMaximumPoolSize());
            assertEquals(16, executor(server, "starweaveStreamExecutor").getMaximumPoolSize());
        } finally {
            server.stop();
            if (previous == null) Files.deleteIfExists(file);
            else Files.write(file, previous);
        }
    }

    private static JSONObject config(int size) {
        return JSON.parseObject("{\"configUi\":{\"httpThreadPoolSize\":" + size + "}}");
    }

    private static ThreadPoolExecutor executor(ConfigUiServer server, String name) throws Exception {
        Field field = ConfigUiServer.class.getDeclaredField(name);
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(server);
    }
}
