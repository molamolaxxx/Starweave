package com.mola.cmd.proxy.app.acp.team.coordinator;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Atomic files for global records and durable participant event outboxes. */
public final class CoordinationStore {
    private final Path root;
    public CoordinationStore(Path root) { this.root = root.toAbsolutePath().normalize(); }
    /** Atomic durable admission; duplicate deliveries do not consume capacity. */
    public synchronized void enqueue(String area, String id, JSONObject value, int capacity) {
        if (find(area, id) != null) return;
        Path directory = root.resolve(safe(area));
        int count = 0;
        if (Files.isDirectory(directory)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.json")) {
                for (Path ignored : stream) if (++count >= capacity)
                    throw new CoordinationException("QUEUE_FULL", "团队事件队列已满，请稍后重试");
            } catch (IOException failure) { throw new CoordinationException("STORE_FAILED", "团队事件队列读取失败"); }
        }
        save(area, id, value);
    }
    public synchronized void save(String area, String id, JSONObject value) {
        Path file = file(area, id);
        Path temp = null;
        try {
            Files.createDirectories(file.getParent());
            temp = Files.createTempFile(file.getParent(), ".write-", ".tmp");
            byte[] bytes = value.toJSONString().getBytes(StandardCharsets.UTF_8);
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(temp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new CoordinationException("STORE_FAILED", "团队协调记录保存失败"); }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { } }
    }
    public synchronized JSONObject find(String area, String id) {
        Path file = file(area, id);
        if (!Files.exists(file)) return null;
        try { return JSON.parseObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)); }
        catch (IOException | RuntimeException e) { throw new CoordinationException("STORE_FAILED", "团队协调记录读取失败"); }
    }
    public synchronized List<JSONObject> list(String area) {
        Path directory = root.resolve(safe(area));
        List<JSONObject> values = new ArrayList<>();
        if (!Files.isDirectory(directory)) return values;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : stream) values.add(find(area, file.getFileName().toString().replaceFirst("\\.json$", "")));
        } catch (IOException e) { throw new CoordinationException("STORE_FAILED", "团队协调目录读取失败"); }
        values.sort(Comparator.comparingLong((JSONObject value) -> value.getLongValue("timestamp"))
                .thenComparingLong(value -> value.getLongValue("eventSeq")));
        return values;
    }
    public synchronized void delete(String area, String id) {
        try { Files.deleteIfExists(file(area, id)); }
        catch (IOException e) { throw new CoordinationException("STORE_FAILED", "团队协调记录清理失败"); }
    }
    private Path file(String area, String id) { return root.resolve(safe(area)).resolve(safe(id) + ".json"); }
    private static String safe(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9._-]{1,240}") || value.equals(".") || value.equals(".."))
            throw new IllegalArgumentException("invalid coordination record ID");
        return value;
    }
}
