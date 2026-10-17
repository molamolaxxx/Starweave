package com.mola.starweave.agent;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.Assert.*;

public class AgentToolPolicyTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void readOnlyWorkspaceRejectsMutationShellAndOutsidePaths() throws Exception {
        Path workspace = temp.newFolder("workspace").toPath(), skills = temp.newFolder("skills").toPath();
        AgentToolPolicy policy = AgentToolPolicy.workspace(workspace, skills, false, false);
        policy.check(workspace.resolve("file.txt"), false); policy.check(skills.resolve("example/SKILL.md"), false);
        reject(() -> policy.check(workspace.resolve("file.txt"), true)); reject(policy::checkShell); reject(() -> policy.check(workspace.resolve("../other.txt"), false));
    }
    @Test public void rejectsSymlinksEscapingWorkspace() throws Exception {
        Path workspace = temp.newFolder("workspace").toPath(), outside = temp.newFolder("outside").toPath();
        try { Files.createSymbolicLink(workspace.resolve("link"), outside); } catch (UnsupportedOperationException | IOException e) { Assume.assumeNoException(e); }
        AgentToolPolicy policy = AgentToolPolicy.workspace(workspace, workspace.resolve("skills"), true, false);
        reject(() -> policy.check(workspace.resolve("link/secret.txt"), false)); reject(() -> policy.check(workspace.resolve("link/new.txt"), true));
    }
    @Test public void remoteMemoryAllowsHostApprovedDetailsButProtectsGeneratedIndex() throws Exception {
        Path workspace = temp.newFolder("work").toPath(), managed = temp.newFolder("managed").toPath();
        AgentToolPolicy policy = AgentToolPolicy.workspace(workspace, workspace.resolve("skills"), true, false).withManagedResources(managed);
        policy.check(workspace.resolve(".agents/skills/demo/SKILL.md"), true);
        policy.check(managed.resolve("memory/example.md"), true); policy.check(managed.resolve("memory/index.json"), false);
        reject(() -> policy.check(managed.resolve("memory/index.json"), true)); reject(() -> policy.check(managed.resolve("../identity"), false));
        AgentToolPolicy readOnly = AgentToolPolicy.workspace(workspace, workspace.resolve("skills"), false, false).withManagedResources(managed);
        reject(() -> readOnly.check(managed.resolve("memory/example.md"), true));
    }
    private static void reject(Action action) throws Exception { try { action.run(); fail("operation should have been rejected"); } catch (IOException expected) { } }
    interface Action { void run() throws Exception; }
}
