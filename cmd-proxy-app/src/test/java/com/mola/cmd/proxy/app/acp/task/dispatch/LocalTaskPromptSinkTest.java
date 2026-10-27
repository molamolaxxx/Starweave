package com.mola.cmd.proxy.app.acp.task.dispatch;

import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class LocalTaskPromptSinkTest {
    @Test
    public void opensMissingStarweaveSessionAndKeepsReceiptPendingUntilReady() {
        AtomicInteger opens = new AtomicInteger();
        LocalTaskPromptSink sink = new LocalTaskPromptSink(AcpClientRegistry.getInstance(),
                null, (assignee, card) -> fail("Not ready: no card may be projected"),
                assignee -> opens.incrementAndGet());
        JSONObject assignee = new JSONObject();
        assignee.put("groupId", "task-test-missing-starweave");
        assignee.put("surface", "STARWEAVE");
        assertFalse(sink.submit(assignee, new JSONObject(), "test"));
        assertEquals(1, opens.get());
        assignee.put("surface", "MOLACHAT");
        assertFalse(sink.submit(assignee, new JSONObject(), "test"));
        assertEquals("Must not start another surface", 1, opens.get());
    }

    @Test public void selfStatusNotificationsProjectWithoutStartingAnotherTurn(){AtomicInteger projected=new AtomicInteger();LocalTaskPromptSink sink=new LocalTaskPromptSink(AcpClientRegistry.getInstance(),null,(assignee,card)->projected.incrementAndGet());JSONObject assignee=new JSONObject();assignee.put("groupId","missing-is-fine-for-projection");JSONObject card=new JSONObject();card.put("eventType","TASK_STATUS_CHANGED");card.put("status","COMPLETED");card.put("actorType","AGENT");assertTrue(sink.submit(assignee,card,"must not be sent"));assertEquals(1,projected.get());}
    @Test public void publisherCompletionOpensSessionInsteadOfOnlyProjectingAgentStatus() {
        AtomicInteger opens = new AtomicInteger();
        LocalTaskPromptSink sink = new LocalTaskPromptSink(AcpClientRegistry.getInstance(), null,
                (recipient, card) -> fail("Publisher notification must start a turn"),
                recipient -> opens.incrementAndGet());
        JSONObject recipient = new JSONObject();
        recipient.put("groupId", "missing-task-publisher");recipient.put("surface", "STARWEAVE");
        JSONObject card = new JSONObject();card.put("eventType", "TASK_STATUS_CHANGED");
        card.put("status", "COMPLETED");card.put("actorType", "AGENT");card.put("recipientRole", "PUBLISHER");
        com.mola.cmd.proxy.app.acp.acpclient.PromptOptions options = LocalTaskPromptSink.taskOptions(card);
        assertFalse(options.isTaskTurn());
        assertNull(options.getTaskId());
        assertTrue(options.isTaskNotification());
        assertFalse(options.isTaskControl());
        assertTrue(options.forNewSession().isTaskNotification());
        assertFalse(sink.submit(recipient, card, "completion"));
        assertEquals(1, opens.get());
    }

}
