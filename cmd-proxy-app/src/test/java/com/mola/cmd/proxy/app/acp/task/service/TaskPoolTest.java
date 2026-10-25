package com.mola.cmd.proxy.app.acp.task.service;

import com.alibaba.fastjson.*;
import com.mola.cmd.proxy.app.acp.task.model.TaskException;
import com.mola.cmd.proxy.app.acp.task.mcp.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class TaskPoolTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    TaskService service;Path root;
    JSONObject a=JSON.parseObject("{instanceId:'instance',ownerId:'owner',surface:'STARWEAVE',agentId:'a',groupId:'group-a',robotNameSnapshot:'Agent A'}");
    @Before public void open()throws Exception {root=temp.newFolder().toPath();service=new TaskService(root,"instance");}
    @After public void close(){service.close();}
    JSONObject obj(String text){return JSON.parseObject(text);}
    JSONObject publish(String key){return service.publish(obj("{name:'Task',contentMarkdown:'requirements',requestId:'"+key+"'}"),a).getJSONObject("task");}
    JSONObject claim(JSONObject task,String key){JSONObject r=obj("{requestId:'"+key+"'}");r.put("taskId",task.getString("id"));r.put("expectedRevision",task.getLong("revision"));r.put("observedContentVersion",task.getLong("contentVersion"));return r;}
    void fails(String code,Runnable action){try{action.run();fail(code);}catch(TaskException e){assertEquals(e.getMessage(),code,e.getCode());}}
    JSONObject state(JSONObject task,String next,String key){JSONObject r=claim(task,key);r.remove("taskId");r.put("status",next);r.put("actorName","Human");return service.updateStatus(task.getString("id"),r).getJSONObject("task");}
    @Test public void publishEditSuspendReopenAndRetryNeverAutomaticallyAssign() {
        JSONObject t=publish("p");assertEquals("UNCLAIMED",t.getString("assignmentState"));assertNull(t.getJSONObject("assignee"));
        JSONObject edit=obj("{expectedRevision:1,contentMarkdown:'new',requestId:'edit'}");service.edit(t.getString("id"),edit);
        t=service.get(t.getString("id"),new JSONObject()).getJSONObject("task");t=state(t,"SUSPENDED","s");
        assertEquals(Arrays.asList("START","CANCELLED"),service.get(t.getString("id"),new JSONObject()).getJSONArray("allowedTransitions"));
        t=state(t,"START","resume");assertEquals("UNCLAIMED",t.getString("assignmentState"));
        t=state(t,"CANCELLED","c");JSONObject reopen=claim(t,"r");reopen.remove("taskId");reopen.put("status","START");reopen.put("actorName","Human");reopen.put("reopen",true);service.updateStatus(t.getString("id"),reopen);
        service.retryDelivery(t.getString("id"));assertTrue(service.getRepository().claimOutbox("worker",20,System.currentTimeMillis(),30000).isEmpty());
    }
    @Test public void claimReturnsLatestRequirementsWithoutExecutionMessageAndRetrySurvivesRestart() {
        JSONObject t=publish("p"),request=claim(t,"claim");JSONObject result=service.claim(request,a,null);
        assertTrue(result.getBooleanValue("claimed"));assertTrue(result.getBooleanValue("continueExecution"));
        assertEquals(2,result.getJSONObject("task").getLongValue("revision"));assertEquals(1,result.getJSONObject("task").getLongValue("contentVersion"));
        assertTrue(service.getRepository().claimOutbox("worker",20,System.currentTimeMillis(),30000).isEmpty());
        service.close();service=new TaskService(root,"instance");assertEquals(t.getString("id"),service.claim(request,a,null).getJSONObject("task").getString("id"));
        JSONObject changed=JSON.parseObject(request.toJSONString());changed.put("observedContentVersion",2);fails("IDEMPOTENCY_CONFLICT",()->service.claim(changed,a,null));
    }
    @Test public void concurrentClaimAcrossSeparateDatabaseConnectionsHasOneWinner()throws Exception {
        JSONObject t=publish("p");try(TaskService second=new TaskService(root,"instance")) {
            ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
            try {List<Future<Boolean>> jobs=new ArrayList<>();for(int i=0;i<2;i++){final int n=i;jobs.add(workers.submit(()->{start.await();JSONObject who=JSON.parseObject(a.toJSONString());who.put("agentId","agent-"+n);try{(n==0?service:second).claim(claim(t,"claim-"+n),who,null);return true;}catch(TaskException e){assertEquals("TASK_ALREADY_ASSIGNED",e.getCode());return false;}}));}start.countDown();assertNotEquals(jobs.get(0).get(),jobs.get(1).get());}
            finally{workers.shutdownNow();}
        }
        assertEquals(2,service.history(t.getString("id"),new JSONObject()).getLongValue("total"));
    }
    @Test public void manualAssignmentAndClaimRaceAtomically()throws Exception {
        JSONObject t=publish("p");JSONObject request=obj("{target:{type:'AGENT',agentId:'b'},expectedRevision:1,requestId:'manual'}");
        ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch go=new CountDownLatch(1);
        try {
            Future<Boolean> c=workers.submit(()->{go.await();try{service.claim(claim(t,"claim"),a,null);return true;}catch(TaskException e){assertEquals("TASK_ALREADY_ASSIGNED",e.getCode());return false;}});
            Future<Boolean> m=workers.submit(()->{go.await();try{service.assign(t.getString("id"),request,Collections.singletonList(a),"FIXED");return true;}catch(TaskException e){assertEquals("TASK_ALREADY_ASSIGNED",e.getCode());return false;}});
            go.countDown();boolean manual=m.get();assertNotEquals(manual,c.get());assertEquals(manual?1:0,service.getRepository().claimOutbox("worker",20,System.currentTimeMillis(),30000).size());
        }finally{workers.shutdownNow();}
    }
    @Test public void automaticClaimRetryDoesNotTakeSecondTaskAndActiveWorkCannotBeReplaced() {
        publish("one");publish("two");JSONObject request=obj("{requestId:'auto'}");JSONObject first=service.claim(request,a,null).getJSONObject("task");
        assertEquals(first.getString("id"),service.claim(request,a,first.getString("id")).getJSONObject("task").getString("id"));
        fails("CURRENT_TASK_ACTIVE",()->service.claim(obj("{requestId:'next'}"),a,first.getString("id")));
        assertEquals(1,service.list(obj("{claimableOnly:true}")).getLongValue("total"));
    }
    @Test public void rejectsChangedVersionsSuspendedTerminalAndInvalidCaller() {
        JSONObject t=publish("p");service.edit(t.getString("id"),obj("{expectedRevision:1,contentMarkdown:'new',requestId:'edit'}"));
        fails("VERSION_CONFLICT",()->service.claim(claim(t,"claim"),a,null));
        JSONObject latest=service.get(t.getString("id"),new JSONObject()).getJSONObject("task");JSONObject old=claim(latest,"old");old.put("observedContentVersion",1);fails("LATEST_CONTENT_REQUIRED",()->service.claim(old,a,null));
        JSONObject suspended=state(latest,"SUSPENDED","s");fails("TASK_NOT_CLAIMABLE",()->service.claim(claim(suspended,"s-claim"),a,null));
        JSONObject invalid=JSON.parseObject(a.toJSONString());invalid.put("instanceId","other");fails("CALLER_NOT_ELIGIBLE",()->service.claim(obj("{requestId:'wrong'}"),invalid,null));
        fails("NO_CLAIMABLE_TASKS",()->service.claim(obj("{requestId:'auto'}"),a,null));
    }
    @Test public void replayCannotReplaceDifferentActiveTask() {
        JSONObject first=publish("first"),second=publish("second"),original=claim(first,"claim-first");
        service.claim(original,a,null);service.claim(claim(second,"claim-second"),a,null);
        fails("CURRENT_TASK_ACTIVE",()->service.claim(original,a,second.getString("id")));
    }
    @Test public void repeatedPublishDoesNotMutateArgumentsAndIgnoresRenamedDisplayLabel() {
        JSONObject request=obj("{name:'Task',requestId:'publish'}");String id=service.publish(request,a).getJSONObject("task").getString("id");
        assertFalse(request.containsKey("creatorName"));JSONObject renamed=JSON.parseObject(a.toJSONString());renamed.put("robotNameSnapshot","Renamed");
        assertEquals(id,service.publish(request,renamed).getJSONObject("task").getString("id"));
    }
    @Test public void idempotentClaimOfFinishedTaskDoesNotTellAgentToExecuteAgain() {
        JSONObject t=publish("p"),request=claim(t,"claim");t=service.claim(request,a,null).getJSONObject("task");t=state(t,"IN_PROGRESS","start");state(t,"COMPLETED","done");
        JSONObject retry=service.claim(request,a,null);assertTrue(retry.getBooleanValue("claimed"));assertFalse(retry.getBooleanValue("continueExecution"));assertEquals("COMPLETED",retry.getString("currentStatus"));
    }
    @Test public void listDefaultsToAllNonTerminalAndOnlyMineUsesCompleteIdentity() {
        JSONObject t=publish("p");t=service.claim(claim(t,"c"),a,null).getJSONObject("task");t=state(t,"IN_PROGRESS","s");state(t,"COMPLETED","done");
        publish("unclaimed");JSONObject team=JSON.parseObject(a.toJSONString());team.put("teamId","team");team.put("teamMemberId","member");team.put("surface","STARWEAVE_TEAM");
        JSONObject other=publish("team");service.claim(claim(other,"team-claim"),team,null);
        TaskServiceMcpOperations operations=new TaskServiceMcpOperations(service);TaskCaller caller=new TaskCaller(){public JSONObject identity(){return a;}public JSONObject claim(TaskService s,JSONObject r){return s.claim(r,a,null);}};
        assertEquals(2,operations.listTasks(new JSONObject(),caller).getLongValue("total"));
        assertEquals(3,operations.listTasks(obj("{includeTerminal:true}"),caller).getLongValue("total"));
        assertEquals(0,operations.listTasks(obj("{onlyMine:true}"),caller).getLongValue("total"));
        assertEquals(1,operations.listTasks(obj("{onlyMine:true,includeTerminal:true}"),caller).getLongValue("total"));
        JSONObject item=operations.listTasks(obj("{claimableOnly:true}"),caller).getJSONArray("items").getJSONObject(0);assertFalse(item.containsKey("contentMarkdown"));assertTrue(item.getBooleanValue("canClaim"));
        fails("INVALID_ARGUMENT",()->operations.listTasks(obj("{statuses:['START'],includeTerminal:true}"),caller));
    }
}
