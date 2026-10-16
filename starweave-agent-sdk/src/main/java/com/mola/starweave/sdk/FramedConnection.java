package com.mola.starweave.sdk;

import com.google.gson.JsonObject;
import java.io.*;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded writer keeps socket I/O out of ACP and heartbeat threads. */
public final class FramedConnection implements Closeable {
    private final Socket socket;
    private final BlockingQueue<JsonObject> control = new ArrayBlockingQueue<>(64), data = new ArrayBlockingQueue<>(128);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicLong queuedBytes = new java.util.concurrent.atomic.AtomicLong();
    public FramedConnection(Socket socket) throws IOException {
        this.socket = socket; socket.setTcpNoDelay(true); socket.setKeepAlive(true);
        Thread writer = daemon("writer", () -> {
            try {
                while (!closed.get()) {
                    JsonObject f = control.poll(); if (f == null) f = data.poll(100, TimeUnit.MILLISECONDS);
                    if (f != null) { try { Wire.write(socket.getOutputStream(), f); } finally { queuedBytes.addAndGet(-f.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length); } }
                }
            } catch (Exception e) { close(); }
        });
    }
    public void send(JsonObject f) throws IOException {
        boolean acp = java.util.Arrays.asList("acp", "closed", "runtime_error").contains(Wire.str(f, "type"));
        int bytes = f.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes > Wire.MAX_FRAME || queuedBytes.addAndGet(bytes) > 64L * 1024 * 1024 || closed.get() || !(acp ? data : control).offer(f.deepCopy())) { close(); throw new IOException("Remote ACP connection closed or queue full"); }
    }
    public JsonObject read() throws IOException { return Wire.read(socket.getInputStream()); }
    public boolean isAlive() { return !closed.get(); }
    public void close() { if (closed.compareAndSet(false, true)) { try { socket.close(); } catch (IOException ignored) { } control.clear(); data.clear(); } }
    public static Thread daemon(String name, Runnable action) { Thread t = new Thread(action, "starcore-" + name); t.setDaemon(true); t.start(); return t; }
}
