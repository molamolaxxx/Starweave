package com.mola.cmd.proxy.app.acp.registry;

import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.registry.tunnel.*;
import io.netty.channel.Channel;
import org.junit.Test;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class NettyRealtimeTest {
    @Test(timeout = 20000) public void pipelinesBothDirectionsAndReplaysAfterDisconnectWithoutDuplicates() throws Exception {
        NettyTunnelProvider center = new NettyTunnelProvider(), client = new NettyTunnelProvider();
        List<Integer> uplink = Collections.synchronizedList(new ArrayList<>()), downlink = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch uploaded = new CountDownLatch(300), downloaded = new CountDownLatch(300);
        AtomicBoolean rejectOnce = new AtomicBoolean();
        int tunnel = port(), forwarded = port();
        center.realtimeReceiver((id, lease, event) -> {
            assertEquals("env", id); assertEquals("lease", lease);
            if (rejectOnce.compareAndSet(true, false)) return false;
            uplink.add(event.getIntValue("index")); uploaded.countDown(); return true;
        });
        client.realtimeReceiver((id, lease, event) -> { assertNull(id); downlink.add(event.getIntValue("index")); downloaded.countDown(); return true; });
        try {
            center.startServer(tunnel, "token", new TunnelProvider.Authorizer() {
                public boolean authorize(String id, String lease, int p, String run) { return "env".equals(id) && "lease".equals(lease) && p == forwarded; }
                public void connected(String id, String lease, String run) { }
                public void disconnected(String id, String lease, String run) { }
            });
            client.startClient("127.0.0.1", tunnel, "token", "env", "lease", forwarded, port(), center.serverCertificate());
            await(client::clientAlive);
            // Crossing the pipeline window cannot turn admission into per-event RPC waiting.
            long start = System.nanoTime();
            for (int i = 0; i < 150; i++) assertTrue(client.sendRealtime(null, event(i)));
            await(() -> uplink.size() == 150);
            for (int i = 0; i < 150; i++) assertTrue(center.sendRealtime("env", event(i)));
            await(() -> downlink.size() == 150);
            assertTrue("300 events must arrive continuously", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000);
            rejectOnce.set(true);
            for (int i = 150; i < 300; i++) assertTrue(client.sendRealtime(null, event(i)));
            assertTrue(uploaded.await(6, TimeUnit.SECONDS));
            // Interrupt the other direction while events are outstanding.
            java.lang.reflect.Field peers = NettyTunnelProvider.class.getDeclaredField("realtimePeers"); peers.setAccessible(true);
            Object peer = ((Map<?, ?>) peers.get(center)).get("env");
            java.lang.reflect.Field ch = peer.getClass().getDeclaredField("channel"); ch.setAccessible(true);
            ((Channel) ch.get(peer)).close().sync();
            for (int i = 150; i < 300; i++) assertTrue(center.sendRealtime("env", event(i)));
            assertTrue(downloaded.await(6, TimeUnit.SECONDS));
            assertEquals(300, uplink.size()); assertEquals(300, downlink.size());
            for (int i = 0; i < 300; i++) { assertEquals(i, uplink.get(i).intValue()); assertEquals(i, downlink.get(i).intValue()); }
        } finally { client.close(); center.close(); }
        assertFalse(client.sendRealtime(null, event(301)));
    }
    private static JSONObject event(int index) {
        JSONObject event = new JSONObject(); event.put("index", index);
        if (index == 0) { char[] text = new char[65536]; Arrays.fill(text, '文'); event.put("content", new String(text)); }
        return event;
    }
    private static int port() throws Exception { try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); } }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
}
