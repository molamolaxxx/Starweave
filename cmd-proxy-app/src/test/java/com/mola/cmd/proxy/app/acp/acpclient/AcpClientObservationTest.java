package com.mola.cmd.proxy.app.acp.acpclient;

import static org.junit.Assert.*;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager;
import com.mola.cmd.proxy.app.acp.action.ActionRuntimeRegistry;
import com.mola.cmd.proxy.app.acp.observation.ObservationManager;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleOwnerKey;

import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.*;

public class AcpClientObservationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void chineseMolaChatClientsInitializeWithObservationEnabledOrDisabled() throws Exception {
        try (ObservationManager manager =
                new ObservationManager(temporary.newFolder("unicode-observations").toPath())) {
            for (String name : new String[]{"定时任务-MM", "定时任务-ML"}) {
                for (boolean enabled : new boolean[]{false, true}) {
                    AcpRobotParam robot = new AcpRobotParam();
                    robot.setName(name);
                    robot.setObservationEnabled(enabled);
                    String groupId = "1785566835011cSLOSacp-" + name;
                    AcpClient client = createClient(groupId, robot);
                    try {
                        AcpClientIdentity identity = client.getClientIdentity();
                        ScheduleOwnerKey owner = ScheduleOwnerKey.main(
                                identity.getOwnerId() == null ? identity.getLogicalId() : identity.getOwnerId(),
                                identity.getSurface(), identity.getLogicalId(), name);
                        client.setObservationSupport(manager, owner, prompt -> false);
                        assertEquals(groupId, owner.getLogicalId());
                        assertEquals(enabled,
                                client.availableActionTools().contains("manage_observation_channels"));
                    } finally {
                        client.close();
                    }
                }
            }
            assertEquals(0, manager.owners().getAsJsonArray("items").size());
        }
    }

    @Test
    public void gatesToolsAndRejectsCallsFromOldDisabledSessions() throws Exception {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName("assistant");
        assertFalse(robot.isObservationEnabled());
        try (ObservationManager manager =
                new ObservationManager(temporary.newFolder("observations").toPath())) {
            AcpClient client = createClient("observation-test", robot);
            try {
                client.setObservationSupport(
                        manager, ScheduleOwnerKey.main("assistant"), prompt -> false);
                assertFalse(client.availableActionTools().contains("manage_observation_channels"));
                robot.setObservationEnabled(true);
                assertTrue(
                        client.availableActionTools()
                                .containsAll(
                                        Arrays.asList(
                                                "manage_observation_channels",
                                                "test_observation_script",
                                                "query_observation_events")));
                robot.setObservationEnabled(false);
                try {
                    ActionRuntimeRegistry.getInstance()
                            .execute(
                                    client.getAuthSessionId(),
                                    "manage_observation_channels",
                                    new JsonObject());
                    fail();
                } catch (IllegalStateException expected) {
                    assertTrue(expected.getMessage().contains("TOOL_NOT_AVAILABLE"));
                }
            } finally {
                client.close();
            }
            assertEquals(0, manager.owners().getAsJsonArray("items").size());
        }
    }

    @Test
    public void rejectsBusyObservationAdmissionWithoutOverwritingTurn() throws Exception {
        AcpClient client = createClient("observation-busy", new AcpRobotParam());
        try {
            client.state.set(AbstractAcpClient.State.BUSY);
            assertFalse(client.trySendObservation("变化事件"));
            assertEquals(AbstractAcpClient.State.BUSY, client.getState());
        } finally {
            client.close();
        }
    }

    private AcpClient createClient(String id, AcpRobotParam robot) throws Exception {
        AcpClientIdentity identity = AcpClientIdentity.main(id, id, robot.getName());
        return new AcpClient(
                new FakeProvider(),
                temporary.newFolder().getAbsolutePath(),
                identity,
                robot,
                new ConversationHistoryManager(identity, temporary.newFolder().toPath()));
    }

    private static final class FakeProvider implements AgentProvider {
        public String getName() {
            return "observation-test";
        }

        public String getCommand() {
            return "unused";
        }

        public String[] getArgs() {
            return new String[0];
        }

        public List<Path> getMcpConfigPaths(String workspace) {
            return Collections.emptyList();
        }
    }
}
