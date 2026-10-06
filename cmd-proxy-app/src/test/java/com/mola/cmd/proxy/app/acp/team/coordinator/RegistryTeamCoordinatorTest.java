package com.mola.cmd.proxy.app.acp.team.coordinator;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.registry.RegistryManager;
import com.sun.net.httpserver.HttpServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.net.*;
import java.io.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class RegistryTeamCoordinatorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void realtimeAndDurableCallbacksShareMemberOrderWithoutBlockingAnotherMember() throws Exception {
        Path root = temporary.newFolder().toPath();
        RegistryManager registry = new RegistryManager(root.resolve("registry"), 12345, "fixture");
        CountDownLatch firstEntered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch otherDelivered = new CountDownLatch(1), completeDelivered = new CountDownLatch(1);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        RegistryTeamCoordinator service = new RegistryTeamCoordinator(registry, root.resolve("coordination"), (owner, event) -> {
            String id = event.getString("eventId");
            if ("first".equals(id)) {
                firstEntered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            }
            order.add(id);
            if ("other".equals(id)) otherDelivered.countDown();
            if ("end".equals(id)) completeDelivered.countDown();
        });
        try {
            assertTrue(service.enqueueClientProjection("user", projectionEvent("first", "m1", "MESSAGE_CHUNK"), false));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            assertTrue(service.enqueueClientProjection("user", projectionEvent("end", "m1", "MESSAGE_COMPLETE"), true));
            assertTrue(service.enqueueClientProjection("user", projectionEvent("other", "m2", "MESSAGE_CHUNK"), false));
            assertTrue(otherDelivered.await(5, TimeUnit.SECONDS));
            assertFalse(order.contains("end")); release.countDown();
            assertTrue(completeDelivered.await(5, TimeUnit.SECONDS));
            assertTrue(order.indexOf("first") < order.indexOf("end"));
            service.close();
            assertFalse(service.enqueueClientProjection("user", projectionEvent("closed", "m1", "MESSAGE_CHUNK"), false));
        } finally { release.countDown(); service.close(); registry.close(); }
    }
    @Test public void fullMessageSnapshotsDoNotDelayLegacyIncrementalCallbacks() throws Exception {
        Path root = temporary.newFolder().toPath();
        RegistryManager registry = new RegistryManager(root.resolve("registry"), 12345, "fixture");
        List<String> delivered = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch chunk = new CountDownLatch(1);
        RegistryTeamCoordinator service = new RegistryTeamCoordinator(registry, root.resolve("coordination"), (owner, event) -> {
            delivered.add(event.getString("type")); chunk.countDown();
        });
        try {
            assertTrue(service.enqueueClientProjection("user", projectionEvent("snapshot", "m1", "MESSAGE_UPDATED"), false));
            assertTrue(service.enqueueClientProjection("user", projectionEvent("chunk", "m1", "MESSAGE_CHUNK"), false));
            assertTrue(chunk.await(5, TimeUnit.SECONDS));
            assertEquals(Collections.singletonList("MESSAGE_CHUNK"), delivered);
        } finally { service.close(); registry.close(); }
    }
    private JSONObject projectionEvent(String id, String member, String type) {
        JSONObject event = MixedTeamCoordinator.object("eventId", id, "type", type);
        event.put("teamId", "team"); event.put("teamMemberId", member); return event;
    }
    @Test public void fullMemberQueueRejectsAdmissionWithoutBlockingProducer() throws Exception {
        Path root = temporary.newFolder().toPath();
        RegistryManager registry = new RegistryManager(root.resolve("registry"), 12345, "fixture");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        RegistryTeamCoordinator service = new RegistryTeamCoordinator(registry, root.resolve("coordination"), (owner, event) -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(service.enqueueClientProjection("user", projectionEvent("running", "m1", "MESSAGE_CHUNK"), false));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 256; i++)
                assertTrue(service.enqueueClientProjection("user", projectionEvent("queued-" + i, "m1", "MESSAGE_CHUNK"), false));
            assertFalse(service.enqueueClientProjection("user", projectionEvent("overflow", "m1", "MESSAGE_CHUNK"), false));
        } finally { release.countDown(); service.close(); registry.close(); }
    }
    @Test public void retiredOrSpoofedRealtimeFrameIsDiscardedWithoutBlockingFollowingTeams() throws Exception {
        Path root = temporary.newFolder().toPath();
        RegistryManager registry = new RegistryManager(root.resolve("registry"), 12345, "fixture");
        RegistryTeamCoordinator service = new RegistryTeamCoordinator(registry, root.resolve("coordination"));
        try {
            java.lang.reflect.Method receive = RegistryTeamCoordinator.class.getDeclaredMethod("receiveRealtime", String.class, JSONObject.class);
            receive.setAccessible(true);
            JSONObject event = MixedTeamCoordinator.object("eventId", "retired-event");
            event.put("teamId", "retired"); event.put("teamMemberId", "m1"); event.put("type", "MESSAGE_CHUNK");
            JSONObject frame = MixedTeamCoordinator.object("operation", "event", "payload", event);
            assertEquals(true, receive.invoke(service, "other-instance", frame));
            event.put("type", "TEAM_DELETED"); assertEquals(true, receive.invoke(service, "other-instance", frame));
            assertTrue(new CoordinationStore(root.resolve("coordination")).list("inbox").isEmpty());
        } finally { service.close(); registry.close(); }
    }

    @Test public void optionalMolaChatProjectionIsDurableWithoutBlockingCoordinator() throws Exception {
        Path root = temporary.newFolder().toPath();
        RegistryManager registry = new RegistryManager(root.resolve("registry"), 12345, "offline-client-fixture");
        CoordinationStore store = new CoordinationStore(root.resolve("coordination"));
        JSONObject binding = MixedTeamCoordinator.object("homeInstanceId", "offline-client-fixture");
        binding.put("ownerChatterId", "user"); binding.put("roster", JSON.parseArray("[{\"teamMemberId\":\"m1\"}]"));
        store.save("bindings", "team", binding);
        JSONObject event = MixedTeamCoordinator.object("eventId", "e1"); event.put("teamId", "team"); event.put("teamMemberId", "m1");
        RegistryTeamCoordinator service = new RegistryTeamCoordinator(registry, root.resolve("coordination"));
        try {
            service.project("offline-client-fixture", "user", event);
            service.project("offline-client-fixture", "user", event);
            assertEquals(1, store.list("projections").size());
            service.close();
            service = new RegistryTeamCoordinator(registry, root.resolve("coordination"));
            java.lang.reflect.Method drain = RegistryTeamCoordinator.class.getDeclaredMethod("projectClientsSafely");
            drain.setAccessible(true); drain.invoke(service);
            assertEquals("user", store.find("projections", "e1").getString("projectionOwner"));
            try { service.project("offline-client-fixture", "another", event); fail(); }
            catch (CoordinationException expected) { assertEquals("UNAUTHORIZED", expected.getCode()); }
        } finally { service.close(); registry.close(); }
    }

    @Test public void cookiesAndBrowserOriginNeverAuthorizeInstanceEndpoints() throws Exception {
        Path root = temporary.newFolder().toPath();
        RegistryManager registry = new RegistryManager(root.resolve("registry"), 12345, "fixture");
        RegistryTeamCoordinator service = new RegistryTeamCoordinator(registry, root.resolve("coordination"));
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext(RegistryTeamCoordinator.CENTER_PATH, service::handleCenter);
        http.createContext(RegistryTeamCoordinator.PARTICIPANT_PATH, service::handleParticipant); http.start();
        try {
            for (String path : new String[]{RegistryTeamCoordinator.CENTER_PATH, RegistryTeamCoordinator.PARTICIPANT_PATH}) {
                HttpURLConnection request = (HttpURLConnection) new URL("http://127.0.0.1:" + http.getAddress().getPort() + path).openConnection();
                request.setRequestMethod("POST"); request.setDoOutput(true); request.setRequestProperty("Cookie", "authenticated=true");
                request.setRequestProperty("Origin", "http://localhost"); request.setRequestProperty("X-Starweave-Team-Lease", "forged");
                try {
                    request.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
                    assertEquals(403, request.getResponseCode());
                    try (InputStream input = request.getErrorStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[1024]; int count; while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
                        assertEquals("UNAUTHORIZED", JSON.parseObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)).getString("code"));
                    }
                } finally { request.disconnect(); }
            }
        } finally { http.stop(0); service.close(); registry.close(); }
    }
}
