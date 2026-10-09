package com.mola.cmd.proxy.app.acp.acpclient.agent;

import java.net.URI;

/** Robot-scoped configuration; credentials never fall back to global Pi/OpenAI settings. */
public class EmbeddedPiConfig {
    private String stateId;
    private String baseUrl = "https://api.deepseek.com";
    private String apiKey;
    private int contextWindow = 1000000;
    private int maxTokens = 64000;

    public String getStateId() { return stateId; }
    public void setStateId(String value) { stateId = value; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey = value; }
    public int getContextWindow() { return contextWindow; }
    public void setContextWindow(int value) { contextWindow = value; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int value) { maxTokens = value; }

    public void validate(String model) {
        if (model == null || model.trim().isEmpty()) throw new IllegalArgumentException("内嵌 Pi 需要配置模型");
        if (apiKey == null || apiKey.trim().isEmpty() || "********".equals(apiKey)) {
            throw new IllegalArgumentException("内嵌 Pi 需要配置独立 API Key");
        }
        try {
            URI uri = new URI(baseUrl == null ? "" : baseUrl.trim());
            if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("API 地址必须是 HTTP/HTTPS Base URL");
            }
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("API 地址格式无效");
        }
        if (contextWindow < 1024 || maxTokens < 1 || (long) maxTokens + 512 >= contextWindow) {
            throw new IllegalArgumentException("上下文窗口必须大于最大输出 Token 数，并预留至少 512 Token");
        }
        if (stateId != null && !stateId.matches("[a-zA-Z0-9_-]{1,80}")) {
            throw new IllegalArgumentException("内嵌 Pi stateId 无效");
        }
    }
}
