package com.mola.cmd.proxy.app.acp.acpclient.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CodexAcpProviderControlArtifactTest {

    @Test
    public void usesThePreparedManagedRuntimeAndKeepsTheGlobalFallback() {
        CodexAcpProvider provider = new CodexAcpProvider();

        assertEquals("codex-acp", provider.getCommand());
        assertArrayEquals(new String[0], provider.getArgs());
        assertTrue(provider.hasFallbackCommand());
        assertEquals("codex-acp", provider.getFallbackCommand());
    }

    @Test
    public void forcesFullAccessAsTheInitialCodexAcpMode() {
        CodexAcpProvider provider = new CodexAcpProvider();

        Map<String, String> configuredEnv = provider.getExtraEnv(new AcpRobotParam());
        Map<String, String> defaultEnv = provider.getExtraEnv(null);

        assertEquals("agent-full-access", configuredEnv.get("INITIAL_AGENT_MODE"));
        assertEquals("agent-full-access", defaultEnv.get("INITIAL_AGENT_MODE"));
    }

    @Test
    public void recognizesProviderControlMessagesOnly() {
        CodexAcpProvider provider = new CodexAcpProvider();

        assertTrue(provider.isAgentMessageControlArtifact("*Conversation interrupted*"));
        assertTrue(provider.isAgentMessageControlArtifact("\n*Conversation interrupted*\n"));
        assertTrue(provider.isAgentMessageControlArtifact(
                "*Context compacted to fit the model's context window.*"));
        assertTrue(provider.isAgentMessageControlArtifact("Context compacted."));
        assertFalse(provider.isAgentMessageControlArtifact("Conversation interrupted"));
        assertFalse(provider.isAgentMessageControlArtifact("normal response"));
        assertFalse(provider.isAgentMessageControlArtifact(null));
    }

    @Test
    public void recognizesStructuredContextCompactionLifecycle() {
        CodexAcpProvider provider = new CodexAcpProvider();

        assertEquals(AgentProvider.CompactionSignal.STARTED,
                provider.detectCompactionSignal(compactionUpdate(
                        "tool_call", "in_progress", true)));
        assertEquals(AgentProvider.CompactionSignal.COMPLETED,
                provider.detectCompactionSignal(compactionUpdate(
                        "tool_call_update", "completed", true)));
        assertEquals(AgentProvider.CompactionSignal.COMPLETED,
                provider.detectCompactionSignal(compactionUpdate(
                        "tool_call", "completed", true)));
        assertEquals(AgentProvider.CompactionSignal.FAILED,
                provider.detectCompactionSignal(compactionUpdate(
                        "tool_call_update", "failed", true)));
        assertEquals(AgentProvider.CompactionSignal.FAILED,
                provider.detectCompactionSignal(compactionUpdate(
                        "tool_call_update", "cancelled", true)));
    }

    @Test
    public void recognizesDedicatedCompactionLifecycleWithoutToolMetadata() {
        CodexAcpProvider provider = new CodexAcpProvider();
        assertTrue(provider.supportsCompactionUpdates());
        String[] statuses = {"in_progress", "completed", "failed", "cancelled", "future_status"};
        AgentProvider.CompactionSignal[] signals = {
                AgentProvider.CompactionSignal.STARTED,
                AgentProvider.CompactionSignal.COMPLETED,
                AgentProvider.CompactionSignal.FAILED,
                AgentProvider.CompactionSignal.FAILED,
                AgentProvider.CompactionSignal.NONE};
        for (int i = 0; i < statuses.length; i++) {
            JsonObject message = compactionUpdate("compaction_update", statuses[i], false);
            JsonObject update = message.getAsJsonObject("params").getAsJsonObject("update");
            update.remove("toolCallId");
            update.remove("kind");
            update.remove("title");
            update.addProperty("compactionId", "compact-1");
            assertEquals(statuses[i], signals[i], provider.detectCompactionSignal(message));
        }
        assertEquals(AgentProvider.CompactionSignal.NONE,
                provider.detectCompactionSignal(compactionUpdate(
                        "compaction_summary_chunk", "completed", false)));
    }

    @Test
    public void doesNotTreatDisplayTitleAsCompactionContract() {
        CodexAcpProvider provider = new CodexAcpProvider();

        assertEquals(AgentProvider.CompactionSignal.NONE,
                provider.detectCompactionSignal(compactionUpdate(
                        "tool_call", "in_progress", false)));
    }

    @Test
    public void retainsLegacyContextCompactedMarker() {
        CodexAcpProvider provider = new CodexAcpProvider();
        JsonObject message = JsonParser.parseString("{"
                + "\"method\":\"session/update\","
                + "\"params\":{\"update\":{"
                + "\"sessionUpdate\":\"agent_message_chunk\","
                + "\"content\":{\"type\":\"text\","
                + "\"text\":\"*Context compacted to fit the model's context window.*\"}"
                + "}}}").getAsJsonObject();

        assertEquals(AgentProvider.CompactionSignal.COMPLETED,
                provider.detectCompactionSignal(message));
    }

    private JsonObject compactionUpdate(String updateType, String status,
                                        boolean includeMetadata) {
        String metadata = includeMetadata
                ? ",\"_meta\":{\"contextCompaction\":{\"version\":1}}" : "";
        return JsonParser.parseString("{"
                + "\"method\":\"session/update\","
                + "\"params\":{\"update\":{"
                + "\"sessionUpdate\":\"" + updateType + "\","
                + "\"toolCallId\":\"compact-1\","
                + "\"kind\":\"think\","
                + "\"title\":\"Compact conversation\","
                + "\"status\":\"" + status + "\""
                + metadata
                + "}}}").getAsJsonObject();
    }
}
