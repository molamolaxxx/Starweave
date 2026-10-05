package com.mola.cmd.proxy.app.acp.team.coordinator;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.team.TeamManager;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamTransportDescriptor;
import com.mola.cmd.proxy.app.acp.starweave.StarweaveTeamApiBridge;
import com.mola.cmd.proxy.app.acp.starweave.StarweaveIdentity;
import com.mola.cmd.proxy.client.provider.CmdReceiver;
import com.mola.cmd.proxy.client.resp.CmdResponseContent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.*;
import java.util.function.Supplier;

/** Lifecycle bridge between ConfigUI's registry and the reloadable ACP runtime. */
public final class TeamCoordinationBridge {
    public static final String RPC_COMMAND = "acpTeamCoordinator";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static volatile RegistryTeamCoordinator service;
    private static volatile TeamParticipant participant;
    private static volatile TeamManager manager;
    private TeamCoordinationBridge() { }
    public static void installService(RegistryTeamCoordinator next) { service = next; }
    public static void clearService(RegistryTeamCoordinator expected) { if (service == expected) service = null; }
    public static void installParticipant(TeamManager next, Supplier<TeamTransportDescriptor> descriptor) {
        participant = new TeamParticipant(next, descriptor); manager = next;
    }
    public static void clearParticipant(TeamManager expected) { if (manager == expected) { participant = null; manager = null; } }
    public static TeamParticipant participant() {
        TeamParticipant current = participant;
        if (current == null) throw new CoordinationException("TEAM_NOT_READY", "团队运行时尚未启动");
        return current;
    }
    public static JSONObject request(String owner, String operation, JSONObject payload) {
        RegistryTeamCoordinator current = service;
        if (current == null) throw new CoordinationException("COORDINATOR_UNAVAILABLE", "注册中心协调器尚未启动");
        return current.request(owner, operation, payload);
    }
    public static boolean capture(TeamEventEnvelope event) {
        RegistryTeamCoordinator current = service;
        return current != null && current.capture(JSON.parseObject(GSON.toJson(event)));
    }
    public static boolean isAssigned(String teamId) {
        RegistryTeamCoordinator current = service;
        return current != null && current.isAssigned(teamId);
    }
    public static boolean authorizesProjection(String owner, JSONObject event) {
        RegistryTeamCoordinator current = service;
        return current != null && current.authorizesProjection(owner, event);
    }
    public static Map<String, String> handleRpc(String requestId, String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("schemaVersion", "1"); result.put("requestId", requestId);
        try {
            if (args == null || args.length != 1) throw new CoordinationException("VALIDATION_ERROR", "需要一个 JSON 参数");
            JSONObject value = JSON.parseObject(args[0]);
            String owner = value.getString("ownerChatterId");
            // Only owners exposed on this existing trusted MolaChat RPC transport
            // may delegate requests. Starweave owner calls use the local facade.
            boolean allowed = false;
            com.alibaba.fastjson.JSONArray sources = participant().describe().getJSONArray("teamMemberSources");
            if (sources != null) for (Object source : sources)
                allowed |= Objects.equals(owner, ((JSONObject) source).getString("ownerChatterId"));
            if (!allowed || (owner != null && owner.startsWith("starweave-")))
                throw new CoordinationException("UNAUTHORIZED", "当前接入不能代理该用户的队伍");
            JSONObject data = request(owner, value.getString("operation"), value.getJSONObject("payload"));
            result.put("accepted", "true"); result.put("code", "OK"); result.put("message", "OK"); result.put("data", data.toJSONString());
        } catch (RuntimeException failure) {
            result.put("accepted", "false"); result.put("code", failure instanceof CoordinationException ? ((CoordinationException) failure).getCode() : "INTERNAL_ERROR");
            result.put("message", failure.getMessage() == null ? "团队协调请求失败" : failure.getMessage());
        }
        return result;
    }
    public static void project(String instanceId, String owner, JSONObject event) {
        if (!authorizesProjection(owner, event)) throw new CoordinationException("UNAUTHORIZED", "协调事件不属于当前队伍或成员");
        if (StarweaveIdentity.ownerId(instanceId).equals(owner)) {
            StarweaveTeamApiBridge.projectCoordinatorEvent(owner, event);
        } else {
            if (!CmdReceiver.INSTANCE.hasCallbackConsumer("team-acp-" + instanceId))
                throw new CoordinationException("CLIENT_UNAVAILABLE", "MolaChat 事件客户端尚未连接");
            Map<String, String> map = new LinkedHashMap<>();
            for (String key : event.keySet()) map.put(key, "data".equals(key) ? JSON.toJSONString(event.get(key)) : String.valueOf(event.get(key)));
            map.put("participantTransportGroup", event.getString("transportGroup"));
            map.put("transportGroup", "team-acp-" + instanceId);
            CmdReceiver.INSTANCE.callback("acpTeamEvent", "team-acp-" + instanceId,
                    new CmdResponseContent(event.getString("eventId"), map));
        }
    }
}
