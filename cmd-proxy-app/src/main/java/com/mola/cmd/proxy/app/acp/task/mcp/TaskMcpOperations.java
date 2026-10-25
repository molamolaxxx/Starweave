package com.mola.cmd.proxy.app.acp.task.mcp;

import com.alibaba.fastjson.JSONObject;

/** Business operations exposed by the independent task MCP transport. */
public interface TaskMcpOperations {
    JSONObject getTask(String taskId, JSONObject query);

    JSONObject updateTaskStatus(String taskId, JSONObject request);

    JSONObject addTaskComment(String taskId, JSONObject request);

    default JSONObject listTasks(JSONObject request, TaskCaller caller) { throw new UnsupportedOperationException(); }
    default JSONObject publishTask(JSONObject request, TaskCaller caller) { throw new UnsupportedOperationException(); }
    default JSONObject claimTask(JSONObject request, TaskCaller caller) { throw new UnsupportedOperationException(); }
}
