package com.mola.starweave.sdk.sync;

import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.Assert.*;

public class SessionSyncTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path central, data; private SessionAuthority authority; private SessionReplica replica;
    @Before public void setup() throws Exception {
        central = temp.newFolder("central").toPath(); data = temp.newFolder("sdk").toPath();
        authority = new SessionAuthority(central); replica = new SessionReplica(data); replica.bind("set-one");
    }
    private byte[] snapshot(String id, String text) {
        JsonObject s = new JsonObject(); s.addProperty("version", 1); s.addProperty("sessionId", id); s.addProperty("cwd", "/workspace");
        JsonArray messages = new JsonArray(); JsonObject m = new JsonObject(); m.addProperty("role", "user"); m.addProperty("content", text); messages.add(m);
        s.add("messages", messages); s.add("history", messages.deepCopy()); return s.toString().getBytes(StandardCharsets.UTF_8);
    }
    private Path local(String id) throws Exception { return SessionSnapshots.file(data.resolve("java-sessions"), id); }
    private void write(String id, String text) throws Exception { SessionSnapshots.write(local(id), snapshot(id, text)); }
    @Test public void uploadsThenNewEmptyReplicaRestoresOnlyRequestedSnapshot() throws Exception {
        write("one", "original"); write("two", "other"); replica.upload(authority::exchange);
        assertArrayEquals(Files.readAllBytes(local("one")), Files.readAllBytes(central.resolve("one.json")));
        Path replacement = temp.newFolder("replacement").toPath(); replica = new SessionReplica(replacement); replica.bind("set-one");
        replica.prepare("one", authority::exchange);
        assertArrayEquals(snapshot("one", "original"), Files.readAllBytes(replacement.resolve("java-sessions/one.json")));
        assertFalse(Files.exists(replacement.resolve("java-sessions/two.json")));
    }
    @Test public void localCleanupNeverDeletesBackupAndRetainedBaselineRestores() throws Exception {
        write("one", "keep"); replica.upload(authority::exchange); Files.delete(local("one"));
        replica = new SessionReplica(data); replica.bind("set-one"); replica.upload(authority::exchange);
        assertTrue(Files.exists(central.resolve("one.json"))); replica.prepare("one", authority::exchange);
        assertArrayEquals(snapshot("one", "keep"), Files.readAllBytes(local("one")));
    }
    @Test public void acknowledgmentLossRetriesIdempotently() throws Exception {
        write("one", "first");
        try { replica.upload(request -> { authority.exchange(request); throw new IOException("reply lost"); }); fail(); } catch (IOException expected) { }
        replica = new SessionReplica(data); replica.bind("set-one"); replica.upload(authority::exchange);
        write("one", "next"); replica.upload(authority::exchange);
        assertArrayEquals(snapshot("one", "next"), Files.readAllBytes(central.resolve("one.json")));
    }
    @Test public void concurrentVersionsArePreservedWithoutOverwritingEitherSide() throws Exception {
        write("one", "base"); replica.upload(authority::exchange);
        byte[] remote = snapshot("one", "center"), local = snapshot("one", "remote");
        SessionSnapshots.write(central.resolve("one.json"), remote); SessionSnapshots.write(local("one"), local);
        try { replica.prepare("one", authority::exchange); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("冲突")); }
        assertArrayEquals(remote, Files.readAllBytes(central.resolve("one.json"))); assertArrayEquals(local, Files.readAllBytes(local("one")));
        assertArrayEquals(local, Files.readAllBytes(central.resolve("conflicts/one/" + ResourceFiles.hash(local) + ".json")));
    }
    @Test public void backgroundNeverDownloadsOverLocalSessionAndPrepareCanUpdateIdleFile() throws Exception {
        write("one", "base"); replica.upload(authority::exchange);
        SessionSnapshots.write(central.resolve("one.json"), snapshot("one", "new-center"));
        replica.upload(authority::exchange); assertArrayEquals(snapshot("one", "base"), Files.readAllBytes(local("one")));
        replica.prepare("one", authority::exchange); assertArrayEquals(snapshot("one", "new-center"), Files.readAllBytes(local("one")));
    }
    @Test public void editsDuringUploadRemainPendingAndDuringDownloadAreNeverOverwritten() throws Exception {
        write("one", "first");
        replica.upload(request -> { JsonObject result = authority.exchange(request); SessionSnapshots.write(SessionSnapshots.file(data.resolve("java-sessions"), "one"), snapshot("one", "inflight")); return result; });
        replica.upload(authority::exchange); assertArrayEquals(snapshot("one", "inflight"), Files.readAllBytes(central.resolve("one.json")));
        SessionSnapshots.write(central.resolve("one.json"), snapshot("one", "center"));
        try {
            replica.prepare("one", request -> { JsonObject result = authority.exchange(request); SessionSnapshots.write(SessionSnapshots.file(data.resolve("java-sessions"), "one"), snapshot("one", "editing")); return result; }); fail();
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("下载期间")); }
        assertArrayEquals(snapshot("one", "editing"), Files.readAllBytes(local("one")));
    }
    @Test public void largeSnapshotDoesNotUseSkillLimitsAndBadSessionDoesNotStarveOthers() throws Exception {
        write("large", new String(new char[600000]).replace('\0', 'x')); write("valid", "ok");
        SessionSnapshots.write(local("broken"), "invalid".getBytes(StandardCharsets.UTF_8));
        try { replica.upload(authority::exchange); fail(); } catch (IOException expected) { }
        assertTrue(Files.size(central.resolve("large.json")) > ResourceFiles.MAX_FILE); assertTrue(Files.exists(central.resolve("valid.json")));
    }
    @Test public void batchesYieldForRestoreAndKeepEarlierErrorsVisibleUntilRepaired() throws Exception {
        for (int i = 0; i < 10; i++) write("s" + i, "valid");
        SessionSnapshots.write(local("s7"), "broken".getBytes(StandardCharsets.UTF_8));
        try { replica.uploadBatch(authority::exchange); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("s7")); }
        assertFalse(Files.exists(central.resolve("s8.json")));
        try { replica.uploadBatch(authority::exchange); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("s7")); }
        assertTrue(Files.exists(central.resolve("s8.json"))); assertTrue(Files.exists(central.resolve("s9.json")));
        write("s7", "repaired"); replica.uploadBatch(authority::exchange);
        assertTrue(Files.exists(central.resolve("s7.json")));
    }
    @Test public void rejectsWrongIdentityTraversalHashTamperingAndCrossSetReuse() throws Exception {
        try { replica.prepare("../escape", authority::exchange); fail(); } catch (IOException expected) { }
        try { replica.bind("set-two"); fail(); } catch (IOException expected) { }
        JsonObject request = new JsonObject(); request.addProperty("sessionId", "one"); request.addProperty("base", "");
        SessionSnapshots.content(request, snapshot("wrong", "text"));
        try { authority.exchange(request); fail(); } catch (IOException expected) { }
        SessionSnapshots.content(request, snapshot("one", "text")); request.addProperty("hash", "tampered");
        try { authority.exchange(request); fail(); } catch (IOException expected) { }
        assertFalse(Files.exists(central.resolve("one.json")));
    }
    @Test public void rejectsSymlinkAndOversizedSnapshot() throws Exception {
        Path outside = temp.newFile("outside").toPath(); Files.createDirectories(data.resolve("java-sessions"));
        Files.createSymbolicLink(local("link"), outside);
        try { replica.prepare("link", authority::exchange); fail(); } catch (IOException expected) { }
        try (RandomAccessFile file = new RandomAccessFile(local("large").toFile(), "rw")) { file.setLength(SessionSnapshots.MAX_BYTES + 1L); }
        try { replica.prepare("large", authority::exchange); fail(); } catch (IOException expected) { }
    }
}
