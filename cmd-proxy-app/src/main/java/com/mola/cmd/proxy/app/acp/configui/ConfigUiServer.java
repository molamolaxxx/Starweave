package com.mola.cmd.proxy.app.acp.configui;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.mola.cmd.proxy.app.acp.channel.ChannelConfigFileStore;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelOutboundTarget;
import com.mola.cmd.proxy.app.acp.channel.archive.ChannelMessageArchive;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.agent.DeepSeekHarnessAcpProvider;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProviderType;
import com.mola.cmd.proxy.app.acp.acpclient.agent.NpmProviderRuntimeManager;
import com.mola.cmd.proxy.app.acp.acpclient.model.AgentModelCatalog;
import com.mola.cmd.proxy.app.acp.filepreview.TextFilePreviewResult;
import com.mola.cmd.proxy.app.acp.starweave.StarweaveSessionApiBridge;
import com.mola.cmd.proxy.app.acp.starweave.StarweaveRequestDeduplicator;
import com.mola.cmd.proxy.app.acp.starweave.StarweaveResourcePayload;
import com.mola.cmd.proxy.app.acp.starweave.StarweaveTeamApiBridge;
import com.mola.cmd.proxy.app.acp.gateway.AgentGatewayAdminBridge;
import com.mola.cmd.proxy.app.acp.gateway.model.AgentGatewayConfig;
import com.mola.cmd.proxy.app.acp.task.api.TaskApiBridge;
import com.mola.cmd.proxy.app.acp.task.api.ExternalTaskApiHandler;
import com.mola.cmd.proxy.app.acp.task.api.ExternalTaskApiService;
import com.mola.cmd.proxy.app.acp.task.api.TaskRestHandler;
import com.mola.cmd.proxy.app.acp.task.model.TaskException;
import com.mola.cmd.proxy.app.acp.schedule.ScheduleAdminApiBridge;
import com.mola.cmd.proxy.app.acp.team.TeamSharingStatusRegistry;
import com.mola.cmd.proxy.app.acp.common.InstanceRegistry;
import com.mola.cmd.proxy.app.acp.registry.RegistryManager;
import com.mola.cmd.proxy.app.acp.mcpauth.McpAuthManager;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;
import com.mola.cmd.proxy.client.conf.CmdProxyConf;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.BiFunction;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.Map;
import java.util.UUID;

/**
 * 内嵌轻量 HTTP 服务，提供 ACP 配置管理页面。
 * 基于 JDK 内置 com.sun.net.httpserver.HttpServer 实现。
 */
public class ConfigUiServer {

    private static final Logger logger = LoggerFactory.getLogger(ConfigUiServer.class);
    private static final String CONFIG_PATH =
            com.mola.cmd.proxy.app.utils.CmdProxyHome.pathOf("acpConfig.json");

    private static final String SECRET_MASK = "********";
    private static final Map<String, StaticAsset> CONFIG_UI_ASSETS = configUiAssets();

    private final int port;
    private final Runnable refreshCallback;
    private final BiConsumer<String, String> refreshRobotCallback;
    private final Supplier<java.util.Map<String, String>> channelStatusSupplier;
    private final Supplier<java.util.Map<String, String>> channelErrorSupplier;
    private final BiFunction<String, Boolean, Boolean> channelInboundUpdater;
    private final BiFunction<String, Boolean, Boolean> channelPrivateChatUpdater;
    private final Supplier<List<Map<String, Object>>> channelBindingTargetSupplier;
    private final BiConsumer<String, String> refreshChannelCallback;
    private final AgentResourceBrowser agentResourceBrowser = new AgentResourceBrowser();
    private final ProviderModelDiscoveryService providerModelDiscovery =
            new ProviderModelDiscoveryService(AgentModelCatalog.getInstance());
    private Function<String, Map<String, Boolean>> memoryDreamStatus =
            ignored -> { throw new IllegalStateException("记忆服务未运行"); };
    private Function<String, Boolean> memoryDreamTrigger =
            ignored -> { throw new IllegalStateException("记忆服务未运行"); };
    private final StarweaveRequestDeduplicator starweaveRequests =
            new StarweaveRequestDeduplicator();
    private HttpServer server;
    private RegistryManager registryManager;
    private com.mola.cmd.proxy.app.acp.team.coordinator.RegistryTeamCoordinator teamCoordinator;
    private final EnvironmentHttpProxy environmentProxy = new EnvironmentHttpProxy();
    private ExecutorService executor;
    private ExecutorService starweaveStreamExecutor;
    private ScheduledExecutorService updateCheckExecutor;
    private final Semaphore starweaveStreamSlots = new Semaphore(16);
    private final ConcurrentMap<String, Long> refreshingRobots = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> refreshingChannels = new ConcurrentHashMap<>();

    // 更新状态
    private static final long UPDATE_CHECK_INTERVAL_MINUTES = 10L;
    private final AtomicBoolean updating = new AtomicBoolean(false);
    private volatile String updateStatus = "idle"; // idle, checking, latest, downloading, done, error
    private volatile int updateProgress = 0;
    private volatile String updateMessage = "";
    private volatile boolean automaticUpdate = false;
    private volatile boolean updatePreparedForRestart = false;

    /**
     * @param port            监听端口
     * @param refreshCallback 用户点击“刷新服务”后触发 ACP 服务重载
     * @param refreshRobotCallback 按 robot 维度刷新的回调
     */
    public ConfigUiServer(int port, Runnable refreshCallback, Consumer<String> refreshRobotCallback) {
        this(port, refreshCallback, refreshRobotCallback,
                java.util.Collections::emptyMap, java.util.Collections::emptyMap,
                (channelId, enabled) -> {
                    throw new IllegalStateException("channel service is not running");
                }, (channelId, enabled) -> {
                    throw new IllegalStateException("channel service is not running");
                }, java.util.Collections::emptyList,
                (previousId, channelId) -> {
                    throw new IllegalStateException("channel service is not running");
                });
    }

    public ConfigUiServer(int port, Runnable refreshCallback,
                          BiConsumer<String, String> refreshRobotCallback) {
        this(port, refreshCallback, refreshRobotCallback,
                java.util.Collections::emptyMap, java.util.Collections::emptyMap,
                (channelId, enabled) -> {
                    throw new IllegalStateException("channel service is not running");
                }, (channelId, enabled) -> {
                    throw new IllegalStateException("channel service is not running");
                }, java.util.Collections::emptyList,
                (previousId, channelId) -> {
                    throw new IllegalStateException("channel service is not running");
                });
    }

    public ConfigUiServer(int port, Runnable refreshCallback,
                          Consumer<String> refreshRobotCallback,
                          Supplier<java.util.Map<String, String>> channelStatusSupplier,
                          Supplier<java.util.Map<String, String>> channelErrorSupplier) {
        this(port, refreshCallback, refreshRobotCallback, channelStatusSupplier,
                channelErrorSupplier, (channelId, enabled) -> {
                    throw new IllegalStateException("channel service is not running");
                }, (channelId, enabled) -> {
                    throw new IllegalStateException("channel service is not running");
                }, java.util.Collections::emptyList,
                (previousId, channelId) -> {
                    throw new IllegalStateException("channel service is not running");
                });
    }

    public ConfigUiServer(int port, Runnable refreshCallback,
                          Consumer<String> refreshRobotCallback,
                          Supplier<java.util.Map<String, String>> channelStatusSupplier,
                          Supplier<java.util.Map<String, String>> channelErrorSupplier,
                          BiFunction<String, Boolean, Boolean> channelInboundUpdater) {
        this(port, refreshCallback, refreshRobotCallback, channelStatusSupplier,
                channelErrorSupplier, channelInboundUpdater,
                (channelId, enabled) -> {
                    throw new IllegalStateException("channel service is not running");
                },
                java.util.Collections::emptyList,
                (previousId, channelId) -> {
                    throw new IllegalStateException("channel service is not running");
                });
    }

    public ConfigUiServer(int port, Runnable refreshCallback,
                          Consumer<String> refreshRobotCallback,
                          Supplier<java.util.Map<String, String>> channelStatusSupplier,
                          Supplier<java.util.Map<String, String>> channelErrorSupplier,
                          BiFunction<String, Boolean, Boolean> channelInboundUpdater,
                          Supplier<List<Map<String, Object>>> channelBindingTargetSupplier) {
        this(port, refreshCallback, refreshRobotCallback, channelStatusSupplier,
                channelErrorSupplier, channelInboundUpdater,
                (channelId, enabled) -> {
                    throw new IllegalStateException("channel service is not running");
                },
                channelBindingTargetSupplier,
                (previousId, channelId) -> {
                    throw new IllegalStateException("channel service is not running");
                });
    }

    public ConfigUiServer(int port, Runnable refreshCallback,
                          Consumer<String> refreshRobotCallback,
                          Supplier<java.util.Map<String, String>> channelStatusSupplier,
                          Supplier<java.util.Map<String, String>> channelErrorSupplier,
                          BiFunction<String, Boolean, Boolean> channelInboundUpdater,
                          BiFunction<String, Boolean, Boolean> channelPrivateChatUpdater,
                          Supplier<List<Map<String, Object>>> channelBindingTargetSupplier,
                          BiConsumer<String, String> refreshChannelCallback) {
        this(port, refreshCallback,
                (previousName, name) -> refreshRobotCallback.accept(name),
                channelStatusSupplier, channelErrorSupplier, channelInboundUpdater,
                channelPrivateChatUpdater, channelBindingTargetSupplier,
                refreshChannelCallback);
    }

    public ConfigUiServer(int port, Runnable refreshCallback,
                          BiConsumer<String, String> refreshRobotCallback,
                          Supplier<java.util.Map<String, String>> channelStatusSupplier,
                          Supplier<java.util.Map<String, String>> channelErrorSupplier,
                          BiFunction<String, Boolean, Boolean> channelInboundUpdater,
                          BiFunction<String, Boolean, Boolean> channelPrivateChatUpdater,
                          Supplier<List<Map<String, Object>>> channelBindingTargetSupplier,
                          BiConsumer<String, String> refreshChannelCallback) {
        this.port = port;
        this.refreshCallback = refreshCallback;
        this.refreshRobotCallback = refreshRobotCallback;
        this.channelStatusSupplier = channelStatusSupplier;
        this.channelErrorSupplier = channelErrorSupplier;
        this.channelInboundUpdater = channelInboundUpdater;
        this.channelPrivateChatUpdater = channelPrivateChatUpdater;
        this.channelBindingTargetSupplier = channelBindingTargetSupplier;
        this.refreshChannelCallback = refreshChannelCallback;
    }

    public void setMemoryDreamHandler(Function<String, Map<String, Boolean>> status,
                                      Function<String, Boolean> trigger) {
        this.memoryDreamStatus = java.util.Objects.requireNonNull(status);
        this.memoryDreamTrigger = java.util.Objects.requireNonNull(trigger);
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        executor = Executors.newFixedThreadPool(4);
        starweaveStreamExecutor = Executors.newFixedThreadPool(16, runnable -> {
            Thread thread = new Thread(runnable, "starweave-session-stream");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);

        // ConfigUI 自带静态资源。仅暴露明确登记的资源，避免把 classpath 变成文件服务。
        server.createContext("/assets/", this::handleStaticAsset);
        // 静态页面
        server.createContext("/", this::handleIndex);
        // 环境列表（不代理，始终由本进程扫描主机级注册表）
        server.createContext("/api/instances", this::handleInstances);
        registryManager = createRegistryManager(server.getAddress().getPort());
        teamCoordinator = new com.mola.cmd.proxy.app.acp.team.coordinator.RegistryTeamCoordinator(
                registryManager, registryManager.coordinationDirectory());
        server.createContext(com.mola.cmd.proxy.app.acp.team.coordinator.RegistryTeamCoordinator.CENTER_PATH,
                teamCoordinator::handleCenter);
        server.createContext(com.mola.cmd.proxy.app.acp.team.coordinator.RegistryTeamCoordinator.PARTICIPANT_PATH,
                teamCoordinator::handleParticipant);
        // 注册控制属于接收进程；系统设置操作跟随当前选中的环境。
        server.createContext("/api/registry/", registryManager::handleControl);
        server.createContext("/api/registry/access", proxied(registryManager::handleAccess));
        server.createContext("/api/registry/settings", proxied(registryManager::handleAdmin));
        // Public endpoint: authentication is handled by its configured Bearer code.
        // Deliberately do not proxy by ConfigUI's instance query parameter.
        server.createContext(ExternalTaskApiHandler.PREFIX,
                new ExternalTaskApiHandler(new ExternalTaskApiService(
                        Paths.get(CONFIG_PATH), TaskApiBridge::getService)));
        server.createContext(TaskRestHandler.PREFIX, proxied(this::handleTasks));
        server.createContext("/api/schedules/v1/", proxied(this::handleSchedules));
        server.createContext("/api/observations/v1/", proxied(this::handleObservations));
        // REST API：带 instance 参数且非本环境时，转发到目标环境的 ConfigUI
        server.createContext("/api/config", proxied(this::handleConfig));
        server.createContext("/api/channels/status", proxied(this::handleChannelStatus));
        server.createContext("/api/channels/inbound", proxied(this::handleChannelInbound));
        server.createContext("/api/channels/private-chat",
                proxied(this::handleChannelPrivateChat));
        server.createContext("/api/channels/binding-targets",
                proxied(this::handleChannelBindingTargets));
        server.createContext("/api/agent-gateways/targets",
                proxied(this::handleAgentGatewayTargets));
        server.createContext("/api/agent-gateways/status",
                proxied(this::handleAgentGatewayStatus));
        server.createContext("/api/agent-gateways/auth-code",
                proxied(this::handleAgentGatewayAuthCode));
        server.createContext("/api/channels/v1/messages",
                proxied(this::handleChannelMessages));
        server.createContext("/api/external-task-apis/auth-code",
                proxied(this::handleExternalTaskApiAuthCode));
        server.createContext("/api/team/sharing-status",
                proxied(this::handleTeamSharingStatus));
        server.createContext("/api/mcp-auth/v1/servers", proxied(this::handleMcpServers));
        server.createContext("/api/mcp-auth/v1/principals", proxied(this::handleMcpPrincipals));
        server.createContext("/api/mcp-auth/v1/policies", proxied(this::handleMcpPolicies));
        server.createContext("/api/refresh", proxied(this::handleRefresh));
        server.createContext("/api/refresh-robot", proxied(this::handleRefreshRobot));
        server.createContext("/api/refresh-channel", proxied(this::handleRefreshChannel));
        server.createContext("/api/item-refresh-status",
                proxied(this::handleItemRefreshStatus));
        server.createContext("/api/browse-dir", proxied(this::handleBrowseDir));
        server.createContext("/api/agent-resources/export",
                proxied(exchange -> handleAgentResourceMigration(exchange, false)));
        server.createContext("/api/agent-resources/import",
                proxied(exchange -> handleAgentResourceMigration(exchange, true)));
        server.createContext("/api/agent-resources/tree",
                proxied(this::handleAgentResourceTree));
        server.createContext("/api/agent-resources/content",
                proxied(this::handleAgentResourceContent));
        server.createContext("/api/agent-resources/dream",
                proxied(this::handleAgentMemoryDream));
        server.createContext("/api/starweave/v1/sessions",
                proxied(this::handleStarweaveSessions));
        server.createContext("/api/starweave/v1/sessions/open",
                proxied(exchange -> handleStarweaveCommand(exchange, "open")));
        server.createContext("/api/starweave/v1/sessions/messages",
                proxied(exchange -> handleStarweaveCommand(exchange, "messages")));
        server.createContext("/api/starweave/v1/sessions/uploads",
                proxied(this::handleStarweaveUpload));
        server.createContext("/api/starweave/v1/sessions/resources",
                proxied(exchange -> handleStarweaveResources(exchange, "list")));
        server.createContext("/api/starweave/v1/sessions/resources/preview",
                proxied(exchange -> handleStarweaveResources(exchange, "preview")));
        server.createContext("/api/starweave/v1/sessions/resources/download",
                proxied(exchange -> handleStarweaveResources(exchange, "download")));
        server.createContext("/api/starweave/v1/sessions/file-preview",
                proxied(exchange -> handleStarweaveFilePreview(exchange, false)));
        server.createContext("/api/starweave/v1/sessions/cancel",
                proxied(exchange -> handleStarweaveCommand(exchange, "cancel")));
        server.createContext("/api/starweave/v1/sessions/new",
                proxied(exchange -> handleStarweaveCommand(exchange, "new")));
        server.createContext("/api/starweave/v1/sessions/restore",
                proxied(exchange -> handleStarweaveCommand(exchange, "restore")));
        server.createContext("/api/starweave/v1/sessions/delete",
                proxied(exchange -> handleStarweaveCommand(exchange, "delete")));
        server.createContext("/api/starweave/v1/sessions/events",
                proxied(this::handleStarweaveEvents));
        server.createContext("/api/starweave/v1/sessions/stream",
                this::handleStarweaveStream);
        server.createContext("/api/starweave/v1/teams",
                proxied(exchange -> handleStarweaveTeams(exchange, "list")));
        server.createContext("/api/starweave/v1/teams/sources",
                proxied(exchange -> handleStarweaveTeams(exchange, "sources")));
        server.createContext("/api/starweave/v1/teams/create",
                proxied(exchange -> handleStarweaveTeams(exchange, "create")));
        server.createContext("/api/starweave/v1/teams/update",
                proxied(exchange -> handleStarweaveTeams(exchange, "update")));
        server.createContext("/api/starweave/v1/teams/delete",
                proxied(exchange -> handleStarweaveTeams(exchange, "delete")));
        server.createContext("/api/starweave/v1/teams/member",
                proxied(exchange -> handleStarweaveTeams(exchange, "member")));
        server.createContext("/api/starweave/v1/teams/uploads",
                proxied(exchange -> handleStarweaveTeams(exchange, "upload")));
        server.createContext("/api/starweave/v1/teams/events",
                proxied(exchange -> handleStarweaveTeams(exchange, "events")));
        server.createContext("/api/starweave/v1/teams/stream",
                this::handleStarweaveTeamStream);
        server.createContext("/api/starweave/v1/teams/resources",
                proxied(exchange -> handleStarweaveTeamResources(exchange, "list")));
        server.createContext("/api/starweave/v1/teams/resources/preview",
                proxied(exchange -> handleStarweaveTeamResources(exchange, "preview")));
        server.createContext("/api/starweave/v1/teams/resources/download",
                proxied(exchange -> handleStarweaveTeamResources(exchange, "download")));
        server.createContext("/api/starweave/v1/teams/file-preview",
                proxied(exchange -> handleStarweaveFilePreview(exchange, true)));
        server.createContext("/api/update-jar", proxied(this::handleUpdateJar));
        server.createContext("/api/update-jar/status", proxied(this::handleUpdateJarStatus));
        server.createContext("/api/provider-runtime/releases",
                proxied(this::handleProviderRuntimeReleases));
        server.createContext("/api/provider-runtime/status",
                proxied(this::handleProviderRuntimeStatus));
        server.createContext("/api/provider-runtime/install",
                proxied(this::handleProviderRuntimeInstall));
        server.createContext("/api/provider-runtime/job",
                proxied(this::handleProviderRuntimeJob));
        server.createContext("/api/provider-models",
                proxied(this::handleProviderModels));

        server.start();
        registryManager.start();
        teamCoordinator.start();
        updateCheckExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jar-update-checker");
            thread.setDaemon(true);
            return thread;
        });
        updateCheckExecutor.scheduleWithFixedDelay(
                () -> requestJarUpdate(true),
                UPDATE_CHECK_INTERVAL_MINUTES,
                UPDATE_CHECK_INTERVAL_MINUTES,
                TimeUnit.MINUTES);
        logger.info("ConfigUI 已启动: http://localhost:{}", port);
    }

    private void handleMcpRegister(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "application/json", "{\"success\":false,\"code\":\"METHOD_NOT_ALLOWED\"}");
            return;
        }
        JSONObject result;
        try { result = McpAuthManager.getInstance().register(JSON.parseObject(readBody(exchange))); }
        catch (Exception e) { result = apiError("INVALID_REQUEST", e.getMessage()); }
        int status = "AUTH_SESSION_NOT_FOUND".equals(result.getString("code")) ? 404
                : result.getBooleanValue("success") ? 200 : 400;
        sendResponse(exchange, status, "application/json", JSON.toJSONString(result));
    }

    private void handleMcpCheck(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "application/json", "{\"success\":false,\"code\":\"METHOD_NOT_ALLOWED\"}");
            return;
        }
        JSONObject result;
        try { result = McpAuthManager.getInstance().check(JSON.parseObject(readBody(exchange))); }
        catch (Exception e) { result = apiError("INVALID_REQUEST", e.getMessage()); }
        sendResponse(exchange, result.getBooleanValue("success") ? 200 : 400,
                "application/json", JSON.toJSONString(result));
    }

    private void handleMcpServers(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed"); return;
        }
        JSONObject result = new JSONObject(true);
        result.put("servers", McpAuthManager.getInstance().serverSnapshot());
        sendResponse(exchange, 200, "application/json", JSON.toJSONString(result));
    }

    private void handleMcpPrincipals(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed"); return;
        }
        JSONObject result = new JSONObject(true);
        result.put("principals", McpAuthManager.getInstance().principalSnapshot());
        sendResponse(exchange, 200, "application/json", JSON.toJSONString(result));
    }

    private void handleMcpPolicies(HttpExchange exchange) throws IOException {
        if (!"PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed"); return;
        }
        JSONObject result;
        try { result = McpAuthManager.getInstance().updatePolicy(JSON.parseObject(readBody(exchange))); }
        catch (Exception e) { result = apiError("INVALID_REQUEST", e.getMessage()); }
        sendResponse(exchange, result.getBooleanValue("success") ? 200 : 400,
                "application/json", JSON.toJSONString(result));
    }

    private JSONObject apiError(String code, String message) {
        JSONObject result = new JSONObject(true); result.put("success", false);
        result.put("code", code); result.put("message", message == null ? "invalid request" : message);
        return result;
    }

    public void stop() {
        if (teamCoordinator != null) teamCoordinator.close();
        if (registryManager != null) registryManager.close();
        environmentProxy.close();
        if (updateCheckExecutor != null) {
            updateCheckExecutor.shutdownNow();
        }
        if (server != null) {
            server.stop(0);
            logger.info("ConfigUI 已停止");
        }
        if (starweaveStreamExecutor != null) {
            starweaveStreamExecutor.shutdownNow();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    int getBoundPort() {
        HttpServer current = server;
        if (current == null) throw new IllegalStateException("ConfigUI is not running");
        return current.getAddress().getPort();
    }

    /** 测试可覆盖注册服务的创建，隔离真实环境的密码、登记数据和自动连接。 */
    RegistryManager createRegistryManager(int boundPort) throws IOException {
        return new RegistryManager(Paths.get(CmdProxyHome.pathOf("registry")),
                boundPort, CmdProxyHome.instanceId());
    }

    // ==================== 多环境：环境列表与跨环境代理 ====================

    /** 代理请求标记，避免转发成环 */
    private static final String PROXY_HEADER = "X-Cmd-Proxy-Proxied";

    /**
     * 包装 handler：请求带 {@code instance} 参数且指向其它环境时，
     * 转发到该环境 ConfigUI 的同名接口，否则按本地逻辑处理。
     * 这样前端始终同源，无需 CORS，且 refresh/update-jar 这类必须在目标进程内执行的操作也能正确落地。
     */
    private com.sun.net.httpserver.HttpHandler proxied(com.sun.net.httpserver.HttpHandler local) {
        return exchange -> {
            String target = param(exchange, "instance");
            boolean alreadyProxied = exchange.getRequestHeaders().getFirst(PROXY_HEADER) != null;
            if (target == null || target.isEmpty() || alreadyProxied
                    || target.equals(CmdProxyHome.instanceId())) {
                if (alreadyProxied && !exchange.getRequestURI().getPath().equals("/api/registry/access")
                        && !registryManager.allowEnvironmentAccess(exchange)) return;
                local.handle(exchange);
                return;
            }
            forward(exchange, target);
        };
    }

    /** Resolve the current service for each request so reload never retains a closed database. */
    private void handleTasks(HttpExchange exchange) throws IOException {
        com.mola.cmd.proxy.app.acp.task.service.TaskService service;
        try {
            service = TaskApiBridge.getService();
        } catch (TaskException unavailable) {
            sendResponse(exchange, unavailable.getHttpStatus(), "application/json",
                    TaskRestHandler.envelope(false, unavailable.getCode(),
                            unavailable.getMessage(), unavailable.getData()).toJSONString());
            return;
        }
        new TaskRestHandler(service).handle(exchange);
    }

    private void handleObservations(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        try {
            com.mola.cmd.proxy.app.acp.observation.ObservationManager manager =
                    com.mola.cmd.proxy.app.acp.observation.ObservationAdminApiBridge.current();
            com.google.gson.JsonObject body = new com.google.gson.JsonObject();
            if ("POST".equals(method) || "PUT".equals(method)) {
                String raw = new String(readAllBytes(exchange.getRequestBody()), StandardCharsets.UTF_8);
                if (!raw.trim().isEmpty()) body = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
            }
            com.google.gson.JsonObject result;
            String channel = param(exchange, "channelId"), owner = param(exchange, "ownerPath");
            if ("/api/observations/v1/channels".equals(path)) {
                if ("GET".equals(method)) result = channel != null ? manager.get(channel, null) : manager.list(owner,
                        param(exchange, "query"), param(exchange, "status"), intParam(exchange, "page", 1), intParam(exchange, "pageSize", 10));
                else if ("POST".equals(method)) result = manager.create(body.has("ownerPath") ? body.get("ownerPath").getAsString() : "", body);
                else if ("PUT".equals(method)) result = manager.update(channel, null, body);
                else if ("DELETE".equals(method)) result = manager.delete(channel, null);
                else { sendResponse(exchange, 405, "application/json", "{\"message\":\"Method Not Allowed\"}"); return; }
            } else if ("/api/observations/v1/test".equals(path) && "POST".equals(method)) {
                result = manager.test(body.has("ownerPath") ? body.get("ownerPath").getAsString() : "", body);
            } else if ("/api/observations/v1/events".equals(path) && "GET".equals(method)) {
                result = manager.events(owner, channel, param(exchange, "status"), param(exchange, "eventId"), intParam(exchange,"page",1),intParam(exchange,"pageSize",10));
            } else if ("/api/observations/v1/retry".equals(path) && "POST".equals(method)) {
                result = manager.retry(channel, param(exchange,"eventId"));
            } else if ("/api/observations/v1/owners".equals(path) && "GET".equals(method)) result = manager.owners();
            else if ("/api/observations/v1/catalog".equals(path) && "GET".equals(method)) result = manager.catalog();
            else if ("/api/observations/v1/stats".equals(path) && "GET".equals(method)) result = manager.stats();
            else { sendResponse(exchange,"GET".equals(method)?404:405,"application/json","{\"message\":\"观测接口不存在\"}"); return; }
            sendResponse(exchange,200,"application/json",result.toString());
        } catch (IllegalArgumentException error) {
            sendResponse(exchange,400,"application/json",observationError(error.getMessage()));
        } catch (Exception error) {
            logger.warn("观测台接口失败",error);
            sendResponse(exchange,503,"application/json",observationError("观测服务不可用"));
        }
    }

    private static String observationError(String message) {
        com.google.gson.JsonObject result = new com.google.gson.JsonObject(); result.addProperty("message",message); return result.toString();
    }

    private void handleSchedules(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        try {
            JSONObject result;
            if ("/api/schedules/v1/tasks".equals(path)) {
                if ("GET".equals(method)) {
                    result = ScheduleAdminApiBridge.listTasks(
                            intParam(exchange, "page", 1), intParam(exchange, "pageSize", 10),
                            param(exchange, "query"), param(exchange, "scope"),
                            param(exchange, "type"), param(exchange, "status"));
                } else if ("PUT".equals(method)) {
                    JSONObject body = JSON.parseObject(new String(
                            readAllBytes(exchange.getRequestBody()), StandardCharsets.UTF_8));
                    result = ScheduleAdminApiBridge.update(param(exchange, "ownerPath"),
                            param(exchange, "taskId"), body == null ? new JSONObject() : body);
                } else if ("DELETE".equals(method)) {
                    result = ScheduleAdminApiBridge.delete(param(exchange, "ownerPath"),
                            param(exchange, "taskId"));
                } else {
                    sendResponse(exchange, 405, "application/json",
                            "{\"ok\":false,\"code\":\"METHOD_NOT_ALLOWED\",\"message\":\"Method Not Allowed\"}");
                    return;
                }
            } else if ("/api/schedules/v1/executions".equals(path) && "GET".equals(method)) {
                result = ScheduleAdminApiBridge.executions(
                        intParam(exchange, "page", 1), intParam(exchange, "pageSize", 10),
                        param(exchange, "ownerPath"), param(exchange, "taskId"),
                        param(exchange, "status"));
            } else if ("/api/schedules/v1/stats".equals(path) && "GET".equals(method)) {
                result = ScheduleAdminApiBridge.stats();
            } else {
                sendResponse(exchange, "GET".equals(method) ? 404 : 405,
                        "application/json", "{\"ok\":false,\"code\":\"NOT_FOUND\","
                                + "\"message\":\"定时任务接口不存在\"}");
                return;
            }
            sendResponse(exchange, 200, "application/json", result.toJSONString());
        } catch (ScheduleAdminApiBridge.ApiException error) {
            JSONObject result = new JSONObject(true);
            result.put("ok", false);
            result.put("code", error.getCode());
            result.put("message", error.getMessage());
            sendResponse(exchange, error.getStatus(), "application/json", result.toJSONString());
        } catch (IllegalArgumentException error) {
            JSONObject result = new JSONObject(true);
            result.put("ok", false);
            result.put("code", "INVALID_ARGUMENT");
            result.put("message", error.getMessage());
            sendResponse(exchange, 400, "application/json", result.toJSONString());
        } catch (Exception error) {
            logger.error("定时任务管理接口失败, path={}", path, error);
            JSONObject result = new JSONObject(true);
            result.put("ok", false);
            result.put("code", "INTERNAL_ERROR");
            result.put("message", "定时任务管理失败");
            sendResponse(exchange, 500, "application/json", result.toJSONString());
        }
    }

    private int intParam(HttpExchange exchange, String name, int fallback) throws IOException {
        String value = param(exchange, name);
        if (value == null || value.trim().isEmpty()) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " 必须是整数");
        }
    }

    /** 环境列表：主机上所有存活环境，供前端渲染环境页签 */
    private void handleInstances(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        List<InstanceRegistry.InstanceInfo> instances = InstanceRegistry.listAll();
        if (registryManager != null) instances.addAll(registryManager.environments());
        sendResponse(exchange, 200, "application/json",
                JSON.toJSONString(instances, SerializerFeature.DisableCircularReferenceDetect));
    }

    /** 本机和跨主机环境均复用同一通用代理。 */
    private void forward(HttpExchange exchange, String instanceId) throws IOException {
        int targetPort = resolveEnvironmentPort(exchange, instanceId);
        if (targetPort > 0) environmentProxy.forward(exchange, targetPort);
    }

    private int resolveEnvironmentPort(HttpExchange exchange, String instanceId) throws IOException {
        if (registryManager != null) {
            try {
                int remotePort = registryManager.resolve(instanceId);
                if (remotePort > 0) return remotePort;
            } catch (IllegalStateException e) {
                sendResponse(exchange, 503, "application/json", JSON.toJSONString(apiError("ENVIRONMENT_OFFLINE", "目标环境离线")));
                return 0;
            }
        }
        for (InstanceRegistry.InstanceInfo info : InstanceRegistry.listAll()) {
            if (!instanceId.equals(info.instanceId)) continue;
            if (info.configUiPort > 0) return info.configUiPort;
            sendResponse(exchange, 409, "application/json", JSON.toJSONString(apiError("CONFIG_UI_DISABLED", "目标环境未开启配置页")));
            return 0;
        }
        sendResponse(exchange, 404, "application/json", JSON.toJSONString(apiError("INSTANCE_NOT_FOUND", "环境不存在或已退出")));
        return 0;
    }

    /** 读取 query 参数（已 URL 解码） */
    private String param(HttpExchange exchange, String key) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && key.equals(kv[0])) {
                return java.net.URLDecoder.decode(kv[1], "UTF-8");
            }
        }
        return null;
    }

    private String jsonEscape(String raw) {
        return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try (InputStream is = getClass().getResourceAsStream("/configui/index.html")) {
            if (is == null) {
                sendResponse(exchange, 404, "text/plain", "Page not found");
                return;
            }
            byte[] bytes = readAllBytes(is);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        }
    }

    private void handleStaticAsset(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String requestPath = exchange.getRequestURI().getPath();
        StaticAsset asset = CONFIG_UI_ASSETS.get(requestPath);
        if (asset == null) {
            sendResponse(exchange, 404, "text/plain", "Asset not found");
            return;
        }
        try (InputStream input = getClass().getResourceAsStream(asset.resourcePath)) {
            if (input == null) {
                sendResponse(exchange, 404, "text/plain", "Asset not found");
                return;
            }
            byte[] bytes = readAllBytes(input);
            exchange.getResponseHeaders().set("Content-Type", asset.contentType);
            exchange.getResponseHeaders().set("Cache-Control", asset.cacheControl);
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            if ("HEAD".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.getResponseBody().close();
        }
    }

    private static Map<String, StaticAsset> configUiAssets() {
        Map<String, StaticAsset> assets = new java.util.LinkedHashMap<>();
        addConfigUiAsset(assets, "MaterialIcons-Regular.woff2", "font/woff2", true);
        addConfigUiAsset(assets, "css/base.css", "text/css; charset=utf-8", false);
        addConfigUiAsset(assets, "css/channels.css", "text/css; charset=utf-8", false);
        addConfigUiAsset(assets, "css/starweave.css", "text/css; charset=utf-8", false);
        addConfigUiAsset(assets, "css/tasks.css", "text/css; charset=utf-8", false);
        addConfigUiAsset(assets, "css/resources.css", "text/css; charset=utf-8", false);
        addConfigUiAsset(assets, "css/responsive.css", "text/css; charset=utf-8", false);
        addConfigUiAsset(assets, "css/dark-theme.css", "text/css; charset=utf-8", false);
        addConfigUiAsset(assets, "css/schedules.css", "text/css; charset=utf-8", false);
        addConfigUiAsset(assets, "css/observations.css", "text/css; charset=utf-8", false);
        addConfigUiAsset(assets, "js/theme.js", "application/javascript; charset=utf-8", false);
        addConfigUiAsset(assets, "js/registry.js", "application/javascript; charset=utf-8", false);
        addConfigUiAsset(assets, "js/core.js", "application/javascript; charset=utf-8", false);
        addConfigUiAsset(assets, "js/mcp-auth.js", "application/javascript; charset=utf-8", false);
        addConfigUiAsset(assets, "js/providers.js", "application/javascript; charset=utf-8", false);
        addConfigUiAsset(assets, "js/chat-history.js", "application/javascript; charset=utf-8", false);
        addConfigUiAsset(assets, "js/starweave.js", "application/javascript; charset=utf-8", false);
        addConfigUiAsset(assets, "js/resources.js", "application/javascript; charset=utf-8", false);
        addConfigUiAsset(assets, "js/channels.js", "application/javascript; charset=utf-8", false);
        addConfigUiAsset(assets, "js/agents.js", "application/javascript; charset=utf-8", false);
        addConfigUiAsset(assets, "js/tasks.js", "application/javascript; charset=utf-8", false);
        addConfigUiAsset(assets, "js/schedules.js", "application/javascript; charset=utf-8", false);
        addConfigUiAsset(assets, "js/observations.js", "application/javascript; charset=utf-8", false);
        addConfigUiAsset(assets, "js/ui.js", "application/javascript; charset=utf-8", false);
        addConfigUiAsset(assets, "js/app.js", "application/javascript; charset=utf-8", false);
        return java.util.Collections.unmodifiableMap(assets);
    }

    private static void addConfigUiAsset(Map<String, StaticAsset> assets, String relativePath,
                                         String contentType, boolean immutable) {
        assets.put("/assets/" + relativePath,
                new StaticAsset("/configui/assets/" + relativePath, contentType,
                        immutable ? "public, max-age=31536000, immutable" : "no-cache"));
    }

    private static final class StaticAsset {
        private final String resourcePath;
        private final String contentType;
        private final String cacheControl;

        private StaticAsset(String resourcePath, String contentType, String cacheControl) {
            this.resourcePath = resourcePath;
            this.contentType = contentType;
            this.cacheControl = cacheControl;
        }
    }

    private void handleConfig(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        switch (method) {
            case "GET":
                handleGetConfig(exchange);
                break;
            case "POST":
                handlePostConfig(exchange);
                break;
            default:
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
        }
    }

    private void handleGetConfig(HttpExchange exchange) throws IOException {
        Path path = Paths.get(CONFIG_PATH);
        if (!Files.exists(path)) {
            sendResponse(exchange, 200, "application/json", "{}");
            return;
        }
        String raw = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        // 通过 fastjson 解析再序列化，确保输出标准 JSON（消除尾逗号等非标准语法）
        JSONObject json = JSON.parseObject(raw);
        appendDshRuntimeProjection(json);
        appendProviderRuntimeProjection(json);
        maskChannelSecrets(json);
        String content = JSON.toJSONString(json, SerializerFeature.PrettyFormat);
        sendResponse(exchange, 200, "application/json", content);
    }

    private void handlePostConfig(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        if (body == null || body.trim().isEmpty()) {
            sendResponse(exchange, 400, "application/json", "{\"error\":\"empty body\"}");
            return;
        }
        // 格式化写入
        JSONObject json = JSON.parseObject(body);
        if (json.getJSONArray("robots") != null) {
            java.util.Set<String> robotNames = new java.util.HashSet<>();
            for (int i = 0; i < json.getJSONArray("robots").size(); i++) {
                JSONObject robot = json.getJSONArray("robots").getJSONObject(i);
                if (robot != null) {
                    robot.remove("_dshProfileBundles");
                    robot.remove("_dshProfileError");
                    robot.remove("_providerRuntime");
                }
                if (robot != null && robot.getBooleanValue("onlySubAgent")
                        && robot.getBooleanValue("onlyTeamMember")) {
                    sendResponse(exchange, 400, "application/json",
                            "{\"error\":\"onlySubAgent and onlyTeamMember cannot both be true\"}");
                    return;
                }
                String robotName = robot == null ? "" : robot.getString("name");
                robotName = robotName == null ? "" : robotName.trim();
                if (robotName.isEmpty()) {
                    sendResponse(exchange, 400, "application/json",
                            "{\"error\":\"robot name is required\"}");
                    return;
                }
                if (!robotNames.add(robotName)) {
                    JSONObject error = new JSONObject(true);
                    error.put("error", "duplicate robot name: " + robotName);
                    sendResponse(exchange, 400, "application/json",
                            JSON.toJSONString(error));
                    return;
                }
                robot.put("name", robotName);
            }
        }
        // 全局代理已改为批量操作；清理旧配置字段，避免继续维护独立状态。
        json.remove("globalProxyEnabled");
        String channelTargetError = validateChannelConversationMappings(json);
        if (channelTargetError == null) channelTargetError = validateChannelOutboundTargets(json);
        if (channelTargetError != null) {
            JSONObject error = new JSONObject(true);
            error.put("error", channelTargetError);
            sendResponse(exchange, 400, "application/json", JSON.toJSONString(error));
            return;
        }
        String externalTaskApiError = validateExternalTaskApis(json);
        if (externalTaskApiError != null) {
            JSONObject error = new JSONObject(true);
            error.put("error", externalTaskApiError);
            sendResponse(exchange, 400, "application/json", JSON.toJSONString(error));
            return;
        }
        String agentGatewayError = validateAgentGateways(json);
        if (agentGatewayError != null) {
            JSONObject error = new JSONObject(true);
            error.put("error", agentGatewayError);
            sendResponse(exchange, 400, "application/json", JSON.toJSONString(error));
            return;
        }
        String captainEntryError = validateCaptainTeamEntrypoints(json);
        if (captainEntryError != null) {
            JSONObject error = new JSONObject(true);
            error.put("error", captainEntryError);
            sendResponse(exchange, 400, "application/json", JSON.toJSONString(error));
            return;
        }
        try {
            ChannelConfigFileStore.saveUiConfig(json, SECRET_MASK);
        } catch (IllegalArgumentException e) {
            JSONObject error = new JSONObject(true);
            error.put("error", e.getMessage());
            sendResponse(exchange, 400, "application/json", JSON.toJSONString(error));
            return;
        }
        sendResponse(exchange, 200, "application/json", "{\"ok\":true}");
    }

    String validateChannelConversationMappings(JSONObject root) {
        com.alibaba.fastjson.JSONArray channels = root.getJSONArray("channels");
        if (channels == null) return null;
        List<Map<String, Object>> teams = null;
        for (int i = 0; i < channels.size(); i++) {
            JSONObject channel = channels.getJSONObject(i);
            JSONObject value = channel == null ? null : channel.getJSONObject("binding");
            if (value == null) continue;
            com.mola.cmd.proxy.app.acp.channel.model.ChannelBinding binding;
            try {
                binding = value.toJavaObject(com.mola.cmd.proxy.app.acp.channel.model.ChannelBinding.class);
            } catch (RuntimeException e) {
                return "channel binding is invalid";
            }
            if (!binding.usesConversationMapping()) continue;
            if (trimmed(binding.getTeamId()).isEmpty()) return "binding.teamId is required";
            if (!trimmed(binding.getTeamMemberId()).isEmpty()) return "conversation mapping binding cannot contain teamMemberId";
            if (teams == null) {
                try { teams = channelBindingTargetSupplier.get(); }
                catch (RuntimeException e) { return "Team binding targets are unavailable"; }
                if (teams == null) teams = java.util.Collections.emptyList();
            }
            java.util.Set<String> members = null;
            for (Map<String, Object> team : teams) {
                if (!trimmed(binding.getTeamId()).equals(String.valueOf(team.get("id")))) continue;
                if ("CAPTAIN".equals(String.valueOf(team.get("mode")))) return "CAPTAIN_ONLY_BINDING: captain Team must use FIXED captain member";
                members = new java.util.HashSet<>();
                Object list = team.get("members");
                if (list instanceof List) for (Object item : (List<?>) list) {
                    if (item instanceof Map) members.add(String.valueOf(((Map<?, ?>) item).get("id")));
                }
            }
            String error = binding.validateConversationMappings(members);
            if (error != null) return error;
            value.put("conversationMappings", binding.getConversationMappings());
        }
        return null;
    }

    private String validateChannelOutboundTargets(JSONObject root) {
        com.alibaba.fastjson.JSONArray channels = root.getJSONArray("channels");
        if (channels == null) return null;
        java.util.Set<String> targetIds = new java.util.HashSet<>();
        for (int i = 0; i < channels.size(); i++) {
            JSONObject channel = channels.getJSONObject(i);
            if (channel == null) continue;
            com.alibaba.fastjson.JSONArray targets = channel.getJSONArray("outboundTargets");
            if (targets == null) {
                String legacyChatId = trimmed(channel.getString("defaultChatId"));
                if (legacyChatId.isEmpty()) continue;
                String legacyId = trimmed(channel.getString("id"));
                if (!targetIds.add(legacyId)) {
                    return "duplicate channel outbound target id: " + legacyId;
                }
                continue;
            }
            for (int j = 0; j < targets.size(); j++) {
                JSONObject target = targets.getJSONObject(j);
                if (target == null) return "channel outbound target must be an object";
                String id = trimmed(target.getString("id"));
                String description = trimmed(target.getString("description"));
                String chatId = trimmed(target.getString("chatId"));
                if (!ChannelOutboundTarget.isValidId(id)) {
                    return "channel outbound target id is invalid";
                }
                if (!targetIds.add(id)) {
                    return "duplicate channel outbound target id: " + id;
                }
                if (description.isEmpty() || description.length() > 500) {
                    return "channel outbound target description is invalid";
                }
                if (chatId.length() > 512) {
                    return "channel outbound target chatId is invalid";
                }
                target.put("id", id);
                target.put("description", description);
                target.put("chatId", chatId);
            }
        }
        return null;
    }

    /** Adds read-only DSH profile state to GET /api/config; POST strips it again. */
    private void appendDshRuntimeProjection(JSONObject json) {
        com.alibaba.fastjson.JSONArray robots = json.getJSONArray("robots");
        if (robots == null) return;
        DeepSeekHarnessAcpProvider provider = new DeepSeekHarnessAcpProvider();
        for (int i = 0; i < robots.size(); i++) {
            JSONObject robot = robots.getJSONObject(i);
            if (robot == null || !"DEEPSEEK_HARNESS_ACP".equalsIgnoreCase(
                    robot.getString("agentProvider"))) {
                continue;
            }
            try {
                AcpRobotParam param = robot.toJavaObject(AcpRobotParam.class);
                robot.put("_dshProfileBundles", provider.getInstalledProfileBundles(param));
            } catch (Exception e) {
                logger.warn("读取 DSH profile bundles 失败, robot={}", robot.getString("name"), e);
                robot.put("_dshProfileBundles", java.util.Collections.emptyList());
                robot.put("_dshProfileError", e.getMessage());
            }
        }
    }

    /** Adds installed npm runtime versions without persisting projection fields. */
    private void appendProviderRuntimeProjection(JSONObject json) {
        com.alibaba.fastjson.JSONArray robots = json.getJSONArray("robots");
        if (robots == null) return;
        NpmProviderRuntimeManager manager = NpmProviderRuntimeManager.getInstance();
        for (int i = 0; i < robots.size(); i++) {
            JSONObject robot = robots.getJSONObject(i);
            if (robot == null) continue;
            AgentProviderType type = AgentProviderType.fromString(robot.getString("agentProvider"));
            if (!manager.supports(type)) continue;
            NpmProviderRuntimeManager.RuntimeStatus status = manager.status(type);
            JSONObject runtime = new JSONObject(true);
            runtime.put("provider", type.name());
            runtime.put("packageName", status.getPackageName());
            runtime.put("selectedVersion", robot.getString("providerVersion"));
            runtime.put("installedVersions", status.getInstalledVersions());
            runtime.put("defaultVersion", status.getDefaultVersion());
            robot.put("_providerRuntime", runtime);
        }
    }

    private void handleProviderRuntimeReleases(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            AgentProviderType type = requiredNpmProvider(exchange);
            NpmProviderRuntimeManager.ReleaseCatalog catalog =
                    NpmProviderRuntimeManager.getInstance().fetchReleases(type);
            JSONObject result = new JSONObject(true);
            result.put("provider", catalog.getProvider().name());
            result.put("packageName", catalog.getPackageName());
            result.put("distTags", catalog.getDistTags());
            result.put("versions", catalog.getVersions());
            result.put("installedVersions", catalog.getInstalledVersions());
            sendResponse(exchange, 200, "application/json", JSON.toJSONString(result));
        } catch (IllegalArgumentException e) {
            sendResponse(exchange, 400, "application/json",
                    JSON.toJSONString(apiError("INVALID_PROVIDER", e.getMessage())));
        } catch (Exception e) {
            logger.warn("读取 Provider 发行版本失败", e);
            sendResponse(exchange, 502, "application/json",
                    JSON.toJSONString(apiError("REGISTRY_UNAVAILABLE", e.getMessage())));
        }
    }

    private void handleProviderRuntimeStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            AgentProviderType type = requiredNpmProvider(exchange);
            NpmProviderRuntimeManager.RuntimeStatus status =
                    NpmProviderRuntimeManager.getInstance().status(type);
            JSONObject result = new JSONObject(true);
            result.put("provider", status.getProvider().name());
            result.put("packageName", status.getPackageName());
            result.put("installedVersions", status.getInstalledVersions());
            result.put("defaultVersion", status.getDefaultVersion());
            sendResponse(exchange, 200, "application/json", JSON.toJSONString(result));
        } catch (IllegalArgumentException e) {
            sendResponse(exchange, 400, "application/json",
                    JSON.toJSONString(apiError("INVALID_PROVIDER", e.getMessage())));
        }
    }

    private void handleProviderRuntimeInstall(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            JSONObject request = JSON.parseObject(readBody(exchange));
            AgentProviderType type = AgentProviderType.fromString(
                    request == null ? null : request.getString("provider"));
            NpmProviderRuntimeManager manager = NpmProviderRuntimeManager.getInstance();
            if (!manager.supportsManagedInstall(type)) {
                throw new IllegalArgumentException("provider does not use managed installation: "
                        + type);
            }
            String version = request == null ? null : request.getString("version");
            Map<String, String> environment = new java.util.LinkedHashMap<>(System.getenv());
            NpmProviderRuntimeManager.InstallJob job =
                    manager.startInstall(type, version, environment);
            sendResponse(exchange, 202, "application/json",
                    JSON.toJSONString(providerJobJson(job)));
        } catch (IllegalArgumentException e) {
            sendResponse(exchange, 400, "application/json",
                    JSON.toJSONString(apiError("INVALID_REQUEST", e.getMessage())));
        }
    }

    private void handleProviderRuntimeJob(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String jobId = param(exchange, "jobId");
        NpmProviderRuntimeManager.InstallJob job =
                NpmProviderRuntimeManager.getInstance().job(jobId);
        if (job == null) {
            sendResponse(exchange, 404, "application/json",
                    JSON.toJSONString(apiError("JOB_NOT_FOUND", "安装任务不存在")));
            return;
        }
        sendResponse(exchange, 200, "application/json",
                JSON.toJSONString(providerJobJson(job)));
    }

    private void handleProviderModels(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "application/json",
                    "{\"success\":false,\"message\":\"Method Not Allowed\"}");
            return;
        }
        try {
            JSONObject request = JSON.parseObject(readBody(exchange, 64L * 1024L));
            if (request == null) request = new JSONObject(true);
            String action = request.getString("action");
            if ("remember".equalsIgnoreCase(action)) {
                providerModelDiscovery.remember(request);
                JSONObject result = new JSONObject(true);
                result.put("success", true);
                sendResponse(exchange, 200, "application/json", result.toJSONString());
                return;
            }
            JSONObject result = providerModelDiscovery.models(
                    request, request.getBooleanValue("force"));
            sendResponse(exchange, 200, "application/json", result.toJSONString());
        } catch (IllegalArgumentException e) {
            sendResponse(exchange, 400, "application/json",
                    JSON.toJSONString(apiError("INVALID_REQUEST", e.getMessage())));
        } catch (Exception e) {
            logger.warn("模型目录请求失败", e);
            sendResponse(exchange, 500, "application/json",
                    JSON.toJSONString(apiError("MODEL_DISCOVERY_FAILED", e.getMessage())));
        }
    }

    private AgentProviderType requiredNpmProvider(HttpExchange exchange) throws IOException {
        AgentProviderType type = AgentProviderType.fromString(param(exchange, "provider"));
        if (!NpmProviderRuntimeManager.getInstance().supports(type)) {
            throw new IllegalArgumentException("provider is not npm-managed: " + type);
        }
        return type;
    }

    private JSONObject providerJobJson(NpmProviderRuntimeManager.InstallJob job) {
        JSONObject result = new JSONObject(true);
        result.put("jobId", job.getJobId());
        result.put("provider", job.getProvider().name());
        result.put("version", job.getVersion());
        result.put("status", job.getStatus());
        result.put("progress", job.getProgress());
        result.put("message", job.getMessage());
        return result;
    }

    private void handleChannelStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        JSONObject result = new JSONObject();
        result.put("instanceId", CmdProxyHome.instanceId());
        result.put("statuses", channelStatusSupplier.get());
        result.put("errors", channelErrorSupplier.get());
        sendResponse(exchange, 200, "application/json", JSON.toJSONString(result));
    }

    private void handleChannelMessages(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "application/json",
                    "{\"ok\":false,\"code\":\"METHOD_NOT_ALLOWED\"}");
            return;
        }
        String base = "/api/channels/v1/messages";
        String path = exchange.getRequestURI().getPath();
        String suffix = path.length() <= base.length() ? "" : path.substring(base.length());
        String archiveId = param(exchange, "archiveId");
        if (isBlank(archiveId)) {
            sendResponse(exchange, 400, "application/json",
                    "{\"ok\":false,\"code\":\"ARCHIVE_ID_REQUIRED\",\"message\":\"archiveId is required\"}");
            return;
        }
        try {
            ChannelMessageArchive archive = ChannelMessageArchive.getInstance();
            if (suffix.isEmpty() || "/".equals(suffix)) {
                int page = (int) parseLong(param(exchange, "page"), 1L);
                int pageSize = (int) parseLong(param(exchange, "pageSize"), 20L);
                if (page < 1 || page > 1000000
                        || !java.util.Arrays.asList(10, 20, 50, 100).contains(pageSize)) {
                    sendResponse(exchange, 400, "application/json",
                            "{\"ok\":false,\"code\":\"INVALID_PAGINATION\",\"message\":\"invalid page or pageSize\"}");
                    return;
                }
                java.util.Map<String, String> filters = new java.util.LinkedHashMap<>();
                for (String name : java.util.Arrays.asList("keyword", "messageId", "senderId",
                        "senderName", "chatType", "chatId", "chatName", "messageType",
                        "deliveryStatus", "content", "quote", "attachmentName", "mimeType",
                        "receivedFrom", "receivedTo")) {
                    filters.put(name, param(exchange, name));
                }
                JSONObject envelope = new JSONObject(true); envelope.put("ok", true);
                envelope.put("data", archive.list(archiveId, filters, page, pageSize));
                sendResponse(exchange, 200, "application/json", JSON.toJSONString(envelope));
                return;
            }
            String[] segments = suffix.substring(1).split("/");
            String messageId = java.net.URLDecoder.decode(segments[0], "UTF-8");
            if (segments.length == 1) {
                JSONObject item = archive.detail(archiveId, messageId);
                if (item == null) {
                    sendResponse(exchange, 404, "application/json",
                            "{\"ok\":false,\"code\":\"MESSAGE_NOT_FOUND\",\"message\":\"消息不存在\"}");
                    return;
                }
                JSONObject envelope = new JSONObject(true); envelope.put("ok", true);
                envelope.put("data", item);
                sendResponse(exchange, 200, "application/json", JSON.toJSONString(envelope));
                return;
            }
            if (segments.length == 3 && "attachments".equals(segments[1])) {
                String attachmentId = java.net.URLDecoder.decode(segments[2], "UTF-8");
                ChannelMessageArchive.AttachmentResource resource =
                        archive.attachment(archiveId, messageId, attachmentId);
                if (resource == null) {
                    sendResponse(exchange, 404, "application/json",
                            "{\"ok\":false,\"code\":\"ATTACHMENT_NOT_FOUND\",\"message\":\"附件不存在\"}");
                    return;
                }
                String mime = resource.getMimeType() == null
                        ? "application/octet-stream" : resource.getMimeType();
                boolean inline = "inline".equals(param(exchange, "disposition"))
                        && java.util.Arrays.asList("image/png", "image/jpeg", "image/gif",
                        "image/webp").contains(mime.toLowerCase(java.util.Locale.ROOT));
                String encoded = java.net.URLEncoder.encode(resource.getFileName(), "UTF-8")
                        .replace("+", "%20");
                exchange.getResponseHeaders().set("Content-Type", mime);
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.getResponseHeaders().set("Content-Disposition",
                        (inline ? "inline" : "attachment") + "; filename*=UTF-8''" + encoded);
                exchange.sendResponseHeaders(200, resource.getSize());
                try (InputStream input = resource.open();
                     OutputStream output = exchange.getResponseBody()) {
                    byte[] buffer = new byte[8192]; int read;
                    while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
                }
                return;
            }
            sendResponse(exchange, 404, "application/json",
                    "{\"ok\":false,\"code\":\"NOT_FOUND\"}");
        } catch (IllegalArgumentException e) {
            JSONObject error = new JSONObject(true); error.put("ok", false);
            error.put("code", "INVALID_REQUEST"); error.put("message", e.getMessage());
            sendResponse(exchange, 400, "application/json", JSON.toJSONString(error));
        } catch (Exception e) {
            logger.error("读取信道消息档案失败", e);
            sendResponse(exchange, 503, "application/json",
                    "{\"ok\":false,\"code\":\"CHANNEL_ARCHIVE_UNAVAILABLE\",\"message\":\"消息记录暂不可用\"}");
        }
    }

    private void handleStarweaveSessions(HttpExchange exchange) throws IOException {
        if (!allowStarweaveOrigin(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            JSONObject data = new JSONObject(true);
            data.put("instanceId", CmdProxyHome.instanceId());
            data.put("sessions", StarweaveSessionApiBridge.list());
            sendResponse(exchange, 200, "application/json",
                    JSON.toJSONString(starweaveEnvelope(null, true, "OK", "", data)));
        } catch (Exception e) {
            sendStarweaveError(exchange, null, e);
        }
    }

    private void handleStarweaveEvents(HttpExchange exchange) throws IOException {
        if (!allowStarweaveOrigin(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            String groupId = param(exchange, "groupId");
            String sessionId = param(exchange, "sessionId");
            long afterSeq = parseLong(param(exchange, "after"), 0L);
            JSONObject data = param(exchange, "limit") != null
                    ? StarweaveSessionApiBridge.historyPage(param(exchange, "groupId"), param(exchange, "sessionId"), param(exchange, "before"), intParam(exchange, "limit", 50))
                    : StarweaveSessionApiBridge.eventBatch(
                    groupId, sessionId, afterSeq, null);
            sendResponse(exchange, 200, "application/json",
                    JSON.toJSONString(starweaveEnvelope(null, true, "OK", "", data)));
        } catch (Exception e) {
            sendStarweaveError(exchange, null, e);
        }
    }

    /**
     * Bounded SSE entry point. The HttpServer worker only validates and hands the
     * exchange to the dedicated stream pool, so ordinary ConfigUI requests cannot
     * be starved by long-lived browsers.
     */
    private void handleStarweaveStream(HttpExchange exchange) throws IOException {
        if (!allowStarweaveOrigin(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        if (!starweaveStreamSlots.tryAcquire()) {
            sendResponse(exchange, 429, "application/json",
                    JSON.toJSONString(apiError("STREAM_LIMIT", "会话流连接数已达上限")));
            return;
        }
        try {
            starweaveStreamExecutor.execute(() -> {
                try {
                    String target = uncheckedParam(exchange, "instance");
                    if (target != null && !target.isEmpty()
                            && !target.equals(CmdProxyHome.instanceId())) {
                        forwardStarweaveStream(exchange, target);
                    } else {
                        serveStarweaveStream(exchange);
                    }
                } catch (Exception e) {
                    logger.debug("Starweave 会话流已结束: {}", e.getMessage());
                    try { exchange.close(); } catch (Exception ignored) { }
                } finally {
                    starweaveStreamSlots.release();
                }
            });
        } catch (RejectedExecutionException stopped) {
            starweaveStreamSlots.release();
            sendResponse(exchange, 503, "application/json",
                    JSON.toJSONString(apiError("STREAM_STOPPED", "会话流服务正在停止")));
        }
    }

    private void serveStarweaveStream(HttpExchange exchange) throws IOException {
        String groupId = param(exchange, "groupId");
        String sessionId = param(exchange, "sessionId");
        String generationText = param(exchange, "generation");
        if (groupId == null || groupId.trim().isEmpty()
                || sessionId == null || sessionId.trim().isEmpty()
                || generationText == null || generationText.trim().isEmpty()) {
            sendResponse(exchange, 400, "application/json",
                    JSON.toJSONString(apiError("INVALID_REQUEST",
                            "groupId、sessionId、generation 均不能为空")));
            return;
        }
        long generation = parseLong(generationText, -1L);
        if (generation < 1L) {
            sendResponse(exchange, 400, "application/json",
                    JSON.toJSONString(apiError("INVALID_GENERATION", "generation 必须为正整数")));
            return;
        }
        long after = parseLong(param(exchange, "after"), 0L);
        String lastEventId = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        if (lastEventId != null && !lastEventId.trim().isEmpty()) {
            after = Math.max(after, parseLong(lastEventId, after));
        }

        // Validate ownership/current index before committing streaming headers.
        StarweaveSessionApiBridge.eventBatch(groupId, sessionId, after, generation);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(25);
        try (OutputStream output = exchange.getResponseBody()) {
            writeSseComment(output, "starweave stream ready");
            while (System.currentTimeMillis() < deadline
                    && !Thread.currentThread().isInterrupted()) {
                JSONObject batch = StarweaveSessionApiBridge.awaitEventBatch(
                        groupId, sessionId, after, generation, 10_000L);
                if (batch.getBooleanValue("resyncRequired")) {
                    JSONObject payload = new JSONObject(true);
                    payload.put("firstAvailableSeq", batch.getLongValue("firstAvailableSeq"));
                    payload.put("latestSeq", batch.getLongValue("latestSeq"));
                    writeSse(output, null, "RESYNC_REQUIRED", payload.toJSONString());
                    break;
                }
                com.alibaba.fastjson.JSONArray values = batch.getJSONArray("events");
                if (values == null || values.isEmpty()) {
                    writeSseComment(output, "heartbeat");
                    continue;
                }
                for (int i = 0; i < values.size(); i++) {
                    JSONObject event = values.getJSONObject(i);
                    after = Math.max(after, event.getLongValue("eventSeq"));
                    writeSse(output, Long.toString(event.getLongValue("eventSeq")),
                            "starweave", event.toJSONString());
                }
            }
        }
    }

    private void forwardStarweaveStream(HttpExchange exchange, String instanceId)
            throws IOException {
        forward(exchange, instanceId);
    }

    private static void writeSse(OutputStream output, String id, String event,
                                 String data) throws IOException {
        StringBuilder frame = new StringBuilder();
        if (id != null) frame.append("id: ").append(id).append('\n');
        if (event != null) frame.append("event: ").append(event).append('\n');
        for (String line : data.split("\\r?\\n", -1)) {
            frame.append("data: ").append(line).append('\n');
        }
        frame.append('\n');
        output.write(frame.toString().getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void writeSseComment(OutputStream output, String comment)
            throws IOException {
        output.write((": " + comment + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private String uncheckedParam(HttpExchange exchange, String key) {
        try {
            return param(exchange, key);
        } catch (IOException impossible) {
            return null;
        }
    }

    private void handleStarweaveCommand(HttpExchange exchange, String action)
            throws IOException {
        if (!allowStarweaveOrigin(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        JSONObject request = null;
        String requestId = null;
        try {
            request = JSON.parseObject(readBody(exchange, 2L * 1024L * 1024L));
            if (request == null) request = new JSONObject(true);
            requestId = request.getString("requestId");
            final JSONObject commandRequest = request;
            JSONObject data = starweaveRequests.execute(requestId,
                    action + ":" + request.toJSONString(),
                    () -> executeStarweaveCommand(action, commandRequest));
            sendResponse(exchange, 200, "application/json",
                    JSON.toJSONString(starweaveCommandEnvelope(requestId, data)));
        } catch (Exception e) {
            sendStarweaveError(exchange, requestId, e);
        }
    }

    private void handleStarweaveUpload(HttpExchange exchange) throws IOException {
        if (!allowStarweaveOrigin(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String requestId = null;
        try {
            JSONObject request = JSON.parseObject(readBody(exchange,
                    28L * 1024L * 1024L));
            if (request == null) throw new IllegalArgumentException("request body is required");
            requestId = request.getString("requestId");
            JSONObject data = StarweaveSessionApiBridge.upload(
                    request.getString("groupId"), request.getString("sessionId"),
                    request.getLongValue("generation"), request.getString("fileName"),
                    request.getString("contentBase64"));
            sendResponse(exchange, 200, "application/json",
                    JSON.toJSONString(starweaveEnvelope(
                            requestId, true, "OK", "", data)));
        } catch (Exception e) {
            sendStarweaveError(exchange, requestId, e);
        }
    }

    private void handleStarweaveResources(HttpExchange exchange, String action)
            throws IOException {
        if (!allowStarweaveOrigin(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            String groupId = param(exchange, "groupId");
            String sessionId = param(exchange, "sessionId");
            long generation = parseLong(param(exchange, "generation"), -1L);
            String resourceId = param(exchange, "resourceId");
            if ("download".equals(action)) {
                StarweaveResourcePayload resource = StarweaveSessionApiBridge.downloadResource(
                        groupId, sessionId, generation, resourceId);
                exchange.getResponseHeaders().set("Content-Type", resource.getContentType());
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                String encoded = java.net.URLEncoder.encode(
                        resource.getFileName(), "UTF-8").replace("+", "%20");
                exchange.getResponseHeaders().set("Content-Disposition",
                        "attachment; filename*=UTF-8''" + encoded);
                byte[] bytes = resource.getBytes();
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
                return;
            }
            JSONObject data = new JSONObject(true);
            if ("preview".equals(action)) {
                data.put("resource", StarweaveSessionApiBridge.previewResource(
                        groupId, sessionId, generation, resourceId));
            } else {
                data.put("resources", StarweaveSessionApiBridge.resources(
                        groupId, sessionId, generation));
            }
            sendResponse(exchange, 200, "application/json",
                    JSON.toJSONString(starweaveEnvelope(null, true, "OK", "", data)));
        } catch (Exception e) {
            sendStarweaveError(exchange, null, e);
        }
    }

    private void handleStarweaveFilePreview(HttpExchange exchange, boolean team)
            throws IOException {
        if (!allowStarweaveOrigin(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String requestId = null;
        try {
            JSONObject request = JSON.parseObject(readBody(exchange, 64L * 1024L));
            if (request == null) throw new IllegalArgumentException("request body is required");
            requestId = request.getString("requestId");
            String target = request.getString("target");
            Integer requestedLine = request.getInteger("requestedLine");
            if (target == null || target.trim().isEmpty() || target.length() > 8192) {
                throw new IllegalArgumentException("target is required");
            }
            if (requestedLine != null && requestedLine < 1) {
                throw new IllegalArgumentException("requestedLine must be positive");
            }

            JSONObject preview;
            boolean accepted;
            String code;
            String message;
            if (team) {
                request.put("path", target);
                request.put("maxBytes",
                        com.mola.cmd.proxy.app.acp.filepreview.TextFilePreviewReader.HARD_MAX_BYTES);
                preview = StarweaveTeamApiBridge.previewTextFile(request);
                accepted = preview.getBooleanValue("accepted");
                code = preview.getString("code");
                message = preview.getString("message");
                JSONObject data = preview.getJSONObject("data");
                if (data != null) {
                    data.put("requestedLine", requestedLine);
                }
                preview = data;
            } else {
                TextFilePreviewResult result = StarweaveSessionApiBridge.previewTextFile(
                        requestId, request.getString("groupId"), request.getString("sessionId"),
                        request.getLongValue("generation"), target,
                        com.mola.cmd.proxy.app.acp.filepreview.TextFilePreviewReader.HARD_MAX_BYTES,
                        null);
                accepted = result.isAccepted();
                code = result.getCode();
                message = result.getMessage();
                preview = result.getData() == null ? null
                        : JSON.parseObject(JSON.toJSONString(result.getData()));
                if (preview != null) preview.put("requestedLine", requestedLine);
            }
            sendResponse(exchange, filePreviewStatus(accepted, code), "application/json",
                    JSON.toJSONString(starweaveEnvelope(requestId, accepted,
                            code == null ? (accepted ? "OK" : "FILE_PREVIEW_FAILED") : code,
                            message, preview)));
        } catch (Exception e) {
            sendStarweaveError(exchange, requestId, e);
        }
    }

    private static int filePreviewStatus(boolean accepted, String code) {
        if (accepted) return 200;
        if ("FILE_NOT_FOUND".equals(code)) return 404;
        if ("FILE_TOO_LARGE".equals(code)) return 413;
        if ("BUSY".equals(code)) return 429;
        if ("REMOTE_FILE_PREVIEW_UNSUPPORTED".equals(code)) return 422;
        if ("IO_ERROR".equals(code)) return 502;
        return 400;
    }

    private void handleStarweaveTeams(HttpExchange exchange, String action)
            throws IOException {
        if (!allowStarweaveOrigin(exchange)) return;
        boolean mutation = "create".equals(action) || "update".equals(action)
                || "delete".equals(action)
                || "member".equals(action) || "upload".equals(action);
        String expectedMethod = mutation ? "POST" : "GET";
        if (!expectedMethod.equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            JSONObject data;
            if ("events".equals(action)) {
                data = new JSONObject(true);
                data.put("events", StarweaveTeamApiBridge.events(
                        parseLong(param(exchange, "after"), 0L),
                        param(exchange, "teamId"), param(exchange, "teamMemberId")));
            } else if ("list".equals(action)) {
                data = StarweaveTeamApiBridge.list();
            } else if ("sources".equals(action)) {
                data = StarweaveTeamApiBridge.sources();
            } else {
                JSONObject request = JSON.parseObject(readBody(exchange,
                        "upload".equals(action) ? 28L * 1024L * 1024L : 256L * 1024L));
                data = "create".equals(action)
                        ? StarweaveTeamApiBridge.create(request)
                        : "update".equals(action)
                        ? StarweaveTeamApiBridge.update(request)
                        : "upload".equals(action)
                        ? StarweaveTeamApiBridge.upload(request)
                        : "member".equals(action)
                        ? StarweaveTeamApiBridge.member(request)
                        : StarweaveTeamApiBridge.delete(request);
            }
            boolean accepted = !data.containsKey("accepted")
                    || data.getBooleanValue("accepted");
            sendResponse(exchange, accepted ? 200 : 422, "application/json",
                    JSON.toJSONString(starweaveEnvelope(null, accepted,
                            data.getString("code"), data.getString("message"), data)));
        } catch (Exception e) {
            sendStarweaveError(exchange, null, e);
        }
    }

    /** Team sessions use the same immediate, bounded SSE delivery as normal sessions. */
    private void handleStarweaveTeamStream(HttpExchange exchange) throws IOException {
        if (!allowStarweaveOrigin(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        if (!starweaveStreamSlots.tryAcquire()) {
            sendResponse(exchange, 429, "application/json",
                    JSON.toJSONString(apiError("STREAM_LIMIT", "会话流连接数已达上限")));
            return;
        }
        try {
            starweaveStreamExecutor.execute(() -> {
                try {
                    String target = uncheckedParam(exchange, "instance");
                    if (target != null && !target.isEmpty()
                            && !target.equals(CmdProxyHome.instanceId())) {
                        forwardStarweaveStream(exchange, target);
                    } else {
                        serveStarweaveTeamStream(exchange);
                    }
                } catch (Exception e) {
                    logger.debug("Starweave 团队会话流已结束: {}", e.getMessage());
                    try { exchange.close(); } catch (Exception ignored) { }
                } finally {
                    starweaveStreamSlots.release();
                }
            });
        } catch (RejectedExecutionException stopped) {
            starweaveStreamSlots.release();
            sendResponse(exchange, 503, "application/json",
                    JSON.toJSONString(apiError("STREAM_STOPPED", "会话流服务正在停止")));
        }
    }

    private void serveStarweaveTeamStream(HttpExchange exchange) throws IOException {
        String teamId = param(exchange, "teamId");
        String teamMemberId = param(exchange, "teamMemberId");
        if (teamId == null || teamId.trim().isEmpty()
                || teamMemberId == null || teamMemberId.trim().isEmpty()) {
            sendResponse(exchange, 400, "application/json",
                    JSON.toJSONString(apiError("INVALID_REQUEST",
                            "teamId、teamMemberId 均不能为空")));
            return;
        }
        long after = parseLong(param(exchange, "after"), 0L);
        String lastEventId = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        if (lastEventId != null && !lastEventId.trim().isEmpty()) {
            after = Math.max(after, parseLong(lastEventId, after));
        }

        // Validate Team ownership before committing streaming headers.
        StarweaveTeamApiBridge.events(after, teamId, teamMemberId);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(25);
        try (OutputStream output = exchange.getResponseBody()) {
            writeSseComment(output, "starweave team stream ready");
            while (System.currentTimeMillis() < deadline
                    && !Thread.currentThread().isInterrupted()) {
                com.alibaba.fastjson.JSONArray values = StarweaveTeamApiBridge.awaitEvents(
                        after, teamId, teamMemberId, 10_000L);
                if (values.isEmpty()) {
                    writeSseComment(output, "heartbeat");
                    continue;
                }
                for (int i = 0; i < values.size(); i++) {
                    JSONObject event = values.getJSONObject(i);
                    after = Math.max(after, event.getLongValue("eventSeq"));
                    writeSse(output, Long.toString(event.getLongValue("eventSeq")),
                            "starweave-team", event.toJSONString());
                }
            }
        }
    }

    private void handleStarweaveTeamResources(HttpExchange exchange, String action)
            throws IOException {
        if (!allowStarweaveOrigin(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            JSONObject request = new JSONObject(true);
            request.put("requestId", UUID.randomUUID().toString());
            request.put("teamId", param(exchange, "teamId"));
            request.put("teamMemberId", param(exchange, "teamMemberId"));
            request.put("acpClientId", param(exchange, "acpClientId"));
            request.put("sessionId", param(exchange, "sessionId"));
            request.put("resourceId", param(exchange, "resourceId"));
            if ("download".equals(action)) {
                com.mola.cmd.proxy.app.acp.team.TeamResourcePayload resource =
                        StarweaveTeamApiBridge.downloadResource(request);
                exchange.getResponseHeaders().set("Content-Type", resource.getContentType());
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                String encoded = java.net.URLEncoder.encode(
                        resource.getFileName(), "UTF-8").replace("+", "%20");
                exchange.getResponseHeaders().set("Content-Disposition",
                        "attachment; filename*=UTF-8''" + encoded);
                byte[] bytes = resource.getBytes();
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
                return;
            }
            JSONObject data = StarweaveTeamApiBridge.resources(
                    request, "preview".equals(action));
            boolean accepted = data.getBooleanValue("accepted");
            sendResponse(exchange, accepted ? 200 : 422, "application/json",
                    JSON.toJSONString(starweaveEnvelope(null, accepted,
                            data.getString("code"), data.getString("message"), data)));
        } catch (Exception e) {
            sendStarweaveError(exchange, null, e);
        }
    }

    private JSONObject executeStarweaveCommand(String action, JSONObject request)
            throws Exception {
            JSONObject data;
            switch (action) {
                case "open":
                    data = StarweaveSessionApiBridge.open(request.getString("robotName"));
                    break;
                case "messages":
                    com.alibaba.fastjson.JSONArray uploadValues =
                            request.getJSONArray("uploadIds");
                    data = StarweaveSessionApiBridge.send(
                            request.getString("groupId"), request.getString("message"),
                            request.getString("expectedSessionId"),
                            request.getLongValue("expectedGeneration"),
                            request.getString("busyPolicy"), uploadValues == null
                                    ? java.util.Collections.emptyList()
                                    : uploadValues.toJavaList(String.class), request.getString("requestId"));
                    break;
                case "cancel":
                    data = StarweaveSessionApiBridge.cancel(
                            request.getString("groupId"),
                            request.getString("expectedSessionId"),
                            request.getLongValue("expectedGeneration"));
                    break;
                case "new":
                    data = StarweaveSessionApiBridge.newSession(
                            request.getString("groupId"),
                            request.getString("expectedSessionId"),
                            request.getLongValue("expectedGeneration"));
                    break;
                case "restore":
                    data = StarweaveSessionApiBridge.restore(
                            request.getString("groupId"),
                            request.getString("targetSessionId"),
                            request.getString("expectedSessionId"),
                            request.getLongValue("expectedGeneration"));
                    break;
                case "delete":
                    data = StarweaveSessionApiBridge.delete(
                            request.getString("groupId"),
                            request.getString("expectedSessionId"),
                            request.getLongValue("expectedGeneration"));
                    break;
                default:
                    throw new IllegalArgumentException("unsupported action: " + action);
            }
            return data;
    }

    private boolean allowStarweaveOrigin(HttpExchange exchange) throws IOException {
        String fetchSite = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
        if ("cross-site".equalsIgnoreCase(fetchSite)) {
            sendResponse(exchange, 403, "application/json",
                    JSON.toJSONString(apiError("ORIGIN_REJECTED", "不允许跨站访问会话接口")));
            return false;
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.trim().isEmpty()) return true;
        String host = exchange.getRequestHeaders().getFirst("Host");
        try {
            java.net.URI uri = java.net.URI.create(origin);
            String originAuthority = uri.getRawAuthority();
            if (("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    && originAuthority != null && host != null
                    && originAuthority.equalsIgnoreCase(host.trim())) return true;
        } catch (RuntimeException ignored) { }
        sendResponse(exchange, 403, "application/json",
                JSON.toJSONString(apiError("ORIGIN_REJECTED", "会话接口仅接受同源请求")));
        return false;
    }

    private JSONObject starweaveEnvelope(String requestId, boolean accepted,
                                         String code, String message,
                                         Object data) {
        JSONObject result = new JSONObject(true);
        result.put("schemaVersion", 1);
        result.put("requestId", requestId == null || requestId.trim().isEmpty()
                ? UUID.randomUUID().toString() : requestId);
        result.put("accepted", accepted);
        result.put("code", code);
        result.put("message", message == null ? "" : message);
        result.put("data", data);
        return result;
    }

    JSONObject starweaveCommandEnvelope(String requestId, JSONObject data) {
        boolean accepted = !data.containsKey("accepted")
                || data.getBooleanValue("accepted");
        String code = data.getString("code");
        String message = data.getString("message");
        return starweaveEnvelope(requestId, accepted,
                code == null ? (accepted ? "OK" : "REJECTED_STATE") : code,
                message == null ? "" : message, data);
    }

    private void sendStarweaveError(HttpExchange exchange, String requestId,
                                    Exception error) throws IOException {
        String message = error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        String code;
        if (message.startsWith("SESSION_STALE")) code = "SESSION_STALE";
        else if (lower.startsWith("robot not found")) code = "ROBOT_NOT_FOUND";
        else if (lower.startsWith("robot is disabled")) code = "ROBOT_DISABLED";
        else if (lower.contains("cannot create a main session")) code = "ROBOT_NOT_MAIN_CAPABLE";
        else if (lower.startsWith("session not found")) code = "SESSION_NOT_FOUND";
        else if (lower.contains("not ready")) code = "STATE_NOT_READY";
        else if (lower.contains("busy session")) code = "ALREADY_BUSY";
        else if (lower.contains("restore") && lower.contains("not support"))
            code = "PROVIDER_RESTORE_UNSUPPORTED";
        else if (lower.contains("upload not found") || lower.contains("upload has expired"))
            code = "UPLOAD_NOT_FOUND";
        else if (lower.contains("file size") || lower.contains("file too large"))
            code = "FILE_TOO_LARGE";
        else if (lower.contains("resource not found") || lower.contains("outside workspace"))
            code = "PATH_OUTSIDE_WORKSPACE";
        else if (lower.contains("service is not running")) code = "SERVICE_UNAVAILABLE";
        else if (error instanceof IllegalArgumentException) code = "INVALID_REQUEST";
        else code = "INTERNAL_ERROR";
        int status = "SESSION_STALE".equals(code) || "STATE_NOT_READY".equals(code)
                || "ALREADY_BUSY".equals(code) ? 409
                : "ROBOT_NOT_FOUND".equals(code) || "SESSION_NOT_FOUND".equals(code)
                || "UPLOAD_NOT_FOUND".equals(code) ? 404
                : "FILE_TOO_LARGE".equals(code) ? 413
                : "SERVICE_UNAVAILABLE".equals(code) ? 503
                : "INTERNAL_ERROR".equals(code) ? 500 : 400;
        sendResponse(exchange, status, "application/json",
                JSON.toJSONString(starweaveEnvelope(
                        requestId, false, code, message, null)));
    }

    private static long parseLong(String value, long defaultValue) {
        if (value == null || value.trim().isEmpty()) return defaultValue;
        try { return Long.parseLong(value.trim()); }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid long value: " + value);
        }
    }

    private void handleTeamSharingStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        JSONObject result = new JSONObject();
        result.put("instanceId", CmdProxyHome.instanceId());
        result.put("grants", TeamSharingStatusRegistry.snapshot());
        sendResponse(exchange, 200, "application/json", JSON.toJSONString(result));
    }

    private void handleChannelInbound(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            JSONObject request = JSON.parseObject(readBody(exchange));
            String channelId = request == null ? null : request.getString("channelId");
            if (isBlank(channelId) || !request.containsKey("inboundEnabled")) {
                sendResponse(exchange, 400, "application/json",
                        "{\"error\":\"channelId and inboundEnabled are required\"}");
                return;
            }
            boolean enabled = request.getBooleanValue("inboundEnabled");
            boolean previous = channelInboundUpdater.apply(channelId.trim(), enabled);
            try {
                ChannelConfigFileStore.setInboundEnabled(channelId.trim(), enabled);
            } catch (Exception persistFailure) {
                try {
                    channelInboundUpdater.apply(channelId.trim(), previous);
                } catch (Exception rollbackFailure) {
                    persistFailure.addSuppressed(rollbackFailure);
                }
                throw persistFailure;
            }
            sendResponse(exchange, 200, "application/json", "{\"ok\":true}");
        } catch (Exception e) {
            logger.error("切换信道入站状态失败", e);
            JSONObject error = new JSONObject();
            error.put("error", e.getMessage() == null ? "unknown error" : e.getMessage());
            sendResponse(exchange, 500, "application/json", JSON.toJSONString(error));
        }
    }

    private void handleChannelPrivateChat(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            JSONObject request = JSON.parseObject(readBody(exchange));
            String channelId = request == null ? null : request.getString("channelId");
            if (isBlank(channelId) || !request.containsKey("privateChatEnabled")) {
                sendResponse(exchange, 400, "application/json",
                        "{\"error\":\"channelId and privateChatEnabled are required\"}");
                return;
            }
            boolean enabled = request.getBooleanValue("privateChatEnabled");
            boolean previous = channelPrivateChatUpdater.apply(channelId.trim(), enabled);
            try {
                ChannelConfigFileStore.setPrivateChatEnabled(channelId.trim(), enabled);
            } catch (Exception persistFailure) {
                try {
                    channelPrivateChatUpdater.apply(channelId.trim(), previous);
                } catch (Exception rollbackFailure) {
                    persistFailure.addSuppressed(rollbackFailure);
                }
                throw persistFailure;
            }
            sendResponse(exchange, 200, "application/json", "{\"ok\":true}");
        } catch (Exception e) {
            logger.error("切换信道单聊状态失败", e);
            JSONObject error = new JSONObject();
            error.put("error", e.getMessage() == null ? "unknown error" : e.getMessage());
            sendResponse(exchange, 500, "application/json", JSON.toJSONString(error));
        }
    }

    private void handleChannelBindingTargets(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        JSONObject result = new JSONObject();
        result.put("instanceId", CmdProxyHome.instanceId());
        result.put("sessions", StarweaveSessionApiBridge.list());
        try {
            List<Map<String, Object>> teams = channelBindingTargetSupplier.get();
            result.put("teams", teams == null ? java.util.Collections.emptyList() : teams);
        } catch (RuntimeException e) {
            logger.warn("读取信道 Team 绑定目标失败", e);
            result.put("teams", java.util.Collections.emptyList());
        }
        sendResponse(exchange, 200, "application/json", JSON.toJSONString(result));
    }

    private void handleAgentGatewayTargets(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        JSONObject result = new JSONObject(true);
        result.put("instanceId", CmdProxyHome.instanceId());
        result.put("targets", AgentGatewayAdminBridge.targets());
        sendResponse(exchange, 200, "application/json", result.toJSONString());
    }

    private void handleAgentGatewayStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        sendResponse(exchange, 200, "application/json",
                AgentGatewayAdminBridge.status().toJSONString());
    }

    private void handleAgentGatewayAuthCode(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String id = param(exchange, "id");
        if (isBlank(id)) {
            sendResponse(exchange, 400, "application/json", "{\"error\":\"id is required\"}");
            return;
        }
        try {
            JSONObject root = JSON.parseObject(new String(Files.readAllBytes(
                    Paths.get(CONFIG_PATH)), StandardCharsets.UTF_8));
            com.alibaba.fastjson.JSONArray gateways = root.getJSONArray("agentGateways");
            if (gateways != null) {
                for (int i = 0; i < gateways.size(); i++) {
                    JSONObject item = gateways.getJSONObject(i);
                    if (item != null && id.equals(item.getString("id"))) {
                        JSONObject result = new JSONObject(true);
                        result.put("authCode", item.getString("authCode"));
                        sendResponse(exchange, 200, "application/json", result.toJSONString());
                        return;
                    }
                }
            }
            sendResponse(exchange, 404, "application/json",
                    "{\"error\":\"Agent gateway not found\"}");
        } catch (Exception e) {
            sendResponse(exchange, 503, "application/json",
                    "{\"error\":\"Agent gateway configuration unavailable\"}");
        }
    }

    private void handleExternalTaskApiAuthCode(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String id = param(exchange, "id");
        if (isBlank(id)) {
            sendResponse(exchange, 400, "application/json",
                    "{\"error\":\"id is required\"}");
            return;
        }
        try {
            JSONObject root = JSON.parseObject(new String(Files.readAllBytes(
                    Paths.get(CONFIG_PATH)), StandardCharsets.UTF_8));
            com.alibaba.fastjson.JSONArray apis = root.getJSONArray("externalTaskApis");
            if (apis != null) {
                for (int i = 0; i < apis.size(); i++) {
                    JSONObject item = apis.getJSONObject(i);
                    if (item != null && id.equals(item.getString("id"))) {
                        JSONObject result = new JSONObject(true);
                        result.put("authCode", item.getString("authCode"));
                        sendResponse(exchange, 200, "application/json",
                                JSON.toJSONString(result));
                        return;
                    }
                }
            }
            sendResponse(exchange, 404, "application/json",
                    "{\"error\":\"external task endpoint not found\"}");
        } catch (Exception e) {
            sendResponse(exchange, 503, "application/json",
                    "{\"error\":\"external task configuration unavailable\"}");
        }
    }

    private void maskChannelSecrets(JSONObject json) {
        com.alibaba.fastjson.JSONArray channels = json.getJSONArray("channels");
        if (channels != null) {
            for (int i = 0; i < channels.size(); i++) {
                JSONObject channel = channels.getJSONObject(i);
                if (channel != null && !isBlank(channel.getString("secret"))) {
                    channel.put("secret", SECRET_MASK);
                }
            }
        }
        com.alibaba.fastjson.JSONArray apis = json.getJSONArray("externalTaskApis");
        if (apis != null) {
            for (int i = 0; i < apis.size(); i++) {
                JSONObject item = apis.getJSONObject(i);
                if (item != null && !isBlank(item.getString("authCode"))) {
                    item.put("authCode", SECRET_MASK);
                }
            }
        }
        com.alibaba.fastjson.JSONArray gateways = json.getJSONArray("agentGateways");
        if (gateways != null) {
            for (int i = 0; i < gateways.size(); i++) {
                JSONObject item = gateways.getJSONObject(i);
                if (item != null && !isBlank(item.getString("authCode"))) {
                    item.put("authCode", SECRET_MASK);
                }
            }
        }
    }

    private String validateExternalTaskApis(JSONObject root) {
        com.alibaba.fastjson.JSONArray apis = root.getJSONArray("externalTaskApis");
        if (apis == null) return null;
        java.util.Set<String> ids = new java.util.HashSet<>();
        java.util.Set<String> authCodes = new java.util.HashSet<>();
        for (int i = 0; i < apis.size(); i++) {
            JSONObject item = apis.getJSONObject(i);
            if (item == null) return "externalTaskApis[" + i + "] must be an object";
            String id = trimmed(item.getString("id"));
            String name = trimmed(item.getString("name"));
            String authCode = item.getString("authCode");
            if (id.isEmpty() || id.length() > 120) return "external task endpoint id is invalid";
            if (!ids.add(id)) return "duplicate external task endpoint id: " + id;
            if (name.isEmpty() || name.length() > 200) return "external task endpoint name is invalid";
            if (!SECRET_MASK.equals(authCode)) {
                if (authCode == null || authCode.trim().length() < 16
                        || authCode.length() > 512) {
                    return "external task endpoint auth code must contain 16-512 characters";
                }
                authCode = authCode.trim();
                item.put("authCode", authCode);
                if (!authCodes.add(authCode)) return "duplicate external task endpoint auth code";
            }
            String defaultName = item.getString("defaultTaskName");
            String defaultContent = item.getString("defaultTaskContent");
            if (defaultName != null && defaultName.length() > 200) {
                return "external task default name is too long";
            }
            if (defaultContent != null && defaultContent.length() > 1024 * 1024) {
                return "external task default content is too long";
            }
            JSONObject target = item.getJSONObject("target");
            if (target == null) return "external task endpoint target is required";
            String instanceId = trimmed(target.getString("instanceId"));
            if (instanceId.isEmpty()) target.put("instanceId", CmdProxyHome.instanceId());
            else if (!CmdProxyHome.instanceId().equals(instanceId)) {
                return "external task endpoint target belongs to another instance";
            }
            String type = trimmed(target.getString("type")).toUpperCase();
            if ("AGENT".equals(type)) {
                if (trimmed(target.getString("agentId")).isEmpty()
                        && trimmed(target.getString("groupId")).isEmpty()) {
                    return "external task Agent target is required";
                }
            } else if ("TEAM".equals(type)) {
                if (trimmed(target.getString("teamId")).isEmpty()) {
                    return "external task Team target is required";
                }
                String mode = trimmed(target.getString("mode")).toUpperCase();
                if (mode.isEmpty()) mode = "FIXED";
                if (!java.util.Arrays.asList("FIXED", "RANDOM", "AFFINITY").contains(mode)) {
                    return "external task Team assignment mode is invalid";
                }
                target.put("mode", mode);
                if ("FIXED".equals(mode)
                        && trimmed(target.getString("teamMemberId")).isEmpty()) {
                    return "external task fixed Team member is required";
                }
            } else {
                return "external task endpoint target type must be AGENT or TEAM";
            }
            item.put("id", id);
            item.put("name", name);
        }
        return null;
    }

    private String validateAgentGateways(JSONObject root) {
        JSONObject server = root.getJSONObject("agentGatewayServer");
        if (server != null) {
            int port = server.getIntValue("port");
            if (port < 0 || port > 65535
                    || (server.getBooleanValue("enabled") && port == 0)) {
                return "Agent gateway server port is invalid";
            }
            String host = trimmed(server.getString("bindHost"));
            if (server.getBooleanValue("enabled") && host.isEmpty()) {
                return "Agent gateway bindHost is required";
            }
        }
        com.alibaba.fastjson.JSONArray values = root.getJSONArray("agentGateways");
        if (values == null) return null;
        java.util.Set<String> ids = new java.util.HashSet<>();
        java.util.Set<String> authCodes = new java.util.HashSet<>();
        java.util.Set<String> enabledTargets = new java.util.HashSet<>();
        for (int i = 0; i < values.size(); i++) {
            JSONObject value = values.getJSONObject(i);
            if (value == null) return "agentGateways[" + i + "] must be an object";
            String id = trimmed(value.getString("id"));
            String name = trimmed(value.getString("name"));
            if (id.isEmpty() || id.length() > 120) return "Agent gateway id is invalid";
            if (!ids.add(id)) return "duplicate Agent gateway id: " + id;
            if (name.isEmpty() || name.length() > 200) return "Agent gateway name is invalid";
            String authCode = value.getString("authCode");
            if (!SECRET_MASK.equals(authCode)) {
                authCode = trimmed(authCode);
                if (authCode.length() < 32 || authCode.length() > 512) {
                    return "Agent gateway auth code must contain 32-512 characters";
                }
                value.put("authCode", authCode);
                if (!authCodes.add(authCode)) return "duplicate Agent gateway auth code";
            }
            JSONObject targetValue = value.getJSONObject("target");
            if (targetValue == null) return "Agent gateway target is required";
            if (trimmed(targetValue.getString("instanceId")).isEmpty()) {
                targetValue.put("instanceId", CmdProxyHome.instanceId());
            }
            try {
                com.mola.cmd.proxy.app.acp.gateway.model.GatewayTarget target =
                        targetValue.toJavaObject(
                                com.mola.cmd.proxy.app.acp.gateway.model.GatewayTarget.class);
                target.validate(CmdProxyHome.instanceId());
                if (value.getBooleanValue("enabled") && !enabledTargets.add(target.key())) {
                    return "only one enabled Agent gateway may bind the same target";
                }
            } catch (IllegalArgumentException error) {
                return error.getMessage();
            }
            value.put("id", id);
            value.put("name", name);
        }
        return null;
    }

    /** Re-validates captain-only entrypoints against the current authoritative Team view. */
    String validateCaptainTeamEntrypoints(JSONObject root) {
        List<Map<String, Object>> targets;
        try {
            targets = channelBindingTargetSupplier.get();
        } catch (RuntimeException e) {
            logger.warn("保存配置时读取 Team 绑定目标失败", e);
            return "Team binding targets are unavailable";
        }
        if (targets == null || targets.isEmpty()) return null;
        Map<String, Map<String, Object>> captainTeams = new java.util.HashMap<>();
        for (Map<String, Object> target : targets) {
            if (target == null || !"CAPTAIN".equals(String.valueOf(target.get("mode")))) continue;
            String teamId = trimmed(String.valueOf(target.get("id")));
            if (!teamId.isEmpty()) captainTeams.put(teamId, target);
        }
        if (captainTeams.isEmpty()) return null;

        com.alibaba.fastjson.JSONArray channels = root.getJSONArray("channels");
        if (channels != null) {
            for (int i = 0; i < channels.size(); i++) {
                JSONObject channel = channels.getJSONObject(i);
                JSONObject binding = channel == null ? null : channel.getJSONObject("binding");
                if (binding == null || !"TEAM_MEMBER".equals(
                        trimmed(binding.getString("type")).toUpperCase())) continue;
                String error = captainTargetError(binding, captainTeams,
                        "teamMemberSelection", "CAPTAIN_ONLY_BINDING");
                if (error != null) return error;
            }
        }

        com.alibaba.fastjson.JSONArray apis = root.getJSONArray("externalTaskApis");
        if (apis != null) {
            for (int i = 0; i < apis.size(); i++) {
                JSONObject item = apis.getJSONObject(i);
                JSONObject target = item == null ? null : item.getJSONObject("target");
                if (target == null || !"TEAM".equals(
                        trimmed(target.getString("type")).toUpperCase())) continue;
                String error = captainTargetError(target, captainTeams,
                        "mode", "CAPTAIN_ONLY_TASK_TARGET");
                if (error != null) return error;
            }
        }
        com.alibaba.fastjson.JSONArray gateways = root.getJSONArray("agentGateways");
        if (gateways != null) {
            for (int i = 0; i < gateways.size(); i++) {
                JSONObject item = gateways.getJSONObject(i);
                JSONObject target = item == null ? null : item.getJSONObject("target");
                if (target == null || !"TEAM_MEMBER".equals(
                        trimmed(target.getString("type")).toUpperCase())) continue;
                Map<String, Object> team = captainTeams.get(
                        trimmed(target.getString("teamId")));
                if (team == null) continue;
                String captainId = trimmed(String.valueOf(team.get("captainTeamMemberId")));
                if (captainId.isEmpty() || !captainId.equals(
                        trimmed(target.getString("teamMemberId")))) {
                    return "CAPTAIN_ONLY_GATEWAY_TARGET: captain Team gateway must target the captain";
                }
            }
        }
        return null;
    }

    private static String captainTargetError(JSONObject target,
                                             Map<String, Map<String, Object>> captainTeams,
                                             String selectionField,
                                             String errorCode) {
        Map<String, Object> team = captainTeams.get(trimmed(target.getString("teamId")));
        if (team == null) return null;
        String selection = trimmed(target.getString(selectionField)).toUpperCase();
        if (selection.isEmpty()) selection = "FIXED";
        String captainId = trimmed(String.valueOf(team.get("captainTeamMemberId")));
        String memberId = trimmed(target.getString("teamMemberId"));
        if (!"FIXED".equals(selection) || captainId.isEmpty()
                || !captainId.equals(memberId)) {
            return errorCode + ": captain Team must use the fixed captain member";
        }
        return null;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    /** Mask or blank means keep the prior secret for the same channel id. */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private void handleRefresh(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            refreshCallback.run();
            sendResponse(exchange, 200, "application/json", "{\"ok\":true}");
        } catch (Exception e) {
            logger.error("刷新 ACP 服务失败", e);
            sendResponse(exchange, 500, "application/json",
                    "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    private void handleRefreshRobot(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            String body = readBody(exchange);
            JSONObject json = JSON.parseObject(body);
            String name = json.getString("name");
            String previousName = json.getString("previousName");
            if (name == null || name.trim().isEmpty()) {
                sendResponse(exchange, 400, "application/json", "{\"error\":\"name 不能为空\"}");
                return;
            }
            String current = name.trim();
            long startedAt = System.currentTimeMillis();
            if (refreshingRobots.putIfAbsent(current, startedAt) != null) {
                sendResponse(exchange, 409, "application/json",
                        "{\"error\":\"智能体正在刷新，请勿重复操作\"}");
                return;
            }
            try {
                refreshRobotCallback.accept(previousName == null ? "" : previousName.trim(),
                        current);
                sendResponse(exchange, 200, "application/json", "{\"ok\":true}");
            } finally {
                refreshingRobots.remove(current, startedAt);
            }
        } catch (Exception e) {
            logger.error("按 robot 刷新服务失败", e);
            String message = e.getMessage() == null ? "unknown error" : e.getMessage();
            sendResponse(exchange, 500, "application/json",
                    "{\"error\":\"" + message.replace("\"", "'") + "\"}");
        }
    }

    private void handleRefreshChannel(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            JSONObject json = JSON.parseObject(readBody(exchange));
            String channelId = json == null ? null : json.getString("channelId");
            String previousChannelId = json == null ? null : json.getString("previousChannelId");
            String current = channelId == null ? "" : channelId.trim();
            String previous = previousChannelId == null ? "" : previousChannelId.trim();
            if (current.isEmpty() && previous.isEmpty()) {
                sendResponse(exchange, 400, "application/json",
                        "{\"error\":\"channelId 不能为空\"}");
                return;
            }
            String refreshKey = current.isEmpty() ? previous : current;
            long startedAt = System.currentTimeMillis();
            if (refreshingChannels.putIfAbsent(refreshKey, startedAt) != null) {
                sendResponse(exchange, 409, "application/json",
                        "{\"error\":\"消息渠道正在刷新，请勿重复操作\"}");
                return;
            }
            try {
                refreshChannelCallback.accept(previous, current);
                sendResponse(exchange, 200, "application/json", "{\"ok\":true}");
            } finally {
                refreshingChannels.remove(refreshKey, startedAt);
            }
        } catch (Exception e) {
            logger.error("按 channel 刷新服务失败", e);
            String message = e.getMessage() == null ? "unknown error" : e.getMessage();
            sendResponse(exchange, 500, "application/json",
                    "{\"error\":\"" + message.replace("\"", "'") + "\"}");
        }
    }

    private void handleItemRefreshStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        JSONObject result = new JSONObject(true);
        result.put("robots", new java.util.LinkedHashMap<>(refreshingRobots));
        result.put("channels", new java.util.LinkedHashMap<>(refreshingChannels));
        sendResponse(exchange, 200, "application/json", JSON.toJSONString(result));
    }

    /**
     * 目录浏览 API：GET /api/browse-dir?path=/some/path
     * 返回规范化路径、父目录、文件系统根目录和完整子目录路径。
     * 路径拼接全部在服务端完成，以兼容 Windows 盘符、反斜杠和 UNC 路径。
     */
    private void handleBrowseDir(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String dirPath = param(exchange, "path");
        if (dirPath == null || dirPath.trim().isEmpty()) {
            dirPath = System.getProperty("user.home");
        }

        File dir = Paths.get(dirPath).toAbsolutePath().normalize().toFile();
        JSONObject result = new JSONObject();
        result.put("path", dir.getPath());
        result.put("parent", dir.getParent());
        result.put("roots", directoryEntries(File.listRoots()));

        if (!dir.exists() || !dir.isDirectory()) {
            result.put("dirs", new ArrayList<>());
            result.put("error", "not a directory");
            sendResponse(exchange, 200, "application/json", JSON.toJSONString(result));
            return;
        }

        File[] children = dir.listFiles(File::isDirectory);
        if (children != null) {
            java.util.Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        }
        result.put("dirs", directoryEntries(children));
        sendResponse(exchange, 200, "application/json", JSON.toJSONString(result));
    }

    private void handleAgentResourceMigration(HttpExchange exchange, boolean importing) throws IOException {
        if (!(importing ? "POST" : "GET").equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            AcpRobotParam robot = findConfiguredRobot(param(exchange, "robot"));
            AgentResourceMigration migration = new AgentResourceMigration();
            if (importing) {
                if (!"true".equals(param(exchange, "overwrite"))) {
                    throw new IllegalArgumentException("请先确认覆盖已有配置");
                }
                String kinds = param(exchange, "kinds");
                java.util.Set<String> selected = new java.util.LinkedHashSet<>();
                if (kinds != null && !kinds.isEmpty()) {
                    selected.addAll(java.util.Arrays.asList(kinds.split(",", -1)));
                }
                JSONObject result = migration.importZip(robot, exchange.getRequestBody(), selected);
                sendResponse(exchange, 200, "application/json", result.toJSONString());
            } else {
                byte[] bytes = migration.exportZip(robot);
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=agent-resources.zip");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
            }
        } catch (IllegalArgumentException e) {
            sendResponse(exchange, 400, "application/json",
                    JSON.toJSONString(apiError("INVALID_MIGRATION_REQUEST", e.getMessage())));
        } catch (Exception e) {
            sendResponse(exchange, 422, "application/json",
                    JSON.toJSONString(apiError("RESOURCE_MIGRATION_FAILED", e.getMessage())));
        }
    }

    private void handleAgentResourceTree(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            AcpRobotParam robot = findConfiguredRobot(param(exchange, "robot"));
            JSONObject result = agentResourceBrowser.tree(robot, param(exchange, "kind"));
            sendResponse(exchange, 200, "application/json", JSON.toJSONString(result));
        } catch (IllegalArgumentException e) {
            sendResponse(exchange, 400, "application/json",
                    JSON.toJSONString(apiError("INVALID_RESOURCE_REQUEST", e.getMessage())));
        } catch (Exception e) {
            logger.warn("读取智能体资源树失败", e);
            sendResponse(exchange, 500, "application/json",
                    JSON.toJSONString(apiError("RESOURCE_TREE_FAILED", e.getMessage())));
        }
    }

    private void handleAgentResourceContent(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            AcpRobotParam robot = findConfiguredRobot(param(exchange, "robot"));
            JSONObject result = agentResourceBrowser.content(robot,
                    param(exchange, "kind"), param(exchange, "resource"));
            int status = result.getBooleanValue("ok") ? 200 : 422;
            sendResponse(exchange, status, "application/json", JSON.toJSONString(result));
        } catch (IllegalArgumentException e) {
            sendResponse(exchange, 400, "application/json",
                    JSON.toJSONString(apiError("INVALID_RESOURCE_REQUEST", e.getMessage())));
        } catch (Exception e) {
            logger.warn("读取智能体资源内容失败", e);
            sendResponse(exchange, 500, "application/json",
                    JSON.toJSONString(apiError("RESOURCE_READ_FAILED", e.getMessage())));
        }
    }

    private void handleAgentMemoryDream(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            AcpRobotParam robot = findConfiguredRobot(param(exchange, "robot"));
            JSONObject result = new JSONObject(true);
            result.put("ok", true);
            if ("POST".equalsIgnoreCase(method)) {
                if (!memoryDreamTrigger.apply(robot.getName())) {
                    boolean running = Boolean.TRUE.equals(
                            memoryDreamStatus.apply(robot.getName()).get("running"));
                    sendResponse(exchange, running ? 409 : 503, "application/json",
                            JSON.toJSONString(apiError(running ? "DREAM_RUNNING" : "DREAM_QUEUE_FULL",
                                    running ? "记忆正在整理中，请稍候" : "记忆整理队列已满，请稍后重试")));
                    return;
                }
            }
            result.putAll(memoryDreamStatus.apply(robot.getName()));
            sendResponse(exchange, 200, "application/json", JSON.toJSONString(result));
        } catch (IllegalArgumentException e) {
            sendResponse(exchange, 400, "application/json",
                    JSON.toJSONString(apiError("INVALID_RESOURCE_REQUEST", e.getMessage())));
        } catch (IllegalStateException e) {
            sendResponse(exchange, 422, "application/json",
                    JSON.toJSONString(apiError("DREAM_UNAVAILABLE", e.getMessage())));
        } catch (Exception e) {
            logger.warn("记忆整理操作失败", e);
            sendResponse(exchange, 500, "application/json",
                    JSON.toJSONString(apiError("DREAM_FAILED", e.getMessage())));
        }
    }

    private AcpRobotParam findConfiguredRobot(String robotName) throws IOException {
        if (robotName == null || robotName.trim().isEmpty()) {
            throw new IllegalArgumentException("robot is required");
        }
        Path configPath = Paths.get(CONFIG_PATH);
        if (!Files.isRegularFile(configPath)) {
            throw new IllegalArgumentException("agent configuration does not exist");
        }
        JSONObject config = JSON.parseObject(new String(
                Files.readAllBytes(configPath), StandardCharsets.UTF_8));
        com.alibaba.fastjson.JSONArray robots = config.getJSONArray("robots");
        if (robots != null) {
            for (int i = 0; i < robots.size(); i++) {
                JSONObject item = robots.getJSONObject(i);
                if (item != null && robotName.equals(item.getString("name"))) {
                    return item.toJavaObject(AcpRobotParam.class);
                }
            }
        }
        throw new IllegalArgumentException("agent not found: " + robotName);
    }

    private List<JSONObject> directoryEntries(File[] directories) {
        List<JSONObject> result = new ArrayList<>();
        if (directories == null) {
            return result;
        }
        for (File directory : directories) {
            JSONObject entry = new JSONObject();
            String path = directory.toPath().toAbsolutePath().normalize().toString();
            String name = directory.getName();
            entry.put("path", path);
            entry.put("name", name == null || name.isEmpty() ? path : name);
            result.add(entry);
        }
        return result;
    }

    private void sendResponse(HttpExchange exchange, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(readAllBytes(is), StandardCharsets.UTF_8);
        }
    }

    private String readBody(HttpExchange exchange, long maxBytes) throws IOException {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null && parseLong(contentLength, -1L) > maxBytes) {
            throw new IllegalArgumentException("request body is too large");
        }
        try (InputStream input = exchange.getRequestBody();
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            long total = 0L;
            int length;
            while ((length = input.read(chunk)) >= 0) {
                total += length;
                if (total > maxBytes) {
                    throw new IllegalArgumentException("request body is too large");
                }
                buffer.write(chunk, 0, length);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = is.read(tmp)) != -1) {
            buffer.write(tmp, 0, n);
        }
        return buffer.toByteArray();
    }

    // ========== 更新 JAR 相关 ==========

    private void handleUpdateJar(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        if (!requestJarUpdate(false)) {
            sendResponse(exchange, 409, "application/json",
                    "{\"error\":\"更新进行中，请勿重复操作\"}");
            return;
        }
        sendResponse(exchange, 200, "application/json", "{\"ok\":true}");
    }

    private boolean requestJarUpdate(boolean automatic) {
        if (updatePreparedForRestart) {
            return false;
        }
        if (!updating.compareAndSet(false, true)) {
            return false;
        }
        automaticUpdate = automatic;
        updateProgress = 0;
        updateMessage = "正在检查最新版本...";
        updateStatus = "checking";
        Thread updater = new Thread(this::checkAndUpdateJar, "jar-updater");
        updater.setDaemon(true);
        updater.start();
        return true;
    }

    private void handleUpdateJarStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        JSONObject result = new JSONObject(true);
        result.put("status", updateStatus);
        result.put("progress", updateProgress);
        result.put("message", updateMessage);
        result.put("automatic", automaticUpdate);
        result.put("restartRequired", updatePreparedForRestart);
        sendResponse(exchange, 200, "application/json", JSON.toJSONString(result));
    }

    private void checkAndUpdateJar() {
        Path tmpFile = null;
        try {
            Path jarPath = getRunningJarPath();
            if (jarPath == null) {
                updateMessage = "无法确定当前运行的 JAR 路径";
                updateStatus = "error";
                return;
            }

            String updateBaseUrl = "https://" + CmdProxyConf.INSTANCE.getRemoteHost()
                    + "/download/cmd-proxy.jar";
            String expectedMd5 = downloadRemoteMd5(updateBaseUrl + ".md5");
            String currentMd5 = calculateMd5(jarPath);
            if (expectedMd5.equalsIgnoreCase(currentMd5)) {
                updateProgress = 100;
                updateMessage = "当前已是最新版本";
                updateStatus = "latest";
                logger.debug("JAR 已是最新版本: {}", jarPath);
                return;
            }

            updateProgress = 0;
            updateMessage = "正在下载最新版本";
            updateStatus = "downloading";
            String updateJarUrl = updateBaseUrl;
            logger.info("开始下载更新: {} -> {}", updateJarUrl, jarPath);

            HttpsURLConnection conn = openUpdateConnection(updateJarUrl, 60000);
            try {
                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    throw new IOException("下载失败，HTTP " + responseCode);
                }

                long totalSize = conn.getContentLengthLong();
                tmpFile = Files.createTempFile(jarPath.toAbsolutePath().getParent(),
                        ".cmd-proxy-update-", ".tmp");

                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(tmpFile)) {
                    byte[] buf = new byte[8192];
                    long downloaded = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        downloaded += n;
                        if (totalSize > 0) {
                            updateProgress = (int) (downloaded * 100 / totalSize);
                        }
                        updateMessage = "正在下载最新版本 " + (downloaded / 1024) + "KB" +
                                (totalSize > 0 ? " / " + (totalSize / 1024) + "KB" : "");
                    }
                }
            } finally {
                conn.disconnect();
            }

            String downloadedMd5 = calculateMd5(tmpFile);
            if (!expectedMd5.equalsIgnoreCase(downloadedMd5)) {
                throw new IOException("下载文件 MD5 校验失败");
            }

            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            if (isWindows) {
                // Windows下无法原地替换运行中的JAR，先写到.new.jar，再通过脚本异步替换
                Path newJar = jarPath.resolveSibling(".cmd-proxy-update-" +
                        UUID.randomUUID() + ".new.jar");
                Files.move(tmpFile, newJar, StandardCopyOption.REPLACE_EXISTING);
                tmpFile = null;

                Path scriptFile = jarPath.resolveSibling(".cmd-proxy-update-replace-" +
                        UUID.randomUUID() + ".bat");
                try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(scriptFile))) {
                    pw.println("@echo off");
                    pw.println("set OLD=" + jarPath.toAbsolutePath());
                    pw.println("set NEW=" + newJar.toAbsolutePath());
                    pw.println("set SELF=" + scriptFile.toAbsolutePath());
                    pw.println(":wait");
                    pw.println("ping 127.0.0.1 -n 2 >nul");
                    pw.println("move /Y \"%NEW%\" \"%OLD%\" >nul 2>&1");
                    pw.println("if errorlevel 1 goto wait");
                    pw.println("del \"%SELF%\"");
                }
                Runtime.getRuntime().exec(
                        new String[]{"cmd.exe", "/c", "start", "/min", "",
                                scriptFile.toAbsolutePath().toString()});

                updateProgress = 100;
                updatePreparedForRestart = true;
                updateMessage = "更新已准备完成，关闭程序后自动替换并重启";
                updateStatus = "done";
                logger.info("JAR 更新脚本已创建: {}", scriptFile);
            } else {
                Files.move(tmpFile, jarPath, StandardCopyOption.REPLACE_EXISTING);
                tmpFile = null;

                updateProgress = 100;
                updateMessage = "更新完成，重启后生效";
                updateStatus = "done";
                logger.info("JAR 更新完成: {}", jarPath);
            }

        } catch (Exception e) {
            logger.error("JAR 更新失败", e);
            updateMessage = "更新失败: " + e.getMessage();
            updateStatus = "error";
        } finally {
            if (tmpFile != null) {
                try {
                    Files.deleteIfExists(tmpFile);
                } catch (IOException cleanupError) {
                    logger.warn("清理 JAR 更新临时文件失败: {}", tmpFile, cleanupError);
                }
            }
            updating.set(false);
        }
    }

    private String downloadRemoteMd5(String md5Url) throws Exception {
        HttpsURLConnection conn = openUpdateConnection(md5Url, 10000);
        try {
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                throw new IOException("获取版本 MD5 失败，HTTP " + responseCode);
            }
            try (InputStream input = conn.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[256];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 1024) {
                        throw new IOException("版本 MD5 文件内容过大");
                    }
                    output.write(buffer, 0, count);
                }
                return parseMd5(new String(output.toByteArray(), StandardCharsets.US_ASCII));
            }
        } finally {
            conn.disconnect();
        }
    }

    private HttpsURLConnection openUpdateConnection(String targetUrl, int readTimeout)
            throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);

        HttpsURLConnection conn = (HttpsURLConnection) new URL(targetUrl).openConnection();
        conn.setSSLSocketFactory(sslContext.getSocketFactory());
        conn.setHostnameVerifier((hostname, session) -> true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(readTimeout);
        conn.connect();
        return conn;
    }

    static String parseMd5(String content) throws IOException {
        String value = content == null ? "" : content.trim();
        String hash = value.isEmpty() ? "" : value.split("\\s+", 2)[0];
        if (!hash.matches("(?i)[0-9a-f]{32}")) {
            throw new IOException("版本 MD5 文件格式无效");
        }
        return hash.toLowerCase(Locale.ROOT);
    }

    static String calculateMd5(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder result = new StringBuilder(32);
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private Path getRunningJarPath() {
        try {
            // 通过 CodeSource 获取当前 jar 路径
            URL location = ConfigUiServer.class.getProtectionDomain().getCodeSource().getLocation();
            Path path = Paths.get(location.toURI());
            if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
                return path;
            }
        } catch (Exception e) {
            logger.warn("通过 CodeSource 获取 JAR 路径失败", e);
        }
        // 备选：从启动命令解析
        try {
            String cmd = System.getProperty("sun.java.command");
            if (cmd != null) {
                String jarFile = cmd.split("\\s+")[0];
                Path path = Paths.get(jarFile).toAbsolutePath();
                if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
                    return path;
                }
            }
        } catch (Exception e) {
            logger.warn("通过 sun.java.command 获取 JAR 路径失败", e);
        }
        return null;
    }
}
