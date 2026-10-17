package com.mola.cmd.proxy.app.acp.ability;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Explicit extraction sources. User roots and links outside the workspace are excluded. */
final class WorkspaceSkillLoader {
    private WorkspaceSkillLoader() {}

    static Map<String, String> load(AgentProvider provider, String workspacePath, AcpRobotParam robot) throws IOException {
        Map<String, String> skills = new TreeMap<>();
        if (workspacePath == null || workspacePath.trim().isEmpty()) return skills;
        Path workspace = Paths.get(workspacePath).toAbsolutePath().normalize();
        if (!Files.isDirectory(workspace)) return skills;
        Path realWorkspace = workspace.toRealPath();
        Set<Path> userRoots = new HashSet<>();
        for (Path path : provider.getSkillPaths(null, robot)) {
            userRoots.add(realOrNormalized(path));
        }
        for (Path path : provider.getSkillPaths(workspacePath, robot)) {
            Path root = path.toAbsolutePath().normalize();
            Path realRoot = realOrNormalized(root);
            if (!root.startsWith(workspace) || !realRoot.startsWith(realWorkspace)
                    || userRoots.contains(realRoot) || !Files.isDirectory(root)) continue;
            boolean commands = "commands".equals(root.getFileName().toString());
            try (Stream<Path> paths = Files.walk(root)) {
                java.util.Iterator<Path> entries = paths.sorted().iterator();
                while (entries.hasNext()) {
                    Path file = entries.next();
                    String name = file.getFileName().toString();
                    if (!("SKILL.md".equals(name) || commands && name.endsWith(".md")) || !Files.isRegularFile(file)) continue;
                    Path realFile = file.toRealPath();
                    if (!realFile.startsWith(realWorkspace) || userRoots.stream().anyMatch(realFile::startsWith)) continue;
                    skills.put(workspace.relativize(file).toString().replace('\\', '/'),
                            new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                }
            }
        }
        return skills;
    }

    private static Path realOrNormalized(Path path) throws IOException {
        return Files.exists(path) ? path.toRealPath() : path.toAbsolutePath().normalize();
    }
}
