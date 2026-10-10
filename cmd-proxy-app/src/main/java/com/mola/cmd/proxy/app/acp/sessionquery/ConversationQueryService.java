package com.mola.cmd.proxy.app.acp.sessionquery;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.common.ToolOutputPreview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Read-only, instance-local query over canonical turn history, including legacy records. */
public final class ConversationQueryService implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ConversationQueryService.class);
    private static final byte[] CURSOR_KEY = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
    private static final Map<ConversationQueryService, Boolean> LIVE = new ConcurrentHashMap<>();
    // Shared by all clients: concurrent searches cannot multiply scanning threads.
    private static final int SCAN_THREADS = 4;
    private static final ExecutorService SCANNERS = new ThreadPoolExecutor(SCAN_THREADS, SCAN_THREADS,
            0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), runnable -> {
                Thread thread = new Thread(runnable, "conversation-query-scan");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final int PAGE_SIZE = 30, PAGE_CHARS = 24000, OUTPUT_CHARS = 2000;
    private final Path root, namespace;
    private final String agent;
    private final Supplier<String> sessionId;
    private final Supplier<JsonArray> currentHistory;
    // A bounded, rebuildable cache avoids reparsing unchanged turns during repeated queries.
    private final LinkedHashMap<Path, CachedTurn> turns = new LinkedHashMap<>(16, 0.75f, true);
    private long cachedBytes;
    private final LinkedHashMap<String, SearchSnapshot> snapshots = new LinkedHashMap<>();
    private long snapshotBytes;
    private static final long SNAPSHOT_TTL = TimeUnit.MINUTES.toMillis(15);
    private static final int SEARCH_BYTES = 64 * 1024, BATCH_BYTES = 96 * 1024;

    public ConversationQueryService(Path root, Path namespace, String agent,
                                    Supplier<String> sessionId, Supplier<JsonArray> currentHistory) {
        this.root = root.toAbsolutePath().normalize();
        this.namespace = namespace.toAbsolutePath().normalize();
        if (!this.namespace.startsWith(this.root)) throw new IllegalArgumentException("Invalid namespace");
        this.agent = agent;
        this.sessionId = sessionId;
        this.currentHistory = currentHistory;
        LIVE.put(this, true);
    }

    @Override public void close() {
        LIVE.remove(this);
        synchronized (turns) { turns.clear(); cachedBytes = 0; }
        synchronized (snapshots) { snapshots.clear(); snapshotBytes = 0; }
    }

    public String search(JsonObject arguments) {
        try { return searchInternal(arguments); }
        catch (QueryException e) { throw e; }
        catch (Exception e) {
            LOG.warn("Conversation search failed", e);
            throw failure("HISTORY_UNAVAILABLE", "会话历史暂时不可用，请稍后重试");
        }
    }

    private String searchInternal(JsonObject arguments) throws Exception {
        validate(arguments, "keyword", "agent", "cursor", "days", "roles", "limit", "scope", "team_id", "member_id");
        String scope = value(arguments, "scope").trim();
        if (scope.isEmpty()) scope = "all";
        String teamId = value(arguments, "team_id").trim(), memberId = value(arguments, "member_id").trim();
        if (!Arrays.asList("all", "main", "team").contains(scope)
                || "main".equals(scope) && (!teamId.isEmpty() || !memberId.isEmpty()))
            throw failure("INVALID_ARGUMENT", "scope 支持 all、main、team；main 不能与 team_id、member_id 同时使用");
        int days = arguments.has("days") ? arguments.get("days").getAsBigDecimal().intValueExact() : 7;
        int limit = arguments.has("limit") ? arguments.get("limit").getAsInt() : 50;
        Set<String> roles = searchRoles(arguments);
        long cutoff = days == 0 ? Long.MIN_VALUE : System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
        String keyword = value(arguments, "keyword").trim();
        String filter = value(arguments, "agent").trim();
        if (filter.isEmpty()) filter = "self";
        String binding = "search\n" + root + "\n" + ownerKey() + "\n" + keyword + "\n" + filter
                + "\n" + days + "\n" + String.join(",", roles) + "\n" + limit
                + "\n" + scope + "\n" + teamId + "\n" + memberId;
        if (!value(arguments, "cursor").isEmpty()) {
            JsonObject cursor = decode(value(arguments, "cursor"), binding);
            String id = value(cursor, "snapshot");
            SearchSnapshot snapshot;
            synchronized (snapshots) { expireSnapshots(); snapshot = snapshots.get(id); }
            if (snapshot == null) throw failure("CURSOR_EXPIRED", "搜索结果快照已过期，请重新搜索");
            return searchPage(snapshot, id, cursor.get("offset").getAsInt(), limit, binding);
        }
        List<Session> sessions = discover();
        if (!"self".equals(filter) && !"all".equals(filter)) {
            // One configured source Agent can have both MAIN and multiple TEAM namespaces.
            if (sessions.stream().noneMatch(s -> s.agent.equals(filterName(arguments))))
                throw failure("AGENT_NOT_FOUND", "指定 Agent 不存在或尚无历史记录");
        }
        List<Session> selected = new ArrayList<>();
        for (Session session : sessions) {
            if ("self".equals(filter) ? !session.owner.equals(ownerKey())
                    : !"all".equals(filter) && !session.agent.equals(filter)) continue;
            if (!"all".equals(scope) && !scope.equals(session.scope)) continue;
            if (!teamId.isEmpty() && !teamId.equals(session.teamId)) continue;
            if (!memberId.isEmpty() && !memberId.equals(session.memberId)) continue;
            // Live history can contain new, unflushed messages despite an old turn timestamp.
            if (session.live == null && session.updated > 0 && session.updated < cutoff) continue;
            selected.add(session);
        }
        List<JsonObject> hits = scanSessions(selected, keyword, roles);
        String id = UUID.randomUUID().toString();
        SearchSnapshot snapshot = new SearchSnapshot(hits);
        if (snapshot.bytes > 8 * 1024 * 1024) throw failure("SEARCH_TOO_LARGE", "搜索结果过多，请缩小时间或 Agent 范围");
        if (hits.size() > limit || snapshot.bytes + 1024 > SEARCH_BYTES) {
            synchronized (snapshots) {
                expireSnapshots();
                snapshots.put(id, snapshot); snapshotBytes += snapshot.bytes;
                while (snapshots.size() > 16 || snapshotBytes > 8 * 1024 * 1024) {
                    Iterator<SearchSnapshot> values = snapshots.values().iterator();
                    snapshotBytes -= values.next().bytes; values.remove();
                }
                if (!snapshots.containsKey(id)) throw failure("SEARCH_TOO_LARGE", "搜索结果过多，请缩小时间或 Agent 范围");
            }
        }
        return searchPage(snapshot, id, 0, limit, binding);
    }

    private void expireSnapshots() {
        Iterator<SearchSnapshot> values = snapshots.values().iterator();
        while (values.hasNext()) {
            SearchSnapshot snapshot = values.next();
            if (snapshot.expires < System.currentTimeMillis()) { snapshotBytes -= snapshot.bytes; values.remove(); }
        }
    }

    private String searchPage(SearchSnapshot snapshot, String id, int start, int limit, String binding) {
        if (start < 0 || start > snapshot.hits.size()) throw failure("CURSOR_EXPIRED", "游标已失效");
        JsonArray page = new JsonArray();
        int end = start, bytes = 1024;
        while (end < snapshot.hits.size() && end - start < limit) {
            JsonObject hit = snapshot.hits.get(end);
            int size = hit.toString().getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > SEARCH_BYTES && page.size() > 0) break;
            page.add(hit); bytes += size; end++;
        }
        JsonObject result = new JsonObject();
        result.add("sessions", page);
        result.addProperty("total_sessions", snapshot.hits.size());
        JsonObject next = new JsonObject();
        next.addProperty("snapshot", id); next.addProperty("offset", end);
        result.add("next_cursor", end < snapshot.hits.size() ? new JsonPrimitive(encode(next, binding)) : JsonNull.INSTANCE);
        return result.toString();
    }

    private List<JsonObject> scanSessions(List<Session> sessions, String keyword, Set<String> roles) throws Exception {
        JsonObject[] hits = new JsonObject[sessions.size()];
        AtomicInteger next = new AtomicInteger();
        List<Future<?>> workers = new ArrayList<>();
        try {
            // Submit only four workers per query, rather than enqueue every historical session.
            for (int n = 0; n < Math.min(SCAN_THREADS, sessions.size()); n++) {
                workers.add(SCANNERS.submit(() -> {
                    int index;
                    while (!Thread.currentThread().isInterrupted()
                            && (index = next.getAndIncrement()) < sessions.size()) {
                        hits[index] = searchSession(sessions.get(index), keyword, roles);
                    }
                    return null;
                }));
            }
            for (Future<?> worker : workers) worker.get();
        } catch (RejectedExecutionException e) {
            throw failure("SEARCH_BUSY", "会话搜索繁忙，请稍后重试");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            for (Future<?> worker : workers) if (!worker.isDone()) worker.cancel(true);
            // Remove cancelled queued work immediately, keeping admission available to other Agents.
            ((ThreadPoolExecutor) SCANNERS).purge();
        }
        // Completion order must not change pagination order or cursor snapshots.
        return Arrays.stream(hits).filter(Objects::nonNull).collect(Collectors.toList());
    }

    private JsonObject searchSession(Session session, String keyword, Set<String> roles) throws Exception {
        List<JsonObject> messages = messages(session, true, roles);
        String title = title(messages);
        JsonArray matches = new JsonArray();
        for (JsonObject message : messages) {
            if (keyword.isEmpty() || matches.size() == 2) break;
            for (String source : roles) {
                String text = searchable(message, source);
                if (text.isEmpty() || !contains(text, keyword)) continue;
                JsonObject match = new JsonObject();
                match.add("message_id", message.get("message_id"));
                match.add("role", message.get("role"));
                match.addProperty("source", source);
                match.addProperty("snippet", snippet(text, keyword));
                matches.add(match);
                break;
            }
        }
        // A display title cannot bypass the requested message roles.
        if (!keyword.isEmpty() && matches.size() == 0) return null;
        JsonObject hit = new JsonObject();
        hit.addProperty("session_ref", session.ref);
        addIdentity(hit, session);
        hit.addProperty("title", title);
        hit.add("updated_at", session.updated > 0 ? new JsonPrimitive(Instant.ofEpochMilli(session.updated).toString()) : JsonNull.INSTANCE);
        hit.add("matches", matches);
        return hit;
    }

    private static String filterName(JsonObject args) { return value(args, "agent").trim(); }

    public String read(JsonObject arguments) {
        try { return readInternal(arguments); }
        catch (QueryException e) { throw e; }
        catch (Exception e) {
            LOG.warn("Conversation history read failed", e);
            throw failure("HISTORY_UNAVAILABLE", "会话历史暂时不可用，请稍后重试");
        }
    }

    private String readInternal(JsonObject arguments) throws Exception {
        validate(arguments, "session_ref", "message_id", "cursor");
        String ref = value(arguments, "session_ref");
        if (ref.isEmpty()) throw failure("INVALID_ARGUMENT", "session_ref 必填");
        String anchor = value(arguments, "message_id"), token = value(arguments, "cursor");
        if (!anchor.isEmpty() && !token.isEmpty()) throw failure("INVALID_ARGUMENT", "message_id 与 cursor 互斥");
        Session session = discover().stream().filter(s -> s.ref.equals(ref)).findFirst()
                .orElseThrow(() -> failure("SESSION_NOT_FOUND", "会话不存在或已删除"));
        List<JsonObject> messages = messages(session, false);
        return readPage(session, messages, arguments, 14, Integer.MAX_VALUE);
    }

    private String readPage(Session session, List<JsonObject> messages, JsonObject arguments, int beforeCount, int afterCount) {
        String ref = session.ref, anchor = value(arguments, "message_id"), token = value(arguments, "cursor");
        String binding = "read\n" + root + "\n" + ownerKey() + "\n" + ref;
        boolean backwards = anchor.isEmpty();
        int endExclusive = messages.size();
        int index = messages.size() - 1, offset = index < 0 ? 0 : length(messages.get(index));
        if (!token.isEmpty()) {
            JsonObject cursor = decode(token, binding);
            index = locate(messages, value(cursor, "id"));
            if (index < 0 || !hash(messages.get(index).toString()).equals(value(cursor, "version")))
                throw failure("CURSOR_EXPIRED", "游标对应的消息已变化，请重新读取");
            offset = cursor.get("offset").getAsInt();
            backwards = cursor.get("backwards").getAsBoolean();
        } else if (!anchor.isEmpty()) {
            int found = locate(messages, anchor);
            if (found < 0) throw failure("MESSAGE_NOT_FOUND", "指定消息不在该会话中");
            // Allocate at most half the character budget before the anchor so it always appears.
            index = found;
            int precedingChars = 0;
            while (index > 0 && found - index < beforeCount
                    && precedingChars + length(messages.get(index - 1)) < PAGE_CHARS / 2) {
                precedingChars += length(messages.get(--index));
            }
            if ("tool".equals(value(messages.get(found), "role"))
                    && precedingChars + length(messages.get(found)) > PAGE_CHARS) index = found;
            offset = 0; backwards = false;
            endExclusive = (int) Math.min(messages.size(), (long) found + afterCount + 1);
        }
        if (index >= 0 && (offset < 0 || offset > length(messages.get(index))))
            throw failure("CURSOR_EXPIRED", "游标位置无效");
        int startIndex = index, startOffset = offset;
        List<JsonObject> page = new ArrayList<>();
        int used = 0;
        while (index >= 0 && index < endExclusive && page.size() < PAGE_SIZE) {
            JsonObject original = messages.get(index);
            boolean tool = "tool".equals(value(original, "role"));
            int available = PAGE_CHARS - used;
            int size = length(original);
            if (available <= 0) break;
            if (tool && size > available && !page.isEmpty()) break;
            JsonObject item = original.deepCopy();
            if (tool) {
                // The budget is deliberately soft for a single complete tool input.
                page.add(item); used += size;
                index += backwards ? -1 : 1;
                offset = index >= 0 && index < messages.size() && backwards ? length(messages.get(index)) : 0;
            } else {
                String text = value(original, "content");
                int from = backwards ? Math.max(0, offset - available) : offset;
                int to = backwards ? offset : Math.min(text.length(), offset + available);
                // Never split a surrogate pair across pages.
                if (from > 0 && from < text.length() && Character.isLowSurrogate(text.charAt(from))) from++;
                if (to < text.length() && to > from && Character.isHighSurrogate(text.charAt(to - 1))) to--;
                if (to <= from && !text.isEmpty()) break;
                item.addProperty("content", text.substring(from, to));
                item.addProperty("partial", from > 0 || to < text.length() || original.get("partial").getAsBoolean());
                page.add(item); used += Math.max(1, to - from);
                if (backwards ? from == 0 : to == text.length()) {
                    index += backwards ? -1 : 1;
                    offset = index >= 0 && index < messages.size() && backwards ? length(messages.get(index)) : 0;
                } else { offset = backwards ? from : to; break; }
            }
        }
        JsonElement before, after;
        if (backwards) {
            Collections.reverse(page);
            before = position(messages, index, offset, true, binding);
            after = position(messages, startIndex, startOffset, false, binding);
        } else {
            before = position(messages, startIndex, startOffset, true, binding);
            after = position(messages, index, offset, false, binding);
        }
        JsonObject result = new JsonObject();
        result.addProperty("session_ref", ref); addIdentity(result, session);
        JsonArray array = new JsonArray(); page.forEach(array::add); result.add("messages", array);
        result.add("before_cursor", before); result.add("after_cursor", after);
        return result.toString();
    }

    public String readContexts(JsonObject arguments) {
        if (arguments.size() != 1 || !arguments.has("items") || !arguments.get("items").isJsonArray()
                || arguments.getAsJsonArray("items").size() < 1 || arguments.getAsJsonArray("items").size() > 10)
            throw failure("INVALID_ARGUMENT", "items 必须为包含 1 到 10 项的数组");
        JsonArray items = arguments.getAsJsonArray("items");
        for (JsonElement item : items) {
            if (!item.isJsonObject()) throw failure("INVALID_ARGUMENT", "每项必须包含 session_ref 和 message_id");
            JsonObject request = item.getAsJsonObject(); validate(request, "session_ref", "message_id");
            if (value(request, "session_ref").isEmpty() || value(request, "message_id").isEmpty())
                throw failure("INVALID_ARGUMENT", "每项必须包含 session_ref 和 message_id");
        }
        Map<String, Session> sessions = new HashMap<>();
        try { for (Session session : discover()) sessions.put(session.ref, session); }
        catch (Exception e) {
            LOG.warn("Conversation context discovery failed", e);
            throw failure("HISTORY_UNAVAILABLE", "会话历史暂时不可用，请稍后重试");
        }
        Map<String, List<JsonObject>> loaded = new HashMap<>();
        JsonArray contexts = new JsonArray(), remaining = new JsonArray();
        int bytes = 1024;
        for (int n = 0; n < items.size(); n++) {
            JsonObject request = items.get(n).getAsJsonObject(), context;
            String ref = value(request, "session_ref");
            try {
                Session session = sessions.get(ref);
                if (session == null) throw failure("SESSION_NOT_FOUND", "会话不存在或已删除");
                List<JsonObject> history = loaded.get(ref);
                if (history == null) { history = messages(session, false); loaded.put(ref, history); }
                context = JsonParser.parseString(readPage(session, history, request, 2, 3)).getAsJsonObject();
                context.add("message_id", request.get("message_id"));
            } catch (Exception e) {
                QueryException error;
                if (e instanceof QueryException) error = (QueryException) e;
                else {
                    LOG.warn("Conversation context read failed", e);
                    error = failure("HISTORY_UNAVAILABLE", "会话历史暂时不可用，请稍后重试");
                }
                context = request.deepCopy();
                if (sessions.containsKey(ref)) addIdentity(context, sessions.get(ref));
                JsonObject details = new JsonObject(); details.addProperty("code", error.code);
                details.addProperty("message", error.getMessage()); context.add("error", details);
            }
            int size = context.toString().getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > BATCH_BYTES && contexts.size() > 0) {
                for (int i = n; i < items.size(); i++) remaining.add(items.get(i));
                break;
            }
            contexts.add(context); bytes += size;
        }
        JsonObject result = new JsonObject(); result.add("contexts", contexts); result.add("remaining_items", remaining);
        return result.toString();
    }

    private static void addIdentity(JsonObject result, Session session) {
        result.addProperty("agent", session.agent);
        result.addProperty("scope", session.scope);
        if ("team".equals(session.scope)) {
            result.addProperty("team_id", session.teamId);
            result.addProperty("member_id", session.memberId);
        }
    }

    private JsonElement position(List<JsonObject> messages, int index, int offset, boolean backwards, String binding) {
        if (index < 0 || index >= messages.size()) return JsonNull.INSTANCE;
        if (backwards && offset == 0) { index--; if (index >= 0) offset = length(messages.get(index)); }
        else if (!backwards && offset == length(messages.get(index))) { index++; offset = 0; }
        if (index < 0 || index >= messages.size()) return JsonNull.INSTANCE;
        JsonObject cursor = new JsonObject();
        cursor.add("id", messages.get(index).get("message_id"));
        cursor.addProperty("version", hash(messages.get(index).toString()));
        cursor.addProperty("offset", offset); cursor.addProperty("backwards", backwards);
        return new JsonPrimitive(encode(cursor, binding));
    }

    private List<Session> discover() throws Exception {
        Map<String, Session> found = new LinkedHashMap<>();
        scan(root, false, found);
        scan(root.resolveSibling("team-archive"), true, found);
        for (ConversationQueryService live : LIVE.keySet()) {
            if (!root.equals(live.root)) continue;
            String sid = live.sessionId.get();
            if (sid == null || sid.isEmpty() || sid.contains("/") || sid.contains("\\")) continue;
            Path directory = live.namespace.resolve(sid);
            String relative = root.relativize(directory).toString().replace('\\', '/');
            Session session = found.computeIfAbsent(hash(relative), key -> new Session(directory, key, live.agent,
                    root.relativize(live.namespace).toString().replace('\\', '/'), 0));
            session.live = live;
        }
        List<Session> result = new ArrayList<>(found.values());
        result.sort(Comparator.comparingLong((Session s) -> s.updated).reversed().thenComparing(s -> s.ref));
        return result;
    }

    private void scan(Path base, boolean archived, Map<String, Session> found) throws Exception {
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> paths = Files.walk(base, 8)) {
            for (Path turn : paths.filter(p -> !Files.isSymbolicLink(p) && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> p.getFileName().toString().matches("turn_[0-9]+\\.json"))
                    .collect(Collectors.toList())) {
                Path directory = turn.getParent(), ownerDir = directory.getParent();
                String relative = base.relativize(directory).toString().replace('\\', '/');
                if (archived) relative = "team/" + relative;
                int ownerEnd = relative.lastIndexOf('/');
                // Legacy provider histories can sit directly below the session root.
                // They have no reliable Agent ownership and must not abort other queries.
                if (ownerEnd < 0) continue;
                String owner = relative.substring(0, ownerEnd);
                String[] parts = owner.split("/");
                // Never expose internal memory/ability/subagent runs as user conversations.
                if (!(parts.length == 1 || parts.length == 2 && "starweave".equals(parts[0])
                        || parts.length == 3 && "team".equals(parts[0]))) continue;
                String name = parts[parts.length - 1];
                Path metadata = ownerDir.resolve("query-owner.json");
                if (Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        JsonObject identity = JsonParser.parseString(new String(Files.readAllBytes(metadata), StandardCharsets.UTF_8)).getAsJsonObject();
                        if (!Arrays.asList("MAIN", "TEAM").contains(value(identity, "scope"))) continue;
                        name = value(identity, "agent");
                    } catch (RuntimeException e) { LOG.warn("Invalid conversation ownership metadata"); continue; }
                }
                for (ConversationQueryService live : LIVE.keySet()) {
                    if (root.equals(live.root) && owner.equals(live.ownerKey())) { name = live.agent; break; }
                }
                String ref = hash(relative);
                long updated = Files.getLastModifiedTime(turn).toMillis();
                Session previous = found.get(ref);
                if (previous == null) found.put(ref, new Session(directory, ref, name, owner, updated));
                else previous.updated = Math.max(previous.updated, updated);
            }
        }
    }

    private List<JsonObject> messages(Session session, boolean searching) throws Exception {
        return messages(session, searching, null);
    }

    private List<JsonObject> messages(Session session, boolean searching, Set<String> roles) throws Exception {
        List<JsonObject> raw = new ArrayList<>();
        if (session.live != null) {
            String before = session.live.sessionId.get();
            JsonArray snapshot = session.live.currentHistory.get();
            if (Objects.equals(before, session.live.sessionId.get())
                    && session.directory.getFileName().toString().equals(before)) {
                int n = 0;
                for (JsonElement item : snapshot) {
                    String id = session.ref + ":live:" + n++;
                    if (eligible(item.getAsJsonObject(), roles)) raw.add(normalize(item.getAsJsonObject(), id));
                }
                return merge(raw, searching);
            }
        }
        if (!Files.isDirectory(session.directory, LinkOption.NOFOLLOW_LINKS)) return raw;
        try (Stream<Path> paths = Files.list(session.directory)) {
            for (Path turn : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && p.getFileName().toString().matches("turn_[0-9]+\\.json"))
                    .sorted(Comparator.comparingLong(ConversationQueryService::turnNumber)).collect(Collectors.toList())) {
                try {
                    JsonArray array = loadTurn(turn);
                    int i = 0;
                    for (JsonElement item : array) {
                        String id = "history:" + session.directory.getFileName() + ":" + turn.getFileName() + ":" + i++;
                        if (eligible(item.getAsJsonObject(), roles)) raw.add(normalize(item.getAsJsonObject(), id));
                    }
                } catch (RuntimeException e) { throw failure("HISTORY_UNAVAILABLE", "历史记录正在写入或格式损坏，请稍后重试"); }
            }
        }
        return merge(raw, searching);
    }

    private static boolean eligible(JsonObject raw, Set<String> roles) {
        if (roles == null) return true;
        String role = value(raw, "role").toLowerCase(Locale.ROOT);
        return "tool".equals(role) ? roles.contains("tool_input") || roles.contains("tool_output") : roles.contains(role);
    }

    private JsonArray loadTurn(Path path) throws Exception {
        java.nio.file.attribute.FileTime stamp = Files.getLastModifiedTime(path);
        long size = Files.size(path);
        synchronized (turns) {
            CachedTurn cached = turns.get(path);
            if (cached != null && cached.stamp.equals(stamp) && cached.size == size) return cached.messages;
        }
        JsonArray messages = JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonArray();
        if (size <= 1024 * 1024 && stamp.equals(Files.getLastModifiedTime(path)) && size == Files.size(path)) {
            synchronized (turns) {
                CachedTurn previous = turns.remove(path);
                if (previous != null) cachedBytes -= previous.size;
                turns.put(path, new CachedTurn(stamp, size, messages)); cachedBytes += size;
                while (turns.size() > 64 || cachedBytes > 4 * 1024 * 1024) {
                    Iterator<CachedTurn> values = turns.values().iterator();
                    cachedBytes -= values.next().size; values.remove();
                }
            }
        }
        return messages;
    }

    private static long turnNumber(Path p) {
        String name = p.getFileName().toString(); return Long.parseLong(name.substring(5, name.length() - 5));
    }

    private static JsonObject normalize(JsonObject raw, String fallback) {
        JsonObject result = new JsonObject();
        result.addProperty("message_id", value(raw, "messageId").isEmpty() ? fallback : value(raw, "messageId"));
        result.addProperty("role", value(raw, "role").toLowerCase(Locale.ROOT));
        if ("tool".equals(value(result, "role"))) {
            result.addProperty("tool_name", value(raw, "toolName"));
            result.addProperty("status", value(raw, "status"));
            result.add("input", raw.has("rawInput") ? raw.get("rawInput").deepCopy() : JsonNull.INSTANCE);
            result.add("_raw_output", raw.has("rawOutput") ? raw.get("rawOutput").deepCopy() : JsonNull.INSTANCE);
            result.addProperty("_call", value(raw, "toolCallId"));
        } else {
            result.addProperty("content", value(raw, "content"));
            result.addProperty("partial", raw.has("partial") && raw.get("partial").getAsBoolean());
            if (raw.has("userOrigin")) result.addProperty("origin", value(raw, "userOrigin").toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private static List<JsonObject> merge(List<JsonObject> raw, boolean searching) {
        Map<String, JsonObject> messages = new LinkedHashMap<>();
        for (JsonObject item : raw) {
            if (!Arrays.asList("user", "assistant", "tool").contains(value(item, "role"))) continue;
            String call = value(item, "_call");
            String key = call.isEmpty() ? value(item, "message_id") : "tool:" + call;
            JsonObject previous = messages.get(key);
            if (previous != null && !call.isEmpty()) {
                if (!value(item, "tool_name").isEmpty()) previous.add("tool_name", item.get("tool_name"));
                if (!value(item, "status").isEmpty()) previous.add("status", item.get("status"));
                if (!item.get("input").isJsonNull()) previous.add("input", item.get("input"));
                if (!item.get("_raw_output").isJsonNull()) previous.add("_raw_output", item.get("_raw_output"));
            } else messages.put(key, item);
        }
        List<JsonObject> result = new ArrayList<>(messages.values());
        for (JsonObject item : result) {
            if (!"tool".equals(value(item, "role"))) continue;
            JsonElement output = item.remove("_raw_output"); item.remove("_call");
            if (searching) {
                item.add("_search_output", output);
                continue;
            }
            String original = output.isJsonNull() ? "" : output.toString();
            com.alibaba.fastjson.JSONObject wrapper = new com.alibaba.fastjson.JSONObject();
            wrapper.put("rawOutput", output.isJsonNull() ? null : com.alibaba.fastjson.JSON.parse(original));
            com.alibaba.fastjson.JSONObject preview = ToolOutputPreview.payload(wrapper);
            String text = preview.get("rawOutput") == null ? "" : com.alibaba.fastjson.JSON.toJSONString(preview.get("rawOutput"));
            boolean shortened = preview.getBooleanValue("outputTruncated") || text.length() > OUTPUT_CHARS;
            item.addProperty("output", abbreviate(text, OUTPUT_CHARS));
            item.addProperty("output_truncated", shortened);
        }
        return result;
    }

    private static int length(JsonObject message) {
        return "tool".equals(value(message, "role")) ? message.toString().length() : value(message, "content").length();
    }
    private static int locate(List<JsonObject> messages, String id) {
        for (int i = 0; i < messages.size(); i++) if (value(messages.get(i), "message_id").equals(id)) return i;
        return -1;
    }
    private static String title(List<JsonObject> messages) {
        for (String role : new String[]{"user", "assistant"}) for (JsonObject m : messages)
            if (role.equals(value(m, "role")) && (!m.has("origin") || "user".equals(value(m, "origin")))
                    && !value(m, "content").trim().isEmpty()) return abbreviate(value(m, "content").trim(), 60);
        return "(空会话)";
    }
    private static String searchable(JsonObject m, String source) {
        String name = value(m, "tool_name");
        if ("tool".equals(value(m, "role"))
                && (name.endsWith("search_sessions") || name.endsWith("read_session_history")
                || name.endsWith("read_session_contexts"))) return "";
        if ("tool".equals(value(m, "role"))) {
            if (!source.startsWith("tool_")) return "";
            return name + "\n" + SearchText.extract(m.get("tool_input".equals(source) ? "input" : "_search_output"));
        }
        return source.equals(value(m, "role")) ? SearchText.clean(value(m, "content")) : "";
    }
    private static boolean contains(String text, String keyword) {
        String lower = text.toLowerCase(Locale.ROOT);
        return Arrays.stream(keyword.toLowerCase(Locale.ROOT).split("\\s+")).allMatch(lower::contains);
    }
    private static String snippet(String text, String keyword) {
        int at = text.toLowerCase(Locale.ROOT).indexOf(keyword.toLowerCase(Locale.ROOT).split("\\s+")[0]);
        int start = Math.max(0, at - 60);
        int end = Math.min(text.length(), start + 300);
        if (start > 0 && Character.isLowSurrogate(text.charAt(start))) start++;
        if (end < text.length() && end > start && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return (start > 0 ? "…" : "") + text.substring(start, end) + (end < text.length() ? "…" : "");
    }
    private static String abbreviate(String text, int max) {
        if (text.length() <= max) return text;
        String marker = "\n[中间内容已省略]\n";
        int head = (max - marker.length()) * 3 / 4, tail = max - marker.length() - head;
        if (Character.isHighSurrogate(text.charAt(head - 1))) head--;
        int end = text.length() - tail;
        if (Character.isLowSurrogate(text.charAt(end))) end++;
        return text.substring(0, head) + marker + text.substring(end);
    }
    private String ownerKey() { return root.relativize(namespace).toString().replace('\\', '/'); }
    private static String value(JsonObject object, String key) {
        JsonElement value = object.get(key); return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
    private static void validate(JsonObject args, String... fields) {
        Set<String> allowed = new HashSet<>(Arrays.asList(fields));
        for (Map.Entry<String, JsonElement> field : args.entrySet())
            if (allowed.contains(field.getKey()) && "roles".equals(field.getKey())) {
                searchRoles(args);
            } else if (allowed.contains(field.getKey()) && ("days".equals(field.getKey()) || "limit".equals(field.getKey()))) {
                try {
                    if (!field.getValue().isJsonPrimitive() || !field.getValue().getAsJsonPrimitive().isNumber()
                            || field.getValue().getAsBigDecimal().intValueExact() < 0) throw new IllegalArgumentException();
                    if ("limit".equals(field.getKey()) && (field.getValue().getAsInt() < 1 || field.getValue().getAsInt() > 50))
                        throw new IllegalArgumentException();
                } catch (RuntimeException e) { throw failure("INVALID_ARGUMENT", "days 必须为非负整数，limit 必须为 1 到 50 的整数"); }
            } else if (!allowed.contains(field.getKey()) || !field.getValue().isJsonPrimitive()
                    || !field.getValue().getAsJsonPrimitive().isString())
                throw failure("INVALID_ARGUMENT", "参数必须为字符串且属于已定义字段：" + field.getKey());
    }

    private static Set<String> searchRoles(JsonObject args) {
        SortedSet<String> roles = new TreeSet<>();
        if (!args.has("roles")) roles.addAll(Arrays.asList("user", "assistant"));
        else {
            if (!args.get("roles").isJsonArray() || args.getAsJsonArray("roles").size() == 0)
                throw failure("INVALID_ARGUMENT", "roles 必须为非空数组");
            for (JsonElement role : args.getAsJsonArray("roles")) {
                if (!role.isJsonPrimitive() || !role.getAsJsonPrimitive().isString()
                        || !Arrays.asList("user", "assistant", "tool", "tool_input", "tool_output").contains(role.getAsString()))
                    throw failure("INVALID_ARGUMENT", "roles 支持 user、assistant、tool、tool_input、tool_output");
                if ("tool".equals(role.getAsString())) roles.addAll(Arrays.asList("tool_input", "tool_output"));
                else roles.add(role.getAsString());
            }
        }
        return roles;
    }
    private static String hash(String text) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String sign(String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(CURSOR_KEY, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String encode(JsonObject cursor, String binding) {
        cursor.addProperty("binding", hash(binding)); cursor.addProperty("expires", System.currentTimeMillis() + 15 * 60 * 1000);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(cursor.toString().getBytes(StandardCharsets.UTF_8));
        return body + "." + sign(body);
    }
    private static JsonObject decode(String token, String binding) {
        try {
            if (token.length() > 4096) throw new IllegalArgumentException();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]).getBytes(StandardCharsets.UTF_8), parts[1].getBytes(StandardCharsets.UTF_8)))
                throw new IllegalArgumentException();
            JsonObject result = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!hash(binding).equals(value(result, "binding")) || result.get("expires").getAsLong() < System.currentTimeMillis())
                throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException e) { throw failure("CURSOR_EXPIRED", "游标无效、已过期或不属于当前查询"); }
    }
    public static QueryException failure(String code, String message) { return new QueryException(code, message); }
    public static final class QueryException extends RuntimeException {
        public final String code;
        private QueryException(String code, String message) { super(message); this.code = code; }
    }
    private static final class Session {
        final Path directory;
        final String ref, agent, owner, scope, teamId, memberId;
        long updated;
        ConversationQueryService live;
        Session(Path directory, String ref, String agent, String owner, long updated) {
            this.directory = directory; this.ref = ref; this.agent = agent; this.owner = owner; this.updated = updated;
            String[] parts = owner.split("/");
            boolean team = parts.length == 3 && "team".equals(parts[0]);
            this.scope = team ? "team" : "main";
            this.teamId = team ? parts[1] : null;
            this.memberId = team ? parts[2] : null;
        }
    }
    private static final class CachedTurn {
        final java.nio.file.attribute.FileTime stamp;
        final long size;
        final JsonArray messages;
        CachedTurn(java.nio.file.attribute.FileTime stamp, long size, JsonArray messages) {
            this.stamp = stamp; this.size = size; this.messages = messages;
        }
    }
    private static final class SearchSnapshot {
        final List<JsonObject> hits;
        final long expires = System.currentTimeMillis() + SNAPSHOT_TTL;
        final long bytes;
        SearchSnapshot(List<JsonObject> hits) {
            this.hits = hits;
            this.bytes = hits.stream().mapToLong(hit -> hit.toString().getBytes(StandardCharsets.UTF_8).length).sum();
        }
    }
}
