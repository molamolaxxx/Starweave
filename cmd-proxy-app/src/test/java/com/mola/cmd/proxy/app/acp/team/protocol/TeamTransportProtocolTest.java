package com.mola.cmd.proxy.app.acp.team.protocol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.team.TeamLimits;
import com.mola.cmd.proxy.app.acp.team.model.TeamMetricsSnapshot;
import com.mola.cmd.proxy.app.acp.team.model.TeamResourceSnapshot;
import org.junit.Test;

import java.util.Map;
import java.util.Collections;

import static org.junit.Assert.*;

public class TeamTransportProtocolTest {

    @Test
    public void limitsAllowSingleMemberTeams() {
        TeamLimits limits = new TeamLimits(1, 1, 1);

        assertEquals(1, limits.getMaxMembersPerTeam());
        assertEquals(1, limits.getMaxTotalMembers());
    }

    @Test
    public void limitsAllowTenMembersPerTeam() {
        TeamLimits limits = new TeamLimits(1, 10, 10);

        assertEquals(10, limits.getMaxMembersPerTeam());
        try {
            new TeamLimits(1, 11, 11);
            fail("eleven members per Team must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("between 1 and 10"));
        }
    }

    @Test
    public void derivesStableInstanceScopedTransportGroup() {
        TeamTransportDescriptor descriptor =
                TeamTransportDescriptor.forInstance("home-mola-.cmd-proxy");

        assertEquals("team-acp-home-mola-.cmd-proxy", descriptor.getTransportGroup());
        assertEquals("team-acp", descriptor.getRobotGroup());
        assertEquals("1", descriptor.getSchemaVersion());
        TeamLimits configured = TeamLimits.system();
        assertEquals(configured.getMaxActiveTeams(),
                descriptor.getLimits().getMaxActiveTeams());
        assertEquals(configured.getMaxMembersPerTeam(),
                descriptor.getLimits().getMaxMembersPerTeam());
        assertEquals(configured.getMaxTotalMembers(),
                descriptor.getLimits().getMaxTotalMembers());
    }

    @Test
    public void differentInstancesNeverShareTransportGroup() {
        assertNotEquals(
                TeamTransportDescriptor.forInstance("instance-a").getTransportGroup(),
                TeamTransportDescriptor.forInstance("instance-b").getTransportGroup());
    }

    @Test
    public void describeUsesCanonicalStringResultMapEnvelope() {
        TeamTransportDescriptor descriptor =
                TeamTransportDescriptor.forInstance("instance-a");
        Map<String, String> result =
                TeamTransportProtocol.describeResult("request-1", descriptor);
        JsonObject data = JsonParser.parseString(result.get("data")).getAsJsonObject();

        assertEquals("1", result.get("schemaVersion"));
        assertEquals("request-1", result.get("requestId"));
        assertEquals("true", result.get("accepted"));
        assertEquals("OK", result.get("code"));
        assertEquals("team-acp-instance-a",
                data.get("transportGroup").getAsString());
        assertFalse(data.get("businessCommandsReady").getAsBoolean());
        assertEquals("acpTeamDescribe",
                data.getAsJsonArray("commands").get(0).getAsString());
    }

    @Test
    public void syncDiscoveryCarriesDirectFieldsAndJsonDescriptor() {
        TeamTransportDescriptor descriptor =
                TeamTransportDescriptor.forInstance("instance-a");
        Map<String, String> fields =
                TeamTransportProtocol.discoveryFields(descriptor);

        assertEquals("instance-a", fields.get("teamCmdProxyInstanceId"));
        assertEquals("team-acp-instance-a", fields.get("teamTransportGroup"));
        assertTrue(fields.get("teamDiscovery").contains("\"schemaVersion\":\"1\""));
    }

    @Test
    public void discoveryPublishesNonSensitiveTeamMemberSources() {
        TeamMemberSourceDescriptor source = new TeamMemberSourceDescriptor(
                "owner-a", "group-a", "acp-Codex", "Codex",
                "Codex", "img/codex.png", "coding", true);
        TeamTransportDescriptor descriptor = TeamTransportDescriptor.readyForBusiness(
                "instance-a", Collections.singletonList(source));

        JsonObject discovery = JsonParser.parseString(
                TeamTransportProtocol.discoveryFields(descriptor).get("teamDiscovery"))
                .getAsJsonObject();
        JsonObject published = discovery.getAsJsonArray("teamMemberSources")
                .get(0).getAsJsonObject();

        assertEquals("owner-a", published.get("ownerChatterId").getAsString());
        assertEquals("group-a", published.get("sourceGroupId").getAsString());
        assertTrue(published.get("onlyTeamMember").getAsBoolean());
        assertFalse(published.has("apiKey"));
    }

    @Test
    public void describeCanExposeBackwardCompatibleMetricsSnapshot() {
        TeamMetricsSnapshot metrics = new TeamMetricsSnapshot(
                3, 1, 2, 0, 0, 1, 4,
                1, 2, new TeamResourceSnapshot(1, 2, 0, 0, 0));
        Map<String, String> result = TeamTransportProtocol.describeResult(
                "request-1",
                TeamTransportDescriptor.readyForBusiness("instance-a"),
                metrics);
        JsonObject data = JsonParser.parseString(result.get("data"))
                .getAsJsonObject();

        assertEquals("team-acp-instance-a",
                data.get("transportGroup").getAsString());
        assertEquals(1, data.getAsJsonObject("metrics")
                .get("activeTeams").getAsInt());
        assertEquals(2, data.getAsJsonObject("metrics")
                .getAsJsonObject("resources").get("clients").getAsInt());
    }

    @Test
    public void readyDescriptorAdvertisesImplementedBusinessCommands() {
        TeamTransportDescriptor descriptor =
                TeamTransportDescriptor.readyForBusiness("instance-a");

        assertTrue(descriptor.isBusinessCommandsReady());
        assertEquals(19, descriptor.getCommands().size());
        assertTrue(descriptor.getCommands().contains("acpTeamCoordinator"));
        assertTrue(descriptor.getCommands().contains("acpTeamCreate"));
        assertTrue(descriptor.getCommands().contains("acpTeamUpdateMembers"));
        assertTrue(descriptor.getCommands().contains("acpTeamList"));
        assertTrue(descriptor.getCommands().contains("acpTeamGet"));
        assertTrue(descriptor.getCommands().contains("acpTeamDelete"));
        assertTrue(descriptor.getCommands().contains("acpTeamSend"));
        assertTrue(descriptor.getCommands().contains("acpTeamRestoreSession"));
        assertTrue(descriptor.getCommands().contains("acpTeamGetSessionHistory"));
        assertTrue(descriptor.getCommands().contains("acpTeamGetContextUsage"));
        assertTrue(descriptor.getCommands().contains("acpTeamMemoryDream"));
        assertTrue(descriptor.getCommands().contains("acpTeamReadTextFile"));
        assertTrue(descriptor.getCommands().contains("acpTeamTalkToCircuitOpen"));
        assertTrue(descriptor.getCommands().contains("acpTeamTalkToDeliver"));
    }

    @Test
    public void discoverySeparatesRemoteGrantsAndAdvertisesMixedCapabilities() {
        RemoteTeamMemberSourceDescriptor remote = new RemoteTeamMemberSourceDescriptor(
                "owner-a", "instance-b", "team-shared-instance-b-acp-Codex",
                "acp-Codex", "Codex", "Codex", "", "coding");
        TeamTransportDescriptor descriptor = TeamTransportDescriptor.readyForBusiness(
                "instance-b", Collections.emptyList(), Collections.singletonList(remote));
        JsonObject discovery = JsonParser.parseString(
                TeamTransportProtocol.discoveryFields(descriptor).get("teamDiscovery"))
                .getAsJsonObject();

        assertTrue(discovery.getAsJsonObject("capabilities")
                .get("mixedTeamFragment").getAsBoolean());
        assertTrue(discovery.getAsJsonObject("capabilities")
                .get("mixedTeamTalkToDeliver").getAsBoolean());
        assertEquals("owner-a", discovery.getAsJsonArray("remoteTeamMemberSources")
                .get(0).getAsJsonObject().get("granteeOwnerChatterId").getAsString());
        assertEquals(0, discovery.getAsJsonArray("teamMemberSources").size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsafeInstanceId() {
        TeamTransportDescriptor.forInstance("../other");
    }
}
