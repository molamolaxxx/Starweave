package com.mola.cmd.proxy.app.acp.starweave;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.team.MapTeamSourceRobotResolver;
import com.mola.cmd.proxy.app.acp.team.TeamClientRegistry;
import com.mola.cmd.proxy.app.acp.team.TeamManager;
import com.mola.cmd.proxy.app.acp.team.TeamStore;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberState;
import com.mola.cmd.proxy.app.acp.team.model.TeamMode;
import com.mola.cmd.proxy.app.acp.team.model.TeamState;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberSourceDescriptor;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamCreateCommand;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberCreateSpec;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamRosterMemberSpec;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StarweaveTeamApiBridgeTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void createsFromConfiguredSourcesWithoutAnyMainSession() throws Exception {
        String instanceId = "instance-test";
        String ownerId = StarweaveIdentity.ownerId(instanceId);
        AcpRobotParam normal = robot("Normal", false);
        AcpRobotParam teamOnly = robot("Team Only", true);
        TeamMemberSourceDescriptor normalSource = source(instanceId, ownerId, normal);
        TeamMemberSourceDescriptor teamOnlySource = source(instanceId, ownerId, teamOnly);
        List<TeamMemberSourceDescriptor> sources =
                Arrays.asList(normalSource, teamOnlySource);
        Map<String, AcpRobotParam> robots = new LinkedHashMap<>();
        robots.put(normalSource.getSourceGroupId(), normal);
        robots.put(teamOnlySource.getSourceGroupId(), teamOnly);
        TeamManager manager = new TeamManager(
                new TeamStore(temporary.newFolder("teams").toPath()),
                new TeamClientRegistry(), new MapTeamSourceRobotResolver(robots));
        StarweaveTeamApiBridge.install(manager, instanceId, () -> sources);
        try {
            JSONObject discovered = StarweaveTeamApiBridge.sources();
            assertEquals(2, discovered.getJSONArray("sources").size());
            assertTrue(discovered.getJSONArray("sources").getJSONObject(1)
                    .getBooleanValue("onlyTeamMember"));

            JSONArray members = new JSONArray();
            JSONObject normalMember = member(normalSource);
            normalMember.put("remark", "负责产品需求分析");
            members.add(normalMember);
            members.add(member(teamOnlySource));
            JSONObject request = new JSONObject(true);
            request.put("requestId", "create-no-main-session");
            request.put("teamId", "team-no-main-session");
            request.put("name", "No MAIN Required");
            request.put("members", members);

            JSONObject result = StarweaveTeamApiBridge.create(request);

            assertTrue(result.getBooleanValue("accepted"));
            assertEquals("ACCEPTED", result.getString("code"));
            assertEquals(1, manager.snapshotDefinitions().size());
            assertEquals(2, manager.snapshotDefinitions().get(0).getMembers().size());
            assertEquals("负责产品需求分析", manager.snapshotDefinitions().get(0)
                    .getMembers().get(0).getRemark());
        } finally {
            StarweaveTeamApiBridge.clear(manager);
            manager.close();
        }
    }

    @Test
    public void updatesLocalTeamMemberRemarks() throws Exception {
        String instanceId = "instance-edit";
        String ownerId = StarweaveIdentity.ownerId(instanceId);
        TeamStore store = new TeamStore(temporary.newFolder("edit-remarks").toPath());
        TeamManager manager = new TeamManager(store, new TeamClientRegistry(),
                new MapTeamSourceRobotResolver(java.util.Collections.emptyMap()));
        TeamDefinition definition = TeamDefinition.creating("team-edit", ownerId,
                "Editable", "team-acp-" + instanceId, "create-edit",
                Arrays.asList(
                        new TeamMemberDefinition("member-a", "source-a", "A", "A",
                                "old a", "fingerprint-a"),
                        new TeamMemberDefinition("member-b", "source-b", "B", "B",
                                "old b", "fingerprint-b")), 100L);
        store.saveTeam(definition);
        manager.recoverPersistedDefinitions();
        StarweaveTeamApiBridge.install(manager, instanceId,
                java.util.Collections::emptyList);
        try {
            JSONObject remarks = new JSONObject(true);
            remarks.put("member-a", "new a");
            remarks.put("member-b", "new b");
            JSONObject request = new JSONObject(true);
            request.put("requestId", "update-edit");
            request.put("teamId", "team-edit");
            request.put("expectedVersion", 1L);
            request.put("memberRemarks", remarks);

            JSONObject result = StarweaveTeamApiBridge.update(request);

            assertTrue(result.getBooleanValue("accepted"));
            TeamDefinition updated = store.loadTeam("team-edit").get();
            assertEquals("new a", updated.getMembers().get(0).getRemark());
            assertEquals("new b", updated.getRoster().get(1).getRemark());
        } finally {
            StarweaveTeamApiBridge.clear(manager);
            manager.close();
        }
    }

    @Test
    public void rejectsSourceOutsideTheStarweaveCatalog() throws Exception {
        TeamManager manager = new TeamManager(
                new TeamStore(temporary.newFolder("rejected").toPath()),
                new TeamClientRegistry(), new MapTeamSourceRobotResolver(
                        java.util.Collections.emptyMap()));
        StarweaveTeamApiBridge.install(manager, "instance-test",
                java.util.Collections::emptyList);
        try {
            JSONObject request = new JSONObject(true);
            JSONArray members = new JSONArray();
            JSONObject member = new JSONObject(true);
            member.put("sourceGroupId", "foreign-group");
            member.put("sourceRobotId", "acp-Foreign");
            members.add(member);
            request.put("members", members);
            try {
                StarweaveTeamApiBridge.create(request);
                fail("foreign source must be rejected");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("available Starweave"));
            }
        } finally {
            StarweaveTeamApiBridge.clear(manager);
            manager.close();
        }
    }

    @Test
    public void rejectsCoordinatedFilePreviewWithoutReadingTheLocalFilesystem()
            throws Exception {
        TeamManager manager = new TeamManager(
                new TeamStore(temporary.newFolder("remote-preview").toPath()),
                new TeamClientRegistry(), new MapTeamSourceRobotResolver(
                java.util.Collections.emptyMap()));
        StarweaveTeamApiBridge.install(manager, "instance-test",
                java.util.Collections::emptyList);
        try {
            JSONObject request = new JSONObject(true);
            request.put("coordinated", true);
            request.put("target", "/etc/passwd");

            JSONObject result = StarweaveTeamApiBridge.previewTextFile(request);

            assertFalse(result.getBooleanValue("accepted"));
            assertEquals("REMOTE_FILE_PREVIEW_UNSUPPORTED", result.getString("code"));
        } finally {
            StarweaveTeamApiBridge.clear(manager);
            manager.close();
        }
    }

    @Test
    public void coordinatedMixedEventsAcceptRemoteRosterMembersAndRejectWrongOwner()
            throws Exception {
        String instanceId = "instance-home";
        String ownerId = StarweaveIdentity.ownerId(instanceId);
        AcpRobotParam local = robot("Local", false);
        TeamMemberSourceDescriptor localSource = source(instanceId, ownerId, local);
        Map<String, AcpRobotParam> robots = new LinkedHashMap<>();
        robots.put(localSource.getSourceGroupId(), local);
        TeamManager manager = new TeamManager(
                new TeamStore(temporary.newFolder("mixed-events").toPath()),
                new TeamClientRegistry(), new MapTeamSourceRobotResolver(robots));
        StarweaveTeamApiBridge.install(manager, instanceId,
                () -> java.util.Collections.singletonList(localSource));
        try {
            TeamCreateCommand command = new TeamCreateCommand("1", "request-mixed",
                    "team-mixed", ownerId, "Mixed",
                    java.util.Collections.singletonList(new TeamMemberCreateSpec(
                            "member-local", localSource.getSourceRobotId(),
                            localSource.getSourceGroupId(), 0)), true,
                    Arrays.asList(
                            new TeamRosterMemberSpec("member-local", "team-acp-member-local",
                                    "Local", "", 0),
                            new TeamRosterMemberSpec("member-remote", "team-acp-member-remote",
                                    "Remote", "", 1)));
            assertTrue(manager.create(command, "team-acp-" + instanceId).isAccepted());
            TeamEventEnvelope localEvent = TeamEventEnvelope.next(
                    manager.getRuntime("team-mixed").get(), "member-local",
                    "team-acp-member-local", TeamEventType.MESSAGE_COMPLETE,
                    java.util.Collections.singletonMap("content", "done"));
            assertTrue(StarweaveTeamApiBridge.requiresCoordinator(localEvent));
            JSONObject offlineList = StarweaveTeamApiBridge.list();
            JSONObject degraded = offlineList.getJSONObject("data").getJSONArray("teams")
                    .getJSONObject(0);
            assertTrue(degraded.getBooleanValue("coordinated"));
            assertTrue(degraded.getBooleanValue("degraded"));
            assertFalse(degraded.getBooleanValue("coordinatorAvailable"));
            assertEquals(2, degraded.getJSONArray("members").size());
            JSONObject unavailableRemote = degraded.getJSONArray("members")
                    .getJSONObject(1);
            assertEquals("member-remote", unavailableRemote.getString("teamMemberId"));
            assertEquals("UNKNOWN", unavailableRemote.getString("state"));
            assertTrue(degraded.getBooleanValue("coordinatorStateUnknown"));

            JSONObject remoteEvent = new JSONObject(true);
            remoteEvent.put("eventId", "remote-event-1");
            remoteEvent.put("eventSeq", 7L);
            remoteEvent.put("transportGroup", "team-acp-instance-remote");
            remoteEvent.put("teamId", "team-mixed");
            remoteEvent.put("teamMemberId", "member-remote");
            remoteEvent.put("acpClientId", "team-acp-member-remote");
            remoteEvent.put("type", "MESSAGE_COMPLETE");
            JSONObject wrapper = new JSONObject(true);
            wrapper.put("ownerChatterId", ownerId);
            wrapper.put("event", remoteEvent);
            assertEquals("true", StarweaveTeamApiBridge.acceptGatewayEvent(
                    "rpc-event", new String[]{wrapper.toJSONString()}).get("accepted"));
            assertEquals(1, StarweaveTeamApiBridge.events(
                    0L, "team-mixed", "member-remote").size());

            wrapper.put("ownerChatterId", "starweave-forged");
            try {
                StarweaveTeamApiBridge.acceptGatewayEvent(
                        "rpc-forged", new String[]{wrapper.toJSONString()});
                fail("wrong Starweave owner must be rejected");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("owner mismatch"));
            }
        } finally {
            StarweaveTeamApiBridge.clear(manager);
            manager.close();
        }
    }

    @Test
    public void listMergesLocalTeamsWithCoordinatorMixedTeams() throws Exception {
        String instanceId = "instance-home";
        String ownerId = StarweaveIdentity.ownerId(instanceId);
        AcpRobotParam localRobot = robot("Local", false);
        TeamMemberSourceDescriptor localSource = source(instanceId, ownerId, localRobot);
        Map<String, AcpRobotParam> robots = new LinkedHashMap<>();
        robots.put(localSource.getSourceGroupId(), localRobot);
        TeamManager manager = new TeamManager(
                new TeamStore(temporary.newFolder("merged-list").toPath()),
                new TeamClientRegistry(), new MapTeamSourceRobotResolver(robots));
        assertTrue(manager.create(new TeamCreateCommand("1", "request-local",
                "team-local", ownerId, "Local Team",
                java.util.Collections.singletonList(new TeamMemberCreateSpec(
                        "member-local", localSource.getSourceRobotId(),
                        localSource.getSourceGroupId(), 0))),
                "team-acp-" + instanceId).isAccepted());

        final StarweaveTeamGateway[] holder = new StarweaveTeamGateway[1];
        holder[0] = new StarweaveTeamGateway(instanceId, "team-acp-" + instanceId,
                200L, 200L, (command, group, callback) -> {
            JSONObject mixed = new JSONObject(true);
            mixed.put("teamId", "team-mixed");
            mixed.put("ownerChatterId", ownerId);
            mixed.put("name", "Mixed Team");
            mixed.put("state", "READY");
            mixed.put("members", new JSONArray());
            JSONObject data = new JSONObject(true);
            data.put("teams", new JSONArray(
                    java.util.Collections.singletonList(mixed)));
            JSONObject response = new JSONObject(true);
            response.put("requestId", callback.getCmdId());
            response.put("accepted", true);
            response.put("code", "OK");
            response.put("data", data);
            holder[0].acceptResult("rpc-result",
                    new String[]{response.toJSONString()});
        });
        JSONObject ready = new JSONObject(true);
        ready.put("instanceId", instanceId);
        ready.put("ownerChatterId", ownerId);
        holder[0].acceptReady("ready", new String[]{ready.toJSONString()});
        StarweaveTeamApiBridge.install(manager, instanceId,
                () -> java.util.Collections.singletonList(localSource), holder[0]);
        try {
            JSONArray teams = StarweaveTeamApiBridge.list()
                    .getJSONObject("data").getJSONArray("teams");

            assertEquals(2, teams.size());
            assertEquals("team-local", teams.getJSONObject(0).getString("teamId"));
            assertEquals("team-mixed", teams.getJSONObject(1).getString("teamId"));
            assertTrue(teams.getJSONObject(1).getBooleanValue("coordinated"));
        } finally {
            StarweaveTeamApiBridge.clear(manager);
            manager.close();
        }
    }

    @Test
    public void coordinatorTimeoutKeepsLastReadyRosterAndMarksItStale() throws Exception {
        String instanceId = "instance-home";
        TeamManager manager = new TeamManager(
                new TeamStore(temporary.newFolder("stale-cache").toPath()),
                new TeamClientRegistry(), new MapTeamSourceRobotResolver(
                java.util.Collections.emptyMap()));
        AtomicInteger calls = new AtomicInteger();
        final StarweaveTeamGateway[] holder = new StarweaveTeamGateway[1];
        holder[0] = new StarweaveTeamGateway(instanceId, "team-acp-" + instanceId,
                20L, 20L, (command, group, callback) -> {
            if (calls.incrementAndGet() != 1) return;
            JSONObject member = new JSONObject(true);
            member.put("teamMemberId", "member-remote");
            member.put("state", "READY");
            JSONObject team = new JSONObject(true);
            team.put("teamId", "team-mixed");
            team.put("state", "READY");
            team.put("members", new JSONArray(
                    java.util.Collections.singletonList(member)));
            JSONObject data = new JSONObject(true);
            data.put("teams", new JSONArray(
                    java.util.Collections.singletonList(team)));
            JSONObject response = new JSONObject(true);
            response.put("requestId", callback.getCmdId());
            response.put("accepted", true);
            response.put("data", data);
            holder[0].acceptResult("rpc-result",
                    new String[]{response.toJSONString()});
        });
        JSONObject ready = new JSONObject(true);
        ready.put("instanceId", instanceId);
        ready.put("ownerChatterId", StarweaveIdentity.ownerId(instanceId));
        holder[0].acceptReady("ready", new String[]{ready.toJSONString()});
        StarweaveTeamApiBridge.install(manager, instanceId,
                java.util.Collections::emptyList, holder[0]);
        try {
            StarweaveTeamApiBridge.list();
            JSONObject stale = StarweaveTeamApiBridge.list().getJSONObject("data")
                    .getJSONArray("teams").getJSONObject(0);
            assertEquals("READY", stale.getString("state"));
            assertEquals("READY", stale.getJSONArray("members")
                    .getJSONObject(0).getString("state"));
            assertTrue(stale.getBooleanValue("coordinatorDelayed"));
            assertTrue(stale.getBooleanValue("stale"));
            assertFalse(stale.getBooleanValue("coordinatorAvailable"));
        } finally {
            StarweaveTeamApiBridge.clear(manager);
            manager.close();
        }
    }

    @Test
    public void coordinatorPreservesLocalSourceAndReceivesStableMixedSelection()
            throws Exception {
        String instanceId = "instance-home";
        String ownerId = StarweaveIdentity.ownerId(instanceId);
        AcpRobotParam local = robot("Local", false);
        TeamMemberSourceDescriptor localSource = source(instanceId, ownerId, local);
        Map<String, AcpRobotParam> robots = new LinkedHashMap<>();
        robots.put(localSource.getSourceGroupId(), local);
        TeamManager manager = new TeamManager(
                new TeamStore(temporary.newFolder("coordinated-sources").toPath()),
                new TeamClientRegistry(), new MapTeamSourceRobotResolver(robots));
        AtomicReference<JSONObject> createPayload = new AtomicReference<>();
        final StarweaveTeamGateway[] holder = new StarweaveTeamGateway[1];
        holder[0] = new StarweaveTeamGateway(instanceId, "team-acp-" + instanceId,
                200L, 200L, (command, group, callback) -> {
            JSONObject data = new JSONObject(true);
            String operation = callback.getResultMap().get("operation");
            if ("sources".equals(operation)) {
                JSONArray coordinated = new JSONArray();
                JSONObject replacement = sourceJson(localSource, instanceId);
                replacement.put("transportGroup", "team-acp-" + instanceId);
                replacement.put("sourceLabel", "本环境 · Starweave");
                coordinated.add(replacement);
                JSONObject remote = new JSONObject(true);
                remote.put("cmdProxyInstanceId", "instance-remote");
                remote.put("transportGroup", "team-acp-instance-remote");
                remote.put("sourceGroupId", "team-shared-instance-remote-acp-Remote");
                remote.put("sourceRobotId", "acp-Remote");
                remote.put("displayName", "Remote");
                remote.put("sourceLabel", "远程环境 · 共享 Agent");
                coordinated.add(remote);
                data.put("sources", coordinated);
            } else if ("create".equals(operation)) {
                createPayload.set(JSON.parseObject(
                        callback.getResultMap().get("payload")));
                JSONObject team = new JSONObject(true);
                team.put("teamId", "team-coordinated");
                data.put("team", team);
            } else {
                fail("unexpected gateway operation: " + operation);
            }
            JSONObject response = new JSONObject(true);
            response.put("requestId", callback.getCmdId());
            response.put("accepted", true);
            response.put("code", "OK");
            response.put("data", data);
            holder[0].acceptResult("rpc-result",
                    new String[]{response.toJSONString()});
        });
        JSONObject ready = new JSONObject(true);
        ready.put("instanceId", instanceId);
        ready.put("ownerChatterId", ownerId);
        holder[0].acceptReady("ready", new String[]{ready.toJSONString()});
        StarweaveTeamApiBridge.install(manager, instanceId,
                () -> java.util.Collections.singletonList(localSource), holder[0]);
        try {
            JSONObject discovery = StarweaveTeamApiBridge.sources();
            JSONArray sources = discovery.getJSONArray("sources");
            assertEquals(2, sources.size());
            assertEquals("starweave-team:" + instanceId,
                    sources.getJSONObject(0).getString("transportGroup"));
            assertFalse(sources.getJSONObject(0).getBooleanValue("coordinated"));
            assertTrue(sources.getJSONObject(1).getBooleanValue("coordinated"));
            JSONArray localSources = discovery.getJSONArray("localSources");
            assertEquals(1, localSources.size());
            assertFalse(localSources.getJSONObject(0).getBooleanValue("coordinated"));
            assertEquals(localSource.getSourceGroupId(),
                    localSources.getJSONObject(0).getString("sourceGroupId"));

            JSONArray members = new JSONArray();
            for (int i = 0; i < sources.size(); i++) {
                JSONObject selected = new JSONObject(sources.getJSONObject(i));
                selected.put("remark", i == 0 ? "本地实现" : "远程复核");
                members.add(selected);
            }
            JSONObject request = new JSONObject(true);
            request.put("requestId", "create-coordinated");
            request.put("name", "Mixed");
            request.put("members", members);

            JSONObject result = StarweaveTeamApiBridge.create(request);

            assertTrue(result.getBooleanValue("accepted"));
            assertEquals("team-coordinated", result.getJSONObject("data")
                    .getJSONObject("team").getString("teamId"));
            assertEquals("instance-remote", createPayload.get()
                    .getJSONArray("members").getJSONObject(1)
                    .getString("cmdProxyInstanceId"));
            assertEquals("远程复核", createPayload.get()
                    .getJSONArray("members").getJSONObject(1)
                    .getString("remark"));
        } finally {
            StarweaveTeamApiBridge.clear(manager);
            manager.close();
        }
    }

    @Test
    public void captainProductConversationAdmitsCaptainAndOrdinaryMembers()
            throws Exception {
        String instanceId = "instance-product-entry";
        String ownerId = StarweaveIdentity.ownerId(instanceId);
        TeamStore store = new TeamStore(temporary.newFolder("captain-product-entry").toPath());
        TeamManager manager = new TeamManager(store, new TeamClientRegistry(),
                new MapTeamSourceRobotResolver(java.util.Collections.emptyMap()));
        List<TeamMemberDefinition> members = Arrays.asList(
                new TeamMemberDefinition("captain", "source-captain", "Captain",
                        "Captain", "coordinates", "fingerprint-captain")
                        .withState(TeamMemberState.READY, "session-captain", null),
                new TeamMemberDefinition("ordinary", "source-ordinary", "Ordinary",
                        "Ordinary", "implements", "fingerprint-ordinary")
                        .withState(TeamMemberState.READY, "session-ordinary", null));
        TeamDefinition creating = TeamDefinition.creating(
                "team-product-entry", ownerId, "Captain Team",
                "team-acp-" + instanceId, "create-product-entry", members,
                false, null, TeamMode.CAPTAIN, "captain", 1L);
        TeamDefinition ready = creating.transitionTo(TeamState.READY, null, 2L);
        store.saveTeam(creating);
        store.saveTeam(ready);
        java.lang.reflect.Method attach = TeamManager.class.getDeclaredMethod(
                "attachPersistedDefinition", TeamDefinition.class);
        attach.setAccessible(true);
        assertTrue((Boolean) attach.invoke(manager, ready));
        StarweaveTeamApiBridge.install(manager, instanceId,
                java.util.Collections::emptyList);
        try {
            JSONObject captain = productSend("team-product-entry", "captain",
                    "session-captain");
            JSONObject ordinary = productSend("team-product-entry", "ordinary",
                    "session-ordinary");

            // No clients are attached in this fixture. Reaching CLIENT_CLOSED proves both
            // product conversation targets passed Team/owner/member/session admission.
            assertEquals("CLIENT_CLOSED", captain.getString("code"));
            assertEquals("CLIENT_CLOSED", ordinary.getString("code"));
        } finally {
            StarweaveTeamApiBridge.clear(manager);
            manager.close();
        }
    }

    @Test
    public void sendRetryUsesOneCoordinatorMutationAndHistoryReturnsReplayBoundary() throws Exception {
        String instance = "chat-projection";
        TeamManager manager = new TeamManager(
                new TeamStore(temporary.newFolder("chat-projection").toPath()),
                new TeamClientRegistry(), new MapTeamSourceRobotResolver(
                        java.util.Collections.emptyMap()));
        java.util.concurrent.atomic.AtomicInteger sends = new java.util.concurrent.atomic.AtomicInteger();
        final StarweaveTeamGateway[] gateway = new StarweaveTeamGateway[1];
        gateway[0] = new StarweaveTeamGateway(instance, "team-acp-" + instance,
                200L, 200L, (command, group, callback) -> {
            JSONObject payload = JSON.parseObject(callback.getResultMap().get("payload"));
            JSONObject data = new JSONObject(true);
            data.put("sessionId", "new-session");
            if ("send".equals(payload.getString("action"))) {
                sends.incrementAndGet();
                assertEquals("send-unique", payload.getString("clientMessageId"));
            } else {
                data.put("messages", new JSONArray());
            }
            JSONObject response = new JSONObject(true);
            response.put("requestId", callback.getCmdId());
            response.put("accepted", true);
            response.put("data", data);
            gateway[0].acceptResult("rpc-result", new String[]{response.toJSONString()});
        });
        JSONObject ready = new JSONObject(true);
        ready.put("instanceId", instance);
        ready.put("ownerChatterId", StarweaveIdentity.ownerId(instance));
        gateway[0].acceptReady("ready", new String[]{ready.toJSONString()});
        StarweaveTeamApiBridge.install(manager, instance, java.util.Collections::emptyList, gateway[0]);
        try {
            JSONObject request = new JSONObject(true);
            request.put("requestId", "send-unique");
            request.put("clientMessageId", "send-unique");
            request.put("coordinated", true);
            request.put("action", "send");
            request.put("teamId", "team-remote");
            request.put("teamMemberId", "member-remote");
            request.put("sessionId", "old-session");
            request.put("message", "你好");
            JSONObject first = StarweaveTeamApiBridge.member(request);
            assertEquals(first, StarweaveTeamApiBridge.member(request));
            assertEquals(1, sends.get());
            request.put("requestId", "history-unique");
            request.put("action", "history");
            JSONObject snapshot = StarweaveTeamApiBridge.member(request).getJSONObject("data");
            assertEquals("new-session", snapshot.getString("sessionId"));
            assertTrue(snapshot.containsKey("replayAfter"));
        } finally {
            StarweaveTeamApiBridge.clear(manager);
            manager.close();
        }
    }

    private static JSONObject productSend(String teamId, String memberId,
                                          String sessionId) {
        JSONObject request = new JSONObject(true);
        request.put("requestId", "send-" + memberId);
        request.put("action", "send");
        request.put("teamId", teamId);
        request.put("teamMemberId", memberId);
        request.put("sessionId", sessionId);
        request.put("message", "hello " + memberId);
        return StarweaveTeamApiBridge.member(request);
    }

    private static AcpRobotParam robot(String name, boolean teamOnly) {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName(name);
        robot.setEnabled(true);
        robot.setOnlyTeamMember(teamOnly);
        robot.setWorkDir(".");
        return robot;
    }

    private static TeamMemberSourceDescriptor source(
            String instanceId, String ownerId, AcpRobotParam robot) {
        return new TeamMemberSourceDescriptor(ownerId,
                StarweaveIdentity.identity(instanceId, robot.getName()).getLogicalId(),
                StarweaveIdentity.acpId(robot.getName()), robot.getName(),
                robot.getName(), "", "", robot.isOnlyTeamMember());
    }

    private static JSONObject member(TeamMemberSourceDescriptor source) {
        JSONObject member = new JSONObject(true);
        member.put("sourceGroupId", source.getSourceGroupId());
        member.put("sourceRobotId", source.getSourceRobotId());
        return member;
    }

    private static JSONObject sourceJson(TeamMemberSourceDescriptor source,
                                         String instanceId) {
        JSONObject value = new JSONObject(true);
        value.put("cmdProxyInstanceId", instanceId);
        value.put("ownerChatterId", source.getOwnerChatterId());
        value.put("sourceGroupId", source.getSourceGroupId());
        value.put("sourceRobotId", source.getSourceRobotId());
        value.put("robotName", source.getRobotName());
        value.put("displayName", source.getDisplayName());
        value.put("avatar", source.getAvatar());
        value.put("remark", source.getRemark());
        value.put("onlyTeamMember", source.isOnlyTeamMember());
        return value;
    }
}
