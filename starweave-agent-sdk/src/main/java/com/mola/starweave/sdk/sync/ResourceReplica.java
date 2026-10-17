package com.mola.starweave.sdk.sync;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Persistent three-way baseline: a new empty replica never sends deletions. */
public final class ResourceReplica {
    private final Map<String, Path> roots = new LinkedHashMap<>();
    private final Path state, conflictRoot;
    private JsonObject baseline = new JsonObject(), conflicts = new JsonObject();
    private JsonObject sent, sentManifest;
    private String resourceSet = "";
    private boolean firstScan = true, coldStart = true;
    public ResourceReplica(Path directory) throws IOException { this(directory, directory.resolve("workspace")); }
    public ResourceReplica(Path directory, Path workspace) throws IOException {
        roots.put("skills", workspace.resolve(".agents/skills")); roots.put("memory", directory.resolve("managed/memory"));
        state = directory.resolve("resource-sync-state.json"); conflictRoot = directory.resolve("resource-conflicts");
        if (Files.exists(state)) {
            JsonObject saved = JsonParser.parseString(new String(Files.readAllBytes(state), StandardCharsets.UTF_8)).getAsJsonObject();
            baseline = ResourceFiles.object(saved, "baseline"); conflicts = ResourceFiles.object(saved, "conflicts"); resourceSet = ResourceFiles.str(saved, "resourceSet");
        }
    }
    public Path root() { return roots.get("memory").getParent().toAbsolutePath().normalize(); }
    public Path skillRoot() { return roots.get("skills").toAbsolutePath().normalize(); }
    public synchronized void bind(String id) throws IOException {
        if (id.isEmpty() || !resourceSet.isEmpty() && !resourceSet.equals(id)) throw new IOException("Managed resources belong to another resource set; use a separate data directory");
        resourceSet = id; sent = null; save();
    }
    public synchronized JsonObject request() throws IOException {
        Map<String, byte[]> local = ResourceFiles.scan(roots); JsonObject manifest = ResourceAuthority.hashes(local), changes = new JsonObject();
        if (firstScan) {
            // A retained SDK state plus a replaced/empty container directory is a restore, not mass deletion.
            for (String scope : roots.keySet()) {
                boolean empty = manifest.keySet().stream().noneMatch(key -> key.startsWith(scope + "/"));
                if (empty) for (String key : new ArrayList<>(baseline.keySet())) if (key.startsWith(scope + "/")) baseline.remove(key);
            }
            firstScan = false;
        }
        Set<String> keys = new TreeSet<>(baseline.keySet()); keys.addAll(local.keySet());
        for (String key : keys) {
            String hash = ResourceFiles.str(manifest, key), base = ResourceFiles.str(baseline, key);
            JsonObject conflict = conflicts.has(key) ? conflicts.getAsJsonObject(key) : null;
            if (conflict != null && ResourceFiles.str(conflict, "local").equals(hash)) continue;
            if (conflict != null) conflicts.remove(key); // A new edit explicitly resolves/rebases the local conflict.
            if (!hash.equals(base)) { JsonObject entry = ResourceFiles.entry(local.get(key)); entry.addProperty("base", base); changes.add(key, entry); }
        }
        JsonObject request = new JsonObject(); request.addProperty("type", "resources_request"); request.addProperty("requestId", UUID.randomUUID().toString());
        request.addProperty("coldStart", coldStart); request.add("manifest", manifest); request.add("changes", changes); sent = request.deepCopy(); sentManifest = manifest.deepCopy(); return request;
    }
    public synchronized boolean apply(JsonObject response) throws IOException {
        if (sent == null || !ResourceFiles.str(sent, "requestId").equals(ResourceFiles.str(response, "requestId"))) return false;
        if (response.has("error")) { sent = null; throw new IOException(ResourceFiles.str(response, "error")); }
        JsonObject files = ResourceFiles.object(response, "files"), accepted = ResourceFiles.object(response, "accepted"), collisions = ResourceFiles.object(response, "conflicts");
        if (files.size() > ResourceFiles.MAX_FILES) throw new IOException("Too many remote resources");
        JsonObject resolutions = ResourceFiles.object(response, "resolutions");
        for (Map.Entry<String, JsonElement> e : resolutions.entrySet()) if (conflicts.has(e.getKey())) files.add(e.getKey(), e.getValue());
        Map<String, byte[]> decoded = new LinkedHashMap<>(); long total = 0;
        for (Map.Entry<String, JsonElement> entry : files.entrySet()) {
            ResourceFiles.target(roots, entry.getKey()); byte[] data = ResourceFiles.decode(entry.getValue().getAsJsonObject());
            total += data == null ? 0 : data.length; if (total > ResourceFiles.MAX_TOTAL) throw new IOException("Resource response exceeds limit"); decoded.put(entry.getKey(), data);
        }
        // Acknowledged uploads advance the baseline even if a newer local edit happened in flight.
        for (Map.Entry<String, JsonElement> entry : accepted.entrySet()) { ResourceFiles.target(roots, entry.getKey()); baseline.add(entry.getKey(), entry.getValue()); }
        for (Map.Entry<String, byte[]> entry : decoded.entrySet()) {
            String key = entry.getKey(); Path file = ResourceFiles.target(roots, key); byte[] actual = Files.exists(file) ? Files.readAllBytes(file) : null;
            String current = ResourceFiles.digest(actual), expected = ResourceFiles.str(sentManifest, key), remote = ResourceFiles.digest(entry.getValue());
            if (!current.equals(expected)) continue; // Never overwrite edits made while the request was in flight.
            boolean resolved = resolutions.has(key) && ResourceFiles.str(resolutions.getAsJsonObject(key), "local").equals(current);
            if (resolved) conflicts.remove(key);
            if (!resolved && (collisions.has(key) || conflicts.has(key) && ResourceFiles.str(conflicts.getAsJsonObject(key), "local").equals(current))) {
                String id = ResourceFiles.hash((key + "\n" + current + "\n" + remote).getBytes(StandardCharsets.UTF_8));
                Path dir = ResourceFiles.resolve(conflictRoot, id); Files.createDirectories(dir);
                ResourceFiles.write(dir.resolve("path.txt"), key.getBytes(StandardCharsets.UTF_8));
                if (actual != null) ResourceFiles.write(dir.resolve("container"), actual);
                if (entry.getValue() != null) ResourceFiles.write(dir.resolve("starweave"), entry.getValue());
                JsonObject conflict = new JsonObject(); conflict.addProperty("local", current); conflict.addProperty("remote", remote); conflict.addProperty("copy", dir.toString()); conflicts.add(key, conflict);
                baseline.addProperty(key, remote); continue;
            }
            if (entry.getValue() == null) Files.deleteIfExists(file); else ResourceFiles.write(file, entry.getValue());
            baseline.addProperty(key, remote);
        }
        // Fully identical files need a baseline too, including content already present on first registration.
        for (Map.Entry<String, JsonElement> entry : sentManifest.entrySet()) if (!files.has(entry.getKey()) && !collisions.has(entry.getKey())) { baseline.add(entry.getKey(), entry.getValue()); conflicts.remove(entry.getKey()); }
        if (response.has("memoryIndex")) {
            String index = response.get("memoryIndex").toString();
            String source = ResourceFiles.str(response, "memorySource");
            if (!source.isEmpty()) {
                // JSON escaping is handled by tree traversal, never textual JSON substitution.
                JsonElement tree = response.get("memoryIndex").deepCopy(); rewriteIndex(tree, source, roots.get("memory").toAbsolutePath().toString()); index = tree.toString();
            }
            ResourceFiles.write(ResourceFiles.resolve(roots.get("memory"), "index.json"), index.getBytes(StandardCharsets.UTF_8));
        }
        for (String key : new ArrayList<>(baseline.keySet())) if (ResourceFiles.str(baseline, key).isEmpty() && !conflicts.has(key)) baseline.remove(key);
        sent = null; coldStart = false; save(); return true;
    }
    private static void rewriteIndex(JsonElement value, String source, String target) {
        if (value.isJsonObject()) for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) entry.setValue(new JsonPrimitive(entry.getValue().getAsString().replace(source + "/memories/", target + "/").replace(source + "\\memories\\", target + "/")));
            else rewriteIndex(entry.getValue(), source, target);
        } else if (value.isJsonArray()) for (JsonElement item : value.getAsJsonArray()) rewriteIndex(item, source, target);
    }
    private void save() throws IOException {
        JsonObject saved = new JsonObject(); saved.addProperty("resourceSet", resourceSet); saved.add("baseline", baseline); saved.add("conflicts", conflicts);
        ResourceFiles.write(state, saved.toString().getBytes(StandardCharsets.UTF_8));
    }
    public synchronized int conflictCount() { return conflicts.size(); }
    public synchronized void resetRequest() { sent = null; }
}
