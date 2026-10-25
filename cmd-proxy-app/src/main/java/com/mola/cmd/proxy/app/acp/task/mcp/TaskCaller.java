package com.mola.cmd.proxy.app.acp.task.mcp;

import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.task.service.TaskService;

/** Server-owned identity and execution binding; never populated from model arguments. */
public interface TaskCaller {
    JSONObject identity();
    JSONObject claim(TaskService service, JSONObject request);
    default JSONObject publish(TaskService service,JSONObject request) { return service.publish(request,identity()); }
    default boolean canClaim() { identity();return true; }
}
