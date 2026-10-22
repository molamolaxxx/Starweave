package com.mola.starweave.sdk;

import com.google.gson.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent bounded queues; resource and observation chunks do not occupy heartbeat/ACP slots. */
public final class FramedConnection implements Closeable {
    private static final int CHUNK_BYTES = 32768;
    private final Socket socket;
    private final BlockingQueue<JsonObject> control = new ArrayBlockingQueue<>(64), data = new ArrayBlockingQueue<>(128), resources = new ArrayBlockingQueue<>(64), sessions = new ArrayBlockingQueue<>(64);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicLong queuedBytes = new java.util.concurrent.atomic.AtomicLong();
    private final Map<String, Assembly> assemblies = new HashMap<>(); // Single reader owns this map.
    public FramedConnection(Socket socket) throws IOException {
        this.socket = socket; socket.setTcpNoDelay(true); socket.setKeepAlive(true);
        daemon("writer", () -> {
            try {
                boolean sessionTurn = false;
                while (!closed.get()) {
                    JsonObject f = control.poll(); if (f == null) f = data.poll(); if (f == null) f = (sessionTurn ? sessions : resources).poll(); if (f == null) f = (sessionTurn ? resources : sessions).poll(); sessionTurn = !sessionTurn; if (f == null) f = control.poll(50, TimeUnit.MILLISECONDS);
                    if (f != null) { try { Wire.write(socket.getOutputStream(), f); } finally { queuedBytes.addAndGet(-f.toString().getBytes(StandardCharsets.UTF_8).length); } }
                }
            } catch (Exception e) { close(); }
        });
    }
    public void send(JsonObject f) throws IOException {
        String type = Wire.str(f, "type");
        // Cancellation shares FIFO with execution so it cannot overtake a chunked request.
        boolean session = type.equals("sessions_request") || type.equals("sessions_response");
        boolean resource = session || chunkedType(type);
        BlockingQueue<JsonObject> bulk = session ? sessions : resources;
        byte[] bytes = f.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Wire.MAX_FRAME) throw new IOException("Remote ACP frame exceeds limit");
        if (resource && bytes.length > CHUNK_BYTES) {
            String transfer = UUID.randomUUID().toString(); int count = (bytes.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
            for (int offset = 0, index = 0; offset < bytes.length; offset += CHUNK_BYTES, index++) {
                JsonObject part = Wire.frame("resource_chunk", null); part.addProperty("transfer", transfer); part.addProperty("index", index); part.addProperty("count", count);
                part.addProperty("data", Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + CHUNK_BYTES))));
                enqueue(part, bulk, true);
            }
        } else enqueue(f.deepCopy(), resource ? bulk : Arrays.asList("acp", "closed", "runtime_error").contains(type) ? data : control, resource);
    }
    private void enqueue(JsonObject frame, BlockingQueue<JsonObject> queue, boolean wait) throws IOException {
        int bytes = frame.toString().getBytes(StandardCharsets.UTF_8).length;
        try {
            if (closed.get() || queuedBytes.addAndGet(bytes) > 64L * 1024 * 1024 || !(wait ? queue.offer(frame, 10, TimeUnit.SECONDS) : queue.offer(frame))) { close(); throw new IOException("Remote ACP connection closed or queue full"); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); close(); throw new IOException("Resource transmission interrupted", e); }
    }
    public JsonObject read() throws IOException {
        for (;;) {
            JsonObject frame = Wire.read(socket.getInputStream());
            if (!Wire.str(frame, "type").equals("resource_chunk")) return frame;
            String id = Wire.str(frame, "transfer"); int count = frame.has("count") ? frame.get("count").getAsInt() : 0, index = frame.has("index") ? frame.get("index").getAsInt() : -1;
            if (id.length() > 64 || count < 1 || count > Wire.MAX_FRAME / CHUNK_BYTES || index < 0 || Wire.str(frame, "data").length() > CHUNK_BYTES * 2) throw new IOException("Invalid resource chunk");
            Assembly assembly = assemblies.get(id);
            if (assembly == null) {
                if (index != 0 || assemblies.size() >= 4) throw new IOException("Resource assembly limit exceeded");
                assembly = new Assembly(count); assemblies.put(id, assembly);
            }
            if (assembly.count != count || assembly.next++ != index || System.nanoTime() - assembly.started > TimeUnit.SECONDS.toNanos(30)) throw new IOException("Out of order or expired resource transfer");
            byte[] decoded;
            try { decoded = Base64.getDecoder().decode(Wire.str(frame, "data")); } catch (IllegalArgumentException e) { throw new IOException("Invalid resource chunk data", e); }
            if (decoded.length > CHUNK_BYTES || assembly.bytes.size() + decoded.length > Wire.MAX_FRAME) throw new IOException("Resource transfer too large");
            assembly.bytes.write(decoded);
            if (assembly.next == count) {
                assemblies.remove(id);
                try {
                    JsonObject value = JsonParser.parseString(new String(assembly.bytes.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
                    if (!chunkedType(Wire.str(value, "type"))) throw new IOException("Invalid chunked message type"); return value;
                } catch (JsonParseException e) { throw new IOException("Invalid resource message", e); }
            }
        }
    }
    private static boolean chunkedType(String type) { return Arrays.asList("resources_request", "resources_response", "sessions_request", "sessions_response", "observation_execute", "observation_result", "observation_cancel").contains(type); }
    public boolean isAlive() { return !closed.get(); }
    public void close() { if (closed.compareAndSet(false, true)) { try { socket.close(); } catch (IOException ignored) { } control.clear(); data.clear(); resources.clear(); sessions.clear(); } }
    private static final class Assembly { final int count; int next; final long started = System.nanoTime(); final ByteArrayOutputStream bytes = new ByteArrayOutputStream(); Assembly(int count) { this.count = count; } }
    public static Thread daemon(String name, Runnable action) { Thread t = new Thread(action, "starcore-" + name); t.setDaemon(true); t.start(); return t; }
}
