package com.mola.starweave.agent;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Host-owned upper bound; central permission grants cannot override it. */
public final class AgentToolPolicy {
    public static final AgentToolPolicy UNRESTRICTED = new AgentToolPolicy(null, null, true, true);
    private final Path workspace, skills;
    private final boolean write, shell;
    private AgentToolPolicy(Path workspace, Path skills, boolean write, boolean shell) { this.workspace = workspace; this.skills = skills; this.write = write; this.shell = shell; }
    public static AgentToolPolicy workspace(Path workspace, Path skills, boolean write, boolean shell) { return new AgentToolPolicy(workspace.toAbsolutePath().normalize(), skills.toAbsolutePath().normalize(), write, shell); }
    private Map<String, Object> jvmBindings;
    /** Full in-process execution; only the host can grant this capability. */
    public AgentToolPolicy withJvmExecution(Map<String, Object> bindings) {
        AgentToolPolicy copy = new AgentToolPolicy(workspace, skills, write, shell);
        copy.jvmBindings = Collections.unmodifiableMap(new HashMap<>(bindings));
        return copy;
    }
    boolean canExecuteJvm() { return jvmBindings != null; }
    Map<String, Object> jvmBindings() { return jvmBindings == null ? Collections.emptyMap() : jvmBindings; }
    boolean canWrite() { return write; }
    boolean canShell() { return shell; }
    void check(Path target, boolean mutation) throws IOException {
        if (mutation && !write) throw new IOException("Host application disabled file writes");
        if (workspace == null) return;
        Path actual = canonical(target);
        if (!actual.startsWith(canonical(workspace)) && (mutation || !actual.startsWith(canonical(skills)) && !actual.startsWith(canonical(skills.getParent().resolve("uploads"))))) throw new IOException("Path outside host-approved workspace");
    }
    void checkShell() throws IOException { if (!shell) throw new IOException("Host application disabled shell execution"); }
    private static Path canonical(Path path) throws IOException {
        Path parent = path; while (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) parent = parent.getParent();
        if (parent == null) throw new IOException("Cannot resolve path");
        return parent.toRealPath().resolve(parent.relativize(path)).normalize();
    }
}
