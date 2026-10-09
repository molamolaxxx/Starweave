package com.mola.cmd.proxy.app.acp.team.listener;

import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventType;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberState;
import com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage;
import com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class TeamAcpResponseListenerTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void boundsStructuredAndRenderedToolEventsButPersistsOriginal() throws Exception {
        TeamMemberDefinition member = member("member-large", 0);
        TeamRuntime runtime = new TeamRuntime(TeamDefinition.creating(
                "team-large", "owner-1", "Team", "team-acp-instance", "request-large",
                java.util.Collections.singletonList(member), 100L));
        ConversationHistoryManager history = new ConversationHistoryManager(
                AcpClientIdentity.team("team-acp-member-large", "team-acp-instance",
                        "team/team-large/member-large", "owner-1", "team-large", "member-large", "Source"),
                temporary.newFolder("large-history").toPath());
        List<TeamEventEnvelope> events = new ArrayList<>();
        TeamAcpResponseListener listener = new TeamAcpResponseListener(
                runtime, member, events::add, TeamMemberStateObserver.NOOP, history);
        String large = String.join("", java.util.Collections.nCopies(100000, "large output\n"));
        JsonObject output = new JsonObject(); output.addProperty("text", large);
        JsonObject update = new JsonObject(); update.add("rawOutput", output);
        listener.onToolCall("large-tool", "Read", "completed", update);

        assertEquals(large, history.getCurrentTurn().get(0).getEventData()
                .getAsJsonObject("update").getAsJsonObject("rawOutput").get("text").getAsString());
        assertEquals(large, update.getAsJsonObject("rawOutput").get("text").getAsString());
        assertTrue(events.stream().anyMatch(event -> event.getType() == TeamEventType.MESSAGE_CHUNK));
        assertTrue(events.stream().anyMatch(event -> event.getType() == TeamEventType.TOOL_CALL));
        for (TeamEventEnvelope event : events) {
            assertTrue(new com.google.gson.Gson().toJson(event).getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 100000);
        }
        Map<?, ?> projected = (Map<?, ?>) events.stream()
                .filter(event -> event.getType() == TeamEventType.TOOL_CALL).findFirst().get().getData();
        assertEquals("large-tool", projected.get("toolCallId"));
        assertEquals(Boolean.TRUE, projected.get("outputTruncated"));
        assertEquals(history.getCurrentTurn().get(0).getEventData().get("messageId").getAsString(), projected.get("messageId"));
    }

    @Test
    public void mapsMessageToolAndCompletionWithStableMemberRoute() {
        Fixture fixture = fixture();
        JsonObject update = new JsonObject();
        update.addProperty("kind", "read");

        fixture.listener.onMessage("hello");
        fixture.listener.onToolCall("tool-1", "Read", "completed", update);
        fixture.listener.onComplete("hello");

        assertEquals(Arrays.asList(
                        TeamEventType.MESSAGE_CHUNK,
                        TeamEventType.MESSAGE_CHUNK,
                        TeamEventType.TOOL_CALL,
                        TeamEventType.MESSAGE_COMPLETE),
                types(fixture.events));
        assertEquals("member-1", fixture.events.get(0).getTeamMemberId());
        assertEquals("team-acp-member-1", fixture.events.get(0).getAcpClientId());
        assertEquals(1L, fixture.events.get(0).getEventSeq());
        assertEquals(4L, fixture.events.get(3).getEventSeq());
    }

    @Test
    public void mapsErrorToStructuredRetryableTeamError() {
        Fixture fixture = fixture();

        fixture.listener.onError(new IllegalStateException("provider failed"));

        Map<?, ?> error = (Map<?, ?>) fixture.events.get(0).getData();
        assertEquals(TeamEventType.MESSAGE_ERROR, fixture.events.get(0).getType());
        assertEquals("provider failed", error.get("message"));
        assertEquals(Boolean.TRUE, error.get("retryable"));
        assertEquals("====== 发生错误 ======\nprovider failed",
                error.get("content"));
    }

    @Test
    public void mapsAuxiliaryCallbacksWithoutChangingNormalAcpBehavior() {
        Fixture fixture = fixture();

        fixture.listener.onSubAgentEvent("AGENT_START", "worker", "running");
        fixture.listener.onScheduleEvent("SCHEDULE_CREATE", "task", true);
        fixture.listener.onTalkToEvent("TALK_TO_RECEIVE", "peer", "message");
        fixture.listener.onTalkToEvent("TALK_TO_REJECTED", "peer", "rejected");
        fixture.listener.onCompactionEvent("COMPACTION_COMPLETED", "codex");

        assertEquals(Arrays.asList(
                TeamEventType.MESSAGE_CHUNK,
                TeamEventType.SUB_AGENT_EVENT,
                TeamEventType.MESSAGE_CHUNK,
                TeamEventType.SCHEDULE_EVENT,
                TeamEventType.TALK_TO_RECEIVE,
                TeamEventType.TALK_TO_REJECTED,
                TeamEventType.MESSAGE_CHUNK,
                TeamEventType.COMPACTION_EVENT), types(fixture.events));
    }

    @Test
    public void persistsUiOnlyEventsForTeamHistoryRecovery() throws Exception {
        TeamMemberDefinition member = member("member-history", 0);
        TeamRuntime runtime = new TeamRuntime(TeamDefinition.creating(
                "team-history", "owner-1", "Team", "team-acp-instance",
                "request-1", java.util.Collections.singletonList(member), 100L));
        ConversationHistoryManager history = new ConversationHistoryManager(
                AcpClientIdentity.team("team-acp-member-history", "team-acp-instance",
                        "team/team-history/member-history", "owner-1",
                        "team-history", "member-history", "Source"),
                temporary.newFolder("history").toPath());
        TeamAcpResponseListener listener = new TeamAcpResponseListener(
                runtime, member, event -> { }, TeamMemberStateObserver.NOOP, history);

        listener.onSubAgentEvent("AGENT_COMPLETE", "worker", "done");
        listener.onScheduleEvent("SCHEDULE_CREATE", "created", true);
        listener.onCompactionEvent("COMPACTION_COMPLETED", "codex");
        listener.onError(new IllegalStateException("failed"));
        JsonObject taskCard = new JsonObject();
        taskCard.addProperty("cardType", "STARWEAVE_TASK");
        taskCard.addProperty("eventId", "task-event-1");
        listener.onTaskEvent(taskCard);

        assertEquals(5, history.getCurrentTurn().size());
        for (ContextMessage message : history.getCurrentTurn()) {
            assertEquals(ContextMessage.Role.EVENT, message.getRole());
        }
        assertEquals("SUB_AGENT_EVENT", history.getCurrentTurn().get(0).getEventType());
        assertEquals("MESSAGE_ERROR", history.getCurrentTurn().get(3).getEventType());
        assertEquals("TASK_EVENT", history.getCurrentTurn().get(4).getEventType());
        assertEquals("task-event-1", history.getCurrentTurn().get(4).getEventData()
                .get("eventId").getAsString());
    }

    @Test
    public void snapshotAndLiveProjectionShareIdentityAndErrorsEndPartialReply() throws Exception {
        TeamMemberDefinition member = member("member-projection", 0);
        TeamRuntime runtime = new TeamRuntime(TeamDefinition.creating(
                "team-projection", "owner-1", "Team", "team-acp-instance",
                "request-1", java.util.Collections.singletonList(member), 100L));
        ConversationHistoryManager history = new ConversationHistoryManager(
                AcpClientIdentity.team("team-acp-member-projection", "team-acp-instance",
                        "team/team-projection/member-projection", "owner-1",
                        "team-projection", "member-projection", "Source"),
                temporary.newFolder("projection").toPath());
        List<TeamEventEnvelope> events = new ArrayList<>();
        TeamAcpResponseListener listener = new TeamAcpResponseListener(
                runtime, member, events::add, TeamMemberStateObserver.NOOP, history);
        listener.onLifecycleEvent("AGENT_WAKE", "SLEEP", "READY", 10, true);
        ContextMessage wake = history.getUiHistory("s").get(0);
        TeamEventEnvelope liveWake = events.stream()
                .filter(event -> event.getType() == TeamEventType.LIFECYCLE_EVENT).findFirst().get();
        assertEquals(wake.getEventData().get("messageId").getAsString(),
                ((Map<?, ?>) liveWake.getData()).get("messageId"));
        listener.onMessage("partial");
        String firstId = history.getUiHistory("s").get(1).getMessageId();
        listener.onError(new IllegalStateException("interrupted"));
        listener.onMessage("next");
        List<ContextMessage> snapshot = history.getUiHistory("s");
        ContextMessage next = snapshot.get(snapshot.size() - 1);
        assertNotEquals(firstId, next.getMessageId());
        assertEquals("next", next.getContent());
        TeamEventEnvelope liveNext = events.stream()
                .filter(event -> event.getType() == TeamEventType.MESSAGE_UPDATED)
                .reduce((first, second) -> second).get();
        assertEquals(next.getMessageId(), ((Map<?, ?>) liveNext.getData()).get("messageId"));
        assertEquals(next.getRevision(), ((Map<?, ?>) liveNext.getData()).get("revision"));
    }

    @Test
    public void authoritativeReadyWaitsForClientLifecycleTransition() {
        TeamMemberDefinition first = member("member-1", 0);
        TeamRuntime runtime = new TeamRuntime(TeamDefinition.creating(
                "team-1", "owner-1", "Team", "team-acp-instance", "request-1",
                Arrays.asList(first, member("member-2", 1)), 100L));
        List<TeamMemberState> states = new ArrayList<>();
        TeamAcpResponseListener listener = new TeamAcpResponseListener(
                runtime, first, event -> {
        }, (teamId, memberId, state, error) -> states.add(state));

        listener.onComplete("done");
        assertTrue(states.isEmpty());

        listener.onClientReady();
        listener.onError(new IllegalStateException("failed"));

        assertEquals(Arrays.asList(
                TeamMemberState.READY, TeamMemberState.ERROR), states);
    }

    @Test
    public void dropsCallbacksAfterRuntimeStopsAcceptingRequests() {
        Fixture fixture = fixture();
        fixture.runtime.stopAcceptingRequests();

        fixture.listener.onMessage("late");
        fixture.listener.onError(new RuntimeException("late"));

        assertTrue(fixture.events.isEmpty());
        assertEquals(0L, fixture.runtime.getLatestEventSeq());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMemberFromAnotherTeamDefinition() {
        Fixture fixture = fixture();
        TeamMemberDefinition foreign = member("foreign-member", 0);

        new TeamAcpResponseListener(fixture.runtime, foreign, fixture.events::add);
    }

    private static Fixture fixture() {
        TeamMemberDefinition first = member("member-1", 0);
        TeamDefinition definition = TeamDefinition.creating(
                "team-1", "owner-1", "Team", "team-acp-instance", "request-1",
                Arrays.asList(first, member("member-2", 1)), 100L);
        TeamRuntime runtime = new TeamRuntime(definition);
        List<TeamEventEnvelope> events = new ArrayList<>();
        return new Fixture(runtime, events,
                new TeamAcpResponseListener(runtime, first, events::add));
    }

    private static List<TeamEventType> types(List<TeamEventEnvelope> events) {
        List<TeamEventType> result = new ArrayList<>();
        for (TeamEventEnvelope event : events) {
            result.add(event.getType());
        }
        return result;
    }

    private static TeamMemberDefinition member(String id, int order) {
        return new TeamMemberDefinition(
                id, "acp-Robot_" + order, "source-" + id,
                "Robot " + id, "Robot " + id, "", order,
                "remark", "fingerprint-" + id);
    }

    private static final class Fixture {
        private final TeamRuntime runtime;
        private final List<TeamEventEnvelope> events;
        private final TeamAcpResponseListener listener;

        private Fixture(TeamRuntime runtime, List<TeamEventEnvelope> events,
                        TeamAcpResponseListener listener) {
            this.runtime = runtime;
            this.events = events;
            this.listener = listener;
        }
    }
}
