package com.mola.starweave.agent;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * In-JVM ACP endpoint. Process is only a compatibility handle for Starweave's existing
 * stream/lifecycle owner: no OS process, executable, Node runtime or listening port is created.
 */
public final class EmbeddedAgentRuntime extends Process implements PiAgent.Peer {
    private final AgentConfig config;
    private final ByteQueue output = new ByteQueue();
    private final CountDownLatch exited = new CountDownLatch(1);
    private final AtomicBoolean closing = new AtomicBoolean(), prompting = new AtomicBoolean();
    private final ConcurrentMap<String, CompletableFuture<JsonObject>> permissions = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final ThreadPoolExecutor worker;
    private final PiAgent agent;
    private final Input input = new Input();
    public EmbeddedAgentRuntime(AgentConfig config) throws IOException {
        this.config = config; agent = new PiAgent(config, this);
        worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> {
            Thread t = new Thread(task, "starweave-java-agent"); t.setDaemon(true); return t;
        });
        worker.allowCoreThreadTimeOut(true);
    }
    private void receive(JsonObject message) throws IOException {
        if (!message.has("method")) {
            if (message.has("id")) { CompletableFuture<JsonObject> future = permissions.get(message.get("id").getAsString()); if (future != null) future.complete(message); }
            return;
        }
        String method = Json.str(message, "method"); JsonObject params = Json.object(message, "params");
        if (method.equals("session/cancel") || method.equals("session/close") || method.equals("session/end")) agent.cancel(Json.str(params, "sessionId"));
        if (method.equals("session/cancel")) { if (message.has("id")) response(message, Json.obj()); return; }
        boolean prompt = method.equals("session/prompt");
        if (closing.get()) { error(message, -32000, "Agent runtime closed"); return; }
        if (prompt ? !prompting.compareAndSet(false, true) : prompting.get() && !method.equals("session/close") && !method.equals("session/end")) {
            error(message, -32602, "会话正在回复"); return;
        }
        try {
            if (prompt) agent.preparePrompt(Json.str(params, "sessionId"));
            worker.execute(() -> {
                try { JsonObject result = agent.handle(method, params); if (prompt) prompting.set(false); if (message.has("id")) response(message, result); }
                catch (Exception e) {
                    if (prompt) prompting.set(false);
                    try { error(message, e instanceof IllegalArgumentException ? -32602 : e instanceof UnsupportedOperationException ? -32601 : -32603, config.redact(rootMessage(e))); }
                    catch (IOException ignored) { }
                }
            });
        } catch (RejectedExecutionException e) { if (prompt) prompting.set(false); error(message, -32000, "Agent request queue full or closed"); }
        catch (IllegalArgumentException e) { if (prompt) prompting.set(false); error(message, -32602, config.redact(e.getMessage())); }
    }
    private static String rootMessage(Throwable error) { while (error.getCause() != null && (error instanceof ExecutionException || error instanceof CompletionException)) error = error.getCause(); return error.getMessage(); }
    private void response(JsonObject request, JsonObject result) throws IOException { send(Json.obj("jsonrpc", "2.0", "id", request.get("id"), "result", result)); }
    private void error(JsonObject request, int code, String message) throws IOException { if (request.has("id")) send(Json.obj("jsonrpc", "2.0", "id", request.get("id"), "error", Json.obj("code", code, "message", message))); }
    private synchronized void send(JsonObject message) throws IOException { output.put((Json.GSON.toJson(message) + "\n").getBytes(StandardCharsets.UTF_8)); }
    @Override public void update(String sessionId, JsonObject update) throws IOException { send(Json.obj("jsonrpc", "2.0", "method", "session/update", "params", Json.obj("sessionId", sessionId, "update", update))); }
    @Override public JsonObject request(String method, JsonObject params, Cancellation cancel) throws Exception {
        cancel.check(); String id = "java-agent-" + ids.incrementAndGet(); CompletableFuture<JsonObject> future = new CompletableFuture<>(); permissions.put(id, future);
        try (AutoCloseable hook = cancel.onCancel(() -> future.completeExceptionally(new CancellationException("Permission cancelled")))) {
            send(Json.obj("jsonrpc", "2.0", "id", id, "method", method, "params", params));
            JsonObject response = future.get(120, TimeUnit.SECONDS); if (response.has("error")) throw new IOException("Permission request failed"); return Json.object(response, "result");
        } finally { permissions.remove(id); }
    }
    @Override public OutputStream getOutputStream() { return input; }
    @Override public InputStream getInputStream() { return output; }
    @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
    @Override public int waitFor() throws InterruptedException { exited.await(); return 0; }
    @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException { return exited.await(timeout, unit); }
    @Override public int exitValue() { if (isAlive()) throw new IllegalThreadStateException("Embedded runtime is active"); return 0; }
    @Override public boolean isAlive() { return exited.getCount() != 0; }
    @Override public void destroy() { shutdown(true); }
    @Override public Process destroyForcibly() { shutdown(true); return this; }
    private void shutdown(boolean immediate) {
        if (immediate) output.close();
        if (!closing.compareAndSet(false, true)) return;
        agent.cancelAll(); for (CompletableFuture<JsonObject> f : permissions.values()) f.cancel(false);
        Runnable finish = () -> { try { agent.close(); } finally { output.finish(); exited.countDown(); } };
        try { worker.execute(finish); worker.shutdown(); }
        catch (RejectedExecutionException e) { worker.shutdownNow(); finish.run(); }
    }
    private final class Input extends OutputStream {
        final ByteArrayOutputStream line = new ByteArrayOutputStream(); boolean closed;
        @Override public synchronized void write(int value) throws IOException {
            if (closed) throw new IOException("ACP input closed");
            if (value == '\n') { if (line.size() > 0) { String s = new String(line.toByteArray(), StandardCharsets.UTF_8); line.reset(); try { receive(Json.parse(s)); } catch (JsonParseException e) { throw new IOException("Invalid ACP JSON", e); } } }
            else { if (line.size() >= 32 * 1024 * 1024) throw new IOException("ACP request exceeds limit"); line.write(value); }
        }
        @Override public synchronized void write(byte[] b, int off, int len) throws IOException { for (int i = off; i < off + len; i++) write(b[i]); }
        @Override public synchronized void close() { if (!closed) { closed = true; line.reset(); shutdown(false); } }
    }
    /** Bounded stream with backpressure and explicit EOF; avoids PipedInputStream's dead-writer semantics. */
    private static final class ByteQueue extends InputStream {
        final BlockingQueue<byte[]> chunks = new ArrayBlockingQueue<>(128);
        final byte[] eof = new byte[0]; volatile boolean closed, finished;
        byte[] current; int position;
        void put(byte[] bytes) throws IOException {
            try {
                for (int offset = 0; offset < bytes.length; offset += 8192) {
                    byte[] chunk = Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + 8192)); boolean accepted = false;
                    while (!closed && !finished) if (chunks.offer(chunk, 100, TimeUnit.MILLISECONDS)) { accepted = true; break; }
                    if (!accepted) throw new IOException("ACP output closed");
                }
                return;
            }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("ACP output interrupted"); }
        }
        void finish() { finished = true; chunks.offer(eof); }
        @Override public int read() throws IOException {
            try {
                while (current == null || position == current.length) {
                    if (closed) return -1;
                    current = chunks.poll(100, TimeUnit.MILLISECONDS); position = 0;
                    if (current == eof || current == null && finished && chunks.isEmpty()) return -1;
                }
                return current[position++] & 255;
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("ACP read interrupted"); }
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0; int first = read(); if (first < 0) return -1; b[off] = (byte) first;
            int n = Math.min(len - 1, current.length - position); System.arraycopy(current, position, b, off + 1, n); position += n; return n + 1;
        }
        @Override public int available() { return current == null ? 0 : current.length - position; }
        @Override public void close() { closed = true; chunks.clear(); chunks.offer(eof); }
    }
}
