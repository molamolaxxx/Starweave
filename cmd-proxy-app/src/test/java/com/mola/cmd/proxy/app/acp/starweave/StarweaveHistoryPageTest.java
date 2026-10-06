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
}
