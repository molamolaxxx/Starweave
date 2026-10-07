package com.mola.cmd.proxy.app.acp.schedule;

import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleConfig;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleOwnerKey;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduledTask;
import com.mola.cmd.proxy.app.acp.mcpauth.AuthPrincipalContext;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelDeliveryContext;
import com.mola.cmd.proxy.app.acp.acpclient.ClientSurface;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.starweave.StarweaveIdentity;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ScheduleOwnerIsolationTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void molaChatUnicodeIdsRetainRoutingIdentityAcrossPersistenceRecovery()
            throws Exception {
        Path root = temporaryFolder.newFolder("unicode-schedules").toPath();
        ScheduleTaskManager manager = new ScheduleTaskManager(root);
        for (String name : new String[]{"定时任务-MM", "定时任务-ML"}) {
            String groupId = "1785566835011cSLOSacp-" + name;
            ScheduleOwnerKey owner = ScheduleOwnerKey.main(
                    groupId, ClientSurface.MOLACHAT, groupId, name);
            assertEquals(groupId, owner.getOwnerId());
            assertEquals(groupId, owner.getLogicalId());
            ScheduleOwnerKey recovered = ScheduleOwnerKey.fromPersistencePath(
                    owner.getPersistencePath());
            assertEquals(owner, recovered);
            assertEquals(groupId, recovered.getLogicalId());
            manager.createTask(owner, name, "prompt", new ScheduleConfig("once", "+1h"));
            assertTrue(Files.exists(root.resolve(owner.getPersistencePath()).resolve("tasks.json")));

            // Existing MolaChat schedules keep their original robot-name directory.
            ScheduleOwnerKey legacy = ScheduleOwnerKey.main(name);
            manager.createTask(legacy, "legacy", "prompt", new ScheduleConfig("once", "+1h"));
            assertEquals(name, legacy.getPersistencePath());
            assertTrue(Files.exists(root.resolve(name).resolve("tasks.json")));
        }
        ScheduleTaskManager restored = new ScheduleTaskManager(root);
        restored.start();
        try {
            for (String name : new String[]{"定时任务-MM", "定时任务-ML"}) {
                String groupId = "1785566835011cSLOSacp-" + name;
                ScheduleOwnerKey owner = ScheduleOwnerKey.main(
                        groupId, ClientSurface.MOLACHAT, groupId, name);
                assertEquals(1, restored.listTasks(owner).size());
                assertEquals(groupId, restored.listTasks(owner).get(0).getOwner().getLogicalId());
                assertEquals(1, restored.listTasks(ScheduleOwnerKey.main(name)).size());
            }
        } finally {
            restored.stop();
        }
    }

    @Test
    public void unicodeMolaChatIdsStillRejectUnsafePathSegments() {
        for (String invalid : new String[]{"..", ".", "用户/机器人", "用户\\机器人", "C:机器人"}) {
            for (boolean invalidOwner : new boolean[]{true, false}) {
                try {
                    ScheduleOwnerKey.main(invalidOwner ? invalid : "用户",
                            ClientSurface.MOLACHAT, invalidOwner ? "会话" : invalid, "机器人");
                    fail("Accepted unsafe ID: " + invalid);
                } catch (IllegalArgumentException expected) {
                    assertTrue(expected.getMessage().contains("is not safe"));
                }
            }
        }
        try {
            ScheduleOwnerKey.main("starweave-instance", ClientSurface.STARWEAVE, "中文", "机器人");
            fail("Starweave logical IDs must retain their transport-safe validation");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("logicalId is not safe"));
        }
    }

    @Test
    public void scheduledTaskPersistsChannelNeutralPrincipalForDerivedExecution()
            throws Exception {
        Path root = temporaryFolder.newFolder("principal").toPath();
        ScheduleOwnerKey owner = ScheduleOwnerKey.main("Robot");
        ScheduleTaskManager manager = new ScheduleTaskManager(root);
        AuthPrincipalContext principal = new AuthPrincipalContext(
                "user-a", "Alice", "WECOM", "wecom-main");
        ChannelDeliveryContext delivery = new ChannelDeliveryContext(
                "wecom-main", "single", "user-a", "user-a", "Alice");

        ScheduledTask task = manager.createTask(owner, "run", "prompt",
                new ScheduleConfig("once", "+1h"), null, principal, delivery);

        assertEquals("user-a", task.getAuthPrincipalContext().getPrincipalId());
        String persisted = new String(Files.readAllBytes(
                root.resolve("Robot").resolve("tasks.json")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(persisted.contains("\"principalId\": \"user-a\""));
        assertTrue(persisted.contains("\"sourceType\": \"WECOM\""));
        assertTrue(persisted.contains("\"conversationAddress\": \"user-a\""));

        ScheduleTaskManager restored = new ScheduleTaskManager(root);
        restored.start();
        try {
            ScheduledTask restoredTask = restored.listTasks(owner).get(0);
            assertEquals("wecom-main",
                    restoredTask.getChannelDeliveryContext().getChannelId());
            assertEquals("user-a",
                    restoredTask.getChannelDeliveryContext().getConversationAddress());
        } finally {
            restored.stop();
        }
    }

    @Test
    public void mainAndTeamOwnersUseIndependentBackwardCompatiblePaths()
            throws Exception {
        Path root = temporaryFolder.newFolder("schedules").toPath();
        ScheduleTaskManager manager = new ScheduleTaskManager(root);
        ScheduleOwnerKey main = ScheduleOwnerKey.main("Robot One");
        ScheduleOwnerKey member = ScheduleOwnerKey.team(
                "owner-1", "team-1", "member-1", "Robot One");

        manager.createTask(main, "main", "normal",
                new ScheduleConfig("once", "+1h"));
        manager.createTask(member, "team", "isolated",
                new ScheduleConfig("once", "+1h"));

        assertTrue(Files.exists(root.resolve("Robot One/tasks.json")));
        assertTrue(Files.exists(
                root.resolve("team/team-1/member-1/tasks.json")));
        assertEquals(1, manager.listTasks(main).size());
        assertEquals(1, manager.listTasks(member).size());

        ScheduleTaskManager restored = new ScheduleTaskManager(root);
        restored.start();
        try {
            ScheduledTask restoredTask = restored.listTasks(member).get(0);
            assertEquals("owner-1", restoredTask.getOwner().getOwnerId());
            assertEquals("member-1",
                    restoredTask.getOwner().getTeamMemberId());
        } finally {
            restored.stop();
        }

        manager.cleanupTeam("team-1");
        assertFalse(Files.exists(root.resolve("team/team-1")));
        assertTrue(Files.exists(root.resolve("Robot One/tasks.json")));
    }

    @Test
    public void restoredGroupCannotOverwriteLiveTeamScheduleOwner()
            throws Exception {
        Path root = temporaryFolder.newFolder("team-group-owner-recovery").toPath();
        ScheduleOwnerKey liveOwner = ScheduleOwnerKey.team(
                "owner-1", "team-1", "member-1", "Robot One");
        ScheduleTaskManager writer = new ScheduleTaskManager(root);
        writer.createTask(liveOwner, "grouped", "prompt",
                new ScheduleConfig("cron", "* * * * *"), "daily");
        writer.bindGroupSession(liveOwner, "daily", "session-1");

        // Team client feature initialization can finish before scheduler persistence load.
        ScheduleTaskManager restored = new ScheduleTaskManager(root);
        restored.register(liveOwner);
        restored.start();
        try {
            Method ownerFor = ScheduleTaskManager.class.getDeclaredMethod(
                    "ownerFor", String.class);
            ownerFor.setAccessible(true);
            ScheduleOwnerKey recoveredOwner = (ScheduleOwnerKey) ownerFor.invoke(
                    restored, liveOwner.getPersistencePath());
            assertEquals("owner-1", recoveredOwner.getOwnerId());

            ScheduledTask task = restored.listTasks(liveOwner).get(0);
            task.setNextRunAt(0L);
            CountDownLatch invoked = new CountDownLatch(1);
            AtomicReference<ScheduleOwnerKey> callbackOwner = new AtomicReference<>();
            restored.setScopedExecutionCallback((owner, taskId, groupName, prompt,
                                                  principal, delivery) -> {
                callbackOwner.set(owner);
                invoked.countDown();
                return false;
            });

            Method scan = ScheduleTaskManager.class.getDeclaredMethod("scan");
            scan.setAccessible(true);
            scan.invoke(restored);

            assertTrue(invoked.await(2, TimeUnit.SECONDS));
            awaitExecutionCount(root, "DEFERRED", 1);
            assertEquals("owner-1", callbackOwner.get().getOwnerId());
            assertEquals(liveOwner, callbackOwner.get());
        } finally {
            restored.stop();
        }
    }

    @Test
    public void starweaveMainOwnerPersistsExactSurfaceAndLogicalSession()
            throws Exception {
        Path root = temporaryFolder.newFolder("starweave-schedule").toPath();
        ScheduleOwnerKey owner = ScheduleOwnerKey.main(
                "starweave-instance-a", ClientSurface.STARWEAVE,
                "acp-Robotstarweave-instance-a", "Robot One");

        assertEquals(ClientSurface.STARWEAVE, owner.getSurface());
        assertEquals("acp-Robotstarweave-instance-a", owner.getLogicalId());
        assertEquals("main/starweave/starweave-instance-a/"
                + "acp-Robotstarweave-instance-a/Robot One",
                owner.getPersistencePath());
        assertEquals(owner, ScheduleOwnerKey.fromPersistencePath(
                owner.getPersistencePath()));

        ScheduleTaskManager manager = new ScheduleTaskManager(root);
        manager.createTask(owner, "local", "prompt",
                new ScheduleConfig("once", "+1h"));
        assertTrue(Files.exists(root.resolve(owner.getPersistencePath())
                .resolve("tasks.json")));

        ScheduleTaskManager restored = new ScheduleTaskManager(root);
        restored.start();
        try {
            assertEquals(1, restored.listTasks(owner).size());
            ScheduleOwnerKey restoredOwner = restored.listTasks(owner).get(0).getOwner();
            assertEquals(ClientSurface.STARWEAVE, restoredOwner.getSurface());
            assertEquals(owner.getLogicalId(), restoredOwner.getLogicalId());
        } finally {
            restored.stop();
        }
    }

    @Test
    public void unicodeStarweaveDisplayNameProducesSafeScheduleOwner() {
        AcpClientIdentity identity = StarweaveIdentity.identity(
                "env-a", "Sales专家");

        ScheduleOwnerKey owner = ScheduleOwnerKey.main(
                identity.getOwnerId(), identity.getSurface(),
                identity.getLogicalId(), identity.getSourceRobotName());

        assertEquals("Sales专家", owner.getRobotName());
        assertTrue(owner.getLogicalId().matches("[a-zA-Z0-9._-]+"));
        assertTrue(owner.getPersistencePath().contains("/Sales专家"));
        assertEquals(owner, ScheduleOwnerKey.fromPersistencePath(
                owner.getPersistencePath()));
    }

    @Test
    public void scopedCallbackReceivesTeamIdentity() throws Exception {
        Path root = temporaryFolder.newFolder("callback").toPath();
        ScheduleTaskManager manager = new ScheduleTaskManager(root);
        ScheduleOwnerKey member = ScheduleOwnerKey.team(
                "owner-1", "team-1", "member-1", "Robot");
        ScheduledTask task = manager.createTask(member, "run", "prompt",
                new ScheduleConfig("once", "+1h"));
        task.setNextRunAt(0L);
        CountDownLatch invoked = new CountDownLatch(1);
        AtomicReference<ScheduleOwnerKey> actual = new AtomicReference<>();
        manager.setScopedExecutionCallback((owner, taskId, groupName, prompt, principal, delivery) -> {
            actual.set(owner);
            invoked.countDown();
            return false;
        });

        Method scan = ScheduleTaskManager.class.getDeclaredMethod("scan");
        scan.setAccessible(true);
        scan.invoke(manager);

        assertTrue(invoked.await(2, TimeUnit.SECONDS));
        awaitExecutionCount(root, "DEFERRED", 1);
        assertEquals(member, actual.get());
        assertEquals("owner-1", actual.get().getOwnerId());
    }

    @Test
    public void scheduledChannelExecutionCarriesDeliveryContextAndAgentHint()
            throws Exception {
        Path root = temporaryFolder.newFolder("delivery-callback").toPath();
        ScheduleTaskManager manager = new ScheduleTaskManager(root);
        ScheduleOwnerKey owner = ScheduleOwnerKey.main("Robot");
        ChannelDeliveryContext delivery = new ChannelDeliveryContext(
                "wecom-main", "single", "user-a", "user-a", "Alice");
        ScheduledTask task = manager.createTask(owner, "drink", "提醒喝水",
                new ScheduleConfig("once", "+1h"), null, null, delivery);
        task.setNextRunAt(0L);
        CountDownLatch invoked = new CountDownLatch(1);
        AtomicReference<String> actualPrompt = new AtomicReference<>();
        AtomicReference<ChannelDeliveryContext> actualDelivery = new AtomicReference<>();
        manager.setScopedExecutionCallback((callbackOwner, taskId, groupName, prompt,
                                             principal, channelDelivery) -> {
            actualPrompt.set(prompt);
            actualDelivery.set(channelDelivery);
            invoked.countDown();
            return false;
        });

        Method scan = ScheduleTaskManager.class.getDeclaredMethod("scan");
        scan.setAccessible(true);
        scan.invoke(manager);

        assertTrue(invoked.await(2, TimeUnit.SECONDS));
        awaitExecutionCount(root, "DEFERRED", 1);
        assertSame(delivery, actualDelivery.get());
        assertTrue(actualPrompt.get().contains("已绑定创建时的原始外部信道会话"));
        assertTrue(actualPrompt.get().contains("target 必须精确设置为“回复”"));
        assertFalse(actualPrompt.get().contains("user-a"));
    }

    @Test
    public void executionJournalPersistsDeferredSuccessAndFailureAttempts()
            throws Exception {
        Path root = temporaryFolder.newFolder("execution-journal").toPath();
        ScheduleTaskManager manager = new ScheduleTaskManager(root);
        ScheduleOwnerKey owner = ScheduleOwnerKey.team(
                "owner-1", "team-1", "member-1", "Robot");
        ScheduledTask cron = manager.createTask(owner, "cron run", "prompt",
                new ScheduleConfig("cron", "* * * * *"));
        cron.setNextRunAt(0L);
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch invoked = new CountDownLatch(2);
        manager.setScopedExecutionCallback((callbackOwner, taskId, groupName, prompt,
                                             principal, delivery) -> {
            invoked.countDown();
            return callbacks.incrementAndGet() > 1;
        });

        Method scan = ScheduleTaskManager.class.getDeclaredMethod("scan");
        scan.setAccessible(true);
        scan.invoke(manager);
        awaitExecutionCount(root, "DEFERRED", 1);
        scan.invoke(manager);
        assertTrue(invoked.await(2, TimeUnit.SECONDS));
        awaitExecutionCount(root, "SUCCESS", 1);

        ScheduledTask failed = manager.createTask(owner, "failed run", "prompt",
                new ScheduleConfig("once", "+1h"));
        failed.setNextRunAt(0L);
        manager.setScopedExecutionCallback((callbackOwner, taskId, groupName, prompt,
                                             principal, delivery) -> {
            throw new IllegalStateException("expected callback failure");
        });
        scan.invoke(manager);
        awaitExecutionCount(root, "FAILED", 1);

        String jdbcUrl = "jdbc:sqlite:"
                + root.resolve(ScheduleExecutionJournal.DATABASE_FILE).toAbsolutePath();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT status,result_code,owner_scope,team_id,team_member_id,attempt "
                             + "FROM schedule_execution ORDER BY started_at,id")) {
            assertTrue(rows.next());
            assertEquals("DEFERRED", rows.getString("status"));
            assertEquals("TARGET_NOT_EXECUTABLE", rows.getString("result_code"));
            assertEquals("TEAM", rows.getString("owner_scope"));
            assertEquals("team-1", rows.getString("team_id"));
            assertEquals("member-1", rows.getString("team_member_id"));
            assertEquals(1, rows.getInt("attempt"));
            assertTrue(rows.next());
            assertEquals("SUCCESS", rows.getString("status"));
            assertEquals(2, rows.getInt("attempt"));
            assertTrue(rows.next());
            assertEquals("FAILED", rows.getString("status"));
            assertEquals("EXECUTION_EXCEPTION", rows.getString("result_code"));
            assertFalse(rows.next());
        }
    }

    private static void awaitExecutionCount(Path root, String status, int expected)
            throws Exception {
        String jdbcUrl = "jdbc:sqlite:"
                + root.resolve(ScheduleExecutionJournal.DATABASE_FILE).toAbsolutePath();
        long deadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < deadline) {
            try (Connection connection = DriverManager.getConnection(jdbcUrl);
                 Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT COUNT(*) FROM schedule_execution WHERE status='"
                                 + status + "'")) {
                if (result.next() && result.getInt(1) >= expected) return;
            }
            Thread.sleep(10L);
        }
        fail("Timed out waiting for schedule execution status " + status);
    }

    @Test
    public void groupNameAndSessionBindingPersistAndRemainOwnerScoped()
            throws Exception {
        Path root = temporaryFolder.newFolder("groups").toPath();
        ScheduleOwnerKey main = ScheduleOwnerKey.main("Robot One");
        ScheduleOwnerKey member = ScheduleOwnerKey.team(
                "owner-1", "team-1", "member-1", "Robot One");
        ScheduleTaskManager manager = new ScheduleTaskManager(root);

        ScheduledTask mainTask = manager.createTask(main, "main", "prompt",
                new ScheduleConfig("once", "+1h"), " daily ");
        ScheduledTask teamTask = manager.createTask(member, "team", "prompt",
                new ScheduleConfig("once", "+1h"), "daily");
        manager.bindGroupSession(main, "daily", "session-main");
        manager.bindGroupSession(member, "daily", "session-team");

        assertEquals("daily", mainTask.getGroupName());
        assertEquals("daily", teamTask.getGroupName());
        assertEquals("session-main", manager.findGroupSession(main, "daily"));
        assertEquals("session-team", manager.findGroupSession(member, "daily"));
        assertTrue(Files.exists(root.resolve("Robot One/groups.json")));
        assertTrue(Files.exists(
                root.resolve("team/team-1/member-1/groups.json")));

        ScheduleTaskManager restored = new ScheduleTaskManager(root);
        restored.start();
        try {
            assertEquals("session-main",
                    restored.findGroupSession(main, "daily"));
            assertEquals("session-team",
                    restored.findGroupSession(member, "daily"));
            assertEquals("daily",
                    restored.listTasks(main).get(0).getGroupName());
        } finally {
            restored.stop();
        }
    }

    @Test
    public void scheduleMcpArgumentsApplyOneGroupNameToAllCreatedTasks()
            throws Exception {
        Path root = temporaryFolder.newFolder("json-group").toPath();
        ScheduleTaskManager manager = new ScheduleTaskManager(root);
        ScheduleOwnerKey owner = ScheduleOwnerKey.main("Robot");

        manager.executeTool("schedule_task", JsonParser.parseString("{"
                + "\"groupName\":\"daily\",\"tasks\":["
                + "{\"title\":\"one\",\"prompt\":\"p1\","
                + "\"schedule\":{\"type\":\"once\",\"expr\":\"+1h\"}},"
                + "{\"title\":\"two\",\"prompt\":\"p2\","
                + "\"schedule\":{\"type\":\"once\",\"expr\":\"+2h\"}}]}")
                .getAsJsonObject(), owner, null, null);

        assertEquals(2, manager.listTasks(owner).size());
        assertTrue(manager.listTasks(owner).stream()
                .allMatch(task -> "daily".equals(task.getGroupName())));
    }

    @Test
    public void resolvesGroupDateTemplateFromScheduledOccurrence()
            throws Exception {
        Path root = temporaryFolder.newFolder("group-date-template").toPath();
        ScheduleTaskManager manager = new ScheduleTaskManager(root);
        ScheduleOwnerKey owner = ScheduleOwnerKey.main("Robot");
        ScheduledTask task = manager.createTask(owner, "daily", "prompt",
                new ScheduleConfig("cron", "0 5-18 * * *"),
                "这是一个group{yyyyMMdd}");
        ZoneId zone = ZoneId.systemDefault();
        long scheduledAt = ZonedDateTime.of(
                2026, 9, 16, 18, 0, 0, 0, zone).toInstant().toEpochMilli();

        assertEquals("这是一个group{yyyyMMdd}", task.getGroupName());
        assertEquals("这是一个group20260916",
                ScheduleTaskManager.resolveGroupName(
                        task.getGroupName(), scheduledAt, zone));
        assertEquals("这是一个group20260917",
                ScheduleTaskManager.resolveGroupName(task.getGroupName(),
                        scheduledAt + TimeUnit.DAYS.toMillis(1), zone));
        assertEquals("fixed", ScheduleTaskManager.resolveGroupName(
                "fixed", scheduledAt, zone));

        task.setNextRunAt(scheduledAt);
        CountDownLatch invoked = new CountDownLatch(1);
        AtomicReference<String> effectiveGroup = new AtomicReference<>();
        manager.setScopedExecutionCallback((callbackOwner, taskId, groupName, prompt,
                                             principal, delivery) -> {
            effectiveGroup.set(groupName);
            invoked.countDown();
            return false;
        });
        Method trigger = ScheduleTaskManager.class.getDeclaredMethod(
                "triggerExecution", String.class, ScheduledTask.class);
        trigger.setAccessible(true);
        trigger.invoke(manager, owner.getPersistencePath(), task);

        assertTrue(invoked.await(2, TimeUnit.SECONDS));
        assertEquals("这是一个group20260916", effectiveGroup.get());
        awaitExecutionCount(root, "DEFERRED", 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMalformedGroupDateTemplate() throws Exception {
        ScheduleTaskManager manager = new ScheduleTaskManager(
                temporaryFolder.newFolder("invalid-group-template").toPath());
        manager.createTask(ScheduleOwnerKey.main("Robot"), "daily", "prompt",
                new ScheduleConfig("cron", "0 5-18 * * *"),
                "daily-{yyyyMMdd");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsafeTeamPersistenceIdentity() {
        ScheduleOwnerKey.team("owner", "../team", "member", "Robot");
    }
}
