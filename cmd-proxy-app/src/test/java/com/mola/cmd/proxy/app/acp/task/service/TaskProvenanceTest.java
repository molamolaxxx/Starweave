package com.mola.cmd.proxy.app.acp.task.service;

import com.alibaba.fastjson.*;
import com.mola.cmd.proxy.app.acp.task.model.TaskException;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class TaskProvenanceTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    TaskService service;JSONObject caller=JSON.parseObject("{instanceId:'instance',agentId:'a',ownerId:'starweave-instance',surface:'STARWEAVE',groupId:'a'}");
    @Before public void start()throws Exception{service=new TaskService(temp.newFolder().toPath(),"instance");}
    @After public void close(){service.close();}
    JSONObject request(String key){return JSON.parseObject("{name:'Task',contentMarkdown:'requirements',requestId:'"+key+"'}");}
    JSONObject parent(){JSONObject t=service.publish(request("parent"),caller).getJSONObject("task");JSONObject claim=JSON.parseObject("{requestId:'claim'}");return service.claim(claim,caller,null).getJSONObject("task");}
    @Test public void directedPublishRecordsTrustedTargetAndOneDispatchOnRetry() {
        JSONObject r=request("direct");r.put("assignee_ref","agent-opaque");JSONObject target=JSON.parseObject("{type:'TEAM',teamId:'team',mode:'FIXED',teamMemberId:'member'}");
        JSONObject task=service.publish(r,caller,()->target,null).getJSONObject("task");assertEquals("DIRECT",task.getString("dispatchMode"));assertEquals("member",task.getJSONObject("target").getString("teamMemberId"));
        assertEquals(task.getString("id"),service.publish(r,caller,()->{throw new AssertionError("Committed retry must not re-resolve target");},null).getJSONObject("task").getString("id"));
        assertEquals(1,service.getRepository().claimOutbox("worker",20,System.currentTimeMillis(),30000).size());
    }
    @Test public void sourceAndReverseLinksPersistRetryKeepsOriginalSourceAndDeletionKeepsSnapshot() {
        JSONObject parent=parent(),r=request("child");String id=service.publish(r,caller,null,parent.getString("id")).getJSONObject("task").getString("id");
        JSONObject detail=service.get(id,new JSONObject());assertEquals(parent.getString("id"),detail.getJSONObject("sourceTask").getString("id"));assertFalse(detail.getJSONObject("sourceTask").getBooleanValue("deleted"));
        assertEquals(1,service.get(parent.getString("id"),new JSONObject()).getJSONObject("derivedTasks").getLongValue("total"));
        assertEquals(id,service.publish(r,caller,null,null).getJSONObject("task").getString("id"));
        JSONObject delete=new JSONObject();delete.put("expectedRevision",parent.getLong("revision"));service.delete(parent.getString("id"),delete);
        detail=service.get(id,new JSONObject());assertTrue(detail.getJSONObject("sourceTask").getBooleanValue("deleted"));assertEquals("Task",detail.getJSONObject("sourceTask").getString("name"));
    }
    @Test public void foreignSourceAndModelSuppliedSourceAreRejectedWithoutCreatingTask() {
        JSONObject parent=parent(),other=JSON.parseObject(caller.toJSONString());other.put("agentId","other");
        try{service.publish(request("foreign"),other,null,parent.getString("id"));fail();}catch(TaskException expected){assertEquals("SOURCE_TASK_CONFLICT",expected.getCode());}
        JSONObject forged=request("forged");forged.put("sourceTaskId",parent.getString("id"));try{service.publish(forged,caller);fail();}catch(TaskException expected){assertEquals("INVALID_ARGUMENT",expected.getCode());}
        assertEquals(1,service.list(new JSONObject()).getLongValue("total"));
    }
    @Test public void plainPublishHasNoSourceAndDerivedListPaginates() {
        JSONObject p=parent();for(int i=0;i<3;i++)service.publish(request("child-"+i),caller,null,p.getString("id"));
        JSONObject page=service.derivedTasks(p.getString("id"),JSON.parseObject("{page:2,pageSize:2}"));assertEquals(3,page.getLongValue("total"));assertEquals(1,page.getJSONArray("items").size());
        JSONObject plain=service.publish(request("plain"),caller).getJSONObject("task");assertNull(plain.getJSONObject("sourceTask"));
    }
}
