package com.mola.cmd.proxy.app.acp.configui;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProviderType;
import com.mola.cmd.proxy.app.acp.acpclient.agent.NpmProviderRuntimeManager;
import com.mola.cmd.proxy.app.acp.acpclient.model.AgentModelCatalog;
import com.mola.cmd.proxy.app.acp.common.PathResolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Provider-specific live model discovery used by ConfigUI. */
final class ProviderModelDiscoveryService {

    private static final int HTTP_TIMEOUT_MILLIS = 12_000;
    private static final int PROCESS_TIMEOUT_SECONDS = 20;
    private static final String SECRET_MASK = "********";

    private final AgentModelCatalog catalog;

    ProviderModelDiscoveryService(AgentModelCatalog catalog) {
        this.catalog = catalog;
    }

    JSONObject models(JSONObject request, boolean force) {
        String provider = trim(request.getString("provider"));
        if (provider == null) provider = AgentProviderType.KIRO_CLI.name();
        String warning = null;
        if (force || !catalog.isFresh(request)) {
            try {
                List<AgentModelCatalog.ModelEntry> discovered = discover(provider, request, force);
                if (discovered != null && !discovered.isEmpty()) {
                    catalog.replaceLive(request, discovered, liveSource(provider));
                } else if (AgentProviderType.KIRO_CLI.name().equals(provider)) {
                    warning = "Kiro 模型目录将在智能体下次启动或刷新会话后同步";
                }
            } catch (Exception e) {
                warning = e.getMessage() == null ? "实时模型目录刷新失败" : e.getMessage();
            }
        }
        JSONObject result = catalog.snapshot(request);
        if (warning != null) result.put("warning", warning);
        return result;
    }

    void remember(JSONObject request) throws IOException {
        List<String> values = new ArrayList<>();
        values.add(request.getString("model"));
        values.add(request.getString("memoryModel"));
        catalog.remember(request, values);
    }

    private List<AgentModelCatalog.ModelEntry> discover(
            String provider, JSONObject request, boolean force) throws IOException {
        AgentProviderType type = AgentProviderType.fromString(provider);
        switch (type) {
            case EMBEDDED_PI_ACP:
                return discoverEmbeddedPi(request);
            case OPENCODE:
                return discoverOpenCode(request, force);
            case CODEX_ACP:
                return discoverOpenAi(request);
            case CLAUDE_AGENT_ACP:
                return discoverClaude(request);
            case DEEPSEEK_HARNESS_ACP:
                return discoverDeepSeek(request);
            case KIRO_CLI:
            default:
                return Collections.emptyList();
        }
    }

    private List<AgentModelCatalog.ModelEntry> discoverOpenCode(
            JSONObject request, boolean force) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(openCodeExecutable(request.getString("providerVersion")));
        command.add("models");
        if (force) command.add("--refresh");

        ProcessBuilder builder = new ProcessBuilder(command);
        String workDir = trim(request.getString("workDir"));
        if (workDir != null && Files.isDirectory(Paths.get(workDir))) {
            builder.directory(Paths.get(workDir).toFile());
        }
        String currentPath = builder.environment().get("PATH");
        builder.environment().put("PATH", PathResolver.enrichPath(
                System.getProperty("user.home"), currentPath == null ? "" : currentPath));
        builder.environment().put("OPENCODE_DISABLE_AUTOUPDATE", "true");
        builder.redirectErrorStream(true);

        Process process = builder.start();
        FutureTask<byte[]> output = new FutureTask<>(() -> readLimited(process.getInputStream(), 2 * 1024 * 1024));
        Thread reader = new Thread(output, "opencode-model-discovery-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("OpenCode 模型列表刷新超时");
            }
            String text;
            try {
                text = new String(output.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IOException("无法读取 OpenCode 模型列表", e);
            }
            if (process.exitValue() != 0) {
                throw new IOException("OpenCode 模型列表刷新失败: " + compact(text));
            }
            LinkedHashMap<String, AgentModelCatalog.ModelEntry> result = new LinkedHashMap<>();
            for (String line : text.split("\\r?\\n")) {
                String id = trim(line);
                if (id == null || id.startsWith("[") || id.indexOf('/') <= 0 || id.contains(" ")) continue;
                result.put(id, new AgentModelCatalog.ModelEntry(id, id));
            }
            return new ArrayList<>(result.values());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("OpenCode 模型列表刷新被中断", e);
        }
    }

    private List<AgentModelCatalog.ModelEntry> discoverOpenAi(JSONObject request) throws IOException {
        String key = secret(request.getString("apiKey"), System.getenv("OPENAI_API_KEY"));
        if (key == null) return Collections.emptyList();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + key);
        JSONArray data = getModels("https://api.openai.com/v1/models", headers, request);
        List<AgentModelCatalog.ModelEntry> result = new ArrayList<>();
        for (Object value : data) {
            if (!(value instanceof JSONObject)) continue;
            String id = trim(((JSONObject) value).getString("id"));
            if (id != null && isOpenAiTextModel(id)) {
                result.add(new AgentModelCatalog.ModelEntry(id, id));
            }
        }
        return result;
    }

    private List<AgentModelCatalog.ModelEntry> discoverEmbeddedPi(JSONObject request) throws IOException {
        JSONObject pi = request.getJSONObject("embeddedPi");
        if (pi == null) return Collections.emptyList();
        String base = trim(pi.getString("baseUrl")), key = secret(pi.getString("apiKey"), null);
        if (base == null || key == null) return Collections.emptyList();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        Map<String, String> headers = new LinkedHashMap<>(); headers.put("Authorization", "Bearer " + key);
        return entries(getModels(base + "/models", headers, request));
    }

    private List<AgentModelCatalog.ModelEntry> discoverClaude(JSONObject request) throws IOException {
        String key = secret(request.getString("apiKey"), System.getenv("ANTHROPIC_API_KEY"));
        if (key == null) return Collections.emptyList();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", key);
        headers.put("anthropic-version", "2023-06-01");
        JSONArray data = getModels("https://api.anthropic.com/v1/models?limit=1000", headers, request);
        return entries(data);
    }

    private List<AgentModelCatalog.ModelEntry> discoverDeepSeek(JSONObject request) throws IOException {
        String key = secret(request.getString("apiKey"), System.getenv("DEEPSEEK_API_KEY"));
        if (key == null) return Collections.emptyList();
        String base = trim(request.getString("deepSeekBaseUrl"));
        if (base == null) base = trim(System.getenv("DEEPSEEK_BASE_URL"));
        if (base == null) base = "https://api.deepseek.com";
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + key);
        return entries(getModels(base + "/models", headers, request));
    }

    private JSONArray getModels(String endpoint, Map<String, String> headers,
                                JSONObject request) throws IOException {
        URL url = new URL(endpoint);
        String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
        if (!"http".equals(protocol) && !"https".equals(protocol)) {
            throw new IOException("模型目录 Endpoint 仅支持 HTTP/HTTPS");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection(proxy(request));
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(HTTP_TIMEOUT_MILLIS);
        connection.setReadTimeout(HTTP_TIMEOUT_MILLIS);
        connection.setRequestProperty("Accept", "application/json");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String body = stream == null ? "" : new String(readLimited(stream, 2 * 1024 * 1024), StandardCharsets.UTF_8);
        if (status < 200 || status >= 300) {
            throw new IOException("模型目录请求失败 (HTTP " + status + "): " + compact(body));
        }
        JSONObject response = JSON.parseObject(body);
        JSONArray data = response == null ? null : response.getJSONArray("data");
        if (data == null) throw new IOException("模型目录响应缺少 data 数组");
        return data;
    }

    private Proxy proxy(JSONObject request) {
        if (!request.getBooleanValue("proxyEnabled")) return Proxy.NO_PROXY;
        String raw = trim(request.getString("httpProxy"));
        if (raw == null) return Proxy.NO_PROXY;
        try {
            URL url = new URL(raw.contains("://") ? raw : "http://" + raw);
            int port = url.getPort() > 0 ? url.getPort() : 80;
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(url.getHost(), port));
        } catch (Exception ignored) {
            return Proxy.NO_PROXY;
        }
    }

    private String openCodeExecutable(String requestedVersion) {
        try {
            NpmProviderRuntimeManager manager = NpmProviderRuntimeManager.getInstance();
            NpmProviderRuntimeManager.RuntimeStatus status = manager.status(AgentProviderType.OPENCODE);
            String version = trim(requestedVersion);
            if (version == null) version = status.getDefaultVersion();
            if (version != null && status.getInstalledVersions().contains(version)) {
                Path home = manager.runtimeHome(AgentProviderType.OPENCODE, version);
                Path executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                        ? home.resolve("opencode.cmd") : home.resolve("bin").resolve("opencode");
                if (Files.isRegularFile(executable)) return executable.toString();
            }
        } catch (Exception ignored) {
            // Fall through to PATH-based command for unmanaged/older installations.
        }
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "opencode.cmd" : "opencode";
    }

    private static List<AgentModelCatalog.ModelEntry> entries(JSONArray data) {
        List<AgentModelCatalog.ModelEntry> result = new ArrayList<>();
        for (Object value : data) {
            if (!(value instanceof JSONObject)) continue;
            JSONObject item = (JSONObject) value;
            String id = trim(item.getString("id"));
            if (id == null) continue;
            String name = trim(item.getString("name"));
            if (name == null) name = trim(item.getString("display_name"));
            result.add(new AgentModelCatalog.ModelEntry(id, name == null ? id : name));
        }
        return result;
    }

    private static boolean isOpenAiTextModel(String id) {
        String value = id.toLowerCase(Locale.ROOT);
        if (value.contains("image") || value.contains("audio") || value.contains("realtime")
                || value.contains("transcri") || value.contains("tts")
                || value.contains("embedding") || value.contains("moderation")
                || value.contains("search-preview")) {
            return false;
        }
        return value.startsWith("gpt-") || value.startsWith("o1")
                || value.startsWith("o3") || value.startsWith("o4")
                || value.contains("codex") || value.startsWith("computer-use");
    }

    private static String liveSource(String provider) {
        if (AgentProviderType.OPENCODE.name().equals(provider)) return "opencode";
        if (AgentProviderType.KIRO_CLI.name().equals(provider)) return "acp";
        return "api";
    }

    private static String secret(String requestValue, String environmentValue) {
        String value = trim(requestValue);
        if (value != null && !SECRET_MASK.equals(value)) return value;
        return trim(environmentValue);
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > limit) throw new IOException("模型目录响应过大");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static String compact(String text) {
        String value = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return value.length() > 180 ? value.substring(0, 180) + "…" : value;
    }

    private static String trim(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
