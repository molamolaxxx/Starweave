package com.mola.starweave.agent;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Host tool adapter for an ACP runtime whose model loop runs elsewhere. */
public final class HostedToolSession implements AutoCloseable {
    private volatile Tools tools;
    private final AgentConfig config;
    private final Path cwd;
    private final JsonArray servers;
    private final Cancellation lifetime = new Cancellation();
    private final ConcurrentMap<String, Cancellation> running = new ConcurrentHashMap<>();
    private final Set<String> used = new HashSet<>();
    private final String systemPrompt;
    private volatile boolean closed;

    public HostedToolSession(AgentConfig config, Path cwd, JsonArray servers) throws Exception {
        if (!Files.isDirectory(cwd)) throw new java.io.IOException("工作目录不存在：" + cwd);
        systemPrompt = new Resources().systemPrompt(config, cwd);
        this.config = config; this.cwd = cwd; this.servers = servers;
    }
    private synchronized Tools prepare() throws Exception {
        lifetime.check();
        if (tools == null) {
            Tools created = new Tools(config, cwd, servers, lifetime);
            boolean discard;
            synchronized (used) { discard = closed; if (!discard) tools = created; }
            if (discard) { created.close(); lifetime.check(); }
        }
        return tools;
    }
    public JsonObject describe() throws Exception {
        Tools tools = prepare();
        JsonArray specs = new JsonArray();
        for (Tools.Tool tool : tools.tools.values()) specs.add(Json.obj("name", tool.name, "description", tool.description, "parameters", tool.schema));
        return Json.obj("tools", specs, "systemPrompt", systemPrompt);
    }
    public JsonObject execute(String id, String name, JsonObject args, Consumer<JsonObject> update) throws Exception {
        Cancellation token;
        synchronized (used) {
            if (closed) throw new java.io.IOException("工具会话已关闭");
            // Reliable transport replays frames only once. Reject duplicate IDs as a second defense;
            // never run an old mutation again when the original outcome is unknown.
            if (used.contains(id)) throw new java.io.IOException("工具调用 ID 已使用，禁止重复执行");
            if (used.size() >= 10000) throw new java.io.IOException("工具调用数量达到上限，请创建新会话");
            used.add(id); token = running.computeIfAbsent(id, key -> new Cancellation());
        }
        try (AutoCloseable hook = lifetime.onCancel(token::cancel)) {
            token.check(); Tools tools = prepare(); token.check(); Tools.Tool tool = tools.tools.get(name);
            if (tool == null) throw new java.io.IOException("未知或未开放的工具：" + name);
            return tool.execute.run(args, token, update);
        } finally { running.remove(id); }
    }
    /** Binary upload relay; results go directly to the extension, never to model history. */
    public JsonObject readFiles(JsonArray paths) throws Exception {
        lifetime.check();
        if (paths == null || paths.size() < 1 || paths.size() > 20) throw new java.io.IOException("需要 1 到 20 个文件路径");
        JsonArray files = new JsonArray(); int total = 0;
        for (JsonElement item : paths) {
            lifetime.check(); String value = item.getAsString();
            if (value.equals("~") || value.startsWith("~/")) value = System.getProperty("user.home") + value.substring(1);
            Path path = cwd.resolve(value).normalize().toAbsolutePath(); config.toolPolicy.check(path, false);
            if (!Files.isRegularFile(path) || Files.size(path) > 8 * 1024 * 1024 - total) throw new java.io.IOException("上传必须是普通文件且合计不超过 8 MiB");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) { lifetime.check(); total += n; if (total > 8 * 1024 * 1024) throw new java.io.IOException("上传文件超过 8 MiB"); out.write(buffer, 0, n); }
            }
            String mime = Files.probeContentType(path);
            files.add(Json.obj("name", path.getFileName().toString(), "mimeType", mime == null ? "application/octet-stream" : mime, "dataBase64", Base64.getEncoder().encodeToString(out.toByteArray())));
        }
        return Json.obj("files", files);
    }
    public void cancel(String id) { synchronized (used) { if (!closed && (!used.contains(id) || running.containsKey(id)) && used.size() + running.size() < 20000) running.computeIfAbsent(id, key -> new Cancellation()).cancel(); } }
    public void cancelAll() { for (Cancellation token : running.values()) token.cancel(); }
    @Override public void close() { synchronized (used) { if (closed) return; closed = true; } lifetime.cancel(); Tools current = tools; if (current != null) current.close(); }
}
