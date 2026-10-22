package com.mola.starweave.sdk.sync;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Single worker owns this replica. Local cache deletion never deletes a central backup. */
public final class SessionReplica {
    public interface Exchange { JsonObject call(JsonObject request) throws IOException; }
    private final Path root, state;
    private JsonObject heads = new JsonObject();
    private String resourceSet = "";
    private int scanOffset;
    private final Map<String, IOException> uploadErrors = new LinkedHashMap<>();
    public SessionReplica(Path directory) throws IOException {
        root = directory.resolve("java-sessions"); state = directory.resolve("session-sync-state.json");
        if (Files.exists(state)) {
            JsonObject saved = JsonParser.parseString(new String(Files.readAllBytes(state), StandardCharsets.UTF_8)).getAsJsonObject();
            heads = ResourceFiles.object(saved, "heads"); resourceSet = ResourceFiles.str(saved, "resourceSet");
        }
    }
    public void bind(String value) throws IOException {
        if (value.isEmpty() || !resourceSet.isEmpty() && !resourceSet.equals(value)) throw new IOException("会话备份不属于当前资源集");
        resourceSet = value; save();
    }
    public void upload(Exchange exchange) throws IOException {
        if (!Files.exists(root)) return;
        IOException failure = null;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "*.json")) {
            for (Path file : files) {
                String name = file.getFileName().toString(), id = name.substring(0, name.length() - 5);
                try { sync(id, false, exchange); }
                catch (IOException e) { failure = e; } // One broken session must not starve other backups.
            }
        }
        if (failure != null) throw failure;
    }
    /** Limit background work so a large history cannot indefinitely queue a requested restore. */
    public void uploadBatch(Exchange exchange) throws IOException {
        if (!Files.exists(root)) return;
        List<String> ids = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "*.json")) {
            for (Path file : files) {
                String name = file.getFileName().toString(); ids.add(name.substring(0, name.length() - 5));
                if (ids.size() > 10000) throw new IOException("本地会话快照数量超过 10000");
            }
        }
        if (ids.isEmpty()) return;
        Collections.sort(ids); uploadErrors.keySet().retainAll(new HashSet<>(ids)); int count = Math.min(8, ids.size());
        for (int n = 0; n < count; n++) {
            String id = ids.get(scanOffset++ % ids.size());
            try { sync(id, false, exchange); uploadErrors.remove(id); }
            catch (IOException e) { uploadErrors.put(id, e); }
        }
        scanOffset %= ids.size();
        if (!uploadErrors.isEmpty()) {
            Map.Entry<String, IOException> error = uploadErrors.entrySet().iterator().next();
            throw new IOException("会话 " + error.getKey() + " 备份失败：" + error.getValue().getMessage(), error.getValue());
        }
    }
    public void prepare(String id, Exchange exchange) throws IOException { sync(id, true, exchange); uploadErrors.remove(id); }
    public void uploadOne(String id, Exchange exchange) throws IOException {
        try { sync(id, false, exchange); uploadErrors.remove(id); }
        catch (IOException e) { uploadErrors.put(id, e); throw e; }
    }
    private void sync(String id, boolean pull, Exchange exchange) throws IOException {
        Path file = SessionSnapshots.file(root, id); byte[] local = SessionSnapshots.read(file);
        String hash = ResourceFiles.digest(local), base = ResourceFiles.str(heads, id);
        if (!pull && (local == null || hash.equals(base))) return;
        JsonObject request = new JsonObject(); request.addProperty("sessionId", id); request.addProperty("base", base);
        request.addProperty("local", hash); request.addProperty("pull", pull);
        if (local != null && !hash.equals(base)) { SessionSnapshots.validate(id, local); SessionSnapshots.content(request, local); }
        JsonObject result = exchange.call(request);
        if (result.has("error")) throw new IOException(ResourceFiles.str(result, "error"));
        if (!id.equals(ResourceFiles.str(result, "sessionId"))) throw new IOException("会话同步响应 ID 不匹配");
        String remote = ResourceFiles.str(result, "hash");
        if (pull && result.has("data")) {
            byte[] bytes = SessionSnapshots.decode(result); SessionSnapshots.validate(id, bytes);
            if (!hash.equals(ResourceFiles.digest(SessionSnapshots.read(file)))) throw new IOException("会话在下载期间发生修改，请重试恢复");
            SessionSnapshots.write(file, bytes);
        } else if (local != null && !remote.equals(hash)) {
            throw new IOException("中心会话版本已变化，请停止该会话后重新恢复");
        }
        // Missing both sides is allowed: the engine can still import a legacy Pi session.
        if (!remote.isEmpty()) { heads.addProperty(id, remote); save(); }
    }
    private void save() throws IOException {
        JsonObject value = new JsonObject(); value.addProperty("resourceSet", resourceSet); value.add("heads", heads);
        SessionSnapshots.write(state, value.toString().getBytes(StandardCharsets.UTF_8));
    }
}
