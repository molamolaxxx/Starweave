package com.mola.cmd.proxy.app.acp.acpclient;

import com.mola.cmd.proxy.app.acp.acpclient.agent.KiroCliAgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager;
import com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelTurnContext;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class AcpClientTaskTurnTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void activeTaskIdentityPreventsUnrelatedBusyTurnCancellation() throws Exception {
        AcpClient client = client();
        client.state.set(AbstractAcpClient.State.BUSY);
        PromptOptions task = PromptOptions.forTask(
                "task-1", "event-1", 3L, 4L, 2L, false);
        bindAccepted(client, task);

        assertTrue(client.isActiveTask("task-1"));
        assertFalse(client.isActiveTask("task-2"));
        assertSame(task, client.getActiveTaskOptions());

        bindAccepted(client, PromptOptions.defaults());
        assertFalse(client.isActiveTask("task-1"));
        assertNull(client.getActiveTaskOptions());
    }

    @Test public void matchingTaskCancellationUsesAcpSessionCancel() throws Exception {
        AcpClient client = client();
        client.state.set(AbstractAcpClient.State.BUSY);
        bindAccepted(client, PromptOptions.forTask(
                "task-1", "event-1", 3L, 4L, 2L, false));
        StringWriter protocol = new StringWriter();
        client.writer = new BufferedWriter(protocol);
        client.sessionId = "task-session";

        client.cancelForQueuedWork();

        assertTrue(protocol.toString().contains("\"method\":\"session/cancel\""));
        assertTrue(protocol.toString().contains("\"sessionId\":\"task-session\""));
    }

    @Test public void internalPromptOptionsAreNotClassifiedAsVisibleUserInput() {
        assertEquals(ContextMessage.UserOrigin.USER,
                AcpClient.historyOrigin(PromptOptions.defaults()));
        assertEquals(ContextMessage.UserOrigin.TASK,
                AcpClient.historyOrigin(PromptOptions.forTask(
                        "task-1", "event-1", 1L, 1L, 1L, false)));
        assertEquals(ContextMessage.UserOrigin.TASK,
                AcpClient.historyOrigin(PromptOptions.forTaskNotification()));
        assertEquals(ContextMessage.UserOrigin.CHANNEL,
                AcpClient.historyOrigin(PromptOptions.forChannelReply(
                        new ChannelTurnContext("wecom", "reply", "group", "chat-1"))));
        assertEquals(ContextMessage.UserOrigin.TALK_TO,
                AcpClient.historyOrigin(PromptOptions.defaults().setInboundTalkTo(true)));
        assertEquals(ContextMessage.UserOrigin.SCHEDULE,
                AcpClient.historyOrigin(PromptOptions.forScheduleExecution()));
    }

    @Test public void claimBindsExistingExecutionWithoutSchedulingAnotherPrompt() throws Exception {
        AcpClient client=client();client.state.set(AbstractAcpClient.State.BUSY);PromptOptions options=PromptOptions.defaults();bindAccepted(client,options);
        Field f=AcpClient.class.getDeclaredField("activeMcpOptions");f.setAccessible(true);((AtomicReference<PromptOptions>)f.get(client)).set(options);
        com.alibaba.fastjson.JSONObject response=client.claimTaskInActiveTurn(previous->{assertNull(previous);com.alibaba.fastjson.JSONObject result=com.alibaba.fastjson.JSON.parseObject("{claimed:true,continueExecution:true,task:{id:'claimed-task',revision:2,contentVersion:1}}");return result;});
        assertTrue(response.getBooleanValue("claimed"));assertTrue(client.isActiveTask("claimed-task"));assertFalse(client.isActiveTask("other-task"));assertSame(options,client.getActiveTaskOptions());
        StringWriter protocol=new StringWriter();client.writer=new BufferedWriter(protocol);client.sessionId="session";client.cancelForQueuedWork();assertTrue(protocol.toString().contains("session/cancel"));assertFalse(protocol.toString().contains("session/prompt"));
    }
    @Test public void interruptedClaimKeepsOwnershipButDoesNotPromiseContinuation() throws Exception {
        AcpClient client=client();client.state.set(AbstractAcpClient.State.BUSY);PromptOptions options=PromptOptions.defaults();bindAccepted(client,options);
        Field f=AcpClient.class.getDeclaredField("activeMcpOptions");f.setAccessible(true);((AtomicReference<PromptOptions>)f.get(client)).set(options);
        com.alibaba.fastjson.JSONObject result=client.claimTaskInActiveTurn(previous->{client.state.set(AbstractAcpClient.State.READY);return com.alibaba.fastjson.JSON.parseObject("{claimed:true,continueExecution:true,task:{id:'task',revision:2,contentVersion:1}}");});
        assertTrue(result.getBooleanValue("claimed"));assertFalse(result.getBooleanValue("continueExecution"));assertFalse(client.isActiveTask("task"));
    }

    @Test public void taskCallerCredentialIsRedactedFromDebugPayload() throws Exception {
        AcpClient client=client();java.lang.reflect.Method method=AbstractAcpClient.class.getDeclaredMethod("redactSensitiveJson",com.google.gson.JsonObject.class);method.setAccessible(true);
        com.google.gson.JsonObject payload=com.google.gson.JsonParser.parseString("{\"mcpServers\":[{\"headers\":[{\"name\":\"Authorization\",\"value\":\"Bearer task-caller-secret\"}]}]}").getAsJsonObject();
        String redacted=(String)method.invoke(client,payload);assertFalse(redacted.contains("task-caller-secret"));assertTrue(redacted.contains("[REDACTED]"));assertTrue(payload.toString().contains("task-caller-secret"));
    }

    @Test public void publishingFromBoundTaskAutomaticallyCapturesSourceAndResolvesExecutor() throws Exception {
        AcpClient client=client();client.state.set(AbstractAcpClient.State.BUSY);PromptOptions options=PromptOptions.defaults();bindAccepted(client,options);
        Field active=AcpClient.class.getDeclaredField("activeMcpOptions");active.setAccessible(true);((AtomicReference<PromptOptions>)active.get(client)).set(options);
        com.alibaba.fastjson.JSONObject identity=com.alibaba.fastjson.JSON.parseObject("{instanceId:'instance',agentId:'worker',groupId:'group'}");
        try(com.mola.cmd.proxy.app.acp.task.service.TaskService service=new com.mola.cmd.proxy.app.acp.task.service.TaskService(temporary.newFolder("task-source").toPath(),"instance")) {
            com.alibaba.fastjson.JSONObject parent=service.publish(com.alibaba.fastjson.JSON.parseObject("{name:'Parent',requestId:'parent'}"),identity).getJSONObject("task");
            com.mola.cmd.proxy.app.acp.task.mcp.ClientTaskCaller caller=new com.mola.cmd.proxy.app.acp.task.mcp.ClientTaskCaller(client,()->identity,ref->{assertEquals("agent-target",ref);return com.alibaba.fastjson.JSON.parseObject("{type:'AGENT',agentId:'target',groupId:'target'}");},()->{});
            caller.claim(service,com.alibaba.fastjson.JSON.parseObject("{requestId:'claim'}"));
            com.alibaba.fastjson.JSONObject result=caller.publish(service,com.alibaba.fastjson.JSON.parseObject("{name:'Child',requestId:'child',assignee_ref:'agent-target'}"));
            assertEquals(parent.getString("id"),result.getJSONObject("sourceTask").getString("id"));assertEquals("target",result.getJSONObject("task").getJSONObject("target").getString("agentId"));assertEquals(parent.getString("id"),client.getActiveTaskId());
        }finally{client.close();}
    }

    private AcpClient client() throws Exception {
        AcpClientIdentity identity = AcpClientIdentity.main(
                "task-group", "Task Agent", "Task Agent");
        ConversationHistoryManager history = new ConversationHistoryManager(
                identity, temporary.newFolder().toPath());
        return new AcpClient(new KiroCliAgentProvider(), ".", identity, null, history);
    }

    @SuppressWarnings("unchecked")
    private static void bindAccepted(AcpClient client, PromptOptions options) throws Exception {
        Field field = AcpClient.class.getDeclaredField("acceptedPromptOptions");
        field.setAccessible(true);
        ((AtomicReference<PromptOptions>) field.get(client)).set(options);
    }
}
