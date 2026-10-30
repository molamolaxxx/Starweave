package com.mola.cmd.proxy.app.acp.remote;

import com.google.gson.*;
import com.mola.starweave.sdk.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** One logical connection survives replacement of its physical WebSocket. */
final class ResumableBrowserConnection implements AgentConnection {
    private final BlockingQueue<JsonObject> incoming = new ArrayBlockingQueue<>(256);
    private final NavigableMap<Long, JsonObject> pending = new TreeMap<>();
    private final JsonObject registration;
    private final Runnable removed;
    private RemoteBrowserServer.BrowserConnection socket;
    private long sent, received, outgoingBytes, incomingBytes, detachedAt, transmitted, acknowledged;
    private volatile boolean closed;
    ResumableBrowserConnection(JsonObject hello, Runnable removed) {
        registration = hello.deepCopy(); registration.remove("resumeReceived");
        this.removed = removed;
        incoming.add(hello.deepCopy());
        incomingBytes = bytes(hello);
    }
    private static int bytes(JsonObject frame) { return frame.toString().getBytes(StandardCharsets.UTF_8).length; }
    synchronized boolean matches(JsonObject hello) { JsonObject copy = hello.deepCopy(); copy.remove("resumeReceived"); return registration.equals(copy); }
    synchronized void attach(RemoteBrowserServer.BrowserConnection next, JsonObject hello, boolean resumed) throws IOException {
        if (!isAlive()) throw new IOException("Browser resume expired");
        acknowledge(hello.get("resumeReceived").getAsLong());
        RemoteBrowserServer.BrowserConnection old = socket; socket = next; detachedAt = 0; transmitted = acknowledged;
        next.send(ready(resumed));
        flush();
        if (old != null && old != next) old.close();
        notifyAll();
    }
    private JsonObject ready(boolean resumed) { JsonObject f = new JsonObject(); f.addProperty("type", "resume_ready"); f.addProperty("received", received); f.addProperty("resumed", resumed); return f; }
    synchronized void detach(RemoteBrowserServer.BrowserConnection source, String reason) {
        if (socket != source || closed) return;
        socket = null; detachedAt = System.nanoTime(); notifyAll();
        org.slf4j.LoggerFactory.getLogger(getClass()).info("Browser connection suspended: name={}, reason={}, pendingFrames={}, pendingBytes={}", Wire.str(registration, "name"), reason, pending.size(), outgoingBytes);
    }
    synchronized void receive(RemoteBrowserServer.BrowserConnection source, JsonObject frame) throws IOException {
        if (source != socket || closed) return;
        String type = Wire.str(frame, "type");
        long seq = frame.has("seq") ? frame.get("seq").getAsLong() : -1;
        if ("resume_ack".equals(type)) { acknowledge(seq); flush(); return; }
        if (!"resume_frame".equals(type) || seq < 1 || seq > received + 1) throw new IOException("Invalid browser resume sequence");
        if (seq == received + 1) {
            JsonObject payload = frame.getAsJsonObject("payload"); int size = bytes(payload);
            if (incomingBytes + size > 64L * 1024 * 1024 || !incoming.offer(payload.deepCopy())) throw new IOException("Browser incoming buffer full");
            incomingBytes += size; received = seq;
        }
        JsonObject ack = new JsonObject(); ack.addProperty("type", "resume_ack"); ack.addProperty("seq", received); socket.send(ack);
    }
    private void acknowledge(long seq) throws IOException {
        if (seq < 0 || seq > sent) throw new IOException("Invalid browser resume acknowledgement");
        acknowledged = Math.max(acknowledged, seq);
        Iterator<Map.Entry<Long, JsonObject>> it = pending.entrySet().iterator();
        while (it.hasNext()) { Map.Entry<Long, JsonObject> entry = it.next(); if (entry.getKey() > seq) break; outgoingBytes -= bytes(entry.getValue()); it.remove(); }
    }
    public synchronized void send(JsonObject payload) throws IOException {
        if (!isAlive()) throw new EOFException("Browser resume expired");
        JsonObject frame = new JsonObject(); frame.addProperty("type", "resume_frame"); frame.addProperty("seq", sent + 1); frame.add("payload", payload.deepCopy());
        int size = bytes(frame);
        if (size > Wire.MAX_FRAME || outgoingBytes + size > 64L * 1024 * 1024 || pending.size() >= 8192) { close(); throw new IOException("Browser resume buffer full"); }
        sent++; pending.put(sent, frame); outgoingBytes += size;
        flush();
    }
    private void flush() {
        if (socket == null) return;
        RemoteBrowserServer.BrowserConnection current = socket;
        try {
            for (Map.Entry<Long, JsonObject> entry : pending.entrySet()) {
                if (entry.getKey() <= transmitted) continue;
                if (entry.getKey() > acknowledged + 32) break;
                current.send(entry.getValue()); transmitted = entry.getKey();
            }
        } catch (IOException e) { detach(current, "send failed"); current.close(); }
    }
    public JsonObject read() throws IOException {
        try {
            while (isAlive()) {
                JsonObject frame = incoming.poll(1, TimeUnit.SECONDS);
                if (frame != null) { synchronized (this) { incomingBytes -= bytes(frame); } return frame; }
            }
            throw new EOFException("Browser resume expired");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
    }
    public synchronized boolean isAlive() { return !closed && (detachedAt == 0 || System.nanoTime() - detachedAt < TimeUnit.SECONDS.toNanos(60)); }
    public synchronized void close() {
        if (closed) return; closed = true;
        if (socket != null) socket.close(); socket = null; pending.clear(); incoming.clear(); removed.run(); notifyAll();
    }
}
