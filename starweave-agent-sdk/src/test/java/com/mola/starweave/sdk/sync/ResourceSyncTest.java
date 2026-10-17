package com.mola.starweave.sdk.sync;

import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class ResourceSyncTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Map<String, Path> roots; private ResourceAuthority server; private Path data; private ResourceReplica client;
    @Before public void setup() throws Exception {
        roots = new LinkedHashMap<>(); roots.put("skills", temp.newFolder("central-skills").toPath()); roots.put("memory", temp.newFolder("central-memory").toPath());
        server = new ResourceAuthority(roots, temp.newFolder("conflicts").toPath(), new ResourceAuthority.Commit() {
            public void validate(String path, byte[] content) { }
            public void changed(Set<String> paths) { }
        });
        data = temp.newFolder("container").toPath(); client = new ResourceReplica(data); client.bind("set-one");
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private void central(String path, String text) throws Exception { ResourceFiles.write(ResourceFiles.target(roots, path), bytes(text)); }
    private Path localPath(String path) { return path.startsWith("skills/") ? client.skillRoot().resolve(path.substring(7)) : client.root().resolve(path); }
    private void local(String path, String text) throws Exception { ResourceFiles.write(localPath(path), bytes(text)); }
    private JsonObject exchange() throws Exception {
        JsonObject request = client.request(); JsonObject reply = server.exchange(request); reply.add("requestId", request.get("requestId")); assertTrue(client.apply(reply)); return reply;
    }
    @Test public void emptyContainerRestoresThenUploadsEditsAndDeletes() throws Exception {
        central("skills/demo/SKILL.md", "initial"); central("memory/example.md", "memory"); exchange();
        assertEquals("initial", new String(Files.readAllBytes(localPath("skills/demo/SKILL.md")), StandardCharsets.UTF_8));
        local("skills/demo/SKILL.md", "edited"); local("memory/new.md", "new"); exchange();
        assertEquals("edited", new String(Files.readAllBytes(roots.get("skills").resolve("demo/SKILL.md")), StandardCharsets.UTF_8));
        assertTrue(Files.exists(roots.get("memory").resolve("new.md")));
        Files.delete(client.root().resolve("memory/example.md")); exchange(); assertFalse(Files.exists(roots.get("memory").resolve("example.md")));
        client = new ResourceReplica(temp.newFolder("replacement").toPath()); client.bind("set-one"); exchange();
        assertTrue(Files.exists(client.root().resolve("memory/new.md"))); assertTrue(Files.exists(roots.get("skills").resolve("demo/SKILL.md")));
    }
    @Test public void coldRestartWithRetainedStateAndEmptyWorkspaceRestoresInsteadOfDeleting() throws Exception {
        central("skills/demo/SKILL.md", "keep"); exchange();
        Files.delete(localPath("skills/demo/SKILL.md")); client = new ResourceReplica(data); client.bind("set-one"); exchange();
        assertEquals("keep", new String(Files.readAllBytes(localPath("skills/demo/SKILL.md")), StandardCharsets.UTF_8));
    }
    @Test public void freshContainerCannotSilentlyResurrectPreviouslyDeletedSkillFromOldImage() throws Exception {
        central("skills/demo/SKILL.md", "old-image"); exchange(); Files.delete(roots.get("skills").resolve("demo/SKILL.md")); exchange();
        client = new ResourceReplica(temp.newFolder("fresh").toPath()); client.bind("set-one"); local("skills/demo/SKILL.md", "old-image"); exchange();
        assertEquals(1, client.conflictCount()); assertFalse(Files.exists(roots.get("skills").resolve("demo/SKILL.md")));
    }
    @Test public void conflictingEditsArePreservedAndNewEditExplicitlyResolves() throws Exception {
        central("skills/demo/SKILL.md", "base"); exchange();
        central("skills/demo/SKILL.md", "center-edit"); local("skills/demo/SKILL.md", "container-edit");
        assertEquals(1, exchange().getAsJsonObject("conflicts").size()); assertEquals(1, client.conflictCount());
        assertEquals("container-edit", new String(Files.readAllBytes(localPath("skills/demo/SKILL.md")), StandardCharsets.UTF_8));
        assertEquals("center-edit", new String(Files.readAllBytes(roots.get("skills").resolve("demo/SKILL.md")), StandardCharsets.UTF_8));
        client = new ResourceReplica(data); client.bind("set-one"); exchange(); assertEquals(1, client.conflictCount());
        local("skills/demo/SKILL.md", "merged"); exchange(); assertEquals(0, client.conflictCount());
        assertEquals("merged", new String(Files.readAllBytes(roots.get("skills").resolve("demo/SKILL.md")), StandardCharsets.UTF_8));
    }
    @Test public void inFlightEditIsNeverOverwrittenAndCanBeUploadedNextRound() throws Exception {
        central("memory/a.md", "base"); exchange();
        JsonObject request = client.request(); central("memory/a.md", "center"); JsonObject reply = server.exchange(request); reply.add("requestId", request.get("requestId"));
        local("memory/a.md", "in-flight"); client.apply(reply);
        assertEquals("in-flight", new String(Files.readAllBytes(client.root().resolve("memory/a.md")), StandardCharsets.UTF_8));
        exchange(); assertEquals(1, client.conflictCount());
    }
    @Test public void lostAcknowledgmentIsIdempotentAndRemoteDeletesPropagate() throws Exception {
        local("memory/a.md", "local"); JsonObject request = client.request(); server.exchange(request); // reply lost
        client = new ResourceReplica(data); client.bind("set-one"); exchange(); assertEquals(0, client.conflictCount());
        Files.delete(roots.get("memory").resolve("a.md")); exchange(); assertFalse(Files.exists(client.root().resolve("memory/a.md")));
    }
    @Test public void refusesTraversalSymlinksHashTamperingAndCrossSetReuse() throws Exception {
        try { ResourceFiles.resolve(roots.get("skills"), "../outside"); fail(); } catch (IOException expected) { }
        try { ResourceFiles.resolve(roots.get("skills"), "dir/../../outside"); fail(); } catch (IOException expected) { }
        try { ResourceFiles.resolve(roots.get("skills"), "C:/outside"); fail(); } catch (IOException expected) { }
        Path outside = temp.newFolder("outside").toPath();
        try { Files.createSymbolicLink(roots.get("skills").resolve("link"), outside); try { ResourceFiles.resolve(roots.get("skills"), "link/file"); fail(); } catch (IOException expected) { } }
        catch (UnsupportedOperationException e) { }
        JsonObject bad = ResourceFiles.entry(bytes("x")); bad.addProperty("hash", "bad");
        try { ResourceFiles.decode(bad); fail(); } catch (IOException expected) { }
        try { client.bind("other"); fail(); } catch (IOException expected) { }
    }
    @Test public void emptyReplicaDoesNotDeleteCenterAndRejectsOversizedFile() throws Exception {
        central("memory/a.md", "keep"); exchange();
        assertTrue(Files.exists(roots.get("memory").resolve("a.md")));
        ResourceFiles.write(localPath("skills/large"), new byte[ResourceFiles.MAX_FILE + 1]);
        try { client.request(); fail(); } catch (IOException expected) { }
    }
}
