package com.mola.starweave.sdk.sync;

import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;

/** Snapshot limits are independent of Skills/memory limits. Never accepts a remote file path. */
public final class SessionSnapshots {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private SessionSnapshots() { }
    public static String id(String value) throws IOException {
        if (value == null || !value.matches("[a-zA-Z0-9_-]{1,100}")) throw new IOException("无效的会话 ID");
        return value;
    }
    public static Path file(Path root, String value) throws IOException { return ResourceFiles.resolve(root, id(value) + ".json"); }
    public static byte[] read(Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) throw new IOException("会话快照必须是普通文件且不超过 16 MiB");
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > MAX_BYTES) throw new IOException("会话快照超过 16 MiB");
        return bytes;
    }
    public static JsonObject validate(String id, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IOException("会话快照为空或超过 16 MiB");
        try {
            JsonObject value = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!id(id).equals(ResourceFiles.str(value, "sessionId")) || !value.has("version") || value.get("version").getAsInt() != 1
                    || ResourceFiles.str(value, "cwd").isEmpty() || !value.has("messages") || !value.get("messages").isJsonArray()
                    || !value.has("history") || !value.get("history").isJsonArray()) throw new IOException("会话快照格式或 ID 不匹配");
            return value;
        } catch (RuntimeException e) { throw new IOException("无效的会话快照", e); }
    }
    public static byte[] decode(JsonObject frame) throws IOException {
        String encoded = ResourceFiles.str(frame, "data");
        if (encoded.length() > (MAX_BYTES + 2L) / 3 * 4) throw new IOException("会话快照超过 16 MiB");
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length > MAX_BYTES || !ResourceFiles.hash(bytes).equals(ResourceFiles.str(frame, "hash"))) throw new IOException("会话快照哈希不匹配");
            return bytes;
        } catch (IllegalArgumentException e) { throw new IOException("无效的会话快照编码", e); }
    }
    public static void content(JsonObject frame, byte[] bytes) {
        frame.addProperty("hash", ResourceFiles.digest(bytes));
        if (bytes != null) frame.addProperty("data", Base64.getEncoder().encodeToString(bytes));
    }
    public static void write(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent()); Path temp = Files.createTempFile(path.getParent(), ".session-sync-", ".tmp");
        try {
            try (FileChannel out = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) out.write(buffer); out.force(true);
            }
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
