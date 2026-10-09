package com.mola.cmd.proxy.app.acp.starweave;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mola.cmd.proxy.app.acp.team.TeamManager;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamCreateCommand;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamDeleteCommand;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberCreateSpec;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberCommand;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamQuery;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamCommandResult;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberSourceDescriptor;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamRemarksUpdateCommand;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMembersUpdateCommand;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Starweave-owned facade over the shared Fast Team authority. */
public final class StarweaveTeamApiBridge {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int EVENT_CAPACITY = 2000;
    private static final Deque<JSONObject> EVENTS = new ArrayDeque<>();
    private static final java.util.concurrent.atomic.AtomicLong EVENT_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();
    private static final StarweaveUploadStore UPLOADS = new StarweaveUploadStore();
    private static volatile Runtime runtime;

    private StarweaveTeamApiBridge() { }

    public static void install(TeamManager manager, String instanceId,
                               Supplier<List<TeamMemberSourceDescriptor>> sources) {
        install(manager, instanceId, sources, null);
    }

    public static void install(TeamManager manager, String instanceId,
                               Supplier<List<TeamMemberSourceDescriptor>> sources,
                               StarweaveTeamGateway gateway) {
        runtime = new Runtime(Objects.requireNonNull(manager, "manager"),
                instanceId, Objects.requireNonNull(sources, "sources"), gateway);
    }

    public static void clear(TeamManager expected) {
        Runtime current = runtime;
        if (current != null && current.manager == expected) runtime = null;
        synchronized (EVENTS) { EVENTS.clear(); }
        EVENT_SEQUENCE.set(0L);
    }

    public static JSONObject list() {
        Runtime current = requireRuntime();
        JSONObject local = result(current.manager.list(new TeamQuery(
                TeamDefinition.SCHEMA_VERSION, current.ownerId, null, null)));
        JSONObject localData = local.getJSONObject("data");
        JSONArray localTeams = localData == null ? null : localData.getJSONArray("teams");
        if (localTeams == null) {
            localTeams = new JSONArray();
            if (localData == null) {
                localData = new JSONObject(true);
                local.put("data", localData);
            }
            localData.put("teams", localTeams);
        }
        if (current.gateway != null) {
            try {
                JSONObject data = current.gateway.query("list", new JSONObject(true));
                JSONArray teams = data.getJSONArray("teams");
                if (teams != null) {
                    current.lastCoordinatedTeams = copyArray(teams);
                    for (int i = 0; i < teams.size(); i++) {
                        JSONObject coordinated = teams.getJSONObject(i);
                        coordinated.put("coordinated", true);
                        int localIndex = indexOfTeam(
                                localTeams, coordinated.getString("teamId"));
                        if (localIndex >= 0) localTeams.set(localIndex, coordinated);
                        else localTeams.add(coordinated);
                    }
                    return local;
                }
            } catch (IllegalStateException ignored) {
                // Starweave remains fully usable in local-only deployments.
            }
        }
        if (current.lastCoordinatedTeams != null) {
            mergeStaleTeams(localTeams, current.lastCoordinatedTeams);
            return local;
        }
        if (localTeams != null) {
            for (int i = 0; i < localTeams.size(); i++) {
                JSONObject team = localTeams.getJSONObject(i);
                if (team.getBooleanValue("mixedPlacement") ||
                        com.mola.cmd.proxy.app.acp.team.coordinator.TeamCoordinationBridge.isAssigned(team.getString("teamId"))) {
                    // A persisted local fragment is not an ordinary local Team. Keep all
                    // mutations fail-closed through the coordinator while it reconnects.
                    team.put("coordinated", true);
                    projectDegradedRoster(team);
                }
            }
        }
        return local;
    }

    private static int indexOfTeam(JSONArray teams, String teamId) {
        if (teamId == null) return -1;
        for (int i = 0; i < teams.size(); i++) {
            if (teamId.equals(teams.getJSONObject(i).getString("teamId"))) return i;
        }
        return -1;
    }

    public static JSONObject sources() {
        Runtime current = requireRuntime();
        boolean coordinatorFailed = false;
        JSONArray values = new JSONArray();
        for (TeamMemberSourceDescriptor source : current.sources.get()) {
            JSONObject value = new JSONObject(true);
            value.put("ownerChatterId", source.getOwnerChatterId());
            value.put("sourceGroupId", source.getSourceGroupId());
            value.put("sourceRobotId", source.getSourceRobotId());
            value.put("robotName", source.getRobotName());
            value.put("displayName", source.getDisplayName());
            value.put("avatar", source.getAvatar());
            value.put("remark", source.getRemark());
            value.put("onlyTeamMember", source.isOnlyTeamMember());
            value.put("cmdProxyInstanceId", current.instanceId);
            value.put("transportGroup", current.transportGroup);
            value.put("sourceType", "STARWEAVE");
            value.put("sourceLabel", "本环境 · Starweave");
            value.put("coordinated", false);
            values.add(value);
        }
        JSONArray localSources = copyArray(values);
        if (current.gateway != null) {
            try {
                JSONArray coordinated = current.gateway.query(
                        "sources", new JSONObject(true)).getJSONArray("sources");
                if (coordinated != null) {
                    current.lastCoordinatedSources = copyArray(coordinated);
                    java.util.Set<String> identities = new java.util.HashSet<>();
                    for (Object item : values) {
                        JSONObject source = (JSONObject) item;
                        identities.add(sourceIdentity(source));
                    }
                    for (int i = 0; i < coordinated.size(); i++) {
                        JSONObject source = coordinated.getJSONObject(i);
                        source.put("coordinated", true);
                        String identity = sourceIdentity(source);
                        int existingIndex = indexOfSource(values, identity);
                        // Keep ordinary home sources on the direct local TeamManager path.
                        // Only additional shared/remote sources require coordination.
                        if (existingIndex >= 0) continue;
                        else if (identities.add(identity)) values.add(source);
                    }
                }
            } catch (IllegalStateException ignored) {
                // No coordinator is a supported Starweave-only deployment mode.
                coordinatorFailed = true;
            }
        }
        boolean coordinatorDelayed = false;
        if (coordinatorFailed && current.lastCoordinatedSources != null) {
            mergeStaleSources(values, current.lastCoordinatedSources);
            coordinatorDelayed = true;
        }
        JSONObject result = new JSONObject(true);
        result.put("sources", values);
        result.put("localSources", localSources);
        if (coordinatorDelayed) {
            markCoordinatorDelayed(result);
        } else if (coordinatorFailed) {
            result.put("coordinatorAvailable", false);
            result.put("coordinatorDelayed", true);
            result.put("coordinatorStateUnknown", true);
            result.put("degraded", true);
            result.put("degradedReason", "协调器暂不可达，远程来源状态未知");
        }
        return result;
    }

    public static JSONObject create(JSONObject request) {
        Runtime current = requireRuntime();
        JSONArray requestedMembers = request == null ? null : request.getJSONArray("members");
        if (requestedMembers == null || requestedMembers.isEmpty()
                || requestedMembers.size() > 10) {
            throw new IllegalArgumentException("members size must be between 1 and 10");
        }
        boolean coordinated = false;
        for (int i = 0; i < requestedMembers.size(); i++) {
            coordinated |= requestedMembers.getJSONObject(i)
                    .getBooleanValue("coordinated");
        }
        if (coordinated) {
            if (current.gateway == null) {
                throw new IllegalStateException("Starweave Team coordinator is unavailable");
            }
            return success(current.gateway.mutate("create", new JSONObject(request)));
        }
        List<TeamMemberSourceDescriptor> availableSources = current.sources.get();
        List<TeamMemberCreateSpec> members = new ArrayList<>();
        for (int i = 0; i < requestedMembers.size(); i++) {
            JSONObject requested = requestedMembers.getJSONObject(i);
            String groupId = requested.getString("sourceGroupId");
            if (groupId == null || groupId.trim().isEmpty()) {
                groupId = requested.getString("groupId");
            }
            groupId = required(groupId, "members.sourceGroupId");
            TeamMemberSourceDescriptor source = findSource(availableSources, groupId);
            String requestedRobotId = requested.getString("sourceRobotId");
            if (requestedRobotId != null && !requestedRobotId.trim().isEmpty()
                    && !source.getSourceRobotId().equals(requestedRobotId.trim())) {
                throw new IllegalArgumentException(
                        "members.sourceRobotId does not match sourceGroupId");
            }
            String memberId = requested.getString("teamMemberId");
            if (memberId == null || memberId.trim().isEmpty()) {
                memberId = "member-" + (i + 1) + "-" + UUID.randomUUID()
                        .toString().substring(0, 8);
            }
            members.add(new TeamMemberCreateSpec(memberId,
                    source.getSourceRobotId(), source.getSourceGroupId(), i,
                    requested.getString("remark")));
        }
        String requestId = textOr(request.getString("requestId"), UUID.randomUUID().toString());
        String teamId = textOr(request.getString("teamId"),
                "team-" + UUID.randomUUID().toString());
        String name = textOr(request.getString("name"), "Starweave Team");
        TeamCreateCommand command = new TeamCreateCommand(
                TeamDefinition.SCHEMA_VERSION, requestId, teamId,
                current.ownerId, name, members, request.getString("mode"),
                request.getString("captainTeamMemberId"));
        return result(current.manager.create(command, current.transportGroup));
    }

    public static JSONObject delete(JSONObject request) {
        Runtime current = requireRuntime();
        if (isCoordinated(request)) {
            if (current.gateway == null) {
                throw new IllegalStateException("Starweave Team coordinator is unavailable");
            }
            return success(current.gateway.mutate("delete", new JSONObject(request)));
        }
        String requestId = textOr(request == null ? null : request.getString("requestId"),
                UUID.randomUUID().toString());
        TeamDeleteCommand command = new TeamDeleteCommand(
                TeamDefinition.SCHEMA_VERSION, requestId, current.ownerId,
                required(request == null ? null : request.getString("teamId"), "teamId"),
                request == null ? null : request.getLong("expectedVersion"));
        return result(current.manager.delete(command));
    }

    public static JSONObject update(JSONObject request) {
        Runtime current = requireRuntime();
        if (request == null) throw new IllegalArgumentException("request body is required");
        if (isCoordinated(request)) {
            if (current.gateway == null) {
                throw new IllegalStateException("Starweave Team coordinator is unavailable");
            }
            return success(current.gateway.mutate("update", new JSONObject(request)));
        }
        JSONArray requestedMembers = request.getJSONArray("members");
        if (requestedMembers != null) {
            List<TeamMemberCreateSpec> members = new ArrayList<>();
            for (int i = 0; i < requestedMembers.size(); i++) {
                JSONObject requested = requestedMembers.getJSONObject(i);
                String groupId = required(requested.getString("sourceGroupId"),
                        "members.sourceGroupId");
                members.add(new TeamMemberCreateSpec(
                        required(requested.getString("teamMemberId"),
                                "members.teamMemberId"),
                        required(requested.getString("sourceRobotId"),
                                "members.sourceRobotId"), groupId, i,
                        requested.getString("remark")));
            }
            return result(current.manager.updateMembers(new TeamMembersUpdateCommand(
                    TeamDefinition.SCHEMA_VERSION,
                    textOr(request.getString("requestId"), UUID.randomUUID().toString()),
                    current.ownerId, required(request.getString("teamId"), "teamId"),
                    request.getLong("expectedVersion"), members)));
        }
        JSONObject remarksValue = request.getJSONObject("memberRemarks");
        if (remarksValue == null) {
            throw new IllegalArgumentException("memberRemarks is required");
        }
        Map<String, String> memberRemarks = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : remarksValue.entrySet()) {
            Object value = entry.getValue();
            memberRemarks.put(entry.getKey(), value == null ? "" : String.valueOf(value));
        }
        TeamRemarksUpdateCommand command = new TeamRemarksUpdateCommand(
                TeamDefinition.SCHEMA_VERSION,
                textOr(request.getString("requestId"), UUID.randomUUID().toString()),
                current.ownerId, required(request.getString("teamId"), "teamId"),
                request.getLong("expectedVersion"), memberRemarks);
        return result(current.manager.updateRemarks(command));
    }

    public static JSONObject member(JSONObject request) {
        if (request == null) throw new IllegalArgumentException("request body is required");
        Runtime current = requireRuntime();
        if ("send".equals(request.getString("action"))) {
            try {
                return current.messageRequests.execute(request.getString("requestId"), request.toJSONString(),
                        () -> executeMember(current, request));
            } catch (RuntimeException error) { throw error;
            } catch (Exception error) { throw new IllegalStateException(error); }
        }
        // A lower replay bound, sampled before history. Overlap is merged by message ID/revision.
        long replayAfter;
        synchronized (EVENTS) {
            replayAfter = EVENTS.isEmpty() ? 0L : EVENTS.getLast().getLongValue("eventSeq");
        }
        JSONObject result = executeMember(current, request);
        if ("history".equals(request.getString("action")) && result.getJSONObject("data") != null) {
            result.getJSONObject("data").put("replayAfter", replayAfter);
        }
        return result;
    }

    private static JSONObject executeMember(Runtime current, JSONObject request) {
        if (request == null) throw new IllegalArgumentException("request body is required");
        boolean coordinated = isCoordinated(request);
        JSONObject commandValue = new JSONObject(true);
        commandValue.putAll(request);
        commandValue.put("schemaVersion", TeamDefinition.SCHEMA_VERSION);
        commandValue.put("ownerChatterId", current.ownerId);
        List<StarweaveUploadStore.ResolvedUpload> resolvedUploads = new ArrayList<>();
        if ("send".equals(request.getString("action"))) {
            String teamId = required(request.getString("teamId"), "teamId");
            String memberId = required(request.getString("teamMemberId"), "teamMemberId");
            java.util.Optional<com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime> local =
                    current.manager.getRuntime(teamId);
            if (!coordinated || local.isPresent()) {
                local.orElseThrow(
                        () -> new IllegalArgumentException("Team not found"))
                        .getDefinition();
            }
            String sessionId = coordinated
                    ? required(request.getString("sessionId"), "sessionId")
                    : currentSessionId(current, teamId, memberId,
                    request.getString("sessionId"));
            JSONArray uploadIds = request.getJSONArray("uploadIds");
            JSONArray files = new JSONArray();
            if (uploadIds != null) {
                if (uploadIds.size() > 10) {
                    throw new IllegalArgumentException("at most 10 uploads per message");
                }
                for (int i = 0; i < uploadIds.size(); i++) {
                    StarweaveUploadStore.ResolvedUpload upload = UPLOADS.resolve(
                            uploadIds.getString(i), uploadOwner(teamId, memberId),
                            sessionId, 0L);
                    resolvedUploads.add(upload);
                    JSONObject file = new JSONObject(true);
                    file.put(upload.fileName, java.util.Base64.getEncoder()
                            .encodeToString(upload.bytes));
                    files.add(file);
                }
            }
            commandValue.put("files", files);
        }
        if (coordinated) {
            if (current.gateway == null) {
                throw new IllegalStateException("Starweave Team coordinator is unavailable");
            }
            String action = required(request.getString("action"), "action");
            JSONObject data = isReadOnlyMemberAction(action)
                    ? current.gateway.query("member", commandValue)
                    : current.gateway.mutate("member", commandValue);
            for (StarweaveUploadStore.ResolvedUpload upload : resolvedUploads) {
                UPLOADS.delete(upload.uploadId);
            }
            return success(data);
        }
        TeamMemberCommand command = GSON.fromJson(
                commandValue.toJSONString(), TeamMemberCommand.class);
        String requestId = textOr(request.getString("requestId"), UUID.randomUUID().toString());
        String action = required(request.getString("action"), "action");
        TeamCommandResult result;
        switch (action) {
            case "send": result = current.manager.send(requestId, command); break;
            case "cancel": result = current.manager.cancel(requestId, command); break;
            case "newSession": result = current.manager.newSession(requestId, command); break;
            case "memoryDream": result = current.manager.memoryDream(requestId, command); break;
            case "listSessions": result = current.manager.listSessions(requestId, command); break;
            case "status": result = current.manager.getStatus(requestId, command); break;
            case "context": result = current.manager.getContextUsage(requestId, command); break;
            case "history": result = current.manager.getSessionHistory(requestId, command); break;
            case "restore": result = current.manager.restoreSession(requestId, command); break;
            default: throw new IllegalArgumentException("unsupported Team member action: " + action);
        }
        if (result.isAccepted()) {
            for (StarweaveUploadStore.ResolvedUpload upload : resolvedUploads) {
                UPLOADS.delete(upload.uploadId);
            }
        }
        return result(result);
    }

    public static JSONObject upload(JSONObject request) {
        Runtime current = requireRuntime();
        if (request == null) throw new IllegalArgumentException("request body is required");
        String teamId = required(request.getString("teamId"), "teamId");
        String memberId = required(request.getString("teamMemberId"), "teamMemberId");
        String sessionId = isCoordinated(request)
                ? required(request.getString("sessionId"), "sessionId")
                : currentSessionId(current, teamId, memberId,
                request.getString("sessionId"));
        return UPLOADS.stage(uploadOwner(teamId, memberId), sessionId, 0L,
                request.getString("fileName"), request.getString("contentBase64"));
    }

    public static JSONObject resources(JSONObject request, boolean preview) {
        Runtime current = requireRuntime();
        TeamMemberCommand command = resourceCommand(current, request);
        String requestId = textOr(request.getString("requestId"),
                UUID.randomUUID().toString());
        return result(preview
                ? current.manager.previewSessionResource(requestId, command,
                request.getString("resourceId"))
                : current.manager.listSessionResources(requestId, command));
    }

    public static JSONObject previewTextFile(JSONObject request) {
        Runtime current = requireRuntime();
        if (request == null) throw new IllegalArgumentException("request is required");
        if (isCoordinated(request)) {
            JSONObject rejected = new JSONObject(true);
            rejected.put("accepted", false);
            rejected.put("code", "REMOTE_FILE_PREVIEW_UNSUPPORTED");
            rejected.put("message", "跨实例团队成员暂不支持文件预览");
            return rejected;
        }
        String teamId = required(request.getString("teamId"), "teamId");
        String memberId = required(request.getString("teamMemberId"), "teamMemberId");
        currentSessionId(current, teamId, memberId, request.getString("sessionId"));
        TeamMemberCommand command = resourceCommand(current, request);
        String requestId = textOr(request.getString("requestId"), UUID.randomUUID().toString());
        com.mola.cmd.proxy.app.acp.filepreview.TextFilePreviewResult preview =
                current.manager.readTextFile(requestId, command);
        return JSON.parseObject(GSON.toJson(preview));
    }

    public static com.mola.cmd.proxy.app.acp.team.TeamResourcePayload downloadResource(
            JSONObject request) {
        Runtime current = requireRuntime();
        return current.manager.downloadSessionResource(
                resourceCommand(current, request), request.getString("resourceId"));
    }

    private static TeamMemberCommand resourceCommand(Runtime current,
                                                      JSONObject request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        JSONObject value = new JSONObject(request);
        value.put("schemaVersion", TeamDefinition.SCHEMA_VERSION);
        value.put("ownerChatterId", current.ownerId);
        return GSON.fromJson(value.toJSONString(), TeamMemberCommand.class);
    }

    private static boolean isCoordinated(JSONObject request) {
        return request != null && (request.getBooleanValue("coordinated")
                || com.mola.cmd.proxy.app.acp.team.coordinator.TeamCoordinationBridge.isAssigned(request.getString("teamId")));
    }

    /** Returns true when the event belongs to Starweave and must not reach MolaChat. */
    public static boolean publishIfOwned(TeamEventEnvelope event) {
        Runtime current = runtime;
        if (current == null || event == null) return false;
        java.util.Optional<com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime> team =
                current.manager.getRuntime(event.getTeamId());
        if (!team.isPresent() || !current.ownerId.equals(
                team.get().getDefinition().getOwnerChatterId())) return false;
        JSONObject value = com.mola.cmd.proxy.app.acp.common.ToolOutputPreview.row(
                JSON.parseObject(GSON.toJson(event)));
        value.put("teamEventSeq", event.getEventSeq());
        synchronized (EVENTS) {
            value.put("eventSeq", EVENT_SEQUENCE.incrementAndGet());
            EVENTS.addLast(value);
            while (EVENTS.size() > EVENT_CAPACITY) EVENTS.removeFirst();
            EVENTS.notifyAll();
        }
        return true;
    }

    /** Mixed fragments require the registry coordinator's global projection. */
    public static boolean requiresCoordinator(TeamEventEnvelope event) {
        Runtime current = runtime;
        if (current == null || event == null) return false;
        java.util.Optional<com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime> team =
                current.manager.getRuntime(event.getTeamId());
        return team.isPresent() && current.ownerId.equals(
                team.get().getDefinition().getOwnerChatterId())
                && team.get().getDefinition().isMixedPlacement();
    }

    public static java.util.Map<String, String> acceptGatewayResult(
            String rpcRequestId, String[] args) {
        Runtime current = requireRuntime();
        if (current.gateway == null) {
            throw new IllegalStateException("Starweave Team coordinator is unavailable");
        }
        return current.gateway.acceptResult(rpcRequestId, args);
    }

    public static java.util.Map<String, String> acceptGatewayReady(
            String rpcRequestId, String[] args) {
        Runtime current = requireRuntime();
        if (current.gateway == null) {
            throw new IllegalStateException("Starweave Team coordinator is unavailable");
        }
        return current.gateway.acceptReady(rpcRequestId, args);
    }

    public static java.util.Map<String, String> acceptGatewayEvent(
            String rpcRequestId, String[] args) {
        if (args == null || args.length != 1) {
            throw new IllegalArgumentException("exactly one event argument is required");
        }
        Runtime current = requireRuntime();
        JSONObject wrapper = JSON.parseObject(args[0]);
        if (wrapper == null || !current.ownerId.equals(wrapper.getString("ownerChatterId"))) {
            throw new IllegalArgumentException("Starweave Team event owner mismatch");
        }
        JSONObject event = wrapper.getJSONObject("event");
        if (event == null) throw new IllegalArgumentException("event is required");
        String teamId = required(event.getString("teamId"), "event.teamId");
        com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime team = current.manager
                .getRuntime(teamId).orElseThrow(() ->
                        new IllegalArgumentException("coordinated Team not found"));
        TeamDefinition definition = team.getDefinition();
        if ((!definition.isMixedPlacement()
                && !com.mola.cmd.proxy.app.acp.team.coordinator.TeamCoordinationBridge.isAssigned(teamId))
                || !current.ownerId.equals(definition.getOwnerChatterId())) {
            throw new IllegalArgumentException("event does not belong to a mixed Starweave Team");
        }
        String memberId = event.getString("teamMemberId");
        if (memberId != null && !memberId.trim().isEmpty()
                && definition.getRoster().stream().noneMatch(contact ->
                memberId.equals(contact.getTargetTeamMemberId()))) {
            throw new IllegalArgumentException("event Team member is not in the roster");
        }
        appendEvent(event);
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        result.put("requestId", rpcRequestId == null ? "" : rpcRequestId);
        result.put("accepted", "true");
        result.put("code", "OK");
        return result;
    }

    /** Authenticated registry delivery also accepts the terminal event after local cleanup. */
    public static void projectCoordinatorEvent(String ownerId, JSONObject event) {
        Runtime current = requireRuntime();
        if (!current.ownerId.equals(ownerId)) throw new IllegalArgumentException("coordinator event owner mismatch");
        String teamId = required(event.getString("teamId"), "event.teamId");
        if (!com.mola.cmd.proxy.app.acp.team.coordinator.TeamCoordinationBridge.authorizesProjection(ownerId, event))
            throw new IllegalArgumentException("Team has no registry coordinator binding");
        java.util.Optional<com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime> local = current.manager.getRuntime(teamId);
        // Durable late events remain authorized by the binding after runtime cleanup.
        if (local.isPresent()) {
            TeamDefinition definition = local.get().getDefinition();
            if (!ownerId.equals(definition.getOwnerChatterId())) throw new IllegalArgumentException("Team owner mismatch");
            String memberId = event.getString("teamMemberId");
            if (memberId != null && !memberId.trim().isEmpty() && definition.getRoster().stream()
                    .noneMatch(contact -> memberId.equals(contact.getTargetTeamMemberId())))
                throw new IllegalArgumentException("event member not in roster");
        }
        appendEvent(event);
    }

    public static JSONArray events(long afterSeq, String teamId, String teamMemberId) {
        validateEventTarget(teamId, teamMemberId);
        JSONArray result = new JSONArray();
        synchronized (EVENTS) {
            for (JSONObject event : EVENTS) {
                if (event.getLongValue("eventSeq") <= afterSeq) continue;
                if (teamId != null && !teamId.equals(event.getString("teamId"))) continue;
                if (teamMemberId != null
                        && !teamMemberId.equals(event.getString("teamMemberId"))) continue;
                result.add(new JSONObject(event));
            }
        }
        return result;
    }

    /** Waits for the next filtered Team event batch without making ConfigUI poll. */
    public static JSONArray awaitEvents(long afterSeq, String teamId,
                                        String teamMemberId, long timeoutMillis) {
        validateEventTarget(teamId, teamMemberId);
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
        synchronized (EVENTS) {
            while (true) {
                JSONArray available = eventsLocked(afterSeq, teamId, teamMemberId);
                if (!available.isEmpty()) return available;
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) return available;
                try {
                    EVENTS.wait(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return available;
                }
            }
        }
    }

    private static JSONArray eventsLocked(long afterSeq, String teamId,
                                          String teamMemberId) {
        JSONArray result = new JSONArray();
        for (JSONObject event : EVENTS) {
            if (event.getLongValue("eventSeq") <= afterSeq) continue;
            if (teamId != null && !teamId.equals(event.getString("teamId"))) continue;
            if (teamMemberId != null
                    && !teamMemberId.equals(event.getString("teamMemberId"))) continue;
            result.add(new JSONObject(event));
        }
        return result;
    }

    private static void validateEventTarget(String teamId, String teamMemberId) {
        if (teamId == null || teamId.trim().isEmpty()) return;
        Runtime current = requireRuntime();
        com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime team = current.manager
                .getRuntime(teamId).orElseThrow(() ->
                        new IllegalArgumentException("Team not found"));
        if (!current.ownerId.equals(team.getDefinition().getOwnerChatterId())) {
            throw new IllegalArgumentException("Team does not belong to Starweave owner");
        }
        if (teamMemberId == null || teamMemberId.trim().isEmpty()) return;
        if (team.getDefinition().isMixedPlacement()) {
            for (com.mola.cmd.proxy.app.acp.team.model.TeamContactRef contact
                    : team.getDefinition().getRoster()) {
                if (teamMemberId.equals(contact.getTargetTeamMemberId())) return;
            }
            throw new IllegalArgumentException("Team member not found");
        }
        for (com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition member
                : team.getDefinition().getMembers()) {
            if (teamMemberId.equals(member.getTeamMemberId())) return;
        }
        throw new IllegalArgumentException("Team member not found");
    }

    private static String currentSessionId(Runtime current, String teamId,
                                           String memberId, String expectedSessionId) {
        com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime team = current.manager
                .getRuntime(teamId).orElseThrow(() ->
                        new IllegalArgumentException("Team not found"));
        if (!current.ownerId.equals(team.getDefinition().getOwnerChatterId())) {
            throw new IllegalArgumentException("Team does not belong to Starweave owner");
        }
        for (com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition member
                : team.getDefinition().getMembers()) {
            if (!memberId.equals(member.getTeamMemberId())) continue;
            String sessionId = required(member.getSessionId(), "member.sessionId");
            if (expectedSessionId != null && !expectedSessionId.trim().isEmpty()
                    && !sessionId.equals(expectedSessionId.trim())) {
                throw new IllegalArgumentException("Team member session has changed");
            }
            return sessionId;
        }
        throw new IllegalArgumentException("Team member not found");
    }

    private static String uploadOwner(String teamId, String memberId) {
        return "team:" + teamId + ":" + memberId;
    }

    private static JSONObject result(TeamCommandResult result) {
        return JSON.parseObject(GSON.toJson(result));
    }

    private static JSONObject success(JSONObject data) {
        JSONObject result = new JSONObject(true);
        result.put("accepted", true);
        result.put("code", "OK");
        result.put("message", "OK");
        result.put("data", data == null ? new JSONObject(true) : data);
        return result;
    }

    private static void appendEvent(JSONObject source) {
        JSONObject event = com.mola.cmd.proxy.app.acp.common.ToolOutputPreview.row(source);
        synchronized (EVENTS) {
            String eventId = event.getString("eventId");
            if (eventId != null) {
                for (JSONObject existing : EVENTS) {
                    if (eventId.equals(existing.getString("eventId"))) return;
                }
            }
            event.put("teamEventSeq", event.getLongValue("eventSeq"));
            event.put("eventSeq", EVENT_SEQUENCE.incrementAndGet());
            EVENTS.addLast(event);
            while (EVENTS.size() > EVENT_CAPACITY) EVENTS.removeFirst();
            EVENTS.notifyAll();
        }
    }

    private static String sourceIdentity(JSONObject source) {
        return textOr(source.getString("cmdProxyInstanceId"), "") + "\n"
                + textOr(source.getString("sourceGroupId"), "") + "\n"
                + textOr(source.getString("sourceRobotId"), "");
    }

    private static boolean isReadOnlyMemberAction(String action) {
        return "listSessions".equals(action) || "status".equals(action)
                || "context".equals(action) || "history".equals(action);
    }

    /**
     * A mixed-Team fragment contains only members hosted by this instance, while its
     * roster contains the complete cross-instance identity set. Preserve those remote
     * identities in the degraded UI projection so a coordinator outage is shown as an
     * availability problem instead of making Agents silently disappear.
     */
    private static void projectDegradedRoster(JSONObject team) {
        JSONArray members = team.getJSONArray("members");
        if (members == null) {
            members = new JSONArray();
            team.put("members", members);
        }
        Set<String> memberIds = new HashSet<>();
        for (int i = 0; i < members.size(); i++) {
            memberIds.add(members.getJSONObject(i).getString("teamMemberId"));
        }
        JSONArray roster = team.getJSONArray("roster");
        if (roster != null) {
            for (int i = 0; i < roster.size(); i++) {
                JSONObject contact = roster.getJSONObject(i);
                String memberId = contact.getString("targetTeamMemberId");
                if (memberId == null || !memberIds.add(memberId)) continue;
                JSONObject remote = new JSONObject(true);
                remote.put("teamMemberId", memberId);
                remote.put("acpClientId", contact.getString("targetAcpClientId"));
                remote.put("displayName", contact.getString("displayName"));
                remote.put("sourceRobotName", contact.getString("displayName"));
                remote.put("remark", contact.getString("remark"));
                remote.put("order", contact.getIntValue("order"));
                remote.put("state", "UNKNOWN");
                remote.put("remote", true);
                remote.put("coordinated", true);
                members.add(remote);
            }
        }
        members.sort(Comparator.comparingInt(value ->
                ((JSONObject) value).getIntValue("order")));
        team.put("coordinatorAvailable", false);
        team.put("degraded", true);
        team.put("coordinatorDelayed", true);
        team.put("coordinatorStateUnknown", true);
        team.put("degradedReason", "协调器暂不可达，成员状态未知");
    }

    private static JSONArray copyArray(JSONArray source) {
        return JSON.parseArray(source.toJSONString());
    }

    private static void mergeStaleTeams(JSONArray localTeams, JSONArray cachedTeams) {
        for (int i = 0; i < cachedTeams.size(); i++) {
            JSONObject stale = JSON.parseObject(cachedTeams.getJSONObject(i).toJSONString());
            stale.put("coordinated", true);
            markCoordinatorDelayed(stale);
            int localIndex = indexOfTeam(localTeams, stale.getString("teamId"));
            if (localIndex >= 0) localTeams.set(localIndex, stale);
            else localTeams.add(stale);
        }
    }

    private static void mergeStaleSources(JSONArray values, JSONArray cachedSources) {
        for (int i = 0; i < cachedSources.size(); i++) {
            JSONObject stale = JSON.parseObject(cachedSources.getJSONObject(i).toJSONString());
            stale.put("coordinated", true);
            stale.put("stale", true);
            String identity = sourceIdentity(stale);
            int existingIndex = indexOfSource(values, identity);
            if (existingIndex >= 0) values.set(existingIndex, stale);
            else values.add(stale);
        }
    }

    private static void markCoordinatorDelayed(JSONObject value) {
        value.put("coordinatorAvailable", false);
        value.put("coordinatorDelayed", true);
        value.put("stale", true);
        value.put("degraded", true);
        value.put("degradedReason", "协调器延迟，状态可能过期");
    }

    private static int indexOfSource(JSONArray values, String identity) {
        for (int i = 0; i < values.size(); i++) {
            if (identity.equals(sourceIdentity(values.getJSONObject(i)))) return i;
        }
        return -1;
    }

    private static TeamMemberSourceDescriptor findSource(
            List<TeamMemberSourceDescriptor> sources, String groupId) {
        for (TeamMemberSourceDescriptor source : sources) {
            if (groupId.equals(source.getSourceGroupId())) return source;
        }
        throw new IllegalArgumentException(
                "Team member must reference an available Starweave Fast Team source");
    }

    private static Runtime requireRuntime() {
        Runtime current = runtime;
        if (current == null) throw new IllegalStateException("Starweave Team service is not running");
        return current;
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static final class Runtime {
        final StarweaveRequestDeduplicator messageRequests = new StarweaveRequestDeduplicator();
        final TeamManager manager;
        final String ownerId;
        final String instanceId;
        final String transportGroup;
        final Supplier<List<TeamMemberSourceDescriptor>> sources;
        final StarweaveTeamGateway gateway;
        volatile JSONArray lastCoordinatedTeams;
        volatile JSONArray lastCoordinatedSources;

        Runtime(TeamManager manager, String instanceId,
                Supplier<List<TeamMemberSourceDescriptor>> sources,
                StarweaveTeamGateway gateway) {
            this.manager = manager;
            this.instanceId = instanceId;
            this.ownerId = StarweaveIdentity.ownerId(instanceId);
            this.transportGroup = "starweave-team:" + instanceId;
            this.sources = sources;
            this.gateway = gateway;
        }
    }
}
