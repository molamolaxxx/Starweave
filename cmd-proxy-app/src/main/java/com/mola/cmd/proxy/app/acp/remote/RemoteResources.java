package com.mola.cmd.proxy.app.acp.remote;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.memory.*;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;
import com.mola.starweave.sdk.sync.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/** Central persistence and scope authorization. Never trusts container paths or resource-set claims. */
public final class RemoteResources {
    private final AcpRobotParam robot;
    private final MemoryConfig memory;
    private final MemoryFileStore store;
    private final Path memoryScope;
    public RemoteResources(AcpRobotParam robot) {
        this.robot = robot; memory = robot.getMemory() == null ? new MemoryConfig() : robot.getMemory();
        String base = memory.getBaseDir(); if (base == null || base.trim().isEmpty()) base = CmdProxyHome.pathOf("memory");
        if (base.startsWith("~/")) base = System.getProperty("user.home") + base.substring(1);
        store = new MemoryFileStore(base, memory.isRobotScope() ? robot.getName() : null);
        memoryScope = Paths.get(store.getStorageKey(robot.getWorkDir()));
    }
    public static Path skillRoot(AcpRobotParam robot) {
        String id = robot.getEmbeddedPi() == null ? robot.getName() : robot.getEmbeddedPi().getStateId();
        if (id == null || id.isEmpty()) id = robot.getName();
        return RemoteAcpManager.resourceDirectory().resolve(ResourceFiles.hash(id.getBytes(StandardCharsets.UTF_8))).resolve("skills");
    }
    public String resourceSetId() { return skillRoot(robot).getParent().getFileName().toString(); }
    public String memorySource() { return memoryScope.toString(); }
    public JsonObject exchange(JsonObject request) throws IOException {
        Path skills = skillRoot(robot); ReentrantLock setLock = MemoryScopeLockRegistry.lockForStoragePath(skills);
        ReentrantLock memoryLock = MemoryScopeLockRegistry.lockForStoragePath(memoryScope);
        setLock.lock(); memoryLock.lock();
        try {
            Map<String, Path> roots = new LinkedHashMap<>(); roots.put("skills", skills);
            roots.put("memory", memory.isReadEnabled() ? memoryScope.resolve("memories") : skills.getParent().resolve("memory-disabled"));
            refreshCentralMemoryIndex(skills);
            ResourceAuthority authority = new ResourceAuthority(roots, skills.getParent().resolve("conflicts"), new ResourceAuthority.Commit() {
                public void validate(String key, byte[] bytes) throws IOException {
                    if (key.startsWith("memory/")) {
                        if (!memory.isReadEnabled() || !memory.isWriteEnabled()) throw new IOException("Memory synchronization writes disabled");
                        store.validateSyncedDetail(robot.getWorkDir(), key.substring(7), bytes);
                    }
                }
                public void beforeChange(String key, byte[] previous, byte[] next) throws IOException {
                    if (previous == null) return;
                    Path history = key.startsWith("memory/") ? memoryScope.resolve("archive") : skills.getParent().resolve("history");
                    ResourceFiles.write(ResourceFiles.resolve(history, "sync-" + ResourceFiles.hash(previous)), previous);
                }
                public void validateBatch(Map<String, byte[]> changes) throws IOException {
                    Map<String, byte[]> details = new LinkedHashMap<>(); for (Map.Entry<String, byte[]> e : changes.entrySet()) if (e.getKey().startsWith("memory/")) details.put(e.getKey().substring(7), e.getValue());
                    store.validateSyncedDetails(robot.getWorkDir(), details);
                }
                public void changed(Set<String> keys) throws IOException {
                    Set<String> memoryFiles = new LinkedHashSet<>(); for (String key : keys) if (key.startsWith("memory/")) memoryFiles.add(key.substring(7));
                    store.reconcileSyncedDetails(robot.getWorkDir(), memoryFiles);
                }
            });
            JsonObject result = authority.exchange(request); result.addProperty("memorySource", memoryScope.toString());
            result.add("memoryIndex", new Gson().toJsonTree(memory.isReadEnabled() ? store.loadIndex(robot.getWorkDir()) : new com.mola.cmd.proxy.app.acp.memory.model.MemoryIndex()));
            Path resolutions = skills.getParent().resolve("resolutions.json");
            if (Files.exists(resolutions)) {
                JsonObject choices = JsonParser.parseString(new String(Files.readAllBytes(resolutions), StandardCharsets.UTF_8)).getAsJsonObject(); boolean changed = false;
                for (String key : new ArrayList<>(choices.keySet())) {
                    Path target = resourceTarget(key); String current = Files.exists(target) ? ResourceFiles.hash(Files.readAllBytes(target)) : "";
                    if (!current.equals(ResourceFiles.str(choices.getAsJsonObject(key), "hash")) || key.startsWith("memory/") && !memory.isReadEnabled()) { choices.remove(key); changed = true; }
                }
                if (changed) ResourceFiles.write(resolutions, choices.toString().getBytes(StandardCharsets.UTF_8)); result.add("resolutions", choices);
            }
            saveMemoryBaseline(skills);
            return result;
        } finally { memoryLock.unlock(); setLock.unlock(); }
    }
    private JsonObject memoryHashes() throws IOException {
        Map<String, Path> root = Collections.singletonMap("memory", memoryScope.resolve("memories")); return ResourceAuthority.hashes(ResourceFiles.scan(root));
    }
    private String indexHash() throws IOException { Path index = Paths.get(store.getIndexPath(robot.getWorkDir())); return Files.exists(index) ? ResourceFiles.hash(Files.readAllBytes(index)) : ""; }
    private void refreshCentralMemoryIndex(Path skills) throws IOException {
        if (!memory.isReadEnabled()) return;
        // Recover an interrupted detail/index migration before scanning resource files.
        com.mola.cmd.proxy.app.acp.memory.model.MemoryIndex index = store.loadIndex(robot.getWorkDir());
        Path state = skills.getParent().resolve("memory-indexed.json"); boolean initialized = Files.exists(state);
        JsonObject saved = initialized ? JsonParser.parseString(new String(Files.readAllBytes(state), StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
        JsonObject before = ResourceFiles.object(saved, "files"), now = memoryHashes();
        boolean indexUnchanged = initialized && ResourceFiles.str(saved, "indexHash").equals(indexHash());
        Set<String> indexed = new HashSet<>(); for (com.mola.cmd.proxy.app.acp.memory.model.MemoryEntry e : index.getMemories()) if (e.getFile() != null) indexed.add("memory/" + Paths.get(e.getFile()).getFileName());
        Set<String> candidates = new TreeSet<>(now.keySet()); candidates.addAll(before.keySet()); Map<String, byte[]> changed = new LinkedHashMap<>();
        for (String key : candidates) if (!indexed.contains(key) && now.has(key) || indexUnchanged && !ResourceFiles.str(before, key).equals(ResourceFiles.str(now, key))) {
            Path target = ResourceFiles.resolve(memoryScope.resolve("memories"), key.substring(7)); changed.put(key.substring(7), Files.exists(target) ? Files.readAllBytes(target) : null);
        }
        if (!changed.isEmpty()) { store.validateSyncedDetails(robot.getWorkDir(), changed); store.reconcileSyncedDetails(robot.getWorkDir(), changed.keySet()); }
    }
    private void saveMemoryBaseline(Path skills) throws IOException {
        if (!memory.isReadEnabled()) return;
        JsonObject state = new JsonObject(); state.add("files", memoryHashes()); state.addProperty("indexHash", indexHash());
        ResourceFiles.write(skills.getParent().resolve("memory-indexed.json"), state.toString().getBytes(StandardCharsets.UTF_8));
    }
    private Path conflictRoot() { return skillRoot(robot).getParent().resolve("conflicts"); }
    public JsonArray conflicts() throws IOException {
        JsonArray list = new JsonArray(); Path root = conflictRoot(); if (!Files.isDirectory(root)) return list;
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path dir : stream) {
                if (list.size() >= 1000) break;
                if (!Files.isRegularFile(dir.resolve("metadata.json")) || Files.exists(dir.resolve("resolved"))) continue;
                JsonObject item = new JsonObject(); item.addProperty("id", dir.getFileName().toString()); item.addProperty("path", new String(Files.readAllBytes(dir.resolve("path.txt")), StandardCharsets.UTF_8)); list.add(item);
            }
        }
        return list;
    }
    public JsonObject conflict(String id) throws IOException {
        if (!id.matches("[0-9a-f]{64}")) throw new IOException("Invalid conflict id");
        Path dir = ResourceFiles.resolve(conflictRoot(), id);
        JsonObject metadata = JsonParser.parseString(new String(Files.readAllBytes(dir.resolve("metadata.json")), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject result = new JsonObject(); String key = new String(Files.readAllBytes(dir.resolve("path.txt")), StandardCharsets.UTF_8);
        result.addProperty("id", id); result.addProperty("path", key);
        Path target = resourceTarget(key); byte[] current = Files.exists(target) ? Files.readAllBytes(target) : null;
        result.addProperty("head", ResourceFiles.digest(current)); result.addProperty("containerHash", ResourceFiles.str(metadata, "hash"));
        result.addProperty("starweave", current == null ? "" : new String(current, StandardCharsets.UTF_8));
        result.addProperty("container", Files.exists(dir.resolve("container")) ? new String(Files.readAllBytes(dir.resolve("container")), StandardCharsets.UTF_8) : "");
        result.addProperty("starweaveDeleted", current == null); result.addProperty("containerDeleted", !Files.exists(dir.resolve("container"))); return result;
    }
    private Path resourceTarget(String key) throws IOException {
        Map<String, Path> roots = new LinkedHashMap<>(); roots.put("skills", skillRoot(robot)); roots.put("memory", memoryScope.resolve("memories")); return ResourceFiles.target(roots, key);
    }
    public void resolveConflict(String id, String choice, String expectedHead) throws IOException {
        if (!Arrays.asList("starweave", "container").contains(choice)) throw new IOException("Invalid resolution");
        ReentrantLock lock = MemoryScopeLockRegistry.lockForStoragePath(skillRoot(robot)), memoryLock = MemoryScopeLockRegistry.lockForStoragePath(memoryScope); lock.lock(); memoryLock.lock();
        try {
            JsonObject detail = conflict(id); if (!ResourceFiles.str(detail, "head").equals(expectedHead)) throw new IOException("资源已再次修改，请重新读取冲突");
            String key = ResourceFiles.str(detail, "path"); Path dir = ResourceFiles.resolve(conflictRoot(), id);
            byte[] selected = choice.equals("container") ? (Files.exists(dir.resolve("container")) ? Files.readAllBytes(dir.resolve("container")) : null) : (Files.exists(resourceTarget(key)) ? Files.readAllBytes(resourceTarget(key)) : null);
            if (choice.equals("container")) {
                JsonObject request = new JsonObject(), changes = new JsonObject(), proposal = ResourceFiles.entry(selected);
                proposal.addProperty("base", expectedHead); changes.add(key, proposal); request.add("changes", changes); request.add("manifest", new JsonObject()); exchange(request);
            }
            Path file = skillRoot(robot).getParent().resolve("resolutions.json");
            JsonObject resolutions = Files.exists(file) ? JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
            JsonObject resolution = ResourceFiles.entry(selected); resolution.addProperty("local", ResourceFiles.str(detail, "containerHash")); resolutions.add(key, resolution);
            ResourceFiles.write(file, resolutions.toString().getBytes(StandardCharsets.UTF_8)); ResourceFiles.write(dir.resolve("resolved"), choice.getBytes(StandardCharsets.UTF_8));
        } finally { memoryLock.unlock(); lock.unlock(); }
    }

}
