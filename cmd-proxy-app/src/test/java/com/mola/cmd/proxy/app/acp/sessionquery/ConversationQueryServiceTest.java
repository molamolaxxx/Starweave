package com.mola.cmd.proxy.app.acp.sessionquery;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ConversationQueryServiceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path root;
    private final List<ConversationQueryService> services = new ArrayList<>();

    @Before public void setup() throws Exception { root = temporary.newFolder("session").toPath(); }
    @After public void close() { services.forEach(ConversationQueryService::close); }

    private ConversationHistoryManager history(String name) {
        return new ConversationHistoryManager(AcpClientIdentity.main("g-" + name, name, name), root);
    }
    private ConversationQueryService service(ConversationHistoryManager manager, AtomicReference<String> current) {
        ConversationQueryService service = new ConversationQueryService(root, manager.getQueryNamespace(),
                manager.getQueryAgentName(), current::get, () -> manager.queryCurrentHistory(current.get()));
        services.add(service); return service;
    }
    private static JsonObject args(String... values) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < values.length; i += 2) result.addProperty(values[i], values[i + 1]);
        return result;
    }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static JsonObject roles(JsonObject args, String... roles) {
        JsonArray array = new JsonArray(); for (String role : roles) array.add(role);
        args.add("roles", array); return args;
    }
    private static JsonObject limit(JsonObject args, int limit) { args.addProperty("limit", limit); return args; }
    private static String ref(JsonObject result) {
        return result.getAsJsonArray("sessions").get(0).getAsJsonObject().get("session_ref").getAsString();
    }
    private static String repeat(String text, int count) { return String.join("", Collections.nCopies(count, text)); }
    private static void error(String code, ThrowingRunnable action) throws Exception {
        try { action.run(); fail("Expected " + code); }
        catch (ConversationQueryService.QueryException expected) { assertEquals(code, expected.code); }
    }
    private interface ThrowingRunnable { void run() throws Exception; }

    @Test public void searchesSelfOthersAndKeywordAnchorsWithoutLeakingPaths() throws Exception {
        ConversationHistoryManager one = history("One"), two = history("Two");
        one.addUserMessage("自己的反向隧道讨论"); one.flushTurn("old-one");
        two.addUserMessage("他人的反向隧道讨论"); two.addAssistantMessage("Netty 实现细节"); two.flushTurn("old-two");
        ConversationQueryService query = service(one, new AtomicReference<>());
        JsonObject self = json(query.search(args("keyword", "反向隧道")));
        assertEquals(1, self.getAsJsonArray("sessions").size());
        JsonObject all = json(query.search(args("keyword", "反向隧道", "agent", "all")));
        assertEquals(2, all.getAsJsonArray("sessions").size());
        JsonObject other = json(query.search(args("agent", "Two", "keyword", "Netty")));
        JsonObject hit = other.getAsJsonArray("sessions").get(0).getAsJsonObject();
        String id = hit.getAsJsonArray("matches").get(0).getAsJsonObject().get("message_id").getAsString();
        JsonObject read = json(query.read(args("session_ref", ref(other), "message_id", id)));
        assertEquals(2, read.getAsJsonArray("messages").size());
        assertFalse(all.toString().contains(root.toString()));
        assertFalse(read.has("storage_path"));
        error("AGENT_NOT_FOUND", () -> query.search(args("agent", "missing")));
    }

    @Test public void unownedLegacyHistoryDoesNotBreakSearchOrRead() throws Exception {
        Path legacy = Files.createDirectories(root.resolve("legacy-session"));
        Files.write(legacy.resolve("turn_1.json"), "[]".getBytes(StandardCharsets.UTF_8));
        ConversationHistoryManager manager = history("One");
        manager.addUserMessage("之痕"); manager.flushTurn("owned-session");
        ConversationQueryService query = service(manager, new AtomicReference<>());
        for (JsonObject arguments : Arrays.asList(args("agent", "all"),
                args("agent", "all", "keyword", "之痕"), args("keyword", "之痕"))) {
            JsonObject result = json(query.search(arguments));
            assertEquals(1, result.getAsJsonArray("sessions").size());
            JsonObject read = json(query.read(args("session_ref", ref(result))));
            assertEquals("之痕", read.getAsJsonArray("messages").get(0)
                    .getAsJsonObject().get("content").getAsString());
        }
        assertTrue(Files.exists(legacy.resolve("turn_1.json")));
    }

    @Test public void completeLargeToolInputAndMergedOutputPreviewDoNotChangeRawHistory() throws Exception {
        ConversationHistoryManager manager = history("One");
        JsonObject input = args("script", repeat("输入🧵", 15000));
        JsonObject output = args("text", "HEAD" + repeat("结果", 20000) + "TAIL");
        manager.addToolMessage("call-1", "exec_command", "running", input, null);
        manager.addToolMessage("call-1", null, "completed", null, output);
        manager.addAssistantMessage("done"); manager.flushTurn("s1");
        ConversationQueryService query = service(manager, new AtomicReference<>());
        JsonObject search = json(query.search(args()));
        JsonObject read = json(query.read(args("session_ref", ref(search))));
        JsonArray messages = read.getAsJsonArray("messages");
        // Latest page may contain only the final assistant: use the older page for the huge tool.
        if (!read.get("before_cursor").isJsonNull()) {
            read = json(query.read(args("session_ref", ref(search), "cursor", read.get("before_cursor").getAsString())));
            messages = read.getAsJsonArray("messages");
        }
        JsonObject tool = messages.get(0).getAsJsonObject();
        assertEquals(input, tool.getAsJsonObject("input"));
        assertEquals("exec_command", tool.get("tool_name").getAsString());
        assertEquals("completed", tool.get("status").getAsString());
        assertTrue(tool.get("output_truncated").getAsBoolean());
        assertTrue(tool.get("output").getAsString().contains("HEAD"));
        assertTrue(tool.get("output").getAsString().contains("TAIL"));
        assertTrue(tool.get("output").getAsString().length() <= 2000);
        assertEquals(output, manager.getFullHistory("s1").get(1).getRawOutput());
        JsonObject anchored = json(query.read(args("session_ref", ref(search), "message_id", tool.get("message_id").getAsString())));
        assertEquals(input, anchored.getAsJsonArray("messages").get(0).getAsJsonObject().get("input"));
    }

    @Test public void omitsBinaryOutputButPreservesBinaryInput() throws Exception {
        ConversationHistoryManager manager = history("One");
        JsonObject input = args("data", repeat("abc", 10000));
        JsonObject image = args("type", "image", "data", repeat("xyz", 10000));
        JsonArray content = new JsonArray(); content.add(image);
        JsonObject output = new JsonObject(); output.add("content", content);
        manager.addToolMessage("c", "image", "completed", input, output); manager.flushTurn("s");
        ConversationQueryService query = service(manager, new AtomicReference<>());
        JsonObject tool = json(query.read(args("session_ref", ref(json(query.search(args()))))))
                .getAsJsonArray("messages").get(0).getAsJsonObject();
        assertEquals(input, tool.get("input"));
        assertFalse(tool.get("output").getAsString().contains("xyz"));
        assertTrue(tool.get("output_truncated").getAsBoolean());
    }

    @Test public void searchFindsMiddleOfFullToolOutputEvenWhenReadPreviewOmitsIt() throws Exception {
        ConversationHistoryManager manager = history("One");
        manager.addToolMessage("c", "exec", "completed", args("cmd", "test"),
                args("text", repeat("a", 10000) + "UNIQUE_MIDDLE" + repeat("b", 10000)));
        manager.flushTurn("s");
        ConversationQueryService query = service(manager, new AtomicReference<>());
        JsonObject search = json(query.search(roles(args("keyword", "UNIQUE_MIDDLE"), "tool_output")));
        assertEquals(1, search.getAsJsonArray("sessions").size());
        assertTrue(search.toString().contains("UNIQUE_MIDDLE"));
    }

    @Test public void parallelSearchesShareFourScannersAndKeepStableOrder() throws Exception {
        CountDownLatch entered = new CountDownLatch(4), release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
        for (int i = 0; i < 8; i++) {
            final int index = i;
            ConversationQueryService live = new ConversationQueryService(root, root.resolve("Agent" + i),
                    "Agent" + i, () -> "live", () -> {
                int count = active.incrementAndGet();
                maximum.accumulateAndGet(count, Math::max);
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("scan gate timeout");
                    JsonArray messages = new JsonArray();
                    messages.add(args("role", "USER", "content", "needle " + index, "messageId", "m" + index));
                    return messages;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(e);
                } finally { active.decrementAndGet(); }
            });
            services.add(live);
        }
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = callers.submit(() -> services.get(0).search(args("agent", "all", "keyword", "needle")));
            assertTrue("Four different sessions should scan concurrently", entered.await(5, TimeUnit.SECONDS));
            Future<String> second = callers.submit(() -> services.get(1).search(args("agent", "all", "keyword", "needle")));
            release.countDown();
            JsonArray a = json(first.get(10, TimeUnit.SECONDS)).getAsJsonArray("sessions");
            JsonArray b = json(second.get(10, TimeUnit.SECONDS)).getAsJsonArray("sessions");
            assertEquals(8, a.size()); assertEquals(a, b);
            assertEquals(4, maximum.get()); assertEquals(0, active.get());
            for (int i = 1; i < a.size(); i++) assertTrue(a.get(i - 1).getAsJsonObject()
                    .get("session_ref").getAsString().compareTo(a.get(i).getAsJsonObject()
                            .get("session_ref").getAsString()) < 0);
        } finally { release.countDown(); callers.shutdownNow(); }
    }

    @Test public void workerFailurePreservesBusinessErrorAndLaterQueriesWork() throws Exception {
        ConversationHistoryManager manager = history("One");
        AtomicInteger attempts = new AtomicInteger();
        ConversationQueryService query = new ConversationQueryService(root, manager.getQueryNamespace(),
                "One", () -> "live", () -> {
            if (attempts.getAndIncrement() == 0)
                throw ConversationQueryService.failure("HISTORY_UNAVAILABLE", "temporarily unavailable");
            JsonArray messages = new JsonArray(); messages.add(args("role", "USER", "content", "recovered"));
            return messages;
        });
        services.add(query);
        error("HISTORY_UNAVAILABLE", () -> query.search(args()));
        assertEquals(1, json(query.search(args("keyword", "recovered"))).getAsJsonArray("sessions").size());
    }

    @Test public void defaultsToSevenDaysAndCanSearchAllOrAnotherRange() throws Exception {
        ConversationHistoryManager manager = history("One");
        manager.addUserMessage("recent needle"); manager.flushTurn("recent");
        manager.addUserMessage("older needle"); manager.flushTurn("older");
        Path oldTurn;
        try (java.util.stream.Stream<Path> files = Files.list(manager.getQueryNamespace().resolve("older"))) {
            oldTurn = files.filter(p -> p.getFileName().toString().startsWith("turn_")).findFirst().get();
        }
        Files.setLastModifiedTime(oldTurn, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)));
        ConversationQueryService query = service(manager, new AtomicReference<>());
        assertEquals(1, json(query.search(args("keyword", "needle"))).getAsJsonArray("sessions").size());
        JsonObject all = args("keyword", "needle"); all.addProperty("days", 0);
        assertEquals(2, json(query.search(all)).getAsJsonArray("sessions").size());
        all.addProperty("days", 9);
        assertEquals(2, json(query.search(all)).getAsJsonArray("sessions").size());
        // The filter precedes JSON parsing, so an excluded old record cannot break the search.
        Files.write(oldTurn, "broken JSON".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(oldTurn, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)));
        assertEquals(1, json(query.search(args())).getAsJsonArray("sessions").size());
        error("HISTORY_UNAVAILABLE", () -> query.search(all));
        for (JsonElement invalid : Arrays.asList(new JsonPrimitive(-1), new JsonPrimitive(1.5),
                new JsonPrimitive("7"), new JsonPrimitive(true), JsonNull.INSTANCE, new JsonPrimitive(2147483648L))) {
            JsonObject bad = args(); bad.add("days", invalid);
            error("INVALID_ARGUMENT", () -> query.search(bad));
        }
    }

    @Test public void recentLiveMessageIsNotExcludedByOldPersistedTimestamp() throws Exception {
        ConversationHistoryManager manager = history("One");
        manager.addUserMessage("old turn"); manager.flushTurn("active");
        Files.setLastModifiedTime(manager.getQueryNamespace().resolve("active/turn_0000.json"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)));
        manager.addUserMessage("new unflushed needle");
        ConversationQueryService query = service(manager, new AtomicReference<>("active"));
        assertEquals(1, json(query.search(args("keyword", "needle"))).getAsJsonArray("sessions").size());
    }

    @Test public void pagesBothDirectionsWithoutDuplicatesAndReadsAnchor() throws Exception {
        ConversationHistoryManager manager = history("One");
        for (int i = 0; i < 85; i++) manager.addAssistantMessage("message-" + i);
        manager.flushTurn("s");
        ConversationQueryService query = service(manager, new AtomicReference<>());
        String ref = ref(json(query.search(args())));
        JsonObject latest = json(query.read(args("session_ref", ref)));
        assertEquals(30, latest.getAsJsonArray("messages").size());
        assertTrue(latest.get("after_cursor").isJsonNull());
        Set<String> seen = new HashSet<>();
        JsonObject page = latest;
        do {
            for (JsonElement item : page.getAsJsonArray("messages"))
                assertTrue(seen.add(item.getAsJsonObject().get("message_id").getAsString()));
            if (page.get("before_cursor").isJsonNull()) break;
            page = json(query.read(args("session_ref", ref, "cursor", page.get("before_cursor").getAsString())));
        } while (true);
        assertEquals(85, seen.size());
        JsonObject forward = json(query.read(args("session_ref", ref, "cursor", page.get("after_cursor").getAsString())));
        assertEquals("message-25", forward.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString());
        String anchor = latest.getAsJsonArray("messages").get(10).getAsJsonObject().get("message_id").getAsString();
        assertTrue(json(query.read(args("session_ref", ref, "message_id", anchor))).toString().contains(anchor));
    }

    @Test public void longTextPagesReconstructExactlyIncludingSurrogatePairs() throws Exception {
        ConversationHistoryManager manager = history("One");
        String text = repeat("内容🧵", 14000);
        manager.addAssistantMessage(text); manager.flushTurn("s");
        ConversationQueryService query = service(manager, new AtomicReference<>());
        String ref = ref(json(query.search(args())));
        JsonObject page = json(query.read(args("session_ref", ref)));
        String reconstructed = "";
        do {
            String chunk = page.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            assertFalse(Character.isLowSurrogate(chunk.charAt(0)));
            assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)));
            reconstructed = chunk + reconstructed;
            if (page.get("before_cursor").isJsonNull()) break;
            page = json(query.read(args("session_ref", ref, "cursor", page.get("before_cursor").getAsString())));
        } while (true);
        assertEquals(text, reconstructed);
    }

    @Test public void liveOtherClientIsReadableAndOldSessionNeverGetsCurrentTurn() throws Exception {
        ConversationHistoryManager one = history("One"), two = history("Two");
        two.addAssistantMessage("OLD ONLY"); two.flushTurn("old");
        AtomicReference<String> sid = new AtomicReference<>("new");
        two.addUserMessage("LIVE ONLY"); two.appendUiAssistant("streaming");
        ConversationQueryService reader = service(one, new AtomicReference<>());
        service(two, sid);
        JsonObject old = json(reader.search(args("agent", "Two", "keyword", "OLD ONLY")));
        String oldRead = reader.read(args("session_ref", ref(old)));
        assertFalse(oldRead.contains("LIVE ONLY"));
        JsonObject live = json(reader.search(args("agent", "Two", "keyword", "LIVE ONLY")));
        assertEquals(1, live.getAsJsonArray("sessions").size());
        JsonObject read = json(reader.read(args("session_ref", ref(live))));
        assertTrue(read.getAsJsonArray("messages").get(1).getAsJsonObject().get("partial").getAsBoolean());
        two.flushTurn("new");
        assertEquals(ref(live), ref(json(reader.search(args("agent", "Two", "keyword", "LIVE ONLY")))));
    }

    @Test public void legacyRecordsTeamArchiveAndInternalScopeFiltering() throws Exception {
        ConversationHistoryManager manager = history("One");
        Path legacy = root.resolve("Legacy/s/turn_0000.json"); Files.createDirectories(legacy.getParent());
        Files.write(legacy, "[{\"role\":\"USER\",\"content\":\"legacy history\"}]".getBytes(StandardCharsets.UTF_8));
        ConversationHistoryManager team = new ConversationHistoryManager(AcpClientIdentity.team("t-m", "t-g", "team/t/m", "owner", "t", "m", "TeamAgent"), root);
        team.addUserMessage("archived history"); team.flushTurn("s");
        ConversationQueryService query = service(manager, new AtomicReference<>());
        JsonObject before = json(query.search(args("agent", "all", "keyword", "archived")));
        Path archive = root.resolveSibling("team-archive"); Files.createDirectories(archive);
        Files.move(root.resolve("team/t"), archive.resolve("t"));
        assertEquals(ref(before), ref(json(query.search(args("agent", "all", "keyword", "archived")))));
        assertTrue(query.read(args("session_ref", ref(before))).contains("archived history"));
        assertEquals(1, json(query.search(args("agent", "Legacy", "keyword", "legacy"))).getAsJsonArray("sessions").size());
        Path hidden = root.resolve("Internal/s/turn_0000.json"); Files.createDirectories(hidden.getParent());
        Files.write(hidden, "[{\"role\":\"USER\",\"content\":\"SECRET\"}]".getBytes(StandardCharsets.UTF_8));
        Files.write(hidden.getParent().getParent().resolve("query-owner.json"),
                "{\"agent\":\"Internal\",\"scope\":\"MEMORY\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, json(query.search(args("agent", "all", "keyword", "SECRET"))).getAsJsonArray("sessions").size());
    }

    @Test public void searchPaginationCursorBindingAndInvalidArguments() throws Exception {
        ConversationHistoryManager manager = history("One");
        for (int i = 0; i < 13; i++) { manager.addUserMessage("search " + i); manager.flushTurn("s" + i); }
        ConversationQueryService query = service(manager, new AtomicReference<>());
        JsonObject first = json(query.search(limit(args("keyword", "search"), 10)));
        assertEquals(10, first.getAsJsonArray("sessions").size());
        String cursor = first.get("next_cursor").getAsString();
        JsonObject second = json(query.search(limit(args("keyword", "search", "cursor", cursor), 10)));
        assertEquals(3, second.getAsJsonArray("sessions").size());
        assertTrue(second.get("next_cursor").isJsonNull());
        error("CURSOR_EXPIRED", () -> query.search(args("keyword", "other", "cursor", cursor)));
        JsonObject changedDays = args("keyword", "search", "cursor", cursor); changedDays.addProperty("days", 0);
        error("CURSOR_EXPIRED", () -> query.search(changedDays));
        error("CURSOR_EXPIRED", () -> query.search(args("cursor", cursor + "x")));
        error("INVALID_ARGUMENT", () -> query.read(args("session_ref", ref(first), "message_id", "m", "cursor", cursor)));
        error("MESSAGE_NOT_FOUND", () -> query.read(args("session_ref", ref(first), "message_id", "missing")));
        error("SESSION_NOT_FOUND", () -> query.read(args("session_ref", "../../etc")));
        JsonObject bad = args(); bad.addProperty("keyword", 123);
        error("INVALID_ARGUMENT", () -> query.search(bad));
        error("INVALID_ARGUMENT", () -> query.search(args("unknown", "x")));
    }

    @Test public void ignoresQueryCopiesAndAttachmentFilesAndInvalidatesChangedTurnCache() throws Exception {
        ConversationHistoryManager manager = history("One");
        manager.addToolMessage("query", "mcp__harness__read_session_history", "completed", args("session_ref", "ref"), args("text", "COPIED_HISTORY"));
        manager.flushTurn("s");
        ConversationQueryService query = service(manager, new AtomicReference<>());
        assertEquals(0, json(query.search(roles(args("keyword", "COPIED_HISTORY"), "tool"))).getAsJsonArray("sessions").size());
        Path attachment = root.resolve("starweave/robot/s/files/turn_0000.json"); Files.createDirectories(attachment.getParent());
        Files.write(attachment, "[{\"role\":\"USER\",\"content\":\"ATTACHMENT_ONLY\"}]".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, json(query.search(args("agent", "all", "keyword", "ATTACHMENT_ONLY"))).getAsJsonArray("sessions").size());
        Path turn = manager.getQueryNamespace().resolve("s/turn_0000.json");
        Files.write(turn, "[{\"role\":\"USER\",\"content\":\"REPLACED_HISTORY\"}]".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, json(query.search(args("keyword", "REPLACED_HISTORY"))).getAsJsonArray("sessions").size());
    }
}
