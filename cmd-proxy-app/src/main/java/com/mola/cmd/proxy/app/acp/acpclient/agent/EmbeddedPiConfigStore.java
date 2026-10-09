package com.mola.cmd.proxy.app.acp.acpclient.agent;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

/** Masking and credential merging performed under the existing configuration store lock. */
public final class EmbeddedPiConfigStore {
    private EmbeddedPiConfigStore() { }
    public static void mask(JSONObject root, String mask) {
        JSONArray robots = root.getJSONArray("robots"); if (robots == null) return;
        for (int i = 0; i < robots.size(); i++) {
            JSONObject robot = robots.getJSONObject(i); if (robot == null) continue;
            JSONObject pi = robot.getJSONObject("embeddedPi");
            if (pi != null && !blank(pi.getString("apiKey"))) pi.put("apiKey", mask);
        }
    }
    public static void merge(JSONObject submitted, JSONObject previous, String mask) {
        JSONArray robots = submitted.getJSONArray("robots"), oldRobots = previous.getJSONArray("robots");
        if (robots == null) return;
        Set<String> identities = new HashSet<>();
        for (int i = 0; i < robots.size(); i++) {
            JSONObject robot = robots.getJSONObject(i); if (robot == null) continue;
            JSONObject old = find(oldRobots, robot.getString("name"), null);
            if (blank(robot.getString("agentProvider"))) {
                robot.put("agentProvider", old == null ? "EMBEDDED_PI_ACP" : "KIRO_CLI");
            }
            JSONObject pi = robot.getJSONObject("embeddedPi");
            String copySource = robot.getString("_piCopyFromStateId"); robot.remove("_piCopyFromStateId");
            if (pi == null) {
                if (!"EMBEDDED_PI_ACP".equalsIgnoreCase(robot.getString("agentProvider"))) continue;
                pi = new JSONObject(true); robot.put("embeddedPi", pi);
            }
            String id = pi.getString("stateId");
            JSONObject source = !blank(copySource) ? find(oldRobots, null, copySource)
                    : !blank(id) ? find(oldRobots, null, id) : old;
            JSONObject oldPi = source == null ? null : source.getJSONObject("embeddedPi");
            if (blank(id) || !blank(copySource)) pi.put("stateId", blank(copySource) && oldPi != null && !blank(oldPi.getString("stateId"))
                    ? oldPi.getString("stateId") : UUID.randomUUID().toString());
            if (!identities.add(pi.getString("stateId"))) throw new IllegalArgumentException("内嵌 Pi 智能体不能共享 stateId");
            if (mask.equals(pi.getString("apiKey"))) {
                if (oldPi == null || blank(oldPi.getString("apiKey"))) throw new IllegalArgumentException("请重新输入内嵌 Pi API Key");
                pi.put("apiKey", oldPi.getString("apiKey"));
            }
            if (!pi.containsKey("baseUrl")) pi.put("baseUrl", "https://api.openai.com/v1");
            if (!pi.containsKey("contextWindow")) pi.put("contextWindow", 128000);
            if (!pi.containsKey("maxTokens")) pi.put("maxTokens", 8192);
            if ("EMBEDDED_PI_ACP".equalsIgnoreCase(robot.getString("agentProvider"))) {
                pi.toJavaObject(EmbeddedPiConfig.class).validate(robot.getString("model"));
            }
        }
    }
    private static JSONObject find(JSONArray robots, String name, String id) {
        if (robots != null) for (int i = 0; i < robots.size(); i++) {
            JSONObject robot = robots.getJSONObject(i); if (robot == null) continue;
            JSONObject pi = robot.getJSONObject("embeddedPi");
            if (name != null && name.equals(robot.getString("name")) || id != null && pi != null && id.equals(pi.getString("stateId"))) return robot;
        }
        return null;
    }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
