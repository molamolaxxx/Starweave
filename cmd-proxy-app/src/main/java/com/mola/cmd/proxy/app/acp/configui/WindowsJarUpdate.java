package com.mola.cmd.proxy.app.acp.configui;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** One pending file and one replacement worker for all updates in this process. */
final class WindowsJarUpdate {
    interface Launcher {
        void launch(Path script) throws IOException;
    }

    private final Path jar;
    private final Path pending;
    private final Path script;
    private final Launcher launcher;
    private boolean started;

    WindowsJarUpdate(Path jar, Launcher launcher) {
        this.jar = jar.toAbsolutePath();
        String id = UUID.randomUUID().toString();
        this.pending = this.jar.resolveSibling(".cmd-proxy-update-" + id + ".new.jar");
        this.script = this.jar.resolveSibling(".cmd-proxy-update-replace-" + id + ".bat");
        this.launcher = launcher;
    }

    Path comparisonJar() {
        return started && Files.isRegularFile(pending) ? pending : jar;
    }

    void stage(Path verifiedDownload) throws IOException {
        Files.move(verifiedDownload, pending, StandardCopyOption.REPLACE_EXISTING);
        if (started) {
            return;
        }
        try {
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(script))) {
                pw.println("@echo off");
                pw.println("set \"OLD=" + jar + "\"");
                pw.println("set \"NEW=" + pending + "\"");
                pw.println("set \"SELF=" + script + "\"");
                pw.println(":wait");
                pw.println("ping 127.0.0.1 -n 2 >nul");
                pw.println("move /Y \"%NEW%\" \"%OLD%\" >nul 2>&1");
                pw.println("if errorlevel 1 goto wait");
                pw.println("del \"%SELF%\"");
                if (pw.checkError()) {
                    throw new IOException("无法写入 JAR 更新脚本");
                }
            }
            launcher.launch(script);
            started = true;
        } catch (IOException e) {
            Files.deleteIfExists(script);
            Files.deleteIfExists(pending);
            throw e;
        }
    }
}
