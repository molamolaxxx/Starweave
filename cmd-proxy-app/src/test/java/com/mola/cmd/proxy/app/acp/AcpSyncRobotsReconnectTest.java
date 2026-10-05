package com.mola.cmd.proxy.app.acp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.team.TeamClientRegistry;
import com.mola.cmd.proxy.app.acp.team.TeamManager;
import com.mola.cmd.proxy.app.acp.team.TeamStore;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamTransportDescriptor;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamTransportProtocol;
import org.junit.Test;

import java.nio.file.Files;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AcpSyncRobotsReconnectTest {

    @Test
    public void newMolaChatConnectionGetsCompleteReadyDiscoveryFromSyncHandshake() {
        AcpSyncRobotsSnapshot snapshot = new AcpSyncRobotsSnapshot(
                "[{\"name\":\"robot-a\"}]", "[\"owner-a\"]",
                TeamTransportDescriptor.readyForBusiness("instance-a"));

        // 首个 MolaChat 连接读取一次；进程退出不会修改 cmd-proxy 的权威快照。
        Map<String, String> firstConnection = snapshot.resultMap();
        // 新 MolaChat 进程注册 callback 后再次调用 acpSyncRobots 握手。
        Map<String, String> reconnected = snapshot.resultMap();

        assertEquals(firstConnection, reconnected);
        assertEquals("[{\"name\":\"robot-a\"}]", reconnected.get("robots"));
        assertEquals("[\"owner-a\"]", reconnected.get("visibleChatterIds"));
        assertEquals("team-acp-instance-a", reconnected.get("teamTransportGroup"));
        JsonObject discovery = JsonParser.parseString(
                reconnected.get("teamDiscovery")).getAsJsonObject();
        assertTrue(discovery.get("businessCommandsReady").getAsBoolean());
        assertEquals(19, discovery.getAsJsonArray("commands").size());
        assertTrue(discovery.getAsJsonArray("commands").toString()
                .contains(TeamTransportProtocol.UPDATE_MEMBERS_COMMAND));
        assertTrue(discovery.getAsJsonArray("commands").toString()
                .contains(TeamTransportProtocol.GET_SESSION_HISTORY_COMMAND));
        assertTrue(discovery.getAsJsonArray("commands").toString()
                .contains(TeamTransportProtocol.TALK_TO_CIRCUIT_OPEN_COMMAND));
    }

    @Test
    public void ordinaryReloadCannotDropTeamDiscoveryFromReconnectSnapshot() {
        AcpSyncRobotsSnapshot snapshot = new AcpSyncRobotsSnapshot(
                "[]", "[]",
                TeamTransportDescriptor.readyForBusiness("instance-a"));

        snapshot.updateOrdinary(
                "[{\"name\":\"robot-b\"}]", "[\"owner-b\"]");
        Map<String, String> reconnected = snapshot.resultMap();

        assertEquals("[{\"name\":\"robot-b\"}]", reconnected.get("robots"));
        assertEquals("instance-a", reconnected.get("teamCmdProxyInstanceId"));
        assertTrue(JsonParser.parseString(reconnected.get("teamDiscovery"))
                .getAsJsonObject().get("businessCommandsReady").getAsBoolean());
    }

    @Test
    public void starweaveOnlyInstancePublishesReadyDiscoveryWithoutVisibleChatter() {
        AcpSyncRobotsSnapshot snapshot = new AcpSyncRobotsSnapshot(
                "[]", "[]",
                TeamTransportDescriptor.readyForBusiness("remote-instance"));
        AtomicReference<Map<String, String>> published = new AtomicReference<>();
        AcpSyncRobotsHeartbeat heartbeat = new AcpSyncRobotsHeartbeat(
                snapshot, published::set, 1L, TimeUnit.DAYS);

        try {
            heartbeat.start();
            heartbeat.publishOnce();

            assertEquals("[]", published.get().get("visibleChatterIds"));
            assertEquals("remote-instance",
                    published.get().get("teamCmdProxyInstanceId"));
            assertTrue(JsonParser.parseString(published.get().get("teamDiscovery"))
                    .getAsJsonObject().get("businessCommandsReady").getAsBoolean());
        } finally {
            heartbeat.close();
        }
    }

    @Test
    public void hotReloadStopRevokesReadyDiscoveryWithoutDroppingOrdinaryRobots() {
        AcpSyncRobotsSnapshot snapshot = new AcpSyncRobotsSnapshot(
                "[{\"name\":\"robot-a\"}]", "[\"owner-a\"]",
                TeamTransportDescriptor.readyForBusiness("instance-a"));

        snapshot.updateTeamDescriptor(
                TeamTransportDescriptor.forInstance("instance-a"));
        Map<String, String> stopped = snapshot.resultMap();

        assertEquals("[{\"name\":\"robot-a\"}]", stopped.get("robots"));
        JsonObject discovery = JsonParser.parseString(
                stopped.get("teamDiscovery")).getAsJsonObject();
        assertTrue(!discovery.get("businessCommandsReady").getAsBoolean());
        assertEquals(1, discovery.getAsJsonArray("commands").size());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void callerCannotCorruptFutureReconnectSnapshot() {
        AcpSyncRobotsSnapshot snapshot = new AcpSyncRobotsSnapshot(
                null, null, TeamTransportDescriptor.forInstance("instance-a"));

        snapshot.resultMap().put("teamDiscovery", "corrupted");
    }

    @Test
    public void twoRunningInstancesRepublishAfterMolaCallbackProviderRestarts()
            throws Exception {
        AcpSyncRobotsSnapshot first = new AcpSyncRobotsSnapshot(
                "[{\"name\":\"robot-a\"}]", "[\"owner-a\"]",
                TeamTransportDescriptor.readyForBusiness("instance-a"));
        AcpSyncRobotsSnapshot second = new AcpSyncRobotsSnapshot(
                "[{\"name\":\"robot-b\"}]", "[\"owner-b\"]",
                TeamTransportDescriptor.readyForBusiness("instance-b"));
        AtomicReference<Receiver> molaProvider = new AtomicReference<>(
                new Receiver(2));
        AcpSyncRobotsHeartbeat firstHeartbeat = heartbeat(first, molaProvider);
        AcpSyncRobotsHeartbeat secondHeartbeat = heartbeat(second, molaProvider);
        try {
            firstHeartbeat.start();
            secondHeartbeat.start();
            assertTrue(molaProvider.get().await());
            assertEquals(setOf("instance-a", "instance-b"),
                    molaProvider.get().instanceIds);

            // Mola callback provider 重启；两个 cmd-proxy heartbeat 本身不重启。
            Receiver restarted = new Receiver(2);
            molaProvider.set(restarted);

            assertTrue(restarted.await());
            assertEquals(setOf("instance-a", "instance-b"),
                    restarted.instanceIds);
            assertEquals(setOf("team-acp-instance-a", "team-acp-instance-b"),
                    restarted.transportGroups);
        } finally {
            firstHeartbeat.close();
            secondHeartbeat.close();
        }
    }

    @Test
    public void heartbeatOnlyReadsReadySnapshotAndStopsWithoutMutatingState()
            throws Exception {
        AcpSyncRobotsSnapshot snapshot = new AcpSyncRobotsSnapshot(
                "[{\"name\":\"robot-a\",\"enabled\":true}]", "[\"owner-a\"]",
                TeamTransportDescriptor.readyForBusiness("instance-a"));
        Map<String, String> before = snapshot.resultMap();
        TeamManager manager = new TeamManager(
                new TeamStore(Files.createTempDirectory("heartbeat-teams")),
                new TeamClientRegistry());
        java.util.concurrent.atomic.AtomicInteger publishes =
                new java.util.concurrent.atomic.AtomicInteger();
        AcpSyncRobotsHeartbeat heartbeat = new AcpSyncRobotsHeartbeat(
                snapshot, ignored -> publishes.incrementAndGet(),
                1L, TimeUnit.DAYS);

        try {
            heartbeat.start();
            heartbeat.publishOnce();
            heartbeat.close();
            heartbeat.publishOnce();

            assertEquals(1, publishes.get());
            assertEquals(before, snapshot.resultMap());
            assertEquals("[{\"name\":\"robot-a\",\"enabled\":true}]",
                    snapshot.resultMap().get("robots"));
            assertTrue(manager.snapshotDefinitions().isEmpty());
            assertFalse(manager.isClosed());
        } finally {
            heartbeat.close();
            manager.close();
        }
    }

    @Test
    public void heartbeatNeverPublishesNotReadySnapshot() {
        AcpSyncRobotsSnapshot snapshot = new AcpSyncRobotsSnapshot(
                "[{\"name\":\"robot-a\"}]", "[\"owner-a\"]",
                TeamTransportDescriptor.forInstance("instance-a"));
        java.util.concurrent.atomic.AtomicInteger publishes =
                new java.util.concurrent.atomic.AtomicInteger();
        AcpSyncRobotsHeartbeat heartbeat = new AcpSyncRobotsHeartbeat(
                snapshot, ignored -> publishes.incrementAndGet(),
                1L, TimeUnit.DAYS);
        try {
            heartbeat.start();
            heartbeat.publishOnce();
            assertEquals(0, publishes.get());
            assertFalse(snapshot.isBusinessCommandsReady());
        } finally {
            heartbeat.close();
        }
    }

    private static AcpSyncRobotsHeartbeat heartbeat(
            AcpSyncRobotsSnapshot snapshot,
            AtomicReference<Receiver> receiver) {
        return new AcpSyncRobotsHeartbeat(
                snapshot, result -> receiver.get().accept(result),
                10L, TimeUnit.MILLISECONDS);
    }

    private static Set<String> setOf(String... values) {
        Set<String> result = new HashSet<>();
        java.util.Collections.addAll(result, values);
        return result;
    }

    private static final class Receiver {
        private final CountDownLatch latch;
        private final Set<String> instanceIds =
                java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final Set<String> transportGroups =
                java.util.concurrent.ConcurrentHashMap.newKeySet();

        private Receiver(int instances) {
            latch = new CountDownLatch(instances);
        }

        private void accept(Map<String, String> snapshot) {
            String instanceId = snapshot.get("teamCmdProxyInstanceId");
            if (instanceIds.add(instanceId)) {
                transportGroups.add(snapshot.get("teamTransportGroup"));
                latch.countDown();
            }
        }

        private boolean await() throws InterruptedException {
            return latch.await(2L, TimeUnit.SECONDS);
        }
    }
}
