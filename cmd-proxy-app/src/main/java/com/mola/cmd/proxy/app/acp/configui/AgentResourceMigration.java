package com.mola.cmd.proxy.app.acp.configui;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.memory.MemoryScopeLockRegistry;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.*;

/** Portable, allowlisted workspace resources; archive paths never choose arbitrary destinations. */
final class AgentResourceMigration {
    static final int MAX_BYTES = 64 * 1024 * 1024;
    private static final int MAX_FILES = 10000;
    private static final List<String> KINDS = Arrays.asList("mcp", "skill", "memory");
    private final AgentResourceBrowser browser = new AgentResourceBrowser();

    byte[] exportZip(AcpRobotParam robot) throws IOException {
        Path memory = memoryRoot(robot);
        ReentrantLock lock = MemoryScopeLockRegistry.lockForStoragePath(memory);
        lock.lock();
        try {
            Map<String, byte[]> files = new LinkedHashMap<>();
            Map<String, String> substitutions = substitutions(robot, true);
            long[] total = {0};
            for (String kind : KINDS) {
                for (AgentResourceBrowser.ResourceRoot root : browser.roots(robot, kind)) {
                    Path work = resourceBase(robot, kind);
                    if (!kind.equals("memory") && !root.path.startsWith(work)) continue;
                    Path base = kind.equals("memory") ? memory : work;
                    if (!Files.exists(root.path, LinkOption.NOFOLLOW_LINKS)) continue;
                    requireNoLinks(root.path);
                    if (root.file) {
                        add(files, kind, base, root.path, substitutions, total);
                    } else {
                        Files.walkFileTree(root.path, new SimpleFileVisitor<Path>() {
                            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                                    throws IOException {
                                requireNoLinks(dir);
                                if (kind.equals("memory") && !dir.equals(base)
                                        && !dir.startsWith(base.resolve("memories"))) return FileVisitResult.SKIP_SUBTREE;
                                return FileVisitResult.CONTINUE;
                            }
                            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                    throws IOException {
                                if (!kind.equals("memory") || activeMemory(base.relativize(file).toString().replace('\\', '/'))) {
                                    add(files, kind, base, file, substitutions, total);
                                }
                                return FileVisitResult.CONTINUE;
                            }
                        });
                    }
                }
            }
            JSONObject manifest = new JSONObject(true);
            manifest.put("format", "cmd-proxy-agent-resources");
            manifest.put("version", 1);
            manifest.put("provider", String.valueOf(robot.getAgentProvider()));
            JSONArray executable = new JSONArray();
            for (String name : files.keySet()) {
                if (name.startsWith("skill/") && Files.isExecutable(resourceBase(robot, "skill").resolve(
                        transformName(name.substring(6), substitutions(robot, false))))) executable.add(name);
            }
            manifest.put("executable", executable);
            manifest.put("pathTokens", Arrays.asList("__CMD_PROXY_HOME_DIR__", "__CMD_PROXY_WORKSPACE__", "__CMD_PROXY_MEMORY__"));
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
                put(zip, "manifest.json", manifest.toJSONString().getBytes(StandardCharsets.UTF_8));
                for (Map.Entry<String, byte[]> file : files.entrySet()) put(zip, file.getKey(), file.getValue());
            }
            if (buffer.size() > MAX_BYTES) throw new IOException("导出 ZIP 超过 64 MiB 限制");
            return buffer.toByteArray();
        } finally { lock.unlock(); }
    }

    JSONObject importZip(AcpRobotParam robot, InputStream input, Set<String> selected) throws IOException {
        if (selected == null || selected.isEmpty() || !KINDS.containsAll(selected)) {
            throw new IllegalArgumentException("请至少选择一项有效的导入类别");
        }
        Map<String, byte[]> archive = readZip(input);
        byte[] manifestBytes = archive.remove("manifest.json");
        if (manifestBytes == null) throw new IllegalArgumentException("ZIP 缺少迁移清单 manifest.json");
        JSONObject manifest = JSON.parseObject(new String(manifestBytes, StandardCharsets.UTF_8));
        if (manifest == null || !"cmd-proxy-agent-resources".equals(manifest.getString("format"))
                || manifest.getIntValue("version") != 1) throw new IllegalArgumentException("不支持的迁移包格式");
        if ((selected.contains("mcp") || selected.contains("skill"))
                && !String.valueOf(robot.getAgentProvider()).equals(manifest.getString("provider"))) {
            throw new IllegalArgumentException("MCP 和 Skill 只能导入到相同类型的 Agent；记忆可单独导入");
        }
        Set<String> executable = new HashSet<>();
        JSONArray executableEntries = manifest.getJSONArray("executable");
        if (executableEntries != null) {
            for (int i = 0; i < executableEntries.size(); i++) executable.add(executableEntries.getString(i));
        }
        Map<String, Path> targets = new LinkedHashMap<>();
        Map<String, byte[]> prepared = new LinkedHashMap<>();
        Map<String, String> substitutions = substitutions(robot, false);
        for (Map.Entry<String, byte[]> entry : archive.entrySet()) {
            String name = entry.getKey();
            int slash = name.indexOf('/');
            if (slash < 1 || !KINDS.contains(name.substring(0, slash))) {
                throw new IllegalArgumentException("ZIP 包含未知资源: " + name);
            }
            String kind = name.substring(0, slash);
            if (!selected.contains(kind)) continue;
            String relative = transformName(name.substring(slash + 1), substitutions);
            validateRelative(relative);
            Path base = kind.equals("memory") ? memoryRoot(robot) : resourceBase(robot, kind);
            Path target = base.resolve(relative).normalize();
            if (!target.startsWith(base) || !allowed(robot, kind, target, relative)) {
                throw new IllegalArgumentException("文件不属于目标 Agent 可识别的资源: " + name);
            }
            if (targets.containsValue(target)) throw new IllegalArgumentException("ZIP 包含重复目标: " + name);
            targets.put(name, target);
            try { prepared.put(name, transform(entry.getValue(), substitutions)); }
            catch (IOException e) { throw new IOException("文件预处理失败: " + name + "：" + e.getMessage(), e); }
        }
        if (prepared.containsKey("memory/MEMORY_INDEX.json")) {
            try {
                JSONObject index = JSON.parseObject(new String(prepared.get("memory/MEMORY_INDEX.json"), StandardCharsets.UTF_8));
                JSONArray memories = index == null ? null : index.getJSONArray("memories");
                if (memories == null) throw new IllegalArgumentException("缺少 memories 列表");
                for (int i = 0; i < memories.size(); i++) {
                    JSONObject entry = memories.getJSONObject(i);
                    String file = entry == null ? null : entry.getString("file");
                    if (file == null || file.isEmpty()) throw new IllegalArgumentException("记忆条目缺少明细路径");
                    Path detail = Paths.get(file.replace('\\', '/')).toAbsolutePath().normalize();
                    if (!detail.startsWith(memoryRoot(robot).resolve("memories")) || !targets.containsValue(detail)) {
                        throw new IllegalArgumentException("索引引用了迁移包之外的明细: " + file);
                    }
                    entry.put("file", detail.toString());
                }
                prepared.put("memory/MEMORY_INDEX.json", index.toJSONString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IllegalArgumentException("memory/MEMORY_INDEX.json 校验失败: " + e.getMessage(), e);
            }
        }
        // All parsing and path replacement completes before any destination is modified.
        JSONArray succeeded = new JSONArray();
        JSONArray failed = new JSONArray();
        JSONObject counts = new JSONObject(true);
        for (String kind : selected) counts.put(kind, 0);
        List<String> names = new ArrayList<>(targets.keySet());
        names.sort(Comparator.comparing(name -> name.equals("memory/MEMORY_INDEX.json")));
        ReentrantLock lock = MemoryScopeLockRegistry.lockForStoragePath(memoryRoot(robot));
        lock.lock();
        try {
            boolean memoryFailed = false;
            for (String name : names) {
                try {
                    if (name.equals("memory/MEMORY_INDEX.json") && memoryFailed) {
                        throw new IOException("记忆明细导入失败，保留原记忆索引");
                    }
                    Path target = targets.get(name);
                    requireNoLinks(target);
                    Files.createDirectories(target.getParent());
                    requireNoLinks(target);
                    Path temp = Files.createTempFile(target.getParent(), ".agent-import-", ".tmp");
                    try {
                        Files.write(temp, prepared.get(name));
                        if (Files.exists(target) && target.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                            Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target));
                        }
                        if (name.startsWith("skill/") && executable.contains(name)
                                && temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                            Set<java.nio.file.attribute.PosixFilePermission> permissions = Files.getPosixFilePermissions(temp);
                            permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
                            Files.setPosixFilePermissions(temp, permissions);
                        }
                        try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                        catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
                    } finally { Files.deleteIfExists(temp); }
                    succeeded.add(name);
                    String kind = name.substring(0, name.indexOf('/'));
                    counts.put(kind, counts.getIntValue(kind) + 1);
                } catch (Exception e) {
                    JSONObject failure = new JSONObject(true);
                    failure.put("file", name);
                    failure.put("reason", e.getMessage());
                    failed.add(failure);
                    if (name.startsWith("memory/")) memoryFailed = true;
                }
            }
        } finally { lock.unlock(); }
        JSONObject result = new JSONObject(true);
        result.put("ok", failed.isEmpty());
        result.put("succeeded", succeeded);
        result.put("failed", failed);
        result.put("counts", counts);
        return result;
    }

    private boolean allowed(AcpRobotParam robot, String kind, Path target, String relative) {
        if (kind.equals("memory")) return activeMemory(relative);
        for (AgentResourceBrowser.ResourceRoot root : browser.roots(robot, kind)) {
            if (!root.path.startsWith(resourceBase(robot, kind))) continue;
            if (root.file ? target.equals(root.path) : target.startsWith(root.path) && !target.equals(root.path)) return true;
        }
        return false;
    }

    private void add(Map<String, byte[]> files, String kind, Path base, Path file,
                     Map<String, String> replacements, long[] total) throws IOException {
        requireNoLinks(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("无法导出非普通文件: " + file);
        String name = kind + "/" + transformName(base.relativize(file).toString().replace('\\', '/'), replacements);
        validateRelative(name);
        if (files.containsKey(name)) return;
        if (files.size() >= MAX_FILES || Files.size(file) > MAX_BYTES - total[0]) throw new IOException("迁移资源超过限制（10000 文件 / 64 MiB）");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) { bytes = readBounded(input, (int) (MAX_BYTES - total[0])); }
        try { bytes = transform(bytes, replacements); }
        catch (IOException e) { throw new IOException("无法导出 " + name + "：" + e.getMessage(), e); }
        total[0] += bytes.length;
        if (total[0] > MAX_BYTES) throw new IOException("迁移资源超过 64 MiB 限制");
        files.put(name, bytes);
    }

    private Map<String, String> substitutions(AcpRobotParam robot, boolean exporting) {
        Map<String, String> replacements = new LinkedHashMap<>();
        addReplacement(replacements, memoryRoot(robot).toString(), "__CMD_PROXY_MEMORY__", exporting);
        addReplacement(replacements, workspace(robot).toString(), "__CMD_PROXY_WORKSPACE__", exporting);
        addReplacement(replacements, System.getProperty("user.home"), "__CMD_PROXY_HOME_DIR__", exporting);
        return replacements;
    }

    private static void addReplacement(Map<String, String> map, String path, String token, boolean exporting) {
        if (exporting) {
            map.put(path.replace("\\", "\\\\"), token + "_JSON");
            map.put(path.replace('\\', '/'), token);
            map.put(path, token);
        } else {
            // JSON escaping must be applied before the plain token, especially on Windows.
            map.put(token + "_JSON", path.replace("\\", "\\\\"));
            map.put(token, path.replace('\\', '/'));
        }
    }

    private static String transformName(String value, Map<String, String> replacements) {
        for (Map.Entry<String, String> item : replacements.entrySet()) value = value.replace(item.getKey(), item.getValue());
        return value;
    }

    private static byte[] transform(byte[] bytes, Map<String, String> replacements) throws IOException {
        if (bytes.length >= 2 && ((bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe)
                || (bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff))) {
            Charset charset = bytes[0] == (byte) 0xff ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE;
            String value = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 2, bytes.length - 2)).toString();
            byte[] replaced = transformName(value, replacements).getBytes(charset);
            byte[] result = new byte[replaced.length + 2];
            result[0] = bytes[0]; result[1] = bytes[1];
            System.arraycopy(replaced, 0, result, 2, replaced.length);
            return result;
        }
        try {
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (value.indexOf('\0') >= 0) return binary(bytes, replacements);
            return transformName(value, replacements).getBytes(StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) { return binary(bytes, replacements); }
    }

    private static byte[] binary(byte[] bytes, Map<String, String> replacements) throws IOException {
        String raw = new String(bytes, StandardCharsets.ISO_8859_1);
        for (String path : replacements.keySet()) {
            for (Charset encoding : Arrays.asList(StandardCharsets.UTF_8, StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
                if (raw.contains(new String(path.getBytes(encoding), StandardCharsets.ISO_8859_1))) {
                    throw new IOException("二进制文件包含需要替换的绝对路径，无法安全迁移");
                }
            }
        }
        return bytes;
    }

    private Path resourceBase(AcpRobotParam robot, String kind) {
        if (kind.equals("skill") && "REMOTE_STARCORE_ACP".equals(robot.getAgentProvider()) && !"BROWSER".equals(robot.getRemotePlatform())) return com.mola.cmd.proxy.app.acp.remote.RemoteResources.skillRoot(robot);
        return workspace(robot);
    }
    private Path memoryRoot(AcpRobotParam robot) { return browser.roots(robot, "memory").get(0).path; }
    private static Path workspace(AcpRobotParam robot) { return Paths.get(robot.getWorkDir()).toAbsolutePath().normalize(); }
    private static boolean activeMemory(String relative) {
        return relative.equals("MEMORY_INDEX.json") || relative.startsWith("memories/");
    }
    private static void requireNoLinks(Path path) throws IOException {
        for (Path part = path.toAbsolutePath().normalize(); part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IOException("不支持符号链接路径: " + path);
        }
    }
    private static void validateRelative(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":")) throw new IllegalArgumentException("非法 ZIP 路径: " + name);
        for (String part : name.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("非法 ZIP 路径: " + name);
        }
    }
    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
    }
    private static Map<String, byte[]> readZip(InputStream input) throws IOException {
        byte[] compressed = readBounded(input, MAX_BYTES);
        Map<String, byte[]> files = new LinkedHashMap<>();
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(compressed))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                validateRelative(entry.isDirectory() && name.endsWith("/") ? name.substring(0, name.length() - 1) : name);
                if (entry.isDirectory()) throw new IllegalArgumentException("迁移包不应包含目录项");
                if (files.size() >= MAX_FILES + 1 || files.containsKey(name)) throw new IllegalArgumentException("ZIP 文件过多或路径重复");
                byte[] bytes = readBounded(zip, MAX_BYTES - total);
                total += bytes.length;
                files.put(name, bytes);
            }
        }
        return files;
    }
    static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (n > limit - output.size()) throw new IOException("迁移包超过 64 MiB 限制");
            output.write(buffer, 0, n);
        }
        return output.toByteArray();
    }
}
