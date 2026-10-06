package com.mola.cmd.proxy.app.acp.common;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

/** 可重建的可见消息索引。稳定消息 ID 定位游标，页读取仅 seek 本页记录。 */
public final class ChatHistoryPages {
    private final Path directory;
    private final String scope;
    private String fingerprint;
    private JSONObject metadata;
    private List<String> ids;
    private List<Long> offsets;
    private Map<String, Integer> positions;

    public ChatHistoryPages(Path directory, String scope) {
        this.directory = directory;
        this.scope = UUID.nameUUIDFromBytes(scope.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public synchronized JSONObject page(String version, Supplier<JSONObject> producer, String before, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("每页消息数必须在 1～100 之间");
        try {
            if (!version.equals(fingerprint)) loadOrBuild(version, producer);
            int end = ids.size();
            if (before != null && !before.isEmpty()) {
                if (before.length() > 4096) throw new IllegalArgumentException("历史位置无效，请重新打开会话");
                JSONObject cursor;
                try { cursor = JSON.parseObject(new String(Base64.getUrlDecoder().decode(before), StandardCharsets.UTF_8)); }
                catch (RuntimeException e) { throw new IllegalArgumentException("历史位置无效，请重新打开会话"); }
                if (cursor == null || !scope.equals(cursor.getString("scope"))) throw new IllegalArgumentException("历史位置不属于当前会话");
                Integer position = positions.get(cursor.getString("id"));
                if (position == null) throw new IllegalArgumentException("历史位置已失效，请重新打开会话");
                end = position;
            }
            int start = Math.max(0, end - limit);
            JSONArray items = new JSONArray();
            try (RandomAccessFile file = new RandomAccessFile(directory.resolve("messages.dat").toFile(), "r")) {
                for (int i = start; i < end; i++) {
                    file.seek(offsets.get(i)); int length = file.readInt();
                    if (length < 0 || length > 128 * 1024 * 1024) throw new IOException("消息索引损坏");
                    byte[] bytes = new byte[length]; file.readFully(bytes);
                    items.add(JSON.parseObject(new String(bytes, StandardCharsets.UTF_8)));
                }
            }
            JSONObject result = new JSONObject(new LinkedHashMap<>(metadata));
            result.remove("ids"); result.remove("offsets"); result.remove("fingerprint");
            result.put("items", items); result.put("hasMore", start > 0);
            JSONObject cursor = new JSONObject(); cursor.put("scope", scope); cursor.put("id", start < end ? ids.get(start) : "");
            result.put("olderCursor", start > 0 ? Base64.getUrlEncoder().withoutPadding().encodeToString(cursor.toJSONString().getBytes(StandardCharsets.UTF_8)) : null);
            return result;
        } catch (IOException e) {
            fingerprint = null;
            try { Files.deleteIfExists(directory.resolve("index.json")); } catch (IOException ignored) { }
            throw new IllegalStateException("历史索引读取失败，请重试", e);
        }
    }

    private void loadOrBuild(String version, Supplier<JSONObject> producer) throws IOException {
        Path meta = directory.resolve("index.json"), data = directory.resolve("messages.dat");
        try {
            if (Files.isRegularFile(meta) && Files.isRegularFile(data)) {
                JSONObject saved = JSON.parseObject(new String(Files.readAllBytes(meta), StandardCharsets.UTF_8));
                if (saved != null && version.equals(saved.getString("fingerprint"))) {
                    metadata = saved; ids = saved.getJSONArray("ids").toJavaList(String.class);
                    offsets = saved.getJSONArray("offsets").toJavaList(Long.class);
                    if (ids.size() == offsets.size()) { indexPositions(); fingerprint = version; return; }
                }
            }
        } catch (RuntimeException | IOException ignored) { /* 索引不是原始历史，可重新生成。 */ }
        JSONObject snapshot = producer.get(); JSONArray items = snapshot.getJSONArray("items");
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "messages-", ".tmp");
        List<String> nextIds = new ArrayList<>(); List<Long> nextOffsets = new ArrayList<>();
        try {
            try (RandomAccessFile output = new RandomAccessFile(temporary.toFile(), "rw")) {
                for (int i = 0; i < items.size(); i++) {
                    JSONObject row = items.getJSONObject(i); String id = row.getString("messageId");
                    if (id == null || id.isEmpty()) throw new IllegalStateException("历史消息缺少稳定标识");
                    nextIds.add(id); nextOffsets.add(output.getFilePointer());
                    byte[] bytes = row.toJSONString().getBytes(StandardCharsets.UTF_8); output.writeInt(bytes.length); output.write(bytes);
                }
            }
            Files.move(temporary, data, StandardCopyOption.REPLACE_EXISTING);
            JSONObject next = new JSONObject(new LinkedHashMap<>(snapshot)); next.remove("items");
            next.put("fingerprint", version); next.put("ids", nextIds); next.put("offsets", nextOffsets);
            Files.write(meta, next.toJSONString().getBytes(StandardCharsets.UTF_8));
            metadata = next; ids = nextIds; offsets = nextOffsets; indexPositions(); fingerprint = version;
        } finally { Files.deleteIfExists(temporary); }
    }

    private void indexPositions() {
        positions = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            if (positions.put(ids.get(i), i) != null) throw new IllegalStateException("历史消息标识重复");
        }
    }
}
