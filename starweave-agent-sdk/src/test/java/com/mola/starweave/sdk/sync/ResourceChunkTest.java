package com.mola.starweave.sdk.sync;
import com.google.gson.*;
import com.mola.starweave.sdk.*;
import org.junit.Test;
import java.net.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
public class ResourceChunkTest {
    @Test(timeout=10000) public void observationCancellationCannotOvertakeChunkedExecution() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
             Socket outgoing = new Socket("127.0.0.1", listener.getLocalPort()); Socket incoming = listener.accept();
             FramedConnection sender = new FramedConnection(outgoing); FramedConnection receiver = new FramedConnection(incoming)) {
            outgoing.setSendBufferSize(1024); incoming.setSoTimeout(5000);
            JsonObject request = Wire.frame("observation_execute", null); request.addProperty("requestId", "one");
            request.addProperty("script", new String(new char[600000]).replace('\0', 'x'));
            JsonObject cancel = Wire.frame("observation_cancel", null); cancel.addProperty("requestId", "one");
            sender.send(request); sender.send(cancel);
            assertEquals(request, receiver.read());
            assertEquals(cancel, receiver.read());
        }
    }
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
