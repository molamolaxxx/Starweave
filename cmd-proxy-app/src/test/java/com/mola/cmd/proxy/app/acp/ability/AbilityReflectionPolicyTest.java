package com.mola.cmd.proxy.app.acp.ability;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.agent.EmbeddedPiConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.Assert.*;

public class AbilityReflectionPolicyTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private AgentProvider provider(Path userRoot) {
        return new AgentProvider() {
            public String getCommand() { return "unused"; }
            public String[] getArgs() { return new String[0]; }
            public String getName() { return "test"; }
            public List<Path> getMcpConfigPaths(String workspace) { return Collections.emptyList(); }
            public List<Path> getSkillPaths(String workspace, AcpRobotParam robot) {
                List<Path> roots = new ArrayList<>(); roots.add(userRoot);
                if (workspace != null) {
                    roots.add(java.nio.file.Paths.get(workspace, ".agents/skills"));
                    roots.add(java.nio.file.Paths.get(workspace, ".opencode/skills"));
                }
                return roots;
            }
        };
    }

    private Path skill(Path root, String name, String content) throws Exception {
        Path file = root.resolve(name).resolve("SKILL.md");
        Files.createDirectories(file.getParent()); Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test public void collectsOnlyWorkspaceSkillsAcrossProviderRootsAndIgnoresUserChanges() throws Exception {
        Path workspace = temporary.newFolder("workspace").toPath();
        Path user = temporary.newFolder("user").toPath();
        Path global = skill(user, "generic", "GENERIC-USER-SKILL");
        skill(workspace.resolve(".agents/skills"), "project", "project customization");
        skill(workspace.resolve(".opencode/skills"), "nested/domain", "domain specialization");
        AgentProvider provider = provider(user);
        Map<String, String> result = WorkspaceSkillLoader.load(provider, workspace.toString(), new AcpRobotParam());
        assertEquals(2, result.size()); assertFalse(result.containsValue("GENERIC-USER-SKILL"));
        Files.write(global, "new global skill".getBytes(StandardCharsets.UTF_8));
        assertEquals(result, WorkspaceSkillLoader.load(provider, workspace.toString(), new AcpRobotParam()));
        skill(workspace.resolve(".agents/skills"), "project", "updated customization");
        assertNotEquals(result, WorkspaceSkillLoader.load(provider, workspace.toString(), new AcpRobotParam()));
    }

    @Test public void excludesGlobalRootsInsideWorkspaceAndExternalSymlinks() throws Exception {
        Path workspace = temporary.newFolder("home-workspace").toPath();
        Path user = workspace.resolve(".agents/skills");
        skill(user, "generic", "user-only");
        Path local = workspace.resolve(".opencode/skills");
        Path external = skill(temporary.newFolder("outside").toPath(), "external", "external");
        Files.createDirectories(local.resolve("linked"));
        try { Files.createSymbolicLink(local.resolve("linked/SKILL.md"), external); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException e) { org.junit.Assume.assumeNoException(e); }
        assertTrue(WorkspaceSkillLoader.load(provider(user), workspace.toString(), new AcpRobotParam()).isEmpty());
    }

    @Test public void promptUsesExplicitEvidenceAndDoesNotRequireGenericCapabilities() {
        String prompt = AbilityReflectionPromptTemplate.build(Arrays.asList("business-tool"), "domain memory",
                Collections.singletonMap(".agents/skills/project/SKILL.md", "project-specific-content"), "custom agent role");
        assertTrue(prompt.contains("project-specific-content")); assertTrue(prompt.contains("custom agent role"));
        assertTrue(prompt.contains("domain memory")); assertTrue(prompt.contains("business-tool"));
        assertTrue(prompt.contains("用户级、全局和插件自带的通用 Skills 不参与能力提取"));
        assertTrue(prompt.contains("即使引擎自动加载也必须忽略"));
        assertTrue(prompt.contains("不要凑数")); assertFalse(prompt.contains("已自动加载了 skills"));
    }

    @Test public void extractionUsesSelectedModelWithoutChangingMainRuntimeConfiguration() {
        AcpRobotParam original = new AcpRobotParam(); original.setModel("main-large");
        original.setAgentProvider("JAVA_PI_ACP"); original.setApiKey("test-key"); original.setProviderVersion("1.2.3");
        EmbeddedPiConfig pi = new EmbeddedPiConfig(); pi.setBaseUrl("http://example.invalid"); original.setEmbeddedPi(pi);
        original.setProxyEnabled(true); original.setHttpProxy("http://localhost:9999");
        AcpRobotParam extraction = AbilityReflectionAcpClient.buildEffectiveParam(original, "gpt-6-luna");
        assertEquals("gpt-6-luna", extraction.getModel()); assertEquals("main-large", original.getModel());
        assertEquals(original.getAgentProvider(), extraction.getAgentProvider());
        assertEquals(original.getApiKey(), extraction.getApiKey()); assertEquals(original.getProviderVersion(), extraction.getProviderVersion());
        assertEquals(original.getHttpProxy(), extraction.getHttpProxy()); assertTrue(extraction.isProxyEnabled());
        assertNotSame(original.getEmbeddedPi(), extraction.getEmbeddedPi());
        assertEquals(pi.getBaseUrl(), extraction.getEmbeddedPi().getBaseUrl());
    }

    @Test(expected=IllegalArgumentException.class) public void extractionCannotSilentlyInheritMainModel() {
        AbilityReflectionAcpClient.buildEffectiveParam(new AcpRobotParam(), "");
    }
    private static class ReflectionHarness extends AbilityReflectionService {
        final Path output;
        String selectedModel = "gpt-6-luna";
        String actualModel;
        int calls;
        ReflectionHarness(AcpRobotParam robot, Path output) {
            super(robot.getName(), robot.getWorkDir(), robot, 30, Collections.emptyList(), null);
            this.output = output;
        }
        @Override Path getProjectDir() { return output; }
        @Override String resolveSmallModel() { return selectedModel; }
        @Override String runReflection(String prompt, String model) {
            calls++; actualModel = model;
            assertTrue(prompt.contains("project-specific-content"));
            assertFalse(prompt.contains("GENERIC-USER-SKILL"));
            return "# Custom ability\nProject specialization";
        }
    }

    @Test public void migratesOldCardOnceThenTracksOnlyProjectEvidence() throws Exception {
        Path workspace = temporary.newFolder("migration-workspace").toPath();
        Path userHome = temporary.newFolder("codex-home").toPath();
        skill(userHome.resolve("skills"), "generic", "GENERIC-USER-SKILL");
        Path projectSkill = skill(workspace.resolve(".agents/skills"), "project", "project-specific-content");
        Path output = temporary.newFolder("ability-output").toPath();
        Files.write(output.resolve("ability.md"), "old generic card".getBytes(StandardCharsets.UTF_8));
        Files.write(output.resolve("snapshot.json"), "{\"skillsHash\":\"old\"}".getBytes(StandardCharsets.UTF_8));
        AcpRobotParam robot = new AcpRobotParam(); robot.setName("custom"); robot.setAgentProvider("CODEX_ACP");
        robot.setWorkDir(workspace.toString()); robot.setCodexHome(userHome.toString());
        ReflectionHarness service = new ReflectionHarness(robot, output);
        service.checkAndReflect(); assertEquals(1, service.calls); assertEquals("gpt-6-luna", service.actualModel);
        assertTrue(new String(Files.readAllBytes(output.resolve("ability.md")), StandardCharsets.UTF_8).contains("Custom ability"));
        service.checkAndReflect(); assertEquals(1, service.calls);
        skill(userHome.resolve("skills"), "generic", "updated global skill");
        service.checkAndReflect(); assertEquals(1, service.calls);
        Files.write(projectSkill, "updated project-specific-content".getBytes(StandardCharsets.UTF_8));
        service.checkAndReflect(); assertEquals(2, service.calls);
        robot.setSignature("new custom role"); service.checkAndReflect(); assertEquals(3, service.calls);
    }

    @Test public void noSmallModelRetainsExistingCardAndDoesNotMarkMigrationComplete() throws Exception {
        Path output = temporary.newFolder("no-small-model-output").toPath();
        Files.write(output.resolve("ability.md"), "existing card".getBytes(StandardCharsets.UTF_8));
        String snapshot = "{\"skillsHash\":\"old\"}";
        Files.write(output.resolve("snapshot.json"), snapshot.getBytes(StandardCharsets.UTF_8));
        AcpRobotParam robot = new AcpRobotParam(); robot.setWorkDir(temporary.newFolder("empty-workspace").getAbsolutePath());
        ReflectionHarness service = new ReflectionHarness(robot, output); service.selectedModel = null;
        service.checkAndReflect(); assertEquals(0, service.calls);
        assertEquals("existing card", new String(Files.readAllBytes(output.resolve("ability.md")), StandardCharsets.UTF_8));
        assertEquals(snapshot, new String(Files.readAllBytes(output.resolve("snapshot.json")), StandardCharsets.UTF_8));
    }

}
