package com.mola.cmd.proxy.app.acp.memory;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MemoryExtractorSessionReuseTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void reusesDedicatedClientAndFinalExtractionOnlyReadsTail()
            throws Exception {
        RecordingFactory factory = new RecordingFactory(false);
        MemoryExtractor extractor = extractor(factory);
        String workspace = temporaryFolder.newFolder("workspace").getAbsolutePath();

        List<ContextMessage> first = messages("first-user-7c91", "first-agent-8d22");
        List<ContextMessage> second = messages(
                "first-user-7c91", "first-agent-8d22",
                "tail-user-1ab3", "tail-agent-2bc4");

        extractor.submitExtract("main-session", workspace, first);
        extractor.submitExtract("main-session", workspace, second);

        CountDownLatch completed = new CountDownLatch(1);
        extractor.submitExtractFull("main-session", workspace, second,
                completed::countDown, error -> completed.countDown());
        assertTrue(completed.await(5, TimeUnit.SECONDS));

        assertEquals(1, factory.created.get());
        RecordingClient client = factory.clients.get(0);
        assertEquals(2, client.prompts.size());
        assertTrue(client.prompts.get(0).contains("first-user-7c91"));
        assertTrue(client.prompts.get(1).contains("tail-user-1ab3"));
        assertFalse(client.prompts.get(1).contains("first-user-7c91"));
        assertEquals(1, client.closed.get());
        extractor.shutdown();
    }

    @Test
    public void recoveryWithoutCursorReadsFullHistoryAndClosesClient()
            throws Exception {
        RecordingFactory factory = new RecordingFactory(false);
        MemoryExtractor extractor = extractor(factory);
        String workspace = temporaryFolder.newFolder("recovery").getAbsolutePath();
        CountDownLatch completed = new CountDownLatch(1);

        extractor.submitExtractFull("recovered-session", workspace,
                messages("recover-user-3cd5", "recover-agent-4de6"),
                completed::countDown, error -> completed.countDown());
        assertTrue(completed.await(5, TimeUnit.SECONDS));

        assertEquals(1, factory.created.get());
        RecordingClient client = factory.clients.get(0);
        assertEquals(1, client.prompts.size());
        assertTrue(client.prompts.get(0).contains("recover-user-3cd5"));
        assertTrue(client.prompts.get(0).contains("recover-agent-4de6"));
        assertEquals(1, client.closed.get());
        extractor.shutdown();
    }

    @Test
    public void failedMemoryClientIsDiscardedAndCursorDoesNotAdvance()
            throws Exception {
        RecordingFactory factory = new RecordingFactory(true);
        MemoryExtractor extractor = extractor(factory);
        String workspace = temporaryFolder.newFolder("retry").getAbsolutePath();
        List<ContextMessage> history = messages("retry-user-5ef7", "retry-agent-6fa8");

        extractor.submitExtract("retry-session", workspace, history);
        CountDownLatch completed = new CountDownLatch(1);
        extractor.submitExtractFull("retry-session", workspace, history,
                completed::countDown, error -> completed.countDown());
        assertTrue(completed.await(5, TimeUnit.SECONDS));

        assertEquals(2, factory.created.get());
        assertEquals(1, factory.clients.get(0).closed.get());
        assertTrue(factory.clients.get(1).prompts.get(0).contains("retry-user-5ef7"));
        assertEquals(1, factory.clients.get(1).closed.get());
        extractor.shutdown();
    }

    @Test
    public void resumedSessionSkipsLoadedHistoryAndExtractsOnlyNewTail()
            throws Exception {
        RecordingFactory factory = new RecordingFactory(false);
        MemoryExtractor extractor = extractor(factory);
        String workspace = temporaryFolder.newFolder("resume").getAbsolutePath();
        List<ContextMessage> loaded = messages(
                "loaded-user-7ab1", "loaded-agent-8bc2");
        List<ContextMessage> extended = messages(
                "loaded-user-7ab1", "loaded-agent-8bc2",
                "new-user-9cd3", "new-agent-0de4");

        extractor.resumeSession("resumed-session", loaded.size());
        extractor.submitExtract("resumed-session", workspace, extended);
        CountDownLatch completed = new CountDownLatch(1);
        extractor.submitExtractFull("resumed-session", workspace, extended,
                completed::countDown, error -> completed.countDown());
        assertTrue(completed.await(5, TimeUnit.SECONDS));

        assertEquals(1, factory.created.get());
        RecordingClient client = factory.clients.get(0);
        assertEquals(1, client.prompts.size());
        assertTrue(client.prompts.get(0).contains("new-user-9cd3"));
        assertFalse(client.prompts.get(0).contains("loaded-user-7ab1"));
        extractor.shutdown();
    }

    @Test
    public void activeRecoveryKeepsClientAndContinuesFromRecoveredBaseline()
            throws Exception {
        RecordingFactory factory = new RecordingFactory(false);
        MemoryExtractor extractor = extractor(factory);
        String workspace = temporaryFolder.newFolder("active-recovery").getAbsolutePath();
        List<ContextMessage> recovered = messages(
                "recover-active-user-1ef5", "recover-active-agent-2fa6");
        List<ContextMessage> extended = messages(
                "recover-active-user-1ef5", "recover-active-agent-2fa6",
                "after-recover-user-3ab7", "after-recover-agent-4bc8");
        CountDownLatch recoveredDone = new CountDownLatch(1);

        extractor.submitRecoverActive("active-session", workspace, recovered,
                recoveredDone::countDown, error -> recoveredDone.countDown());
        assertTrue(recoveredDone.await(5, TimeUnit.SECONDS));
        extractor.submitExtract("active-session", workspace, extended);
        CountDownLatch completed = new CountDownLatch(1);
        extractor.submitExtractFull("active-session", workspace, extended,
                completed::countDown, error -> completed.countDown());
        assertTrue(completed.await(5, TimeUnit.SECONDS));

        assertEquals(1, factory.created.get());
        RecordingClient client = factory.clients.get(0);
        assertEquals(2, client.prompts.size());
        assertTrue(client.prompts.get(0).contains("recover-active-user-1ef5"));
        assertTrue(client.prompts.get(1).contains("after-recover-user-3ab7"));
        assertFalse(client.prompts.get(1).contains("recover-active-user-1ef5"));
        assertEquals(1, client.closed.get());
        extractor.shutdown();
    }

    @Test
    public void failedActiveRecoveryDoesNotAdvanceRestoredCursor()
            throws Exception {
        RecordingFactory factory = new RecordingFactory(true);
        MemoryExtractor extractor = extractor(factory);
        String workspace = temporaryFolder.newFolder("active-retry").getAbsolutePath();
        List<ContextMessage> history = messages(
                "active-retry-user-5cd9", "active-retry-agent-6dea");
        CountDownLatch recoveryFailed = new CountDownLatch(1);

        extractor.submitRecoverActive("active-retry-session", workspace, history,
                recoveryFailed::countDown, error -> recoveryFailed.countDown());
        assertTrue(recoveryFailed.await(5, TimeUnit.SECONDS));
        CountDownLatch completed = new CountDownLatch(1);
        extractor.submitExtractFull("active-retry-session", workspace, history,
                completed::countDown, error -> completed.countDown());
        assertTrue(completed.await(5, TimeUnit.SECONDS));

        assertEquals(2, factory.created.get());
        assertEquals(1, factory.clients.get(0).closed.get());
        assertTrue(factory.clients.get(1).prompts.get(0)
                .contains("active-retry-user-5cd9"));
        extractor.shutdown();
    }

    @Test
    public void robotExecutionUsesSelectedRobotAndItsWorkDir() throws Exception {
        MemoryConfig config = new MemoryConfig();
        config.setBaseDir(temporaryFolder.newFolder("robot-mode-memory").getAbsolutePath());
        config.setExecutionMode("robot");
        config.setRobotName("Disabled Memory Robot");
        config.setModel("must-not-override-selected-robot");
        config.setDreamEnabled(false);
        AcpRobotParam executionRobot = new AcpRobotParam();
        executionRobot.setName("Disabled Memory Robot");
        executionRobot.setEnabled(false);
        executionRobot.setAgentProvider("CLAUDE_AGENT_ACP");
        executionRobot.setModel("selected-model");
        String executionWorkDir = temporaryFolder.newFolder("execution-workspace")
                .getAbsolutePath();
        RecordingFactory factory = new RecordingFactory(false);
        MemoryExtractor extractor = new MemoryExtractor(config,
                new MemoryFileStore(config.getBaseDir()), executionRobot,
                executionWorkDir, new MemoryScopeLockRegistry(), factory);
        CountDownLatch completed = new CountDownLatch(1);

        extractor.submitExtractFull("robot-execution-session", "/source/workspace",
                messages("robot-mode-user", "robot-mode-agent"),
                completed::countDown, error -> completed.countDown());
        assertTrue(completed.await(5, TimeUnit.SECONDS));

        assertEquals(executionWorkDir, factory.workspacePath.get());
        assertEquals(executionRobot, factory.robotParam.get());
        assertEquals(null, config.getExecutionModel());
        extractor.shutdown();
    }

    @Test
    public void sleepRetainsClientAndCursorAndDoesNotAffectOtherSessions() throws Exception {
        RecordingFactory factory = new RecordingFactory(false);
        MemoryExtractor extractor = extractor(factory);
        String workspace = temporaryFolder.newFolder("sleep").getAbsolutePath();
        try {
            CountDownLatch ready = new CountDownLatch(2);
            extractor.submitRecoverActive("main", workspace, messages("old-user", "old-agent"),
                    ready::countDown, error -> ready.countDown());
            extractor.submitRecoverActive("other", workspace, messages("other-user", "other-agent"),
                    ready::countDown, error -> ready.countDown());
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            RecordingClient client = factory.clients.get(0);
            extractor.sleepSession("main");
            assertTrue(client.slept.await(5, TimeUnit.SECONDS));
            assertEquals(0, client.closed.get());
            assertEquals(0, factory.clients.get(1).sleeps.get());

            CountDownLatch done = new CountDownLatch(1);
            extractor.submitExtractFull("main", workspace,
                    messages("old-user", "old-agent", "new-user", "new-agent"),
                    done::countDown, error -> done.countDown());
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(2, factory.created.get());
            assertEquals(2, client.prompts.size());
            assertFalse(client.prompts.get(1).contains("old-user"));
            assertTrue(client.prompts.get(1).contains("new-user"));
        } finally {
            extractor.shutdown();
        }
    }

    @Test
    public void sleepSurvivesFullQueueAndWaitsForRunningExtraction() throws Exception {
        RecordingFactory factory = new RecordingFactory(false);
        factory.blockFirst = true;
        MemoryExtractor extractor = extractor(factory);
        String workspace = temporaryFolder.newFolder("busy-sleep").getAbsolutePath();
        try {
            extractor.submitExtract("main", workspace, messages("busy-user", "busy-agent"));
            assertTrue(factory.promptEntered.await(5, TimeUnit.SECONDS));
            RecordingClient client = factory.clients.get(0);
            // Fill all 16 queue slots while a model request is in progress.
            for (int i = 0; i < 16; i++) extractor.resumeSession("queued-" + i, 0);
            extractor.sleepSession("main");
            extractor.sleepSession("main");
            assertEquals(0, client.sleeps.get());
            assertEquals(0, client.closed.get());
            factory.releasePrompt.countDown();
            assertTrue(client.slept.await(5, TimeUnit.SECONDS));
            assertEquals(1, client.sleeps.get());
            assertEquals(1, client.prompts.size());
            assertEquals(0, client.closed.get());
        } finally {
            factory.releasePrompt.countDown();
            extractor.shutdown();
        }
    }

    @Test
    public void failedRestoreRetainsClientAndDoesNotAdvanceCursor() throws Exception {
        RecordingFactory factory = new RecordingFactory(false);
        MemoryExtractor extractor = extractor(factory);
        String workspace = temporaryFolder.newFolder("restore-failure").getAbsolutePath();
        List<ContextMessage> old = messages("before-sleep-user", "before-sleep-agent");
        List<ContextMessage> extended = messages("before-sleep-user", "before-sleep-agent",
                "retry-tail-user", "retry-tail-agent");
        try {
            CountDownLatch ready = new CountDownLatch(1);
            extractor.submitRecoverActive("main", workspace, old,
                    ready::countDown, error -> ready.countDown());
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            RecordingClient client = factory.clients.get(0);
            extractor.sleepSession("main");
            assertTrue(client.slept.await(5, TimeUnit.SECONDS));
            client.failRestoreNext = true;
            CountDownLatch failed = new CountDownLatch(1);
            extractor.submitExtractFull("main", workspace, extended,
                    () -> { }, error -> failed.countDown());
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            assertEquals(0, client.closed.get());
            CountDownLatch completed = new CountDownLatch(1);
            extractor.submitExtractFull("main", workspace, extended,
                    completed::countDown, error -> completed.countDown());
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(1, factory.created.get());
            assertEquals(3, client.prompts.size());
            assertTrue(client.prompts.get(2).contains("retry-tail-user"));
            assertFalse(client.prompts.get(2).contains("before-sleep-user"));
        } finally {
            extractor.shutdown();
        }
    }

    private MemoryExtractor extractor(RecordingFactory factory) throws Exception {
        MemoryConfig config = new MemoryConfig();
        config.setBaseDir(temporaryFolder.newFolder("memory").getAbsolutePath());
        config.setDreamEnabled(false);
        config.setSubClientTimeout(1);
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName("Memory Test Robot");
        return new MemoryExtractor(config,
                new MemoryFileStore(config.getBaseDir()), robot,
                new MemoryScopeLockRegistry(), factory);
    }

    private List<ContextMessage> messages(String... contents) {
        List<ContextMessage> result = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            result.add(new ContextMessage(
                    i % 2 == 0 ? ContextMessage.Role.USER
                            : ContextMessage.Role.ASSISTANT,
                    contents[i]));
        }
        return result;
    }

    private static final class RecordingFactory
            implements MemoryExtractor.MemoryClientFactory {
        private final boolean failFirstClient;
        private boolean blockFirst;
        private final CountDownLatch promptEntered = new CountDownLatch(1);
        private final CountDownLatch releasePrompt = new CountDownLatch(1);
        private final AtomicInteger created = new AtomicInteger();
        private final List<RecordingClient> clients = new ArrayList<>();
        private final AtomicReference<String> workspacePath = new AtomicReference<>();
        private final AtomicReference<AcpRobotParam> robotParam = new AtomicReference<>();

        private RecordingFactory(boolean failFirstClient) {
            this.failFirstClient = failFirstClient;
        }

        @Override
        public MemoryExtractor.MemoryExtractionClient create(
                String workspacePath, String groupId, int timeoutSeconds,
                AcpRobotParam robotParam, MemoryConfig memoryConfig) {
            this.workspacePath.set(workspacePath);
            this.robotParam.set(robotParam);
            int index = created.getAndIncrement();
            RecordingClient client = new RecordingClient(failFirstClient && index == 0);
            if (blockFirst && index == 0) {
                client.promptEntered = promptEntered;
                client.releasePrompt = releasePrompt;
            }
            clients.add(client);
            return client;
        }
    }

    private static final class RecordingClient
            implements MemoryExtractor.MemoryExtractionClient {
        private final boolean fail;
        private boolean failRestoreNext;
        private final List<String> prompts = new ArrayList<>();
        private final AtomicInteger closed = new AtomicInteger();
        private final AtomicInteger sleeps = new AtomicInteger();
        private final CountDownLatch slept = new CountDownLatch(1);
        private CountDownLatch promptEntered;
        private CountDownLatch releasePrompt;

        private RecordingClient(boolean fail) {
            this.fail = fail;
        }

        @Override
        public String sendPromptSync(String promptText) throws IOException {
            prompts.add(promptText);
            if (failRestoreNext) {
                failRestoreNext = false;
                throw new MemoryAcpClient.SessionRestoreException(new IOException("planned load failure"));
            }
            if (promptEntered != null) {
                promptEntered.countDown();
                try {
                    if (!releasePrompt.await(5, TimeUnit.SECONDS)) throw new IOException("blocked prompt timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            if (fail) throw new IOException("simulated memory client failure");
            return "[]";
        }

        @Override
        public void sleep() {
            sleeps.incrementAndGet();
            slept.countDown();
        }

        @Override
        public void close() {
            closed.incrementAndGet();
        }
    }
}
