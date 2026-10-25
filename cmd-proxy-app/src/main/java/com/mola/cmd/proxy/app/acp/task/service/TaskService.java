package com.mola.cmd.proxy.app.acp.task.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.task.attachment.TaskAttachmentStore;
import com.mola.cmd.proxy.app.acp.task.model.TaskException;
import com.mola.cmd.proxy.app.acp.task.store.TaskRepository;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import static com.mola.cmd.proxy.app.acp.task.store.TaskRepository.*;

/** Shared task authority for REST and MCP operations. */
public final class TaskService implements AutoCloseable {
    public static final List<String> STATUSES=Collections.unmodifiableList(Arrays.asList("START","IN_PROGRESS","COMPLETED","CANCELLED","SUSPENDED"));
    private final TaskRepository repository;
    private final TaskAttachmentStore attachments;
    private final String ownerInstanceId;
    private final Consumer<JSONObject> targetValidator;
    public TaskService(Path directory,String ownerInstanceId) { this(directory,ownerInstanceId,target -> { }); }
    public TaskService(Path directory,String ownerInstanceId,Consumer<JSONObject> targetValidator) { this.ownerInstanceId=required(ownerInstanceId,"ownerInstanceId",200);this.targetValidator=Objects.requireNonNull(targetValidator,"targetValidator");repository=new TaskRepository(directory);attachments=new TaskAttachmentStore(directory.resolve("attachments"),repository); }
    public TaskRepository getRepository(){return repository;}
    public TaskAttachmentStore getAttachments(){return attachments;}
    public JSONObject create(JSONObject request){ return createInternal(request, null); }
    private JSONObject createInternal(JSONObject request, JSONObject publisher){return createInternal(request,publisher,null,null,null);}
    private JSONObject createInternal(JSONObject request,JSONObject publisher,JSONObject publishFingerprint,java.util.function.Supplier<JSONObject> targetSupplier,String sourceTaskId){
        fields(request,"name","contentMarkdown","attachmentIds","target","creatorName","requestId");
        JSONObject fingerprint=new JSONObject(new java.util.LinkedHashMap<>(request));if(publisher!=null)fingerprint.remove("creatorName");if(publishFingerprint!=null)fingerprint=publishFingerprint;
        final JSONObject key=fingerprint;
        return repository.transaction(c->dedup(c,publisher==null?"create":"publish:"+identityKey(publisher),key,()->{
            JSONObject t=new JSONObject(true);String now=Instant.now().toString();
            t.put("id",UUID.randomUUID().toString());t.put("ownerInstanceId",ownerInstanceId);t.put("name",required(request.getString("name"),"name",200));
            t.put("contentMarkdown",markdown(request));t.put("status","START");t.put("revision",1L);t.put("contentVersion",1L);
            JSONObject target=targetSupplier==null?request.getJSONObject("target"):targetSupplier.get();if(target!=null){if(!Arrays.asList("AGENT","TEAM").contains(target.getString("type")))throw invalid("target.type must be AGENT or TEAM");target=new JSONObject(target);targetValidator.accept(target);}t.put("dispatchMode",target==null?"OPEN":"DIRECT");if(publisher!=null)t.put("creatorIdentity",new JSONObject(new java.util.LinkedHashMap<>(publisher)));
            if(sourceTaskId!=null){
                JSONObject source=task(c,sourceTaskId);
                if(source.getJSONObject("assignee")==null||!identityKey(publisher).equals(identityKey(source.getJSONObject("assignee"))))throw new TaskException("SOURCE_TASK_CONFLICT","当前来源任务执行归属无法确认",409);
                JSONObject parent=new JSONObject(true);parent.put("id",source.getString("id"));parent.put("name",source.getString("name"));parent.put("revision",source.getLong("revision"));parent.put("contentVersion",source.getLong("contentVersion"));t.put("sourceTask",parent);
            }
            t.put("target",target);t.put("assignee",null);t.put("creatorName",required(request.getString("creatorName"),"creatorName",200));t.put("createdAt",now);t.put("updatedAt",now);t.put("completedAt",null);
            t.put("attachments",attachmentMetadata(c,request.getJSONArray("attachmentIds")));save(c,t,target==null?"PUBLISHED":"CREATED",t.getString("creatorName"));linkContent(c,t);if(target!=null)event(c,t,"TASK_CREATED");return detail(c,t,new JSONObject());}));
    }
    public JSONObject publish(JSONObject request, JSONObject publisher) {return publish(request,publisher,null,null);}
    public JSONObject publish(JSONObject request,JSONObject publisher,java.util.function.Supplier<JSONObject> resolveTarget,String sourceTaskId) {
        fields(request,"name","contentMarkdown","attachmentIds","requestId","assignee_ref");
        if(publisher==null||!ownerInstanceId.equals(publisher.getString("instanceId")))throw new TaskException("CALLER_NOT_ELIGIBLE","发布者不属于当前环境",403);
        if(request.containsKey("assignee_ref"))required(request.getString("assignee_ref"),"assignee_ref",200);
        // Replay before target discovery: a removed target or ended source turn must not break a committed retry.
        JSONObject fingerprint=new JSONObject(new java.util.LinkedHashMap<>(request));
        JSONObject replay=repository.transaction(c->dedupReplay(c,"publish:"+identityKey(publisher),fingerprint));
        if(replay!=null)return replay;
        JSONObject target=null;
        if(request.containsKey("assignee_ref")){
            if(resolveTarget==null)throw new TaskException("TARGET_UNAVAILABLE","请先 list_agents 并选择可分配执行者",409);
            target=resolveTarget.get();if(target==null)throw new TaskException("TARGET_UNAVAILABLE","执行者不可用",409);
        }
        final JSONObject selected=target;
        JSONObject body=new JSONObject(new java.util.LinkedHashMap<>(request));body.remove("assignee_ref");body.put("creatorName",displayName(publisher));
        return createInternal(body,publisher,fingerprint,selected==null?null:()->selected,sourceTaskId);
    }
    public static String identityKey(JSONObject identity) {
        StringBuilder key = new StringBuilder();
        for (String field : Arrays.asList("instanceId","ownerId","surface","agentId","groupId","teamId","teamMemberId"))
            key.append(identity.getString(field)==null?"":identity.getString(field)).append('\n');
        return sha256(key.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static String displayName(JSONObject identity) {
        for (String field : Arrays.asList("displayNameSnapshot","robotNameSnapshot","agentId"))
            if (identity.getString(field)!=null&&!identity.getString(field).trim().isEmpty()) return identity.getString(field);
        return "Agent";
    }
    public static String assignmentState(JSONObject task) {
        return task.getJSONObject("assignee")!=null?"ASSIGNED":("OPEN".equals(task.getString("dispatchMode"))?"UNCLAIMED":"PENDING_ASSIGNMENT");
    }
    private static void decorate(JSONObject task) {
        if (!task.containsKey("dispatchMode")) task.put("dispatchMode","DIRECT");
        task.put("assignmentState",assignmentState(task));
    }
    /** No execution outbox event: the claiming Agent continues from the MCP result. */
    public JSONObject claim(JSONObject request, JSONObject caller, String activeTaskId) {
        fields(request,"taskId","expectedRevision","observedContentVersion","requestId");
        if (!ownerInstanceId.equals(caller.getString("instanceId"))) throw new TaskException("CALLER_NOT_ELIGIBLE","执行者不属于当前环境",403);
        return repository.transaction(c -> {
            JSONObject recorded = dedup(c,"claim:"+identityKey(caller),request,() -> {
                String id = request.getString("taskId");
                if (activeTaskId!=null&&!activeTaskId.equals(id)) {
                    JSONObject previous=task(c,activeTaskId);
                    if (!terminal(previous.getString("status"))) throw new TaskException("CURRENT_TASK_ACTIVE","请先完成或停止正在执行的任务，再认领其他任务",409);
                }
                JSONObject t;
                if (id==null||id.trim().isEmpty()) {
                    if(request.containsKey("expectedRevision")||request.containsKey("observedContentVersion"))throw invalid("Automatic claim does not accept task versions");
                    List<JSONObject> available=snapshots(c,"SELECT snapshot FROM task WHERE status='START' AND json_extract(snapshot,'$.dispatchMode')='OPEN' AND json_extract(snapshot,'$.assignee') IS NULL ORDER BY created_at,id LIMIT 1");
                    if (available.isEmpty()) throw new TaskException("NO_CLAIMABLE_TASKS","当前没有可认领任务，无需立即循环重试",409);
                    t=available.get(0);
                } else {
                    t=task(c,id);
                    if(t.getJSONObject("assignee")!=null)throw claimConflict("TASK_ALREADY_ASSIGNED","任务已被认领，请勿开始执行，可查询其他待认领任务",t);
                    checkRevision(t,request);
                    if(positive(request,"observedContentVersion")!=t.getLongValue("contentVersion"))throw conflict("LATEST_CONTENT_REQUIRED",t);
                }
                if(!"OPEN".equals(t.getString("dispatchMode"))||!"START".equals(t.getString("status")))throw claimConflict("TASK_NOT_CLAIMABLE","任务当前不可认领，请勿执行或自行重新打开",t);
                t.put("assignee",new JSONObject(new java.util.LinkedHashMap<>(caller)));t.put("executionMode","CONTINUE");t.put("revision",t.getLongValue("revision")+1);t.put("updatedAt",Instant.now().toString());
                save(c,t,"CLAIMED",displayName(caller));return detail(c,t,new JSONObject());
            });
            JSONObject current=task(c,recorded.getJSONObject("task").getString("id"));
            if(activeTaskId!=null&&!activeTaskId.equals(current.getString("id"))&&!terminal(task(c,activeTaskId).getString("status")))
                throw new TaskException("CURRENT_TASK_ACTIVE","请先完成或停止正在执行的任务，再恢复其他认领结果",409);
            if(current.getJSONObject("assignee")==null||!identityKey(caller).equals(identityKey(current.getJSONObject("assignee"))))throw claimConflict("TASK_ALREADY_ASSIGNED","任务执行者已变化，请勿继续执行",current);
            JSONObject result=detail(c,current,new JSONObject());result.put("claimed",true);
            boolean executable=Arrays.asList("START","IN_PROGRESS").contains(current.getString("status"));result.put("continueExecution",executable);
            return result;
        });
    }
    private static TaskException claimConflict(String code,String message,JSONObject task) {
        JSONObject data=new JSONObject();data.put("currentRevision",task.get("revision"));data.put("currentContentVersion",task.get("contentVersion"));data.put("currentStatus",task.get("status"));data.put("currentAssignee",task.get("assignee"));
        return new TaskException(code,message,409,data);
    }
    /** Human assignment resolves candidates before acquiring the writer; selection and event are atomic. */
    public JSONObject assign(String id,JSONObject request,List<JSONObject> candidates,String mode) {
        fields(request,"target","expectedRevision","requestId","actorName");
        JSONObject target=request.getJSONObject("target");if(target==null)throw invalid("target is required");targetValidator.accept(target);
        return repository.transaction(c -> dedup(c,"manual-assign:"+id,request,() -> {
            JSONObject t=task(c,id);if(t.getJSONObject("assignee")!=null)throw claimConflict("TASK_ALREADY_ASSIGNED","任务已被其他执行者接走",t);
            checkRevision(t,request);if(!"OPEN".equals(t.getString("dispatchMode"))||!"START".equals(t.getString("status")))throw claimConflict("TASK_NOT_CLAIMABLE","任务当前不可分配",t);
            t.put("target",new JSONObject(target));
            JSONObject assignee=TaskRepository.selectAssignee(c,id,candidates,mode);
            t.put("assignee",assignee);t.put("executionMode","NOTIFICATION");t.put("revision",t.getLongValue("revision")+1);t.put("updatedAt",Instant.now().toString());
            save(c,t,"ASSIGNED",request.getString("actorName")==null?"ConfigUI 用户":request.getString("actorName"));event(c,t,"TASK_ASSIGNED");return detail(c,t,new JSONObject());
        }));
    }
    public JSONObject get(String id,JSONObject query){return repository.transaction(c->{JSONObject current=task(c,id), selected=current;
        if(query.containsKey("revision")&&query.containsKey("contentVersion"))throw invalid("revision and contentVersion are mutually exclusive");
        if(query.containsKey("revision")||query.containsKey("contentVersion")){
            boolean revision=query.containsKey("revision");long value=positive(query,revision?"revision":"contentVersion");
            List<JSONObject> found=snapshots(c,"SELECT snapshot FROM task_history WHERE task_id=? AND "+(revision?"revision":"content_version")+"=? ORDER BY revision LIMIT 1",id,value);
            if(found.isEmpty())throw new TaskException("NOT_FOUND","Task version not found",404);selected=found.get(0);
        }
        return detail(c,selected,query);
    });}
    private JSONObject detail(Connection c,JSONObject selected,JSONObject query)throws Exception{
        JSONObject head=task(c,selected.getString("id"));decorate(selected);JSONObject d=new JSONObject(true);d.put("task",selected);
        JSONArray statusValues=new JSONArray();statusValues.addAll(STATUSES);d.put("currentRevision",head.getLongValue("revision"));d.put("currentContentVersion",head.getLongValue("contentVersion"));d.put("currentStatus",head.getString("status"));d.put("statusValues",statusValues);d.put("allowedTransitions",allowedTransitions(head,true));d.put("agentAllowedTransitions",allowedTransitions(head,false));d.put("commentsScope","CURRENT");
        JSONObject cq=new JSONObject();cq.put("cursor",query.getString("commentsCursor"));cq.put("limit",query.get("limit"));
        JSONObject hq=new JSONObject();hq.put("cursor",query.getString("historyCursor"));hq.put("limit",query.get("limit"));
        d.put("comments",pageSnapshots(c,selected.getString("id"),cq,false));d.put("history",pageSnapshots(c,selected.getString("id"),hq,true));d.put("delivery",delivery(c,selected.getString("id")));
        JSONObject source=selected.getJSONObject("sourceTask");if(source!=null){JSONObject info=JSON.parseObject(source.toJSONString());info.put("deleted",count(c,"SELECT COUNT(*) FROM task WHERE id=?",source.getString("id"))==0);d.put("sourceTask",info);}
        d.put("derivedTasks",derivedTasks(c,selected.getString("id"),1,20));return d;
    }
    public JSONObject derivedTasks(String id,JSONObject query){return repository.transaction(c->{task(c,id);return derivedTasks(c,id,number(query,"page",1,1,1000000),number(query,"pageSize",20,1,100));});}
    private JSONObject derivedTasks(Connection c,String id,int page,int size)throws Exception {
        List<JSONObject> children=snapshots(c,"SELECT snapshot FROM task WHERE json_extract(snapshot,'$.sourceTask.id')=? ORDER BY created_at,id LIMIT ? OFFSET ?",id,size,(page-1)*size);
        JSONArray items=new JSONArray();for(JSONObject child:children){JSONObject summary=new JSONObject(true);for(String field:Arrays.asList("id","name","status","revision","contentVersion"))summary.put(field,child.get(field));items.add(summary);}
        JSONObject result=new JSONObject(true);result.put("items",items);result.put("total",count(c,"SELECT COUNT(*) FROM task WHERE json_extract(snapshot,'$.sourceTask.id')=?",id));result.put("page",page);result.put("pageSize",size);return result;
    }
    public JSONObject edit(String id,JSONObject request){fields(request,"contentMarkdown","attachmentIds","expectedRevision","reopen","requestId");
        return repository.transaction(c->dedup(c,"edit:"+id,request,()->{JSONObject t=task(c,id);checkRevision(t,request);
            String body=request.containsKey("contentMarkdown")?markdown(request):t.getString("contentMarkdown");
            JSONArray files=request.containsKey("attachmentIds")?attachmentMetadata(c,request.getJSONArray("attachmentIds")):t.getJSONArray("attachments");
            boolean changed=!Objects.equals(body,t.getString("contentMarkdown"))||!attachmentIds(files).equals(attachmentIds(t.getJSONArray("attachments")));
            if(!changed)return detail(c,t,new JSONObject());
            if(terminal(t.getString("status"))){if(!request.getBooleanValue("reopen"))throw new TaskException("REOPEN_REQUIRED","Explicit reopen is required to edit a terminal task",409);t.put("status","START");t.put("completedAt",null);}
            long previousContentVersion=t.getLongValue("contentVersion");t.put("contentMarkdown",body);t.put("attachments",files);t.put("revision",t.getLongValue("revision")+1);t.put("contentVersion",previousContentVersion+1);t.put("updatedAt",Instant.now().toString());
            checkTotal(c,id,files);save(c,t,"CONTENT_CHANGED","HUMAN");linkContent(c,t);JSONObject eventPayload=new JSONObject(true);eventPayload.put("revision",t.getLong("revision"));eventPayload.put("status",t.getString("status"));eventPayload.put("previousContentVersion",previousContentVersion);if(t.getJSONObject("assignee")!=null||!"OPEN".equals(t.getString("dispatchMode")))event(c,t,"TASK_CONTENT_CHANGED",eventPayload);if(t.getJSONObject("assignee")==null&&"START".equals(t.getString("status")))ensureExecution(c,t);return detail(c,t,new JSONObject());}));
    }
    public JSONObject updateStatus(String id,JSONObject request){fields(request,"status","expectedRevision","observedContentVersion","requestId","actorName","actorType","reason","reopen");
        return repository.transaction(c->dedup(c,"status:"+id,request,()->{JSONObject t=task(c,id);checkRevision(t,request);
            if(positive(request,"observedContentVersion")!=t.getLongValue("contentVersion"))throw conflict("LATEST_CONTENT_REQUIRED",t);
            String old=t.getString("status"),next=required(request.getString("status"),"status",30);String actor=required(request.getString("actorName"),"actorName",200);
            if("IN_PROGRESS".equals(next)&&t.getJSONObject("assignee")==null&&"OPEN".equals(t.getString("dispatchMode")))throw new TaskException("TASK_UNASSIGNED","请先认领或分配任务",409);
            if(!STATUSES.contains(next))throw invalid("Unknown status; expected one of "+STATUSES);if(old.equals(next))return detail(c,t,new JSONObject());
            boolean poolResume=old.equals("SUSPENDED")&&next.equals("START")&&"OPEN".equals(t.getString("dispatchMode"))&&t.getJSONObject("assignee")==null;
            boolean allowed=poolResume||(old.equals("START")&&Arrays.asList("IN_PROGRESS","CANCELLED","SUSPENDED").contains(next))||(old.equals("IN_PROGRESS")&&Arrays.asList("COMPLETED","CANCELLED","SUSPENDED").contains(next))||(old.equals("SUSPENDED")&&Arrays.asList("IN_PROGRESS","CANCELLED").contains(next))||(terminal(old)&&next.equals("START")&&request.getBooleanValue("reopen"));
            if(!allowed)throw transitionConflict(t);
            t.put("status",next);t.put("revision",t.getLongValue("revision")+1);t.put("updatedAt",Instant.now().toString());t.put("completedAt",next.equals("COMPLETED")?t.getString("updatedAt"):null);
            String actorType=request.getString("actorType");if(actorType==null)actorType="HUMAN";if(!Arrays.asList("HUMAN","AGENT","SYSTEM").contains(actorType))throw invalid("Unknown actorType");String reason=request.getString("reason");if(reason!=null&&reason.length()>2000)throw invalid("Status reason is too long");
            JSONObject statusChange=new JSONObject(true);statusChange.put("previousStatus",old);statusChange.put("status",next);statusChange.put("revision",t.getLong("revision"));statusChange.put("contentVersion",t.getLong("contentVersion"));statusChange.put("reason",reason);statusChange.put("actorName",actor);statusChange.put("actorType",actorType);statusChange.put("changedAt",t.getString("updatedAt"));t.put("lastStatusChange",statusChange);
            save(c,t,"STATUS_CHANGED",actor);
            if(Arrays.asList("SUSPENDED","CANCELLED","COMPLETED").contains(next))execute(c,"UPDATE task_outbox SET state='SUPERSEDED',lease_token=NULL,lease_until=0,error='Task no longer executable' WHERE task_id=? AND event_type IN ('TASK_CREATED','TASK_ASSIGNED','TASK_CONTENT_CHANGED') AND state IN ('PENDING','RETRY','PROCESSING')",id);
            if(t.getJSONObject("assignee")!=null||!"OPEN".equals(t.getString("dispatchMode")))event(c,t,"TASK_STATUS_CHANGED",statusChange);
            if("START".equals(next)||("SUSPENDED".equals(old)&&"IN_PROGRESS".equals(next)))ensureExecution(c,t);
            return detail(c,t,new JSONObject());}));
    }
    public JSONObject addComment(String id,JSONObject request){fields(request,"contentMarkdown","attachmentIds","observedContentVersion","authorName","authorType","requestId");
        return repository.transaction(c->dedup(c,"comment:"+id,request,()->{JSONObject t=task(c,id);long observed=positive(request,"observedContentVersion");if(observed>t.getLongValue("contentVersion"))throw conflict("LATEST_CONTENT_REQUIRED",t);
            String body=markdown(request);JSONArray files=attachmentMetadata(c,request.getJSONArray("attachmentIds"));if(body.trim().isEmpty()&&files.isEmpty())throw invalid("Comment content or attachment required");checkTotal(c,id,files);
            JSONObject comment=new JSONObject(true);comment.put("id",UUID.randomUUID().toString());comment.put("taskId",id);comment.put("contentMarkdown",body);comment.put("attachments",files);comment.put("observedContentVersion",observed);
            comment.put("authorName",required(request.getString("authorName"),"authorName",200));String type=request.getString("authorType");if(type==null)type="HUMAN";if(!Arrays.asList("HUMAN","AGENT").contains(type))throw invalid("Unknown authorType");comment.put("authorType",type);comment.put("createdAt",Instant.now().toString());comment.put("requestId",request.getString("requestId"));
            execute(c,"INSERT INTO task_comment VALUES(?,?,?,?)",comment.getString("id"),id,comment.getString("createdAt"),com.alibaba.fastjson.JSON.toJSONString(comment, com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue));for(String a:attachmentIds(files))execute(c,"INSERT INTO task_comment_attachment VALUES(?,?)",comment.getString("id"),a);return comment;}));
    }
    public JSONObject comments(String id,JSONObject query){return repository.transaction(c->{task(c,id);return pageSnapshots(c,id,query,false);});}
    public JSONObject history(String id,JSONObject query){return repository.transaction(c->{task(c,id);return pageSnapshots(c,id,query,true);});}
    private JSONObject pageSnapshots(Connection c,String id,JSONObject query,boolean history)throws Exception{
        int offset=number(query,"cursor",0,0,Integer.MAX_VALUE),limit=number(query,"limit",20,1,100);String table=history?"task_history":"task_comment";
        List<JSONObject> list=snapshots(c,"SELECT snapshot FROM "+table+" WHERE task_id=? ORDER BY "+(history?"revision DESC":"created_at,id")+" LIMIT ? OFFSET ?",id,limit,offset);
        if(history)for(JSONObject item:list){item.remove("contentMarkdown");item.remove("attachments");}
        long total=count(c,"SELECT COUNT(*) FROM "+table+" WHERE task_id=?",id);JSONObject page=new JSONObject(true);page.put("items",list);page.put("total",total);page.put("nextCursor",offset+list.size()<total?String.valueOf(offset+list.size()):null);return page;
    }
    public JSONObject list(JSONObject query){return repository.transaction(c->{List<Object> args=new ArrayList<>();String where=filters(query,args,true);int page=number(query,"page",1,1,1000000),size=number(query,"pageSize",20,1,100);long total=count(c,"SELECT COUNT(*) FROM task"+where,args.toArray());args.add(size);args.add((page-1)*size);
        List<JSONObject> items=snapshots(c,"SELECT snapshot FROM task"+where+" ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",args.toArray());for(JSONObject item:items){decorate(item);item.put("delivery",delivery(c,item.getString("id")));}
        JSONObject result=new JSONObject(true);result.put("items",items);result.put("total",total);result.put("page",page);result.put("pageSize",size);return result;});}
    public JSONObject stats(JSONObject query){return repository.transaction(c->{List<Object> args=new ArrayList<>();String where=filters(query,args,false);JSONObject result=new JSONObject(true);for(String s:STATUSES)result.put(s,0L);try(PreparedStatement s=c.prepareStatement("SELECT status,COUNT(*) FROM task"+where+" GROUP BY status")){bind(s,args.toArray());try(ResultSet r=s.executeQuery()){while(r.next())result.put(r.getString(1),r.getLong(2));}}return result;});}
    private String filters(JSONObject q,List<Object> args,boolean status){StringBuilder w=new StringBuilder(" WHERE 1=1");
        if(q.containsKey("callerIdentity")){
            JSONObject identity=q.getJSONObject("callerIdentity");
            for(String field:Arrays.asList("instanceId","ownerId","surface","agentId","groupId","teamId","teamMemberId")){
                w.append(" AND COALESCE(json_extract(snapshot,'$.assignee.").append(field).append("'),'')=?");
                args.add(identity.getString(field)==null?"":identity.getString(field));
            }
        }
        if(q.containsKey("assignmentState")){
            String state=q.getString("assignmentState");
            if("ASSIGNED".equals(state))w.append(" AND json_extract(snapshot,'$.assignee') IS NOT NULL");
            else if("UNCLAIMED".equals(state))w.append(" AND json_extract(snapshot,'$.assignee') IS NULL AND json_extract(snapshot,'$.dispatchMode')='OPEN'");
            else if("PENDING_ASSIGNMENT".equals(state))w.append(" AND json_extract(snapshot,'$.assignee') IS NULL AND COALESCE(json_extract(snapshot,'$.dispatchMode'),'DIRECT')='DIRECT'");
            else throw invalid("Unknown assignmentState");
        }
        if(q.getBooleanValue("claimableOnly"))w.append(" AND status='START' AND json_extract(snapshot,'$.dispatchMode')='OPEN' AND json_extract(snapshot,'$.assignee') IS NULL");
        if(status&&q.containsKey("statuses")){
            if(q.containsKey("status")||q.containsKey("includeTerminal"))throw invalid("statuses cannot be combined with status or includeTerminal");
            JSONArray values=q.getJSONArray("statuses");if(values==null||values.isEmpty()||values.size()>5)throw invalid("Invalid statuses");
            w.append(" AND status IN (");for(int i=0;i<values.size();i++){if(!STATUSES.contains(values.getString(i)))throw invalid("Unknown status");if(i>0)w.append(',');w.append('?');args.add(values.getString(i));}w.append(')');
        }else if(status&&q.containsKey("includeTerminal")&&!q.getBooleanValue("includeTerminal"))w.append(" AND status NOT IN ('COMPLETED','CANCELLED')");
        String text=q.getString("q");if(text!=null&&!text.isEmpty()){w.append(" AND (instr(lower(json_extract(snapshot,'$.name')),lower(?))>0 OR instr(lower(id),lower(?))>0)");args.add(text);args.add(text);}
        if(status&&q.getString("status")!=null&&!q.getString("status").isEmpty()){if(!STATUSES.contains(q.getString("status")))throw invalid("Unknown status");w.append(" AND status=?");args.add(q.getString("status"));}
        if(q.containsKey("assignee"))throw invalid("Use structured assigneeInstanceId/assigneeAgentId/assigneeTeamId/assigneeTeamMemberId filters");
        boolean identity=q.containsKey("assigneeAgentId")||q.containsKey("assigneeTeamId")||q.containsKey("assigneeTeamMemberId");
        if(identity)required(q.getString("assigneeInstanceId"),"assigneeInstanceId",200);
        if(q.containsKey("assigneeTeamMemberId"))required(q.getString("assigneeTeamId"),"assigneeTeamId",200);
        for(String field:Arrays.asList("instanceId","agentId","teamId","teamMemberId")){String key="assignee"+Character.toUpperCase(field.charAt(0))+field.substring(1);if(q.containsKey(key)){w.append(" AND json_extract(snapshot,'$.assignee.").append(field).append("')=?");args.add(required(q.getString(key),key,200));}}
        String display=q.getString("assigneeName");if(display!=null&&!display.isEmpty()){w.append(" AND (instr(lower(COALESCE(json_extract(snapshot,'$.assignee.displayNameSnapshot'),'')),lower(?))>0 OR instr(lower(COALESCE(json_extract(snapshot,'$.assignee.robotNameSnapshot'),'')),lower(?))>0)");args.add(display);args.add(display);}
        for(String key:Arrays.asList("createdFrom","createdTo")){String value=q.getString(key);if(value!=null&&!value.isEmpty()){try{value=Instant.parse(value).toString();}catch(Exception e){throw invalid("Invalid UTC timestamp");}w.append(key.equals("createdFrom")?" AND julianday(created_at)>=julianday(?)":" AND julianday(created_at)<=julianday(?)");args.add(value);}}
        return w.toString();}
    /** Permanently removes task-owned data; existing Agent turns cannot be retracted. */
    public JSONObject delete(String id, JSONObject request) {
        fields(request, "expectedRevision");
        List<String> files = repository.transaction(c -> {
            JSONObject current = task(c, id);
            checkRevision(current, request);
            List<String> ids = new ArrayList<>();
            try (PreparedStatement statement = c.prepareStatement(
                    "SELECT attachment_id FROM task_content_attachment WHERE task_id=? UNION " +
                    "SELECT attachment_id FROM task_comment_attachment WHERE comment_id IN (SELECT id FROM task_comment WHERE task_id=?)")) {
                bind(statement, id, id);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) ids.add(rows.getString(1));
                }
            }
            execute(c, "DELETE FROM task_receipt WHERE event_id IN (SELECT event_id FROM task_outbox WHERE task_id=?)", id);
            execute(c, "DELETE FROM task_outbox WHERE task_id=?", id);
            execute(c, "DELETE FROM task_comment_attachment WHERE comment_id IN (SELECT id FROM task_comment WHERE task_id=?)", id);
            execute(c, "DELETE FROM task_comment WHERE task_id=?", id);
            execute(c, "DELETE FROM task_content_attachment WHERE task_id=?", id);
            execute(c, "DELETE FROM task_history WHERE task_id=?", id);
            // Do not keep deleted content in cached create/edit/comment responses.
            execute(c, "DELETE FROM task_request_dedup WHERE operation IN (?,?,?) OR json_extract(result,'$.task.id')=? OR json_extract(result,'$.taskId')=?",
                    "edit:" + id, "status:" + id, "comment:" + id, id, id);
            execute(c, "DELETE FROM task WHERE id=?", id);
            return ids;
        });
        attachments.cleanupUnreferenced(files);
        JSONObject result = new JSONObject(true);
        result.put("id", id);
        result.put("deleted", true);
        return result;
    }

    public JSONObject retryDelivery(String id){return repository.transaction(c->{JSONObject t=task(c,id);execute(c,"UPDATE task_outbox SET state='PENDING',next_attempt_at=0,error=NULL WHERE task_id=? AND state='RETRY'",id);return detail(c,t,new JSONObject());});}
    private static void ensureExecution(Connection c,JSONObject t)throws SQLException {
        String id=t.getString("id");
        if(t.getJSONObject("assignee")==null){
            if("OPEN".equals(t.getString("dispatchMode")))return;
            if(count(c,"SELECT COUNT(*) FROM task_outbox WHERE task_id=? AND event_type='TASK_CREATED' AND state IN ('PENDING','RETRY','PROCESSING')",id)==0)event(c,t,"TASK_CREATED");
        }else event(c,t,"TASK_ASSIGNED");
    }
    private interface Operation{JSONObject run()throws Exception;}
    private JSONObject dedupReplay(Connection c,String operation,JSONObject request)throws Exception {
        String requestId=required(request.getString("requestId"),"requestId",200),digest=sha256(canonical(request).getBytes(StandardCharsets.UTF_8));
        try(PreparedStatement statement=c.prepareStatement("SELECT digest,result FROM task_request_dedup WHERE operation=? AND request_id=?")){
            bind(statement,operation,requestId);try(ResultSet result=statement.executeQuery()){if(result.next()){
                if(!digest.equals(result.getString(1)))throw new TaskException("IDEMPOTENCY_CONFLICT","requestId reused with different parameters",409);
                return JSON.parseObject(result.getString(2));
            }}
        }return null;
    }
    private JSONObject dedup(Connection c,String operation,JSONObject request,Operation action)throws Exception{
        String requestId=required(request.getString("requestId"),"requestId",200),digest=sha256(canonical(request).getBytes(StandardCharsets.UTF_8));
        try(PreparedStatement s=c.prepareStatement("SELECT digest,result FROM task_request_dedup WHERE operation=? AND request_id=?")){bind(s,operation,requestId);try(ResultSet r=s.executeQuery()){if(r.next()){if(!digest.equals(r.getString(1)))throw new TaskException("IDEMPOTENCY_CONFLICT","requestId reused with different parameters",409);return JSON.parseObject(r.getString(2));}}}
        JSONObject result=action.run();execute(c,"INSERT INTO task_request_dedup VALUES(?,?,?,?)",operation,requestId,digest,com.alibaba.fastjson.JSON.toJSONString(result, com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue));return result;
    }
    private static String canonical(Object object){if(object instanceof Map){TreeMap<String,Object> map=new TreeMap<>();for(Object key:((Map<?,?>)object).keySet())map.put(String.valueOf(key),((Map<?,?>)object).get(key));StringBuilder b=new StringBuilder("{");for(Map.Entry<String,Object> e:map.entrySet())b.append(JSON.toJSONString(e.getKey())).append(':').append(canonical(e.getValue())).append(',');return b.append('}').toString();}if(object instanceof List){StringBuilder b=new StringBuilder("[");for(Object v:(List<?>)object)b.append(canonical(v)).append(',');return b.append(']').toString();}return JSON.toJSONString(object);}
    public static String sha256(byte[] bytes){try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder b=new StringBuilder();for(byte value:digest)b.append(String.format("%02x",value&255));return b.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private JSONArray attachmentMetadata(Connection c,JSONArray ids)throws Exception{JSONArray result=new JSONArray();if(ids==null)return result;if(ids.size()>10)throw new TaskException("RESOURCE_LIMIT","At most 10 attachments",413);Set<String> seen=new HashSet<>();for(Object raw:ids){if(!(raw instanceof String)||!seen.add((String)raw))throw invalid("Invalid or duplicate attachment ID");List<JSONObject> found=snapshots(c,"SELECT snapshot FROM task_attachment WHERE id=?",raw);if(found.isEmpty())throw new TaskException("NOT_FOUND","Attachment not found",404);result.add(found.get(0));}return result;}
    private static Set<String> attachmentIds(JSONArray files){Set<String> ids=new TreeSet<>();if(files!=null)for(int i=0;i<files.size();i++)ids.add(files.getJSONObject(i).getString("id"));return ids;}
    private static void linkContent(Connection c,JSONObject t)throws SQLException{for(String id:attachmentIds(t.getJSONArray("attachments")))execute(c,"INSERT INTO task_content_attachment VALUES(?,?,?)",t.getString("id"),t.getLongValue("contentVersion"),id);}
    private static void checkTotal(Connection c,String taskId,JSONArray files)throws Exception{
        Set<String> ids=attachmentIds(files);try(PreparedStatement s=c.prepareStatement("SELECT attachment_id FROM task_content_attachment WHERE task_id=? UNION SELECT a.attachment_id FROM task_comment_attachment a JOIN task_comment m ON a.comment_id=m.id WHERE m.task_id=?")){bind(s,taskId,taskId);try(ResultSet r=s.executeQuery()){while(r.next())ids.add(r.getString(1));}}
        long total=0;for(String id:ids){List<JSONObject> m=snapshots(c,"SELECT snapshot FROM task_attachment WHERE id=?",id);if(!m.isEmpty())total+=m.get(0).getLongValue("size");}if(total>200L*1024*1024)throw new TaskException("RESOURCE_LIMIT","Task attachment total exceeds 200 MiB",413);
    }
    private static long count(Connection c,String sql,Object... args)throws SQLException{try(PreparedStatement s=c.prepareStatement(sql)){bind(s,args);try(ResultSet r=s.executeQuery()){r.next();return r.getLong(1);}}}
    private static void checkRevision(JSONObject t,JSONObject request){if(positive(request,"expectedRevision")!=t.getLongValue("revision"))throw conflict("VERSION_CONFLICT",t);}
    private static TaskException conflict(String code,JSONObject t){JSONObject d=new JSONObject();d.put("currentRevision",t.get("revision"));d.put("currentContentVersion",t.get("contentVersion"));d.put("currentStatus",t.get("status"));return new TaskException(code,"Read the latest task before retrying",409,d);}
    private static TaskException transitionConflict(JSONObject t){JSONObject d=new JSONObject();d.put("currentRevision",t.get("revision"));d.put("currentContentVersion",t.get("contentVersion"));d.put("currentStatus",t.get("status"));d.put("allowedTransitions",allowedTransitions(t,true));d.put("agentAllowedTransitions",allowedTransitions(t,false));return new TaskException("INVALID_TRANSITION","Invalid task status transition",400,d);}
    private static JSONArray allowedTransitions(JSONObject task,boolean includeHumanReopen) {
        if("OPEN".equals(task.getString("dispatchMode"))&&task.getJSONObject("assignee")==null) {
            JSONArray result=new JSONArray();String status=task.getString("status");
            if("START".equals(status)){result.add("CANCELLED");result.add("SUSPENDED");}
            else if("SUSPENDED".equals(status)){result.add("START");result.add("CANCELLED");}
            else if(includeHumanReopen&&terminal(status))result.add("START");
            return result;
        }
        return allowedTransitions(task.getString("status"),includeHumanReopen);
    }
    private static JSONArray allowedTransitions(String status,boolean includeHumanReopen){JSONArray result=new JSONArray();if("START".equals(status)){result.add("IN_PROGRESS");result.add("CANCELLED");result.add("SUSPENDED");}else if("IN_PROGRESS".equals(status)){result.add("COMPLETED");result.add("CANCELLED");result.add("SUSPENDED");}else if("SUSPENDED".equals(status)){result.add("IN_PROGRESS");result.add("CANCELLED");}else if(includeHumanReopen&&terminal(status)){result.add("START");}return result;}
    private static boolean terminal(String status){return "COMPLETED".equals(status)||"CANCELLED".equals(status);}
    private static String markdown(JSONObject r){String value=r.getString("contentMarkdown");if(value==null)return "";if(value.length()>1024*1024)throw new TaskException("RESOURCE_LIMIT","Markdown too large",413);return value;}
    public static String required(String text,String field,int max){if(text==null||text.trim().isEmpty()||text.length()>max)throw invalid("Invalid "+field);return text;}
    public static long positive(JSONObject object,String key){try{Object value=object.get(key);if(value==null||!String.valueOf(value).matches("[0-9]+"))throw invalid("Invalid "+key);long n=Long.parseLong(value.toString());if(n<1)throw invalid("Invalid "+key);return n;}catch(NumberFormatException e){throw invalid("Invalid "+key);}}
    private static int number(JSONObject object,String key,int fallback,int min,int max){Object v=object.get(key);if(v==null||"".equals(v))return fallback;try{int n=Integer.parseInt(v.toString());if(n<min||n>max)throw invalid("Invalid "+key);return n;}catch(NumberFormatException e){throw invalid("Invalid "+key);}}
    private static void fields(JSONObject r,String... allowed){if(r==null)throw invalid("Object body required");Set<String> names=new HashSet<>(Arrays.asList(allowed));for(String name:r.keySet())if(!names.contains(name))throw invalid("Unknown field: "+name);}
    private static TaskException invalid(String message){return new TaskException("INVALID_ARGUMENT",message,400);}
    @Override public void close(){repository.close();}
}
