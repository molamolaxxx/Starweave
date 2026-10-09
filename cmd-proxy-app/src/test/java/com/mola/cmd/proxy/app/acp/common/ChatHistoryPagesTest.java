package com.mola.cmd.proxy.app.acp.common;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.Assert.*;

public class ChatHistoryPagesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void boundsToolRowsInExistingCachedPagesWithoutRebuildingOrChangingCursor() throws Exception {
        Path root = temp.newFolder().toPath();
        ChatHistoryPages pages = new ChatHistoryPages(root, "cached-tools");
        JSONObject page = pages.page("v1", () -> snapshot(2), null, 1);
        String cursor = page.getString("olderCursor");
        // Replace only the last cached row with the old format's oversized tool output.
        JSONObject tool = new JSONObject(); tool.put("messageId", "message-1");
        tool.put("role", "TOOL"); tool.put("rawOutput", String.join("", java.util.Collections.nCopies(100000, "output")));
        JSONObject metadata = com.alibaba.fastjson.JSON.parseObject(new String(Files.readAllBytes(root.resolve("index.json")), java.nio.charset.StandardCharsets.UTF_8));
        long offset = metadata.getJSONArray("offsets").getLongValue(1);
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(root.resolve("messages.dat").toFile(), "rw")) {
            file.seek(offset); byte[] bytes = tool.toJSONString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            file.writeInt(bytes.length); file.write(bytes);
        }
        JSONObject bounded = new ChatHistoryPages(root, "cached-tools").page("v1", () -> { throw new AssertionError("cache must be reused"); }, null, 1);
        assertTrue(bounded.toJSONString().length() < 20000);
        assertEquals(cursor, bounded.getString("olderCursor"));
        assertTrue(bounded.getJSONArray("items").getJSONObject(0).getBooleanValue("outputTruncated"));
        assertEquals("message-0", pages.page("v1", () -> snapshot(2), cursor, 1).getJSONArray("items").getJSONObject(0).getString("messageId"));
    }
    private JSONObject snapshot(int count) {
        JSONArray items = new JSONArray();
        for (int i = 0; i < count; i++) {
            JSONObject item = new JSONObject(); item.put("messageId", "message-" + i);
            item.put("content", "中文消息 " + i); items.add(item);
        }
        JSONObject result = new JSONObject(); result.put("items", items); return result;
    }
    @Test public void readsNewestFirstThenOlderPagesAndReusesIndexAcrossRestart() throws Exception {
        Path root = temp.newFolder().toPath(); AtomicInteger builds = new AtomicInteger();
        Supplier<JSONObject> producer = () -> { builds.incrementAndGet(); return snapshot(120); };
        ChatHistoryPages pages = new ChatHistoryPages(root, "environment/session");
        JSONObject latest = pages.page("v1", producer, null, 50);
        assertEquals(50, latest.getJSONArray("items").size());
        assertEquals("message-70", latest.getJSONArray("items").getJSONObject(0).getString("messageId"));
        JSONObject older = pages.page("v1", producer, latest.getString("olderCursor"), 50);
        assertEquals("message-20", older.getJSONArray("items").getJSONObject(0).getString("messageId"));
        JSONObject first = new ChatHistoryPages(root, "environment/session")
                .page("v1", producer, older.getString("olderCursor"), 50);
        assertEquals(20, first.getJSONArray("items").size()); assertFalse(first.getBooleanValue("hasMore"));
        assertEquals(1, builds.get());
        // 后续追加消息不会把旧游标往后推。
        JSONObject afterAppend = pages.page("v2", () -> snapshot(125), latest.getString("olderCursor"), 50);
        assertEquals("message-69", afterAppend.getJSONArray("items").getJSONObject(49).getString("messageId"));
    }
    @Test public void rejectsCrossSessionCursorsAndOutOfRangeLimits() throws Exception {
        ChatHistoryPages a = new ChatHistoryPages(temp.newFolder().toPath(), "a");
        String cursor = a.page("1", () -> snapshot(120), null, 50).getString("olderCursor");
        ChatHistoryPages b = new ChatHistoryPages(temp.newFolder().toPath(), "b");
        try { b.page("1", () -> snapshot(120), cursor, 50); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("当前会话")); }
        try { a.page("1", () -> snapshot(120), null, 101); fail(); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void rebuildsOnlyDerivedFilesAfterCorruption() throws Exception {
        Path root = temp.newFolder().toPath(); ChatHistoryPages pages = new ChatHistoryPages(root, "a");
        pages.page("1", () -> snapshot(5), null, 50);
        Files.write(root.resolve("messages.dat"), new byte[0]);
        try { pages.page("1", () -> snapshot(5), null, 50); fail(); }
        catch (IllegalStateException expected) { }
        assertEquals(5, pages.page("1", () -> snapshot(5), null, 50).getJSONArray("items").size());
    }
}
