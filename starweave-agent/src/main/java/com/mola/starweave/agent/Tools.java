package com.mola.starweave.agent;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Pi's minimal coding tool set. Tool execution policy is owned by the ACP caller. */
final class Tools implements AutoCloseable {
    interface Execute { JsonObject run(JsonObject args, Cancellation cancel, Consumer<JsonObject> update) throws Exception; }
    static final class Tool {
        final String name, description; final JsonObject schema; final Execute execute;
        Tool(String name, String description, JsonObject schema, Execute execute) { this.name = name; this.description = description; this.schema = schema; this.execute = execute; }
        JsonObject definition() { return Json.obj("type", "function", "function", Json.obj("name", name, "description", description, "parameters", schema)); }
    }
    final Map<String, Tool> tools = new LinkedHashMap<>();
    private final List<McpConnection> connections = new ArrayList<>();
    private final AgentConfig config;
    private final Path cwd;
    private JvmExecutor jvm;
    Tools(AgentConfig config, Path cwd, JsonArray servers, Cancellation cancel) throws Exception {
        this.config = config; this.cwd = cwd;
        register(new Tool("read", "Read text or image files. Text uses optional 1-based offset and line limit.", schema(Json.obj("path", string(), "offset", Json.obj("type", "integer", "minimum", 1), "limit", Json.obj("type", "integer", "minimum", 1)), "path"), this::read));
        if (config.toolPolicy.canWrite()) register(new Tool("write", "Write UTF-8 content to a file, creating parent directories.", schema(Json.obj("path", string(), "content", string()), "path", "content"), this::write));
        if (config.toolPolicy.canWrite()) register(new Tool("edit", "Replace one unique occurrence of oldText with newText in a UTF-8 file.", schema(Json.obj("path", string(), "oldText", string(), "newText", string()), "path", "oldText", "newText"), this::edit));
        if (config.toolPolicy.canShell()) register(new Tool("shell", "Execute a workspace command using PowerShell on Windows or POSIX sh on Linux/macOS.", schema(Json.obj("command", string(), "timeout", Json.obj("type", "number", "minimum", 1, "maximum", 600)), "command"), this::shell));
        if (config.toolPolicy.canExecuteJvm()) {
            jvm = new JvmExecutor(config.toolPolicy.jvmBindings());
            register(new Tool("jvm_exec", "Execute Groovy directly inside the host application JVM with full Java/reflection access. Bindings: app (if host supplies it), args (JSON object), out (captured println). Return maps/lists/scalars for structured results. No sandbox. Timeout only interrupts; execution may continue; never automatically retry mutations. Not a method trace/watch engine.", schema(Json.obj("script", string(), "args", Json.obj("type", "object"), "timeoutMs", Json.obj("type", "integer", "minimum", 1, "maximum", 60000)), "script"), (args, token, update) -> Tools.result(jvm.execute(args, token).toString())));
        }
        try {
            for (JsonElement item : servers) {
                cancel.check(); JsonObject server = item.getAsJsonObject(); McpConnection client = new McpConnection(config, server, cwd, cancel); connections.add(client);
                for (JsonElement e : client.discover(cancel)) {
                    JsonObject spec = e.getAsJsonObject(); String remoteName = Json.str(spec, "name"), name = remoteName;
                    if (tools.containsKey(name)) {
                        if (Arrays.asList("read", "write", "edit", "shell", "jvm_exec").contains(name)) throw new IOException("MCP tool conflicts with built-in tool: " + name);
                        name = (Json.str(server, "name") + "_" + name).replaceAll("[^a-zA-Z0-9_-]", "_"); if (name.length() > 64) name = name.substring(0, 64);
                    }
                    register(new Tool(name, Json.str(spec, "description"), Json.object(spec, "inputSchema"), (args, token, update) -> client.invoke(remoteName, args, token)));
                }
            }
        } catch (Exception e) { close(); throw e; }
    }
    private void register(Tool tool) throws IOException { if (tool.name.isEmpty() || tools.putIfAbsent(tool.name, tool) != null) throw new IOException("Duplicate tool: " + tool.name); }
    JsonArray definitions() { JsonArray a = new JsonArray(); for (Tool t : tools.values()) a.add(t.definition()); return a; }
    Path path(JsonObject args) throws IOException {
        String value = Json.str(args, "path"); if (value.isEmpty()) throw new IOException("path is required");
        if (value.equals("~") || value.startsWith("~/")) value = System.getProperty("user.home") + value.substring(1);
        return cwd.resolve(value).normalize().toAbsolutePath();
    }
    private JsonObject read(JsonObject args, Cancellation cancel, Consumer<JsonObject> update) throws Exception {
        cancel.check(); Path file = path(args); config.toolPolicy.check(file, false); String mime = Files.probeContentType(file);
        if (mime != null && Arrays.asList("image/png", "image/jpeg", "image/gif", "image/webp").contains(mime)) {
            if (Files.size(file) > 20 * 1024 * 1024) throw new IOException("Image exceeds 20 MiB");
            return Json.obj("content", Json.array(Json.obj("type", "image", "mimeType", mime, "data", Base64.getEncoder().encodeToString(Files.readAllBytes(file)))));
        }
        if (Files.isDirectory(file)) throw new IOException("Path is a directory; use shell to list it");
        int offset = Json.integer(args, "offset", 1), limit = Json.integer(args, "limit", 2000);
        if (offset < 1 || limit < 1) throw new IOException("offset and limit must be positive");
        StringBuilder text = new StringBuilder(); int lineNumber = 0, kept = 0; boolean truncated = false;
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = Http.boundedLine(in, 1024 * 1024)) != null) {
                cancel.check(); lineNumber++; if (lineNumber < offset) continue;
                if (kept >= Math.min(limit, 10000) || text.length() + line.length() + 1 > 50000) { truncated = true; break; }
                if (kept++ > 0) text.append('\n'); text.append(line);
            }
        }
        if (truncated) text.append("\n[Truncated. Continue with offset=").append(offset + kept).append("]");
        return result(text.toString());
    }
    private JsonObject write(JsonObject args, Cancellation cancel, Consumer<JsonObject> update) throws Exception {
        if (!args.has("content") || !args.get("content").isJsonPrimitive()) throw new IOException("content is required");
        cancel.check(); Path file = path(args); config.toolPolicy.check(file, true); if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.write(file, Json.str(args, "content").getBytes(StandardCharsets.UTF_8)); return result("Wrote " + file);
    }
    private JsonObject edit(JsonObject args, Cancellation cancel, Consumer<JsonObject> update) throws Exception {
        cancel.check(); Path file = path(args); config.toolPolicy.check(file, true); String old = Json.str(args, "oldText"), replacement = Json.str(args, "newText");
        if (old.isEmpty() || !args.has("newText")) throw new IOException("oldText must not be empty and newText is required");
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8); String newline = content.contains("\r\n") ? "\r\n" : "\n";
        String normalized = content.replace("\r\n", "\n"), target = old.replace("\r\n", "\n"); int index = normalized.indexOf(target);
        if (index < 0) throw new IOException("oldText not found; read the current file first");
        if (normalized.indexOf(target, index + target.length()) >= 0) throw new IOException("oldText is not unique");
        String next = normalized.substring(0, index) + replacement.replace("\r\n", "\n") + normalized.substring(index + target.length());
        cancel.check(); Files.write(file, next.replace("\n", newline).getBytes(StandardCharsets.UTF_8)); return result("Edited " + file);
    }
    private JsonObject shell(JsonObject args, Cancellation cancel, Consumer<JsonObject> update) throws Exception {
        config.toolPolicy.checkShell();
        String command = Json.str(args, "command"); if (command.trim().isEmpty()) throw new IOException("command is required");
        int seconds = Json.integer(args, "timeout", 120); if (seconds < 1 || seconds > 600) throw new IOException("timeout must be between 1 and 600 seconds");
        cancel.check(); boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        List<String> argv = shellInvocation(command, windows, config.environment);
        ProcessBuilder builder = new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true); builder.environment().putAll(config.environment);
        Path pidFile = windows ? Files.createTempFile("starweave-shell-", ".pid") : null;
        if (pidFile != null) builder.environment().put("STARWEAVE_SHELL_PID_FILE", pidFile.toString());
        Process child;
        try { child = builder.start(); } catch (IOException e) { if (pidFile != null) Files.deleteIfExists(pidFile); throw e; }
        child.getOutputStream().close();
        StringBuilder output = new StringBuilder();
        CompletableFuture<Void> drained = new CompletableFuture<>();
        Thread pump = McpConnection.daemon("shell-output", () -> {
            try (Reader in = new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[2048]; int n; long last = 0;
                while ((n = in.read(buffer)) != -1) {
                    String snapshot;
                    synchronized (output) { output.append(buffer, 0, n); if (output.length() > 64000) output.delete(0, output.length() - 64000); snapshot = output.toString(); }
                    if (System.nanoTime() - last > TimeUnit.MILLISECONDS.toNanos(150)) { last = System.nanoTime(); update.accept(result(snapshot)); }
                }
                drained.complete(null);
            } catch (Exception e) { drained.completeExceptionally(e); }
        });
        boolean timedOut;
        try (AutoCloseable hook = cancel.onCancel(() -> killTree(child, windows, pidFile))) {
            timedOut = !child.waitFor(seconds, TimeUnit.SECONDS);
            if (timedOut) killTree(child, windows, pidFile);
            child.waitFor(3, TimeUnit.SECONDS);
            try { drained.get(3, TimeUnit.SECONDS); }
            catch (TimeoutException e) { child.getInputStream().close(); }
            catch (ExecutionException e) { if (!timedOut && !cancel.isCancelled()) throw e; }
            cancel.check();
            int code = child.isAlive() ? -1 : child.exitValue(); String text;
            synchronized (output) { text = output.toString(); }
            return Json.obj("content", Json.array(Json.text(text + (timedOut ? "\nCommand timed out." : "\nExit code: " + code))), "details", Json.obj("exitCode", code, "timedOut", timedOut));
        } finally { if (child.isAlive()) killTree(child, windows, pidFile); child.getInputStream().close(); pump.interrupt(); if (pidFile != null) Files.deleteIfExists(pidFile); }
    }
    static List<String> shellInvocation(String command, boolean windows, Map<String, String> env) {
        if (!windows) return Arrays.asList("/bin/sh", "-c", command);
        String root = env.getOrDefault("SystemRoot", "C:\\Windows");
        String script = "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(); $OutputEncoding = [Console]::OutputEncoding;\n"
                + "if ($env:STARWEAVE_SHELL_PID_FILE) { [IO.File]::WriteAllText($env:STARWEAVE_SHELL_PID_FILE, [string]$PID) }\n" + command;
        return Arrays.asList(root + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)));
    }
    private static void killTree(Process child, boolean windows, Path pidFile) {
        try {
            long pid;
            if (windows && pidFile != null && Files.size(pidFile) > 0) pid = Long.parseLong(new String(Files.readAllBytes(pidFile), StandardCharsets.UTF_8).trim());
            else try { pid = ((Number) Process.class.getMethod("pid").invoke(child)).longValue(); }
            catch (ReflectiveOperationException e) { java.lang.reflect.Field f = child.getClass().getDeclaredField("pid"); f.setAccessible(true); pid = ((Number) f.get(child)).longValue(); }
            if (windows) { new ProcessBuilder("taskkill", "/PID", Long.toString(pid), "/T", "/F").start().waitFor(3, TimeUnit.SECONDS); }
            else {
                Process ps = new ProcessBuilder("ps", "-eo", "pid=,ppid=").start(); Map<Long, List<Long>> children = new HashMap<>();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(ps.getInputStream(), StandardCharsets.UTF_8))) {
                    String line; while ((line = in.readLine()) != null) { String[] p = line.trim().split("\\s+"); if (p.length == 2) children.computeIfAbsent(Long.parseLong(p[1]), k -> new ArrayList<>()).add(Long.parseLong(p[0])); }
                }
                ps.waitFor(2, TimeUnit.SECONDS); List<Long> descendants = new ArrayList<>(); collect(pid, children, descendants, new HashSet<>());
                for (Long descendant : descendants) new ProcessBuilder("kill", "-KILL", descendant.toString()).start().waitFor(1, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) { /* process.destroyForcibly remains the final fallback */ }
        child.destroyForcibly();
    }
    private static void collect(long pid, Map<Long, List<Long>> children, List<Long> ordered, Set<Long> seen) {
        if (!seen.add(pid)) return;
        for (long child : children.getOrDefault(pid, Collections.emptyList())) { collect(child, children, ordered, seen); ordered.add(child); }
    }
    static JsonObject result(String text) { return Json.obj("content", Json.array(Json.text(text)), "details", Json.obj()); }
    private static JsonObject string() { return Json.obj("type", "string"); }
    private static JsonObject schema(JsonObject properties, String... required) { return Json.obj("type", "object", "properties", properties, "required", required, "additionalProperties", false); }
    @Override public void close() { if (jvm != null) jvm.close(); for (McpConnection c : connections) c.close(); connections.clear(); }
}
