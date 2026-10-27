package com.mola.cmd.proxy.app.acp.task.dispatch;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.task.service.TaskService;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class TaskPublisherNotificationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void completionNotifiesPublisherDurablyAndRetriesBusyReceiptAfterRestart() throws Exception {
        java.nio.file.Path path = temporary.newFolder().toPath();
        JSONObject publisher = identity("publisher"), worker = identity("worker");
        String publisherEventId;
        AtomicInteger publisherTurns = new AtomicInteger();
        AtomicBoolean ready = new AtomicBoolean(false);
        try (TaskService service = new TaskService(path, "instance")) {
            JSONObject task = claimed(service, publisher, worker);
            JSONObject completed = status(service, task, "COMPLETED", "complete");
            // An idempotent retry must not create another publisher event.
            status(service, task, "COMPLETED", "complete");
            JSONArray events = completed.getJSONArray("delivery");
            assertEquals(1, publisherEvents(events));
            publisherEventId = events.getJSONObject(events.size() - 2).getString("eventId");
            try (LocalTaskDeliveryAdapter receiver = new LocalTaskDeliveryAdapter(service,
                    (recipient, card, prompt) -> {
                        if (!"PUBLISHER".equals(card.getString("recipientRole"))) return true;
                        assertEquals(TaskService.identityKey(publisher), TaskService.identityKey(recipient));
                        assertEquals("COMPLETED", card.getString("status"));
                        assertEquals("TASK_STATUS_CHANGED", card.getString("eventType"));
                        assertTrue(prompt.contains("task you published has been completed"));
                        assertTrue(prompt.contains("comments and results"));
                        return ready.get();
                    }); TaskDispatchService dispatch = dispatcher(service, receiver)) {
                dispatch.processAvailable(); // stale IN_PROGRESS event
                dispatch.processAvailable(); // publisher completion event
                dispatch.processAvailable(); // executor completion event
                receiver.recoverPending(20);
                assertEquals(1, service.getRepository().pendingReceipts(20).size());
                assertEquals(publisherEventId, service.getRepository().pendingReceipts(20)
                        .get(0).getString("eventId"));
            }
        }
        ready.set(true);
        try (TaskService service = new TaskService(path, "instance");
             LocalTaskDeliveryAdapter receiver = new LocalTaskDeliveryAdapter(service, (recipient, card, prompt) -> {
                 assertEquals(publisherEventId, card.getString("eventId"));
                 assertEquals("publisher", recipient.getString("agentId"));
                 publisherTurns.incrementAndGet();
                 return true;
             })) {
            assertEquals(1, receiver.recoverPending(20));
            assertEquals(0, receiver.recoverPending(20));
            assertEquals(1, publisherTurns.get());
        }
    }

    @Test public void sameIdentityWithDifferentDisplayNameDoesNotNotifyPublisher() throws Exception {
        try (TaskService service = new TaskService(temporary.newFolder().toPath(), "instance")) {
            JSONObject publisher = identity("same"), worker = identity("same");
            worker.put("displayNameSnapshot", "renamed");
            JSONObject task = claimed(service, publisher, worker);
            assertEquals(0, publisherEvents(status(service, task, "COMPLETED", "done").getJSONArray("delivery")));
        }
    }

    @Test public void intermediateAndCancelledStatesDoNotNotifyPublisher() throws Exception {
        try (TaskService service = new TaskService(temporary.newFolder().toPath(), "instance")) {
            JSONObject task = claimed(service, identity("publisher"), identity("worker"));
            assertEquals(0, publisherEvents(service.get(task.getString("id"), new JSONObject()).getJSONArray("delivery")));
            assertEquals(0, publisherEvents(status(service, task, "CANCELLED", "cancel").getJSONArray("delivery")));
        }
    }

    @Test public void completionNotificationSurvivesLaterTaskChangesAndRoutesToPublishingTeamMember() throws Exception {
        try (TaskService service = new TaskService(temporary.newFolder().toPath(), "instance")) {
            JSONObject publisher = identity("publisher");
            publisher.put("surface", "STARWEAVE_TEAM");
            publisher.put("teamId", "team");publisher.put("teamMemberId", "member");
            JSONObject task = claimed(service, publisher, identity("worker"));
            JSONObject completed = status(service, task, "COMPLETED", "done").getJSONObject("task");
            JSONObject reopen = statusRequest(completed, "START", "reopen");
            reopen.put("reopen", true);service.updateStatus(task.getString("id"), reopen);
            AtomicInteger delivered = new AtomicInteger();
            try (TaskDispatchService dispatch = dispatcher(service, (recipient, card, prompt) -> {
                if ("PUBLISHER".equals(card.getString("recipientRole"))) {
                    assertEquals("member", recipient.getString("teamMemberId"));
                    assertEquals("COMPLETED", card.getString("status"));
                    assertEquals(completed.getLong("revision"), card.getLong("revision"));
                    delivered.incrementAndGet();
                }
                return TaskDeliveryAdapter.DeliveryResult.accepted("receipt-" + card.getString("eventId"));
            })) {
                for (int i=0;i<6;i++) dispatch.processAvailable();
                assertEquals(1, delivered.get());
            }
        }
    }

    @Test public void unavailableExecutorDoesNotBlockPublisherCompletion() throws Exception {
        try (TaskService service = new TaskService(temporary.newFolder().toPath(), "instance")) {
            JSONObject task = claimed(service, identity("publisher"), identity("worker"));
            status(service, task, "COMPLETED", "done");
            AtomicInteger publisherNotifications = new AtomicInteger();
            TaskExecutionDirectory directory = new TaskExecutionDirectory() {
                @Override public JSONObject resolveAgent(JSONObject target) { return identity("worker"); }
                @Override public java.util.List<JSONObject> listTeamMembers(JSONObject target) { return Collections.emptyList(); }
            };
            try (TaskDispatchService dispatch = new TaskDispatchService(service,
                    new TaskTargetResolver(directory, new Random(1)), recipient -> {
                        if ("worker".equals(recipient.getString("agentId"))) return null;
                        return (target, card, prompt) -> {
                            publisherNotifications.incrementAndGet();
                            return TaskDeliveryAdapter.DeliveryResult.accepted("publisher-receipt");
                        };
                    }, "")) {
                dispatch.processAvailable(); // superseded status
                dispatch.processAvailable(); // publisher notification
                dispatch.processAvailable(); // unavailable executor
                assertEquals(1, publisherNotifications.get());
                JSONArray events = service.get(task.getString("id"), new JSONObject()).getJSONArray("delivery");
                assertEquals("RETRY", events.getJSONObject(events.size()-1).getString("state"));
            }
        }
    }

    private static TaskDispatchService dispatcher(TaskService service, TaskDeliveryAdapter adapter) {
        TaskExecutionDirectory directory = new TaskExecutionDirectory() {
            @Override public JSONObject resolveAgent(JSONObject target) { return identity("worker"); }
            @Override public java.util.List<JSONObject> listTeamMembers(JSONObject target) { return Collections.emptyList(); }
        };
        return new TaskDispatchService(service, new TaskTargetResolver(directory, new Random(1)), recipient -> adapter, "");
    }

    private static JSONObject identity(String agent) {
        JSONObject identity = JSON.parseObject("{instanceId:'instance',ownerId:'owner',surface:'STARWEAVE'}");
        identity.put("agentId", agent);identity.put("groupId", agent);
        return identity;
    }

    private static JSONObject claimed(TaskService service, JSONObject publisher, JSONObject worker) {
        service.publish(JSON.parseObject("{name:'Task',requestId:'publish'}"), publisher);
        JSONObject task = service.claim(JSON.parseObject("{requestId:'claim'}"), worker, null).getJSONObject("task");
        return status(service, task, "IN_PROGRESS", "start").getJSONObject("task");
    }

    private static JSONObject status(TaskService service, JSONObject task, String next, String requestId) {
        return service.updateStatus(task.getString("id"), statusRequest(task, next, requestId));
    }

    private static JSONObject statusRequest(JSONObject task, String next, String requestId) {
        JSONObject request = new JSONObject();request.put("status", next);request.put("requestId", requestId);
        request.put("expectedRevision", task.getLong("revision"));request.put("observedContentVersion", task.getLong("contentVersion"));
        request.put("actorName", "Worker");request.put("actorType", "AGENT");return request;
    }

    private static int publisherEvents(JSONArray events) {
        int count = 0;
        for(int i=0;i<events.size();i++) {
            JSONObject payload=events.getJSONObject(i).getJSONObject("payload");
            if(payload!=null && "PUBLISHER".equals(payload.getString("recipientRole"))) count++;
        }
        return count;
    }
}
