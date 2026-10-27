package com.mola.cmd.proxy.app.acp.channel;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelBinding;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelConfig;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelEvent;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelReplyRoute;
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToMessage;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ChannelUserBehaviorTest {

    @Test
    public void queueModeKeepsBusyTurnAndQueuesNewMessage() throws Exception {
        Fixture fixture = fixture(ChannelConfig.USER_BEHAVIOR_QUEUE);

        TalkToDispatcher.InboundDeliveryResult result = fixture.bridge.onEvent(event());

        assertEquals(TalkToDispatcher.InboundDeliveryResult.Status.QUEUED, result.getStatus());
        assertEquals(0, fixture.client.cancelCount);
        assertEquals(0, fixture.dispatcher.cancelCountAtDelivery);
        assertNotNull(fixture.dispatcher.pollInbox("bound-group"));
    }

    @Test
    public void interruptModeCancelsBusyTurnBeforeQueuingNewMessage() throws Exception {
        Fixture fixture = fixture(ChannelConfig.USER_BEHAVIOR_INTERRUPT);

        TalkToDispatcher.InboundDeliveryResult result = fixture.bridge.onEvent(event());

        assertEquals(TalkToDispatcher.InboundDeliveryResult.Status.QUEUED, result.getStatus());
        assertEquals(1, fixture.client.cancelCount);
        assertEquals(1, fixture.dispatcher.cancelCountAtDelivery);
        TalkToMessage queued = fixture.dispatcher.pollInbox("bound-group");
        assertNotNull(queued);
        assertEquals("hello", queued.getContent());
    }

    @Test
    public void channelMessageRecoversFailedClientBeforeDirectDelivery() throws Exception {
        Fixture fixture = fixture(ChannelConfig.USER_BEHAVIOR_QUEUE);
        fixture.client.failState(false);
        assertEquals(TalkToDispatcher.InboundDeliveryResult.Status.DIRECT,
                fixture.bridge.onEvent(event()).getStatus());
        assertEquals(1, fixture.client.recoveryCount);
        assertEquals(1, fixture.client.sendCount);
    }

    @Test
    public void recoveryFailureRejectsChannelMessageInsteadOfLeavingItInInbox() throws Exception {
        Fixture fixture = fixture(ChannelConfig.USER_BEHAVIOR_QUEUE);
        fixture.client.failState(true);
        assertEquals(TalkToDispatcher.InboundDeliveryResult.Status.REJECTED,
                fixture.bridge.onEvent(event()).getStatus());
        assertEquals(1, fixture.client.recoveryCount);
        assertEquals(0, fixture.client.sendCount);
        org.junit.Assert.assertNull(fixture.dispatcher.pollInbox("bound-group"));
    }

    private static Fixture fixture(String userBehavior) throws Exception {
        ChannelBinding binding = new ChannelBinding();
        binding.setType(ChannelBinding.TYPE_MAIN);
        binding.setInstanceId("instance");
        binding.setGroupId("bound-group");
        ChannelConfig config = new ChannelConfig();
        config.setId("wecom-main");
        config.setType(ChannelConfig.TYPE_WECOM_WS);
        config.setEnabled(true);
        config.setUserBehavior(userBehavior);
        config.setBinding(binding);
        Map<String, ChannelConfig> configs = new HashMap<>();
        configs.put(config.getId(), config);
        RecordingBusyClient client = new RecordingBusyClient();
        RecordingDispatcher dispatcher = new RecordingDispatcher(client);
        ChannelTalkToGateway gateway = new ChannelTalkToGateway(
                Collections.emptyMap(), configs);
        ChannelTalkToBridge bridge = new ChannelTalkToBridge(
                configs, groupId -> client, dispatcher, gateway);
        return new Fixture(bridge, dispatcher, client);
    }

    private static ChannelEvent event() {
        return new ChannelEvent("wecom-main", "message-1", "user-1", "sender", "hello",
                new ChannelReplyRoute("request-1", "message-1", "user-1", "chat-1",
                        "group", System.currentTimeMillis() + 60_000));
    }

    private static final class Fixture {
        private final ChannelTalkToBridge bridge;
        private final RecordingDispatcher dispatcher;
        private final RecordingBusyClient client;

        private Fixture(ChannelTalkToBridge bridge, RecordingDispatcher dispatcher,
                        RecordingBusyClient client) {
            this.bridge = bridge;
            this.dispatcher = dispatcher;
            this.client = client;
        }
    }

    private static final class RecordingDispatcher extends TalkToDispatcher {
        private final RecordingBusyClient client;
        private int cancelCountAtDelivery = -1;

        private RecordingDispatcher(RecordingBusyClient client) {
            super(Collections.emptyMap(), AcpClientRegistry.getInstance(),
                    Collections.emptyMap());
            this.client = client;
        }

        @Override public InboundDeliveryResult deliverInbound(
                String routingKey, AcpClient targetClient, TalkToMessage message) {
            cancelCountAtDelivery = client.cancelCount;
            return super.deliverInbound(routingKey, targetClient, message);
        }
    }

    private static final class RecordingBusyClient extends AcpClient {
        private int cancelCount;
        private int recoveryCount;
        private int sendCount;
        private boolean failRecovery;
        private void failState(boolean fail) { state.set(State.ERROR); failRecovery = fail; }
        @Override public synchronized boolean prepareForMessage() throws IOException {
            if (getState() != State.ERROR) return super.prepareForMessage();
            recoveryCount++;
            if (failRecovery) throw new IOException("planned restore failure");
            state.set(State.READY);
            return true;
        }
        @Override public void send(String input, java.util.List<Map<String, String>> files,
                com.mola.cmd.proxy.app.acp.acpclient.PromptOptions options) { sendCount++; }


        private RecordingBusyClient() throws IOException {
            super(Files.createTempDirectory("channel-user-behavior-").toString(),
                    "bound-group", robot());
            state.set(State.BUSY);
        }

        @Override public void cancel() {
            cancelCount++;
        }

        private static AcpRobotParam robot() {
            AcpRobotParam robot = new AcpRobotParam();
            robot.setName("robot");
            return robot;
        }
    }
}
