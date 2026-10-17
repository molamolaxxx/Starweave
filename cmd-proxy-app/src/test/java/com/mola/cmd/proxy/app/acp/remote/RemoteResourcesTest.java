package com.mola.cmd.proxy.app.acp.remote;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.agent.EmbeddedPiConfig;
import com.mola.cmd.proxy.app.acp.memory.*;
import com.mola.cmd.proxy.app.acp.memory.model.*;
import com.mola.starweave.sdk.sync.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class RemoteResourcesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private AcpRobotParam robot; private RemoteAcpManager manager; private RemoteResources server; private ResourceReplica client; private MemoryFileStore memory;
    @Before public void setup() throws Exception {
        Path config = temp.newFile("config.json").toPath(); Files.write(config, "{\"robots\":[]}".getBytes(StandardCharsets.UTF_8)); manager = new RemoteAcpManager(config, (a,b) -> {});
        robot = new AcpRobotParam(); robot.setName("remote"); robot.setAgentProvider("REMOTE_STARCORE_ACP"); robot.setWorkDir("/remote/application");
        EmbeddedPiConfig pi = new EmbeddedPiConfig(); pi.setStateId("resource-test"); robot.setEmbeddedPi(pi);
        MemoryConfig memoryConfig = new MemoryConfig(); memoryConfig.setBaseDir(temp.newFolder("memory").toString()); memoryConfig.setScope("robot"); robot.setMemory(memoryConfig);
        server = new RemoteResources(robot); client = new ResourceReplica(temp.newFolder("sdk").toPath(), temp.newFolder("workspace").toPath()); client.bind(server.resourceSetId());
        memory = new MemoryFileStore(memoryConfig.getBaseDir(), robot.getName());
    }
    @After public void stop() { if (manager != null) manager.close(); }
    private void exchange() throws Exception { JsonObject request = client.request(), reply = server.exchange(request); reply.add("requestId", request.get("requestId")); assertTrue(client.apply(reply)); }
    private void local(String path, String text) throws Exception { ResourceFiles.write(path.startsWith("skills/") ? client.skillRoot().resolve(path.substring(7)) : client.root().resolve(path), text.getBytes(StandardCharsets.UTF_8)); }
    @Test public void memoryEditsCreateSummaryAndIndexThenRenameAndDeleteByStableId() throws Exception {
        local("memory/a.md", "---\nid: m-one\ntype: project\ntitle: Test\n---\n\nnew detail"); exchange();
        MemoryEntry entry = memory.loadIndex(robot.getWorkDir()).getMemories().get(0); assertEquals("m-one", entry.getId()); assertEquals("new detail", entry.getSummary());
        local("memory/a.md", "---\nid: m-one\ntype: project\ntitle: Test\n---\n\nchanged body"); exchange();
        assertEquals("changed body", memory.loadIndex(robot.getWorkDir()).getMemories().get(0).getSummary());
        Files.move(client.root().resolve("memory/a.md"), client.root().resolve("memory/renamed.md")); exchange();
        assertEquals(1, memory.loadIndex(robot.getWorkDir()).getMemories().size()); assertEquals("m-one", memory.loadIndex(robot.getWorkDir()).getMemories().get(0).getId());
        Files.delete(client.root().resolve("memory/renamed.md")); exchange(); assertEquals(0, memory.loadIndex(robot.getWorkDir()).getMemories().size());
    }
    @Test public void duplicateIdsAndGeneratedIndexUploadsAreRejectedBeforeAnyWrites() throws Exception {
        local("memory/a.md", "---\nid: duplicate\n---\n\none"); local("memory/b.md", "---\nid: duplicate\n---\n\ntwo");
        try { server.exchange(client.request()); fail(); } catch (IOException expected) { }
        assertEquals(0, memory.loadIndex(robot.getWorkDir()).getMemories().size());
        JsonObject request = new JsonObject(), changes = new JsonObject(); JsonObject change = ResourceFiles.entry("{}".getBytes(StandardCharsets.UTF_8)); change.addProperty("base", ""); changes.add("memory/index.json", change); request.add("changes", changes);
        try { server.exchange(request); fail(); } catch (IOException expected) { }
    }
    @Test public void editsMadeDirectlyOnCenterUpdateReplicaAndGeneratedSummary() throws Exception {
        local("memory/a.md", "base"); exchange();
        Path file = Paths.get(memory.loadIndex(robot.getWorkDir()).getMemories().get(0).getFile()); ResourceFiles.write(file, "changed on center".getBytes(StandardCharsets.UTF_8)); exchange();
        assertEquals("changed on center", memory.loadIndex(robot.getWorkDir()).getMemories().get(0).getSummary());
        assertEquals("changed on center", new String(Files.readAllBytes(client.root().resolve("memory/a.md")), StandardCharsets.UTF_8));
    }
    @Test public void manualChoiceKeepsBothConflictCopiesAndPropagatesChosenVersion() throws Exception {
        local("skills/demo/SKILL.md", "base"); exchange();
        ResourceFiles.write(RemoteResources.skillRoot(robot).resolve("demo/SKILL.md"), "central".getBytes(StandardCharsets.UTF_8)); local("skills/demo/SKILL.md", "local"); exchange();
        assertEquals(1, client.conflictCount()); JsonObject item = server.conflicts().get(0).getAsJsonObject(); String id = item.get("id").getAsString();
        JsonObject detail = server.conflict(id); server.resolveConflict(id, "starweave", detail.get("head").getAsString()); exchange();
        assertEquals(0, client.conflictCount()); assertEquals("central", new String(Files.readAllBytes(client.skillRoot().resolve("demo/SKILL.md")), StandardCharsets.UTF_8));
        assertEquals(0, server.conflicts().size());
    }
    @Test public void containerChoiceUpdatesMemoryIndexAndCannotOverwriteNewerCenterVersion() throws Exception {
        local("memory/a.md", "base"); exchange();
        Path central = Paths.get(memory.loadIndex(robot.getWorkDir()).getMemories().get(0).getFile()); ResourceFiles.write(central, "center".getBytes(StandardCharsets.UTF_8)); local("memory/a.md", "container"); exchange();
        String id = server.conflicts().get(0).getAsJsonObject().get("id").getAsString(); JsonObject detail = server.conflict(id);
        try { server.resolveConflict(id, "container", "outdated"); fail(); } catch (IOException expected) { }
        server.resolveConflict(id, "container", detail.get("head").getAsString()); exchange(); assertEquals(0, client.conflictCount());
        assertEquals("container", memory.loadIndex(robot.getWorkDir()).getMemories().get(0).getSummary());
    }
}
