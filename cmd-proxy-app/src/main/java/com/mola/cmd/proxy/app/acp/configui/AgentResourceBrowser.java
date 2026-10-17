package com.mola.cmd.proxy.app.acp.configui;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProviderRouter;
import com.mola.cmd.proxy.app.acp.common.PathUtils;
import com.mola.cmd.proxy.app.acp.filepreview.TextFilePreviewReader;
import com.mola.cmd.proxy.app.acp.filepreview.TextFilePreviewResult;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Read-only, allowlisted resource browser used by the ConfigUI agent cards. */
final class AgentResourceBrowser {
    static final String MCP = "mcp";
    static final String SKILL = "skill";
    static final String MEMORY = "memory";
    private static final int MAX_NODES = 3000;
    private static final int MAX_DEPTH = 16;

    JSONObject tree(AcpRobotParam robot, String kind) {
        requireKind(kind);
        JSONObject result = new JSONObject(true);
        result.put("ok", true);
        result.put("kind", kind);
        result.put("robot", robot.getName());
        JSONArray nodes = new JSONArray();
        List<ResourceRoot> roots = roots(robot, kind);
        int[] count = {0};
        for (int i = 0; i < roots.size() && count[0] < MAX_NODES; i++) {
            ResourceRoot root = roots.get(i);
            if (!existsWithoutFollowingLinks(root.path)) continue;
            JSONObject node = root.file
                    ? fileNode(root.path, "r" + i, kind, root.label)
                    : directoryNode(root.path, root.path, "r" + i, kind,
                    root.label, 0, count);
            if (node != null) nodes.add(node);
        }
        result.put("nodes", nodes);
        result.put("truncated", count[0] >= MAX_NODES);
        return result;
    }

    JSONObject content(AcpRobotParam robot, String kind, String resourceId) {
        requireKind(kind);
        if (resourceId == null || !resourceId.matches("r\\d+(?:/[^\\r\\n]*)?")) {
            throw new IllegalArgumentException("invalid resource id");
        }
        int slash = resourceId.indexOf('/');
        String rootToken = slash < 0 ? resourceId : resourceId.substring(0, slash);
        int rootIndex;
        try {
            rootIndex = Integer.parseInt(rootToken.substring(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid resource id");
        }
        List<ResourceRoot> roots = roots(robot, kind);
        if (rootIndex < 0 || rootIndex >= roots.size()) {
            throw new IllegalArgumentException("resource root no longer exists");
        }
        ResourceRoot root = roots.get(rootIndex);
        String relative = slash < 0 ? "" : resourceId.substring(slash + 1);
        if (root.file && !relative.isEmpty()) {
            throw new IllegalArgumentException("invalid file resource id");
        }
        if (MEMORY.equals(kind) && !isActiveMemoryRelative(relative)) {
            throw new IllegalArgumentException("memory resource is outside the active scope");
        }
        Path target = root.file ? root.path : root.path.resolve(relative).normalize();
        Path readRoot = root.file ? root.path.toAbsolutePath().normalize().getParent()
                : root.path.toAbsolutePath().normalize();
        if (readRoot == null || !isWithin(target.toAbsolutePath().normalize(), readRoot)) {
            throw new IllegalArgumentException("resource path is outside its root");
        }
        try {
            Path realRoot = readRoot.toRealPath();
            Path realTarget = target.toRealPath();
            if (!isWithin(realTarget, realRoot)) {
                throw new IllegalArgumentException("resource path is outside its root");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("resource file does not exist");
        }
        TextFilePreviewResult preview = TextFilePreviewReader.read(
                resourceId, readRoot.toString(), target.toString(),
                TextFilePreviewReader.HARD_MAX_BYTES, "UTF-8");
        JSONObject result = new JSONObject(true);
        result.put("ok", preview.isAccepted());
        result.put("code", preview.getCode());
        result.put("message", preview.getMessage());
        result.put("retryable", preview.isRetryable());
        if (preview.getData() != null) result.put("data", preview.getData());
        if (!preview.isAccepted()) return result;
        JSONObject data = result.getJSONObject("data");
        String fileName = data.getString("fileName");
        data.put("renderMode", renderMode(kind, fileName));
        data.put("language", language(fileName));
        data.put("displayName", translatedName(fileName));
        return result;
    }

    List<ResourceRoot> roots(AcpRobotParam robot, String kind) {
        if (robot == null) throw new IllegalArgumentException("agent not found");
        String workspace = robot.getWorkDir();
        if (workspace == null || workspace.trim().isEmpty()) {
            throw new IllegalArgumentException("agent workspace is empty");
        }
        if (MCP.equals(kind)) return mcpRoots(robot, workspace);
        if (SKILL.equals(kind)) return skillRoots(robot, workspace);
        return memoryRoots(robot, workspace);
    }

    private List<ResourceRoot> mcpRoots(AcpRobotParam robot, String workspace) {
        AgentProvider provider = AgentProviderRouter.getInstance()
                .resolve(robot.getAgentProvider());
        List<ResourceRoot> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Path path : provider.getMcpConfigPaths(workspace, robot)) {
            Path normalized = path.toAbsolutePath().normalize();
            if (seen.add(normalized.toString())) {
                result.add(new ResourceRoot(normalized,
                        sourceLabel(normalized, workspace, "MCP") + " · "
                                + compactPath(normalized), true));
            }
        }
        return result;
    }

    private List<ResourceRoot> skillRoots(AcpRobotParam robot, String workspace) {
        AgentProvider provider = AgentProviderRouter.getInstance()
                .resolve(robot.getAgentProvider());
        List<ResourceRoot> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Path path : provider.getSkillPaths(workspace, robot)) {
            Path normalized = path.toAbsolutePath().normalize();
            if (seen.add(normalized.toString())) {
                result.add(new ResourceRoot(normalized,
                        sourceLabel(normalized, workspace, "Skills") + " · "
                                + compactPath(normalized), false));
            }
        }
        return result;
    }

    private List<ResourceRoot> memoryRoots(AcpRobotParam robot, String workspace) {
        MemoryConfig memory = robot.getMemory();
        String baseDir = memory == null || isBlank(memory.getBaseDir())
                ? com.mola.cmd.proxy.app.utils.CmdProxyHome.pathOf("memory")
                : expandHome(memory.getBaseDir().trim());
        Path path = Paths.get(baseDir, PathUtils.sanitizePath(workspace));
        if (memory != null && memory.isRobotScope()) {
            path = path.resolve(PathUtils.sanitizePath(robot.getName()));
        }
        return Collections.singletonList(new ResourceRoot(path.toAbsolutePath().normalize(),
                "当前生效记忆 · " + compactPath(path.toAbsolutePath().normalize()), false));
    }

    private JSONObject directoryNode(Path root, Path directory, String id, String kind,
                                     String label, int depth, int[] count) {
        if (depth > MAX_DEPTH || count[0] >= MAX_NODES
                || Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return null;
        count[0]++;
        JSONObject node = baseNode(id, label, "directory", kind);
        JSONArray children = new JSONArray();
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                if (MEMORY.equals(kind) && depth == 0 && !isActiveMemoryEntry(entry)) {
                    continue;
                }
                entries.add(entry);
            }
        } catch (IOException | SecurityException e) {
            node.put("unreadable", true);
            node.put("children", children);
            return node;
        }
        entries.sort(Comparator
                .comparing((Path p) -> !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        for (Path entry : entries) {
            if (count[0] >= MAX_NODES || Files.isSymbolicLink(entry)) break;
            String relative = root.relativize(entry).toString().replace('\\', '/');
            String childId = id.substring(0, id.indexOf('/') < 0 ? id.length() : id.indexOf('/'))
                    + "/" + relative;
            JSONObject child;
            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                child = directoryNode(root, entry, childId, kind,
                        translatedName(entry.getFileName().toString()), depth + 1, count);
            } else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                count[0]++;
                child = fileNode(entry, childId, kind,
                        translatedName(entry.getFileName().toString()));
            } else {
                child = null;
            }
            if (child != null) children.add(child);
        }
        node.put("children", children);
        return node;
    }

    private JSONObject fileNode(Path path, String id, String kind, String label) {
        JSONObject node = baseNode(id, label, "file", kind);
        node.put("fileName", path.getFileName().toString());
        node.put("language", language(path.getFileName().toString()));
        node.put("renderMode", renderMode(kind, path.getFileName().toString()));
        return node;
    }

    private JSONObject baseNode(String id, String label, String type, String kind) {
        JSONObject node = new JSONObject(true);
        node.put("id", id);
        node.put("name", label);
        node.put("type", type);
        node.put("kind", kind);
        return node;
    }

    private static String renderMode(String kind, String fileName) {
        if (MEMORY.equals(kind) && isStructuredMemory(fileName)) return "structured";
        if (SKILL.equals(kind) && isMarkdown(fileName)) return "markdown";
        if (MEMORY.equals(kind) && isMarkdown(fileName)) return "markdown";
        return "code";
    }

    private static String language(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".toml")) return "toml";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "markdown";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "yaml";
        if (lower.endsWith(".xml") || lower.endsWith(".html")) return "markup";
        if (lower.endsWith(".js")) return "javascript";
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".kt")) return "kotlin";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".sh")) return "shell";
        return "text";
    }

    private static boolean isMarkdown(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    private static boolean isStructuredMemory(String name) {
        return "DREAM_STATE.json".equalsIgnoreCase(name)
                || "MEMORY_INDEX.json".equalsIgnoreCase(name);
    }

    private static boolean isActiveMemoryEntry(Path entry) {
        String name = entry.getFileName().toString();
        return "memories".equals(name)
                || "DREAM_STATE.json".equalsIgnoreCase(name)
                || "MEMORY_INDEX.json".equalsIgnoreCase(name);
    }

    private static boolean isActiveMemoryRelative(String relative) {
        if (relative == null || relative.isEmpty()) return true;
        String normalized = relative.replace('\\', '/');
        for (String segment : normalized.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) return false;
        }
        return "DREAM_STATE.json".equalsIgnoreCase(relative)
                || "MEMORY_INDEX.json".equalsIgnoreCase(relative)
                || normalized.startsWith("memories/");
    }

    private static String translatedName(String name) {
        if ("DREAM_STATE.json".equalsIgnoreCase(name)) return "记忆整理状态";
        if ("MEMORY_INDEX.json".equalsIgnoreCase(name)) return "记忆索引";
        return name;
    }

    private static String sourceLabel(Path path, String workspace, String suffix) {
        Path work = Paths.get(workspace).toAbsolutePath().normalize();
        return path.startsWith(work) ? "工作区 " + suffix : "用户级 " + suffix;
    }

    private static String compactPath(Path path) {
        String home = System.getProperty("user.home");
        String value = path.toString();
        if (home != null && value.startsWith(home)) return "~" + value.substring(home.length());
        return value;
    }

    private static String expandHome(String value) {
        if ("~".equals(value)) return System.getProperty("user.home");
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            return Paths.get(System.getProperty("user.home"), value.substring(2)).toString();
        }
        return value;
    }

    private static boolean existsWithoutFollowingLinks(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
    }

    private static boolean isWithin(Path path, Path root) {
        if (java.io.File.separatorChar != '\\') return path.startsWith(root);
        String value = path.toString().toLowerCase(Locale.ROOT);
        String expected = root.toString().toLowerCase(Locale.ROOT);
        return value.equals(expected)
                || value.startsWith(expected + java.io.File.separator);
    }

    private static void requireKind(String kind) {
        if (!MCP.equals(kind) && !SKILL.equals(kind) && !MEMORY.equals(kind)) {
            throw new IllegalArgumentException("kind must be mcp, skill or memory");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    static final class ResourceRoot {
        final Path path;
        private final String label;
        final boolean file;

        private ResourceRoot(Path path, String label, boolean file) {
            this.path = path;
            this.label = label;
            this.file = file;
        }
    }
}
