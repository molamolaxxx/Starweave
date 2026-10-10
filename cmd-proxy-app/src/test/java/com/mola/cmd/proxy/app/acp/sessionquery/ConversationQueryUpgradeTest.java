package com.mola.cmd.proxy.app.acp.sessionquery;

import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class ConversationQueryUpgradeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path root;
    private ConversationQueryService query;
    @Before public void setup() throws Exception {
        root = temporary.newFolder("session").toPath();
        query = new ConversationQueryService(root, root.resolve("One"), "One", () -> null, JsonArray::new);
    }
    @After public void close() { query.close(); }
    private static JsonObject object(String... values) {
        JsonObject o = new JsonObject(); for (int i = 0; i < values.length; i += 2) o.addProperty(values[i], values[i + 1]); return o;
    }
    private static JsonObject row(String role, String id, String text) { return object("role", role, "messageId", id, "content", text); }
    private Path write(String session, JsonObject... rows) throws Exception {
        Path file = root.resolve("One/" + session + "/turn_0000.json"); Files.createDirectories(file.getParent());
        JsonArray array = new JsonArray(); for (JsonObject row : rows) array.add(row);
        Files.write(file, array.toString().getBytes(StandardCharsets.UTF_8)); return file;
    }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private JsonObject search(String keyword, String... roles) {
        JsonObject args = object("keyword", keyword);
        if (roles.length > 0) { JsonArray array = new JsonArray(); for (String role : roles) array.add(role); args.add("roles", array); }
        return json(query.search(args));
    }
    private static String ref(JsonObject search) { return search.getAsJsonArray("sessions").get(0).getAsJsonObject().get("session_ref").getAsString(); }
    private static JsonObject batch(JsonObject... requests) {
        JsonArray items = new JsonArray(); for (JsonObject request : requests) items.add(request);
        JsonObject args = new JsonObject(); args.add("items", items); return args;
    }
    private static void error(String code, Runnable action) {
        try { action.run(); fail("Expected " + code); }
        catch (ConversationQueryService.QueryException e) { assertEquals(code, e.code); }
    }

    @Test public void rolesFilterActualMatchesIncludingTitleAndSeparateToolFields() throws Exception {
        JsonObject tool = object("role", "TOOL", "messageId", "t", "toolCallId", "call", "toolName", "exec");
        tool.add("rawInput", object("text", "INPUT_ONLY KIRO_CLI")); tool.add("rawOutput", object("text", "OUTPUT_ONLY"));
        write("discussion", row("USER", "u", "Kiro 用户讨论"), row("ASSISTANT", "a", "另一个话题"));
        write("log", tool);
        assertEquals(1, search("kiro").getAsJsonArray("sessions").size());
        assertEquals(0, search("kiro", "assistant").getAsJsonArray("sessions").size());
        assertEquals(1, search("INPUT_ONLY", "tool_input").getAsJsonArray("sessions").size());
        assertEquals(0, search("INPUT_ONLY", "tool_output").getAsJsonArray("sessions").size());
        assertEquals(1, search("OUTPUT_ONLY", "tool_output").getAsJsonArray("sessions").size());
        JsonObject hit = search("kiro", "user").getAsJsonArray("sessions").get(0).getAsJsonObject();
        assertEquals("user", hit.getAsJsonArray("matches").get(0).getAsJsonObject().get("role").getAsString());
    }

    @Test public void binaryFieldsNeverMatchButTextAndOriginalInputsRemainComplete() throws Exception {
        JsonObject image = object("type", "image", "data", "BINARY_ONLY", "caption", "CAPTION_ONLY");
        JsonArray content = new JsonArray(); content.add(image);
        content.add(object("mimeType", "image/png", "data", "MIME_BINARY_ONLY"));
        content.add(object("mimeType", "application/octet-stream", "blob", "RESOURCE_BINARY_ONLY"));
        JsonObject output = object("text", "ordinary SOURCE_CODE_ONLY", "contentBase64", "ENCODED_ONLY"); output.add("content", content);
        JsonObject input = object("dataBase64", "INPUT_BINARY_ONLY", "script", "readable script");
        JsonObject tool = object("role", "TOOL", "messageId", "t", "toolCallId", "c", "toolName", "exec");
        tool.add("rawInput", input); tool.add("rawOutput", output);
        write("s", tool, row("USER", "u", "data:image/png;base64,URL_BINARY_ONLY followed by TEXT_ONLY"));
        for (String token : Arrays.asList("BINARY_ONLY", "ENCODED_ONLY", "INPUT_BINARY_ONLY", "URL_BINARY_ONLY", "MIME_BINARY_ONLY", "RESOURCE_BINARY_ONLY"))
            assertEquals(0, search(token, "user", "tool").getAsJsonArray("sessions").size());
        assertEquals(1, search("CAPTION_ONLY", "tool_output").getAsJsonArray("sessions").size());
        assertEquals(1, search("SOURCE_CODE_ONLY", "tool_output").getAsJsonArray("sessions").size());
        JsonObject found = search("TEXT_ONLY", "user");
        JsonObject read = json(query.read(object("session_ref", ref(found))));
        assertEquals(input, read.getAsJsonArray("messages").get(0).getAsJsonObject().get("input"));
        String plain = String.join("", Collections.nCopies(1000, "long code text ")) + "LONG_TEXT_ONLY";
        write("long", row("USER", "l", plain));
        assertEquals(1, search("LONG_TEXT_ONLY").getAsJsonArray("sessions").size());
    }

    @Test public void pagesFiftyFromStableSnapshotWithoutReadingChangedFilesAgain() throws Exception {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 55; i++) files.add(write("s" + i, row("USER", "u" + i, "needle " + i)));
        JsonObject first = search("needle"); assertEquals(50, first.getAsJsonArray("sessions").size());
        assertEquals(55, first.get("total_sessions").getAsInt());
        String cursor = first.get("next_cursor").getAsString();
        for (Path file : files) Files.write(file, "broken JSON".getBytes(StandardCharsets.UTF_8));
        JsonObject second = json(query.search(object("keyword", "needle", "cursor", cursor)));
        assertEquals(5, second.getAsJsonArray("sessions").size()); assertTrue(second.get("next_cursor").isJsonNull());
        Set<String> refs = new HashSet<>();
        for (JsonElement hit : first.getAsJsonArray("sessions")) assertTrue(refs.add(hit.getAsJsonObject().get("session_ref").getAsString()));
        for (JsonElement hit : second.getAsJsonArray("sessions")) assertTrue(refs.add(hit.getAsJsonObject().get("session_ref").getAsString()));
        error("CURSOR_EXPIRED", () -> query.search(object("keyword", "changed", "cursor", cursor)));
        error("HISTORY_UNAVAILABLE", () -> query.search(object("keyword", "needle")));
        query.close(); error("CURSOR_EXPIRED", () -> query.search(object("keyword", "needle", "cursor", cursor)));
    }

    @Test public void snippetStaysNearHitAndSearchByteBudgetContinuesPagination() throws Exception {
        String text = "needle " + String.join("", Collections.nCopies(400, "中文内容🧵")) + "DISTANT_TAIL";
        for (int i = 0; i < 50; i++) write("s" + i, row("USER", "u" + i, text), row("ASSISTANT", "a" + i, text));
        JsonObject first = search("needle");
        assertTrue(first.toString().getBytes(StandardCharsets.UTF_8).length < 64 * 1024);
        assertFalse(first.get("next_cursor").isJsonNull());
        String snippet = first.getAsJsonArray("sessions").get(0).getAsJsonObject().getAsJsonArray("matches").get(0)
                .getAsJsonObject().get("snippet").getAsString();
        assertTrue(snippet.contains("needle")); assertFalse(snippet.contains("DISTANT_TAIL"));
        assertFalse(Character.isHighSurrogate(snippet.charAt(snippet.length() - 2)));
        int total = first.getAsJsonArray("sessions").size();
        while (!first.get("next_cursor").isJsonNull()) {
            first = json(query.search(object("keyword", "needle", "cursor", first.get("next_cursor").getAsString())));
            total += first.getAsJsonArray("sessions").size();
        }
        assertEquals(50, total);
    }

    @Test public void batchReadsExactWindowsReusesSessionAndIsolatesErrors() throws Exception {
        JsonObject[] rows = new JsonObject[15];
        for (int i = 0; i < rows.length; i++) rows[i] = row("USER", "m" + i, "needle " + i);
        write("s", rows); String ref = ref(search("needle"));
        JsonObject result = json(query.readContexts(batch(object("session_ref", ref, "message_id", "m7"),
                object("session_ref", "missing", "message_id", "m7"), object("session_ref", ref, "message_id", "m13"),
                object("session_ref", ref, "message_id", "absent"))));
        JsonArray contexts = result.getAsJsonArray("contexts"); assertEquals(4, contexts.size());
        JsonArray window = contexts.get(0).getAsJsonObject().getAsJsonArray("messages");
        assertEquals(6, window.size()); assertEquals("m5", window.get(0).getAsJsonObject().get("message_id").getAsString());
        assertEquals("m10", window.get(5).getAsJsonObject().get("message_id").getAsString());
        assertEquals("SESSION_NOT_FOUND", contexts.get(1).getAsJsonObject().getAsJsonObject("error").get("code").getAsString());
        assertEquals(4, contexts.get(2).getAsJsonObject().getAsJsonArray("messages").size());
        assertEquals("MESSAGE_NOT_FOUND", contexts.get(3).getAsJsonObject().getAsJsonObject("error").get("code").getAsString());
        String continuation = contexts.get(0).getAsJsonObject().get("after_cursor").getAsString();
        JsonArray after = json(query.read(object("session_ref", ref, "cursor", continuation))).getAsJsonArray("messages");
        assertEquals("m11", after.get(0).getAsJsonObject().get("message_id").getAsString());
    }

    @Test public void readsAllMessagesWithoutPagingAndOnlyShortensToolResults() throws Exception {
        JsonObject[] rows = new JsonObject[61];
        String longText = String.join("", Collections.nCopies(40000, "字"));
        for (int i = 0; i < 60; i++) rows[i] = row("USER", "m" + i, i == 0 ? longText : "needle " + i);
        JsonObject input = object("script", String.join("", Collections.nCopies(150000, "x")));
        rows[60] = object("role", "TOOL", "messageId", "t", "toolCallId", "c", "toolName", "exec");
        rows[60].add("rawInput", input);
        rows[60].add("rawOutput", object("text", String.join("", Collections.nCopies(8000, "y"))));
        write("s", rows); String ref = ref(search("needle"));
        JsonObject result = json(query.readHistory(object("session_ref", ref)));
        JsonArray messages = result.getAsJsonArray("messages");
        assertEquals(61, messages.size());
        assertEquals(longText, messages.get(0).getAsJsonObject().get("content").getAsString());
        for (int i = 0; i < 60; i++) assertEquals("m" + i, messages.get(i).getAsJsonObject().get("message_id").getAsString());
        JsonObject tool = messages.get(60).getAsJsonObject();
        assertEquals(input, tool.get("input")); assertTrue(tool.get("output_truncated").getAsBoolean());
        assertTrue(tool.get("output").getAsString().length() <= 2000);
        assertFalse(result.has("before_cursor")); assertFalse(result.has("after_cursor"));
        error("INVALID_ARGUMENT", () -> query.readHistory(object("session_ref", ref, "message_id", "m1")));
        error("INVALID_ARGUMENT", () -> query.readHistory(new JsonObject()));
        error("SESSION_NOT_FOUND", () -> query.readHistory(object("session_ref", "missing")));
    }

    @Test public void batchKeepsOversizedInputAndDefersRemainingItems() throws Exception {
        JsonObject input = object("script", String.join("", Collections.nCopies(150000, "x")));
        JsonObject tool = object("role", "TOOL", "messageId", "t", "toolCallId", "c", "toolName", "exec");
        tool.add("rawInput", input); tool.add("rawOutput", object("text", "needle"));
        write("s", tool); String ref = ref(search("needle", "tool_output"));
        JsonObject request = object("session_ref", ref, "message_id", "t");
        JsonObject result = json(query.readContexts(batch(request, request)));
        assertEquals(1, result.getAsJsonArray("contexts").size()); assertEquals(1, result.getAsJsonArray("remaining_items").size());
        assertEquals(input, result.getAsJsonArray("contexts").get(0).getAsJsonObject().getAsJsonArray("messages").get(0)
                .getAsJsonObject().get("input"));
        JsonObject next = new JsonObject(); next.add("items", result.get("remaining_items"));
        assertEquals(1, json(query.readContexts(next)).getAsJsonArray("contexts").size());
    }

    @Test public void validatesRolesLimitsAndBatchRequests() {
        error("INVALID_ARGUMENT", () -> query.search(object("roles", "user")));
        JsonObject invalid = new JsonObject(); invalid.add("roles", new JsonArray());
        error("INVALID_ARGUMENT", () -> query.search(invalid));
        JsonArray roles = new JsonArray(); roles.add("system"); invalid.add("roles", roles);
        error("INVALID_ARGUMENT", () -> query.search(invalid));
        for (int n : new int[]{0, 51}) { JsonObject args = new JsonObject(); args.addProperty("limit", n); error("INVALID_ARGUMENT", () -> query.search(args)); }
        error("INVALID_ARGUMENT", () -> query.readContexts(batch()));
        error("INVALID_ARGUMENT", () -> query.readContexts(batch(object("session_ref", "s"))));
        JsonObject[] tooMany = new JsonObject[11]; Arrays.fill(tooMany, object("session_ref", "s", "message_id", "m"));
        error("INVALID_ARGUMENT", () -> query.readContexts(batch(tooMany)));
    }

    @Test public void evictedSearchSnapshotExpiresAndBatchToolCopiesDoNotMatch() throws Exception {
        write("one", row("USER", "u1", "needle")); write("two", row("USER", "u2", "needle"));
        JsonObject args = object("keyword", "needle"); args.addProperty("limit", 1);
        JsonObject first = json(query.search(args));
        for (int i = 0; i < 16; i++) query.search(args);
        args.add("cursor", first.get("next_cursor"));
        error("CURSOR_EXPIRED", () -> query.search(args));
        JsonObject tool = object("role", "TOOL", "messageId", "t", "toolCallId", "c", "toolName", "mcp__harness__read_session_contexts");
        tool.add("rawOutput", object("text", "COPIED_ONLY")); write("copy", tool);
        assertEquals(0, search("COPIED_ONLY", "tool").getAsJsonArray("sessions").size());
    }
}
