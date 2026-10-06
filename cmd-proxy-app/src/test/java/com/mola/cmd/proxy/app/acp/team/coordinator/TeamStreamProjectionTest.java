package com.mola.cmd.proxy.app.acp.team.coordinator;

import com.alibaba.fastjson.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.Assert.*;

public class TeamStreamProjectionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private JSONObject event(String id, String type, String content) {
        JSONObject e = MixedTeamCoordinator.object("eventId", id, "type", type);
        e.put("teamId", "team"); e.put("teamMemberId", "member");
        e.put("data", MixedTeamCoordinator.object("content", content)); return e;
    }
    private List<JSONObject> turn(TeamStreamProjection source) {
        List<JSONObject> events = Arrays.asList(event("a", "MESSAGE_CHUNK", "好的，需要时随时"),
                event("b", "MESSAGE_CHUNK", "找我。"), event("end", "MESSAGE_COMPLETE", ""));
        events.forEach(source::capture); return events;
    }
    private static final class LegacyClient implements Consumer<JSONObject> {
        final List<String> messages = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        StringBuilder pending;
        public void accept(JSONObject event) {
            if (!seen.add(event.getString("eventId"))) return;
            if (pending == null) pending = new StringBuilder();
            pending.append(event.getJSONObject("data").getString("content"));
            if (!"MESSAGE_CHUNK".equals(event.getString("type"))) {
                messages.add(pending.toString()); pending = null;
            }
        }
    }
    @Test public void orderedChunksRemainImmediateAndCloseOneMessage() throws Exception {
        TeamStreamProjection projection = new TeamStreamProjection(new CoordinationStore(temporary.newFolder().toPath()));
        List<JSONObject> events = turn(projection); LegacyClient client = new LegacyClient();
        projection.project(events.get(0), client);
        assertEquals("好的，需要时随时", client.pending.toString()); assertTrue(client.messages.isEmpty());
        projection.project(events.get(1), client); projection.project(events.get(2), client);
        assertEquals(Collections.singletonList("好的，需要时随时找我。"), client.messages); assertNull(client.pending);
    }
    @Test public void durableCompletionOvertakesChunksAndLateDuplicateCannotReopen() throws Exception {
        TeamStreamProjection projection = new TeamStreamProjection(new CoordinationStore(temporary.newFolder().toPath()));
        List<JSONObject> events = turn(projection); LegacyClient client = new LegacyClient();
        projection.project(events.get(0), client); projection.project(events.get(2), client);
        projection.project(events.get(1), client); projection.project(events.get(2), client);
        assertEquals(Collections.singletonList("好的，需要时随时找我。"), client.messages); assertNull(client.pending);
    }
    @Test public void offlineChunksAreReconstructedByTerminalCheckpoint() throws Exception {
        TeamStreamProjection projection = new TeamStreamProjection(new CoordinationStore(temporary.newFolder().toPath()));
        List<JSONObject> events = turn(projection); LegacyClient client = new LegacyClient();
        projection.project(events.get(1), client); assertNull(client.pending);
        projection.project(events.get(2), client);
        assertEquals(Collections.singletonList("好的，需要时随时找我。"), client.messages); assertNull(client.pending);
    }
    @Test public void lostCallbackAcknowledgementAndRestartDoNotDuplicateText() throws Exception {
        CoordinationStore store = new CoordinationStore(temporary.newFolder().toPath());
        TeamStreamProjection projection = new TeamStreamProjection(store);
        List<JSONObject> events = turn(projection); LegacyClient client = new LegacyClient();
        try { projection.project(events.get(2), e -> { client.accept(e); throw new RuntimeException("lost ACK"); }); fail(); }
        catch (RuntimeException expected) { assertEquals("lost ACK", expected.getMessage()); }
        projection = new TeamStreamProjection(store); projection.project(events.get(2), client);
        projection = new TeamStreamProjection(store); projection.project(events.get(0), client); projection.project(events.get(2), client);
        assertEquals(Collections.singletonList("好的，需要时随时找我。"), client.messages); assertNull(client.pending);
    }
    @Test public void terminalSendFailureRetriesOnlyTheEnd() throws Exception {
        TeamStreamProjection projection = new TeamStreamProjection(new CoordinationStore(temporary.newFolder().toPath()));
        List<JSONObject> events = turn(projection); LegacyClient client = new LegacyClient();
        try { projection.project(events.get(2), e -> {
            if ("MESSAGE_COMPLETE".equals(e.getString("type"))) throw new RuntimeException("offline");
            client.accept(e);
        }); fail(); } catch (RuntimeException expected) { assertEquals("offline", expected.getMessage()); }
        projection.project(events.get(2), client);
        assertEquals(Collections.singletonList("好的，需要时随时找我。"), client.messages); assertNull(client.pending);
    }
    @Test public void lostIncrementalAckIsRetriedWithSameIdBeforeLargerCompletionSuffix() throws Exception {
        CoordinationStore store = new CoordinationStore(temporary.newFolder().toPath());
        TeamStreamProjection projection = new TeamStreamProjection(store);
        List<JSONObject> events = turn(projection); LegacyClient client = new LegacyClient();
        try { projection.project(events.get(0), e -> { client.accept(e); throw new RuntimeException("lost chunk ACK"); }); fail(); }
        catch (RuntimeException expected) { assertEquals("lost chunk ACK", expected.getMessage()); }
        projection = new TeamStreamProjection(store);
        projection.project(events.get(2), client);
        assertEquals(Collections.singletonList("好的，需要时随时找我。"), client.messages); assertNull(client.pending);
    }
    @Test public void separateTurnsRetainIndependentIdentity() throws Exception {
        TeamStreamProjection projection = new TeamStreamProjection(new CoordinationStore(temporary.newFolder().toPath()));
        List<JSONObject> first = turn(projection); List<JSONObject> second = turn(projection);
        assertNotEquals(first.get(0).getJSONObject("data").getString("projectionStreamId"),
                second.get(0).getJSONObject("data").getString("projectionStreamId"));
        LegacyClient client = new LegacyClient(); first.forEach(e -> projection.project(e, client));
        // MolaChat deduplicates IDs; genuine turns have different source event IDs.
        for (JSONObject e : second) e.put("eventId", "second-" + e.getString("eventId"));
        second.forEach(e -> projection.project(e, client));
        assertEquals(2, client.messages.size()); assertNull(client.pending);
    }
    @Test public void errorsRepairMissingTextAndCloseOnce() throws Exception {
        TeamStreamProjection projection = new TeamStreamProjection(new CoordinationStore(temporary.newFolder().toPath()));
        JSONObject text = event("text", "MESSAGE_CHUNK", "已有内容");
        JSONObject error = event("error", "MESSAGE_ERROR", "错误提示");
        projection.capture(text); projection.capture(error); LegacyClient client = new LegacyClient();
        projection.project(error, client); projection.project(text, client); projection.project(error, client);
        assertEquals(Collections.singletonList("已有内容错误提示"), client.messages); assertNull(client.pending);
    }
    @Test public void nextTurnWaitsForPreviousCompletionInsteadOfAppendingToItsStream() throws Exception {
        TeamStreamProjection projection = new TeamStreamProjection(new CoordinationStore(temporary.newFolder().toPath()));
        List<JSONObject> first = turn(projection), second = turn(projection);
        for (JSONObject e : second) e.put("eventId", "next-" + e.getString("eventId"));
        LegacyClient client = new LegacyClient(); projection.project(first.get(0), client);
        try { projection.project(second.get(0), client); fail(); }
        catch (CoordinationException expected) { assertEquals("STREAM_WAITING", expected.getCode()); }
        projection.project(first.get(2), client); projection.project(second.get(2), client);
        assertEquals(Arrays.asList("好的，需要时随时找我。", "好的，需要时随时找我。"), client.messages);
        assertNull(client.pending);
    }
    @Test public void newTurnRetriesPreviousUnacknowledgedEndBeforeOpening() throws Exception {
        TeamStreamProjection projection = new TeamStreamProjection(new CoordinationStore(temporary.newFolder().toPath()));
        List<JSONObject> first = turn(projection), second = turn(projection);
        for (JSONObject e : second) e.put("eventId", "next-" + e.getString("eventId"));
        LegacyClient client = new LegacyClient();
        try { projection.project(first.get(2), e -> {
            if ("MESSAGE_COMPLETE".equals(e.getString("type"))) throw new RuntimeException("end offline");
            client.accept(e);
        }); fail(); } catch (RuntimeException expected) { assertEquals("end offline", expected.getMessage()); }
        projection.project(second.get(0), client); projection.project(second.get(2), client);
        assertEquals(Arrays.asList("好的，需要时随时找我。", "好的，需要时随时找我。"), client.messages);
        assertNull(client.pending);
    }
}
