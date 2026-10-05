package com.mola.cmd.proxy.app.acp.team.coordinator;

import com.alibaba.fastjson.JSONObject;
import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.*;
import com.mola.cmd.proxy.app.acp.configui.ConfigUiServer;
import com.mola.cmd.proxy.app.acp.registry.RegistryManager;
import com.mola.cmd.proxy.app.acp.starweave.*;
import com.mola.cmd.proxy.app.acp.team.*;
import com.mola.cmd.proxy.app.acp.team.event.*;
import com.mola.cmd.proxy.app.acp.team.listener.TeamAcpResponseListener;
import com.mola.cmd.proxy.app.acp.team.protocol.*;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToRequest;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Separate-JVM integration fixture: real registry, Netty tunnel, TeamManager and REST;
 * only the model provider is a deterministic test client. No MolaChat connection is created. */
public final class RegistryTeamTestNode {
    public static void main(String[] args) throws Exception {
        String instance = CmdProxyHome.instanceId();
        int port = Integer.parseInt(args[0]);
        ConfigUiServer server = new ConfigUiServer(port, () -> { }, ignored -> { });
        server.start();
        java.lang.reflect.Field field = ConfigUiServer.class.getDeclaredField("registryManager");
        field.setAccessible(true); RegistryManager registry = (RegistryManager) field.get(server);
        AcpRobotParam robot = new AcpRobotParam(); robot.setName("Agent"); robot.setEnabled(true);
        robot.setWorkDir(CmdProxyHome.resolve("workspace").toString());
        robot.setTeamSharedWithChatterIds(Arrays.asList("starweave-center", "starweave-remote", "user"));
        java.nio.file.Files.createDirectories(CmdProxyHome.resolve("workspace"));
        String localGroup = StarweaveIdentity.identity(instance, "Agent").getLogicalId();
        String shared = TeamSharedSourceIds.groupId(instance, "acp-Agent");
        Map<String, AcpRobotParam> robots = new HashMap<>(); robots.put(localGroup, robot); robots.put(shared, robot);
        MapTeamSourceRobotResolver resolver = new MapTeamSourceRobotResolver(robots);
        TeamClientRegistry clients = new TeamClientRegistry();
        AtomicReference<TeamManager> holder = new AtomicReference<>();
        TeamEventSink sink = event -> {
            if (!TeamCoordinationBridge.capture(event)) StarweaveTeamApiBridge.publishIfOwned(event);
        };
        TeamStartupCoordinator startup = new TeamStartupCoordinator(resolver,
                (runtime, member, snapshot, options, created) -> {
                    AcpClientIdentity identity = AcpClientIdentity.team(member.getAcpClientId(), runtime.getDefinition().getTransportGroup(),
                            "team/" + runtime.getDefinition().getTeamId() + "/" + member.getTeamMemberId(), runtime.getDefinition().getOwnerChatterId(),
                            runtime.getDefinition().getTeamId(), member.getTeamMemberId(), member.getSourceRobotName());
                    TestClient client = new TestClient(identity, snapshot.copyRobotParam(),
                            options.getTargetRestoreSessionId() == null ? "session-" + UUID.randomUUID() : options.getTargetRestoreSessionId());
                    created.accept(client);
                    TeamAcpResponseListener listener = new TeamAcpResponseListener(runtime, member, sink,
                            (team, id, state, error) -> holder.get().onMemberState(team, id, state, error), client.getHistoryManager());
                    client.setGlobalListener(listener);
                    client.finish = listener::onClientReady;
                    client.route = text -> holder.get().getOrCreateTalkToDispatcher(runtime.getDefinition().getTeamId())
                            .deliver(new TalkToRequest(text.substring("talk:".length()), "coordination-test", 0),
                                    member.getTeamMemberId(), "", null);
                    return client;
                }, clients, 2, 5000);
        TeamManager manager = new TeamManager(new TeamStore(CmdProxyHome.resolve("teams")), clients, resolver, sink, startup);
        holder.set(manager);
        List<TeamMemberSourceDescriptor> sources = Arrays.asList(
                new TeamMemberSourceDescriptor(StarweaveIdentity.ownerId(instance), localGroup, "acp-Agent", "Agent", "Agent", "", "", false),
                new TeamMemberSourceDescriptor("user", localGroup, "acp-Agent", "Agent", "Agent", "", "", false));
        List<RemoteTeamMemberSourceDescriptor> remote = Arrays.asList(
                new RemoteTeamMemberSourceDescriptor("starweave-center", instance, shared, "acp-Agent", "Agent", "Agent", "", ""),
                new RemoteTeamMemberSourceDescriptor("starweave-remote", instance, shared, "acp-Agent", "Agent", "Agent", "", ""),
                new RemoteTeamMemberSourceDescriptor("user", instance, shared, "acp-Agent", "Agent", "Agent", "", ""));
        TeamTransportDescriptor descriptor = TeamTransportDescriptor.readyForBusiness(instance, sources, remote);
        TeamCoordinationBridge.installParticipant(manager, () -> descriptor);
        StarweaveTeamApiBridge.install(manager, instance, () -> Collections.singletonList(sources.get(0)),
                new StarweaveTeamGateway(instance, descriptor.getTransportGroup()));
        manager.recoverPersistedDefinitions(descriptor.getTransportGroup());
        JSONObject settings = new JSONObject();
        if ("center".equals(instance)) { settings.put("serverEnabled", true); settings.put("tunnelPort", Integer.parseInt(args[1])); }
        else { settings.put("clientEnabled", true); settings.put("centerUrl", args[1]); }
        registry.configure(settings);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { manager.close(); server.stop(); }));
        System.out.println("TEST_NODE_READY " + instance + " " + port);
        new java.util.concurrent.CountDownLatch(1).await();
    }
    private static final class TestClient extends AcpClient {
        Runnable finish;
        java.util.function.Function<String, String> route;
        TestClient(AcpClientIdentity identity, AcpRobotParam robot, String session) {
            super(robot.getWorkDir(), identity, robot); state.set(State.READY); setSessionId(session);
            getHistoryManager().restoreState(session); getHistoryManager().saveLastSessionId(session);
        }
        @Override public void send(String text, List<Map<String, String>> files, PromptOptions options) {
            getGlobalListener().onMessage(text.startsWith("talk:") ? route.apply(text) : "echo:" + text);
            if (files != null) for (Map<String, String> file : files)
                file.forEach((name, content) -> getGlobalListener().onMessage("attachments:" + name + "=" + content));
            JsonObject update = new JsonObject(); update.addProperty("result", "test");
            getGlobalListener().onToolCall("tool-test", "Test tool", "completed", update);
            getGlobalListener().onComplete("done"); finish.run();
        }
        @Override public void cancel() { }
        @Override public void close() { getHistoryManager().forceFlush(getSessionId()); state.set(State.CLOSED); }
    }
}
