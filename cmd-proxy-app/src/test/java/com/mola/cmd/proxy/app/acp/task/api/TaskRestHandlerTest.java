package com.mola.cmd.proxy.app.acp.task.api;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.task.service.TaskService;
import com.sun.net.httpserver.HttpServer;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class TaskRestHandlerTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private HttpServer server;private TaskService service;private String base;
    @Before public void setup()throws Exception{service=new TaskService(temp.newFolder().toPath(),"instance");server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext(TaskRestHandler.PREFIX,new TaskRestHandler(service));server.start();base="http://127.0.0.1:"+server.getAddress().getPort()+TaskRestHandler.PREFIX;}
    @After public void close(){if(server!=null)server.stop(0);if(service!=null)service.close();}
    private JSONObject call(String path,String method,String body,int expected)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(base+path).openConnection();c.setRequestMethod(method);c.setConnectTimeout(3000);c.setReadTimeout(3000);if(body!=null){c.setDoOutput(true);try(OutputStream out=c.getOutputStream()){out.write(body.getBytes(StandardCharsets.UTF_8));}}assertEquals(expected,c.getResponseCode());try(InputStream in=expected>=400?c.getErrorStream():c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return JSON.parseObject(new String(out.toByteArray(),StandardCharsets.UTF_8));}finally{c.disconnect();}}
    @Test public void restEnvelopeAndPagination()throws Exception{JSONObject created=call("","POST","{\"name\":\"A\",\"target\":{\"type\":\"AGENT\"},\"creatorName\":\"Human\",\"requestId\":\"c\"}",200);assertTrue(created.getBooleanValue("accepted"));String id=created.getJSONObject("data").getJSONObject("task").getString("id");assertTrue(created.getJSONObject("data").getJSONObject("comments").containsKey("nextCursor"));assertNull(created.getJSONObject("data").getJSONObject("comments").get("nextCursor"));assertEquals(1,call("?page=1&pageSize=1","GET",null,200).getJSONObject("data").getLongValue("total"));assertEquals("INVALID_ARGUMENT",call("?page=0","GET",null,400).getString("code"));assertEquals("NOT_FOUND",call("/missing","GET",null,404).getString("code"));assertEquals("INVALID_ARGUMENT",call("/"+id+"/status","POST","{\"status\":\"COMPLETED\"}",400).getString("code"));}
    @Test public void publishesWithoutTargetAndAssignsWithVersionAndIdempotency()throws Exception {
        TaskApiBridge.install(service);
        TaskApiBridge.installResolver(new com.mola.cmd.proxy.app.acp.task.dispatch.TaskTargetResolver(new com.mola.cmd.proxy.app.acp.task.dispatch.TaskExecutionDirectory(){
            public JSONObject resolveAgent(JSONObject target){return "worker".equals(target.getString("agentId"))?JSON.parseObject("{instanceId:'instance',agentId:'worker',groupId:'worker-group'}"):null;}
            public java.util.List<JSONObject> listTeamMembers(JSONObject target){return java.util.Collections.emptyList();}
        }));
        try {
            JSONObject published=call("/publish","POST","{name:'Open',creatorName:'Human',requestId:'publish'}",200).getJSONObject("data").getJSONObject("task");
            assertEquals("UNCLAIMED",published.getString("assignmentState"));assertEquals(1,call("?assignmentState=UNCLAIMED","GET",null,200).getJSONObject("data").getIntValue("total"));
            String route="/"+published.getString("id")+"/assign",body="{target:{type:'AGENT',agentId:'worker'},expectedRevision:1,requestId:'assign'}";
            assertEquals("ASSIGNED",call(route,"POST",body,200).getJSONObject("data").getJSONObject("task").getString("assignmentState"));
            call(route,"POST",body,200);assertEquals(1,service.getRepository().claimOutbox("worker",20,System.currentTimeMillis(),30000).size());
            assertEquals("TASK_ALREADY_ASSIGNED",call(route,"POST",body.replace("'assign'","'other'"),409).getString("code"));
        } finally {TaskApiBridge.clear(service);}
    }
    @Test public void binaryUploadDownloadsAsAttachment()throws Exception{JSONObject upload=call("/attachments?fileName=hello.html","POST","<script>danger()</script>",200).getJSONObject("data");HttpURLConnection c=(HttpURLConnection)new URL(base+"/attachments/"+upload.getString("id")+"/download").openConnection();try{assertEquals(200,c.getResponseCode());assertEquals("application/octet-stream",c.getHeaderField("Content-Type"));assertEquals("nosniff",c.getHeaderField("X-Content-Type-Options"));assertTrue(c.getHeaderField("Content-Disposition").startsWith("attachment;"));try(InputStream in=c.getInputStream()){assertEquals('<',in.read());}}finally{c.disconnect();}}
}
