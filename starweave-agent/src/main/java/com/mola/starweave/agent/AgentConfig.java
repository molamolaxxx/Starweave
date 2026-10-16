package com.mola.starweave.agent;

import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Immutable robot-scoped configuration. No global model credentials or JVM proxy mutations. */
public final class AgentConfig {
    public final String baseUrl, apiKey, model;
    public final Path agentDir;
    public final int contextWindow, maxTokens;
    public final Map<String, String> environment;
    public final AgentToolPolicy toolPolicy;
    public AgentConfig(String baseUrl, String apiKey, String model, Path agentDir,
                       int contextWindow, int maxTokens, Map<String, String> environment) {
        this(baseUrl, apiKey, model, agentDir, contextWindow, maxTokens, environment, AgentToolPolicy.UNRESTRICTED);
    }
    public AgentConfig(String baseUrl, String apiKey, String model, Path agentDir,
                       int contextWindow, int maxTokens, Map<String, String> environment, AgentToolPolicy toolPolicy) {
        this.toolPolicy = java.util.Objects.requireNonNull(toolPolicy, "toolPolicy");
        URI uri = URI.create(baseUrl.trim());
        if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("API 地址必须是 HTTP/HTTPS Base URL");
        if (apiKey == null || apiKey.trim().isEmpty() || model == null || model.trim().isEmpty())
            throw new IllegalArgumentException("请配置模型和 API Key");
        if (contextWindow < 1024 || maxTokens < 1 || (long) maxTokens + 512 >= contextWindow)
            throw new IllegalArgumentException("上下文窗口必须大于最大输出 Token 数并保留 512 Token");
        this.baseUrl = baseUrl.trim().replaceAll("/+$", ""); this.apiKey = apiKey.trim(); this.model = model.trim();
        this.agentDir = agentDir.toAbsolutePath().normalize(); this.contextWindow = contextWindow; this.maxTokens = maxTokens;
        this.environment = Collections.unmodifiableMap(new HashMap<>(environment));
    }
    String redact(String text) { return text == null ? "请求失败" : text.replace(apiKey, "[REDACTED]"); }
    Proxy proxy(URL target) {
        String bypass = env("NO_PROXY");
        String host = target.getHost();
        // Harness lives on loopback and must never be sent through an external proxy.
        if (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1")) return Proxy.NO_PROXY;
        for (String item : bypass.split(",")) {
            String domain = item.trim().replaceFirst(":\\d+$", "");
            if (domain.equals("*") || host.equalsIgnoreCase(domain) || (!domain.isEmpty() && host.endsWith(domain.startsWith(".") ? domain : "." + domain))) return Proxy.NO_PROXY;
        }
        String setting = env(target.getProtocol().equals("https") ? "HTTPS_PROXY" : "HTTP_PROXY");
        if (setting.isEmpty()) return Proxy.NO_PROXY;
        URI u = URI.create(setting.contains("://") ? setting : "http://" + setting);
        if (u.getHost() == null || (!"http".equalsIgnoreCase(u.getScheme()) && !"socks".equalsIgnoreCase(u.getScheme()) && !"socks5".equalsIgnoreCase(u.getScheme())))
            throw new IllegalArgumentException("不支持的代理地址");
        return new Proxy(u.getScheme().startsWith("socks") ? Proxy.Type.SOCKS : Proxy.Type.HTTP,
                new InetSocketAddress(u.getHost(), u.getPort() > 0 ? u.getPort() : u.getScheme().startsWith("socks") ? 1080 : 80));
    }
    private String env(String name) { String s = environment.get(name); return s == null ? environment.getOrDefault(name.toLowerCase(Locale.ROOT), "") : s; }
}
