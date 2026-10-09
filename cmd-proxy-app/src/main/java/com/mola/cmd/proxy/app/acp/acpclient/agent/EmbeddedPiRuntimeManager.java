package com.mola.cmd.proxy.app.acp.acpclient.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Offline, versioned extraction. The bundled runtime is independent of PATH/npm/global Pi. */
public final class EmbeddedPiRuntimeManager {
    private static final String RESOURCE = "/embedded/pi/";
    private static final String[] SCRIPTS = { "entry.mjs", "agent.mjs", "mcp.mjs", "shell.mjs" };
    private final Path runtimeRoot;
    public EmbeddedPiRuntimeManager() { this.runtimeRoot = null; }
    public EmbeddedPiRuntimeManager(Path runtimeRoot) { this.runtimeRoot = runtimeRoot.toAbsolutePath().normalize(); }

    static String platform(String os, String arch) throws IOException {
        String normalized = arch.toLowerCase(Locale.ROOT);
        if (!"amd64".equals(normalized) && !"x86_64".equals(normalized) && !"x64".equals(normalized)) {
            throw new IOException("内嵌 Pi 当前运行包仅支持 Linux/Windows x64，当前架构: " + arch);
        }
        String name = os.toLowerCase(Locale.ROOT);
        if (name.contains("windows")) return "win-x64";
        if (name.contains("linux")) return "linux-x64";
        throw new IOException("内嵌 Pi 不支持当前系统: " + os);
    }

    public synchronized Path prepare() throws IOException {
        String platform = platform(System.getProperty("os.name"), System.getProperty("os.arch"));
        byte[] manifestBytes = readResource("manifest.json");
        JsonObject manifest = JsonParser.parseString(new String(manifestBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        // Script changes must also select a fresh immutable directory.
        MessageDigest hash = sha256(); hash.update(manifestBytes);
        for (String script : SCRIPTS) hash.update(readResource("integration/" + script));
        String fingerprint = hex(hash.digest());
        Path parent = runtimeRoot == null ? CmdProxyHome.resolve("runtimes", "embedded-pi") : runtimeRoot;
        Files.createDirectories(parent);
        Path runtime = parent.resolve(manifest.get("version").getAsString() + "-" + platform + "-" + fingerprint.substring(0, 16));
        try (FileChannel channel = FileChannel.open(parent.resolve("extract.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            if (complete(runtime, platform, fingerprint)) return runtime;
            if (Files.exists(runtime)) throw new IOException("内嵌 Pi 运行资源不完整，请检查目录: " + runtime);
            Path staging = parent.resolve(".extract-" + UUID.randomUUID()); Files.createDirectories(staging);
            try {
                JsonObject assets = manifest.getAsJsonObject("assets");
                String nodeAsset = "runtime/node-" + platform + ".gz";
                extractNode(nodeAsset, assets.get(nodeAsset).getAsString(), staging.resolve(platform.startsWith("win") ? "node.exe" : "node"));
                extractDependencies(assets.get("runtime/dependencies.zip").getAsString(), staging);
                Path integration = staging.resolve("integration"); Files.createDirectories(integration);
                for (String script : SCRIPTS) Files.write(integration.resolve(script), readResource("integration/" + script));
                Files.write(staging.resolve(".complete"), fingerprint.getBytes(StandardCharsets.UTF_8));
                try { Files.move(staging, runtime, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException e) { Files.move(staging, runtime); }
            } finally {
                if (Files.exists(staging)) {
                    try (java.util.stream.Stream<Path> files = Files.walk(staging)) {
                        files.sorted(java.util.Comparator.reverseOrder()).forEach(file -> { try { Files.deleteIfExists(file); } catch (IOException ignored) { } });
                    }
                }
            }
        }
        return runtime;
    }

    private boolean complete(Path runtime, String platform, String fingerprint) throws IOException {
        Path marker = runtime.resolve(".complete");
        return Files.isRegularFile(marker)
                && fingerprint.equals(new String(Files.readAllBytes(marker), StandardCharsets.UTF_8))
                && Files.isRegularFile(runtime.resolve(platform.startsWith("win") ? "node.exe" : "node"))
                && Files.isRegularFile(runtime.resolve("node_modules/@earendil-works/pi-coding-agent/package.json"))
                && Files.isRegularFile(runtime.resolve("integration/entry.mjs"));
    }
    private void extractNode(String asset, String expected, Path file) throws IOException {
        Path compressed = file.resolveSibling("node.gz");
        try {
            copyVerified(asset, expected, compressed);
            try (InputStream input = new GZIPInputStream(Files.newInputStream(compressed))) { Files.copy(input, file); }
            if (!file.toFile().setExecutable(true, false) && !file.toString().endsWith(".exe")) throw new IOException("无法设置内嵌 Node 执行权限");
        } finally { Files.deleteIfExists(compressed); }
    }
    private void extractDependencies(String expected, Path target) throws IOException {
        Path archive = target.resolve("dependencies.zip");
        try {
            copyVerified("runtime/dependencies.zip", expected, archive);
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    Path file = target.resolve(entry.getName()).normalize();
                    if (!file.startsWith(target) || entry.getName().contains("\\")) throw new IOException("非法内嵌资源路径");
                    if (entry.isDirectory()) Files.createDirectories(file);
                    else { Files.createDirectories(file.getParent()); Files.copy(zip, file); }
                }
            }
        } finally { Files.deleteIfExists(archive); }
    }
    private void copyVerified(String asset, String expected, Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = resource(asset); OutputStream output = Files.newOutputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) >= 0) { output.write(buffer, 0, count); digest.update(buffer, 0, count); }
        }
        if (!expected.equals(hex(digest.digest()))) throw new IOException("内嵌资源校验失败: " + asset);
    }
    private static InputStream resource(String name) throws IOException {
        InputStream input = EmbeddedPiRuntimeManager.class.getResourceAsStream(RESOURCE + name);
        if (input == null) throw new IOException("缺少内嵌 Pi 资源: " + name + "；请运行 scripts/build-embedded-pi.mjs 后重新打包");
        return input;
    }
    private static byte[] readResource(String name) throws IOException {
        try (InputStream input = resource(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count; while ((count = input.read(buffer)) >= 0) out.write(buffer, 0, count); return out.toByteArray();
        }
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String hex(byte[] data) { StringBuilder out = new StringBuilder(); for (byte item : data) out.append(String.format("%02x", item & 255)); return out.toString(); }
}
