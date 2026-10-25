package com.mola.cmd.proxy.app.acp.configui;

import com.mola.cmd.proxy.app.acp.registry.RegistryManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import static org.junit.Assert.assertEquals;

public class ConfigUiAutomaticUpdateTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void automaticChecksRemainEnabledByDefault() throws Exception {
        checkScheduled(null, 1);
    }

    @Test public void optOutDoesNotQueueAutomaticDownloads() throws Exception {
        checkScheduled("true", 0);
    }

    private void checkScheduled(String value, int expected) throws Exception {
        String key = "cmd.proxy.disableAutomaticUpdate", previous = System.getProperty(key);
        Path registry = temporary.newFolder("registry").toPath();
        ConfigUiServer server = new ConfigUiServer(0, () -> { }, ignored -> { }) {
            @Override RegistryManager createRegistryManager(int port) throws IOException {
                return new RegistryManager(registry, port, "auto-update-test");
            }
        };
        try {
            if (value == null) System.clearProperty(key); else System.setProperty(key, value);
            server.start();
            Field field = ConfigUiServer.class.getDeclaredField("updateCheckExecutor");
            field.setAccessible(true);
            Object executor = field.get(server);
            Field delegate = executor.getClass().getDeclaredField("e");
            delegate.setAccessible(true);
            ScheduledThreadPoolExecutor scheduled = (ScheduledThreadPoolExecutor) delegate.get(executor);
            assertEquals(expected, scheduled.getQueue().size());
        } finally {
            server.stop();
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }
}
