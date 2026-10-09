package com.mola.cmd.proxy.app.acp.acpclient.agent;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class EmbeddedPiConfigStoreTest {
    private JSONObject root(String name, String provider, String pi) {
        return JSON.parseObject("{\"robots\":[{\"name\":\"" + name + "\",\"model\":\"model\",\"agentProvider\":\"" + provider + "\",\"embeddedPi\":" + pi + "}]}");
    }
    private JSONObject pi(JSONObject root) { return root.getJSONArray("robots").getJSONObject(0).getJSONObject("embeddedPi"); }
    @Test public void newConfigurationGetsStableIdentityAndDefaults() {
        JSONObject root = root("Agent", "EMBEDDED_PI_ACP", "{\"apiKey\":\"test-key\"}");
        EmbeddedPiConfigStore.merge(root, new JSONObject(), "********");
        String id = pi(root).getString("stateId"); assertNotNull(id); assertEquals(128000, pi(root).getIntValue("contextWindow"));
        JSONObject second = JSON.parseObject(root.toJSONString()); pi(second).remove("stateId");
        EmbeddedPiConfigStore.merge(second, root, "********"); assertEquals(id, pi(second).getString("stateId"));
    }
    @Test public void maskedKeySurvivesRenameAndCopyGetsIndependentIdentity() {
        JSONObject old = root("Agent", "EMBEDDED_PI_ACP", "{\"stateId\":\"original\",\"apiKey\":\"secret\"}");
        JSONObject renamed = root("Renamed", "EMBEDDED_PI_ACP", "{\"stateId\":\"original\",\"apiKey\":\"********\"}");
        EmbeddedPiConfigStore.merge(renamed, old, "********"); assertEquals("secret", pi(renamed).getString("apiKey"));
        JSONObject copy = JSON.parseObject(renamed.toJSONString()); copy.getJSONArray("robots").getJSONObject(0).put("_piCopyFromStateId", "original");
        EmbeddedPiConfigStore.merge(copy, old, "********"); assertNotEquals("original", pi(copy).getString("stateId"));
        EmbeddedPiConfigStore.mask(copy, "********"); assertEquals("********", pi(copy).getString("apiKey"));
    }
    @Test public void legacyProviderIsPreservedAndNewApiCreationDefaultsToPi() {
        JSONObject old = JSON.parseObject("{\"robots\":[{\"name\":\"Legacy\"}]}"); JSONObject updated = JSON.parseObject(old.toJSONString());
        EmbeddedPiConfigStore.merge(updated, old, "********"); assertEquals("KIRO_CLI", updated.getJSONArray("robots").getJSONObject(0).getString("agentProvider"));
        JSONObject fresh = root("New", "", "{\"apiKey\":\"key\"}"); EmbeddedPiConfigStore.merge(fresh, old, "********");
        assertEquals("EMBEDDED_PI_ACP", fresh.getJSONArray("robots").getJSONObject(0).getString("agentProvider"));
    }
    @Test public void missingMaskedSecretAndDuplicateStateAreRejected() {
        JSONObject root = root("Agent", "EMBEDDED_PI_ACP", "{\"apiKey\":\"********\"}");
        try { EmbeddedPiConfigStore.merge(root, new JSONObject(), "********"); fail(); } catch (IllegalArgumentException expected) { }
        root = root("Agent", "EMBEDDED_PI_ACP", "{\"stateId\":\"shared\",\"apiKey\":\"key\"}");
        JSONObject duplicate = JSON.parseObject(root.getJSONArray("robots").getJSONObject(0).toJSONString()); duplicate.put("name", "Another"); root.getJSONArray("robots").add(duplicate);
        try { EmbeddedPiConfigStore.merge(root, new JSONObject(), "********"); fail(); } catch (IllegalArgumentException expected) { }
    }
}
