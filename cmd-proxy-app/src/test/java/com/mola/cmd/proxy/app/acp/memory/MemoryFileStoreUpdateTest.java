package com.mola.cmd.proxy.app.acp.memory;

import com.mola.cmd.proxy.app.acp.memory.model.MemoryAction;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryEntry;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryIndex;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MemoryFileStoreUpdateTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private MemoryFileStore store;
    private String workspace;

    @Before
    public void setUp() throws Exception {
        workspace = temporaryFolder.newFolder("workspace").getAbsolutePath();
        store = new MemoryFileStore(
                temporaryFolder.newFolder("memory").getAbsolutePath());

        MemoryEntry entry = new MemoryEntry();
        entry.setId("memory_001");
        entry.setType("project");
        entry.setTitle("安全更新");
        entry.setSummary("旧概要");
        entry.setDetail("旧 detail 中的重要背景和边界");
        entry.setTags(new ArrayList<>(Arrays.asList("memory", "update")));
        entry.setRelatedSkills(new ArrayList<>(Collections.singletonList("cmd-proxy")));
        entry.setCreatedAt("2026-08-25T00:00:00+08:00");
        entry.setUpdatedAt("2026-08-25T00:00:00+08:00");
        store.writeDetail(workspace, entry);

        MemoryIndex index = new MemoryIndex();
        index.getMemories().add(entry);
        store.saveIndex(workspace, index);
    }

    @Test
    public void summaryOnlyUpdatePreservesOldDetail() throws Exception {
        MemoryAction action = updateAction();
        action.setSummary("新的决策概要");

        apply(action);

        MemoryEntry updated = currentEntry();
        assertEquals("新的决策概要", updated.getSummary());
        assertEquals("旧 detail 中的重要背景和边界",
                store.readDetailBody(updated));
    }

    @Test
    public void detailAppendAddsNewSectionWithoutReplacingOldDetail() throws Exception {
        MemoryAction action = updateAction();
        action.setDetailAppend("新增限制：只有写入开启时才更新访问统计。");

        apply(action);

        String detail = store.readDetailBody(currentEntry());
        assertTrue(detail.startsWith("旧 detail 中的重要背景和边界"));
        assertTrue(detail.matches("(?s).*## \\d{4}-\\d{2}-\\d{2} 更新.*"));
        assertTrue(detail.contains("新增限制：只有写入开启时才更新访问统计。"));
    }

    @Test
    public void explicitEmptyListsClearTagsAndSkillsWhileKeepingDetail() throws Exception {
        MemoryAction action = updateAction();
        action.setTags(Collections.emptyList());
        action.setRelatedSkills(Collections.emptyList());

        apply(action);

        MemoryEntry updated = currentEntry();
        assertTrue(updated.getTags().isEmpty());
        assertTrue(updated.getRelatedSkills().isEmpty());
        assertEquals("旧 detail 中的重要背景和边界",
                store.readDetailBody(updated));
    }

    @Test
    public void updateDetailFieldIsIgnoredInsteadOfReplacingOldDetail() throws Exception {
        MemoryAction action = updateAction();
        action.setDetail("UPDATE 不再接受的完整 detail");

        apply(action);

        String detail = store.readDetailBody(currentEntry());
        assertEquals("旧 detail 中的重要背景和边界", detail);
        assertFalse(detail.contains("UPDATE 不再接受的完整 detail"));
    }

    @Test
    public void renameArchivesOldFileAndKeepsIdentityAndFullDetail() throws Exception {
        Path oldFile = Paths.get(currentEntry().getFile());
        byte[] before = Files.readAllBytes(oldFile);
        MemoryAction action = updateAction();
        action.setTitle("运行环境实测基线");
        action.setDetailAppend("新内容");
        apply(action);
        MemoryEntry updated = currentEntry();
        assertEquals("memory_001", updated.getId());
        assertEquals("运行环境实测基线", updated.getTitle());
        assertFalse(Files.exists(oldFile));
        assertTrue(Files.exists(Paths.get(updated.getFile())));
        assertTrue(store.readDetailBody(updated).contains("旧 detail 中的重要背景和边界"));
        assertTrue(store.readDetailBody(updated).contains("新内容"));
        try (Stream<Path> files = Files.list(scope().resolve("archive"))) {
            Path archived = files.findFirst().get();
            org.junit.Assert.assertArrayEquals(before, Files.readAllBytes(archived));
        }
        assertActiveFileCount(1);
    }

    @Test
    public void typeChangeMovesFileAndRepeatedRenamesDoNotClobberArchives() throws Exception {
        Path first = Paths.get(currentEntry().getFile());
        MemoryAction action = updateAction();
        action.setType("reference");
        apply(action);
        assertFalse(Files.exists(first));
        assertTrue(currentEntry().getFile().contains("reference_"));
        action.setType("project");
        apply(action);
        action.setType("reference");
        apply(action);
        assertActiveFileCount(1);
        try (Stream<Path> files = Files.list(scope().resolve("archive"))) {
            assertEquals(3, files.count());
        }
        assertEquals("memory_001", currentEntry().getId());
    }

    @Test
    public void unchangedPathDoesNotLeaveBackupOrJournal() throws Exception {
        Path oldFile = Paths.get(currentEntry().getFile());
        MemoryAction action = updateAction();
        action.setSummary("updated");
        apply(action);
        assertEquals(oldFile, Paths.get(currentEntry().getFile()));
        assertActiveFileCount(1);
        assertFalse(Files.exists(scope().resolve(".memory-update.properties")));
        try (Stream<Path> files = Files.list(scope().resolve("archive"))) {
            assertEquals(0, files.count());
        }
    }

    @Test
    public void targetCollisionPreservesBothFilesAndWorkingIndex() throws Exception {
        MemoryEntry original = currentEntry();
        Path target = Paths.get(original.getFile()).resolveSibling("project_occupied.md");
        byte[] unrelated = "unrelated record".getBytes(StandardCharsets.UTF_8);
        Files.write(target, unrelated);
        MemoryIndex working = store.loadIndex(workspace);
        try {
            store.updateMemory(workspace, working, working.getMemories().get(0), next -> next.setTitle("occupied"));
            fail("collision must be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getCause().getMessage().contains("target already exists"));
        }
        assertEquals(original.getFile(), currentEntry().getFile());
        assertEquals(original.getTitle(), working.getMemories().get(0).getTitle());
        org.junit.Assert.assertArrayEquals(unrelated, Files.readAllBytes(target));
        assertTrue(Files.exists(Paths.get(original.getFile())));
    }

    @Test
    public void indexWriteFailureRollsBackRenameAndWorkingIndex() throws Exception {
        MemoryEntry original = currentEntry();
        MemoryIndex working = store.loadIndex(workspace);
        MemoryFileStore failing = faultStore("MEMORY_INDEX.json", false, false);
        try {
            failing.updateMemory(workspace, working, working.getMemories().get(0), next -> next.setTitle("new-name"));
            fail("write failure must propagate");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getCause().getMessage().contains("injected"));
        }
        assertEquals(original.getTitle(), working.getMemories().get(0).getTitle());
        assertEquals(original.getFile(), currentEntry().getFile());
        assertTrue(Files.exists(Paths.get(original.getFile())));
        assertActiveFileCount(1);
        assertFalse(Files.exists(scope().resolve(".memory-update.properties")));
    }

    @Test
    public void detailWriteFailureDoesNotPublishIndexOrLeaveNewFile() throws Exception {
        MemoryIndex working = store.loadIndex(workspace);
        MemoryFileStore failing = faultStore("project_new_name.md", false, false);
        try {
            failing.updateMemory(workspace, working, working.getMemories().get(0), next -> next.setTitle("new-name"));
            fail("write failure must propagate");
        } catch (IllegalStateException expected) { }
        assertEquals("安全更新", currentEntry().getTitle());
        assertActiveFileCount(1);
    }

    @Test
    public void reloadRollsBackRenameInterruptedBeforeIndexCommit() throws Exception {
        simulateCrash(false);
        assertEquals("安全更新", currentEntry().getTitle());
        assertTrue(Files.exists(Paths.get(currentEntry().getFile())));
        assertActiveFileCount(1);
        assertFalse(Files.exists(scope().resolve(".memory-update.properties")));
    }

    @Test
    public void reloadCompletesRenameInterruptedAfterIndexCommit() throws Exception {
        simulateCrash(true);
        assertEquals("new-name", currentEntry().getTitle());
        assertTrue(Files.exists(Paths.get(currentEntry().getFile())));
        assertActiveFileCount(1);
        assertFalse(Files.exists(scope().resolve(".memory-update.properties")));
    }

    @Test
    public void dreamRenameUsesTheSameSafeMigration() throws Exception {
        com.mola.cmd.proxy.app.acp.memory.model.DreamAction action =
                new com.mola.cmd.proxy.app.acp.memory.model.DreamAction();
        action.setAction(com.mola.cmd.proxy.app.acp.memory.model.DreamAction.ActionType.UPDATE);
        action.setId("memory_001");
        action.setFields(Collections.singletonMap("title", "Dream 改名"));
        MemoryDreamer dreamer = new MemoryDreamer(
                new com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig(), store,
                new com.mola.cmd.proxy.app.acp.AcpRobotParam());
        java.lang.reflect.Method apply = MemoryDreamer.class.getDeclaredMethod(
                "applyDreamActions", String.class, java.util.List.class, MemoryIndex.class);
        apply.setAccessible(true);
        Path oldFile = Paths.get(currentEntry().getFile());
        int[] result = (int[]) apply.invoke(dreamer, workspace, Collections.singletonList(action), store.loadIndex(workspace));
        assertEquals(1, result[2]);
        assertEquals("Dream 改名", currentEntry().getTitle());
        assertFalse(Files.exists(oldFile));
        assertActiveFileCount(1);
    }

    private void simulateCrash(boolean afterCommit) throws Exception {
        MemoryIndex working = store.loadIndex(workspace);
        try {
            faultStore("MEMORY_INDEX.json", true, afterCommit).updateMemory(workspace, working,
                    working.getMemories().get(0), next -> next.setTitle("new-name"));
            fail("expected process interruption");
        } catch (SimulatedCrash expected) { }
        assertTrue(Files.exists(scope().resolve(".memory-update.properties")));
        store = new MemoryFileStore(temporaryFolder.getRoot().toPath().resolve("memory").toString());
    }

    private MemoryFileStore faultStore(String file, boolean crash, boolean afterWrite) {
        return new MemoryFileStore(temporaryFolder.getRoot().toPath().resolve("memory").toString()) {
            @Override protected void writeAtomically(Path path, byte[] bytes) throws IOException {
                if (path.getFileName().toString().equals(file)) {
                    if (afterWrite) super.writeAtomically(path, bytes);
                    if (crash) throw new SimulatedCrash();
                    throw new IOException("injected write failure");
                }
                super.writeAtomically(path, bytes);
            }
        };
    }

    private static class SimulatedCrash extends Error { }

    private Path scope() {
        return Paths.get(store.getStorageKey(workspace));
    }

    private void assertActiveFileCount(long expected) throws IOException {
        try (Stream<Path> files = Files.list(scope().resolve("memories"))) {
            assertEquals(expected, files.filter(Files::isRegularFile).count());
        }
    }

    private MemoryAction updateAction() {
        MemoryAction action = new MemoryAction();
        action.setAction(MemoryAction.ActionType.UPDATE);
        action.setId("memory_001");
        return action;
    }

    private void apply(MemoryAction action) {
        MemoryIndex current = store.loadIndex(workspace);
        store.applyActions(workspace, Collections.singletonList(action), current, 30);
    }

    private MemoryEntry currentEntry() {
        return store.loadIndex(workspace).getMemories().get(0);
    }
}
