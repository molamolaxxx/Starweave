package com.mola.cmd.proxy.app.acp.common;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.Assert.*;

public class ToolOutputPreviewTest {
    private static String repeat(String value, int count) {
        return String.join("", Collections.nCopies(count, value));
    }

    @Test
    public void removesDuplicateBinaryAndKeepsTextAndOriginalObjects() {
        JSONObject image = JSON.parseObject("{\"type\":\"image\",\"mimeType\":\"image/png\"}");
        image.put("data", repeat("AAAA", 500000));
        JSONObject text = JSON.parseObject("{\"type\":\"text\",\"text\":\"screenshot captured\"}");
        JSONArray content = new JSONArray(); content.add(image); content.add(text);
        JSONObject output = new JSONObject(); output.put("content", content);
        JSONObject update = new JSONObject(); update.put("content", content); update.put("rawOutput", output);
        JSONObject payload = new JSONObject(); payload.put("toolCallId", "capture"); payload.put("status", "completed"); payload.put("update", update);

        JSONObject preview = ToolOutputPreview.payload(payload);
        assertTrue(preview.getBooleanValue("outputTruncated"));
        assertEquals(preview, ToolOutputPreview.payload(preview));
        assertTrue(preview.toJSONString().contains("screenshot captured"));
        assertFalse(preview.toJSONString().contains("AAAA"));
        assertEquals(2000000, image.getString("data").length());
        assertEquals("image", image.getString("type"));
        assertFalse(payload.containsKey("outputTruncated"));
    }

    @Test
    public void boundsLargeTextAndPreservesFailureAtTailAndIdentity() {
        JSONObject payload = new JSONObject();
        payload.put("toolCallId", "build"); payload.put("status", "failed");
        payload.put("rawOutput", "BEGIN\n" + repeat("中😀\n", 100000) + "\nBUILD FAILED");
        JSONObject preview = ToolOutputPreview.payload(payload);
        String output = preview.getString("rawOutput");
        assertTrue(output.startsWith("BEGIN\n")); assertTrue(output.endsWith("BUILD FAILED"));
        assertEquals("build", preview.getString("toolCallId"));
        assertEquals("failed", preview.getString("status"));
        assertTrue(preview.toJSONString().getBytes(StandardCharsets.UTF_8).length <= ToolOutputPreview.MAX_BYTES);
        assertTrue(preview.getBooleanValue("outputTruncated"));
    }

    @Test
    public void boundsManyNestedFieldsAndEscapedCharacters() {
        JSONObject payload = new JSONObject(); payload.put("toolCallId", "many");
        JSONObject update = new JSONObject();
        for (int i = 0; i < 2000; i++) update.put("field" + i, repeat("\u0001", 10000));
        payload.put("update", update);
        JSONObject preview = ToolOutputPreview.payload(payload);
        assertTrue(preview.toJSONString().getBytes(StandardCharsets.UTF_8).length <= ToolOutputPreview.MAX_BYTES);
        assertEquals("many", preview.getString("toolCallId"));
        assertTrue(preview.getBooleanValue("outputTruncated"));
        assertNotNull(preview.getJSONObject("update").getJSONObject("rawOutput").getString("preview"));
    }

    @Test
    public void leavesSmallToolsAndNonToolMessagesUnchanged() {
        JSONObject payload = JSON.parseObject("{\"toolCallId\":\"small\",\"update\":{\"rawOutput\":{\"exitCode\":0,\"text\":\"ok\"}}}");
        assertEquals(payload, ToolOutputPreview.payload(payload));
        JSONObject user = new JSONObject(); user.put("role", "USER"); user.put("content", repeat("hello", 10000));
        assertEquals(user, ToolOutputPreview.row(user));
    }

    @Test
    public void handlesAllToolProjectionShapes() {
        for (String shape : new String[]{"session", "team", "team-history", "legacy"}) {
            JSONObject row = new JSONObject(); row.put("messageId", "tool:one"); row.put("revision", 7);
            JSONObject payload = new JSONObject(); payload.put("rawOutput", repeat("output", 10000));
            if ("session".equals(shape)) { row.put("type", "TOOL_CALL_UPDATED"); row.put("payload", payload); }
            if ("team".equals(shape)) { row.put("type", "TOOL_CALL"); row.put("data", payload); }
            if ("team-history".equals(shape)) { row.put("eventType", "TOOL_CALL"); row.put("payload", payload); }
            if ("legacy".equals(shape)) { row.put("role", "TOOL"); row.put("rawOutput", payload.get("rawOutput")); }
            JSONObject preview = ToolOutputPreview.row(row);
            assertTrue(preview.toJSONString().length() < 20000);
            assertEquals("tool:one", preview.getString("messageId"));
            assertEquals(7, preview.getIntValue("revision"));
        }
    }
}
