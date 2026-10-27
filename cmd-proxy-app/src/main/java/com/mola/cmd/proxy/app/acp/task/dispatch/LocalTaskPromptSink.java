package com.mola.cmd.proxy.app.acp.task.dispatch;

import com.alibaba.fastjson.JSONObject;
import com.google.gson.Gson;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry;
import com.mola.cmd.proxy.app.acp.acpclient.PromptCommandResult;
import com.mola.cmd.proxy.app.acp.acpclient.PromptOptions;
import com.mola.cmd.proxy.app.acp.team.TeamManager;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamCommandResult;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberCommand;

import java.util.Objects;
import java.util.UUID;

/** Sends work to local Agents; only suspend/cancel controls may interrupt the matching task turn. */
public final class LocalTaskPromptSink implements TaskPromptSink {
    private static final Gson GSON = new Gson();
    private final AcpClientRegistry mainClients;
    private final TeamManager teams;
    private final TaskCardProjector projector;
    private final AgentSessionOpener sessionOpener;

    public LocalTaskPromptSink(AcpClientRegistry mainClients, TeamManager teams,
                               TaskCardProjector projector) {
        this(mainClients, teams, projector, null);
    }

    public LocalTaskPromptSink(AcpClientRegistry mainClients, TeamManager teams,
                               TaskCardProjector projector, AgentSessionOpener sessionOpener) {
        this.mainClients = Objects.requireNonNull(mainClients, "mainClients");
        this.teams = teams;
        this.projector = Objects.requireNonNull(projector, "projector");
        this.sessionOpener = sessionOpener;
    }

    @Override
    public boolean submit(JSONObject assignee, JSONObject card, String agentPrompt) {
        if (assignee == null) return false;
        if (isProjectionOnly(card)) {
            projector.project(assignee, card);
            return true;
        }
        String teamId = trim(assignee.getString("teamId"));
        if (!teamId.isEmpty()) return submitTeam(teamId, assignee, card, agentPrompt);
        String groupId = trim(assignee.getString("groupId"));
        AcpClient client = groupId.isEmpty() ? null : mainClients.getClient(groupId);
        if (client == null && sessionOpener != null
                && "STARWEAVE".equals(assignee.getString("surface"))) {
            sessionOpener.open(new JSONObject(assignee));
            client = mainClients.getClient(groupId);
        }
        if (client == null) return false;
        PromptOptions options = taskOptions(card);
        if (client.getState() == AbstractAcpClient.State.BUSY
                && !isInterruptingControl(card)) return false;
        PromptCommandResult result = mainClients.sendTaskMessageWithResult(
                groupId, agentPrompt, options);
        if (result.isAccepted()) projector.project(assignee, card);
        return result.isAccepted();
    }

    private boolean submitTeam(String teamId, JSONObject assignee, JSONObject card,
                               String agentPrompt) {
        if (teams == null) return false;
        String memberId = trim(assignee.getString("teamMemberId"));
        com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime runtime =
                teams.getRuntime(teamId).orElse(null);
        if (runtime == null || runtime.getDefinition().isMixedPlacement()) return false;
        AcpClient client = teams.getClientRegistry().get(teamId, memberId).orElse(null);
        if (client == null) return false;
        TeamDefinition team = runtime.getDefinition();
        JSONObject command = new JSONObject(true);
        command.put("schemaVersion", TeamDefinition.SCHEMA_VERSION);
        command.put("ownerChatterId", team.getOwnerChatterId());
        command.put("teamId", teamId);
        command.put("teamMemberId", memberId);
        command.put("acpClientId", assignee.getString("acpClientId"));
        command.put("message", agentPrompt);
        String requestId = trim(card.getString("eventId"));
        if (requestId.isEmpty()) requestId = UUID.randomUUID().toString();
        TeamMemberCommand memberCommand = GSON.fromJson(
                command.toJSONString(), TeamMemberCommand.class);
        if (client.getState() == AbstractAcpClient.State.BUSY) {
            if (!isInterruptingControl(card)) return false;
            teams.interruptTask(requestId + ":interrupt", memberCommand,
                    card.getString("taskId"));
            // The durable receipt remains pending and will deliver the control prompt at READY.
            return false;
        }
        if (client.getState() != AbstractAcpClient.State.READY
                && client.getState() != AbstractAcpClient.State.SLEEP) return false;
        TeamCommandResult result = teams.sendTask(requestId, memberCommand, taskOptions(card));
        if (result.isAccepted()) projector.project(assignee, card);
        return result.isAccepted();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    static PromptOptions taskOptions(JSONObject card) {
        // The publisher receives a notification, not ownership of the completed child task.
        // Binding the child here would also break source-task attribution for follow-up publishes.
        if ("PUBLISHER".equals(card.getString("recipientRole"))) {
            return PromptOptions.forTaskNotification();
        }
        return PromptOptions.forTask(card.getString("taskId"), card.getString("eventId"),
                card.getLong("taskEventSeq"), card.getLong("revision"),
                card.getLong("contentVersion"),
                "TASK_STATUS_CHANGED".equals(card.getString("eventType")));
    }

    private static boolean isInterruptingControl(JSONObject card) {
        if (card == null || !"TASK_STATUS_CHANGED".equals(card.getString("eventType"))) {
            return false;
        }
        String status = card.getString("status");
        return "SUSPENDED".equals(status) || "CANCELLED".equals(status);
    }

    private static boolean isProjectionOnly(JSONObject card) {
        if (card == null || !"TASK_STATUS_CHANGED".equals(card.getString("eventType"))) {
            return false;
        }
        if ("PUBLISHER".equals(card.getString("recipientRole"))) return false;
        String status = card.getString("status");
        return "START".equals(status) || "IN_PROGRESS".equals(status)
                || ("AGENT".equals(card.getString("actorType"))
                && "COMPLETED".equals(status));
    }

    public interface AgentSessionOpener {
        void open(JSONObject assignee);
    }
}
