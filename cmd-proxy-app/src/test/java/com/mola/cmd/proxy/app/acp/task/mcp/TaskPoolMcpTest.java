package com.mola.cmd.proxy.app.acp.task.mcp;

import com.alibaba.fastjson.*;
import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.task.service.TaskService;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class TaskPoolMcpTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    TaskService service;TaskMcpServer server;String token,endpoint;AtomicReference<Runnable> revoke=new AtomicReference<>();
    JSONObject identity=JSON.parseObject("{instanceId:'instance',ownerId:'owner',surface:'STARWEAVE',agentId:'trusted',groupId:'group',robotNameSnapshot:'可信 Agent'}");
    @Before public void start()throws Exception {
        service=new TaskService(temp.newFolder().toPath(),"instance");server=new TaskMcpServer(new InetSocketAddress("127.0.0.1",0),service);server.start();endpoint="http://127.0.0.1:"+server.getPort();
        TaskCaller caller=new TaskCaller(){public JSONObject identity(){return identity;}public JSONObject claim(TaskService s,JSONObject request){return s.claim(request,identity,null);}};
        JsonObject descriptor=server.registerCaller(endpoint,caller,revoke::set);token=descriptor.getAsJsonArray("headers").get(0).getAsJsonObject().get("value").getAsString();
    }
    @After public void close(){server.close();service.close();}
    JSONObject call(String name,JSONObject args,String authorization)throws Exception {
        JSONObject request=new JSONObject();request.put("jsonrpc","2.0");request.put("id",1);request.put("method","tools/call");JSONObject params=new JSONObject();params.put("name",name);params.put("arguments",args);request.put("params",params);
        HttpURLConnection c=(HttpURLConnection)new URL(endpoint+TaskMcpServer.HTTP_PATH).openConnection();c.setConnectTimeout(2000);c.setReadTimeout(5000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");if(authorization!=null)c.setRequestProperty("Authorization",authorization);
        try {try(OutputStream out=c.getOutputStream()){out.write(request.toJSONString().getBytes(StandardCharsets.UTF_8));}assertEquals(200,c.getResponseCode());try(InputStream in=c.getInputStream()){return JSON.parseObject(read(in)).getJSONObject("result");}}finally{c.disconnect();}
    }
    static String read(InputStream in)throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);}
    @Test public void signedPublishListClaimContinueAndIdempotentRetry()throws Exception {
        JSONObject publish=call("publish_task",JSON.parseObject("{name:'Task',contentMarkdown:'requirements',requestId:'p'}"),token);assertFalse(publish.getBooleanValue("isError"));
        JSONObject published=publish.getJSONObject("structuredContent").getJSONObject("task");assertEquals("可信 Agent",published.getString("creatorName"));assertEquals("UNCLAIMED",published.getString("assignmentState"));
        JSONObject listed=call("list_tasks",new JSONObject(),token).getJSONObject("structuredContent");assertEquals(1,listed.getLongValue("total"));assertFalse(listed.getJSONArray("items").getJSONObject(0).containsKey("contentMarkdown"));
        JSONObject args=JSON.parseObject("{requestId:'claim'}");JSONObject claim=call("claim_task",args,token).getJSONObject("structuredContent");assertTrue(claim.getBooleanValue("claimed"));assertTrue(claim.getBooleanValue("continueExecution"));assertTrue(claim.getString("instruction").contains("组织团队成员协作"));
        assertEquals("trusted",claim.getJSONObject("task").getJSONObject("assignee").getString("agentId"));assertEquals(published.getString("id"),call("claim_task",args,token).getJSONObject("structuredContent").getJSONObject("task").getString("id"));
        assertTrue(service.getRepository().claimOutbox("worker",20,System.currentTimeMillis(),30000).isEmpty());
    }
    @Test public void anonymousCannotPublishOrClaimAndModelCannotImpersonate()throws Exception {
        JSONObject args=JSON.parseObject("{name:'Task',requestId:'p'}");JSONObject failed=call("publish_task",args,null);assertTrue(failed.getBooleanValue("isError"));assertEquals("CALLER_NOT_ELIGIBLE",failed.getJSONObject("structuredContent").getString("code"));
        args.put("creatorName","forged");assertEquals("INVALID_ARGUMENT",call("publish_task",args,token).getJSONObject("structuredContent").getString("code"));assertEquals(0,service.list(new JSONObject()).getIntValue("total"));
    }
    @Test public void noAvailableTaskIsNormalAndCredentialIsRevokedOnClose()throws Exception {
        JSONObject result=call("claim_task",JSON.parseObject("{requestId:'empty'}"),token);assertFalse(result.getBooleanValue("isError"));assertFalse(result.getJSONObject("structuredContent").getBooleanValue("claimed"));assertEquals("NO_CLAIMABLE_TASKS",result.getJSONObject("structuredContent").getString("code"));
        revoke.get().run();HttpURLConnection c=(HttpURLConnection)new URL(endpoint+TaskMcpServer.HTTP_PATH).openConnection();c.setRequestMethod("POST");c.setRequestProperty("Authorization",token);assertEquals(401,c.getResponseCode());c.disconnect();
    }
}
