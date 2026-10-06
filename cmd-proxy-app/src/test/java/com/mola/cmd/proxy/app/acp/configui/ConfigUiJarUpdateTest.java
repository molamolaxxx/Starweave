package com.mola.cmd.proxy.app.acp.configui;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ConfigUiJarUpdateTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void calculatesJarMd5() throws Exception {
        Path jar = temporaryFolder.newFile("cmd-proxy.jar").toPath();
        Files.write(jar, "cmd-proxy".getBytes(StandardCharsets.UTF_8));

        assertEquals("82b563451085c152b928663de3b17097", ConfigUiServer.calculateMd5(jar));
    }

    @Test
    public void acceptsMd5sumSidecarFormat() throws Exception {
        assertEquals("3ce350537e03467611917b523a7fcb38",
                ConfigUiServer.parseMd5(
                        "3CE350537E03467611917B523A7FCB38  /tmp/cmd-proxy.jar\n"));
    }

    @Test(expected = IOException.class)
    public void rejectsInvalidRemoteMd5() throws Exception {
        ConfigUiServer.parseMd5("not-an-md5");
    }

    @Test
    public void successiveUpdatesReuseWorkerAndInstallLatestPendingJar() throws Exception {
        Path running = writeJar("running.jar", "A");
        List<Path> workers = new ArrayList<>();
        WindowsJarUpdate update = new WindowsJarUpdate(running, workers::add);
        assertEquals(running, update.comparisonJar());

        update.stage(writeJar("download-b.jar", "B"));
        Path pending = update.comparisonJar();
        assertEquals(ConfigUiServer.calculateMd5(writeJar("remote-b.jar", "B")),
                ConfigUiServer.calculateMd5(pending));
        update.stage(writeJar("download-c.jar", "C"));
        update.stage(writeJar("download-d.jar", "D"));

        assertEquals(pending, update.comparisonJar());
        assertEquals(1, workers.size());
        assertEquals("A", readJar(running));
        assertEquals("D", readJar(pending));
        String script = readJar(workers.get(0));
        assertTrue(script.contains("set \"NEW=" + pending + "\""));
        // Model the worker's successful move after Windows releases the running JAR.
        Files.move(pending, running, StandardCopyOption.REPLACE_EXISTING);
        assertEquals("D", readJar(running));
        assertEquals(running, update.comparisonJar());
    }

    @Test
    public void failedRestagingKeepsPreviousPendingJar() throws Exception {
        Path running = writeJar("running.jar", "A");
        AtomicInteger workers = new AtomicInteger();
        WindowsJarUpdate update = new WindowsJarUpdate(running, script -> workers.incrementAndGet());
        update.stage(writeJar("download-b.jar", "B"));
        try {
            update.stage(temporaryFolder.getRoot().toPath().resolve("missing.jar"));
            fail("Missing download must fail");
        } catch (IOException expected) {
            assertEquals("B", readJar(update.comparisonJar()));
            assertEquals(1, workers.get());
        }
    }

    @Test
    public void failedWorkerLaunchCleansUpAndCanRetry() throws Exception {
        Path running = writeJar("running.jar", "A");
        List<Path> scripts = new ArrayList<>();
        WindowsJarUpdate update = new WindowsJarUpdate(running, script -> {
            scripts.add(script);
            if (scripts.size() == 1) throw new IOException("launch failed");
        });
        try {
            update.stage(writeJar("download-b.jar", "B"));
            fail("Launch must fail");
        } catch (IOException expected) {
            assertEquals(running, update.comparisonJar());
            assertFalse(Files.exists(scripts.get(0)));
            assertEquals(1, temporaryFolder.getRoot().list().length);
        }
        update.stage(writeJar("download-c.jar", "C"));
        assertEquals("C", readJar(update.comparisonJar()));
        assertEquals(2, scripts.size());
    }

    private Path writeJar(String name, String content) throws IOException {
        Path path = temporaryFolder.newFile(name).toPath();
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private String readJar(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
