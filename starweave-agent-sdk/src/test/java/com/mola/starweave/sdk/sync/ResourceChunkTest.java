package com.mola.starweave.sdk.sync;
import com.google.gson.*;
import com.mola.starweave.sdk.*;
import org.junit.Test;
import java.net.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
public class ResourceChunkTest {
    @Test(timeout=10000) public void reassemblesLargeResourceWithControlMessagesInBetween() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 2, InetAddress.getLoopbackAddress()); Socket outgoing = new Socket("127.0.0.1", listener.getLocalPort()); Socket incoming = listener.accept();
             FramedConnection sender = new FramedConnection(outgoing); FramedConnection receiver = new FramedConnection(incoming)) {
            incoming.setSoTimeout(5000);
            String text = new String(new char[600000]).replace('\0', '字'); JsonObject frame = Wire.frame("resources_response", null); frame.addProperty("payload", text);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> writing = worker.submit(() -> { try { sender.send(frame); sender.send(Wire.frame("ping", null)); } catch (Exception e) { throw new RuntimeException(e); } });
                boolean resource = false, ping = false;
                while (!resource || !ping) { JsonObject value = receiver.read(); if (Wire.str(value, "type").equals("ping")) ping = true; else { assertEquals(text, value.get("payload").getAsString()); resource = true; } }
                writing.get(2, TimeUnit.SECONDS);
            } finally { worker.shutdownNow(); }
        }
    }
}
