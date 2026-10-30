package com.mola.starweave.sdk;

import com.google.gson.JsonObject;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class StreamAcpTransportTest {
    @Test public void retainsTransformErrorAndDoesNotContaminateFollowingFrame() throws Exception {
        List<JsonObject> sent = new ArrayList<>();
        AgentConnection connection = new AgentConnection() {
            public void send(JsonObject frame) { sent.add(frame); }
            public JsonObject read() throws IOException { throw new EOFException(); }
            public boolean isAlive() { return true; }
            public void close() { }
        };
        StreamAcpTransport transport = new StreamAcpTransport("runtime", connection, message -> {
            if ("unsupported".equals(Wire.str(message, "method"))) throw new IllegalArgumentException("MCP transport is not supported");
            return message;
        });
        try {
            try {
                transport.output().write("{\"id\":1,\"method\":\"unsupported\"}\n".getBytes(StandardCharsets.UTF_8));
                fail("unsupported transport must fail");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("MCP transport is not supported"));
                assertTrue(expected.getCause() instanceof IllegalArgumentException);
            }
            assertTrue(sent.isEmpty());
            transport.output().write("{\"id\":2,\"method\":\"initialize\"}\n".getBytes(StandardCharsets.UTF_8));
            assertEquals(1, sent.size());
            assertEquals(2, sent.get(0).getAsJsonObject("message").get("id").getAsInt());
        } finally { transport.close(); }
    }
}
