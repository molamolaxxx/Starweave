package com.mola.cmd.proxy.app.acp.action;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Trusted, read-only runtime catalog. Routing fields and namespaces never come from tool arguments. */
public final class AgentDirectoryService {
    private final Supplier<JSONArray> catalog;
    public AgentDirectoryService(Supplier<JSONArray> catalog){this.catalog=Objects.requireNonNull(catalog);}
    private static String text(JSONObject row,String key){String value=row.getString(key);return value==null?"":value;}
    private static String boundary(JSONObject row){
        String surface=text(row,"visibility_surface"),owner=text(row,"owner_id");
        if(!Arrays.asList("STARWEAVE","MOLACHAT").contains(surface)||owner.isEmpty())throw new IllegalStateException("Agent owner cannot be verified");
        return surface+"\n"+owner;
    }
    public static String reference(JSONObject row){return "agent_"+digest(text(row,"instance_id")+"\n"+boundary(row)+"\n"+text(row,"scope")+"\n"+text(row,"logical_id")+"\n"+text(row,"team_id")+"\n"+text(row,"member_id"));}
    private static String digest(String value){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02x",b&255));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private JSONObject self(JSONArray rows,String logicalId){
        JSONObject result=null;for(Object item:rows){JSONObject row=(JSONObject)item;if(logicalId.equals(row.getString("logical_id"))){if(result!=null)throw new IllegalStateException("Ambiguous Agent identity");result=row;}}
        if(result==null)throw new IllegalStateException("Agent identity is unavailable");boundary(result);return result;
    }
    public JSONObject list(String logicalId){
        JSONArray rows=catalog.get(),agents=new JSONArray();JSONObject self=self(rows,logicalId);String access=boundary(self);
        for(Object item:rows){JSONObject row=(JSONObject)item;if(!access.equals(boundary(row)))continue;
            JSONObject copy=JSONObject.parseObject(row.toJSONString());copy.put("agent_ref",reference(row));copy.put("is_self",logicalId.equals(row.getString("logical_id")));
            copy.remove("visibility_surface");copy.remove("owner_id");copy.remove("logical_id");copy.remove("history_namespace");copy.remove("task_target");
            JSONObject session=copy.getJSONObject("current_session");if(session!=null&&!allowsNamespace(rows,access,row.getString("history_namespace"))){session.put("session_ref",null);session.put("history_available",false);session.put("history_unavailable_reason","会话存储归属无法唯一确认");}
            agents.add(copy);
        }
        agents.sort(Comparator.comparing(item->((JSONObject)item).getString("agent_ref")));
        JSONObject result=new JSONObject(true);result.put("agents",agents);result.put("observed_at",Instant.now().toString());return result;
    }
    public JSONObject resolveTaskTarget(String logicalId,String ref){
        JSONArray rows=catalog.get();String access=boundary(self(rows,logicalId));
        if(!access.startsWith("STARWEAVE\n"))throw new IllegalArgumentException("Task publishing requires a Starweave identity");
        for(Object item:rows){JSONObject row=(JSONObject)item;if(access.equals(boundary(row))&&reference(row).equals(ref)){
            if(!row.getBooleanValue("task_assignable")||row.getJSONObject("task_target")==null)throw new IllegalArgumentException("Agent is not eligible for task assignment");
            return JSONObject.parseObject(row.getJSONObject("task_target").toJSONString());
        }}throw new IllegalArgumentException("Agent reference is unavailable in the caller's scope; call list_agents again");
    }
    public JSONObject historyOwner(String logicalId) {
        JSONArray rows=catalog.get();JSONObject self=self(rows,logicalId);
        if(!allowsNamespace(rows,boundary(self),self.getString("history_namespace")))return null;
        JSONObject owner=new JSONObject();owner.put("visibility_surface",self.getString("visibility_surface"));owner.put("owner_id",self.getString("owner_id"));return owner;
    }
    public boolean allowsPersistedOwner(String logicalId,JSONObject metadata){
        if(metadata==null)return false;
        try{return boundary(self(catalog.get(),logicalId)).equals(boundary(metadata));}catch(IllegalStateException unknown){return false;}
    }
    public boolean allowsNamespace(String logicalId,String namespace){
        JSONArray rows=catalog.get();return allowsNamespace(rows,boundary(self(rows,logicalId)),namespace);
    }
    private boolean allowsNamespace(JSONArray rows,String access,String namespace){
        if(namespace==null)return false;boolean found=false;
        for(Object item:rows){JSONObject row=(JSONObject)item;if(namespace.equals(row.getString("history_namespace"))){if(!access.equals(boundary(row)))return false;found=true;}}
        return found;
    }
}
