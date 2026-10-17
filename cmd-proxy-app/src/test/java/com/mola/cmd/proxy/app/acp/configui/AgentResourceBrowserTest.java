package com.mola.cmd.proxy.app.acp.configui;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AgentResourceBrowserTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void listsProviderMcpFilesAndReadsOnlyGeneratedResourceIds() throws Exception {
        Path workspace = temporaryFolder.newFolder("mcp-workspace").toPath();
        Path config = workspace.resolve(".cmd-proxy").resolve("mcp.json");
        Files.createDirectories(config.getParent());
        Files.write(config, "{\"mcpServers\":{\"demo\":{}}}".getBytes(StandardCharsets.UTF_8));
        AcpRobotParam robot = robot("mcp-agent", workspace, "DEEPSEEK_HARNESS_ACP");

        AgentResourceBrowser browser = new AgentResourceBrowser();
        JSONObject tree = browser.tree(robot, AgentResourceBrowser.MCP);
        JSONObject node = findFile(tree.getJSONArray("nodes"), "mcp.json");

        assertNotNull(node);
        JSONObject result = browser.content(robot, AgentResourceBrowser.MCP,
                node.getString("id"));
        assertTrue(result.getBooleanValue("ok"));
        assertEquals("code", result.getJSONObject("data").getString("renderMode"));
        assertEquals("json", result.getJSONObject("data").getString("language"));

        try {
            browser.content(robot, AgentResourceBrowser.MCP, "r1/../outside.txt");
            fail("path traversal must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("outside")
                    || expected.getMessage().contains("invalid"));
        }
    }

    @Test
    public void listsWorkspaceSharedMcpForEveryAgentType() throws Exception {
        Path workspace = temporaryFolder.newFolder("all-provider-mcp").toPath();
        Path config = workspace.resolve(".cmd-proxy/mcp.json");
        Files.createDirectories(config.getParent());
        Files.write(config, "{\"mcpServers\":{}}".getBytes(StandardCharsets.UTF_8));
        String[] providers = {"KIRO_CLI", "OPENCODE", "CLAUDE_AGENT_ACP",
                "CODEX_ACP", "DEEPSEEK_HARNESS_ACP"};

        AgentResourceBrowser browser = new AgentResourceBrowser();
        for (String provider : providers) {
            JSONObject node = findFile(browser.tree(robot(provider, workspace, provider),
                    AgentResourceBrowser.MCP).getJSONArray("nodes"), "mcp.json");
            assertNotNull(provider + " must expose .cmd-proxy/mcp.json", node);
        }
    }

    @Test
    public void listsSharedAgentSkillsForCodexOpenCodeAndDeepSeekHarness()
            throws Exception {
        Path workspace = temporaryFolder.newFolder("shared-agent-skills").toPath();
        Path skill = workspace.resolve(".agents/skills/shared/SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.write(skill, "# Shared".getBytes(StandardCharsets.UTF_8));
        String[] providers = {"CODEX_ACP", "OPENCODE", "DEEPSEEK_HARNESS_ACP"};

        AgentResourceBrowser browser = new AgentResourceBrowser();
        for (String provider : providers) {
            JSONObject node = findFile(browser.tree(robot(provider, workspace, provider),
                    AgentResourceBrowser.SKILL).getJSONArray("nodes"), "SKILL.md");
            assertNotNull(provider + " must expose .agents/skills", node);
        }
    }

    @Test
    public void rendersSkillMarkdownAndTranslatesStructuredMemoryFiles() throws Exception {
        Path workspace = temporaryFolder.newFolder("resource-workspace").toPath();
        Path skill = workspace.resolve(".agents/skills/demo/SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.write(skill, "# Demo\n\nSkill body".getBytes(StandardCharsets.UTF_8));
        AcpRobotParam robot = robot("memory-agent", workspace, "DEEPSEEK_HARNESS_ACP");

        Path memoryBase = temporaryFolder.newFolder("memory-home").toPath();
        MemoryConfig memory = new MemoryConfig();
        memory.setBaseDir(memoryBase.toString());
        memory.setScope("robot");
        robot.setMemory(memory);
        Path memoryDir = memoryBase.resolve(
                com.mola.cmd.proxy.app.acp.common.PathUtils.sanitizePath(workspace.toString()))
                .resolve(com.mola.cmd.proxy.app.acp.common.PathUtils.sanitizePath(robot.getName()));
        Files.createDirectories(memoryDir);
        Files.write(memoryDir.resolve("MEMORY_INDEX.json"),
                "{\"memories\":[]}".getBytes(StandardCharsets.UTF_8));
        Files.write(memoryDir.resolve("DREAM_STATE.json"),
                "{\"lastDreamTime\":null}".getBytes(StandardCharsets.UTF_8));

        AgentResourceBrowser browser = new AgentResourceBrowser();
        JSONObject skillNode = findFile(browser.tree(robot, AgentResourceBrowser.SKILL)
                .getJSONArray("nodes"), "SKILL.md");
        assertNotNull(skillNode);
        JSONObject skillContent = browser.content(robot, AgentResourceBrowser.SKILL,
                skillNode.getString("id"));
        assertEquals("markdown", skillContent.getJSONObject("data").getString("renderMode"));

        JSONArray memoryNodes = browser.tree(robot, AgentResourceBrowser.MEMORY)
                .getJSONArray("nodes");
        JSONObject index = findFile(memoryNodes, "记忆索引");
        JSONObject dream = findFile(memoryNodes, "记忆整理状态");
        assertNotNull(index);
        assertNotNull(dream);
        JSONObject indexContent = browser.content(robot, AgentResourceBrowser.MEMORY,
                index.getString("id"));
        assertEquals("structured",
                indexContent.getJSONObject("data").getString("renderMode"));
        assertEquals("记忆索引",
                indexContent.getJSONObject("data").getString("displayName"));
    }

    @Test
    public void isolatesWorkspaceAndRobotMemoryTreesToTheConfiguredScope() throws Exception {
        Path workspace = temporaryFolder.newFolder("scoped-memory-workspace").toPath();
        Path memoryBase = temporaryFolder.newFolder("scoped-memory-home").toPath();
        Path workspaceMemory = memoryBase.resolve(
                com.mola.cmd.proxy.app.acp.common.PathUtils.sanitizePath(workspace.toString()));
        Path robotMemory = workspaceMemory.resolve("scope-agent");
        writeMemoryScope(workspaceMemory, "workspace_detail.md");
        writeMemoryScope(robotMemory, "robot_detail.md");

        AgentResourceBrowser browser = new AgentResourceBrowser();
        AcpRobotParam workspaceRobot = robot("scope-agent", workspace,
                "DEEPSEEK_HARNESS_ACP");
        MemoryConfig workspaceConfig = new MemoryConfig();
        workspaceConfig.setBaseDir(memoryBase.toString());
        workspaceConfig.setScope("workspace");
        workspaceRobot.setMemory(workspaceConfig);
        JSONArray workspaceNodes = browser.tree(workspaceRobot,
                AgentResourceBrowser.MEMORY).getJSONArray("nodes");
        assertNotNull(findFile(workspaceNodes, "workspace_detail.md"));
        assertNotNull(findFile(workspaceNodes, "MEMORY_INDEX.json"));
        assertNotNull(findFile(workspaceNodes, "DREAM_STATE.json"));
        assertNull(findFile(workspaceNodes, "robot_detail.md"));
        assertNull(findFile(workspaceNodes, "archived.md"));
        assertRejectedMemoryPath(browser, workspaceRobot,
                "r0/scope-agent/memories/robot_detail.md");
        assertRejectedMemoryPath(browser, workspaceRobot, "r0/archive/archived.md");
        assertRejectedMemoryPath(browser, workspaceRobot,
                "r0/memories/../scope-agent/memories/robot_detail.md");

        AcpRobotParam robotScoped = robot("scope-agent", workspace,
                "DEEPSEEK_HARNESS_ACP");
        MemoryConfig robotConfig = new MemoryConfig();
        robotConfig.setBaseDir(memoryBase.toString());
        robotConfig.setScope("robot");
        robotScoped.setMemory(robotConfig);
        JSONArray robotNodes = browser.tree(robotScoped,
                AgentResourceBrowser.MEMORY).getJSONArray("nodes");
        assertNotNull(findFile(robotNodes, "robot_detail.md"));
        assertNotNull(findFile(robotNodes, "MEMORY_INDEX.json"));
        assertNotNull(findFile(robotNodes, "DREAM_STATE.json"));
        assertNull(findFile(robotNodes, "workspace_detail.md"));
        assertNull(findFile(robotNodes, "archived.md"));
        assertRejectedMemoryPath(browser, robotScoped, "r0/archive/archived.md");
    }

    private void assertRejectedMemoryPath(AgentResourceBrowser browser,
                                          AcpRobotParam robot,
                                          String resourceId) {
        try {
            browser.content(robot, AgentResourceBrowser.MEMORY, resourceId);
            fail("inactive memory path must be rejected: " + resourceId);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("active scope"));
        }
    }

    private void writeMemoryScope(Path scopeRoot, String detailName) throws Exception {
        Files.createDirectories(scopeRoot.resolve("memories"));
        Files.createDirectories(scopeRoot.resolve("archive"));
        Files.write(scopeRoot.resolve("MEMORY_INDEX.json"),
                "{\"memories\":[]}".getBytes(StandardCharsets.UTF_8));
        Files.write(scopeRoot.resolve("DREAM_STATE.json"),
                "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(scopeRoot.resolve("memories").resolve(detailName),
                "# active".getBytes(StandardCharsets.UTF_8));
        Files.write(scopeRoot.resolve("archive").resolve("archived.md"),
                "# archived".getBytes(StandardCharsets.UTF_8));
    }

    @Test public void remoteSkillsAndMemoryAreBrowsableFromPersistentCenterEvenOffline() throws Exception {
        Path workspace = temporaryFolder.newFolder("remote-workspace").toPath(); AcpRobotParam robot = robot("remote-browser", workspace, "REMOTE_STARCORE_ACP");
        com.mola.cmd.proxy.app.acp.acpclient.agent.EmbeddedPiConfig pi = new com.mola.cmd.proxy.app.acp.acpclient.agent.EmbeddedPiConfig(); pi.setStateId("browser-" + java.util.UUID.randomUUID()); robot.setEmbeddedPi(pi);
        Path root = com.mola.cmd.proxy.app.acp.remote.RemoteResources.skillRoot(robot), file = root.resolve("demo/SKILL.md");
        Files.createDirectories(file.getParent()); Files.write(file, "---\nname: demo\ndescription: central skill\n---\nRemote body".getBytes(StandardCharsets.UTF_8));
        try {
            AgentResourceBrowser browser = new AgentResourceBrowser(); JSONObject node = findFile(browser.tree(robot, "skill").getJSONArray("nodes"), "SKILL.md"); assertNotNull(node);
            assertTrue(browser.content(robot, "skill", node.getString("id")).getBooleanValue("ok"));
            MemoryConfig memory = new MemoryConfig(); memory.setBaseDir(temporaryFolder.newFolder("remote-memory").toString()); memory.setScope("robot"); robot.setMemory(memory);
            Path memoryRoot = browser.roots(robot, "memory").get(0).path; Files.createDirectories(memoryRoot.resolve("memories")); Files.write(memoryRoot.resolve("memories/test.md"), "persistent memory".getBytes(StandardCharsets.UTF_8));
            assertNotNull(findFile(browser.tree(robot, "memory").getJSONArray("nodes"), "test.md"));
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(file.getParent()); Files.deleteIfExists(root); Files.deleteIfExists(root.getParent()); }
    }
    private AcpRobotParam robot(String name, Path workspace, String provider) {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName(name);
        robot.setWorkDir(workspace.toString());
        robot.setAgentProvider(provider);
        return robot;
    }

    private JSONObject findFile(JSONArray nodes, String name) {
        if (nodes == null) return null;
        for (int i = 0; i < nodes.size(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            if ("file".equals(node.getString("type"))
                    && (name.equals(node.getString("name"))
                    || name.equals(node.getString("fileName")))) return node;
            JSONObject child = findFile(node.getJSONArray("children"), name);
            if (child != null) return child;
        }
        return null;
    }
}
