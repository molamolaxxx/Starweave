package com.mola.cmd.proxy.app.acp.acpclient;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class AcpClientRegistryReplacementTest {

    @Test
    public void newSessionReplacesSleepingClientButRejectsBusyClient() throws Exception {
        FakeFactory factory = new FakeFactory();
        AcpClientRegistry registry = new AcpClientRegistry(factory);
        try {
            FakeClient current = (FakeClient) registry.createSession(identity(), ".", robot("Robot"), true, null);
            current.state.set(AbstractAcpClient.State.BUSY);
            assertNull(registry.replaceSessionIfCurrent("group", current, null, null));
            assertFalse(current.closed);
            current.state.set(AbstractAcpClient.State.SLEEP);
            AcpClient replacement = registry.replaceSessionIfCurrent("group", current, null, null);
            assertNotNull(replacement);
            assertTrue(current.closed);
            assertEquals(2, factory.created.size());
        } finally {
            registry.closeAllForShutdown();
        }
    }

    @Test
    public void failedReplacementKeepsCurrentClientPublishedAndOpen() throws Exception {
        FakeFactory factory = new FakeFactory();
        AcpClientRegistry registry = new AcpClientRegistry(factory);
        AcpRobotParam robot = robot("Robot");
        AcpClientIdentity identity = identity();
        AcpClient current = registry.createSession(identity, ".", robot,
                true, null);

        factory.failNextStart = true;
        try {
            registry.replaceSessionIfCurrent("group", current, null, null);
            fail("replacement start should fail");
        } catch (IOException expected) {
            assertEquals("planned start failure", expected.getMessage());
        }

        assertSame(current, registry.getClient("group"));
        assertFalse(((FakeClient) current).closed);
        assertEquals(AbstractAcpClient.State.READY, current.getState());
        registry.closeAllForShutdown();
    }

    @Test
    public void successfulReplacementPreservesIdentityAndClosesOldAfterPublish()
            throws Exception {
        FakeFactory factory = new FakeFactory();
        AcpClientRegistry registry = new AcpClientRegistry(factory);
        AcpRobotParam robot = robot("Robot");
        AcpClientIdentity identity = identity();
        AcpClient current = registry.createSession(identity, ".", robot,
                true, null);

        AcpClient replacement = registry.replaceSessionIfCurrent(
                "group", current, "restored-session", null);

        assertNotNull(replacement);
        assertSame(replacement, registry.getClient("group"));
        assertSame(identity, replacement.getClientIdentity());
        assertEquals("restored-session", replacement.getSessionId());
        assertTrue(((FakeClient) current).closed);
        registry.closeAllForShutdown();
    }

    @Test
    public void concurrentReplacePublishesExactlyOneReplacement() throws Exception {
        FakeFactory factory = new FakeFactory();
        AcpClientRegistry registry = new AcpClientRegistry(factory);
        AcpRobotParam robot = robot("Robot");
        AcpClient current = registry.createSession(identity(),
                ".", robot, true, null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<AcpClient> first = executor.submit(() -> {
            start.await();
            return registry.replaceSessionIfCurrent("group", current, null, null);
        });
        Future<AcpClient> second = executor.submit(() -> {
            start.await();
            return registry.replaceSessionIfCurrent("group", current, null, null);
        });
        start.countDown();

        AcpClient firstResult = first.get(3, TimeUnit.SECONDS);
        AcpClient secondResult = second.get(3, TimeUnit.SECONDS);
        executor.shutdownNow();
        assertTrue((firstResult == null) ^ (secondResult == null));
        assertSame(firstResult == null ? secondResult : firstResult,
                registry.getClient("group"));
        assertEquals(2, factory.created.size());
        registry.closeAllForShutdown();
    }

    private static AcpRobotParam robot(String name) {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName(name);
        return robot;
    }

    private static AcpClientIdentity identity() {
        return AcpClientIdentity.starweave(
                "group", "transport", "starweave/robot",
                "starweave-instance", "Robot");
    }

    private static final class FakeFactory implements AcpClientRegistry.ClientFactory {
        private final List<FakeClient> created = new ArrayList<>();
        private volatile boolean failNextStart;

        @Override
        public AcpClient create(String workspacePath, String groupId,
                                AcpRobotParam robotParam) {
            return create(workspacePath, AcpClientIdentity.main(
                    groupId, groupId, robotParam.getName()), robotParam);
        }

        @Override
        public synchronized AcpClient create(String workspacePath,
                                             AcpClientIdentity identity,
                                             AcpRobotParam robotParam) {
            FakeClient client = new FakeClient(identity, robotParam, failNextStart);
            failNextStart = false;
            created.add(client);
            return client;
        }
    }

    private static final class FakeClient extends AcpClient {
        private final boolean failStart;
        private boolean closed;
        private String restoreSessionId;

        private FakeClient(AcpClientIdentity identity, AcpRobotParam robot,
                           boolean failStart) {
            super(".", identity, robot);
            this.failStart = failStart;
        }

        @Override
        public void start() throws IOException {
            if (failStart) throw new IOException("planned start failure");
            setSessionId(restoreSessionId == null
                    ? "session-" + System.nanoTime() : restoreSessionId);
            state.set(State.READY);
        }

        @Override
        public void setTargetRestoreSessionId(String targetRestoreSessionId) {
            super.setTargetRestoreSessionId(targetRestoreSessionId);
            this.restoreSessionId = targetRestoreSessionId;
        }

        @Override
        public void close() {
            closed = true;
            state.set(State.CLOSED);
        }

        @Override
        public void closeForShutdown() {
            close();
        }
    }
}
