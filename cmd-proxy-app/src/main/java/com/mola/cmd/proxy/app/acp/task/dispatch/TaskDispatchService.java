package com.mola.cmd.proxy.app.acp.task.dispatch;

import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.task.service.TaskService;
import com.mola.cmd.proxy.app.acp.task.store.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Claims the transactional outbox and performs assignment/delivery outside DB transactions. */
public final class TaskDispatchService implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(TaskDispatchService.class);
    private static final long LEASE_MILLIS = 30_000L;
    private static final int BATCH_SIZE = 20;
    private final TaskService taskService;
    private final TaskRepository repository;
    private final TaskTargetResolver resolver;
    private final TaskDeliveryRouter deliveryRouter;
    private final TaskCardEventFactory cards;
    private final String workerId;
    private final String taskViewBaseUrl;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public TaskDispatchService(TaskService taskService, TaskTargetResolver resolver,
                               TaskDeliveryRouter deliveryRouter, String taskViewBaseUrl) {
        this.taskService = Objects.requireNonNull(taskService, "taskService");
        this.repository = taskService.getRepository();
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.deliveryRouter = Objects.requireNonNull(deliveryRouter, "deliveryRouter");
        this.cards = new TaskCardEventFactory();
        this.workerId = "task-dispatch-" + UUID.randomUUID();
        this.taskViewBaseUrl = taskViewBaseUrl == null ? "" : taskViewBaseUrl.trim();
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "starweave-task-dispatch");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        if (closed.get()) throw new IllegalStateException("task dispatch is closed");
        if (started.compareAndSet(false, true)) {
            executor.scheduleWithFixedDelay(this::safePoll, 0L, 1L, TimeUnit.SECONDS);
        }
    }

    /** Visible for deterministic integration tests and manual retry wakeups. */
    public int processAvailable() {
        if (closed.get()) return 0;
        List<JSONObject> events = repository.claimOutbox(workerId, BATCH_SIZE,
                System.currentTimeMillis(), LEASE_MILLIS);
        for (JSONObject event : events) process(event);
        return events.size();
    }

    private void safePoll() {
        try {
            processAvailable();
        } catch (RuntimeException failure) {
            logger.warn("Starweave task outbox poll failed", failure);
        }
    }

    private void process(JSONObject event) {
        String eventId = event.getString("eventId");
        String token = event.getString("leaseToken");
        try {
            if ("TASK_CREATED".equals(event.getString("eventType"))) {
                assign(event, eventId, token);
                return;
            }
            JSONObject view = taskService.get(event.getString("taskId"), new JSONObject(true));
            JSONObject task = view.getJSONObject("task");
            JSONObject assignee = task == null ? null : task.getJSONObject("assignee");
            if (assignee == null) {
                repository.discard(eventId, token, "Task has no assignee");
                return;
            }
            JSONObject eventPayload = event.getJSONObject("payload");
            if ("TASK_STATUS_CHANGED".equals(event.getString("eventType"))
                    && eventPayload != null && eventPayload.getLong("revision") != null
                    && eventPayload.getLongValue("revision") < task.getLongValue("revision")) {
                repository.discard(eventId, token, "A newer task status revision exists");
                return;
            }
            if (isExecutionEvent(event.getString("eventType"))) {
                String status = task.getString("status");
                if ("SUSPENDED".equals(status) || "COMPLETED".equals(status)
                        || "CANCELLED".equals(status)) {
                    repository.discard(eventId, token, "Task is not executable: " + status);
                    return;
                }
                if (event.getLongValue("contentVersion")
                        < task.getLongValue("contentVersion")) {
                    repository.discard(eventId, token, "A newer task content version exists");
                    return;
                }
            }
            JSONObject card = cards.create(event, view, "PENDING_ACCEPTANCE",
                    taskViewUrl(event.getString("taskId")));
            TaskDeliveryAdapter adapter = deliveryRouter.route(new JSONObject(assignee));
            if (adapter == null) {
                retry(event, token, "No trusted route to task assignee");
                return;
            }
            TaskDeliveryAdapter.DeliveryResult delivered = adapter.deliver(
                    new JSONObject(assignee), card, agentPrompt(card));
            finish(event, token, delivered);
        } catch (RuntimeException failure) {
            retry(event, token, safeMessage(failure));
        }
    }

    private void assign(JSONObject event, String eventId, String token) {
        JSONObject view = taskService.get(event.getString("taskId"), new JSONObject(true));
        JSONObject task = view.getJSONObject("task");
        String status = task.getString("status");
        if ("COMPLETED".equals(status) || "CANCELLED".equals(status)) {
            repository.discard(eventId, token, "Terminal task is not assigned");
            return;
        }
        if("OPEN".equals(task.getString("dispatchMode"))&&task.getJSONObject("assignee")==null){repository.discard(eventId,token,"Open task awaits claim or manual assignment");return;}
        JSONObject existing = task.getJSONObject("assignee");
        if (existing == null) {
            JSONObject target = task.getJSONObject("target");
            repository.assign(task.getString("id"), resolver.candidates(target),
                    resolver.assignmentMode(target));
        }
        if (!repository.completeAssignment(eventId, token)) {
            logger.debug("Task assignment outbox lease was fenced, eventId={}", eventId);
        }
    }

    private void finish(JSONObject event, String token,
                        TaskDeliveryAdapter.DeliveryResult result) {
        if (result == null) {
            retry(event, token, "Delivery adapter returned no result");
        } else if (result.getStatus() == TaskDeliveryAdapter.DeliveryResult.Status.ACCEPTED) {
            repository.acknowledge(event.getString("eventId"), token, result.getReceipt());
        } else if (result.getStatus() == TaskDeliveryAdapter.DeliveryResult.Status.DISCARD) {
            repository.discard(event.getString("eventId"), token, result.getError());
        } else {
            retry(event, token, result.getError());
        }
    }

    private void retry(JSONObject event, String token, String error) {
        int attempts = Math.max(1, event.getIntValue("attempts"));
        long delay = Math.min(TimeUnit.MINUTES.toMillis(5),
                TimeUnit.SECONDS.toMillis(1L << Math.min(8, attempts - 1)));
        repository.retry(event.getString("eventId"), token,
                System.currentTimeMillis() + delay, error);
    }

    private String taskViewUrl(String taskId) {
        if (taskViewBaseUrl.isEmpty()) return null;
        return taskViewBaseUrl + (taskViewBaseUrl.endsWith("/") ? "" : "/") + taskId;
    }

    private static boolean isExecutionEvent(String type) {
        return "TASK_ASSIGNED".equals(type) || "TASK_CONTENT_CHANGED".equals(type);
    }

    static String agentPrompt(JSONObject card) {
        String header = "[Starweave Task]\n"
                + "eventId: " + card.getString("eventId") + "\n"
                + "eventType: " + card.getString("eventType") + "\n"
                + "taskEventSeq: " + card.getLongValue("taskEventSeq") + "\n"
                + "taskId: " + card.getString("taskId") + "\n"
                + "revision: " + card.getLongValue("revision") + "\n"
                + "contentVersion: " + card.getLongValue("contentVersion") + "\n"
                + "status: " + card.getString("status") + optional(card, "previousContentVersion")
                + optional(card, "previousStatus") + optional(card, "reason")
                + optional(card, "actorName") + optional(card, "actorType") + "\n";
        if ("TASK_STATUS_CHANGED".equals(card.getString("eventType"))) {
            String status = card.getString("status");
            if ("SUSPENDED".equals(status)) {
                return header + "This task has been suspended. Call get_task and treat its current "
                        + "revision, contentVersion, and status as authoritative. Stop task work at "
                        + "the earliest safe boundary; do not start new task operations and do not "
                        + "mark it IN_PROGRESS or COMPLETED. Preserve partial work. Resume only after "
                        + "a newer authoritative execution event and a fresh get_task confirm that it "
                        + "is executable again. No conversational acknowledgement is required.";
            }
            if ("CANCELLED".equals(status)) {
                return header + "This task has been cancelled. Call get_task to confirm the current "
                        + "authoritative state, then stop all work for this task at the earliest safe "
                        + "boundary. Do not start new task operations, do not mark it IN_PROGRESS or "
                        + "COMPLETED, and do not treat cancellation as successful completion. Do not "
                        + "claim external side effects were reverted unless rollback was verified. "
                        + "Only a newer authoritative reopen may create new work. No conversational "
                        + "acknowledgement is required.";
            }
            if ("COMPLETED".equals(status)) {
                return header + "This is a completion notification. Call get_task and obey the latest "
                        + "authoritative state. Do not restart or duplicate completed work.";
            }
            return header + "This is a status notification, not an execution request. Call get_task "
                    + "and obey its latest authoritative state. Do not start duplicate work from this "
                    + "notification; executable work is delivered by TASK_ASSIGNED or "
                    + "TASK_CONTENT_CHANGED.";
        }
        String changed = "TASK_CONTENT_CHANGED".equals(card.getString("eventType"))
                ? "This event represents a new work version even if an older version was completed. "
                : "";
        return header + changed + "The expected outcome is to finish the latest authoritative task "
                + "requirements and persist the result in the task system. Required workflow: "
                + "(1) call get_task before acting and use currentRevision, currentContentVersion, "
                + "currentStatus and allowedTransitions as authoritative; event fields may be stale; "
                + "(2) stop if the task is SUSPENDED, CANCELLED, or COMPLETED; "
                + "(3) when real work starts, call update_task_status with IN_PROGRESS if it is not "
                + "already IN_PROGRESS, using the latest versions, your current Agent display name, "
                + "and a stable requestId such as task:<taskId>:v<contentVersion>:start:<agent>; "
                + "(4) perform the complete latest task content; "
                + "(5) persist meaningful progress, blockers, and results with add_task_comment, "
                + "naming the processed contentVersion and using a stable requestId such as "
                + "task:<taskId>:v<contentVersion>:progress:<stage>; a comment is not a status transition; "
                + "(6) before claiming completion, call get_task again and stop if status or content "
                + "changed; (7) after successfully finishing the latest version, call "
                + "update_task_status with COMPLETED using the newly read revision and contentVersion. "
                + "Use a stable requestId such as task:<taskId>:v<contentVersion>:complete:<agent>. "
                + "Do not merely report completion in chat. Receipt acceptance is neither task start "
                + "nor task completion.";
    }

    private static String optional(JSONObject card, String key) {
        Object value = card.get(key);
        return value == null ? "" : "\n" + key + ": " + value;
    }

    private static String safeMessage(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? "task delivery failed" : message.trim();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdownNow();
        try {
            executor.awaitTermination(2L, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
