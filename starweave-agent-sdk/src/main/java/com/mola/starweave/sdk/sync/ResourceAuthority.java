package com.mola.starweave.sdk.sync;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Per-resource-set optimistic commits. Caller also holds the owning memory service's storage lock. */
public final class ResourceAuthority {
    public interface Commit {
        void validate(String key, byte[] content) throws IOException;
        void changed(Set<String> keys) throws IOException;
        default void validateBatch(Map<String, byte[]> changes) throws IOException { }
        default void beforeChange(String key, byte[] previous, byte[] next) throws IOException { }
    }
    private final Map<String, Path> roots;
    private final Path conflicts;
    private final Commit commit;
    public ResourceAuthority(Map<String, Path> roots, Path conflicts, Commit commit) {
        this.roots = new LinkedHashMap<>(roots); this.conflicts = conflicts; this.commit = commit;
    }
    public synchronized JsonObject exchange(JsonObject request) throws IOException {
        JsonObject changes = ResourceFiles.object(request, "changes"), manifest = ResourceFiles.object(request, "manifest");
        if (manifest.size() > ResourceFiles.MAX_FILES || changes.size() > ResourceFiles.MAX_FILES) throw new IOException("Too many resource entries");
        Map<String, byte[]> current = ResourceFiles.scan(roots), decoded = new LinkedHashMap<>(), acceptedChanges = new LinkedHashMap<>(); int total = 0;
        for (Map.Entry<String, JsonElement> e : changes.entrySet()) {
            ResourceFiles.target(roots, e.getKey()); byte[] bytes = ResourceFiles.decode(e.getValue().getAsJsonObject());
            total += bytes == null ? 0 : bytes.length; if (total > ResourceFiles.MAX_TOTAL) throw new IOException("Resource changes exceed limit");
            commit.validate(e.getKey(), bytes); decoded.put(e.getKey(), bytes);
        }
        for (String key : manifest.keySet()) ResourceFiles.target(roots, key);
        Path historyFile = conflicts.getParent().resolve("resource-heads.json");
        JsonObject history = Files.exists(historyFile) ? JsonParser.parseString(new String(Files.readAllBytes(historyFile), StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
        JsonObject oldHeads = ResourceFiles.object(history, "heads"), tombstones = ResourceFiles.object(history, "deleted");
        for (String key : oldHeads.keySet()) if (!current.containsKey(key)) tombstones.add(key, oldHeads.get(key));
        boolean coldStart = request.has("coldStart") && request.get("coldStart").getAsBoolean();
        JsonObject collisions = new JsonObject(), accepted = new JsonObject();
        for (Map.Entry<String, byte[]> e : decoded.entrySet()) {
            String key = e.getKey(); JsonObject proposal = changes.getAsJsonObject(key);
            String head = ResourceFiles.digest(current.get(key)), wanted = ResourceFiles.digest(e.getValue());
            if ((!head.equals(ResourceFiles.str(proposal, "base")) && !head.equals(wanted)) || coldStart && head.isEmpty() && !wanted.isEmpty() && tombstones.has(key)) {
                String conflictId = ResourceFiles.hash((key + "\n" + head + "\n" + wanted).getBytes(StandardCharsets.UTF_8));
                Path dir = ResourceFiles.resolve(conflicts, conflictId); Files.createDirectories(dir);
                JsonObject metadata = proposal.deepCopy(); metadata.addProperty("head", head); metadata.remove("data");
                ResourceFiles.write(dir.resolve("metadata.json"), metadata.toString().getBytes(StandardCharsets.UTF_8));
                ResourceFiles.write(dir.resolve("path.txt"), key.getBytes(StandardCharsets.UTF_8));
                if (current.get(key) != null) ResourceFiles.write(dir.resolve("starweave"), current.get(key));
                if (e.getValue() != null) ResourceFiles.write(dir.resolve("container"), e.getValue());
                collisions.addProperty(key, head); continue;
            }
            acceptedChanges.put(key, e.getValue()); accepted.addProperty(key, wanted);
        }
        // Validate the ACTUALLY accepted batch, including partial rename/conflict outcomes, before changing files.
        commit.validateBatch(acceptedChanges);
        Map<String, byte[]> projected = new LinkedHashMap<>(current); projected.putAll(acceptedChanges); long projectedBytes = 0; int projectedCount = 0;
        for (byte[] value : projected.values()) if (value != null) { projectedBytes += value.length; projectedCount++; }
        if (projectedBytes > ResourceFiles.MAX_TOTAL || projectedCount > ResourceFiles.MAX_FILES) throw new IOException("Resource set exceeds limit");
        for (Map.Entry<String, byte[]> e : acceptedChanges.entrySet()) {
            String key = e.getKey(); if (ResourceFiles.digest(current.get(key)).equals(ResourceFiles.digest(e.getValue()))) continue;
            commit.beforeChange(key, current.get(key), e.getValue());
            if (e.getValue() == null) tombstones.addProperty(key, ResourceFiles.digest(current.get(key))); else tombstones.remove(key);
            Path target = ResourceFiles.target(roots, key);
            if (e.getValue() == null) Files.deleteIfExists(target); else ResourceFiles.write(target, e.getValue());
            if (e.getValue() == null) current.remove(key); else current.put(key, e.getValue());
        }
        // Rebuild indexes even on idempotent retries after an interrupted previous commit.
        commit.changed(acceptedChanges.keySet());
        JsonObject delta = new JsonObject(); Set<String> keys = new TreeSet<>(current.keySet()); keys.addAll(manifest.keySet());
        for (String key : keys) if (!ResourceFiles.digest(current.get(key)).equals(ResourceFiles.str(manifest, key))) delta.add(key, ResourceFiles.entry(current.get(key)));
        history.add("heads", hashes(current)); history.add("deleted", tombstones); ResourceFiles.write(historyFile, history.toString().getBytes(StandardCharsets.UTF_8));
        JsonObject result = new JsonObject(); result.add("files", delta); result.add("accepted", accepted); result.add("conflicts", collisions);
        result.addProperty("version", ResourceFiles.hash(hashes(current).toString().getBytes(StandardCharsets.UTF_8))); return result;
    }
    public static JsonObject hashes(Map<String, byte[]> files) { JsonObject result = new JsonObject(); for (Map.Entry<String, byte[]> e : files.entrySet()) result.addProperty(e.getKey(), ResourceFiles.hash(e.getValue())); return result; }
}
