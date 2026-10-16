package com.mola.cmd.proxy.app.acp.acpclient.context;

import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.starweave.StarweaveIdentity;
import org.junit.Rule;
import org.junit.Test;
import org.junit.Assume;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class ConversationHistoryManagerTest {

    @Test
    public void interruptedSegmentsPreservePartialModelReplyAndDoNotLeakIntoNextTurn() throws Exception {
        Path root = temporaryFolder.newFolder("interrupted-segments").toPath();
        AcpClientIdentity identity = teamIdentity("team/team-1/member-interrupted");
        ConversationHistoryManager manager = new ConversationHistoryManager(identity, root);
        manager.addUserMessage("first");
        manager.appendUiAssistant("before");
        manager.addEventMessage("TOOL_CALL", new JsonObject());
        manager.appendUiAssistant("partial");
        manager.finishUiAssistant();
        manager.finishUiAssistant();
        manager.flushTurn("s");
        manager.addUserMessage("next");
        manager.appendUiAssistant("next reply");
        manager.addAssistantMessage("next reply");
        manager.flushTurn("s");
        ConversationHistoryManager restored = new ConversationHistoryManager(identity, root);
        List<String> model = restored.getFullHistory("s").stream()
                .filter(message -> message.getRole() == ContextMessage.Role.ASSISTANT)
                .map(ContextMessage::getContent).collect(java.util.stream.Collectors.toList());
        assertEquals(Arrays.asList("beforepartial", "next reply"), model);
        List<String> ui = restored.getUiHistory("s").stream()
                .filter(message -> message.getRole() == ContextMessage.Role.ASSISTANT)
                .map(ContextMessage::getContent).collect(java.util.stream.Collectors.toList());
        assertEquals(Arrays.asList("before", "partial", "next reply"), ui);
    }

    @Test
    public void forcedFlushPersistsTheLastStreamingSegment() throws Exception {
        Path root = temporaryFolder.newFolder("forced-segments").toPath();
        AcpClientIdentity identity = teamIdentity("team/team-1/member-forced");
        ConversationHistoryManager manager = new ConversationHistoryManager(identity, root);
        manager.appendUiAssistant("interrupted before completion");
        manager.forceFlush("s");
        ConversationHistoryManager restored = new ConversationHistoryManager(identity, root);
        assertEquals(1, restored.getUiHistory("s").size());
        assertEquals("interrupted before completion", restored.getUiHistory("s").get(0).getContent());
        assertEquals(1, restored.getFullHistory("s").stream()
                .filter(message -> message.getRole() == ContextMessage.Role.ASSISTANT).count());
    }

    @Test
    public void uiSegmentsKeepToolBoundariesAndModelReplySurvivesReloadWithoutUiDuplication() throws Exception {
        Path root = temporaryFolder.newFolder("segmented-history").toPath();
        AcpClientIdentity identity = teamIdentity("team/team-1/member-segments");
        ConversationHistoryManager manager = new ConversationHistoryManager(identity, root);
        manager.addUserMessage("question");
        ContextMessage before = manager.appendUiAssistant("before");
        JsonObject tool = new JsonObject();
        tool.addProperty("toolCallId", "tool-1");
        manager.addEventMessage("TOOL_CALL", tool);
        ContextMessage after = manager.appendUiAssistant("after");
        assertNotEquals(before.getMessageId(), after.getMessageId());
        List<ContextMessage> live = manager.getUiHistory("s");
        assertEquals("before", live.get(1).getContent());
        assertEquals("TOOL_CALL", live.get(2).getEventType());
        assertEquals("after", live.get(3).getContent());
        manager.addAssistantMessage("beforeafter");
        manager.flushTurn("s");
        ConversationHistoryManager restored = new ConversationHistoryManager(identity, root);
        List<ContextMessage> ui = restored.getUiHistory("s");
        assertEquals(4, ui.size());
        assertEquals(before.getMessageId(), ui.get(1).getMessageId());
        assertEquals("before", ui.get(1).getContent());
        assertEquals("TOOL_CALL", ui.get(2).getEventType());
        assertEquals(after.getMessageId(), ui.get(3).getMessageId());
        assertEquals("after", ui.get(3).getContent());
        List<ContextMessage> model = restored.getFullHistory("s").stream()
                .filter(message -> message.getRole() == ContextMessage.Role.ASSISTANT)
                .collect(java.util.stream.Collectors.toList());
        assertEquals(1, model.size());
        assertEquals("beforeafter", model.get(0).getContent());
        // A subsequent non-streamed reply remains visible, including old-format history.
        manager.addUserMessage("next");
        manager.addAssistantMessage("legacy reply");
        manager.flushTurn("s");
        assertEquals("legacy reply", restored.getUiHistory("s").get(5).getContent());
    }

    @Test
    public void starweaveHistoryUsesValidatedLayeredNamespace() throws Exception {
        Path root = temporaryFolder.newFolder("starweave-root").toPath();
        AcpClientIdentity identity = StarweaveIdentity.identity(
                "instance-a", "Robot/One");

        ConversationHistoryManager manager =
                new ConversationHistoryManager(identity, root);

        assertTrue(manager.getSessionBaseDir().startsWith(
                root.resolve("starweave")));
        assertEquals(2, manager.getSessionBaseDir().getNameCount()
                - root.toAbsolutePath().normalize().getNameCount());
    }

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void teamIdentityUsesHierarchicalNamespaceBelowSessionRoot() {
        AcpClientIdentity identity = teamIdentity("team/team-1/member-1");
        ConversationHistoryManager manager = new ConversationHistoryManager(identity);

        Path path = manager.getSessionBaseDir();
        assertTrue(path.endsWith("team/team-1/member-1"));
        assertTrue(path.isAbsolute());
    }

    @Test
    public void mainIdentityKeepsLegacySingleDirectorySanitization() {
        AcpClientIdentity identity =
                AcpClientIdentity.main("group-1", "Robot/One", "Robot/One");
        ConversationHistoryManager manager = new ConversationHistoryManager(identity);

        assertTrue(manager.getSessionBaseDir().endsWith("Robot-One"));
        assertFalse(manager.getSessionBaseDir().endsWith("Robot/One"));
    }

    @Test
    public void mainIdentitySupportsPureUnicodeRobotName() {
        AcpClientIdentity identity =
                AcpClientIdentity.main("group-1", "结算专家", "结算专家");
        ConversationHistoryManager manager = new ConversationHistoryManager(identity);

        assertTrue(manager.getSessionBaseDir().getFileName().toString()
                .startsWith("unicode-"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void teamIdentityRejectsParentTraversal() {
        new ConversationHistoryManager(teamIdentity("team/team-1/../member-1"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void teamIdentityRejectsAbsolutePath() {
        new ConversationHistoryManager(teamIdentity("/tmp/team/member-1"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void teamIdentityRejectsWindowsAbsolutePath() {
        new ConversationHistoryManager(teamIdentity("C:\\temp\\team\\member-1"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void teamIdentityRejectsEmptySegment() {
        new ConversationHistoryManager(teamIdentity("team//member-1"));
    }

    @Test
    public void pendingMemoryMarkerIsVersionedAndClearedOnlyByCompletedVersion()
            throws Exception {
        ConversationHistoryManager manager = new ConversationHistoryManager(
                AcpClientIdentity.main("group-1", "Robot One", "Robot One"),
                temporaryFolder.newFolder("sessions").toPath());
        manager.addUserMessage("first");
        manager.addAssistantMessage("answer");
        manager.flushTurn("session-1");

        assertTrue(manager.markMemoryExtractionPending("session-1", 1));
        assertTrue(manager.markMemoryExtractionPending("session-1", 2));
        assertTrue(manager.markMemoryExtractionPending("session-1", 1));

        List<ConversationHistoryManager.PendingMemoryExtraction> pending =
                manager.listPendingMemoryExtractions();
        assertEquals(1, pending.size());
        assertEquals("session-1", pending.get(0).getSessionId());
        assertEquals(2, pending.get(0).getTurnCount());
        assertFalse(manager.clearMemoryExtractionPending("session-1", 1));
        assertEquals(1, manager.listPendingMemoryExtractions().size());
        assertTrue(manager.clearMemoryExtractionPending("session-1", 2));
        assertTrue(manager.listPendingMemoryExtractions().isEmpty());
    }

    @Test
    public void lastMessageTimeExistsOnlyAfterATurnIsPersisted() throws Exception {
        ConversationHistoryManager manager = new ConversationHistoryManager(
                AcpClientIdentity.main("group-2", "Robot Two", "Robot Two"),
                temporaryFolder.newFolder("activity-sessions").toPath());

        assertEquals(0L, manager.getLastMessageAt("session-2"));
        manager.addUserMessage("hello");
        manager.addAssistantMessage("world");
        manager.flushTurn("session-2");

        assertTrue(manager.getLastMessageAt("session-2") > 0L);
        assertEquals(0L, manager.getLastMessageAt("missing-session"));
    }

    @Test
    public void structuredUiEventsSurviveFlushAndReload() throws Exception {
        Path root = temporaryFolder.newFolder("event-sessions").toPath();
        AcpClientIdentity identity = teamIdentity("team/team-1/member-event");
        ConversationHistoryManager writer =
                new ConversationHistoryManager(identity, root);
        JsonObject data = new JsonObject();
        data.addProperty("eventType", "COMPACTION_COMPLETED");
        data.addProperty("provider", "codex");

        writer.addUserMessage("hello");
        writer.addEventMessage("COMPACTION_EVENT", data);
        writer.addAssistantMessage("done");
        writer.flushTurn("session-event");

        ConversationHistoryManager reader =
                new ConversationHistoryManager(identity, root);
        List<ContextMessage> history = reader.getFullHistory("session-event");
        assertEquals(3, history.size());
        assertEquals(ContextMessage.Role.EVENT, history.get(1).getRole());
        assertEquals("COMPACTION_EVENT", history.get(1).getEventType());
        assertEquals("codex", history.get(1).getEventData().get("provider").getAsString());
    }

    @Test
    public void internalUserOriginsSurviveFlushWithoutChangingModelRole() throws Exception {
        Path root = temporaryFolder.newFolder("origin-sessions").toPath();
        AcpClientIdentity identity = teamIdentity("team/team-1/member-origin");
        ConversationHistoryManager writer = new ConversationHistoryManager(identity, root);
        writer.addUserMessage("[Starweave Task] internal",
                ContextMessage.UserOrigin.TASK);
        writer.addAssistantMessage("done");
        writer.flushTurn("session-origin");

        ConversationHistoryManager reader = new ConversationHistoryManager(identity, root);
        ContextMessage message = reader.getFullHistory("session-origin").get(0);
        assertEquals(ContextMessage.Role.USER, message.getRole());
        assertEquals(ContextMessage.UserOrigin.TASK, message.getUserOrigin());
        assertFalse(message.isVisibleUserMessage());
    }

    @Test
    public void userAttachmentNamesSurviveFlushAndReload() throws Exception {
        Path root = temporaryFolder.newFolder("attachment-sessions").toPath();
        AcpClientIdentity identity = teamIdentity("team/team-1/member-attachments");
        ConversationHistoryManager writer = new ConversationHistoryManager(identity, root);
        writer.addUserMessage("please inspect", ContextMessage.UserOrigin.USER,
                java.util.Arrays.asList("screen.png", "notes.txt"));
        writer.addAssistantMessage("done");
        writer.flushTurn("session-attachments");

        ConversationHistoryManager reader = new ConversationHistoryManager(identity, root);
        ContextMessage message = reader.getFullHistory("session-attachments").get(0);
        assertEquals(java.util.Arrays.asList("screen.png", "notes.txt"),
                message.getAttachments());
    }

    @Test
    public void sameNamedAttachmentsAreStoredSeparatelyAcrossBatches() throws Exception {
        Path root = temporaryFolder.newFolder("same-name-sessions").toPath();
        ConversationHistoryManager manager = new ConversationHistoryManager(
                AcpClientIdentity.main("group-files", "Robot Files", "Robot Files"), root);
        String sessionId = "session-files";

        List<String> firstBatch = manager.saveFiles(sessionId, Arrays.asList(
                encodedFile("image.png", "first"),
                encodedFile("image.png", "second")));
        List<String> secondBatch = manager.saveFiles(sessionId,
                Collections.singletonList(encodedFile("image.png", "third")));

        assertEquals(Arrays.asList("image.png", "image-2.png"), fileNames(firstBatch));
        assertEquals(Collections.singletonList("image-3.png"), fileNames(secondBatch));
        assertEquals("first", readUtf8(firstBatch.get(0)));
        assertEquals("second", readUtf8(firstBatch.get(1)));
        assertEquals("third", readUtf8(secondBatch.get(0)));
        assertEquals(3, manager.getFileAbsolutePaths().size());
    }

    @Test
    public void repeatedSessionDirectoryScansDoNotLeakFileDescriptors()
            throws Exception {
        Path procFds = Paths.get("/proc/self/fd");
        Assume.assumeTrue("requires Linux procfs", Files.isDirectory(procFds));
        ConversationHistoryManager manager = new ConversationHistoryManager(
                AcpClientIdentity.main("group-fd", "Robot FD", "Robot FD"),
                temporaryFolder.newFolder("fd-sessions").toPath());
        for (int i = 0; i < 3; i++) {
            String sessionId = "session-" + i;
            manager.addUserMessage("meaningful message " + i);
            manager.addAssistantMessage("answer " + i);
            manager.flushTurn(sessionId);
            Path files = manager.getSessionBaseDir().resolve(sessionId).resolve("files");
            Files.createDirectories(files);
            Files.write(files.resolve("attachment.txt"),
                    "attachment".getBytes(StandardCharsets.UTF_8));
        }

        scanSessionDirectories(manager);
        manager.restoreState("session-0");
        long before = openFileDescriptorCount(procFds);
        for (int i = 0; i < 200; i++) scanSessionDirectories(manager);
        for (int i = 0; i < 10; i++) manager.restoreState("session-0");
        long after = openFileDescriptorCount(procFds);

        assertTrue("session scans leaked file descriptors: before=" + before
                + ", after=" + after, after <= before + 4L);
    }

    private static void scanSessionDirectories(ConversationHistoryManager manager) {
        manager.listRecentSessions(50);
        manager.findLatestSessionId();
        manager.getFullHistory("session-0");
        manager.loadFilePaths("session-0");
    }

    private static Map<String, String> encodedFile(String name, String content) {
        Map<String, String> file = new LinkedHashMap<>();
        file.put(name, Base64.getEncoder().encodeToString(
                content.getBytes(StandardCharsets.UTF_8)));
        return file;
    }

    private static List<String> fileNames(List<String> paths) {
        List<String> names = new java.util.ArrayList<>();
        for (String path : paths) names.add(Paths.get(path).getFileName().toString());
        return names;
    }

    private static String readUtf8(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static long openFileDescriptorCount(Path procFds) throws Exception {
        try (Stream<Path> descriptors = Files.list(procFds)) {
            return descriptors.count();
        }
    }

    private static AcpClientIdentity teamIdentity(String namespace) {
        return AcpClientIdentity.team(
                "team-acp-member-1",
                "team-acp-instance-1",
                namespace,
                "chatter-1",
                "team-1",
                "member-1",
                "Source Robot");
    }
}
