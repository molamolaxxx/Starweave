package com.mola.cmd.proxy.app.acp.team;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.AutoNewSessionConfig;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.acpclient.PromptOptions;
import com.mola.cmd.proxy.app.acp.schedule.ScheduleTaskManager;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleOwnerKey;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberState;
import com.mola.cmd.proxy.app.acp.team.model.TeamState;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberCreateSpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class TeamMemberCommandTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void sendPreservesMultipleSameNamedAttachmentsForMemberClient()
            throws Exception {
        Fixture fixture = fixture();
        String payload = basePayload()
                .replace("}", ",\"message\":\"hello\",\"files\":["
                        + "{\"a.png\":\"Zmlyc3Q=\"},{\"a.png\":\"c2Vjb25k\"}]}");

        Map<String, String> result =
                fixture.handler.handleSend("rpc-send", new String[]{payload});

        assertEquals("true", result.get("accepted"));
        assertEquals("QUEUED", result.get("code"));
        assertEquals("hello", fixture.current.get().message);
        assertEquals(2, fixture.current.get().files.size());
        assertEquals("Zmlyc3Q=", fixture.current.get().files.get(0).get("a.png"));
        assertEquals("c2Vjb25k", fixture.current.get().files.get(1).get("a.png"));
        assertEquals(TeamMemberState.BUSY,
                fixture.manager.getRuntime("team-1").get().getDefinition()
                        .getMembers().get(0).getState());
        assertTrue(fixture.events.stream().anyMatch(
                event -> event.getType().name().equals("MEMBER_STATE_CHANGED")));
        fixture.manager.close();
    }

    @Test
    public void statusContextCancelAndSessionListUseCanonicalDataEnvelope()
            throws Exception {
        Fixture fixture = fixture();

        Map<String, String> status =
                fixture.handler.handleGetStatus("rpc-status", one(basePayload()));
        Map<String, String> context =
                fixture.handler.handleGetContextUsage("rpc-context", one(basePayload()));
        Map<String, String> cancel =
                fixture.handler.handleCancel("rpc-cancel", one(basePayload()));
        Map<String, String> sessions =
                fixture.handler.handleListSessions("rpc-list",
                        one(basePayload().replace("}", ",\"limit\":7}")));

        JsonObject statusData = json(status);
        assertEquals("READY", statusData.get("teamState").getAsString());
        assertEquals("READY", statusData.get("memberState").getAsString());
        assertEquals(42.5, json(context)
                .get("contextUsagePercentage").getAsDouble(), 0.001);
        assertTrue(fixture.current.get().cancelled);
        assertTrue(json(sessions).getAsJsonArray("sessions").isEmpty());
        assertEquals("true", cancel.get("accepted"));
        fixture.manager.close();
    }

    @Test
    public void historyRestoresBusinessCardsAndSuppressesTheirGenericTools()
            throws Exception {
        Fixture fixture = fixture();
        JsonObject talkTo = new JsonObject();
        talkTo.addProperty("targetTeamMemberId", "member-2");
        talkTo.addProperty("content", "hello");
        talkTo.addProperty("delivery", "DELIVERED");
        fixture.current.get().getHistoryManager().addEventMessage(
                "TALK_TO_SEND", talkTo);
        JsonObject input = new JsonObject();
        input.addProperty("target", "member-2");
        input.addProperty("content", "hello");
        fixture.current.get().getHistoryManager().addToolMessage(
                "tool-talk", "mcp__cmd-proxy-runtime__talk_to",
                "completed", input, new JsonObject());
        JsonObject compaction = new JsonObject();
        compaction.addProperty("provider", "codex");
        fixture.current.get().getHistoryManager().addEventMessage(
                "COMPACTION_EVENT", compaction);

        Map<String, String> result = fixture.handler.handleGetSessionHistory(
                "rpc-history", one(basePayload()));

        JsonArray messages = json(result).getAsJsonArray("messages");
        assertEquals(2, messages.size());
        assertEquals("TALK_TO_SEND", messages.get(0).getAsJsonObject()
                .get("eventType").getAsString());
        assertEquals("COMPACTION_EVENT", messages.get(1).getAsJsonObject()
                .get("eventType").getAsString());
        assertEquals("TEAM_EVENT", messages.get(1).getAsJsonObject()
                .get("kind").getAsString());
        fixture.manager.close();
    }

    @Test
    public void historyRestoresAttachmentNamesOnUserMessages() throws Exception {
        Fixture fixture = fixture();
        fixture.current.get().getHistoryManager().addUserMessage(
                "please inspect", com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage.UserOrigin.USER,
                Arrays.asList("screen.png", "notes.txt"));

        Map<String, String> result = fixture.handler.handleGetSessionHistory(
                "rpc-history-attachments", one(basePayload()));

        JsonObject message = json(result).getAsJsonArray("messages")
                .get(0).getAsJsonObject();
        assertEquals("USER", message.get("role").getAsString());
        assertEquals("please inspect", message.get("content").getAsString());
        assertEquals("screen.png", message.getAsJsonArray("attachments")
                .get(0).getAsJsonObject().get("fileName").getAsString());
        assertEquals("notes.txt", message.getAsJsonArray("attachments")
                .get(1).getAsJsonObject().get("fileName").getAsString());
        fixture.manager.close();
    }

    @Test
    public void busyMemberCancelSendsCurrentSessionCancel() throws Exception {
        Fixture fixture = fixture();
        fixture.current.get().setClientState(AbstractAcpClient.State.BUSY);
        fixture.manager.onMemberState(
                "team-1", "member-1", TeamMemberState.BUSY, null);

        Map<String, String> cancel = fixture.handler.handleCancel(
                "rpc-cancel-busy", one(basePayload()));

        assertEquals("true", cancel.get("accepted"));
        assertEquals("OK", cancel.get("code"));
        assertTrue(fixture.current.get().cancelled);
        assertEquals("session-member-1",
                json(cancel).get("sessionId").getAsString());
        fixture.manager.close();
    }

    @Test
    public void cancelRejectsStartingErrorAndClosedMemberStates() throws Exception {
        for (TeamMemberState state : Arrays.asList(
                TeamMemberState.STARTING, TeamMemberState.ERROR,
                TeamMemberState.CLOSING, TeamMemberState.CLOSED)) {
            Fixture fixture = fixture();
            fixture.current.get().setClientState(clientStateFor(state));
            fixture.manager.onMemberState("team-1", "member-1", state, null);

            Map<String, String> cancel = fixture.handler.handleCancel(
                    "rpc-cancel-" + state.name(), one(basePayload()));

            assertEquals(state.name(), "false", cancel.get("accepted"));
            assertFalse(state.name(), fixture.current.get().cancelled);
            assertEquals(state.name(),
                    state == TeamMemberState.STARTING
                            ? "TEAM_NOT_READY" : "CLIENT_CLOSED",
                    cancel.get("code"));
            fixture.manager.close();
        }
    }

    @Test
    public void memoryDreamUsesSourceRobotMemoryOwner() throws Exception {
        Fixture fixture = fixture();

        Map<String, String> result = fixture.handler.handleMemoryDream(
                "rpc-dream", one(basePayload()));

        assertEquals("true", result.get("accepted"));
        assertEquals("OK", result.get("code"));
        assertEquals("group-1", fixture.dreamRoute.get());
        assertTrue(json(result).get("triggered").getAsBoolean());
        assertEquals("group-1", json(result)
                .get("memoryOwnerSourceGroupId").getAsString());
        fixture.manager.close();
    }

    @Test
    public void newAndRestoreSessionReplaceOnlyTargetMemberAndPublishStates()
            throws Exception {
        Fixture fixture = fixture();

        Map<String, String> created =
                fixture.handler.handleNewSession("rpc-new", one(basePayload()));
        Map<String, String> restored = fixture.handler.handleRestoreSession(
                "rpc-restore", one(basePayload().replace(
                        "}", ",\"sessionId\":\"historic-session\"}")));

        assertEquals("OK", created.get("code"));
        assertEquals("new-session", JsonParser.parseString(created.get("data"))
                .getAsJsonObject().get("sessionId").getAsString());
        assertEquals("OK", restored.get("code"));
        assertEquals("historic-session", fixture.lastOptions.get()
                .getTargetRestoreSessionId());
        assertEquals("historic-session", JsonParser.parseString(restored.get("data"))
                .getAsJsonObject().get("sessionId").getAsString());
        assertEquals(1, fixture.registry.size());
        fixture.manager.close();
    }

    @Test
    public void sourceRobotRefreshRebuildsAndReopensAffectedTeamMember()
            throws Exception {
        Fixture fixture = fixture();
        TestClient previous = fixture.current.get();

        int refreshed = fixture.manager.refreshSourceRobot("acp-Robot_One");

        assertEquals(1, refreshed);
        assertTrue(previous.closed);
        assertNotSame(previous, fixture.current.get());
        assertEquals(AbstractAcpClient.State.READY, fixture.current.get().getState());
        assertEquals("new-session", fixture.current.get().getSessionId());
        assertFalse(fixture.lastOptions.get().isForceNewSession());
        assertNull(fixture.lastOptions.get().getTargetRestoreSessionId());
        assertEquals(TeamMemberState.READY,
                fixture.manager.getRuntime("team-1").get().getDefinition()
                        .getMembers().get(0).getState());
        fixture.manager.close();
    }

    @Test
    public void automaticIdleRotationPublishesMemberSessionChanged() throws Exception {
        Fixture fixture = fixture();
        AutoNewSessionConfig config = new AutoNewSessionConfig();
        config.setEnabled(true);
        config.setCheckIntervalMinutes(1);
        config.setIdleMinutes(1);
        fixture.current.get().getRobotParam().setAutoNewSession(config);
        fixture.current.get().allowAutomaticRotation = true;

        assertEquals(0, fixture.manager.rotateIdleSessions(1L));
        assertEquals(1, fixture.manager.rotateIdleSessions(60_001L));

        TeamEventEnvelope changed = fixture.events.stream()
                .filter(event -> event.getType().name().equals("MEMBER_SESSION_CHANGED"))
                .findFirst().orElse(null);
        assertNotNull(changed);
        assertEquals("member-1", changed.getTeamMemberId());
        assertEquals("team-acp-member-1", changed.getAcpClientId());
        Map data = (Map) changed.getData();
        assertEquals("session-member-1", data.get("oldSessionId"));
        assertEquals("new-session", data.get("newSessionId"));
        assertEquals("AUTO_IDLE", data.get("reason"));
        fixture.manager.close();
    }

    @Test
    public void agentToolRotationPersistsMemberIdentityAndRejectsStaleClient() throws Exception {
        Fixture fixture = fixture();
        TestClient current = fixture.current.get();
        current.changeSession("tool-session");
        current.setClientState(AbstractAcpClient.State.STARTING);
        fixture.manager.onMemberNewSession("team-1", "member-1", current,
                "session-member-1", "tool-session", "continue", "new-message-id");
        TeamMemberDefinition member = fixture.manager.getRuntime("team-1").get()
                .getDefinition().getMembers().get(0);
        assertEquals("tool-session", member.getSessionId());
        assertEquals(TeamMemberState.BUSY, member.getState());
        TeamEventEnvelope changed = fixture.events.stream()
                .filter(event -> event.getType().name().equals("MEMBER_SESSION_CHANGED"))
                .findFirst().get();
        assertEquals("AGENT_TOOL", ((Map) changed.getData()).get("reason"));
        Map accepted = (Map) fixture.events.stream()
                .filter(event -> event.getType().name().equals("USER_MESSAGE_ACCEPTED"))
                .findFirst().get().getData();
        assertEquals("continue", accepted.get("content"));
        assertEquals("new-message-id", accepted.get("messageId"));
        assertEquals("tool-session", accepted.get("sessionId"));
        try {
            fixture.manager.onMemberNewSession("team-1", "member-1", current,
                    "tool-session", "wrong-session", "continue", "new-message-id");
            fail("stale identity accepted");
        } catch (java.io.IOException expected) { }
        fixture.manager.close();
    }

    @Test
    public void rejectsCrossOwnerMismatchedClientAndUnsafeFileNames() throws Exception {
        Fixture fixture = fixture();
        Map<String, String> owner = fixture.handler.handleGetStatus(
                "rpc-owner", one(basePayload().replace("owner-1", "owner-2")));
        Map<String, String> client = fixture.handler.handleGetStatus(
                "rpc-client", one(basePayload().replace(
                        "\"teamMemberId\":\"member-1\"",
                        "\"teamMemberId\":\"member-1\",\"acpClientId\":\"wrong\"")));
        String unsafe = basePayload().replace(
                "}", ",\"message\":\"hello\",\"files\":[{\"../a.txt\":\"aGk=\"}]}");
        Map<String, String> file =
                fixture.handler.handleSend("rpc-file", one(unsafe));

        assertEquals("UNAUTHORIZED", owner.get("code"));
        assertEquals("VALIDATION_ERROR", client.get("code"));
        assertEquals("VALIDATION_ERROR", file.get("code"));
        fixture.manager.close();
    }

    @Test
    public void teamScheduleReplacesOnlyMemberAndUsesNonRecursivePromptOptions()
            throws Exception {
        Fixture fixture = fixture();
        ScheduleOwnerKey owner = ScheduleOwnerKey.team(
                "owner-1", "team-1", "member-1", "Robot One");

        assertTrue(fixture.manager.executeScheduledPrompt(
                owner, "task-1", "[定时任务触发] hello"));

        TestClient replacement = fixture.current.get();
        assertEquals("[定时任务触发] hello", replacement.message);
        assertNotNull(replacement.promptOptions);
        assertTrue(replacement.promptOptions.isScheduleExecution());
        assertTrue(fixture.lastOptions.get().isForceNewSession());
        assertEquals(TeamMemberState.BUSY,
                fixture.manager.getRuntime("team-1").get().getDefinition()
                        .getMembers().get(0).getState());
        assertTrue(fixture.events.stream().anyMatch(event ->
                event.getType().name().equals("SCHEDULE_EVENT")));
        fixture.manager.close();
    }

    @Test
    public void teamScheduleRepairsStaleBusyMemberWhenClientIsReady()
            throws Exception {
        Fixture fixture = fixture();
        ScheduleOwnerKey owner = ScheduleOwnerKey.team(
                "owner-1", "team-1", "member-1", "Robot One");
        fixture.manager.onMemberState(
                "team-1", "member-1", TeamMemberState.BUSY, null);
        assertEquals(AbstractAcpClient.State.READY,
                fixture.current.get().getState());

        assertFalse(fixture.manager.executeScheduledPrompt(
                owner, "task-grace", "[定时任务触发] wait"));
        assertEquals(TeamMemberState.BUSY,
                fixture.manager.getRuntime("team-1").get().getDefinition()
                        .getMembers().get(0).getState());

        java.lang.reflect.Field field = TeamManager.class.getDeclaredField(
                "scheduleStateMismatchSince");
        field.setAccessible(true);
        Map<String, Long> mismatches = (Map<String, Long>) field.get(fixture.manager);
        mismatches.put("team-1:member-1", 0L);

        assertTrue(fixture.manager.executeScheduledPrompt(
                owner, "task-stale", "[定时任务触发] repair"));
        assertEquals("[定时任务触发] repair", fixture.current.get().message);
        assertEquals(TeamMemberState.BUSY,
                fixture.manager.getRuntime("team-1").get().getDefinition()
                        .getMembers().get(0).getState());
        fixture.manager.close();
    }

    @Test
    public void teamScheduleDoesNotRepairAnActuallyBusyClient()
            throws Exception {
        Fixture fixture = fixture();
        ScheduleOwnerKey owner = ScheduleOwnerKey.team(
                "owner-1", "team-1", "member-1", "Robot One");
        TestClient active = fixture.current.get();
        active.setClientState(AbstractAcpClient.State.BUSY);
        fixture.manager.onMemberState(
                "team-1", "member-1", TeamMemberState.BUSY, null);

        assertFalse(fixture.manager.executeScheduledPrompt(
                owner, "task-active", "[定时任务触发] must wait"));
        assertSame(active, fixture.current.get());
        assertEquals(TeamMemberState.BUSY,
                fixture.manager.getRuntime("team-1").get().getDefinition()
                        .getMembers().get(0).getState());
        fixture.manager.close();
    }

    @Test
    public void teamScheduleGroupReusesCurrentSessionOrRestoresBoundSession()
            throws Exception {
        Fixture fixture = fixture();
        ScheduleOwnerKey owner = ScheduleOwnerKey.team(
                "owner-1", "team-1", "member-1", "Robot One");
        TestClient original = fixture.current.get();

        fixture.scheduleTaskManager.bindGroupSession(
                owner, "daily", original.getSessionId());
        assertTrue(fixture.manager.executeScheduledPrompt(
                owner, "task-1", "daily", "same-session"));
        assertSame(original, fixture.current.get());
        assertEquals("same-session", original.message);
        assertNull(fixture.lastOptions.get());

        original.setClientState(AbstractAcpClient.State.READY);
        fixture.manager.onMemberState(
                "team-1", "member-1", TeamMemberState.READY, null);
        fixture.scheduleTaskManager.bindGroupSession(
                owner, "other", "restored-session");
        assertTrue(fixture.manager.executeScheduledPrompt(
                owner, "task-2", "other", "restored"));
        assertEquals("restored-session",
                fixture.lastOptions.get().getTargetRestoreSessionId());
        assertEquals("restored", fixture.current.get().message);
        fixture.manager.close();
    }

    private Fixture fixture() throws Exception {
        TeamStore store = new TeamStore(temporaryFolder.newFolder().toPath());
        Map<String, AcpRobotParam> robots = new HashMap<>();
        robots.put("group-1", robot());
        MapTeamSourceRobotResolver resolver = new MapTeamSourceRobotResolver(robots);
        String fingerprint = resolver.snapshot(new TeamMemberCreateSpec(
                "member-1", "acp-Robot_One", "group-1", 0)).getConfigFingerprint();
        TeamMemberDefinition starting = new TeamMemberDefinition(
                "member-1", "acp-Robot_One", "group-1", "Robot One",
                "Robot One", "", 0, "remark", fingerprint);
        TeamDefinition creating = TeamDefinition.creating(
                "team-1", "owner-1", "Team", "team-acp-instance",
                "request-1", Arrays.asList(starting,
                new TeamMemberDefinition("member-2", "source-2", "Robot Two",
                        "Robot Two", "remark", "fingerprint-2")), 1L);
        List<TeamMemberDefinition> readyMembers = new ArrayList<>();
        for (TeamMemberDefinition member : creating.getMembers()) {
            readyMembers.add(member.withState(
                    TeamMemberState.READY, "session-" + member.getTeamMemberId(), null));
        }
        TeamDefinition ready = creating.transitionWithMembers(
                TeamState.READY, readyMembers, null, 2L);
        store.saveTeam(creating);
        store.saveTeam(ready);

        TeamClientRegistry registry = new TeamClientRegistry();
        AtomicReference<TestClient> current = new AtomicReference<>(
                client(ready, readyMembers.get(0), "session-member-1"));
        registry.register("team-1", "member-1", current.get());
        AtomicReference<TeamMemberStartOptions> lastOptions = new AtomicReference<>();
        TeamStartupCoordinator coordinator = new TeamStartupCoordinator(
                resolver, (runtime, member, snapshot, options, created) -> {
            lastOptions.set(options);
            String sessionId = options.getTargetRestoreSessionId() == null
                    ? "new-session" : options.getTargetRestoreSessionId();
            TestClient replacement = client(
                    runtime.getDefinition(), member, sessionId);
            created.accept(replacement);
            current.set(replacement);
            return replacement;
        }, registry, 1, 1_000L);
        List<TeamEventEnvelope> events = new ArrayList<>();
        TeamManager manager = new TeamManager(
                store, registry, resolver, events::add, coordinator);
        ScheduleTaskManager scheduleTaskManager = new ScheduleTaskManager(
                temporaryFolder.newFolder().toPath());
        manager.setScheduleTaskManager(scheduleTaskManager);
        AtomicReference<String> dreamRoute = new AtomicReference<>();
        manager.setMemoryDreamTrigger((sourceGroupId, workspacePath) -> {
            dreamRoute.set(sourceGroupId);
            return true;
        });
        assertTrue(manager.attachPersistedDefinition(ready));
        return new Fixture(manager, registry, new TeamCommandHandler(
                manager, "team-acp-instance"), current, lastOptions, events,
                dreamRoute, scheduleTaskManager);
    }

    @Test
    public void pagesVisibleHistoryWithStableToolsAndPreservesLegacyFullHistory() throws Exception {
        Fixture fixture = fixture();
        // 显式临时目录，分页索引不能落到开发机的真实会话目录。
        fixture.current.get().isolatedHistory = new com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager(
                com.mola.cmd.proxy.app.acp.starweave.StarweaveIdentity.identity("page-test", "robot"),
                temporaryFolder.newFolder().toPath());
        try {
            com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager h = fixture.current.get().getHistoryManager();
            JsonObject tool = new JsonObject(); tool.addProperty("toolCallId", "tool-1"); tool.addProperty("status", "running");
            h.addEventMessage("TOOL_CALL", tool);
            for (int i = 0; i < 119; i++) h.addUserMessage("消息" + i,
                    com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage.UserOrigin.USER,
                    java.util.Collections.emptyList(), "message-" + i);
            JsonObject finished = tool.deepCopy(); finished.addProperty("status", "completed");
            h.addEventMessage("TOOL_CALL", finished);
            JsonObject request = JsonParser.parseString(basePayload()).getAsJsonObject(); request.addProperty("limit", 50);
            JsonObject latest = json(fixture.handler.handleGetSessionHistory("page-latest", one(request.toString())));
            assertEquals(50, latest.getAsJsonArray("messages").size()); assertTrue(latest.get("hasMore").getAsBoolean());
            assertEquals("消息69", latest.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString());
            request.addProperty("before", latest.get("olderCursor").getAsString());
            JsonObject older = json(fixture.handler.handleGetSessionHistory("page-older", one(request.toString())));
            assertEquals(50, older.getAsJsonArray("messages").size());
            request.addProperty("before", older.get("olderCursor").getAsString());
            JsonObject first = json(fixture.handler.handleGetSessionHistory("page-first", one(request.toString())));
            assertEquals(20, first.getAsJsonArray("messages").size()); assertFalse(first.get("hasMore").getAsBoolean());
            JsonObject card = first.getAsJsonArray("messages").get(0).getAsJsonObject();
            assertEquals("tool:tool-1", card.get("messageId").getAsString());
            assertEquals("completed", card.getAsJsonObject("payload").get("status").getAsString());
            assertEquals(120, json(fixture.handler.handleGetSessionHistory("legacy", one(basePayload())))
                    .getAsJsonArray("messages").size());
        } finally { fixture.manager.close(); }
    }

    private static TestClient client(TeamDefinition team, TeamMemberDefinition member,
                                     String sessionId) {
        AcpRobotParam robot = robot();
        AcpClientIdentity identity = AcpClientIdentity.team(
                member.getAcpClientId(), team.getTransportGroup(),
                "team/team-1/" + member.getTeamMemberId(), team.getOwnerChatterId(),
                team.getTeamId(), member.getTeamMemberId(), member.getSourceRobotName());
        return new TestClient(identity, robot, sessionId);
    }

    @Test public void pageCoalescesGenericToolsAndUsesPersistedEventIdentityForReplay() throws Exception {
        Fixture fixture = fixture();
        fixture.current.get().isolatedHistory = new com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager(
                com.mola.cmd.proxy.app.acp.starweave.StarweaveIdentity.identity("page-test", "robot"), temporaryFolder.newFolder().toPath());
        try {
            com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager h = fixture.current.get().getHistoryManager();
            h.addToolMessage("generic-tool", "test", "running", new JsonObject(), new JsonObject());
            h.addToolMessage("generic-tool", "test", "completed", new JsonObject(), new JsonObject());
            JsonObject event = new JsonObject(); event.addProperty("messageId", "wake-id"); event.addProperty("revision", 42);
            h.addEventMessage("LIFECYCLE_EVENT", event);
            JsonObject request = JsonParser.parseString(basePayload()).getAsJsonObject(); request.addProperty("limit", 50);
            JsonArray messages = json(fixture.handler.handleGetSessionHistory("generic-page", one(request.toString()))).getAsJsonArray("messages");
            assertEquals(2, messages.size());
            assertEquals("tool:generic-tool", messages.get(0).getAsJsonObject().get("messageId").getAsString());
            assertEquals("completed", messages.get(0).getAsJsonObject().get("status").getAsString());
            assertEquals("wake-id", messages.get(1).getAsJsonObject().get("messageId").getAsString());
            assertEquals(42, messages.get(1).getAsJsonObject().get("revision").getAsLong());
        } finally { fixture.manager.close(); }
    }

    private static AcpRobotParam robot() {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName("Robot One");
        return robot;
    }

    private static String basePayload() {
        return "{\"schemaVersion\":\"1\",\"ownerChatterId\":\"owner-1\","
                + "\"teamId\":\"team-1\",\"teamMemberId\":\"member-1\"}";
    }

    private static String[] one(String value) {
        return new String[]{value};
    }

    private static JsonObject json(Map<String, String> result) {
        return JsonParser.parseString(result.get("data")).getAsJsonObject();
    }

    private static AbstractAcpClient.State clientStateFor(TeamMemberState state) {
        switch (state) {
            case STARTING: return AbstractAcpClient.State.STARTING;
            case ERROR: return AbstractAcpClient.State.ERROR;
            case CLOSING: return AbstractAcpClient.State.CLOSING;
            case CLOSED: return AbstractAcpClient.State.CLOSED;
            default: throw new IllegalArgumentException(state.name());
        }
    }

    private static final class TestClient extends AcpClient {
        private com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager isolatedHistory;
        @Override public com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager getHistoryManager() {
            return isolatedHistory == null ? super.getHistoryManager() : isolatedHistory;
        }
        private void changeSession(String sessionId) { setSessionId(sessionId); }
        private String message;
        private List<Map<String, String>> files;
        private boolean cancelled;
        private boolean closed;
        private PromptOptions promptOptions;
        private boolean allowAutomaticRotation;

        private TestClient(AcpClientIdentity identity, AcpRobotParam robot,
                           String sessionId) {
            super(".", identity, robot);
            state.set(AbstractAcpClient.State.READY);
            setSessionId(sessionId);
            contextUsagePercentage = 42.5;
        }

        @Override
        public void send(String input, List<Map<String, String>> attachments) {
            this.message = input;
            this.files = attachments;
        }

        @Override
        public void send(String input, List<Map<String, String>> attachments,
                         PromptOptions options) {
            this.message = input;
            this.files = attachments;
            this.promptOptions = options;
        }

        @Override
        public void cancel() {
            this.cancelled = true;
        }

        @Override
        public boolean tryReserveIdleForAutoNewSession(long nowMillis, long idleMillis) {
            return allowAutomaticRotation
                    && state.compareAndSet(State.READY, State.STARTING);
        }

        private void setClientState(AbstractAcpClient.State update) {
            state.set(update);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            state.set(AbstractAcpClient.State.CLOSED);
        }
    }

    private static final class Fixture {
        private final TeamManager manager;
        private final TeamClientRegistry registry;
        private final TeamCommandHandler handler;
        private final AtomicReference<TestClient> current;
        private final AtomicReference<TeamMemberStartOptions> lastOptions;
        private final List<TeamEventEnvelope> events;
        private final AtomicReference<String> dreamRoute;
        private final ScheduleTaskManager scheduleTaskManager;

        private Fixture(TeamManager manager, TeamClientRegistry registry,
                        TeamCommandHandler handler, AtomicReference<TestClient> current,
                        AtomicReference<TeamMemberStartOptions> lastOptions,
                        List<TeamEventEnvelope> events,
                        AtomicReference<String> dreamRoute,
                        ScheduleTaskManager scheduleTaskManager) {
            this.manager = manager;
            this.registry = registry;
            this.handler = handler;
            this.current = current;
            this.lastOptions = lastOptions;
            this.events = events;
            this.dreamRoute = dreamRoute;
            this.scheduleTaskManager = scheduleTaskManager;
        }
    }
}
