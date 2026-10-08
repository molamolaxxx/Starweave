package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.agent.CodexAcpProvider;
import com.mola.cmd.proxy.app.acp.acpclient.agent.KiroCliAgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class AcpClientCompactionProtocolTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void negotiatesCompactionOnlyForSupportedProviders() throws Exception {
        HandshakeClient codex = new HandshakeClient(new CodexAcpProvider());
        codex.initialize();
        assertTrue(codex.params.getAsJsonObject("clientCapabilities")
                .getAsJsonObject("session").has("compaction"));
        HandshakeClient kiro = new HandshakeClient(new KiroCliAgentProvider());
        kiro.initialize();
        assertFalse(kiro.params.getAsJsonObject("clientCapabilities")
                .getAsJsonObject("session").has("compaction"));
    }

    @Test public void dedicatedUpdatesPublishOneCardAndReinjectOnlyTheNextPrompt() throws Exception {
        AcpClientIdentity identity = AcpClientIdentity.main("compact-test", "Robot", "Robot");
        ConversationHistoryManager history = new ConversationHistoryManager(identity,
                temporary.newFolder().toPath());
        AcpClient client = new AcpClient(new CodexAcpProvider(),
                temporary.newFolder().getAbsolutePath(), identity, null, history);
        client.sessionId = "compact-session";
        AtomicInteger cards = new AtomicInteger();
        StringBuilder replies = new StringBuilder();
        AcpResponseListener listener = new AcpResponseListener() {
            public void onMessage(String text) { replies.append(text); }
            public void onToolCall(String id, String title, String status, JsonObject update) {
                fail("compaction must not publish a generic tool card");
            }
            public void onComplete(String text) { }
            public void onError(Exception error) { throw new AssertionError(error); }
            public void onCompactionEvent(String type, String provider) {
                assertEquals("COMPACTION_COMPLETED", type);
                cards.incrementAndGet();
            }
        };
        assertTrue(prompt(client, listener, "")); // first turn
        String updates = update("compaction_update", "in_progress")
                + "{\"method\":\"session/update\",\"params\":{\"update\":{"
                + "\"sessionUpdate\":\"compaction_summary_chunk\",\"compactionId\":\"c1\","
                + "\"content\":{\"type\":\"text\",\"text\":\"private summary\"}}}}\n"
                + update("compaction_update", "completed")
                + update("compaction_update", "completed")
                + "{\"method\":\"session/update\",\"params\":{\"update\":{"
                + "\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\","
                + "\"text\":\"Context compacted.\"}}}}\n";
        assertFalse(prompt(client, listener, updates)); // compaction happens during this turn
        assertEquals(1, cards.get());
        assertEquals("", replies.toString());
        assertTrue(prompt(client, listener, "")); // re-injection
        assertFalse(prompt(client, listener, "")); // consumed once
        assertFalse(prompt(client, listener, update("compaction_update", "in_progress")
                + update("compaction_update", "failed")));
        assertFalse(prompt(client, listener, ""));
        assertFalse(prompt(client, listener, update("compaction_update", "cancelled")));
        assertFalse(prompt(client, listener, ""));
        assertEquals(1, cards.get());
        // No compaction summaries or synthetic tool results enter conversation history.
        history.getFullHistory(client.sessionId).forEach(message -> {
            assertFalse(String.valueOf(message.getContent()).contains("private summary"));
            assertNull(message.getToolCallId());
        });
    }

    private boolean prompt(AcpClient client, AcpResponseListener listener, String updates) throws Exception {
        StringWriter output = new StringWriter();
        CountDownLatch written = new CountDownLatch(1);
        client.writer = new BufferedWriter(output) {
            @Override public void flush() throws IOException {
                super.flush();
                written.countDown();
            }
        };
        String id = String.valueOf(client.idCounter.get());
        client.reader = new BufferedReader(new StringReader(updates
                + "{\"id\":\"" + id + "\",\"result\":{\"stopReason\":\"end_turn\"}}\n"));
        Method send = AcpClient.class.getDeclaredMethod("sendPrompt", String.class,
                Collection.class, AcpResponseListener.class);
        send.setAccessible(true);
        send.invoke(client, "test input", Collections.emptyList(), listener);
        assertTrue("prompt write timed out", written.await(5, TimeUnit.SECONDS));
        String request = output.toString();
        assertFalse("prompt was not written", request.isEmpty());
        return JsonParser.parseString(request.trim()).getAsJsonObject()
                .getAsJsonObject("params").getAsJsonArray("prompt")
                .get(0).getAsJsonObject().get("text").getAsString().contains("<acp-harness>");
    }

    private String update(String type, String status) {
        return "{\"method\":\"session/update\",\"params\":{\"update\":{"
                + "\"sessionUpdate\":\"" + type + "\",\"compactionId\":\"c1\","
                + "\"status\":\"" + status + "\"}}}\n";
    }

    private static final class HandshakeClient extends AbstractAcpClient {
        JsonObject params;
        HandshakeClient(AgentProvider provider) { super(provider, ".", "handshake-test"); }
        @Override protected void createSession() { }
        @Override protected JsonObject sendRequest(String method, JsonObject params) {
            assertEquals("initialize", method);
            this.params = params;
            JsonObject response = new JsonObject();
            response.add("result", new JsonObject());
            return response;
        }
    }
}
