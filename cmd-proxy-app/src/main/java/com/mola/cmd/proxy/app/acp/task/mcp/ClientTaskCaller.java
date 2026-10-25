package com.mola.cmd.proxy.app.acp.task.mcp;

import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.task.model.TaskException;
import com.mola.cmd.proxy.app.acp.task.service.TaskService;
import java.util.function.Supplier;

public final class ClientTaskCaller implements TaskCaller {
    private final AcpClient client;
    private final Supplier<JSONObject> resolver;
    private final Runnable claimPolicy;
    private final java.util.function.Function<String,JSONObject> targetResolver;
    public ClientTaskCaller(AcpClient client, Supplier<JSONObject> resolver) { this(client,resolver,()->{}); }
    public ClientTaskCaller(AcpClient client, Supplier<JSONObject> resolver,Runnable claimPolicy) { this(client,resolver,ref->{throw new TaskException("TARGET_UNAVAILABLE","执行者查询尚不可用",409);},claimPolicy); }
    public ClientTaskCaller(AcpClient client,Supplier<JSONObject> resolver,java.util.function.Function<String,JSONObject> targetResolver,Runnable claimPolicy) {
        this.client=client;this.resolver=resolver;this.targetResolver=targetResolver;this.claimPolicy=claimPolicy;
    }
    @Override public JSONObject publish(TaskService service,JSONObject request) {
        return service.publish(request,identity(),request.getString("assignee_ref")==null?null:()->targetResolver.apply(request.getString("assignee_ref")),client.getActiveTaskId());
    }
    @Override public boolean canClaim() { try { identity();claimPolicy.run();return true; } catch(TaskException unavailable) { return false; } }
    @Override public JSONObject identity() {
        JSONObject identity=resolver.get();
        if(identity==null||identity.isEmpty())throw new TaskException("CALLER_NOT_ELIGIBLE","当前执行身份不可用",403);
        return new JSONObject(new java.util.LinkedHashMap<>(identity));
    }
    @Override public JSONObject claim(TaskService service, JSONObject request) {
        JSONObject assignee=identity();claimPolicy.run();
        return client.claimTaskInActiveTurn(previous -> service.claim(request,assignee,previous));
    }
}
