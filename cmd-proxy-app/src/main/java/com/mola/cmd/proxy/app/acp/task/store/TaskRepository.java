package com.mola.cmd.proxy.app.acp.task.store;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.task.model.TaskException;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** A single short-transaction writer; no external callbacks are executed here. */
public final class TaskRepository implements AutoCloseable {
    private final Connection connection;
    public interface Work<T> { T run(Connection connection) throws Exception; }
    public TaskRepository(Path directory) {
        try {
            Files.createDirectories(directory);
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("tasks.db").toAbsolutePath());
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA busy_timeout=5000"); s.execute("PRAGMA foreign_keys=ON");
                s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA synchronous=FULL");
            }
            TaskMigrationRunner.migrate(connection);
        } catch (Exception e) { throw storage(e); }
    }
    public synchronized <T> T transaction(Work<T> work) {
        try {
            connection.setAutoCommit(false);
            // Acquire the writer before reading state, including across repository instances.
            execute(connection, "UPDATE schema_migration SET version=version WHERE version=1");
            T result = work.run(connection); connection.commit(); return result;
        } catch (Exception e) {
            try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
            if (e instanceof TaskException) throw (TaskException)e;
            throw storage(e);
        } finally { try { connection.setAutoCommit(true); } catch (SQLException ignored) { } }
    }
    public static TaskException storage(Exception e) { return new TaskException("STORAGE_UNAVAILABLE", "Task storage unavailable: " + e.getClass().getSimpleName(), 503); }
    public static int execute(Connection c,String sql,Object... args) throws SQLException {
        try (PreparedStatement s=c.prepareStatement(sql)) { bind(s,args); return s.executeUpdate(); }
    }
    public static void bind(PreparedStatement s,Object... args) throws SQLException { for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]); }
    public static List<JSONObject> snapshots(Connection c,String sql,Object... args) throws SQLException {
        List<JSONObject> values=new ArrayList<>();
        try(PreparedStatement s=c.prepareStatement(sql)) { bind(s,args); try(ResultSet r=s.executeQuery()) { while(r.next()) values.add(JSON.parseObject(r.getString(1))); } }
        return values;
    }
    public static JSONObject task(Connection c,String id) throws SQLException {
        List<JSONObject> found=snapshots(c,"SELECT snapshot FROM task WHERE id=?",id);
        if(found.isEmpty()) throw new TaskException("NOT_FOUND","Task not found",404); return found.get(0);
    }
    public static void save(Connection c,JSONObject t,String change,String actor) throws SQLException {
        execute(c,"INSERT INTO task(id,status,revision,content_version,created_at,updated_at,snapshot) VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET status=excluded.status,revision=excluded.revision,content_version=excluded.content_version,updated_at=excluded.updated_at,snapshot=excluded.snapshot",
                t.getString("id"),t.getString("status"),t.getLongValue("revision"),t.getLongValue("contentVersion"),t.getString("createdAt"),t.getString("updatedAt"),com.alibaba.fastjson.JSON.toJSONString(t, com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue));
        JSONObject h=JSON.parseObject(com.alibaba.fastjson.JSON.toJSONString(t, com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue)); h.put("changeType",change); h.put("actorName",actor); h.put("changedAt",t.getString("updatedAt"));
        execute(c,"INSERT INTO task_history VALUES(?,?,?,?)",t.getString("id"),t.getLongValue("revision"),t.getLongValue("contentVersion"),com.alibaba.fastjson.JSON.toJSONString(h, com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue));
    }
    public static void event(Connection c,JSONObject t,String type) throws SQLException {
        JSONObject payload=new JSONObject(true);
        payload.put("revision",t.getLong("revision"));payload.put("status",t.getString("status"));
        event(c,t,type,payload);
    }
    public static void event(Connection c,JSONObject t,String type,JSONObject payload) throws SQLException {
        event(c,t,type,payload,t.getJSONObject("assignee"));
    }
    /** Each recipient has its own durable event and delivery receipt. */
    public static void event(Connection c,JSONObject t,String type,JSONObject payload,JSONObject recipient) throws SQLException {
        execute(c,"INSERT INTO task_outbox(event_id,task_id,content_version,event_type,seq,assignee,state,payload) SELECT ?,?,?,?,?,?,?,?",
                UUID.randomUUID().toString(),t.getString("id"),t.getLongValue("contentVersion"),type,nextSequence(c,t.getString("id")),recipient==null?null:recipient.toJSONString(),"PENDING",payload==null?null:payload.toJSONString());
    }
    private static long nextSequence(Connection c,String id) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("SELECT COALESCE(MAX(seq),0)+1 FROM task_outbox WHERE task_id=?")) { s.setString(1,id); try(ResultSet r=s.executeQuery()) { r.next(); return r.getLong(1); } }
    }
    public JSONObject assign(String taskId,JSONObject assignee) {
        if(assignee==null || assignee.isEmpty()) throw new TaskException("INVALID_ARGUMENT","Assignee required",400);
        return transaction(c->assignInTransaction(c,task(c,taskId),assignee));
    }
    /** Candidate discovery happens outside the transaction; occupancy and selection commit together. */
    public JSONObject assign(String taskId,List<JSONObject> candidates,String mode) {
        return transaction(c->{JSONObject t=task(c,taskId);if(t.getJSONObject("assignee")!=null)return t;
            return assignInTransaction(c,t,selectAssignee(c,taskId,candidates,mode));});
    }
    public static JSONObject selectAssignee(Connection c,String taskId,List<JSONObject> candidates,String mode) throws SQLException {
        if(candidates==null||candidates.isEmpty()||candidates.size()>1000||("FIXED".equals(mode)&&candidates.size()!=1)||!Arrays.asList("FIXED","RANDOM","AFFINITY").contains(mode))
            throw new TaskException("INVALID_ARGUMENT","Invalid assignment candidates or mode",400);
        List<JSONObject> stable=new ArrayList<>();Set<String> identities=new HashSet<>();
        for(JSONObject candidate:candidates){if(candidate==null||candidate.isEmpty()||!identities.add(assignmentKey(candidate)))throw new TaskException("INVALID_ARGUMENT","Invalid or duplicate candidate",400);stable.add(JSON.parseObject(candidate.toJSONString()));}
        stable.sort(Comparator.comparing(TaskRepository::assignmentKey));JSONObject chosen=stable.get(0);
        if("RANDOM".equals(mode))chosen=stable.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(stable.size()));
        if("AFFINITY".equals(mode)){
            Set<String> occupied=new HashSet<>();for(JSONObject active:snapshots(c,"SELECT snapshot FROM task WHERE status NOT IN ('COMPLETED','CANCELLED')")){JSONObject a=active.getJSONObject("assignee");if(a!=null)occupied.add(assignmentKey(a));}
            chosen=null;for(JSONObject candidate:stable)if(!occupied.contains(assignmentKey(candidate))){chosen=candidate;break;}
            if(chosen==null)chosen=stable.get(Math.floorMod(taskId.hashCode(),stable.size()));
        }
        return chosen;
    }
    private static String assignmentKey(JSONObject a){StringBuilder key=new StringBuilder();for(String field:Arrays.asList("instanceId","ownerId","agentId","groupId","teamId","teamMemberId")){String value=a.getString(field);key.append(value==null?"":value).append('\n');}return key.toString();}
    private static JSONObject assignInTransaction(Connection c,JSONObject t,JSONObject assignee)throws SQLException {
        if(t.getJSONObject("assignee")!=null || Arrays.asList("COMPLETED","CANCELLED").contains(t.getString("status")))return t;
        t.put("assignee",JSON.parseObject(assignee.toJSONString()));t.put("revision",t.getLongValue("revision")+1);t.put("updatedAt",Instant.now().toString());
        save(c,t,"ASSIGNED","SYSTEM");event(c,t,"TASK_ASSIGNED");return t;
    }
    public List<JSONObject> activeAssignments() { return transaction(c->{List<JSONObject> values=snapshots(c,"SELECT snapshot FROM task WHERE status NOT IN ('COMPLETED','CANCELLED') ORDER BY created_at,id");for(JSONObject value:values)value.put("taskId",value.getString("id"));return values;}); }
    public List<JSONObject> claimOutbox(String workerId,int limit,long nowMillis,long leaseMillis) {
        if(workerId==null || workerId.trim().isEmpty() || limit<1 || limit>100 || leaseMillis<1 || leaseMillis>600000) throw new TaskException("INVALID_ARGUMENT","Invalid outbox lease",400);
        return transaction(c->{ List<JSONObject> result=new ArrayList<>();
            String sql="SELECT * FROM task_outbox o WHERE ((state IN ('PENDING','RETRY') AND next_attempt_at<=?) OR (state='PROCESSING' AND lease_until<=?)) AND NOT EXISTS (SELECT 1 FROM task_outbox earlier WHERE earlier.task_id=o.task_id AND earlier.seq<o.seq AND earlier.state NOT IN ('ACKNOWLEDGED','SUPERSEDED','COMPLETED')) ORDER BY rowid LIMIT ?";
            try(PreparedStatement s=c.prepareStatement(sql)) { bind(s,nowMillis,nowMillis,limit); try(ResultSet r=s.executeQuery()) { while(r.next()) result.add(outbox(r)); } }
            for(JSONObject e:result) { String token=UUID.randomUUID().toString();
                execute(c,"UPDATE task_outbox SET state='PROCESSING',attempts=attempts+1,lease_token=?,lease_until=? WHERE event_id=?",token,nowMillis+leaseMillis,e.getString("eventId"));
                e.put("state","PROCESSING"); e.put("attempts",e.getIntValue("attempts")+1);e.put("leaseToken",token);e.put("leaseUntil",nowMillis+leaseMillis);
            } return result; });
    }
    /** Internal assignment completion is not an external durable-delivery ACK. */
    public boolean completeAssignment(String eventId,String leaseToken) {
        return transaction(c->execute(c,"UPDATE task_outbox SET state='COMPLETED',lease_token=NULL,lease_until=0,error=NULL WHERE event_id=? AND event_type='TASK_CREATED' AND state='PROCESSING' AND lease_token=? AND lease_until>? AND EXISTS(SELECT 1 FROM task t WHERE t.id=task_outbox.task_id AND json_extract(t.snapshot,'$.assignee') IS NOT NULL)",eventId,leaseToken,System.currentTimeMillis())==1);
    }
    public boolean acknowledge(String eventId,String leaseToken,String receipt) {
        if(receipt==null || receipt.trim().isEmpty()) throw new TaskException("INVALID_ARGUMENT","Durable receipt required",400);
        return finish(eventId,leaseToken,"ACKNOWLEDGED",0,null,receipt);
    }
    public boolean retry(String eventId,String leaseToken,long nextAttemptAt,String error) { return finish(eventId,leaseToken,"RETRY",nextAttemptAt,error,null); }
    public boolean discard(String eventId,String leaseToken,String reason) { return finish(eventId,leaseToken,"SUPERSEDED",0,reason,null); }
    private boolean finish(String id,String token,String state,long next,String error,String receipt) {
        return transaction(c->execute(c,"UPDATE task_outbox SET state=?,next_attempt_at=?,error=?,receipt=?,lease_token=NULL,lease_until=0 WHERE event_id=? AND state='PROCESSING' AND lease_token=? AND lease_until>? AND (event_type<>'TASK_CREATED' OR ?<>'ACKNOWLEDGED')",state,next,error==null?null:error.substring(0,Math.min(1000,error.length())),receipt,id,token,System.currentTimeMillis(),state)==1);
    }
    public static List<JSONObject> delivery(Connection c,String taskId) throws SQLException {
        List<JSONObject> result=new ArrayList<>(); try(PreparedStatement s=c.prepareStatement("SELECT * FROM task_outbox WHERE task_id=? ORDER BY seq")) { bind(s,taskId);try(ResultSet r=s.executeQuery()) {while(r.next())result.add(outbox(r));}}return result;
    }
    private static JSONObject outbox(ResultSet r) throws SQLException {
        JSONObject e=new JSONObject(true);e.put("eventId",r.getString("event_id"));e.put("taskId",r.getString("task_id"));e.put("contentVersion",r.getLong("content_version"));e.put("eventType",r.getString("event_type"));e.put("seq",r.getLong("seq"));e.put("assignee",JSON.parseObject(r.getString("assignee")));e.put("state",r.getString("state"));e.put("attempts",r.getInt("attempts"));e.put("nextAttemptAt",r.getLong("next_attempt_at"));e.put("error",r.getString("error"));e.put("receipt",r.getString("receipt"));e.put("payload",JSON.parseObject(r.getString("payload")));return e;
    }
    public JSONObject acceptReceipt(String eventId,JSONObject payload) {
        return acceptReceipt(eventId, payload, false);
    }
    public JSONObject acceptReceipt(String eventId,JSONObject payload,boolean requireLocalTask) {
        if(eventId==null || eventId.length()>200 || payload==null)throw new TaskException("INVALID_ARGUMENT","Invalid receipt",400);
        return transaction(c->{
            if (requireLocalTask) {
                JSONObject card = payload.getJSONObject("card");
                if (card == null) throw new TaskException("INVALID_ARGUMENT", "Task card required", 400);
                task(c, card.getString("taskId"));
            }
            JSONObject result=new JSONObject(true);String receipt=null;JSONObject stored=null;
            try(PreparedStatement s=c.prepareStatement("SELECT receipt,payload FROM task_receipt WHERE event_id=?")){bind(s,eventId);try(ResultSet r=s.executeQuery()){if(r.next()){receipt=r.getString(1);stored=JSON.parseObject(r.getString(2));}}}
            boolean duplicate=receipt!=null;
            if(!duplicate){receipt=UUID.randomUUID().toString();stored=payload;execute(c,"INSERT INTO task_receipt(event_id,receipt,payload) VALUES(?,?,?)",eventId,receipt,com.alibaba.fastjson.JSON.toJSONString(payload, com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue));}
            else if(!stored.equals(payload))throw new TaskException("IDEMPOTENCY_CONFLICT","Event payload differs",409);
            result.put("eventId",eventId);result.put("receipt",receipt);result.put("duplicate",duplicate);result.put("payload",stored);return result;});
    }
    public List<JSONObject> pendingReceipts(int limit) { return transaction(c->{List<JSONObject> items=new ArrayList<>();try(PreparedStatement s=c.prepareStatement("SELECT event_id,receipt,payload FROM task_receipt WHERE completed=0 ORDER BY rowid LIMIT ?")){bind(s,Math.max(1,Math.min(100,limit)));try(ResultSet r=s.executeQuery()){while(r.next()){JSONObject v=new JSONObject();v.put("eventId",r.getString(1));v.put("receipt",r.getString(2));v.put("payload",JSON.parseObject(r.getString(3)));items.add(v);}}}return items;}); }
    /** Diagnostic listing is not authority to start a prompt. Consumers must claim first. */
    public List<JSONObject> claimReceipts(String workerId,int limit,long nowMillis,long leaseMillis) {
        validateReceiptLease(workerId,limit,leaseMillis);
        return transaction(c->{List<JSONObject> items=new ArrayList<>();
            try(PreparedStatement s=c.prepareStatement("SELECT event_id,receipt,payload,attempts FROM task_receipt WHERE completed=0 AND lease_until<=? ORDER BY rowid LIMIT ?")){bind(s,nowMillis,limit);try(ResultSet r=s.executeQuery()){while(r.next()){JSONObject item=new JSONObject(true);item.put("eventId",r.getString(1));item.put("receipt",r.getString(2));item.put("payload",JSON.parseObject(r.getString(3)));item.put("attempts",r.getInt(4)+1);items.add(item);}}}
            for(JSONObject item:items){String token=UUID.randomUUID().toString();execute(c,"UPDATE task_receipt SET lease_token=?,lease_until=?,attempts=attempts+1 WHERE event_id=?",token,nowMillis+leaseMillis,item.getString("eventId"));item.put("leaseToken",token);item.put("leaseUntil",nowMillis+leaseMillis);}return items;
        });
    }
    public boolean completeReceipt(String eventId,String leaseToken) { return receiptMutation(eventId,leaseToken,true); }
    public boolean retryReceipt(String eventId,String leaseToken) { return receiptMutation(eventId,leaseToken,false); }
    private boolean receiptMutation(String id,String token,boolean complete){return transaction(c->execute(c,"UPDATE task_receipt SET completed=?,lease_token=NULL,lease_until=0 WHERE event_id=? AND completed=0 AND lease_token=? AND lease_until>?",complete?1:0,id,token,System.currentTimeMillis())==1);}
    public boolean renewReceipt(String id,String token,long leaseMillis){validateReceiptLease("renew",1,leaseMillis);long now=System.currentTimeMillis();return transaction(c->execute(c,"UPDATE task_receipt SET lease_until=? WHERE event_id=? AND completed=0 AND lease_token=? AND lease_until>?",now+leaseMillis,id,token,now)==1);}
    private static void validateReceiptLease(String workerId,int limit,long leaseMillis){if(workerId==null||workerId.trim().isEmpty()||limit<1||limit>100||leaseMillis<1||leaseMillis>600000)throw new TaskException("INVALID_ARGUMENT","Invalid receipt lease",400);}
    /** @deprecated Unfenced completion is unsafe; claimReceipts and use the token overload. */
    @Deprecated public boolean completeReceipt(String eventId) { throw new TaskException("INVALID_ARGUMENT","Receipt leaseToken required",400); }
    @Override public synchronized void close() {try{connection.close();}catch(SQLException e){throw storage(e);}}
}
