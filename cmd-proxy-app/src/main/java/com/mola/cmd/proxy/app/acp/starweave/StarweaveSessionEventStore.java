package com.mola.cmd.proxy.app.acp.starweave;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;
import com.mola.cmd.proxy.app.acp.common.ChatHistoryPages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Durable Starweave event journal with a bounded in-memory tail for live delivery.
 * Identifiers are hashed before becoming path components so callers cannot escape
 * the server-owned storage root.
 */
public final class StarweaveSessionEventStore {
    private static final Logger log = LoggerFactory.getLogger(StarweaveSessionEventStore.class);
    private static final int DEFAULT_CAPACITY = 2000;
    private static final int MAX_SNAPSHOT_EVENTS = 10_000;
    private static final String JOURNAL_FILE = "events.jsonl";

    private final int capacity;
    private final Path root;
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final Map<String, Deque<StarweaveSessionEvent>> events = new ConcurrentHashMap<>();
    private final Map<String, Object> groupLocks = new ConcurrentHashMap<>();
    private final Map<String, Boolean> loadedGroups = new ConcurrentHashMap<>();
    private final Map<String, ChatHistoryPages> pages = new ConcurrentHashMap<>();

    public StarweaveSessionEventStore() {
        this(DEFAULT_CAPACITY, CmdProxyHome.resolve("starweave/events"));
    }

    /** In-memory constructor retained for focused unit tests. */
    StarweaveSessionEventStore(int capacity) {
        this(capacity, null);
    }

    StarweaveSessionEventStore(int capacity, Path root) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.root = root == null ? null : root.toAbsolutePath().normalize();
    }

    public StarweaveSessionEvent append(String groupId, String sessionId,
                                        long generation, String type,
                                        JSONObject payload) {
        return append(groupId, sessionId, null, generation, type, payload);
    }

    public StarweaveSessionEvent append(String groupId, String sessionId,
                                        String turnId, long generation, String type,
                                        JSONObject payload) {
        requireIdentifier(groupId, "groupId");
        requireIdentifier(sessionId, "sessionId");
        requireIdentifier(type, "type");
        Object lock = groupLocks.computeIfAbsent(groupId, ignored -> new Object());
        synchronized (lock) {
            ensureLoaded(groupId);
            long seq = sequences.computeIfAbsent(groupId,
                    ignored -> new AtomicLong()).incrementAndGet();
            StarweaveSessionEvent event = new StarweaveSessionEvent(
                    groupId, sessionId, turnId, generation, seq,
                    System.currentTimeMillis(), type, payload);
            persist(event);
            // 分页历史保留原始日志，不能再以实时尾部容量截断历史。
            addToRing(groupId, event);
            lock.notifyAll();
            return event;
        }
    }

    public List<StarweaveSessionEvent> snapshot(String groupId,
                                                String sessionId,
                                                long afterSeq) {
        return read(groupId, sessionId, afterSeq, null).getEvents();
    }

    /** Reads a bounded durable history for initial session rendering. */
    public ReadResult sessionSnapshot(String groupId, String sessionId) {
        requireIdentifier(groupId, "groupId");
        requireIdentifier(sessionId, "sessionId");
        if (root == null) return read(groupId, sessionId, 0L, null);
        Path journal = journalPath(groupId, sessionId);
        if (!Files.isRegularFile(journal)) return read(groupId, sessionId, 0L, null);
        Deque<StarweaveSessionEvent> bounded = new ArrayDeque<>();
        boolean truncated = false;
        try (BufferedReader reader = Files.newBufferedReader(journal, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    StarweaveSessionEvent event = StarweaveSessionEvent.fromJson(
                            JSON.parseObject(line));
                    if (!groupId.equals(event.getGroupId())
                            || !sessionId.equals(event.getSessionId())) continue;
                    bounded.addLast(event);
                    if (bounded.size() > MAX_SNAPSHOT_EVENTS) {
                        bounded.removeFirst();
                        truncated = true;
                    }
                } catch (RuntimeException malformed) {
                    log.warn("Ignoring malformed Starweave event record in {}", journal);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read Starweave session snapshot", e);
        }
        List<StarweaveSessionEvent> result = Collections.unmodifiableList(
                new ArrayList<>(bounded));
        long first = result.isEmpty() ? 1L : result.get(0).getEventSeq();
        long latest = result.isEmpty() ? 0L
                : result.get(result.size() - 1).getEventSeq();
        return new ReadResult(result, first, latest, false, truncated);
    }

    public JSONObject historyPage(String groupId, String sessionId, String before, int limit) {
        requireIdentifier(groupId, "groupId"); requireIdentifier(sessionId, "sessionId");
        Path journal = root == null ? null : journalPath(groupId, sessionId);
        try {
            String version = journal != null && Files.exists(journal)
                    ? "v1:" + Files.size(journal) + ":" + Files.getLastModifiedTime(journal).toMillis()
                    : "v1:" + sequences.getOrDefault(groupId, new AtomicLong()).get();
            String key = groupId + "\n" + sessionId;
            ChatHistoryPages index = pages.computeIfAbsent(key, ignored -> new ChatHistoryPages(
                    journal == null ? tempPageDirectory() : journal.resolveSibling("message-pages"), key));
            JSONObject result = index.page(version, () -> visibleSnapshot(groupId, sessionId, journal), before, limit);
            result.put("events", result.remove("items")); result.put("latestSeq", result.getLongValue("replayAfter"));
            return result;
        } catch (IOException e) { throw new IllegalStateException("会话历史读取失败", e); }
    }

    private Path tempPageDirectory() {
        try { Path directory = Files.createTempDirectory("starweave-page-test-"); directory.toFile().deleteOnExit(); return directory; }
        catch (IOException e) { throw new IllegalStateException(e); }
    }

    /** 与页面可见行对应：回复片段合并，工具更新覆盖原调用，隐藏内部事件。 */
    private JSONObject visibleSnapshot(String groupId, String sessionId, Path journal) {
        List<JSONObject> source = new ArrayList<>();
        if (journal != null && Files.isRegularFile(journal)) {
            try (BufferedReader reader = Files.newBufferedReader(journal, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try { JSONObject event = JSON.parseObject(line); if (event != null && groupId.equals(event.getString("groupId")) && sessionId.equals(event.getString("sessionId"))) source.add(event); }
                    catch (RuntimeException malformed) { /* 与原日志读取一致，跳过未完成记录。 */ }
                }
            } catch (IOException e) { throw new IllegalStateException("会话历史读取失败", e); }
        } else for (StarweaveSessionEvent event : snapshot(groupId, sessionId, 0)) source.add(event.toJson());
        List<JSONObject> rows = new ArrayList<>();
        Map<String, JSONObject> assistants = new java.util.HashMap<>(), tools = new java.util.HashMap<>();
        java.util.Set<String> taskIds = new java.util.HashSet<>(); long latest = 0;
        for (JSONObject event : source) {
            latest = Math.max(latest, event.getLongValue("eventSeq"));
            String type = event.getString("type"), turn = event.getString("turnId");
            if (type == null) continue;
            if (turn == null) turn = "seq-" + event.getLongValue("eventSeq");
            JSONObject payload = event.getJSONObject("payload"); if (payload == null) payload = new JSONObject();
            if ("TURN_COMPLETED".equals(type) || "SESSION_STATE_CHANGED".equals(type)) continue;
            if ("USER_MESSAGE_ACCEPTED".equals(type) && "HISTORY".equals(payload.getString("source")) && String.valueOf(payload.getString("content")).trim().startsWith("[Starweave Task]\n")) continue;
            if ((type.startsWith("TASK_") || "STARWEAVE_TASK".equals(payload.getString("cardType")) || String.valueOf(payload.getString("eventType")).startsWith("TASK_")) && payload.getString("eventId") != null && !taskIds.add(payload.getString("eventId"))) continue;
            if ("ASSISTANT_MESSAGE_DELTA".equals(type)) {
                JSONObject row = assistants.get(turn);
                if (row == null) {
                    row = JSON.parseObject(event.toJSONString()); row.put("messageId", "assistant:" + event.getLongValue("eventSeq"));
                    row.put("payload", new JSONObject(payload)); assistants.put(turn, row); rows.add(row);
                } else row.getJSONObject("payload").put("text", row.getJSONObject("payload").getString("text") + (payload.getString("text") == null ? "" : payload.getString("text")));
                if (row.getJSONObject("payload").getString("text") == null) row.getJSONObject("payload").put("text", "");
                continue;
            }
            if ("TOOL_CALL_UPDATED".equals(type)) {
                assistants.remove(turn); String tool = payload.getString("toolCallId");
                if (tool == null) tool = "seq-" + event.getLongValue("eventSeq");
                JSONObject row = tools.get(tool);
                if (row == null) { row = JSON.parseObject(event.toJSONString()); row.put("messageId", "tool:" + tool); tools.put(tool, row); rows.add(row); }
                else row.put("payload", payload);
                row.put("revision", event.getLongValue("eventSeq")); continue;
            }
            JSONObject row = JSON.parseObject(event.toJSONString()); row.put("messageId", "event:" + event.getLongValue("eventSeq")); rows.add(row);
        }
        rows.removeIf(row -> "ASSISTANT_MESSAGE_DELTA".equals(row.getString("type")) && row.getJSONObject("payload").getString("text").trim().isEmpty());
        JSONObject result = new JSONObject(); result.put("items", rows); result.put("replayAfter", latest); return result;
    }

    public ReadResult read(String groupId, String sessionId, long afterSeq,
                           Long generation) {
        requireIdentifier(groupId, "groupId");
        Object lock = groupLocks.computeIfAbsent(groupId, ignored -> new Object());
        synchronized (lock) {
            ensureLoaded(groupId);
            Deque<StarweaveSessionEvent> ring = events.get(groupId);
            long latest = sequences.getOrDefault(groupId, new AtomicLong()).get();
            if (ring == null || ring.isEmpty()) {
                return new ReadResult(Collections.emptyList(), latest + 1, latest, false, false);
            }
            long first = ring.getFirst().getEventSeq();
            boolean resync = afterSeq > 0 && afterSeq < first - 1
                    && hasEvictedMatchingEvent(groupId, sessionId, afterSeq,
                    first, generation);
            List<StarweaveSessionEvent> result = new ArrayList<>();
            for (StarweaveSessionEvent event : ring) {
                if (event.getEventSeq() <= afterSeq) continue;
                if (sessionId != null && !sessionId.equals(event.getSessionId())) continue;
                if (generation != null && generation.longValue() != event.getGeneration()) continue;
                result.add(event);
            }
            return new ReadResult(Collections.unmodifiableList(result), first, latest, resync, false);
        }
    }

    private boolean hasEvictedMatchingEvent(String groupId, String sessionId,
                                            long afterSeq, long firstRingSeq,
                                            Long generation) {
        if (sessionId == null || root == null) return true;
        ReadResult durable = sessionSnapshot(groupId, sessionId);
        for (StarweaveSessionEvent event : durable.getEvents()) {
            if (event.getEventSeq() <= afterSeq || event.getEventSeq() >= firstRingSeq) continue;
            if (generation != null && generation.longValue() != event.getGeneration()) continue;
            return true;
        }
        return false;
    }

    public ReadResult await(String groupId, String sessionId, long afterSeq,
                            Long generation, long timeoutMillis) {
        long boundedTimeout = Math.max(0L, Math.min(timeoutMillis, TimeUnit.SECONDS.toMillis(30)));
        Object lock = groupLocks.computeIfAbsent(groupId, ignored -> new Object());
        long deadline = System.currentTimeMillis() + boundedTimeout;
        synchronized (lock) {
            while (true) {
                ReadResult result = read(groupId, sessionId, afterSeq, generation);
                if (!result.getEvents().isEmpty() || result.isResyncRequired()
                        || boundedTimeout == 0L) return result;
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) return result;
                try {
                    lock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return result;
                }
            }
        }
    }

    public boolean hasDurableEvents(String groupId, String sessionId) {
        requireIdentifier(groupId, "groupId");
        requireIdentifier(sessionId, "sessionId");
        if (root == null) return !snapshot(groupId, sessionId, 0).isEmpty();
        return Files.isRegularFile(journalPath(groupId, sessionId));
    }

    private void ensureLoaded(String groupId) {
        if (loadedGroups.putIfAbsent(groupId, Boolean.TRUE) != null) return;
        AtomicLong sequence = sequences.computeIfAbsent(groupId, ignored -> new AtomicLong());
        if (root == null) return;
        Path groupDir = groupPath(groupId);
        if (!Files.isDirectory(groupDir)) return;
        List<StarweaveSessionEvent> loaded = new ArrayList<>();
        try (java.util.stream.Stream<Path> children = Files.list(groupDir)) {
            children.filter(Files::isDirectory)
                    .map(path -> path.resolve(JOURNAL_FILE))
                    .filter(Files::isRegularFile)
                    .forEach(path -> readJournal(path, groupId, loaded));
        } catch (IOException e) {
            loadedGroups.remove(groupId);
            throw new IllegalStateException("failed to load Starweave event journal", e);
        }
        loaded.sort(Comparator.comparingLong(StarweaveSessionEvent::getEventSeq));
        for (StarweaveSessionEvent event : loaded) {
            sequence.accumulateAndGet(event.getEventSeq(), Math::max);
            addToRing(groupId, event);
        }
    }

    private void readJournal(Path path, String expectedGroup,
                             List<StarweaveSessionEvent> target) {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    StarweaveSessionEvent event = StarweaveSessionEvent.fromJson(
                            JSON.parseObject(line));
                    if (!expectedGroup.equals(event.getGroupId())) {
                        log.warn("Ignoring Starweave event with mismatched group in {}", path);
                        continue;
                    }
                    target.add(event);
                } catch (RuntimeException malformed) {
                    // An interrupted append may leave one malformed tail record. Earlier
                    // complete records remain usable and a later append stays monotonic.
                    log.warn("Ignoring malformed Starweave event record in {}", path);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read Starweave event journal", e);
        }
    }

    private void persist(StarweaveSessionEvent event) {
        if (root == null) return;
        Path journal = journalPath(event.getGroupId(), event.getSessionId());
        try {
            Files.createDirectories(journal.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(journal,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                writer.write(event.toJson().toJSONString());
                writer.newLine();
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to persist Starweave event", e);
        }
    }


    private void addToRing(String groupId, StarweaveSessionEvent event) {
        Deque<StarweaveSessionEvent> ring = events.computeIfAbsent(
                groupId, ignored -> new ArrayDeque<>());
        ring.addLast(event);
        while (ring.size() > capacity) ring.removeFirst();
    }

    private Path groupPath(String groupId) {
        return root.resolve(hash(groupId));
    }

    private Path journalPath(String groupId, String sessionId) {
        return groupPath(groupId).resolve(hash(sessionId)).resolve(JOURNAL_FILE);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    public static final class ReadResult {
        private final List<StarweaveSessionEvent> events;
        private final long firstAvailableSeq;
        private final long latestSeq;
        private final boolean resyncRequired;
        private final boolean snapshotTruncated;

        ReadResult(List<StarweaveSessionEvent> events, long firstAvailableSeq,
                   long latestSeq, boolean resyncRequired) {
            this(events, firstAvailableSeq, latestSeq, resyncRequired, false);
        }

        ReadResult(List<StarweaveSessionEvent> events, long firstAvailableSeq,
                   long latestSeq, boolean resyncRequired, boolean snapshotTruncated) {
            this.events = events;
            this.firstAvailableSeq = firstAvailableSeq;
            this.latestSeq = latestSeq;
            this.resyncRequired = resyncRequired;
            this.snapshotTruncated = snapshotTruncated;
        }

        public List<StarweaveSessionEvent> getEvents() { return events; }
        public long getFirstAvailableSeq() { return firstAvailableSeq; }
        public long getLatestSeq() { return latestSeq; }
        public boolean isResyncRequired() { return resyncRequired; }
        public boolean isSnapshotTruncated() { return snapshotTruncated; }

        public JSONObject toJson() {
            JSONObject value = new JSONObject(true);
            value.put("events", events.stream().map(StarweaveSessionEvent::toJson)
                    .collect(java.util.stream.Collectors.toList()));
            value.put("firstAvailableSeq", firstAvailableSeq);
            value.put("latestSeq", latestSeq);
            value.put("resyncRequired", resyncRequired);
            value.put("snapshotTruncated", snapshotTruncated);
            return value;
        }
    }
}
