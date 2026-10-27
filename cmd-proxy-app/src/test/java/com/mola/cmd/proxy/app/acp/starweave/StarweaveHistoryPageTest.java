package com.mola.cmd.proxy.app.acp.starweave;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

public class StarweaveHistoryPageTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private JSONObject payload(String key, String value) {
        JSONObject p = new JSONObject(); p.put(key, value); return p;
    }
    @Test public void countsVisibleMessagesAndKeepsToolsAndReplySegmentsComplete() throws Exception {
        Path root = temp.newFolder().toPath(); StarweaveSessionEventStore store = new StarweaveSessionEventStore(8, root);
        for (int turn = 0; turn < 30; turn++) {
            String id = "turn-" + turn;
            store.append("g", "s", id, 1, "USER_MESSAGE_ACCEPTED", payload("content", "问题" + turn));
            for (int delta = 0; delta < 100; delta++) store.append("g", "s", id, 1, "ASSISTANT_MESSAGE_DELTA", payload("text", "中"));
            JSONObject tool = payload("toolCallId", "tool-" + turn); tool.put("status", "running");
            store.append("g", "s", id, 1, "TOOL_CALL_UPDATED", tool);
            tool.put("status", "completed"); store.append("g", "s", id, 1, "TOOL_CALL_UPDATED", tool);
            store.append("g", "s", id, 1, "ASSISTANT_MESSAGE_DELTA", payload("text", "结论"));
            store.append("g", "s", id, 1, "TURN_COMPLETED", new JSONObject());
        }
        JSONObject page = store.historyPage("g", "s", null, 50);
        assertEquals(50, page.getJSONArray("events").size()); assertTrue(page.getBooleanValue("hasMore"));
        assertEquals(3150, page.getLongValue("replayAfter"));
        Set<String> ids = new HashSet<>(); int count = 0; String cursor = null;
        do {
            page = store.historyPage("g", "s", cursor, 50);
            JSONArray events = page.getJSONArray("events");
            for (int i = 0; i < events.size(); i++) {
                JSONObject event = events.getJSONObject(i); assertTrue(ids.add(event.getString("messageId"))); count++;
                if ("TOOL_CALL_UPDATED".equals(event.getString("type"))) assertEquals("completed", event.getJSONObject("payload").getString("status"));
                if ("ASSISTANT_MESSAGE_DELTA".equals(event.getString("type"))) assertTrue(event.getJSONObject("payload").getString("text").length() == 100 || "结论".equals(event.getJSONObject("payload").getString("text")));
            }
            cursor = page.getString("olderCursor");
        } while (page.getBooleanValue("hasMore"));
        assertEquals(120, count);
        assertEquals(50, new StarweaveSessionEventStore(8, root).historyPage("g", "s", null, 50).getJSONArray("events").size());
    }
    @Test public void keepsHistoryBeyondLegacySnapshotLimitAndStableBoundaryWithNewEvents() throws Exception {
        StarweaveSessionEventStore store = new StarweaveSessionEventStore(8, temp.newFolder().toPath());
        for (int i = 0; i < 10020; i++) store.append("g", "s", 1, "USER_MESSAGE_ACCEPTED", payload("content", "message" + i));
        JSONObject page = store.historyPage("g", "s", null, 50); String cursor = page.getString("olderCursor");
        store.append("g", "s", 1, "USER_MESSAGE_ACCEPTED", payload("content", "new"));
        JSONObject older = store.historyPage("g", "s", cursor, 50);
        assertEquals("message9969", older.getJSONArray("events").getJSONObject(49).getJSONObject("payload").getString("content"));
        int count = 50; Set<String> ids = new HashSet<>();
        while (page.getBooleanValue("hasMore")) {
            page = store.historyPage("g", "s", page.getString("olderCursor"), 100);
            for (Object row : page.getJSONArray("events")) assertTrue(ids.add(((JSONObject)row).getString("messageId")));
            count += page.getJSONArray("events").size();
        }
        assertEquals(10020, count);
    }
    @Test public void businessCardsKeepTextSegmentsAcrossPaginationAndRestart() throws Exception {
        for (String type : new String[]{"SCHEDULE_EVENT", "SUB_AGENT_EVENT", "TALK_TO_EVENT",
                "TASK_EVENT", "TASK_CREATED", "COMPACTION_COMPLETED", "LIFECYCLE_EVENT", "USER_MESSAGE_ACCEPTED"}) {
            Path root = temp.newFolder().toPath();
            StarweaveSessionEventStore store = new StarweaveSessionEventStore(8, root);
            store.append("g", "s", "turn", 1, "ASSISTANT_MESSAGE_DELTA", payload("text", "准备执行。"));
            JSONObject card = payload("eventId", "card"); card.put("source", "CHANNEL");
            store.append("g", "s", "turn", 1, type, card);
            store.append("g", "s", "turn", 1, "ASSISTANT_MESSAGE_DELTA", payload("text", "执行完成。"));
            if (type.startsWith("TASK_")) store.append("g", "s", "turn", 1, type, card);
            store.append("g", "s", "turn", 1, "SESSION_STATE_CHANGED", new JSONObject());
            store.append("g", "s", "turn", 1, "ASSISTANT_MESSAGE_DELTA", payload("text", "继续说明。"));
            store.append("g", "s", "turn", 1, "TURN_COMPLETED", new JSONObject());
            for (int restart = 0; restart < 2; restart++) {
                JSONObject latest = store.historyPage("g", "s", null, 2);
                JSONArray rows = latest.getJSONArray("events");
                assertEquals(type, 2, rows.size());
                assertEquals(type, rows.getJSONObject(0).getString("type"));
                assertEquals("执行完成。继续说明。", rows.getJSONObject(1).getJSONObject("payload").getString("text"));
                JSONObject older = store.historyPage("g", "s", latest.getString("olderCursor"), 2);
                JSONArray before = older.getJSONArray("events");
                assertEquals(type, 1, before.size());
                assertEquals("准备执行。", before.getJSONObject(0).getJSONObject("payload").getString("text"));
                assertNotEquals(before.getJSONObject(0).getString("messageId"), rows.getJSONObject(1).getString("messageId"));
                assertFalse(older.getBooleanValue("hasMore"));
                store = new StarweaveSessionEventStore(8, root);
            }
        }
    }

    @Test public void rebuildsLegacyCachedPagesWithoutChangingOriginalJournal() throws Exception {
        Path root = temp.newFolder().toPath();
        StarweaveSessionEventStore store = new StarweaveSessionEventStore(8, root);
        store.append("g", "s", "turn", 1, "ASSISTANT_MESSAGE_DELTA", payload("text", "before"));
        store.append("g", "s", "turn", 1, "SCHEDULE_EVENT", payload("detail", "created"));
        store.append("g", "s", "turn", 1, "ASSISTANT_MESSAGE_DELTA", payload("text", "after"));
        Path journal;
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.walk(root)) {
            journal = files.filter(path -> path.getFileName().toString().equals("events.jsonl")).findFirst().get();
        }
        byte[] original = java.nio.file.Files.readAllBytes(journal);
        String legacyVersion = "v1:" + java.nio.file.Files.size(journal) + ":"
                + java.nio.file.Files.getLastModifiedTime(journal).toMillis();
        new com.mola.cmd.proxy.app.acp.common.ChatHistoryPages(journal.resolveSibling("message-pages"), "g\ns")
                .page(legacyVersion, () -> {
                    JSONObject legacy = new JSONObject(); legacy.put("items", new JSONArray());
                    legacy.put("replayAfter", 3L); return legacy;
                }, null, 50);
        JSONObject page = new StarweaveSessionEventStore(8, root).historyPage("g", "s", null, 50);
        assertEquals(3, page.getJSONArray("events").size());
        assertEquals("before", page.getJSONArray("events").getJSONObject(0).getJSONObject("payload").getString("text"));
        assertEquals("after", page.getJSONArray("events").getJSONObject(2).getJSONObject("payload").getString("text"));
        assertArrayEquals(original, java.nio.file.Files.readAllBytes(journal));
    }

}
