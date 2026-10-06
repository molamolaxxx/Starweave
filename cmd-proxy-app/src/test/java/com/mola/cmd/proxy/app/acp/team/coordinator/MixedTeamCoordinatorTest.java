package com.mola.cmd.proxy.app.acp.team.coordinator;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.util.*;
import static org.junit.Assert.*;

public class MixedTeamCoordinatorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private CoordinationStore store;
    private FakeTransport transport;
    private MixedTeamCoordinator coordinator;
    @Before public void setup() throws Exception {
        store = new CoordinationStore(temporary.newFolder().toPath());
        transport = new FakeTransport();
        transport.descriptors.put("a", descriptor("a", "user", false));
        transport.descriptors.put("b", descriptor("b", "user", true));
        coordinator = new MixedTeamCoordinator(store, transport);
    }

    @Test public void createsFragmentsAfterPersistingGlobalRecordAndKeepsFullRoster() {
        transport.onCommand = () -> assertNotNull(store.find("teams", "t1"));
        JSONObject team = coordinator.execute("user", "a", "create", request()).getJSONObject("team");
        assertEquals("READY", team.getString("state"));
        assertEquals(2, team.getJSONArray("members").size());
        assertEquals(2, transport.creates.size());
        assertEquals(1, transport.creates.get(0).getJSONArray("members").size());
        assertEquals(2, transport.creates.get(0).getJSONArray("roster").size());
        assertEquals("user", transport.creates.get(1).getString("ownerChatterId"));
        for (Object value : team.getJSONArray("members")) {
            JSONObject member = (JSONObject) value;
            assertEquals(member.getString("state"), member.getString("status"));
        }
    }

    @Test public void existingStaleStatusIsNormalizedWithoutRecreatingTeam() {
        coordinator.execute("user", "a", "create", request());
        JSONObject record = store.find("teams", "t1");
        for (Object value : record.getJSONArray("members")) {
            JSONObject member = (JSONObject) value;
            member.put("state", "READY"); member.put("status", "STARTING");
        }
        store.save("teams", "t1", record);
        JSONObject team = coordinator.execute("user", "a", "list", new JSONObject()).getJSONArray("teams").getJSONObject(0);
        for (Object value : team.getJSONArray("members")) {
            JSONObject member = (JSONObject) value;
            assertEquals("READY", member.getString("status"));
        }
        assertEquals(2, transport.creates.size());
    }
    @Test public void participantStateChangesUpdatePersistedAndProjectedMemberStatus() {
        coordinator.execute("user", "a", "create", request());
        JSONObject fragment = MixedTeamCoordinator.copy(transport.fragments.get("a"));
        fragment.put("version", 4L);
        JSONObject member = fragment.getJSONArray("members").getJSONObject(0);
        member.put("state", "BUSY"); member.remove("status");
        JSONObject changed = event("state-change", "MEMBER_STATE_CHANGED");
        changed.put("data", object("team", fragment));
        coordinator.acceptEvent("a", changed); coordinator.drainEvents();
        JSONObject saved = store.find("teams", "t1").getJSONArray("members").getJSONObject(0);
        assertEquals("BUSY", saved.getString("state")); assertEquals("BUSY", saved.getString("status"));
        JSONObject projected = transport.projected.get(transport.projected.size() - 1)
                .getJSONObject("data").getJSONObject("team").getJSONArray("members").getJSONObject(0);
        assertEquals("BUSY", projected.getString("status"));
    }

    @Test public void retriesSameCreateWithoutCreatingAgainAndRejectsConflictingPayload() {
        coordinator.execute("user", "a", "create", request());
        coordinator.execute("user", "a", "create", request());
        assertEquals(2, transport.creates.size());
        JSONObject changed = request(); changed.put("name", "different");
        expect("IDEMPOTENCY_CONFLICT", () -> coordinator.execute("user", "a", "create", changed));
    }

    @Test public void chatterGrantIsDiscoveredOnlyForItsExactOwner() {
        JSONArray granted = coordinator.sources("user", "a").getJSONArray("sources");
        assertEquals(2, granted.size());
        assertEquals(0, coordinator.sources("another", "a").getJSONArray("sources").size());
        assertEquals(0, coordinator.sources("starweave-a", "a").getJSONArray("sources").size());
    }

    @Test public void rejectsUngrantAndRemoteOnlyBeforeSendingAnyCommands() {
        JSONObject invalid = request(); invalid.getJSONArray("members").getJSONObject(1).put("sourceGroupId", "unshared");
        expect("UNAUTHORIZED", () -> coordinator.execute("user", "a", "create", invalid));
        JSONObject remoteOnly = request(); remoteOnly.getJSONArray("members").remove(0);
        expect("REMOTE_ONLY_TEAM", () -> coordinator.execute("user", "a", "create", remoteOnly));
        assertTrue(transport.creates.isEmpty());
    }

    @Test public void rejectsOtherOwnerAndOtherHomeForExistingTeam() {
        coordinator.execute("user", "a", "create", request());
        expect("UNAUTHORIZED", () -> coordinator.execute("another", "a", "get", object("teamId", "t1")));
        expect("UNAUTHORIZED", () -> coordinator.execute("user", "b", "get", object("teamId", "t1")));
    }

    @Test public void memberCommandsPreserveFilesAndRouteToRemoteInstance() {
        coordinator.execute("user", "a", "create", request());
        JSONObject command = object("teamId", "t1"); command.put("teamMemberId", "m2");
        command.put("action", "send"); command.put("requestId", "send-id");
        command.put("files", JSON.parseArray("[{\"image.png\":\"aGVsbG8=\"}]"));
        coordinator.execute("user", "a", "member", command);
        assertEquals("b:send", transport.lastCommand);
        assertEquals(command.getJSONArray("files"), transport.lastPayload.getJSONArray("files"));
        assertEquals("send-id", transport.lastPayload.getString("requestId"));
        assertEquals("user", transport.lastPayload.getString("ownerChatterId"));
    }

    @Test public void failedAndUncertainCreateCompensatesEveryParticipant() {
        transport.failCreateB = true;
        expect("TRANSPORT_FAILED", () -> coordinator.execute("user", "a", "create", request()));
        assertTrue(transport.deleted.contains("a"));
        assertTrue(transport.deleted.contains("b"));
        assertEquals("DELETED", store.find("teams", "t1").getString("state"));
    }

    @Test public void restartReconcilesDefinitionsWithoutRecreatingReadyMembers() {
        coordinator.execute("user", "a", "create", request());
        MixedTeamCoordinator restarted = new MixedTeamCoordinator(store, transport);
        restarted.reconcileAll();
        assertEquals(2, transport.creates.size());
        assertEquals("READY", restarted.execute("user", "a", "get", object("teamId", "t1")).getJSONObject("team").getString("state"));
    }

    @Test public void duplicateTalkToRetainsIdentityAndTraceAndIsDeliveredOnce() {
        coordinator.execute("user", "a", "create", request());
        JSONObject route = event("e1", "TALK_TO_ROUTE_REQUEST");
        JSONObject data = object("messageId", "msg1"); data.put("senderTeamMemberId", "m1");
        data.put("targetTeamMemberId", "m2"); data.put("content", "work"); data.put("depth", 1);
        data.put("createdAt", System.currentTimeMillis()); data.put("expiresAt", System.currentTimeMillis() + 10000);
        data.put("cascadeId", "cascade1"); data.put("authPrincipalId", "user"); route.put("data", data);
        coordinator.acceptEvent("a", route); coordinator.acceptEvent("a", route);
        coordinator.drainEvents(); coordinator.acceptEvent("a", route); coordinator.drainEvents();
        assertEquals(1, transport.talkToCount);
        assertEquals("b:talkTo", transport.lastCommand);
        assertEquals("cascade1", transport.lastPayload.getString("cascadeId"));
        assertEquals("user", transport.lastPayload.getString("authPrincipalId"));
    }

    @Test public void rejectsSpoofedParticipantEvent() {
        coordinator.execute("user", "a", "create", request());
        expect("UNAUTHORIZED", () -> coordinator.acceptEvent("c", event("e1", "MESSAGE_COMPLETE")));
    }
    @Test public void realtimeRoutingChecksPlacementAndCannotInjectControlOrRollBackSession() {
        coordinator.execute("user", "a", "create", request());
        JSONObject text = event("text", "MESSAGE_CHUNK"); text.put("teamMemberId", "m2");
        JSONObject target = coordinator.realtimeDestination("b", text);
        assertEquals("a", target.getString("homeInstanceId")); assertEquals("user", target.getString("ownerChatterId"));
        expect("UNAUTHORIZED", () -> coordinator.realtimeDestination("a", text));
        expect("UNAUTHORIZED", () -> coordinator.realtimeDestination("c", text));
        JSONObject control = event("control", "TEAM_DELETED"); control.put("teamMemberId", "m2");
        expect("UNAUTHORIZED", () -> coordinator.realtimeDestination("b", control));
        JSONObject session = event("session", "MEMBER_SESSION_CHANGED"); session.put("teamMemberId", "m2"); session.put("teamVersion", 8);
        assertEquals("a", coordinator.realtimeDestination("b", session).getString("homeInstanceId"));
        session.put("teamVersion", 7);
        assertTrue(coordinator.realtimeDestination("b", session).getBooleanValue("ignored"));
        assertTrue(store.list("inbox").isEmpty()); assertTrue(store.list("receipts").isEmpty());
    }

    @Test public void failedProjectionRemainsDurableUntilHomeRecovers() {
        coordinator.execute("user", "a", "create", request());
        transport.failProjection = true;
        coordinator.acceptEvent("b", event("e1", "MESSAGE_COMPLETE")); coordinator.drainEvents();
        assertEquals(1, store.list("inbox").size());
        transport.failProjection = false;
        new MixedTeamCoordinator(store, transport).drainEvents();
        assertTrue(store.list("inbox").isEmpty()); assertEquals(1, transport.projected.size());
    }

    @Test public void deleteQueriesAndCleansAllFragmentsAndLeavesTombstone() {
        coordinator.execute("user", "a", "create", request());
        JSONObject response = coordinator.execute("user", "a", "delete", object("teamId", "t1"));
        assertEquals("DELETED", response.getJSONObject("team").getString("state"));
        assertEquals(2, transport.deleted.size());
        assertEquals(0, coordinator.execute("user", "a", "list", new JSONObject()).getJSONArray("teams").size());
        assertNotNull(store.find("teams", "t1"));
    }

    @Test public void unchangedReconciliationDoesNotInvalidateExpectedVersion() {
        JSONObject team = coordinator.execute("user", "a", "create", request()).getJSONObject("team");
        coordinator.reconcileAll(); coordinator.reconcileAll();
        assertEquals(team.getLong("version"), store.find("teams", "t1").getLong("version"));
        JSONObject deletion = object("teamId", "t1"); deletion.put("expectedVersion", team.getLong("version"));
        assertEquals("DELETED", coordinator.execute("user", "a", "delete", deletion).getJSONObject("team").getString("state"));
    }

    @Test public void staleSessionEventCannotReplaceCurrentSessionOrReachHome() {
        coordinator.execute("user", "a", "create", request());
        JSONObject recent = event("session-new", "MEMBER_SESSION_CHANGED");
        recent.put("teamMemberId", "m2"); recent.put("teamVersion", 8L); recent.put("data", object("newSessionId", "new"));
        coordinator.acceptEvent("b", recent); coordinator.drainEvents();
        JSONObject old = event("session-old", "MEMBER_SESSION_CHANGED");
        old.put("teamMemberId", "m2"); old.put("teamVersion", 7L); old.put("data", object("newSessionId", "old"));
        coordinator.acceptEvent("b", old); coordinator.drainEvents();
        assertEquals("new", store.find("teams", "t1").getJSONArray("members").getJSONObject(1).getString("sessionId"));
        assertEquals(1, transport.projected.size()); assertTrue(store.list("inbox").isEmpty());
    }

    @Test public void authenticatedParticipantCannotForgeAnotherParticipantsMessage() {
        coordinator.execute("user", "a", "create", request());
        JSONObject forged = event("forged", "MESSAGE_COMPLETE"); forged.put("teamMemberId", "m2");
        expect("UNAUTHORIZED", () -> coordinator.acceptEvent("a", forged));
        assertTrue(store.list("inbox").isEmpty());
    }

    @Test public void sessionCommandVersionPreventsDelayedEventFromUndoingItsResult() {
        coordinator.execute("user", "a", "create", request());
        transport.memberResult = object("sessionId", "command-session"); transport.memberResult.put("participantTeamVersion", 9L);
        JSONObject command = object("teamId", "t1"); command.put("teamMemberId", "m2"); command.put("action", "newSession");
        JSONObject result = coordinator.execute("user", "a", "member", command);
        assertFalse(result.containsKey("participantTeamVersion"));
        JSONObject delayed = event("delayed-session", "MEMBER_SESSION_CHANGED"); delayed.put("teamMemberId", "m2");
        delayed.put("teamVersion", 8L); delayed.put("data", object("newSessionId", "previous-session"));
        coordinator.acceptEvent("b", delayed); coordinator.drainEvents();
        assertEquals("command-session", store.find("teams", "t1").getJSONArray("members").getJSONObject(1).getString("sessionId"));
        assertTrue(transport.projected.isEmpty());
    }

    @Test public void offlineGrantedSourceRemainsVisibleButCannotBeUsedToCreate() {
        transport.descriptors.get("b").put("coordinationOnline", false);
        assertEquals("TEAM_NOT_READY", coordinator.sources("user", "a").getJSONArray("sources").getJSONObject(1).getString("status"));
        expect("TEAM_NOT_READY", () -> coordinator.execute("user", "a", "create", request()));
        assertTrue(transport.creates.isEmpty());
    }

    @Test public void captainTopologyAndExpiryAreEnforcedForRemoteRouting() {
        transport.descriptors.put("c", descriptor("c", "user", true));
        JSONObject request = request(); request.put("mode", "CAPTAIN"); request.put("captainTeamMemberId", "m1");
        JSONObject third = object("teamMemberId", "m3"); third.put("cmdProxyInstanceId", "c");
        third.put("sourceGroupId", "shared-c"); third.put("sourceRobotId", "robot-c"); request.getJSONArray("members").add(third);
        coordinator.execute("user", "a", "create", request);
        JSONObject forbidden = route("forbidden", "m2", "m3"); coordinator.acceptEvent("b", forbidden); coordinator.drainEvents();
        assertEquals("TEAM_COMMUNICATION_FORBIDDEN", store.find("receipts", "forbidden").getString("code"));
        JSONObject toCaptain = route("to-captain", "m2", "m1"); coordinator.acceptEvent("b", toCaptain); coordinator.drainEvents();
        assertEquals("a:talkTo", transport.lastCommand);
        JSONObject fromCaptain = route("from-captain", "m1", "m3"); coordinator.acceptEvent("a", fromCaptain); coordinator.drainEvents();
        assertEquals("c:talkTo", transport.lastCommand);
        JSONObject expired = route("expired", "m1", "m2"); expired.getJSONObject("data").put("expiresAt", System.currentTimeMillis() - 1);
        coordinator.acceptEvent("a", expired); coordinator.drainEvents();
        assertEquals("DELIVERY_EXPIRED", store.find("receipts", "expired").getString("code"));
        assertEquals(2, transport.talkToCount); assertTrue(store.list("inbox").isEmpty());
    }

    @Test public void deletedTeamConsumesLateTalkToWithoutResurrectingOrRetrying() {
        coordinator.execute("user", "a", "create", request());
        coordinator.execute("user", "a", "delete", object("teamId", "t1"));
        coordinator.acceptEvent("a", route("late", "m1", "m2")); coordinator.drainEvents();
        assertEquals("TEAM_DELETED", store.find("receipts", "late").getString("code"));
        assertTrue(store.list("inbox").isEmpty()); assertEquals(0, transport.talkToCount);
    }

    @Test public void mixedRosterUpdateRemainsRejectedBeforeParticipantCommands() {
        coordinator.execute("user", "a", "create", request());
        String previous = transport.lastCommand;
        expect("MIXED_TEAM_UPDATE_UNSUPPORTED", () -> coordinator.execute("user", "a", "update", object("teamId", "t1")));
        assertEquals(previous, transport.lastCommand);
    }

    private JSONObject route(String id, String sender, String target) {
        JSONObject event = event(id, "TALK_TO_ROUTE_REQUEST"); JSONObject data = object("messageId", id);
        data.put("senderTeamMemberId", sender); data.put("targetTeamMemberId", target); data.put("depth", 1);
        data.put("createdAt", System.currentTimeMillis()); data.put("expiresAt", System.currentTimeMillis() + 10000);
        data.put("content", "test"); event.put("data", data); return event;
    }

    private JSONObject request() {
        JSONObject request = object("teamId", "t1"); request.put("requestId", "r1"); request.put("name", "team");
        request.put("members", JSON.parseArray("[{\"teamMemberId\":\"m1\",\"cmdProxyInstanceId\":\"a\",\"sourceGroupId\":\"local-a\",\"sourceRobotId\":\"robot-a\"},{\"teamMemberId\":\"m2\",\"cmdProxyInstanceId\":\"b\",\"sourceGroupId\":\"shared-b\",\"sourceRobotId\":\"robot-b\"}]"));
        return request;
    }
    private static JSONObject descriptor(String instance, String owner, boolean remote) {
        JSONObject d = object("cmdProxyInstanceId", instance); d.put("transportGroup", "team-acp-" + instance); d.put("businessCommandsReady", true);
        JSONObject source = object(remote ? "granteeOwnerChatterId" : "ownerChatterId", owner);
        source.put("participantInstanceId", instance); source.put("sourceGroupId", (remote ? "shared-" : "local-") + instance);
        source.put("sourceRobotId", "robot-" + instance); source.put("displayName", instance);
        JSONArray array = new JSONArray(); array.add(source); d.put(remote ? "remoteTeamMemberSources" : "teamMemberSources", array); return d;
    }
    private static JSONObject event(String id, String type) {
        JSONObject event = object("eventId", id); event.put("teamId", "t1"); event.put("type", type);
        event.put("timestamp", System.currentTimeMillis()); event.put("data", new JSONObject()); return event;
    }
    private static JSONObject object(String key, Object value) { return MixedTeamCoordinator.object(key, value); }
    private static void expect(String code, Runnable action) {
        try { action.run(); fail("expected " + code); } catch (CoordinationException e) { assertEquals(code, e.getCode()); }
    }
    private static final class FakeTransport implements MixedTeamCoordinator.Transport {
        final Map<String, JSONObject> descriptors = new LinkedHashMap<>();
        final Map<String, JSONObject> fragments = new HashMap<>();
        final List<JSONObject> creates = new ArrayList<>(), projected = new ArrayList<>();
        final Set<String> deleted = new HashSet<>();
        Runnable onCommand = () -> { };
        boolean failCreateB, failProjection; String lastCommand; JSONObject lastPayload, memberResult; int talkToCount;
        @Override public Map<String, JSONObject> describeParticipants() { return descriptors; }
        @Override public JSONObject command(String instance, String operation, JSONObject payload) {
            onCommand.run(); lastCommand = instance + ":" + operation; lastPayload = MixedTeamCoordinator.copy(payload);
            if ("create".equals(operation)) {
                creates.add(MixedTeamCoordinator.copy(payload));
                JSONObject team = MixedTeamCoordinator.copy(payload); team.put("state", "READY"); team.put("version", 3L);
                fragments.put(instance, team);
                if (failCreateB && "b".equals(instance)) throw new CoordinationException("TRANSPORT_FAILED", "unknown result");
                return object("team", team);
            }
            if ("delete".equals(operation)) {
                deleted.add(instance); JSONObject team = fragments.get(instance);
                if (team == null) throw new CoordinationException("NOT_FOUND", "absent");
                team.put("state", "DELETED"); team.put("version", 4L); return object("team", team);
            }
            if ("get".equals(operation)) return object("team", fragments.get(instance));
            if ("talkTo".equals(operation)) talkToCount++;
            if (memberResult != null) return MixedTeamCoordinator.copy(memberResult);
            return object("accepted", true);
        }
        @Override public void project(String home, String owner, JSONObject event) {
            if (failProjection) throw new CoordinationException("TRANSPORT_FAILED", "home offline");
            projected.add(MixedTeamCoordinator.copy(event));
        }
    }
}
