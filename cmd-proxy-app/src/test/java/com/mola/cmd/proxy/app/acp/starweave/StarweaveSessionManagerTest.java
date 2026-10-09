package com.mola.cmd.proxy.app.acp.starweave;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry;
import com.mola.cmd.proxy.app.acp.acpclient.MainSessionApplicationService;
import com.mola.cmd.proxy.app.acp.acpclient.PromptOptions;
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToRequest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class StarweaveSessionManagerTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void manualNewSessionReplacesExpiredSleepingClientWithoutWaking() throws Exception {
        FakeFactory factory = new FakeFactory();
        AcpClientRegistry registry = registry(factory);
        try {
            StarweaveSessionManager manager = manager(registry);
            JSONObject opened = manager.open("Robot");
            String groupId = opened.getString("groupId");
            FakeClient old = (FakeClient) registry.getClient(groupId);
            old.sleepWithExpiredSession();
            JSONObject replaced = manager.newSession(groupId,
                    opened.getString("sessionId"), opened.getLongValue("generation"));
            assertEquals(0, old.wakeRotations);
            assertTrue(old.closed);
            assertNotEquals(opened.getString("sessionId"), replaced.getString("sessionId"));
            assertEquals(opened.getLongValue("generation") + 1, replaced.getLongValue("generation"));
            JSONObject batch = manager.eventBatch(groupId, replaced.getString("sessionId"), 0L, null);
            int rotations = 0;
            for (Object value : batch.getJSONArray("events")) {
                JSONObject event = (JSONObject) value;
                assertNotEquals("LIFECYCLE_EVENT", event.getString("type"));
                if ("SESSION_REPLACED".equals(event.getString("type"))) {
                    rotations++;
                    assertEquals("MANUAL", event.getJSONObject("payload").getString("reason"));
                }
            }
            assertEquals(1, rotations);
        } finally {
            registry.closeAllForShutdown();
        }
    }

    @Test
    public void lifecyclePreservesGenerationAndDeleteForcesNewOpen() throws Exception {
        FakeFactory factory = new FakeFactory();
        AcpClientRegistry registry = registry(factory);
        StarweaveSessionManager manager = manager(registry);

        JSONObject opened = manager.open("Robot");
        String groupId = opened.getString("groupId");
        String firstSession = opened.getString("sessionId");
        long firstGeneration = opened.getLongValue("generation");
        assertEquals("STARWEAVE",
                opened.getJSONObject("address").getString("surface"));

        JSONObject replaced = manager.newSession(
                groupId, firstSession, firstGeneration);
        assertNotEquals(firstSession, replaced.getString("sessionId"));
        assertEquals(firstGeneration + 1L, replaced.getLongValue("generation"));

        JSONObject restored = manager.restore(groupId, "provider-history-session",
                replaced.getString("sessionId"), replaced.getLongValue("generation"));
        assertEquals("provider-history-session", restored.getString("sessionId"));
        assertEquals(replaced.getLongValue("generation") + 1L,
                restored.getLongValue("generation"));

        JSONObject deleted = manager.delete(groupId, restored.getString("sessionId"),
                restored.getLongValue("generation"));
        assertFalse(deleted.getBooleanValue("active"));
        assertTrue(deleted.getBooleanValue("forceNewOnNextOpen"));
        assertNull(registry.getClient(groupId));
        assertTrue(manager.list().isEmpty());

        JSONObject reopened = manager.open("Robot");
        assertNotEquals("provider-history-session", reopened.getString("sessionId"));
        assertTrue(factory.lastCreated.forceNew);
        assertTrue(reopened.getLongValue("generation") > deleted.getLongValue("generation"));
        assertEquals(1, manager.list().size());
        registry.closeAllForShutdown();
    }

    @Test
    public void robotRefreshReopensPreviouslyActiveStarweaveSession() throws Exception {
        FakeFactory factory = new FakeFactory();
        AcpClientRegistry registry = registry(factory);
        StarweaveSessionManager manager = manager(registry);
        JSONObject opened = manager.open("Robot");
        String groupId = opened.getString("groupId");

        registry.closeByGroupId(groupId);
        assertNull(registry.getClient(groupId));

        JSONObject reopened = manager.reopenAfterRobotRefresh("Robot", "Robot");

        assertNotNull(reopened);
        assertTrue(reopened.getBooleanValue("active"));
        assertNotNull(registry.getClient(groupId));
        assertTrue(reopened.getLongValue("generation")
                > opened.getLongValue("generation"));
        registry.closeAllForShutdown();
    }

    @Test
    public void talkToAutoOpensMissingTargetAndMakesItVisibleInSessionList()
            throws Exception {
        AcpRobotParam robotA = robot("A");
        AcpRobotParam robotB = robot("B");
        Map<String, AcpRobotParam> robots = new LinkedHashMap<>();
        robots.put("A", robotA);
        robots.put("B", robotB);
        Map<String, String> routes = new java.util.concurrent.ConcurrentHashMap<>();
        FakeFactory factory = new FakeFactory();
        AcpClientRegistry registry = registry(factory);
        TalkToDispatcher dispatcher = new TalkToDispatcher(robots, registry, routes);
        StarweaveSessionManager manager = new StarweaveSessionManager(
                "instance-test", registry, robots::get,
                (groupId, client, robot) -> routes.put(
                        "STARWEAVE:starweave-instance-test:" + robot.getName(), groupId),
                new StarweaveSessionIndex(temporary.newFolder("talkto-index")
                        .toPath().resolve("sessions.json")),
                new StarweaveSessionEventStore(64));
        dispatcher.setStarweaveSessionStarter(manager::open);
        JSONObject openedA = manager.open("A");

        String result = dispatcher.deliver(new TalkToRequest("B", "请检查", 0),
                "A", "starweave-instance-test", openedA.getString("groupId"),
                robotA.getContacts());

        assertTrue(result, result.contains("已成功将消息发送给 B"));
        String groupB = StarweaveIdentity.identity(
                "instance-test", "B").getLogicalId();
        FakeClient clientB = (FakeClient) registry.getClient(groupB);
        assertNotNull(clientB);
        assertTrue(clientB.sentMessage.contains("请检查"));
        assertEquals(2, manager.list().size());
        assertTrue(manager.status(groupB).getBooleanValue("active"));
        registry.closeAllForShutdown();
    }

    @Test
    public void talkToReportsFailureWhenAutomaticOpenFailsAndCanRetry() throws Exception {
        AcpRobotParam robotA = robot("A");
        AcpRobotParam robotB = robot("B");
        Map<String, AcpRobotParam> robots = new LinkedHashMap<>();
        robots.put("A", robotA);
        robots.put("B", robotB);
        Map<String, String> routes = new java.util.concurrent.ConcurrentHashMap<>();
        FakeFactory factory = new FakeFactory();
        AcpClientRegistry registry = registry(factory);
        TalkToDispatcher dispatcher = new TalkToDispatcher(robots, registry, routes);
        StarweaveSessionManager manager = new StarweaveSessionManager(
                "instance-test", registry, robots::get,
                (groupId, client, robot) -> routes.put(
                        "STARWEAVE:starweave-instance-test:" + robot.getName(), groupId),
                new StarweaveSessionIndex(temporary.newFolder("talkto-failure-index")
                        .toPath().resolve("sessions.json")),
                new StarweaveSessionEventStore(64));
        dispatcher.setStarweaveSessionStarter(manager::open);
        JSONObject openedA = manager.open("A");
        factory.failNextStart = true;

        String failed = dispatcher.deliver(new TalkToRequest("B", "请检查", 0),
                "A", "starweave-instance-test", openedA.getString("groupId"),
                robotA.getContacts());

        assertTrue(failed, failed.contains("发送失败"));
        assertEquals(1, manager.list().size());

        String retried = dispatcher.deliver(new TalkToRequest("B", "请检查", 0),
                "A", "starweave-instance-test", openedA.getString("groupId"),
                robotA.getContacts());
        assertTrue(retried, retried.contains("已成功将消息发送给 B"));
        assertEquals(2, manager.list().size());
        registry.closeAllForShutdown();
    }

    @Test
    public void concurrentOpenCreatesOnlyOneVisibleSession() throws Exception {
        FakeFactory factory = new FakeFactory();
        factory.startDelayMillis = 50L;
        AcpClientRegistry registry = registry(factory);
        StarweaveSessionManager manager = manager(registry);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<JSONObject> open = () -> {
                ready.countDown();
                start.await();
                return manager.open("Robot");
            };
            Future<JSONObject> first = executor.submit(open);
            Future<JSONObject> second = executor.submit(open);
            ready.await();
            start.countDown();

            assertEquals(first.get().getString("sessionId"),
                    second.get().getString("sessionId"));
            assertEquals(1, factory.sequence.get());
            assertEquals(1, manager.list().size());
        } finally {
            executor.shutdownNow();
            registry.closeAllForShutdown();
        }
    }

    @Test
    public void failedNewSessionLeavesManagerAndRegistryOnPreviousGeneration()
            throws Exception {
        FakeFactory factory = new FakeFactory();
        AcpClientRegistry registry = registry(factory);
        StarweaveSessionManager manager = manager(registry);
        JSONObject opened = manager.open("Robot");
        AcpClient original = registry.getClient(opened.getString("groupId"));
        factory.failNextStart = true;

        try {
            manager.newSession(opened.getString("groupId"),
                    opened.getString("sessionId"), opened.getLongValue("generation"));
            fail("new session should fail");
        } catch (IOException expected) {
            assertEquals("planned start failure", expected.getMessage());
        }

        JSONObject current = manager.status(opened.getString("groupId"));
        assertSame(original, registry.getClient(opened.getString("groupId")));
        assertEquals(opened.getString("sessionId"), current.getString("sessionId"));
        assertEquals(opened.getLongValue("generation"), current.getLongValue("generation"));
        assertFalse(((FakeClient) original).closed);
        registry.closeAllForShutdown();
    }

    @Test
    public void sharedLifecycleReplacementReinstallsProjectionAndPublishesLocally()
            throws Exception {
        FakeFactory factory = new FakeFactory();
        AcpClientRegistry registry = registry(factory);
        StarweaveSessionManager manager = manager(registry);
        JSONObject opened = manager.open("Robot");
        String groupId = opened.getString("groupId");
        AcpClient original = registry.getClient(groupId);
        MainSessionApplicationService service =
                new MainSessionApplicationService(registry);

        AcpClient replacement = service.replaceIfCurrent(groupId, original, null,
                client -> manager.initializeReplacement(
                        groupId, client, client.getRobotParam()));
        manager.onSessionReplaced(groupId, original.getSessionId(),
                replacement.getSessionId(), "SCHEDULE");

        JSONObject current = manager.status(groupId);
        assertEquals(replacement.getSessionId(), current.getString("sessionId"));
        assertEquals(opened.getLongValue("generation") + 1L,
                current.getLongValue("generation"));
        JSONObject batch = manager.eventBatch(groupId, replacement.getSessionId(),
                0L, current.getLong("generation"));
        assertEquals("SESSION_REPLACED", batch.getJSONArray("events")
                .getJSONObject(0).getString("type"));
        assertEquals("SCHEDULE", batch.getJSONArray("events")
                .getJSONObject(0).getJSONObject("payload").getString("reason"));
        registry.closeAllForShutdown();
    }

    @Test
    public void sleepingClientWakeRotationSyncsIndexAndFilesUserMessageOnNewSession()
            throws Exception {
        FakeFactory factory = new FakeFactory();
        AcpClientRegistry registry = registry(factory);
        StarweaveSessionManager manager = manager(registry);
        JSONObject opened = manager.open("Robot");
        String groupId = opened.getString("groupId");
        String oldSession = opened.getString("sessionId");
        long oldGeneration = opened.getLongValue("generation");
        FakeClient client = (FakeClient) registry.getClient(groupId);
        client.sleepWithExpiredSession();

        JSONObject sent = manager.send(groupId, "after wake", oldSession,
                oldGeneration, "REJECT");

        assertTrue(sent.getBooleanValue("accepted"));
        JSONObject status = manager.status(groupId);
        String newSession = status.getString("sessionId");
        assertNotEquals(oldSession, newSession);
        assertEquals(oldGeneration + 1L, status.getLongValue("generation"));

        JSONObject batch = manager.eventBatch(groupId, newSession, 0L, null);
        boolean replaced = false;
        boolean userOnNewSession = false;
        for (int i = 0; i < batch.getJSONArray("events").size(); i++) {
            JSONObject event = batch.getJSONArray("events").getJSONObject(i);
            if ("SESSION_REPLACED".equals(event.getString("type"))) {
                replaced = true;
                assertEquals("AUTO_IDLE",
                        event.getJSONObject("payload").getString("reason"));
                assertEquals(oldSession,
                        event.getJSONObject("payload").getString("oldSessionId"));
            }
            if ("USER_MESSAGE_ACCEPTED".equals(event.getString("type"))) {
                userOnNewSession = true;
                assertEquals(oldGeneration + 1L, event.getLongValue("generation"));
            }
        }
        assertTrue("SESSION_REPLACED must be published on the new session", replaced);
        assertTrue("user message must be filed on the new session", userOnNewSession);
        registry.closeAllForShutdown();
    }

    @Test
    public void wakeFallbackWithAutoRotationDisabledSyncsIndexAndAcceptsNextMessage()
            throws Exception {
        AcpClientRegistry registry = registry(new FakeFactory());
        try {
            StarweaveSessionManager manager = manager(registry);
            JSONObject opened = manager.open("Robot");
            String groupId = opened.getString("groupId");
            String oldSession = opened.getString("sessionId");
            long oldGeneration = opened.getLongValue("generation");
            FakeClient client = (FakeClient) registry.getClient(groupId);
            com.mola.cmd.proxy.app.acp.AutoNewSessionConfig config =
                    new com.mola.cmd.proxy.app.acp.AutoNewSessionConfig();
            config.setEnabled(false);
            client.getRobotParam().setAutoNewSession(config);
            assertTrue(client.restoreSleepAfterRestart());

            assertTrue(manager.send(groupId, "wake fallback", oldSession,
                    oldGeneration, "REJECT").getBooleanValue("accepted"));
            JSONObject current = manager.status(groupId);
            String newSession = current.getString("sessionId");
            assertNotEquals(oldSession, newSession);
            assertEquals(oldGeneration + 1L, current.getLongValue("generation"));

            JSONArray events = manager.eventBatch(groupId, newSession, 0L, null)
                    .getJSONArray("events");
            boolean replaced = false;
            boolean messageOnNewSession = false;
            for (int i = 0; i < events.size(); i++) {
                JSONObject event = events.getJSONObject(i);
                assertEquals(newSession, event.getString("sessionId"));
                assertEquals(oldGeneration + 1L, event.getLongValue("generation"));
                if ("SESSION_REPLACED".equals(event.getString("type"))) {
                    replaced = true;
                    assertEquals(oldSession,
                            event.getJSONObject("payload").getString("oldSessionId"));
                }
                if ("USER_MESSAGE_ACCEPTED".equals(event.getString("type"))) {
                    messageOnNewSession = true;
                }
            }
            assertTrue(replaced);
            assertTrue(messageOnNewSession);
            client.completePrompt();
            assertTrue(manager.send(groupId, "next message", newSession,
                    current.getLongValue("generation"), "REJECT")
                    .getBooleanValue("accepted"));
        } finally {
            registry.closeAllForShutdown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void agentToolRotationUpdatesIndexAndStartsPromptOnNewSession() throws Exception {
        AcpClientRegistry registry = registry(new FakeFactory());
        StarweaveSessionManager manager = manager(registry);
        JSONObject opened = manager.open("Robot");
        String groupId = opened.getString("groupId");
        FakeClient client = (FakeClient) registry.getClient(groupId);
        manager.send(groupId, "initial", opened.getString("sessionId"),
                opened.getLongValue("generation"), "REJECT");
        for (String fieldName : java.util.Arrays.asList("activeMcpOptions", "activeMcpListener")) {
            java.lang.reflect.Field field = AcpClient.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicReference<Object>) field.get(client))
                    .set("activeMcpOptions".equals(fieldName)
                            ? PromptOptions.defaults() : client.getLiveOutputListener());
        }
        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("prompt", "continue on new session");
        com.mola.cmd.proxy.app.acp.action.ActionRuntimeRegistry.getInstance()
                .execute(client.getAuthSessionId(), "new_session", args);
        java.lang.reflect.Method finish = AcpClient.class.getDeclaredMethod(
                "finishSuccessfulTurn", long.class, PromptOptions.class);
        finish.setAccessible(true);
        finish.invoke(client, client.getLifecycleGeneration(), PromptOptions.defaults());
        JSONObject status = manager.status(groupId);
        assertNotEquals(opened.getString("sessionId"), status.getString("sessionId"));
        assertEquals(opened.getLongValue("generation") + 1, status.getLongValue("generation"));
        assertEquals("continue on new session", client.sentMessage);
        com.alibaba.fastjson.JSONArray events = manager.eventBatch(groupId,
                status.getString("sessionId"), 0L, null).getJSONArray("events");
        assertEquals("SESSION_REPLACED", events.getJSONObject(0).getString("type"));
        assertEquals("AGENT_TOOL", events.getJSONObject(0).getJSONObject("payload").getString("reason"));
        assertEquals("USER_MESSAGE_ACCEPTED", events.getJSONObject(1).getString("type"));
        assertEquals("continue on new session",
                events.getJSONObject(1).getJSONObject("payload").getString("content"));
        assertNotNull(events.getJSONObject(1).getJSONObject("payload").getString("messageId"));
        registry.closeAllForShutdown();
    }

    @Test
    public void externalChannelInboundUsesDedicatedCardEventInsteadOfUserMessage()
            throws Exception {
        FakeFactory factory = new FakeFactory();
        AcpClientRegistry registry = registry(factory);
        StarweaveSessionManager manager = manager(registry);
        JSONObject opened = manager.open("Robot");
        JSONObject metadata = new JSONObject(true);
        metadata.put("senderDisplayName", "小王");

        manager.publishInbound(opened.getString("groupId"),
                opened.getString("sessionId"), "请确认报价", "CHANNEL", metadata);

        JSONObject batch = manager.eventBatch(opened.getString("groupId"),
                opened.getString("sessionId"), 0L, opened.getLong("generation"));
        JSONObject event = null;
        for (int i = 0; i < batch.getJSONArray("events").size(); i++) {
            JSONObject candidate = batch.getJSONArray("events").getJSONObject(i);
            if ("CHANNEL_MESSAGE_RECEIVED".equals(candidate.getString("type"))) {
                event = candidate;
                break;
            }
        }
        assertNotNull(event);
        assertEquals("CHANNEL_MESSAGE_RECEIVED", event.getString("type"));
        assertEquals("CHANNEL", event.getJSONObject("payload").getString("source"));
        assertEquals("请确认报价", event.getJSONObject("payload").getString("content"));
        registry.closeAllForShutdown();
    }

    @Test
    public void sameNamedStagedUploadsAreBothDeliveredToAcpSend()
            throws Exception {
        FakeFactory factory = new FakeFactory();
        AcpClientRegistry registry = registry(factory);
        StarweaveSessionIndex index = new StarweaveSessionIndex(
                temporary.newFolder("upload-index").toPath().resolve("sessions.json"));
        StarweaveUploadStore uploadStore = new StarweaveUploadStore(
                temporary.newFolder("uploads").toPath());
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName("Robot");
        robot.setWorkDir(temporary.getRoot().getAbsolutePath());
        StarweaveSessionManager manager = new StarweaveSessionManager(
                "instance-test", registry, name -> robot,
                (groupId, client, configuredRobot) -> { }, index,
                new StarweaveSessionEventStore(64), uploadStore);

        JSONObject opened = manager.open("Robot");
        byte[] firstContent = "first attachment reaches ACP".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "second attachment reaches ACP".getBytes(StandardCharsets.UTF_8);
        JSONObject firstUpload = manager.upload(opened.getString("groupId"),
                opened.getString("sessionId"), opened.getLongValue("generation"),
                "proof.txt", Base64.getEncoder().encodeToString(firstContent));
        JSONObject secondUpload = manager.upload(opened.getString("groupId"),
                opened.getString("sessionId"), opened.getLongValue("generation"),
                "proof.txt", Base64.getEncoder().encodeToString(secondContent));

        JSONObject sent = manager.send(opened.getString("groupId"), "inspect attachment",
                opened.getString("sessionId"), opened.getLongValue("generation"),
                "REJECT", java.util.Arrays.asList(
                        firstUpload.getString("uploadId"), secondUpload.getString("uploadId")));

        assertTrue(sent.getBooleanValue("accepted"));
        assertEquals("inspect attachment", factory.lastCreated.sentMessage);
        assertNotNull(factory.lastCreated.sentFiles);
        assertEquals(2, factory.lastCreated.sentFiles.size());
        assertArrayEquals(firstContent, Base64.getDecoder().decode(
                factory.lastCreated.sentFiles.get(0).get("proof.txt")));
        assertArrayEquals(secondContent, Base64.getDecoder().decode(
                factory.lastCreated.sentFiles.get(1).get("proof.txt")));
        registry.closeAllForShutdown();
    }

    @Test
    public void fileLinkPreviewUsesCurrentSessionWorkspaceAndRejectsStaleGeneration()
            throws Exception {
        Path workspace = temporary.newFolder("preview-workspace").toPath();
        Files.write(workspace.resolve("README.md"),
                "# Starweave preview\n".getBytes(StandardCharsets.UTF_8));
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName("Robot");
        robot.setWorkDir(workspace.toString());
        FakeFactory factory = new FakeFactory();
        AcpClientRegistry registry = registry(factory);
        StarweaveSessionManager manager = new StarweaveSessionManager(
                "instance-test", registry, name -> robot,
                (groupId, client, configuredRobot) -> { },
                new StarweaveSessionIndex(temporary.newFolder("preview-index")
                        .toPath().resolve("sessions.json")),
                new StarweaveSessionEventStore(64));
        JSONObject opened = manager.open("Robot");

        com.mola.cmd.proxy.app.acp.filepreview.TextFilePreviewResult preview =
                manager.previewTextFile("preview-request", opened.getString("groupId"),
                        opened.getString("sessionId"), opened.getLongValue("generation"),
                        "README.md", 1024, null);

        assertTrue(preview.isAccepted());
        assertEquals("# Starweave preview\n", preview.getData().get("content"));
        try {
            manager.previewTextFile("stale-request", opened.getString("groupId"),
                    opened.getString("sessionId"), opened.getLongValue("generation") + 1L,
                    "README.md", 1024, null);
            fail("stale generation must not read a file");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().startsWith("SESSION_STALE"));
        }
        registry.closeAllForShutdown();
    }

    @Test
    public void restartRecoversEveryActiveClosedSessionAndSkipsDeletedSlots()
            throws Exception {
        StarweaveSessionIndex index = new StarweaveSessionIndex(
                temporary.newFolder("recovery-index").toPath().resolve("sessions.json"));
        String robotGroup = StarweaveIdentity.identity(
                "instance-test", "Robot").getLogicalId();
        String secondGroup = StarweaveIdentity.identity(
                "instance-test", "Second").getLogicalId();
        String deletedGroup = StarweaveIdentity.identity(
                "instance-test", "Deleted").getLogicalId();
        index.activate("Robot", robotGroup, "persisted-robot-session");
        index.activate("Second", secondGroup, "persisted-second-session");
        index.activate("Deleted", deletedGroup, "deleted-session");
        index.markDeleted(deletedGroup);

        FakeFactory factory = new FakeFactory();
        AcpClientRegistry registry = registry(factory);
        StarweaveSessionManager manager = new StarweaveSessionManager(
                "instance-test", registry, name -> {
                    AcpRobotParam robot = new AcpRobotParam();
                    robot.setName(name);
                    robot.setWorkDir(temporary.getRoot().getAbsolutePath());
                    return robot;
                }, (groupId, client, robot) -> { }, index,
                new StarweaveSessionEventStore(64));

        JSONObject recovery = manager.recoverActiveSessions(robotGroup::equals);

        assertEquals(2, recovery.getIntValue("attempted"));
        assertEquals(2, recovery.getIntValue("recoveredCount"));
        assertEquals(0, recovery.getIntValue("failedCount"));
        assertNotNull(registry.getClient(robotGroup));
        assertNotNull(registry.getClient(secondGroup));
        assertNull(registry.getClient(deletedGroup));
        assertEquals(AcpClient.State.SLEEP, registry.getClient(robotGroup).getState());
        assertEquals(AcpClient.State.READY, registry.getClient(secondGroup).getState());
        assertEquals(2, factory.sequence.get());
        registry.closeAllForShutdown();
    }

    private StarweaveSessionManager manager(AcpClientRegistry registry) throws Exception {
        AcpRobotParam robot = robot("Robot");
        return new StarweaveSessionManager("instance-test", registry,
                name -> "Robot".equals(name) ? robot : null,
                (groupId, client, configuredRobot) -> { },
                new StarweaveSessionIndex(temporary.newFolder("index")
                        .toPath().resolve("sessions.json")),
                new StarweaveSessionEventStore(64));
    }

    private AcpRobotParam robot(String name) {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName(name);
        robot.setWorkDir(temporary.getRoot().getAbsolutePath());
        return robot;
    }

    private static AcpClientRegistry registry(FakeFactory handler) throws Exception {
        Class<?> factoryType = Class.forName(
                "com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry$ClientFactory");
        Object factory = Proxy.newProxyInstance(factoryType.getClassLoader(),
                new Class<?>[]{factoryType}, handler);
        Constructor<AcpClientRegistry> constructor =
                AcpClientRegistry.class.getDeclaredConstructor(factoryType);
        constructor.setAccessible(true);
        return constructor.newInstance(factory);
    }

    private static final class FakeFactory implements InvocationHandler {
        private final AtomicInteger sequence = new AtomicInteger();
        private volatile boolean failNextStart;
        private volatile long startDelayMillis;
        private volatile FakeClient lastCreated;

        @Override
        public synchronized Object invoke(Object proxy, Method method, Object[] args) {
            if (!"create".equals(method.getName())) {
                if ("toString".equals(method.getName())) return "FakeFactory";
                throw new UnsupportedOperationException(method.getName());
            }
            AcpClientIdentity identity;
            if (args[1] instanceof AcpClientIdentity) {
                identity = (AcpClientIdentity) args[1];
            } else {
                AcpRobotParam robot = (AcpRobotParam) args[2];
                identity = AcpClientIdentity.main(
                        (String) args[1], (String) args[1], robot.getName());
            }
            lastCreated = new FakeClient(identity, (AcpRobotParam) args[2],
                    sequence.incrementAndGet(), failNextStart, startDelayMillis);
            failNextStart = false;
            return lastCreated;
        }
    }

    private static final class FakeClient extends AcpClient {
        private final int sequence;
        private final boolean failStart;
        private final long startDelayMillis;
        private boolean forceNew;
        private String restoreSessionId;
        private boolean closed;
        private String sentMessage;
        private List<Map<String, String>> sentFiles;
        private int wakeRotations;

        private FakeClient(AcpClientIdentity identity, AcpRobotParam robot,
                           int sequence, boolean failStart, long startDelayMillis) {
            super(robot.getWorkDir(), identity, robot);
            this.sequence = sequence;
            this.failStart = failStart;
            this.startDelayMillis = startDelayMillis;
        }

        @Override
        public void setForceNewSession(boolean forceNewSession) {
            super.setForceNewSession(forceNewSession);
            this.forceNew = forceNewSession;
        }

        @Override
        public void setTargetRestoreSessionId(String targetRestoreSessionId) {
            super.setTargetRestoreSessionId(targetRestoreSessionId);
            this.restoreSessionId = targetRestoreSessionId;
        }

        @Override
        public void start() throws IOException {
            if (failStart) throw new IOException("planned start failure");
            if (startDelayMillis > 0L) {
                try {
                    Thread.sleep(startDelayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("start interrupted", interrupted);
                }
            }
            setSessionId(restoreSessionId == null
                    ? "session-" + sequence : restoreSessionId);
            state.set(State.READY);
        }

        @Override
        protected void wakeRuntimeSession() throws IOException {
            if (!state.compareAndSet(State.SLEEP, State.READY)) {
                throw new IOException("unexpected state");
            }
            setSessionId("session-wake-" + sequence + "-" + (++wakeRotations));
        }

        private void sleepWithExpiredSession() throws Exception {
            com.mola.cmd.proxy.app.acp.AutoNewSessionConfig config =
                    new com.mola.cmd.proxy.app.acp.AutoNewSessionConfig();
            config.setEnabled(true);
            config.setIdleMinutes(1);
            getRobotParam().setAutoNewSession(config);
            java.lang.reflect.Field field = AcpClient.class.getDeclaredField("lastMessageAt");
            field.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicLong) field.get(this))
                    .set(System.currentTimeMillis() - 120_000L);
            state.set(State.SLEEP);
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

        private void completePrompt() {
            state.set(State.READY);
        }

        @Override
        public void send(String userInput, List<Map<String, String>> files) {
            sentMessage = userInput;
            sentFiles = files;
            state.set(State.BUSY);
        }

        @Override
        public void send(String userInput, List<Map<String, String>> files,
                         PromptOptions options) {
            send(userInput, files);
        }
    }
}
