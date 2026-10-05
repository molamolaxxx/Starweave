package com.mola.cmd.proxy.app.acp.team.coordinator;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mola.cmd.proxy.app.acp.team.TeamCommandHandler;
import com.mola.cmd.proxy.app.acp.team.TeamManager;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamTransportDescriptor;
import java.util.Map;
import java.util.function.Supplier;

/** Transport-neutral local adapter. The existing TeamManager remains authoritative. */
public final class TeamParticipant {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final TeamManager manager;
    private final Supplier<TeamTransportDescriptor> descriptor;
    public TeamParticipant(TeamManager manager, Supplier<TeamTransportDescriptor> descriptor) {
        this.manager = manager; this.descriptor = descriptor;
    }
    public JSONObject describe() { return JSON.parseObject(GSON.toJson(descriptor.get())); }
    public JSONObject command(String operation, JSONObject payload) {
        TeamTransportDescriptor current = descriptor.get();
        if (!current.isBusinessCommandsReady()) throw new CoordinationException("TEAM_NOT_READY", "团队运行时尚未就绪");
        TeamCommandHandler handler = new TeamCommandHandler(manager, current.getTransportGroup());
        String id = payload.getString("requestId");
        String[] args = {payload.toJSONString()};
        Map<String, String> response;
        switch (operation) {
            case "create": response = handler.handleCreate(id, args); break;
            case "list": response = handler.handleList(id, args); break;
            case "get": response = handler.handleGet(id, args); break;
            case "delete": response = handler.handleDelete(id, args); break;
            case "send": response = handler.handleSend(id, args); break;
            case "cancel": response = handler.handleCancel(id, args); break;
            case "newSession": response = handler.handleNewSession(id, args); break;
            case "listSessions": response = handler.handleListSessions(id, args); break;
            case "history": response = handler.handleGetSessionHistory(id, args); break;
            case "restore": response = handler.handleRestoreSession(id, args); break;
            case "status": response = handler.handleGetStatus(id, args); break;
            case "context": response = handler.handleGetContextUsage(id, args); break;
            case "memoryDream": response = handler.handleMemoryDream(id, args); break;
            case "readTextFile": response = handler.handleReadTextFile(id, args); break;
            case "talkTo": response = handler.handleTalkToDeliver(id, args); break;
            case "circuitOpen": response = handler.handleTalkToCircuitOpen(id, args); break;
            default: throw new CoordinationException("INVALID_ARGUMENT", "不支持的团队成员操作");
        }
        if (!Boolean.parseBoolean(response.get("accepted")))
            throw new CoordinationException(response.get("code"), response.get("message"));
        JSONObject data = JSON.parseObject(response.get("data"));
        if (data == null) data = new JSONObject(true);
        if (response.get("teamVersion") != null) data.put("participantTeamVersion", Long.parseLong(response.get("teamVersion")));
        return data;
    }
}
