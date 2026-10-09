package com.mola.cmd.proxy.app.acp.acpclient.model;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent provider model catalog shared by ACP runtime discovery and ConfigUI.
 * Live entries may expire and refresh; manually entered models are retained as
 * reusable history.
 */
public final class AgentModelCatalog {

    private static final Logger logger = LoggerFactory.getLogger(AgentModelCatalog.class);
    private static final long LIVE_TTL_MILLIS = 10L * 60L * 1000L;
    private static final AgentModelCatalog INSTANCE = new AgentModelCatalog(
            CmdProxyHome.resolve("configui", "model-catalog.json"));

    private final Path file;
    private JSONObject root;

    public AgentModelCatalog(Path file) {
        this.file = file.toAbsolutePath().normalize();
        this.root = load();
    }

    public static AgentModelCatalog getInstance() {
        return INSTANCE;
    }

    /** Capture model choices reported by session/new, session/load or set_config_option. */
    public synchronized void recordAcpResponse(AcpRobotParam robot, JsonObject response) {
        if (robot == null || response == null) return;
        JsonObject result = object(response, "result");
        LinkedHashMap<String, ModelEntry> models = new LinkedHashMap<>();
        JsonArray configOptions = result == null ? null : array(result, "configOptions");
        if (configOptions != null) {
            for (JsonElement element : configOptions) {
                if (!element.isJsonObject()) continue;
                JsonObject option = element.getAsJsonObject();
                String id = string(option, "id");
                String category = string(option, "category");
                if (!"model".equalsIgnoreCase(category)
                        && (id == null || !id.toLowerCase(java.util.Locale.ROOT).contains("model"))) {
                    continue;
                }
                collectOptions(option.get("options"), models);
            }
        }
        collectLegacyModels(result == null ? null : result.get("models"), models);
        collectLegacyModels(result == null ? null : result.get("availableModels"), models);
        if (!models.isEmpty()) {
            replaceLive(catalogKey(robot), new ArrayList<>(models.values()), "acp");
        }
    }

    public synchronized boolean isFresh(JSONObject request) {
        JSONObject catalog = catalogFor(request);
        if (catalog == null) return false;
        return System.currentTimeMillis() - catalog.getLongValue("refreshedAt") < LIVE_TTL_MILLIS;
    }

    public synchronized void replaceLive(JSONObject request, List<ModelEntry> models, String source) {
        replaceLive(catalogKey(request), models, source);
    }

    public synchronized void remember(JSONObject request, List<String> modelIds) throws IOException {
        String key = historyKey(request);
        JSONObject histories = histories();
        JSONArray values = histories.getJSONArray(key);
        if (values == null) values = new JSONArray();

        LinkedHashMap<String, JSONObject> merged = new LinkedHashMap<>();
        for (Object value : values) {
            if (!(value instanceof JSONObject)) continue;
            JSONObject item = (JSONObject) value;
            String id = trim(item.getString("id"));
            if (id != null) merged.put(id, item);
        }
        long now = System.currentTimeMillis();
        for (String raw : modelIds == null ? Collections.<String>emptyList() : modelIds) {
            String id = trim(raw);
            if (id == null) continue;
            JSONObject item = new JSONObject(true);
            item.put("id", id);
            item.put("lastUsedAt", now);
            merged.remove(id);
            merged.put(id, item);
        }
        JSONArray stored = new JSONArray();
        List<JSONObject> ordered = new ArrayList<>(merged.values());
        for (int i = Math.max(0, ordered.size() - 50); i < ordered.size(); i++) {
            stored.add(ordered.get(i));
        }
        histories.put(key, stored);
        persist();
    }

    public synchronized JSONObject snapshot(JSONObject request) {
        LinkedHashMap<String, JSONObject> merged = new LinkedHashMap<>();
        JSONArray history = histories().getJSONArray(historyKey(request));
        if (history != null) {
            for (Object value : history) {
                if (!(value instanceof JSONObject)) continue;
                String id = trim(((JSONObject) value).getString("id"));
                if (id != null) merged.put(id, modelJson(id, id, "history"));
            }
        }

        JSONObject catalog = catalogFor(request);
        if (catalog != null) {
            JSONArray live = catalog.getJSONArray("models");
            if (live != null) {
                for (Object value : live) {
                    if (!(value instanceof JSONObject)) continue;
                    JSONObject item = (JSONObject) value;
                    String id = trim(item.getString("id"));
                    if (id == null) continue;
                    JSONObject liveItem = modelJson(id,
                            trim(item.getString("name")) == null ? id : item.getString("name"),
                            trim(item.getString("source")) == null ? "live" : item.getString("source"));
                    merged.put(id, liveItem);
                }
            }
        }

        addConfigured(merged, request.getString("model"));
        addConfigured(merged, request.getString("memoryModel"));

        JSONObject result = new JSONObject(true);
        result.put("success", true);
        result.put("models", new JSONArray(new ArrayList<>(merged.values())));
        result.put("refreshedAt", catalog == null ? null : catalog.getLong("refreshedAt"));
        return result;
    }

    private void replaceLive(String key, List<ModelEntry> models, String source) {
        JSONObject item = new JSONObject(true);
        JSONArray values = new JSONArray();
        LinkedHashMap<String, ModelEntry> unique = new LinkedHashMap<>();
        for (ModelEntry model : models == null ? Collections.<ModelEntry>emptyList() : models) {
            if (model == null || trim(model.id) == null) continue;
            unique.put(model.id.trim(), model);
        }
        long now = System.currentTimeMillis();
        for (ModelEntry model : unique.values()) {
            JSONObject value = modelJson(model.id.trim(),
                    trim(model.name) == null ? model.id.trim() : model.name.trim(), source);
            value.put("lastSeenAt", now);
            values.add(value);
        }
        item.put("models", values);
        item.put("refreshedAt", now);
        catalogs().put(key, item);
        try {
            persist();
        } catch (IOException e) {
            logger.warn("模型目录持久化失败: {}", file, e);
        }
    }

    private JSONObject load() {
        if (!Files.isRegularFile(file)) return emptyRoot();
        try {
            JSONObject parsed = JSON.parseObject(new String(
                    Files.readAllBytes(file), StandardCharsets.UTF_8));
            return parsed == null ? emptyRoot() : parsed;
        } catch (Exception e) {
            logger.warn("模型目录读取失败，将使用空目录: {}", file, e);
            return emptyRoot();
        }
    }

    private void persist() throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporary, JSON.toJSONString(root, true).getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private JSONObject histories() {
        JSONObject value = root.getJSONObject("histories");
        if (value == null) {
            value = new JSONObject(true);
            root.put("histories", value);
        }
        return value;
    }

    private JSONObject catalogs() {
        JSONObject value = root.getJSONObject("catalogs");
        if (value == null) {
            value = new JSONObject(true);
            root.put("catalogs", value);
        }
        return value;
    }

    /**
     * Read old workspace-scoped Codex entries as a migration fallback. Codex
     * availability belongs to the selected Codex Home/account, not the project
     * directory, while providers such as OpenCode remain project-scoped.
     */
    private JSONObject catalogFor(JSONObject request) {
        JSONObject values = catalogs();
        JSONObject exact = values.getJSONObject(catalogKey(request));
        if (exact != null || !sharesLiveCatalogAcrossWorkspaces(request.getString("provider"))) {
            return exact;
        }
        String legacyPrefix = historyKey(request) + "|";
        JSONObject newest = null;
        long newestAt = Long.MIN_VALUE;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!entry.getKey().startsWith(legacyPrefix)
                    || !(entry.getValue() instanceof JSONObject)) {
                continue;
            }
            JSONObject candidate = (JSONObject) entry.getValue();
            long refreshedAt = candidate.getLongValue("refreshedAt");
            if (newest == null || refreshedAt > newestAt) {
                newest = candidate;
                newestAt = refreshedAt;
            }
        }
        return newest;
    }

    private static JSONObject emptyRoot() {
        JSONObject result = new JSONObject(true);
        result.put("histories", new JSONObject(true));
        result.put("catalogs", new JSONObject(true));
        return result;
    }

    private static String historyKey(JSONObject request) {
        return join(request.getString("provider"), request.getString("providerVersion"),
                endpoint(request), request.getString("codexHome"), request.getString("dshHome"));
    }

    private static String catalogKey(JSONObject request) {
        return historyKey(request) + "|" + catalogScope(
                request.getString("provider"), request.getString("workDir"));
    }

    private static String catalogKey(AcpRobotParam robot) {
        return join(robot.getAgentProvider(), robot.getProviderVersion(),
                "DEEPSEEK_HARNESS_ACP".equalsIgnoreCase(robot.getAgentProvider())
                        ? robot.getDeepSeekBaseUrl() : "EMBEDDED_PI_ACP".equalsIgnoreCase(robot.getAgentProvider()) && robot.getEmbeddedPi() != null
                        ? robot.getEmbeddedPi().getBaseUrl() : null,
                robot.getCodexHome(), robot.getDshHome())
                + "|" + catalogScope(robot.getAgentProvider(), robot.getWorkDir());
    }

    private static String catalogScope(String provider, String workDir) {
        return sharesLiveCatalogAcrossWorkspaces(provider) ? "shared" : value(workDir);
    }

    private static boolean sharesLiveCatalogAcrossWorkspaces(String provider) {
        return "CODEX_ACP".equalsIgnoreCase(provider);
    }

    private static String endpoint(JSONObject request) {
        if ("EMBEDDED_PI_ACP".equalsIgnoreCase(request.getString("provider"))) {
            JSONObject pi = request.getJSONObject("embeddedPi"); return pi == null ? null : pi.getString("baseUrl");
        }
        return "DEEPSEEK_HARNESS_ACP".equalsIgnoreCase(request.getString("provider"))
                ? request.getString("deepSeekBaseUrl") : null;
    }

    private static String join(String... values) {
        StringBuilder key = new StringBuilder();
        for (String item : values) {
            if (key.length() > 0) key.append('|');
            key.append(value(item));
        }
        return key.toString();
    }

    private static String value(String value) {
        return trim(value) == null ? "default" : value.trim();
    }

    private static void addConfigured(Map<String, JSONObject> merged, String raw) {
        String id = trim(raw);
        if (id == null || merged.containsKey(id)) return;
        merged.put(id, modelJson(id, id, "configured"));
    }

    private static JSONObject modelJson(String id, String name, String source) {
        JSONObject item = new JSONObject(true);
        item.put("id", id);
        item.put("name", name);
        item.put("source", source);
        return item;
    }

    private static void collectOptions(JsonElement element,
                                       LinkedHashMap<String, ModelEntry> models) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectOptions(child, models);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject option = element.getAsJsonObject();
        String id = string(option, "value");
        if (id != null) {
            String name = string(option, "name");
            if (name == null) name = string(option, "label");
            models.put(id, new ModelEntry(id, name == null ? id : name));
        }
        collectOptions(option.get("options"), models);
    }

    private static void collectLegacyModels(JsonElement element,
                                            LinkedHashMap<String, ModelEntry> models) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectLegacyModels(child, models);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        String id = firstString(object, "modelId", "id", "value", "model");
        if (id != null) {
            String name = firstString(object, "name", "label", "displayName");
            models.put(id, new ModelEntry(id, name == null ? id : name));
        }
        collectLegacyModels(object.get("availableModels"), models);
        collectLegacyModels(object.get("models"), models);
        collectLegacyModels(object.get("options"), models);
    }

    private static String firstString(JsonObject object, String... names) {
        for (String name : names) {
            String value = string(object, name);
            if (value != null) return value;
        }
        return null;
    }

    private static JsonObject object(JsonObject parent, String name) {
        JsonElement value = parent.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject parent, String name) {
        JsonElement value = parent.get(name);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private static String string(JsonObject parent, String name) {
        JsonElement value = parent.get(name);
        return value != null && value.isJsonPrimitive() ? trim(value.getAsString()) : null;
    }

    private static String trim(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    public static final class ModelEntry {
        public final String id;
        public final String name;

        public ModelEntry(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
