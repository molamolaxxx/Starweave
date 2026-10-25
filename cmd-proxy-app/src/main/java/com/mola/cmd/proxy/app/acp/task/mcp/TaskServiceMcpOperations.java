package com.mola.cmd.proxy.app.acp.task.mcp;

import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.task.service.TaskService;

import java.util.Objects;

/** Thin MCP adapter; all validation, state transitions and idempotency remain in TaskService. */
public final class TaskServiceMcpOperations implements TaskMcpOperations {
    private final TaskService service;

    public TaskServiceMcpOperations(TaskService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public JSONObject getTask(String taskId, JSONObject query) {
        return service.get(taskId, query);
    }

    @Override
    public JSONObject updateTaskStatus(String taskId, JSONObject request) {
        request.put("reopen", false);
        request.put("actorType", "AGENT");
        return service.updateStatus(taskId, request);
    }

    @Override
    public JSONObject addTaskComment(String taskId, JSONObject request) {
        request.put("authorType", "AGENT");
        return service.addComment(taskId, request);
    }

    @Override public JSONObject listTasks(JSONObject request, TaskCaller caller) {
        allowed(request,"q","status","statuses","includeTerminal","assignmentState","onlyMine","claimableOnly","assigneeInstanceId","assigneeAgentId","assigneeTeamId","assigneeTeamMemberId","assigneeName","createdFrom","createdTo","page","pageSize");
        JSONObject query=new JSONObject(new java.util.LinkedHashMap<>(request));
        if(!query.containsKey("statuses")&&!query.containsKey("status")&&!query.containsKey("includeTerminal"))query.put("includeTerminal",false);
        if(query.getBooleanValue("onlyMine")) {
            JSONObject identity=requireCaller(caller).identity();
            query.put("callerIdentity",identity);
        }
        boolean claimEligible=false;
        if(caller!=null)claimEligible=caller.canClaim();
        if(query.getBooleanValue("claimableOnly")&&!requireCaller(caller).canClaim())throw new com.mola.cmd.proxy.app.acp.task.model.TaskException("CALLER_NOT_ELIGIBLE","当前身份不符合认领规则",403);
        JSONObject result=service.list(query);
        for(Object item:result.getJSONArray("items")) {
            JSONObject task=(JSONObject)item;String body=task.getString("contentMarkdown");
            task.put("preview",body==null?"":body.substring(0,Math.min(300,body.length())));task.remove("contentMarkdown");
            task.put("canClaim",claimEligible&&"UNCLAIMED".equals(task.getString("assignmentState"))&&"START".equals(task.getString("status")));
        }
        result.put("instruction","使用 get_task 查看待办任务明细。可用 claim_task 认领待认领待办任务；认领成功后继续执行，无需等待额外通知。未认领成功不要执行。");
        return result;
    }
    @Override public JSONObject publishTask(JSONObject request, TaskCaller caller) {
        JSONObject result=requireCaller(caller).publish(service,request);
        result.put("instruction",result.getJSONObject("task").getJSONObject("target")==null?"待办任务已发布，当前无人认领。发布不会自动分配或触发执行，可由页面分配或通过 claim_task 认领。":"待办任务已发布并指定执行者，系统将投递执行消息。请勿再以发布者身份重复执行该待办任务。");return result;
    }
    @Override public JSONObject claimTask(JSONObject request, TaskCaller caller) {
        JSONObject result=requireCaller(caller).claim(service,request);
        String instruction=result.getBooleanValue("continueExecution")?
            "待办任务已认领，请继续执行，无需等待额外的待办任务通知。可根据需要组织团队成员协作。开始实际工作时更新为 IN_PROGRESS，保存进度和结果；完成前重新读取最新待办任务，确认要求与状态后更新为 COMPLETED。评论或聊天声明不替代状态更新。":
            "待办任务已认领，但当前已挂起、完成或取消，请遵守最新状态，不要重复执行或自行重新打开。";
        if(result.getBooleanValue("continuationInterrupted"))instruction="认领已成功，但会话执行已中断。请使用原 requestId 重试确认，并读取最新待办任务状态后继续；不要另领一条待办任务。";
        result.put("instruction",instruction);return result;
    }
    private static void allowed(JSONObject request,String... fields) {
        java.util.Set<String> names=new java.util.HashSet<>(java.util.Arrays.asList(fields));
        for(String key:request.keySet())if(!names.contains(key))throw new com.mola.cmd.proxy.app.acp.task.model.TaskException("INVALID_ARGUMENT","Unknown field: "+key,400);
    }
    private static TaskCaller requireCaller(TaskCaller caller) {
        if(caller==null)throw new com.mola.cmd.proxy.app.acp.task.model.TaskException("CALLER_NOT_ELIGIBLE","当前调用身份无法确认，请刷新会话后重试，不要自行填写身份",403);
        return caller;
    }
}
