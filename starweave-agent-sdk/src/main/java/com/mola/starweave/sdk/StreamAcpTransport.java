package com.mola.starweave.sdk;

import com.google.gson.*;
import com.mola.starweave.agent.transport.AcpTransport;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One multiplexed ACP stream. Closing it does not close the shared socket. */
public final class StreamAcpTransport implements AcpTransport {
    public interface FileStager { java.util.Map<String, String> stage(java.util.Collection<String> paths) throws IOException; }
    private FileStager stager;
    public void setFileStager(FileStager value) { stager = value; }
    public java.util.Map<String, String> stageFiles(java.util.Collection<String> paths) throws IOException { return stager == null ? AcpTransport.super.stageFiles(paths) : stager.stage(paths); }
    private final String id;
    private final FramedConnection connection;
    private final java.util.function.UnaryOperator<JsonObject> outgoing;
    private final BlockingQueue<byte[]> incoming = new ArrayBlockingQueue<>(256);
    private final CountDownLatch exited = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final byte[] eof = new byte[0];
    private volatile String failure;
    private final InputStream input = new InputStream() {
        private byte[] current; private int offset;
        public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255; }
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            try {
                while (current == null || offset == current.length) {
                    if (closed.get() && incoming.isEmpty()) { if (failure != null) throw new IOException(failure); return -1; }
                    current = incoming.take(); offset = 0;
                    if (current == eof) { if (failure != null) throw new IOException(failure); return -1; }
                }
                int n = Math.min(len, current.length - offset); System.arraycopy(current, offset, b, off, n); offset += n; return n;
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
        }
        public void close() { StreamAcpTransport.this.close(); }
    };
    private final OutputStream output = new OutputStream() {
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        public synchronized void write(int value) throws IOException {
            if (!isAlive()) throw new IOException("Remote ACP runtime closed");
            if (value == '\n') {
                if (line.size() == 0) return;
                JsonObject f = Wire.frame("acp", id);
                try { f.add("message", outgoing.apply(JsonParser.parseString(new String(line.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject())); }
                catch (RuntimeException e) { throw new IOException("Invalid ACP message", e); }
                line.reset(); connection.send(f);
            } else { if (line.size() >= Wire.MAX_FRAME - 1024) throw new IOException("ACP message exceeds limit"); line.write(value); }
        }
        public synchronized void write(byte[] b, int off, int len) throws IOException { for (int i = off; i < off + len; i++) write(b[i]); }
        public void close() { StreamAcpTransport.this.close(); }
    };
    public StreamAcpTransport(String id, FramedConnection connection) { this(id, connection, message -> message); }
    public StreamAcpTransport(String id, FramedConnection connection, java.util.function.UnaryOperator<JsonObject> outgoing) { this.id = id; this.connection = connection; this.outgoing = outgoing; }
    public void accept(JsonObject message) throws IOException {
        if (closed.get()) return;
        byte[] bytes = (message.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        for (int off = 0; off < bytes.length; off += 8192) {
            byte[] chunk = java.util.Arrays.copyOfRange(bytes, off, Math.min(bytes.length, off + 8192));
            if (!incoming.offer(chunk)) { fail("Remote ACP reader queue full"); throw new IOException("Remote ACP reader queue full"); }
        }
    }
    public void fail(String message) { failure = message; finish(); }
    private void finish() { if (closed.compareAndSet(false, true)) { incoming.clear(); incoming.offer(eof); exited.countDown(); } }
    public void close() { if (!closed.get()) { try { connection.send(Wire.frame("close", id)); } catch (IOException ignored) { } finish(); } }
    public InputStream input() { return input; }
    public OutputStream output() { return output; }
    public boolean isAlive() { return !closed.get() && connection.isAlive(); }
    public boolean awaitExit(long timeout, TimeUnit unit) throws InterruptedException { return exited.await(timeout, unit); }
}
