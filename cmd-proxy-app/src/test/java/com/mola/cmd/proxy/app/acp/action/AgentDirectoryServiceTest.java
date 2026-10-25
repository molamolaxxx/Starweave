package com.mola.cmd.proxy.app.acp.action;

import com.alibaba.fastjson.*;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.sessionquery.ConversationQueryService;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class AgentDirectoryServiceTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    JSONObject row(String id,String surface,String owner,String scope,String namespace){
        JSONObject row=JSON.parseObject("{instance_id:'instance',enabled:true,state:'READY',task_assignable:true,task_target:{type:'AGENT',agentId:'trusted'}}");
        row.put("logical_id",id);row.put("visibility_surface",surface);row.put("owner_id",owner);row.put("scope",scope);row.put("history_namespace",namespace);row.put("display_name",id);
        row.put("current_session",JSON.parseObject("{session_id:'s',session_ref:'opaque',history_available:true}"));return row;
    }
    @Test public void separatesSurfacesOwnersAndTeamIdentitiesWithoutLeakingRoutingFields() {
        JSONArray rows=new JSONArray();rows.add(row("s","STARWEAVE","starweave-instance","main","starweave/s"));
        JSONObject team=row("st","STARWEAVE","starweave-instance","team","team/ts/m");team.put("team_id","ts");team.put("member_id","m");rows.add(team);
        rows.add(row("m","MOLACHAT","alice","main","AliceAgent"));rows.add(row("mt","MOLACHAT","alice","team","team/tm/m"));rows.add(row("other","MOLACHAT","bob","main","BobAgent"));
        AgentDirectoryService service=new AgentDirectoryService(()->rows);
        assertEquals(2,service.list("s").getJSONArray("agents").size());assertEquals(2,service.list("m").getJSONArray("agents").size());assertEquals(1,service.list("other").getJSONArray("agents").size());
        assertFalse(service.list("m").toJSONString().contains("starweave"));assertFalse(service.list("s").toJSONString().contains("history_namespace"));assertFalse(service.list("s").toJSONString().contains("task_target"));
        String ref=AgentDirectoryService.reference(team);assertEquals("trusted",service.resolveTaskTarget("s",ref).getString("agentId"));
        try{service.resolveTaskTarget("m",ref);fail();}catch(IllegalArgumentException expected){}
        assertFalse(AgentDirectoryService.reference((JSONObject)rows.get(0)).equals(ref));
        try{service.list("missing");fail();}catch(IllegalStateException expected){}
        team.put("task_assignable",false);try{service.resolveTaskTarget("s",ref);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void rejectsAmbiguousLegacyNamespaceAndLiveRefsCannotBypassHistoryAccess() throws Exception {
        JSONArray rows=new JSONArray();rows.add(row("s","STARWEAVE","starweave-instance","main","starweave/s"));rows.add(row("a","MOLACHAT","alice","main","Shared"));rows.add(row("b","MOLACHAT","bob","main","Shared"));
        AgentDirectoryService directory=new AgentDirectoryService(()->rows);
        assertFalse(directory.allowsNamespace("a","Shared"));assertFalse(directory.list("a").getJSONArray("agents").getJSONObject(0).getJSONObject("current_session").getBooleanValue("history_available"));
        java.nio.file.Path root=temp.newFolder().toPath();
        try(ConversationQueryService star=new ConversationQueryService(root,root.resolve("starweave/s"),"Star",()->"star-session",com.google.gson.JsonArray::new);
            ConversationQueryService mola=new ConversationQueryService(root,root.resolve("Shared"),"Mola",()->"mola-session",com.google.gson.JsonArray::new)){
            star.setNamespaceAccess(key->directory.allowsNamespace("s",key));mola.setNamespaceAccess(key->directory.allowsNamespace("a",key));
            String foreign=star.currentSessionReference();com.google.gson.JsonObject args=JsonParser.parseString("{session_refs:['"+foreign+"']}").getAsJsonObject();
            assertEquals("SESSION_NOT_FOUND",JsonParser.parseString(mola.readHistory(args)).getAsJsonObject().getAsJsonArray("sessions").get(0).getAsJsonObject().getAsJsonObject("error").get("code").getAsString());
            assertEquals(0,JsonParser.parseString(star.readHistory(args)).getAsJsonObject().getAsJsonArray("sessions").get(0).getAsJsonObject().getAsJsonArray("messages").size());
        }
    }
    @Test public void archivedTeamHistoryRetainsVerifiedOwnerIsolation() throws Exception {
        java.nio.file.Path root=temp.newFolder().toPath();com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity id=com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity.team("member","transport","team/t/m","alice","t","m","Agent");
        com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager history=new com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager(id,root);
        history.setQueryAccessOwner(JsonParser.parseString("{visibility_surface:'MOLACHAT',owner_id:'alice'}").getAsJsonObject());history.addUserMessage("archive");history.flushTurn("session");
        java.nio.file.Path archive=root.resolveSibling("team-archive").resolve("t");java.nio.file.Files.createDirectories(archive);java.nio.file.Files.move(root.resolve("team/t/m"),archive.resolve("m"));
        JSONArray rows=new JSONArray();rows.add(row("alice","MOLACHAT","alice","main","Alice"));rows.add(row("bob","MOLACHAT","bob","main","Bob"));AgentDirectoryService directory=new AgentDirectoryService(()->rows);
        try(ConversationQueryService query=new ConversationQueryService(root,root.resolve("Alice"),"Alice",()->null,com.google.gson.JsonArray::new)){
            query.setNamespaceAccess(key->directory.allowsNamespace("alice",key));query.setPersistedOwnerAccess(meta->directory.allowsPersistedOwner("alice",JSON.parseObject(meta.toString())));
            com.google.gson.JsonObject args=JsonParser.parseString("{agent:'all',days:0}").getAsJsonObject();assertEquals(1,JsonParser.parseString(query.search(args)).getAsJsonObject().getAsJsonArray("sessions").size());
            query.setPersistedOwnerAccess(meta->directory.allowsPersistedOwner("bob",JSON.parseObject(meta.toString())));assertEquals(0,JsonParser.parseString(query.search(args)).getAsJsonObject().getAsJsonArray("sessions").size());
        }
    }
    @Test public void referenceSurvivesRenameAndSessionRotation() {
        JSONObject row=row("a","STARWEAVE","starweave-instance","main","starweave/a");String ref=AgentDirectoryService.reference(row);
        row.put("display_name","Renamed");row.put("current_session",JSON.parseObject("{session_id:'new'}"));assertEquals(ref,AgentDirectoryService.reference(row));
    }
    @Test public void listToolHasNoArguments() {
        com.google.gson.JsonArray tools=CmdProxyMcpHttpHandler.tools(java.util.Collections.singleton("list_agents"));
        assertEquals(1,tools.size());com.google.gson.JsonObject schema=tools.get(0).getAsJsonObject().getAsJsonObject("inputSchema");assertEquals(0,schema.getAsJsonObject("properties").size());assertFalse(schema.get("additionalProperties").getAsBoolean());
    }
}
