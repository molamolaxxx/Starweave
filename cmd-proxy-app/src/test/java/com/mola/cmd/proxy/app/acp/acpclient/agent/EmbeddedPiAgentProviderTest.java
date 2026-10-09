package com.mola.cmd.proxy.app.acp.acpclient.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class EmbeddedPiAgentProviderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void routesProviderAndPreservesLegacyDefaults() {
        assertTrue(AgentProviderRouter.getInstance().resolve("EMBEDDED_PI_ACP") instanceof EmbeddedPiAgentProvider);
        assertEquals(AgentProviderType.KIRO_CLI, AgentProviderType.fromString(null));
        assertEquals("KIRO_CLI", new AcpRobotParam().getAgentProvider());
    }
    @Test public void linuxAndWindowsRuntimeSelection() throws Exception {
        assertEquals("linux-x64", EmbeddedPiRuntimeManager.platform("Linux", "amd64"));
        assertEquals("win-x64", EmbeddedPiRuntimeManager.platform("Windows 11", "x86_64"));
        try { EmbeddedPiRuntimeManager.platform("Darwin", "x64"); fail(); } catch (java.io.IOException expected) { }
        try { EmbeddedPiRuntimeManager.platform("Linux", "aarch64"); fail(); } catch (java.io.IOException expected) { }
    }
    @Test public void compactionAndUsageReachExistingProjection() {
        EmbeddedPiAgentProvider provider = new EmbeddedPiAgentProvider();
        for (String status : new String[]{"in_progress", "completed", "failed"}) {
            JsonObject event = JsonParser.parseString("{\"method\":\"session/update\",\"params\":{\"update\":{\"sessionUpdate\":\"tool_call_update\",\"_meta\":{\"starweaveCompaction\":{\"status\":\"" + status + "\"}}}}}").getAsJsonObject();
            AgentProvider.CompactionSignal signal = provider.detectCompactionSignal(event);
            assertEquals(status.equals("in_progress") ? AgentProvider.CompactionSignal.STARTED : status.equals("completed") ? AgentProvider.CompactionSignal.COMPLETED : AgentProvider.CompactionSignal.FAILED, signal);
        }
        JsonObject usage = JsonParser.parseString("{\"method\":\"session/update\",\"params\":{\"update\":{\"sessionUpdate\":\"usage_update\",\"used\":4000,\"size\":16000}}}").getAsJsonObject();
        assertEquals(25, provider.extractContextUsage(usage), .001);
        assertEquals("session/close", provider.getSessionCloseMethod()); assertTrue(provider.closeInputAfterSessionClose());
    }
    @Test public void offlineDependenciesUseSystemNodeWithoutExposingKeyInArguments() throws Exception {
        AcpRobotParam robot = new AcpRobotParam(); robot.setName("测试智能体"); robot.setModel("test-model");
        EmbeddedPiConfig config = new EmbeddedPiConfig(); config.setApiKey("isolated-key"); config.setStateId("test-agent"); robot.setEmbeddedPi(config);
        Path runtimeRoot = temporary.newFolder("runtime with spaces").toPath();
        EmbeddedPiAgentProvider provider = new EmbeddedPiAgentProvider(new EmbeddedPiRuntimeManager(runtimeRoot)); Map<String, String> env = new HashMap<>(System.getenv());
        Path systemNode = EmbeddedPiRuntimeManager.systemNode(env);
        provider.prepareLaunch(robot, env);
        String command = provider.getCommand(robot, env); assertTrue(Files.isRegularFile(java.nio.file.Paths.get(command)));
        assertEquals(systemNode.toString(), command);
        assertFalse(java.nio.file.Paths.get(command).startsWith(runtimeRoot));
        assertFalse(provider.getArgs(robot, env)[0].contains("isolated-key"));
        assertEquals("isolated-key", JsonParser.parseString(env.get("STARWEAVE_PI_CONFIG")).getAsJsonObject().get("apiKey").getAsString());
        Process process = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
        Path runtime = java.nio.file.Paths.get(provider.getArgs(robot, env)[0]).getParent().getParent();
        assertFalse(Files.exists(runtime.resolve("node"))); assertFalse(Files.exists(runtime.resolve("node.exe")));
        Path marker = runtime.resolve(".complete");
        long modified = Files.getLastModifiedTime(marker).toMillis(); provider.prepareLaunch(robot, env);
        assertEquals(modified, Files.getLastModifiedTime(marker).toMillis());
    }
    @Test public void missingNodeAndUnsupportedVersionsAreRejected() throws Exception {
        try { EmbeddedPiRuntimeManager.findSystemNode(new HashMap<>(), false); fail(); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("PATH")); }
        for (String version : new String[]{"v18.20.0", "v22.18.0", "invalid"}) {
            try { EmbeddedPiRuntimeManager.validateNodeVersion(version); fail(version); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("22.19.0")); }
        }
        EmbeddedPiRuntimeManager.validateNodeVersion("v22.19.0"); EmbeddedPiRuntimeManager.validateNodeVersion("v24.21.0");
    }
    @Test public void windowsNodePathSupportsSpacesAndCaseInsensitivePathKey() throws Exception {
        Path folder = temporary.newFolder("Node Program Files").toPath();
        Path node = Files.createFile(folder.resolve("node.exe"));
        Map<String, String> env = new HashMap<>(); env.put("Path", "\"" + folder + "\"");
        assertEquals(node.toAbsolutePath(), EmbeddedPiRuntimeManager.findSystemNode(env, true));
    }
    @Test public void invalidBudgetsAndStatePathsFailBeforeLaunch() {
        EmbeddedPiConfig config = new EmbeddedPiConfig(); config.setApiKey("test"); config.setContextWindow(4096);
        try { config.validate("model"); fail(); } catch (IllegalArgumentException expected) { }
        config.setContextWindow(128000); config.setStateId("../escape");
        try { config.validate("model"); fail(); } catch (IllegalArgumentException expected) { }
    }
}
