package com.mola.cmd.proxy.app.acp.team.coordinator;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Single-center global authority; participant commands are never issued under owner locks. */
public final class MixedTeamCoordinator {
    public interface Transport {
        Map<String, JSONObject> describeParticipants();
        JSONObject command(String instanceId, String operation, JSONObject payload);
        void project(String homeInstanceId, String owner, JSONObject event);
    }
    private final CoordinationStore store;
    private final Transport transport;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, JSONObject> realtimeTeams = new ConcurrentHashMap<>();
    private final Map<String, Long> realtimeSessionVersions = new ConcurrentHashMap<>();
    public MixedTeamCoordinator(CoordinationStore store, Transport transport) {
        this.store = store; this.transport = transport;
        // A persisted READY projection is not proof that any participant is online
        // after the coordinator restarts. Reconcile every fragment before re-admitting work.
        for (JSONObject record : store.list("teams")) {
            realtimeTeams.put(record.getString("teamId"), copy(record));
            if ("READY".equals(record.getString("state"))) {
                record.put("state", "RECOVERING");
                for (Object value : record.getJSONArray("participants")) ((JSONObject) value).put("state", "RECOVERING");
                save(record);
            }
        }
    }

    public JSONObject execute(String owner, String home, String operation, JSONObject payload) {
        required(owner, "ownerChatterId"); required(home, "homeInstanceId");
        if (payload == null) payload = new JSONObject(true);
        switch (operation) {
            case "sources": return sources(owner, home);
            case "create": return create(owner, home, payload);
            case "list": return list(owner, home);
            case "get": return object("team", projection(owned(payload.getString("teamId"), owner, home)));
            case "delete": return delete(owner, home, payload);
            case "member": return member(owner, home, payload);
            case "cleanup": return cleanup(owner, home);
            case "update": throw new CoordinationException("MIXED_TEAM_UPDATE_UNSUPPORTED", "跨实例团队成员变更尚未开放");
            default: throw new CoordinationException("INVALID_ARGUMENT", "不支持的团队协调操作");
        }
    }

    public JSONObject sources(String owner, String home) {
        JSONArray result = new JSONArray();
        Map<String, JSONObject> descriptors = transport.describeParticipants();
        for (Map.Entry<String, JSONObject> entry : descriptors.entrySet()) {
            JSONObject descriptor = entry.getValue();
            if (home.equals(entry.getKey())) {
                for (Object value : array(descriptor, "teamMemberSources")) {
                    JSONObject source = (JSONObject) value;
                    if (owner.equals(source.getString("ownerChatterId")))
                        result.add(sourceView(source, entry.getKey(), descriptor, home, owner));
                }
            }
            for (Object value : array(descriptor, "remoteTeamMemberSources")) {
                JSONObject source = (JSONObject) value;
                if (owner.equals(source.getString("granteeOwnerChatterId"))
                        && entry.getKey().equals(source.getString("participantInstanceId")))
                    result.add(sourceView(source, entry.getKey(), descriptor, home, owner));
            }
        }
        return object("sources", result);
    }

    private JSONObject create(String owner, String home, JSONObject request) {
        String requestId = required(request.getString("requestId"), "requestId");
        String teamId = text(request.getString("teamId"), stable("team:" + requestId));
        String hash = digest(owner + "\n" + home + "\n" + canonical(request));
        synchronized (lock(teamId)) {
            JSONObject old = store.find("teams", teamId);
            if (old != null) {
                requireOwner(old, owner, home);
                if (!hash.equals(old.getString("payloadHash")))
                    throw new CoordinationException("IDEMPOTENCY_CONFLICT", "requestId 已用于不同的队伍请求");
                return object("team", projection(old));
            }
        }
        JSONArray selected = request.getJSONArray("members");
        if (selected == null || selected.isEmpty() || selected.size() > 10)
            throw new CoordinationException("VALIDATION_ERROR", "队伍成员数量必须为 1～10");
        JSONArray available = sources(owner, home).getJSONArray("sources");
        JSONArray members = new JSONArray();
        JSONArray participants = new JSONArray();
        Set<String> sourceIds = new HashSet<>(), memberIds = new HashSet<>(), instanceIds = new LinkedHashSet<>();
        for (int index = 0; index < selected.size(); index++) {
            JSONObject chosen = selected.getJSONObject(index);
            String instance = required(chosen.getString("cmdProxyInstanceId"), "cmdProxyInstanceId");
            JSONObject source = null;
            for (Object candidate : available) {
                JSONObject value = (JSONObject) candidate;
                if (instance.equals(value.getString("cmdProxyInstanceId"))
                        && Objects.equals(chosen.getString("sourceGroupId"), value.getString("sourceGroupId"))
                        && Objects.equals(chosen.getString("sourceRobotId"), value.getString("sourceRobotId"))) source = value;
            }
            if (source == null) throw new CoordinationException("UNAUTHORIZED", "所选 Agent 不可用或未共享给当前用户");
            if (!"AVAILABLE".equals(source.getString("status"))) throw new CoordinationException("TEAM_NOT_READY", "所选 Agent 当前离线或尚未就绪");
            if (!sourceIds.add(instance + "\n" + source.getString("sourceGroupId")))
                throw new CoordinationException("DUPLICATE_TEAM_MEMBER", "同一 Agent 来源不能重复加入队伍");
            String memberId = text(chosen.getString("teamMemberId"), stable("member:" + requestId + ":" + index));
            if (!memberIds.add(memberId)) throw new CoordinationException("DUPLICATE_TEAM_MEMBER", "成员 ID 重复");
            JSONObject member = copy(source);
            member.put("teamMemberId", memberId); member.put("acpClientId", "team-acp-" + memberId);
            member.put("robotId", "team-acp-" + memberId); member.put("order", index);
            member.put("remark", text(chosen.getString("remark"), source.getString("remark")));
            member.put("state", "STARTING"); member.put("status", "STARTING");
            members.add(member); instanceIds.add(instance);
        }
        if (!instanceIds.contains(home)) throw new CoordinationException("REMOTE_ONLY_TEAM", "队伍必须至少包含一位本机成员");
        String mode = text(request.getString("mode"), "NORMAL");
        String captain = request.getString("captainTeamMemberId");
        if (!"NORMAL".equals(mode) && !"CAPTAIN".equals(mode)) throw new CoordinationException("VALIDATION_ERROR", "无效的队伍模式");
        if ("NORMAL".equals(mode) && captain != null && !captain.trim().isEmpty())
            throw new CoordinationException("VALIDATION_ERROR", "普通模式不能指定队长");
        if ("CAPTAIN".equals(mode) && (members.size() < 2 || !memberIds.contains(captain)))
            throw new CoordinationException("VALIDATION_ERROR", "队长模式至少需要两位成员，且队长必须是队伍成员");
        JSONObject record = new JSONObject(true);
        record.put("teamId", teamId); record.put("ownerChatterId", owner); record.put("homeInstanceId", home);
        record.put("name", required(request.getString("name"), "name")); record.put("mode", mode);
        record.put("captainTeamMemberId", captain); record.put("requestId", requestId);
        record.put("payloadHash", hash); record.put("mixedPlacement", instanceIds.size() > 1);
        record.put("members", members); record.put("participants", participants);
        record.put("state", "CREATING"); record.put("createdAt", System.currentTimeMillis());
        for (String instance : instanceIds) {
            JSONObject participant = object("instanceId", instance);
            participant.put("state", "CREATING"); participant.put("createIntent", true);
            participants.add(participant);
        }
        synchronized (lock(teamId)) {
            JSONObject old = store.find("teams", teamId);
            if (old != null) {
                requireOwner(old, owner, home);
                if (!hash.equals(old.getString("payloadHash"))) throw new CoordinationException("IDEMPOTENCY_CONFLICT", "队伍请求冲突");
                return object("team", projection(old));
            }
            save(record);
        }
        try {
            for (Object value : participants) {
                JSONObject participant = (JSONObject) value;
                JSONObject data = transport.command(participant.getString("instanceId"), "create", fragment(record, participant));
                mergeSnapshot(teamId, participant.getString("instanceId"), data.getJSONObject("team"));
            }
        } catch (RuntimeException failure) {
            mutate(teamId, current -> { current.put("state", "PENDING_CLEANUP"); current.put("lastError", failure.getMessage()); });
            // Query and delete every attempted participant, including a timed-out one.
            // The intent was persisted before any network request was sent.
            reconcile(teamId);
            throw failure;
        }
        return object("team", projection(store.find("teams", teamId)));
    }

    private JSONObject fragment(JSONObject record, JSONObject participant) {
        JSONObject payload = base(record);
        payload.put("requestId", stable("fragment:" + record.getString("requestId") + ":" + participant.getString("instanceId")));
        payload.put("name", record.getString("name")); payload.put("mode", record.getString("mode"));
        payload.put("captainTeamMemberId", record.getString("captainTeamMemberId"));
        payload.put("mixedPlacement", record.getBooleanValue("mixedPlacement"));
        JSONArray local = new JSONArray(), roster = new JSONArray();
        for (Object value : record.getJSONArray("members")) {
            JSONObject member = (JSONObject) value;
            roster.add(copy(member));
            if (participant.getString("instanceId").equals(member.getString("cmdProxyInstanceId"))) local.add(copy(member));
        }
        payload.put("members", local);
        if (record.getBooleanValue("mixedPlacement")) payload.put("roster", roster);
        payload.put("coordinatorHomeInstanceId", record.getString("homeInstanceId"));
        return payload;
    }

    private JSONObject list(String owner, String home) {
        JSONArray teams = new JSONArray();
        for (JSONObject record : store.list("teams")) {
            if (owner.equals(record.getString("ownerChatterId")) && home.equals(record.getString("homeInstanceId"))) {
                if (!"DELETED".equals(record.getString("state"))) teams.add(projection(record));
            }
        }
        return object("teams", teams);
    }

    private JSONObject member(String owner, String home, JSONObject payload) {
        JSONObject record = owned(payload.getString("teamId"), owner, home);
        String action = required(payload.getString("action"), "action");
        if (!"READY".equals(record.getString("state")) && !"cancel".equals(action))
            throw new CoordinationException("TEAM_NOT_READY", "队伍当前不可操作");
        JSONObject target = findMember(record, payload.getString("teamMemberId"));
        JSONObject command = copy(payload); command.putAll(base(record));
        command.put("requestId", text(payload.getString("requestId"), UUID.randomUUID().toString()));
        JSONObject result = transport.command(target.getString("cmdProxyInstanceId"), action, command);
        long participantVersion = result.getLongValue("participantTeamVersion"); result.remove("participantTeamVersion");
        if (result.containsKey("sessionId")) mutate(record.getString("teamId"), current -> {
            JSONObject placement = participant(current, target.getString("cmdProxyInstanceId"));
            if (participantVersion > 0 && participantVersion < placement.getLongValue("version")) return;
            placement.put("version", Math.max(participantVersion, placement.getLongValue("version")));
            findMember(current, target.getString("teamMemberId")).put("sessionId", result.getString("sessionId"));
        });
        return result;
    }

    private JSONObject delete(String owner, String home, JSONObject payload) {
        JSONObject record = owned(payload.getString("teamId"), owner, home);
        String id = record.getString("teamId");
        mutate(id, current -> {
            if (!"DELETED".equals(current.getString("state"))) {
                Long expected = payload.getLong("expectedVersion");
                if (expected != null && expected.longValue() != current.getLongValue("version")
                        && !"DELETING".equals(current.getString("state")))
                    throw new CoordinationException("VERSION_CONFLICT", "队伍状态已更新，请刷新后重试");
                current.put("state", "DELETING");
                current.put("deleteRequestId", text(payload.getString("requestId"), stable("delete:" + id)));
            }
        });
        reconcile(id);
        return object("team", projection(store.find("teams", id)));
    }

    private JSONObject cleanup(String owner, String home) {
        JSONArray ids = new JSONArray();
        for (JSONObject record : store.list("teams")) {
            if (owner.equals(record.getString("ownerChatterId")) && home.equals(record.getString("homeInstanceId"))
                    && Arrays.asList("CREATING", "DELETING", "PENDING_CLEANUP", "FAILED").contains(record.getString("state"))) {
                delete(owner, home, object("teamId", record.getString("teamId")));
                ids.add(record.getString("teamId"));
            }
        }
        return object("teamIds", ids);
    }

    /** Called by a bounded background worker, not a command's owner lock. */
    public void reconcileAll() {
        for (JSONObject record : store.list("teams")) {
            if (!"DELETED".equals(record.getString("state"))) {
                try { reconcile(record.getString("teamId")); } catch (RuntimeException ignored) { }
            }
        }
    }

    private void reconcile(String teamId) {
        JSONObject snapshot = store.find("teams", teamId);
        if (snapshot == null || "DELETED".equals(snapshot.getString("state"))) return;
        boolean deleting = Arrays.asList("DELETING", "PENDING_CLEANUP", "FAILED").contains(snapshot.getString("state"));
        for (Object value : snapshot.getJSONArray("participants")) {
            JSONObject participant = (JSONObject) value;
            String instance = participant.getString("instanceId");
            if ("DELETED".equals(participant.getString("state"))) continue;
            try {
                JSONObject query = base(snapshot);
                query.put("requestId", stable("probe:" + teamId + ":" + instance));
                JSONObject data;
                if (deleting) {
                    query.put("requestId", stable("delete:" + text(snapshot.getString("deleteRequestId"), snapshot.getString("requestId")) + ":" + instance));
                    data = transport.command(instance, "delete", query);
                } else {
                    try { data = transport.command(instance, "get", query); }
                    catch (CoordinationException absent) {
                        if (!"NOT_FOUND".equals(absent.getCode()) || !"CREATING".equals(snapshot.getString("state"))) throw absent;
                        data = transport.command(instance, "create", fragment(snapshot, participant));
                    }
                }
                mergeSnapshot(teamId, instance, data.getJSONObject("team"));
            } catch (CoordinationException failure) {
                if (deleting && "NOT_FOUND".equals(failure.getCode())) setParticipant(teamId, instance, "DELETED");
                else setParticipant(teamId, instance, "RECOVERING");
            } catch (RuntimeException failure) { setParticipant(teamId, instance, "RECOVERING"); }
        }
    }

    public void acceptEvent(String instanceId, JSONObject event) {
        String id = required(event.getString("eventId"), "eventId");
        JSONObject record = store.find("teams", required(event.getString("teamId"), "teamId"));
        if (record == null || !hasParticipant(record, instanceId)) throw new CoordinationException("UNAUTHORIZED", "事件来源不属于该队伍");
        required(event.getString("type"), "type");
        String memberId = event.getString("teamMemberId");
        if (memberId != null && !memberId.isEmpty()
                && !instanceId.equals(findMember(record, memberId).getString("cmdProxyInstanceId")))
            throw new CoordinationException("UNAUTHORIZED", "事件成员不属于发送实例");
        JSONObject receipt = copy(event); receipt.put("sourceInstanceId", instanceId);
        synchronized (lock("event-" + id)) {
            if (store.find("receipts", id) != null || store.find("inbox", id) != null) return;
            store.enqueue("inbox", id, receipt, 10000);
        }
    }

    /** Read-only routing of display events; no durable inbox, receipts or network work. */
    JSONObject realtimeDestination(String instanceId, JSONObject event) {
        required(event.getString("eventId"), "eventId");
        if (!RegistryTeamCoordinator.isRealtimeEvent(event)) throw new CoordinationException("UNAUTHORIZED", "实时通道不能投递控制事件");
        JSONObject record = realtimeTeams.get(required(event.getString("teamId"), "teamId"));
        if (record == null || !hasParticipant(record, instanceId)) throw new CoordinationException("UNAUTHORIZED", "事件来源不属于该队伍");
        String memberId = required(event.getString("teamMemberId"), "teamMemberId");
        if (!instanceId.equals(findMember(record, memberId).getString("cmdProxyInstanceId")))
            throw new CoordinationException("UNAUTHORIZED", "事件成员不属于发送实例");
        if ("MEMBER_SESSION_CHANGED".equals(event.getString("type"))) {
            long version = event.getLongValue("teamVersion");
            long durable = participant(record, instanceId).getLongValue("version");
            long latest = realtimeSessionVersions.merge(record.getString("teamId") + ":" + memberId,
                    Math.max(version, durable), Math::max);
            if (version < latest) return object("ignored", true);
        }
        return object("homeInstanceId", record.getString("homeInstanceId"), "ownerChatterId", record.getString("ownerChatterId"));
    }

    public void drainEvents() {
        for (JSONObject event : store.list("inbox")) {
            String eventId = event.getString("eventId");
            try {
                JSONObject record = store.find("teams", event.getString("teamId"));
                if (record == null) continue;
                String source = event.getString("sourceInstanceId");
                if ("TALK_TO_ROUTE_REQUEST".equals(event.getString("type"))) routeTalkTo(record, source, event.getJSONObject("data"));
                else {
                    JSONObject data = event.getJSONObject("data");
                    if (data != null && data.getJSONObject("team") != null) mergeSnapshot(record.getString("teamId"), source, data.getJSONObject("team"));
                    if ("MEMBER_SESSION_CHANGED".equals(event.getString("type")) && data != null) {
                        JSONObject latestPlacement = participant(store.find("teams", record.getString("teamId")), source);
                        if (event.getLongValue("teamVersion") < latestPlacement.getLongValue("version")) {
                            store.save("receipts", eventId, object("timestamp", System.currentTimeMillis()));
                            store.delete("inbox", eventId);
                            continue;
                        }
                        mutate(record.getString("teamId"), current -> {
                            JSONObject placement = participant(current, source);
                            if (event.getLongValue("teamVersion") < placement.getLongValue("version")) return;
                            JSONObject member = findMember(current, event.getString("teamMemberId"));
                            if (!source.equals(member.getString("cmdProxyInstanceId"))) throw new CoordinationException("UNAUTHORIZED", "会话事件成员位置不匹配");
                            placement.put("version", event.getLongValue("teamVersion"));
                            if (data.getString("newSessionId") != null) member.put("sessionId", data.getString("newSessionId"));
                        });
                    }
                    if ("TEAM_CREATE_FAILED".equals(event.getString("type"))) {
                        mutate(record.getString("teamId"), current -> {
                            if (!"DELETED".equals(current.getString("state"))
                                    && event.getLongValue("teamVersion") >= participant(current, source).getLongValue("version")) {
                                current.put("state", "PENDING_CLEANUP"); current.put("lastError", "参与实例创建失败");
                            }
                        });
                    }
                    if ("TEAM_DELETED".equals(event.getString("type"))) setParticipant(record.getString("teamId"), source, "DELETED");
                    JSONObject latest = store.find("teams", record.getString("teamId"));
                    JSONObject projected = copy(event); projected.remove("sourceInstanceId");
                    String type = projected.getString("type");
                    if (type.startsWith("TEAM_") || "MEMBER_STATE_CHANGED".equals(type)) {
                        if ("TEAM_DELETED".equals(type) && !"DELETED".equals(latest.getString("state"))) projected.put("type", "TEAM_DELETE_ACCEPTED");
                        projected.put("data", object("team", projection(latest))); projected.put("teamVersion", latest.getLongValue("version"));
                    }
                    transport.project(record.getString("homeInstanceId"), record.getString("ownerChatterId"), projected);
                }
                store.save("receipts", eventId, object("timestamp", System.currentTimeMillis()));
                store.delete("inbox", eventId);
            } catch (CoordinationException failure) {
                if (Arrays.asList("DELIVERY_EXPIRED", "UNAUTHORIZED", "TEAM_COMMUNICATION_FORBIDDEN", "VALIDATION_ERROR", "NOT_FOUND", "TEAM_DELETED").contains(failure.getCode())) {
                    JSONObject receipt = object("timestamp", System.currentTimeMillis());
                    receipt.put("code", failure.getCode()); store.save("receipts", eventId, receipt);
                    store.delete("inbox", eventId);
                }
                // Transient failure retains the durable inbox for the next pass.
            } catch (RuntimeException retryLater) { /* Durable inbox is retained for the next pass. */ }
        }
    }

    private void routeTalkTo(JSONObject record, String sourceInstance, JSONObject data) {
        if ("DELETED".equals(record.getString("state"))) throw new CoordinationException("TEAM_DELETED", "队伍已删除");
        if (data == null || !"READY".equals(record.getString("state"))) throw new CoordinationException("TEAM_NOT_READY", "队伍尚未就绪");
        JSONObject sender = findMember(record, data.getString("senderTeamMemberId"));
        JSONObject target = findMember(record, data.getString("targetTeamMemberId"));
        if (!sourceInstance.equals(sender.getString("cmdProxyInstanceId"))) throw new CoordinationException("UNAUTHORIZED", "通信发送者身份不匹配");
        long now = System.currentTimeMillis();
        if (data.getIntValue("depth") < 1 || data.getIntValue("depth") > 5
                || data.getLongValue("createdAt") <= 0
                || data.getLongValue("createdAt") > now + 60000
                || data.getLongValue("expiresAt") <= now
                || data.getLongValue("expiresAt") <= data.getLongValue("createdAt")
                || data.getLongValue("expiresAt") - data.getLongValue("createdAt") > 30L * 60 * 1000)
            throw new CoordinationException("DELIVERY_EXPIRED", "通信消息已过期或深度无效");
        if (Objects.equals(sender.getString("teamMemberId"), target.getString("teamMemberId")))
            throw new CoordinationException("TEAM_COMMUNICATION_FORBIDDEN", "成员不能向自己通信");
        String captain = record.getString("captainTeamMemberId");
        if ("CAPTAIN".equals(record.getString("mode")) && !Objects.equals(captain, sender.getString("teamMemberId"))
                && !Objects.equals(captain, target.getString("teamMemberId"))) throw new CoordinationException("TEAM_COMMUNICATION_FORBIDDEN", "成员仅允许与队长通信");
        JSONObject command = copy(data); command.putAll(base(record));
        command.put("requestId", stable("talk-to:" + required(data.getString("messageId"), "messageId")));
        transport.command(target.getString("cmdProxyInstanceId"), "talkTo", command);
    }

    private void mergeSnapshot(String teamId, String instance, JSONObject fragment) {
        if (fragment == null) return;
        mutate(teamId, record -> {
            JSONObject participant = participant(record, instance);
            long version = fragment.getLongValue("version");
            if (version < participant.getLongValue("version")) return;
            participant.put("version", version);
            if (!"DELETED".equals(participant.getString("state"))) {
                String state = fragment.getString("state");
                participant.put("state", state != null && state.startsWith("DELETED") ? "DELETED" : state);
                if ("FAILED".equals(state) && !"DELETED".equals(record.getString("state"))) {
                    record.put("state", "PENDING_CLEANUP"); record.put("lastError", fragment.get("lastError"));
                }
            }
            for (Object value : array(fragment, "members")) {
                JSONObject local = (JSONObject) value;
                JSONObject global = findMember(record, local.getString("teamMemberId"));
                if (!instance.equals(global.getString("cmdProxyInstanceId"))) throw new CoordinationException("UNAUTHORIZED", "成员位置不匹配");
                for (String key : Arrays.asList("state", "status", "sessionId", "clientState", "lastError"))
                    if (local.containsKey(key)) global.put(key, local.get(key));
                if (local.getString("state") != null) global.put("status", local.getString("state"));
            }
            updateState(record);
        });
    }

    private void setParticipant(String teamId, String instance, String state) {
        mutate(teamId, record -> {
            JSONObject p = participant(record, instance);
            if (!"DELETED".equals(p.getString("state"))) p.put("state", state);
            updateState(record);
        });
    }

    private static void updateState(JSONObject record) {
        boolean ready = true, deleted = true;
        for (Object value : record.getJSONArray("participants")) {
            String state = ((JSONObject) value).getString("state");
            ready &= "READY".equals(state); deleted &= "DELETED".equals(state);
        }
        String state = record.getString("state");
        if (deleted) record.put("state", "DELETED");
        else if (!Arrays.asList("DELETING", "PENDING_CLEANUP", "FAILED", "DELETED").contains(state))
            record.put("state", ready ? "READY" : "CREATING".equals(state) ? "CREATING" : "RECOVERING");
    }

    private void mutate(String id, java.util.function.Consumer<JSONObject> change) {
        synchronized (lock(id)) {
            JSONObject current = store.find("teams", id);
            if (current != null) {
                String before = current.toJSONString(); change.accept(current);
                if (!before.equals(current.toJSONString())) save(current);
            }
        }
    }
    private void save(JSONObject record) {
        record.put("version", record.getLongValue("version") + 1);
        record.put("updatedAt", System.currentTimeMillis());
        store.save("teams", record.getString("teamId"), record);
        realtimeTeams.put(record.getString("teamId"), copy(record));
    }
    private JSONObject owned(String id, String owner, String home) {
        JSONObject record = store.find("teams", required(id, "teamId"));
        if (record == null) throw new CoordinationException("NOT_FOUND", "队伍不存在");
        requireOwner(record, owner, home); return record;
    }
    private static void requireOwner(JSONObject record, String owner, String home) {
        if (!owner.equals(record.getString("ownerChatterId")) || !home.equals(record.getString("homeInstanceId")))
            throw new CoordinationException("UNAUTHORIZED", "队伍不属于当前用户或发起实例");
    }
    private static JSONObject findMember(JSONObject record, String id) {
        for (Object value : record.getJSONArray("members")) if (Objects.equals(id, ((JSONObject) value).getString("teamMemberId"))) return (JSONObject) value;
        throw new CoordinationException("NOT_FOUND", "队伍成员不存在");
    }
    private static JSONObject participant(JSONObject record, String instance) {
        for (Object value : record.getJSONArray("participants")) if (instance.equals(((JSONObject) value).getString("instanceId"))) return (JSONObject) value;
        throw new CoordinationException("UNAUTHORIZED", "实例不属于队伍");
    }
    private static boolean hasParticipant(JSONObject record, String instance) {
        for (Object value : record.getJSONArray("participants")) if (instance.equals(((JSONObject) value).getString("instanceId"))) return true;
        return false;
    }
    private static JSONObject projection(JSONObject record) {
        JSONObject view = copy(record);
        for (String key : Arrays.asList("payloadHash", "participants", "requestId", "deleteRequestId")) view.remove(key);
        view.put("status", record.getString("state")); view.put("coordinated", true);
        for (Object value : array(view, "members")) {
            JSONObject member = (JSONObject) value;
            if (member.getString("state") != null) member.put("status", member.getString("state"));
        }
        return view;
    }
    private static JSONObject sourceView(JSONObject source, String instance, JSONObject descriptor, String home, String owner) {
        JSONObject view = copy(source); view.put("ownerChatterId", owner); view.put("cmdProxyInstanceId", instance);
        view.put("transportGroup", descriptor.getString("transportGroup")); view.put("coordinated", true);
        view.put("sourceType", home.equals(instance) ? "HOME" : "REMOTE");
        view.put("sourceLabel", home.equals(instance) ? "本环境" : "远程环境 · 共享 Agent");
        boolean online = !descriptor.containsKey("coordinationOnline") || descriptor.getBooleanValue("coordinationOnline");
        view.put("status", online && descriptor.getBooleanValue("businessCommandsReady") ? "AVAILABLE" : "TEAM_NOT_READY");
        view.put("mixedSupported", true); view.put("discoverySupported", true);
        return view;
    }
    private static JSONObject base(JSONObject record) {
        JSONObject value = object("schemaVersion", "1"); value.put("teamId", record.getString("teamId")); value.put("ownerChatterId", record.getString("ownerChatterId"));
        value.put("coordinatorHomeInstanceId", record.getString("homeInstanceId")); return value;
    }
    private Object lock(String id) { return locks.computeIfAbsent(id, ignored -> new Object()); }
    public static JSONObject object(String key, Object value) { JSONObject object = new JSONObject(true); object.put(key, value); return object; }
    static JSONObject object(String key, Object value, String otherKey, Object otherValue) {
        JSONObject object = object(key, value); object.put(otherKey, otherValue); return object;
    }
    public static JSONObject copy(JSONObject value) { return JSON.parseObject(value.toJSONString()); }
    private static JSONArray array(JSONObject object, String key) { JSONArray value = object.getJSONArray(key); return value == null ? new JSONArray() : value; }
    private static String required(String value, String field) { if (value == null || value.trim().isEmpty()) throw new CoordinationException("VALIDATION_ERROR", field + " 不能为空"); return value.trim(); }
    private static String text(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value.trim(); }
    private static String stable(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString(); }
    private static String canonical(Object value) {
        if (value instanceof Map) {
            Map<String, Object> sorted = new TreeMap<>();
            ((Map<?, ?>) value).forEach((key, item) -> sorted.put(String.valueOf(key), JSON.parse(canonical(item))));
            return JSON.toJSONString(sorted);
        }
        if (value instanceof List) { JSONArray result = new JSONArray(); for (Object item : (List<?>) value) result.add(JSON.parse(canonical(item))); return result.toJSONString(); }
        return JSON.toJSONString(value);
    }
    private static String digest(String value) {
        try { byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(); for (byte b : bytes) result.append(String.format("%02x", b & 255)); return result.toString(); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
