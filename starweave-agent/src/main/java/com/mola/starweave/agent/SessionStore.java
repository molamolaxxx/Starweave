package com.mola.starweave.agent;

import com.google.gson.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/** Atomic snapshots isolated from Pi's original JSONL files. Legacy imports are read-only. */
final class SessionStore {
    private final Path root, legacy;
    SessionStore(Path agentDir) throws IOException { root = agentDir.resolve("java-sessions"); legacy = agentDir.resolve("sessions"); Files.createDirectories(root); }
    JsonObject create(Path cwd, String model) {
        return Json.obj("version", 1, "sessionId", UUID.randomUUID().toString(), "cwd", cwd.toString(), "model", model,
                "messages", Json.array(), "history", Json.array(), "tokens", 0, "updatedAt", Instant.now().toString());
    }
    void save(JsonObject snapshot) throws IOException {
        snapshot.addProperty("updatedAt", Instant.now().toString()); Path target = path(Json.str(snapshot, "sessionId"));
        Path temp = Files.createTempFile(root, ".session-", ".tmp");
        try {
            byte[] bytes = Json.GSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8);
            try (FileChannel out = FileChannel.open(temp, StandardOpenOption.WRITE)) { java.nio.ByteBuffer data = java.nio.ByteBuffer.wrap(bytes); while (data.hasRemaining()) out.write(data); out.force(true); }
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    JsonObject load(String id, Path cwd) throws IOException {
        Path file = path(id); JsonObject result;
        if (Files.isRegularFile(file)) result = Json.parse(Resources.read(file, 128 * 1024 * 1024));
        else result = importLegacy(id, cwd);
        if (!cwd.toString().equals(Json.str(result, "cwd"))) throw new IOException("会话工作区不匹配");
        if (!id.equals(Json.str(result, "sessionId"))) throw new IOException("会话 ID 不匹配");
        return result;
    }
    JsonArray list(Path cwd) throws IOException {
        List<JsonObject> rows = new ArrayList<>(); Set<String> seen = new HashSet<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "*.json")) {
            for (Path file : files) { JsonObject s = Json.parse(Resources.read(file, 128 * 1024 * 1024)); if (cwd == null || cwd.toString().equals(Json.str(s, "cwd"))) { rows.add(info(s)); seen.add(Json.str(s, "sessionId")); } }
        }
        if (Files.isDirectory(legacy)) try (Stream<Path> files = Files.walk(legacy, 3)) {
            Iterator<Path> i = files.filter(p -> p.toString().endsWith(".jsonl")).iterator();
            while (i.hasNext()) { Path file = i.next(); try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String first = Http.boundedLine(in, 1024 * 1024); if (first == null) continue; JsonObject h = Json.parse(first); String id = Json.str(h, "id");
                if (!id.isEmpty() && !seen.contains(id) && (cwd == null || cwd.toString().equals(Json.str(h, "cwd")))) {
                    rows.add(Json.obj("sessionId", id, "cwd", Json.str(h, "cwd"), "title", "Pi 会话", "updatedAt", Files.getLastModifiedTime(file).toInstant().toString())); seen.add(id);
                }
            } catch (JsonParseException ignored) { /* unrelated malformed legacy file */ } }
        }
        rows.sort((a,b) -> Json.str(b, "updatedAt").compareTo(Json.str(a, "updatedAt"))); JsonArray result = new JsonArray(); for (JsonObject row : rows) result.add(row); return result;
    }
    private JsonObject info(JsonObject s) {
        String title = "Java Pi 会话";
        for (JsonElement e : Json.list(s, "history")) { JsonObject m = e.getAsJsonObject(); if (Json.str(m, "role").equals("user")) { title = Json.GSON.toJson(m.get("content")); if (title.length() > 80) title = title.substring(0, 80); break; } }
        return Json.obj("sessionId", Json.str(s, "sessionId"), "cwd", Json.str(s, "cwd"), "title", title, "updatedAt", Json.str(s, "updatedAt"));
    }
    private Path path(String id) throws IOException { if (!id.matches("[a-zA-Z0-9_-]{1,100}")) throw new IOException("无效的会话 ID"); return root.resolve(id + ".json"); }
    private JsonObject importLegacy(String id, Path cwd) throws IOException {
        if (!Files.isDirectory(legacy)) throw new IOException("会话不存在");
        try (Stream<Path> paths = Files.walk(legacy, 3)) {
            Iterator<Path> files = paths.filter(p -> p.toString().endsWith(".jsonl")).iterator();
            while (files.hasNext()) {
                Path file = files.next();
                try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String line = Http.boundedLine(in, 32 * 1024 * 1024); if (line == null) continue; JsonObject header;
                    try { header = Json.parse(line); } catch (JsonParseException e) { continue; }
                    if (!id.equals(Json.str(header, "id"))) continue;
                    if (!cwd.toString().equals(Json.str(header, "cwd"))) throw new IOException("会话工作区不匹配");
                    Map<String, JsonObject> entries = new LinkedHashMap<>(); JsonObject last = null;
                    while ((line = Http.boundedLine(in, 32 * 1024 * 1024)) != null) {
                        if (line.trim().isEmpty()) continue;
                        JsonObject entry; try { entry = Json.parse(line); } catch (JsonParseException e) { throw new IOException("Pi 会话包含损坏记录，未修改原文件", e); }
                        if (!Json.str(entry, "id").isEmpty()) entries.put(Json.str(entry, "id"), entry); last = entry;
                    }
                    LinkedList<JsonObject> branch = new LinkedList<>(); Set<String> visited = new HashSet<>();
                    while (last != null && visited.add(Json.str(last, "id"))) { branch.addFirst(last); last = entries.get(Json.str(last, "parentId")); }
                    JsonObject result = create(cwd, ""); result.addProperty("sessionId", id); JsonArray messages = new JsonArray(), history = new JsonArray();
                    int compactAt = -1; String keep = "", summary = "";
                    for (int j = 0; j < branch.size(); j++) if (Json.str(branch.get(j), "type").equals("compaction")) { compactAt = j; keep = Json.str(branch.get(j), "firstKeptEntryId"); summary = Json.str(branch.get(j), "summary"); }
                    if (compactAt >= 0) messages.add(Json.obj("role", "user", "content", "[Previous context summary]\n" + summary));
                    boolean keeping = compactAt < 0;
                    for (int j = 0; j < branch.size(); j++) {
                        JsonObject entry = branch.get(j); if (Json.str(entry, "id").equals(keep) || j > compactAt) keeping = true;
                        if (Json.str(entry, "type").equals("model_change")) result.addProperty("model", Json.str(entry, "modelId"));
                        if (!Json.str(entry, "type").equals("message")) continue;
                        JsonObject original = Json.object(entry, "message"), converted = convert(original); if (converted == null) continue;
                        history.add(converted); if (keeping) messages.add(converted.deepCopy());
                        if (!Json.str(original, "model").isEmpty()) result.addProperty("model", Json.str(original, "model"));
                    }
                    result.add("messages", messages); result.add("history", history); return result;
                }
            }
        }
        throw new IOException("会话不存在");
    }
    private static JsonObject convert(JsonObject m) {
        String role = Json.str(m, "role");
        if (role.equals("toolResult")) return Json.obj("role", "tool", "tool_call_id", Json.str(m, "toolCallId"), "content", Json.GSON.toJson(Json.list(m, "content")));
        if (!role.equals("user") && !role.equals("assistant")) return null;
        if (m.has("content") && m.get("content").isJsonPrimitive()) return Json.obj("role", role, "content", m.get("content"));
        JsonArray blocks = new JsonArray(), calls = new JsonArray(); StringBuilder text = new StringBuilder(), thought = new StringBuilder();
        for (JsonElement e : Json.list(m, "content")) {
            JsonObject b = e.getAsJsonObject(); String type = Json.str(b, "type");
            if (type.equals("text")) { text.append(Json.str(b, "text")); blocks.add(Json.text(Json.str(b, "text"))); }
            else if (type.equals("image")) blocks.add(Json.obj("type", "image_url", "image_url", Json.obj("url", "data:" + Json.str(b, "mimeType") + ";base64," + Json.str(b, "data"))));
            else if (type.equals("thinking")) thought.append(Json.str(b, "thinking"));
            else if (type.equals("toolCall")) calls.add(Json.obj("id", Json.str(b, "id"), "type", "function", "function", Json.obj("name", Json.str(b, "name"), "arguments", Json.GSON.toJson(Json.object(b, "arguments")))));
        }
        JsonObject converted = Json.obj("role", role, "content", role.equals("user") ? blocks : text.toString());
        if (calls.size() > 0) converted.add("tool_calls", calls); if (thought.length() > 0) converted.addProperty("reasoning_content", thought.toString()); return converted;
    }
}
