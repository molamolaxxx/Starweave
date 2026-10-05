package com.mola.cmd.proxy.app.acp.team.protocol;

import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.TeamLimits;

import java.util.Collections;
import java.util.Arrays;
import java.util.List;

public final class TeamTransportDescriptor {

    private final String schemaVersion;
    private final String cmdProxyInstanceId;
    private final String transportGroup;
    private final String robotGroup;
    private final String describeCommand;
    private final String eventCommand;
    private final boolean businessCommandsReady;
    private final List<String> commands;
    private final TeamLimits limits;
    private final List<TeamMemberSourceDescriptor> teamMemberSources;
    private final List<RemoteTeamMemberSourceDescriptor> remoteTeamMemberSources;
    private final TeamTransportCapabilities capabilities;

    private TeamTransportDescriptor(String cmdProxyInstanceId, String transportGroup,
                                    boolean businessCommandsReady,
                                    List<TeamMemberSourceDescriptor> teamMemberSources,
                                    List<RemoteTeamMemberSourceDescriptor> remoteTeamMemberSources) {
        this.schemaVersion = TeamDefinition.SCHEMA_VERSION;
        this.cmdProxyInstanceId = cmdProxyInstanceId;
        this.transportGroup = transportGroup;
        this.robotGroup = TeamMemberDefinition.ROBOT_GROUP;
        this.describeCommand = TeamTransportProtocol.DESCRIBE_COMMAND;
        this.eventCommand = TeamTransportProtocol.EVENT_COMMAND;
        this.businessCommandsReady = businessCommandsReady;
        this.limits = TeamLimits.system();
        this.teamMemberSources = Collections.unmodifiableList(
                teamMemberSources == null
                        ? Collections.emptyList()
                        : new java.util.ArrayList<>(teamMemberSources));
        this.remoteTeamMemberSources = Collections.unmodifiableList(
                remoteTeamMemberSources == null ? Collections.emptyList()
                        : new java.util.ArrayList<>(remoteTeamMemberSources));
        this.capabilities = new TeamTransportCapabilities(true, true);
        this.commands = businessCommandsReady
                ? Collections.unmodifiableList(Arrays.asList(
                        TeamTransportProtocol.DESCRIBE_COMMAND,
                        com.mola.cmd.proxy.app.acp.team.coordinator.TeamCoordinationBridge.RPC_COMMAND,
                        TeamTransportProtocol.CREATE_COMMAND,
                        TeamTransportProtocol.UPDATE_MEMBERS_COMMAND,
                        TeamTransportProtocol.LIST_COMMAND,
                        TeamTransportProtocol.GET_COMMAND,
                        TeamTransportProtocol.DELETE_COMMAND,
                        TeamTransportProtocol.SEND_COMMAND,
                        TeamTransportProtocol.CANCEL_COMMAND,
                        TeamTransportProtocol.NEW_SESSION_COMMAND,
                        TeamTransportProtocol.LIST_SESSIONS_COMMAND,
                        TeamTransportProtocol.GET_SESSION_HISTORY_COMMAND,
                        TeamTransportProtocol.RESTORE_SESSION_COMMAND,
                        TeamTransportProtocol.GET_STATUS_COMMAND,
                        TeamTransportProtocol.GET_CONTEXT_USAGE_COMMAND,
                        TeamTransportProtocol.MEMORY_DREAM_COMMAND,
                        TeamTransportProtocol.READ_TEXT_FILE_COMMAND,
                        TeamTransportProtocol.TALK_TO_CIRCUIT_OPEN_COMMAND,
                        TeamTransportProtocol.TALK_TO_DELIVER_COMMAND))
                : Collections.singletonList(TeamTransportProtocol.DESCRIBE_COMMAND);
    }

    public static TeamTransportDescriptor forInstance(String instanceId) {
        return forInstance(instanceId, Collections.emptyList());
    }

    public static TeamTransportDescriptor forInstance(
            String instanceId, List<TeamMemberSourceDescriptor> teamMemberSources) {
        String normalized = requireSafeInstanceId(instanceId);
        return new TeamTransportDescriptor(
                normalized, TeamTransportProtocol.TRANSPORT_PREFIX + normalized,
                false, teamMemberSources, Collections.emptyList());
    }

    public static TeamTransportDescriptor readyForBusiness(String instanceId) {
        return readyForBusiness(instanceId, Collections.emptyList());
    }

    public static TeamTransportDescriptor readyForBusiness(
            String instanceId, List<TeamMemberSourceDescriptor> teamMemberSources) {
        return readyForBusiness(instanceId, teamMemberSources, Collections.emptyList());
    }

    public static TeamTransportDescriptor forInstance(String instanceId,
            List<TeamMemberSourceDescriptor> teamMemberSources,
            List<RemoteTeamMemberSourceDescriptor> remoteTeamMemberSources) {
        String normalized = requireSafeInstanceId(instanceId);
        return new TeamTransportDescriptor(normalized,
                TeamTransportProtocol.TRANSPORT_PREFIX + normalized, false,
                teamMemberSources, remoteTeamMemberSources);
    }

    public static TeamTransportDescriptor readyForBusiness(
            String instanceId, List<TeamMemberSourceDescriptor> teamMemberSources,
            List<RemoteTeamMemberSourceDescriptor> remoteTeamMemberSources) {
        String normalized = requireSafeInstanceId(instanceId);
        return new TeamTransportDescriptor(
                normalized, TeamTransportProtocol.TRANSPORT_PREFIX + normalized,
                true, teamMemberSources, remoteTeamMemberSources);
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getCmdProxyInstanceId() {
        return cmdProxyInstanceId;
    }

    public String getTransportGroup() {
        return transportGroup;
    }

    public String getRobotGroup() {
        return robotGroup;
    }

    public String getDescribeCommand() {
        return describeCommand;
    }

    public String getEventCommand() {
        return eventCommand;
    }

    public boolean isBusinessCommandsReady() {
        return businessCommandsReady;
    }

    public List<String> getCommands() {
        return commands;
    }

    public TeamLimits getLimits() {
        return limits;
    }

    public List<TeamMemberSourceDescriptor> getTeamMemberSources() {
        return teamMemberSources;
    }

    public List<RemoteTeamMemberSourceDescriptor> getRemoteTeamMemberSources() {
        return remoteTeamMemberSources;
    }

    public TeamTransportCapabilities getCapabilities() { return capabilities; }

    private static String requireSafeInstanceId(String instanceId) {
        if (instanceId == null || !instanceId.matches("[a-zA-Z0-9._-]+")
                || ".".equals(instanceId) || "..".equals(instanceId)) {
            throw new IllegalArgumentException("instanceId is not transport-safe");
        }
        return instanceId;
    }
}
