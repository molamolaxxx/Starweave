package com.mola.cmd.proxy.app.acp.registry;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.registry.model.RegistryConfig;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class RemoteEnvironmentRegistryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void registrationIsIdempotentAndRequiresVerificationBeforeOnline() throws Exception {
        AtomicLong clock = new AtomicLong(100_000);
        RegistryConfigStore store = new RegistryConfigStore(temporary.newFolder().toPath());
        RemoteEnvironmentRegistry registry = new RemoteEnvironmentRegistry(store, clock::get);
        RemoteEnvironmentRegistry.Entry first = registry.register("node-one", "source", "电脑");
        assertFalse(registry.list().get(0).online);
        assertEquals("new registrations must be probed before ever verified", 1, registry.candidates().size());
        assertEquals(first.id, registry.register("node-one", "source", "电脑").id);
        try { registry.resolve(first.id); fail(); } catch (IllegalStateException expected) { }
        registry.verified(first.id, first.lease, true);
        assertEquals(first.port, registry.resolve(first.id));
        assertTrue(registry.list().get(0).remote);
        assertEquals("source", registry.list().get(0).sourceInstanceId);
        clock.addAndGet(RemoteEnvironmentRegistry.LEASE_MILLIS);
        assertFalse(registry.list().get(0).online);
        RemoteEnvironmentRegistry.Entry next = registry.register("node-one", "source", "电脑");
        assertEquals(first.id, next.id); assertNotEquals(first.lease, next.lease);
        registry.verified(next.id, first.lease, true);
        assertFalse(registry.list().get(0).online);
        registry.verified(next.id, next.lease, true);
        registry.verified(next.id, first.lease, false);
        assertTrue(registry.list().get(0).online);
        RemoteEnvironmentRegistry recovered = new RemoteEnvironmentRegistry(store, clock::get);
        assertEquals(first.id, recovered.list().get(0).instanceId);
        assertFalse(recovered.list().get(0).online);
    }
    @Test public void rejectsInvalidLeasesAndArbitraryProxyPorts() throws Exception {
        RemoteEnvironmentRegistry registry = new RemoteEnvironmentRegistry(new RegistryConfigStore(temporary.newFolder().toPath()));
        RemoteEnvironmentRegistry.Entry entry = registry.register("node", "source", "电脑");
        try { registry.heartbeat(entry.id, "bad"); fail(); } catch (IllegalArgumentException expected) { }
        assertTrue(registry.tunnelAllowed(entry.id, entry.lease, entry.port, null));
        assertFalse(registry.tunnelAllowed(entry.id, entry.lease, 22, null));
        assertFalse(registry.tunnelAllowed(entry.id, "wrong", entry.port, null));
        registry.unregister(entry.id, entry.lease);
        assertFalse(registry.tunnelAllowed(entry.id, entry.lease, entry.port, null));
        assertFalse(registry.list().get(0).online);
    }
    @Test public void teamCallerIdentityRequiresOnlineCurrentLeaseAndExpires() throws Exception {
        AtomicLong clock = new AtomicLong(100_000);
        RemoteEnvironmentRegistry registry = new RemoteEnvironmentRegistry(new RegistryConfigStore(temporary.newFolder().toPath()), clock::get);
        RemoteEnvironmentRegistry.Entry entry = registry.register("node", "actual-source", "电脑");
        try { registry.authorizeParticipant(entry.id, entry.lease); fail(); } catch (IllegalArgumentException expected) { }
        registry.verified(entry.id, entry.lease, true);
        assertEquals("actual-source", registry.authorizeParticipant(entry.id, entry.lease));
        try { registry.authorizeParticipant(entry.id, "forged"); fail(); } catch (IllegalArgumentException expected) { }
        clock.addAndGet(RemoteEnvironmentRegistry.LEASE_MILLIS);
        try { registry.authorizeParticipant(entry.id, entry.lease); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void oldConnectionCloseAndProbeCannotInvalidateReplacement() throws Exception {
        RemoteEnvironmentRegistry registry = new RemoteEnvironmentRegistry(new RegistryConfigStore(temporary.newFolder().toPath()));
        RemoteEnvironmentRegistry.Entry entry = registry.register("node", "source", "电脑");
        registry.tunnelConnected(entry.id, entry.lease, "old");
        registry.verified(entry.id, entry.lease, "old", true);
        registry.tunnelConnected(entry.id, entry.lease, "new");
        assertFalse(registry.tunnelAllowed(entry.id, entry.lease, entry.port, "old"));
        registry.verified(entry.id, entry.lease, "new", true);
        registry.verified(entry.id, entry.lease, "old", false);
        registry.tunnelDisconnected(entry.id, entry.lease, "old");
        assertTrue(registry.list().get(0).online);
        registry.tunnelDisconnected(entry.id, entry.lease, "new");
        registry.verified(entry.id, entry.lease, "new", true);
        assertFalse(registry.list().get(0).online);
    }
    @Test public void validatesCenterUrlAndLoadsLegacySettingsWithoutCredentials() throws Exception {
        assertEquals("http://192.168.0.1:10528", RegistryConfig.normalizeUrl("192.168.0.1:10528"));
        assertEquals("https://example.com:443", RegistryConfig.normalizeUrl("https://example.com:443/"));
        for (String url : new String[]{"file:///etc/passwd", "http://user:pass@host", "http://host/path", "http://host?x=y", "http://host:0"}) {
            try { RegistryConfig.normalizeUrl(url); fail(url); } catch (IllegalArgumentException expected) { }
        }
        RegistryConfigStore store = new RegistryConfigStore(temporary.newFolder().toPath());
        RegistryConfig config = new RegistryConfig(); config.clientEnabled = true; config.centerUrl = "http://localhost:10528";
        JSONObject legacy = (JSONObject) JSON.toJSON(config);
        legacy.put("serverCredential", "old-server-secret"); legacy.put("clientCredential", "old-client-secret");
        store.write("config.json", legacy.toJSONString());
        RegistryConfig restored = store.load(); assertEquals(config.nodeId, restored.nodeId); assertTrue(restored.clientEnabled);
        store.save(restored);
        JSONObject saved = JSON.parseObject(new String(java.nio.file.Files.readAllBytes(store.directory().resolve("config.json")), java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(saved.containsKey("serverCredential")); assertFalse(saved.containsKey("clientCredential"));
    }
}
