package com.mola.cmd.proxy.app.acp.registry.tunnel;

import com.alibaba.fastjson.JSONObject;
import io.netty.channel.Channel;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded in-memory replay window for one ordered, pipelined TLS connection. */
final class RealtimePeer {
    static final int MAX_FRAME = 4 * 1024 * 1024;
    private static final int MAX_PENDING = 2048, MAX_BYTES = 32 * 1024 * 1024, WINDOW = 64;
    private final String epoch = UUID.randomUUID().toString();
    private final LinkedHashMap<Long, byte[]> pending = new LinkedHashMap<>();
    private Channel channel;
    private long sequence, sent, acknowledged, received;
    private String receivedEpoch;
    private int bytes;
    private boolean closed;
    private final Object receiveLock = new Object();

    synchronized boolean offer(JSONObject event) {
        if (closed || pending.size() >= MAX_PENDING) return false;
        JSONObject frame = new JSONObject();
        frame.put("version", 1); frame.put("type", "EVENT"); frame.put("epoch", epoch);
        frame.put("sequence", sequence + 1); frame.put("payload", event);
        byte[] encoded = frame.toJSONString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length + 4 > MAX_FRAME || bytes + encoded.length > MAX_BYTES) return false;
        pending.put(++sequence, encoded); bytes += encoded.length;
        requestDrain(); return true;
    }
    synchronized void attach(Channel next) {
        if (closed) { next.close(); return; }
        if (channel != null && channel != next) channel.close();
        channel = next; sent = acknowledged; requestDrain();
    }
    synchronized void detach(Channel old) { if (channel == old) channel = null; }
    synchronized void acknowledge(JSONObject frame) {
        if (!epoch.equals(frame.getString("epoch"))) return;
        long seq = frame.getLongValue("sequence");
        if (seq <= acknowledged || seq > sent) return;
        Iterator<Map.Entry<Long, byte[]>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, byte[]> entry = iterator.next();
            if (entry.getKey() > seq) break;
            bytes -= entry.getValue().length; iterator.remove();
        }
        acknowledged = seq; requestDrain();
    }
    boolean receive(JSONObject frame, java.util.function.Predicate<JSONObject> receiver) {
        synchronized (receiveLock) {
            String incomingEpoch = frame.getString("epoch");
            long seq = frame.getLongValue("sequence");
            if (incomingEpoch == null || seq <= 0) throw new IllegalArgumentException("invalid realtime sequence");
            if (!incomingEpoch.equals(receivedEpoch)) { receivedEpoch = incomingEpoch; received = 0; }
            if (seq <= received) return true;
            // A new receiver may reconnect after its process restarted; source history remains authoritative.
            if (received != 0 && seq != received + 1) throw new IllegalArgumentException("realtime sequence gap");
            if (!receiver.test(frame.getJSONObject("payload"))) return false;
            received = seq; return true;
        }
    }
    synchronized void writable() { requestDrain(); }
    synchronized boolean connected() { return !closed && channel != null && channel.isActive(); }
    private void requestDrain() {
        Channel target = channel;
        if (target != null && target.isActive() && !target.eventLoop().isShuttingDown()) {
            try { target.eventLoop().execute(() -> drain(target)); }
            catch (java.util.concurrent.RejectedExecutionException stopped) { /* Retain for reconnect. */ }
        }
    }
    private synchronized void drain(Channel target) {
        if (channel != target || !target.isActive()) return;
        boolean wrote = false;
        for (Map.Entry<Long, byte[]> entry : pending.entrySet()) {
            if (entry.getKey() <= sent) continue;
            if (!target.isWritable() || sent - acknowledged >= WINDOW) break;
            byte[] data = entry.getValue(); sent = entry.getKey(); wrote = true;
            target.write(target.alloc().buffer(data.length + 4).writeInt(data.length).writeBytes(data))
                    .addListener(f -> { if (!f.isSuccess()) target.close(); });
        }
        if (wrote) target.flush();
    }
    synchronized void close() { closed = true; if (channel != null) channel.close(); channel = null; pending.clear(); bytes = 0; }
}
