package com.mola.cmd.proxy.app.acp.acpclient.agent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class EmbeddedPiAgentProvider implements AgentProvider {
    private static final EmbeddedPiRuntimeManager RUNTIME = new EmbeddedPiRuntimeManager();
    private final EmbeddedPiRuntimeManager runtimeManager;
    public EmbeddedPiAgentProvider() { this.runtimeManager = RUNTIME; }
    EmbeddedPiAgentProvider(EmbeddedPiRuntimeManager runtimeManager) { this.runtimeManager = runtimeManager; }
    private static final String COMMAND = "STARWEAVE_PI_NODE", ENTRY = "STARWEAVE_PI_ENTRY";
    public String getName() { return "内嵌引擎 · Pi"; }
    public String getCommand() { return "node"; }
    public String[] getArgs() { return new String[0]; }
    public String getCommand(AcpRobotParam robot, Map<String, String> env) { return env.get(COMMAND); }
    public String[] getArgs(AcpRobotParam robot, Map<String, String> env) { return new String[]{ env.get(ENTRY) }; }
    public boolean needsInlineImages() { return true; }
    public boolean supportsCompactionUpdates() { return true; }
    public String getSessionCloseMethod() { return "session/close"; }
    public boolean closeInputAfterSessionClose() { return true; }
    public String getSkillsRelativePath() { return ".agents/skills"; }
    public List<Path> getMcpConfigPaths(String workspace) { return appendSharedMcpConfigPaths(Collections.emptyList(), workspace); }
    public List<Path> getSkillPaths(String workspace, AcpRobotParam robot) {
        List<Path> paths = new ArrayList<>(); paths.add(agentHome(robot).resolve("skills"));
        if (workspace != null && !workspace.trim().isEmpty()) paths.add(Paths.get(workspace, ".agents", "skills"));
        return paths;
    }
    public void prepareLaunch(AcpRobotParam robot, Map<String, String> env) throws IOException {
        try {
            if (robot == null || robot.getEmbeddedPi() == null) throw new IllegalArgumentException("请配置内嵌 Pi 模型接口");
            robot.getEmbeddedPi().validate(robot.getModel());
        } catch (IllegalArgumentException e) { throw new IOException(e.getMessage()); }
        Path node = EmbeddedPiRuntimeManager.systemNode(env);
        Path runtime = runtimeManager.prepare();
        env.put(COMMAND, node.toString());
        env.put(ENTRY, runtime.resolve("integration/entry.mjs").toString());
        env.put("PI_SKIP_VERSION_CHECK", "1");
        JsonObject config = new JsonObject(); EmbeddedPiConfig pi = robot.getEmbeddedPi();
        config.addProperty("baseUrl", pi.getBaseUrl().trim()); config.addProperty("apiKey", pi.getApiKey().trim());
        config.addProperty("model", robot.getModel().trim()); config.addProperty("contextWindow", pi.getContextWindow());
        config.addProperty("maxTokens", pi.getMaxTokens()); config.addProperty("agentDir", agentHome(robot).toString());
        env.put("STARWEAVE_PI_CONFIG", new Gson().toJson(config));
    }
    public static Path agentHome(AcpRobotParam robot) {
        String id = robot != null && robot.getEmbeddedPi() != null ? robot.getEmbeddedPi().getStateId() : null;
        if (id == null || id.trim().isEmpty()) {
            try {
                String seed = robot == null ? "default" : robot.getName() + "\n" + robot.getWorkDir();
                id = EmbeddedPiRuntimeManager.hex(MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
            } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }
        if (!id.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException("内嵌 Pi stateId 无效");
        return CmdProxyHome.resolve("agents", id, "embedded-pi");
    }
    public CompactionSignal detectCompactionSignal(JsonObject message) {
        JsonObject update = update(message); if (update == null) return CompactionSignal.NONE;
        String status;
        if ("compaction_update".equals(text(update, "sessionUpdate"))) status = text(update, "status");
        else {
            JsonObject meta = update.has("_meta") && update.get("_meta").isJsonObject() ? update.getAsJsonObject("_meta") : null;
            if (meta == null || !meta.has("starweaveCompaction") || !meta.get("starweaveCompaction").isJsonObject()) return CompactionSignal.NONE;
            status = text(meta.getAsJsonObject("starweaveCompaction"), "status");
        }
        switch (status) {
            case "in_progress": return CompactionSignal.STARTED;
            case "completed": return CompactionSignal.COMPLETED;
            case "failed": case "cancelled": return CompactionSignal.FAILED;
            default: return CompactionSignal.NONE;
        }
    }
    public double extractContextUsage(JsonObject message) {
        JsonObject update = update(message);
        if (update == null || !"usage_update".equals(text(update, "sessionUpdate")) || !update.has("size") || !update.has("used")) return -1;
        double size = update.get("size").getAsDouble(); return size > 0 ? Math.min(100, update.get("used").getAsDouble() * 100 / size) : -1;
    }
    private static JsonObject update(JsonObject message) {
        if (message == null || !"session/update".equals(text(message, "method"))) return null;
        JsonObject params = message.has("params") && message.get("params").isJsonObject() ? message.getAsJsonObject("params") : null;
        return params != null && params.has("update") && params.get("update").isJsonObject() ? params.getAsJsonObject("update") : null;
    }
    private static String text(JsonObject value, String key) { return value.has(key) && value.get(key).isJsonPrimitive() ? value.get(key).getAsString() : ""; }
}
