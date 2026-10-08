package com.mola.cmd.proxy.app.acp.acpclient.agent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Codex-ACP 的 AgentProvider 实现。
 * <p>
 * 兼容两个版本：
 * <ul>
 *   <li>Node.js 版（@agentclientprotocol/codex-acp）：认证 methodId = "api-key"</li>
 *   <li>Rust 版（zed-industries/codex-acp）：认证 methodId = "openai-api-key"</li>
 * </ul>
 * <p>
 * API Key 通过环境变量 OPENAI_API_KEY 注入，不通过 authenticate 请求体传递。
 * 版本区分由 AbstractAcpClient 在 initialize 响应的 authMethods 中自动检测。
 */
public class CodexAcpProvider implements AgentProvider {

    private static final Gson GSON = new Gson();
    private static final String INITIAL_AGENT_MODE = "agent-full-access";
    private static final NpmProviderRuntimeManager RUNTIME_MANAGER =
            NpmProviderRuntimeManager.getInstance();

    @Override
    public String getCommand() {
        return "codex-acp";
    }

    @Override
    public String[] getArgs() {
        return new String[0];
    }

    @Override
    public String getCommand(AcpRobotParam robotParam, Map<String, String> environment) {
        return RUNTIME_MANAGER.preparedCommand(environment);
    }

    @Override
    public void prepareLaunch(AcpRobotParam robotParam, Map<String, String> environment)
            throws java.io.IOException {
        RUNTIME_MANAGER.prepareLaunch(AgentProviderType.CODEX_ACP, robotParam, environment);
    }

    @Override
    public RuntimeLease prepareRuntimeLaunch(AcpRobotParam robotParam,
                                             Map<String, String> environment)
            throws java.io.IOException {
        return RUNTIME_MANAGER.prepareRuntimeLaunch(
                AgentProviderType.CODEX_ACP, robotParam, environment);
    }

    @Override
    public boolean hasFallbackCommand() {
        return true;
    }

    @Override
    public String getFallbackCommand() {
        return "codex-acp";
    }

    @Override
    public String[] getFallbackArgs() {
        return new String[0];
    }

    @Override
    public List<Path> getMcpConfigPaths(String workspacePath) {
        return getMcpConfigPaths(workspacePath, null);
    }

    @Override
    public List<Path> getMcpConfigPaths(String workspacePath, AcpRobotParam robotParam) {
        // 先加载全局配置；McpConfigLoader 会按 server 名去重，保留先加载的配置。
        // 这使同名的项目级配置不会在 ACP 会话中重复注册。
        List<Path> paths = new ArrayList<>();
        paths.add(resolveCodexHome(robotParam).resolve("config.toml"));
        if (workspacePath != null && !workspacePath.trim().isEmpty()) {
            // Codex 的项目级 MCP 配置位于 <workspace>/.codex/config.toml。
            // 显式传入 ACP，避免依赖 codex-acp/App Server 对项目配置层的隐式加载，
            // 并保证 session/load 恢复时仍会重新挂载这些 MCP server。
            paths.add(Paths.get(workspacePath, ".codex", "config.toml"));
        }
        // 统一共享配置（.cmd-proxy/mcp.json）优先级最低，同名时优先 Codex 自身配置。
        return appendSharedMcpConfigPaths(paths, workspacePath);
    }

    @Override
    public String getName() {
        return "codex-acp";
    }

    @Override
    public String getSkillsRelativePath() {
        return ".agents/skills";
    }

    @Override
    public List<Path> getSkillPaths(String workspacePath, AcpRobotParam robotParam) {
        List<Path> paths = new ArrayList<>();
        paths.add(resolveCodexHome(robotParam).resolve("skills"));
        if (workspacePath != null && !workspacePath.trim().isEmpty()) {
            paths.add(Paths.get(workspacePath, ".agents", "skills"));
        }
        return paths;
    }

    @Override
    public boolean isAgentMessageControlArtifact(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.trim();
        return "*Conversation interrupted*".equals(normalized)
                || "*Context compacted to fit the model's context window.*".equals(normalized)
                || "Context compacted.".equals(normalized);
    }

    @Override
    public boolean supportsCompactionUpdates() {
        return true;
    }

    @Override
    public CompactionSignal detectCompactionSignal(JsonObject msg) {
        if (!isSessionUpdate(msg)) {
            return CompactionSignal.NONE;
        }

        JsonObject update = msg.getAsJsonObject("params").getAsJsonObject("update");
        // codex-acp 2.1.1 negotiates session.compaction and emits dedicated updates
        // without the legacy tool-call metadata. Keep older adapters supported.
        if ("compaction_update".equals(getString(update, "sessionUpdate"))
                || isStructuredContextCompactionUpdate(update)) {
            String status = getString(update, "status")
                    .toLowerCase(java.util.Locale.ROOT);
            if ("in_progress".equals(status) || "pending".equals(status)) {
                return CompactionSignal.STARTED;
            }
            if ("completed".equals(status)) {
                return CompactionSignal.COMPLETED;
            }
            if ("failed".equals(status) || "cancelled".equals(status)) {
                return CompactionSignal.FAILED;
            }
            return CompactionSignal.NONE;
        }

        if (!"agent_message_chunk".equals(getString(update, "sessionUpdate"))) {
            return CompactionSignal.NONE;
        }

        JsonObject content = update.getAsJsonObject("content");
        String text = content != null ? getString(content, "text").trim() : "";
        if ("*Context compacted to fit the model's context window.*".equals(text)
                || "Context compacted.".equals(text)) {
            return CompactionSignal.COMPLETED;
        }
        return CompactionSignal.NONE;
    }

    /**
     * codex-acp 1.7+ exposes context compaction as a synthetic tool call. Use its
     * machine-readable metadata instead of the display title, which may be renamed
     * or localized independently of the lifecycle contract.
     */
    private boolean isStructuredContextCompactionUpdate(JsonObject update) {
        String updateType = getString(update, "sessionUpdate");
        if (!"tool_call".equals(updateType) && !"tool_call_update".equals(updateType)) {
            return false;
        }
        if (update == null || !update.has("_meta") || !update.get("_meta").isJsonObject()) {
            return false;
        }
        JsonObject meta = update.getAsJsonObject("_meta");
        return meta.has("contextCompaction")
                && meta.get("contextCompaction").isJsonObject();
    }

    private boolean isSessionUpdate(JsonObject msg) {
        if (msg == null || !"session/update".equals(getString(msg, "method"))) {
            return false;
        }
        JsonObject params = msg.getAsJsonObject("params");
        return params != null && params.getAsJsonObject("update") != null;
    }

    private String getString(JsonObject object, String member) {
        return object != null && object.has(member) && !object.get(member).isJsonNull()
                ? object.get(member).getAsString() : "";
    }

    @Override
    public Map<String, String> getExtraEnv(AcpRobotParam robotParam) {
        Map<String, String> env = new HashMap<>();
        env.put("INITIAL_AGENT_MODE", INITIAL_AGENT_MODE);
        if (robotParam == null) {
            return env;
        }
        String codexHome = robotParam.getCodexHome();
        if (codexHome != null && !codexHome.trim().isEmpty()) {
            env.put("CODEX_HOME", expandUserHome(codexHome.trim()).toString());
        }
        String apiKey = robotParam.getApiKey();
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            String trimmedApiKey = apiKey.trim();
            env.put("CODEX_API_KEY", trimmedApiKey);
            env.put("OPENAI_API_KEY", trimmedApiKey);
        }
        String model = robotParam.getModel();
        if (model != null && !model.trim().isEmpty()) {
            env.put("CODEX_CONFIG", buildCodexConfig(model.trim()));
        }
        return env;
    }

    private Path resolveCodexHome(AcpRobotParam robotParam) {
        if (robotParam != null && robotParam.getCodexHome() != null
                && !robotParam.getCodexHome().trim().isEmpty()) {
            return expandUserHome(robotParam.getCodexHome().trim());
        }
        String envHome = System.getenv("CODEX_HOME");
        if (envHome != null && !envHome.trim().isEmpty()) {
            return expandUserHome(envHome.trim());
        }
        return Paths.get(System.getProperty("user.home"), ".codex");
    }

    private Path expandUserHome(String path) {
        if ("~".equals(path)) {
            return Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        }
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return Paths.get(System.getProperty("user.home"), path.substring(2))
                    .toAbsolutePath().normalize();
        }
        return Paths.get(path).toAbsolutePath().normalize();
    }

    private String buildCodexConfig(String model) {
        JsonObject config = readExistingCodexConfig();
        String modelId = model;
        String reasoningEffort = null;

        int bracketStart = model.lastIndexOf('[');
        if (bracketStart > 0 && model.endsWith("]")) {
            String parsedModelId = model.substring(0, bracketStart).trim();
            String parsedReasoningEffort = model.substring(bracketStart + 1, model.length() - 1).trim();
            if (!parsedModelId.isEmpty() && !parsedReasoningEffort.isEmpty()) {
                modelId = parsedModelId;
                reasoningEffort = parsedReasoningEffort;
            }
        }

        config.addProperty("model", modelId);
        if (reasoningEffort != null) {
            config.addProperty("model_reasoning_effort", reasoningEffort);
        }
        return GSON.toJson(config);
    }

    private JsonObject readExistingCodexConfig() {
        String existing = System.getenv("CODEX_CONFIG");
        if (existing == null || existing.trim().isEmpty()) {
            return new JsonObject();
        }
        try {
            com.google.gson.JsonElement parsed = JsonParser.parseString(existing);
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (Exception ignored) {
            // Fall back to a fresh config; codex-acp requires CODEX_CONFIG to be a JSON object.
        }
        return new JsonObject();
    }
}
