package com.mola.cmd.proxy.app.acp.action;

import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Provider-neutral application service for cmd-proxy-owned MCP action tools. */
public final class ActionToolService {

    @FunctionalInterface
    public interface Handler {
        String execute(JsonObject arguments) throws Exception;
    }

    private final Map<String, Handler> handlers;

    public ActionToolService(Handler dispatchSubagent,
                             Handler scheduleTask,
                             Handler manageSchedule,
                             Handler talkTo, Handler newSession) {
        this(dispatchSubagent,scheduleTask,manageSchedule,talkTo,newSession,null,null,null);
    }

    public ActionToolService(Handler dispatchSubagent, Handler scheduleTask, Handler manageSchedule,
                             Handler talkTo, Handler newSession, Handler manageObservation,
                             Handler testObservation, Handler queryObservation) {
        this(dispatchSubagent, scheduleTask, manageSchedule, talkTo, newSession, manageObservation,
                testObservation, queryObservation, null, null);
    }

    public ActionToolService(Handler dispatchSubagent, Handler scheduleTask, Handler manageSchedule,
                             Handler talkTo, Handler newSession, Handler manageObservation,
                             Handler testObservation, Handler queryObservation,
                             Handler searchSessions, Handler readHistory) {
        this(dispatchSubagent, scheduleTask, manageSchedule, talkTo, newSession, manageObservation,
                testObservation, queryObservation, searchSessions, readHistory, null);
    }

    public ActionToolService(Handler dispatchSubagent, Handler scheduleTask, Handler manageSchedule,
                             Handler talkTo, Handler newSession, Handler manageObservation,
                             Handler testObservation, Handler queryObservation,
                             Handler searchSessions, Handler readHistory, Handler readContexts) {
        Map<String, Handler> configured = new LinkedHashMap<>();
        configured.put("dispatch_subagent", dispatchSubagent);
        configured.put("schedule_task", scheduleTask);
        configured.put("manage_schedule", manageSchedule);
        configured.put("talk_to", talkTo);
        configured.put("new_session", newSession);
        if (manageObservation != null) configured.put("manage_observation_channels", manageObservation);
        if (testObservation != null) configured.put("test_observation_script", testObservation);
        if (queryObservation != null) configured.put("query_observation_events", queryObservation);
        if (searchSessions != null) configured.put("search_sessions", searchSessions);
        if (readHistory != null) configured.put("read_session_history", readHistory);
        if (readContexts != null) configured.put("read_session_contexts", readContexts);
        handlers = Collections.unmodifiableMap(configured);
    }

    public String execute(String toolName, JsonObject arguments) throws Exception {
        Handler handler = handlers.get(toolName);
        if (handler == null) {
            throw new IllegalArgumentException("Unknown ACP harness tool: " + toolName);
        }
        return handler.execute(arguments == null ? new JsonObject() : arguments);
    }
}
