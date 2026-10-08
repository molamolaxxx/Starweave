package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.agent.KiroCliAgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.model.AgentModelCatalog;
import com.mola.cmd.proxy.app.acp.common.PathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ACP Client 抽象基类，封装子进程管理和 ACP 协议通信层。
 * <p>
 * 提供通用能力：
 * <ul>
 *   <li>子进程启动与 PATH 配置</li>
 *   <li>ACP initialize 协议握手</li>
 *   <li>JSON-RPC 2.0 request/response 收发</li>
 * </ul>
 * <p>
 * 子类通过实现 {@link #createSession()} 来定制 session/new 的参数（如是否加载 MCP）。
 */
public abstract class AbstractAcpClient implements Closeable {

    /**
     * AcpClient 生命周期状态
     */
    public enum State {
        CREATED, STARTING, READY, SLEEP, BUSY, ERROR, CLOSING, CLOSED
    }

    private static final Logger logger = LoggerFactory.getLogger(AbstractAcpClient.class);
    protected static final String JSONRPC_VERSION = "2.0";
    private static final int PROTOCOL_VERSION = 1;
    private static final String CLIENT_NAME = "cmd-proxy-acp";
    private static final String CLIENT_VERSION = "1.0.0";

    protected final AgentProvider agentProvider;
    protected final AcpClientIdentity clientIdentity;
    protected final String workspacePath;
    protected final String groupId;
    protected final AcpRobotParam robotParamRef;
    protected final Gson gson = new GsonBuilder().create();
    protected final AtomicInteger idCounter = new AtomicInteger(0);
    protected final AtomicReference<State> state = new AtomicReference<>(State.CREATED);
    private final AtomicLong lifecycleGeneration = new AtomicLong(0L);
    private final Map<Process, AgentProvider.RuntimeLease> runtimeLeases =
            new ConcurrentHashMap<>();
    private volatile State stateBeforeClose = State.CREATED;

    protected Process process;
    protected BufferedWriter writer;
    protected BufferedReader reader;
    protected String sessionId;
    protected volatile double contextUsagePercentage = -1;

    /** initialize 后暂存的 OAuth methodId，用于 session/new 失败时的认证回退 */
    private String pendingOAuthMethodId;

    /**
     * 使用指定 AgentProvider 创建（protected，供子类使用）。
     */
    protected AbstractAcpClient(AgentProvider agentProvider, String workspacePath, String groupId) {
        this(agentProvider, workspacePath, groupId, null);
    }

    /**
     * 使用指定 AgentProvider 和 robotParam 创建（protected，供子类使用）。
     */
    protected AbstractAcpClient(AgentProvider agentProvider, String workspacePath, String groupId, AcpRobotParam robotParam) {
        this(agentProvider, workspacePath,
                AcpClientIdentity.main(groupId, groupId,
                        robotParam != null ? robotParam.getName() : null),
                robotParam);
    }

    /**
     * 使用显式 Client 身份创建。
     */
    protected AbstractAcpClient(AgentProvider agentProvider, String workspacePath,
                                AcpClientIdentity clientIdentity, AcpRobotParam robotParam) {
        this.agentProvider = agentProvider;
        this.clientIdentity = Objects.requireNonNull(clientIdentity, "clientIdentity");
        this.workspacePath = (workspacePath == null || workspacePath.trim().isEmpty())
                ? System.getProperty("user.home")
                : workspacePath;
        this.groupId = clientIdentity.getLogicalId();
        this.robotParamRef = robotParam;
    }

    /**
     * 使用默认 KiroCliAgentProvider 创建。
     */
    public AbstractAcpClient(String workspacePath, String groupId) {
        this(new KiroCliAgentProvider(), workspacePath, groupId);
    }

    // ==================== 生命周期（模板方法） ====================

    /**
     * 启动 ACP Client：启动子进程 → initialize（带重试）→ createSession
     * <p>
     * Codex-ACP OAuth 场景（无 apiKey）：先尝试直接 session/new（依赖已缓存 token），
     * 失败后回退走 OAuth 认证（首次登录，需浏览器交互，URL 在 stderr 日志中）。
     */
    public void start() throws IOException {
        state.set(State.STARTING);
        startRuntimeSession();
    }

    /** Starts one provider runtime and creates or restores its ACP session. */
    protected void startRuntimeSession() throws IOException {
        try (AcpLaunchConcurrencyGuard.Lease ignored =
                     AcpLaunchConcurrencyGuard.acquire(
                             agentProvider, workspacePath, clientIdentity.getLogicalId())) {
            startProcessAndInitialize();
            try {
                createSession();
            } catch (IOException e) {
                if (pendingOAuthMethodId != null) {
                    logger.info("session/new 失败，回退到 OAuth 认证 (methodId={})，请查看 stderr 日志中的登录 URL", pendingOAuthMethodId);
                    authenticate(pendingOAuthMethodId);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    createSession();
                } else {
                    throw e;
                }
            }
            applyInitialSessionConfigOptions();
            state.set(State.READY);
            logger.info("ACP Client 就绪，sessionId={}, groupId={}", sessionId, groupId);
        } catch (IOException e) {
            state.set(State.ERROR);
            throw e;
        }
    }

    /** Releases only the provider runtime while retaining the logical client. */
    protected void releaseRuntimeForSleep() {
        closeCurrentSessionGracefully(State.READY);
    }

    /** Restarts a provider runtime for a logical client currently in SLEEP. */
    protected void wakeRuntimeSession() throws IOException {
        if (!state.compareAndSet(State.SLEEP, State.STARTING)) {
            throw new IllegalStateException("当前 client 不处于 SLEEP: " + state.get());
        }
        try {
            startRuntimeSession();
        } catch (IOException | RuntimeException failure) {
            closeCurrentProcess();
            state.compareAndSet(State.ERROR, State.SLEEP);
            state.compareAndSet(State.STARTING, State.SLEEP);
            throw failure;
        }
    }

    /**
     * 启动并初始化 ACP 进程。Provider 声明备用命令时，主命令不可用或初始化失败会自动回退。
     */
    private void startProcessAndInitialize() throws IOException {
        try {
            startProcess();
            initializeWithRetry();
        } catch (IOException primaryError) {
            if (!agentProvider.hasFallbackCommand()) {
                throw primaryError;
            }
            logger.warn("ACP 主启动命令失败，尝试备用命令 {}: {}",
                    agentProvider.getFallbackCommand(), primaryError.getMessage());
            closeCurrentProcess();
            pendingOAuthMethodId = null;
            try {
                startProcess(true);
                initializeWithRetry();
            } catch (IOException fallbackError) {
                fallbackError.addSuppressed(primaryError);
                throw fallbackError;
            }
        }
    }

    /**
     * 带重试的 initialize，应对子进程启动慢的场景（例如 Windows 下 .cmd 包装）保持容错。
     */
    private void initializeWithRetry() throws IOException {
        int maxRetries = 5;
        for (int i = 0; i < maxRetries; i++) {
            // 检查进程是否还活着
            if (process != null && !process.isAlive()) {
                int exitCode = process.exitValue();
                throw new IOException("ACP 子进程已退出 (exitCode=" + exitCode + ")，无法 initialize。请检查 [ACP STDERR] 日志获取崩溃原因。");
            }
            try {
                initialize();
                return;
            } catch (IOException e) {
                if (i == maxRetries - 1) {
                    throw e;
                }
                logger.warn("ACP initialize 失败 (第 {} 次重试): {}", i + 1, e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("initialize 被中断", ie);
                }
            }
        }
    }

    /**
     * 子类实现各自的 session/new 逻辑（如是否加载 MCP servers）。
     */
    protected abstract void createSession() throws IOException;

    /**
     * 在 session/new 或 session/load 完成后应用 Provider 声明的 session 配置。
     * <p>
     * 配置失败时直接中止启动，避免 robot 配置的模型与实际运行模型不一致。
     */
    private void applyInitialSessionConfigOptions() throws IOException {
        Map<String, String> configOptions =
                agentProvider.getInitialSessionConfigOptions(robotParamRef);
        if (configOptions == null || configOptions.isEmpty()) {
            return;
        }
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IOException("无法应用 ACP session 配置：sessionId 为空");
        }

        for (Map.Entry<String, String> entry : configOptions.entrySet()) {
            String configId = entry.getKey();
            String requestedValue = entry.getValue();
            if (configId == null || configId.trim().isEmpty()
                    || requestedValue == null || requestedValue.trim().isEmpty()) {
                continue;
            }

            JsonObject params = new JsonObject();
            params.addProperty("sessionId", sessionId);
            params.addProperty("configId", configId.trim());
            params.addProperty("value", requestedValue.trim());

            JsonObject response = sendRequest("session/set_config_option", params);
            String appliedValue = extractAppliedConfigValue(response, configId.trim());
            if (appliedValue == null) {
                throw new IOException("ACP session 配置响应中缺少 configId=" + configId);
            }
            logger.info("ACP session 配置成功, provider={}, configId={}, requested={}, applied={}, sessionId={}",
                    agentProvider.getName(), configId.trim(), requestedValue.trim(), appliedValue, sessionId);
        }
    }

    /**
     * 从 session/set_config_option 的完整 configOptions 响应中读取实际生效值。
     */
    private String extractAppliedConfigValue(JsonObject response, String configId) {
        JsonObject result = response != null ? response.getAsJsonObject("result") : null;
        JsonArray options = result != null ? result.getAsJsonArray("configOptions") : null;
        if (options == null) {
            return null;
        }
        for (JsonElement element : options) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject option = element.getAsJsonObject();
            if (option.has("id") && configId.equals(option.get("id").getAsString())
                    && option.has("currentValue") && !option.get("currentValue").isJsonNull()) {
                return option.get("currentValue").getAsString();
            }
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        if (!beginClose()) {
            return;
        }
        closeAfterBegin();
    }

    /**
     * 抢占关闭权。子类应在自身资源清理前调用，防止重复关闭。
     */
    protected boolean beginClose() {
        while (true) {
            State current = state.get();
            if (current == State.CLOSING || current == State.CLOSED) {
                return false;
            }
            if (state.compareAndSet(current, State.CLOSING)) {
                stateBeforeClose = current;
                lifecycleGeneration.incrementAndGet();
                return true;
            }
        }
    }

    /**
     * 已进入 CLOSING 后执行协议优先的进程关闭，并最终进入 CLOSED。
     */
    protected void closeAfterBegin() throws IOException {
        logger.info("关闭 AbstractAcpClient, logicalId={}, transportGroup={}",
                clientIdentity.getLogicalId(), clientIdentity.getTransportGroup());
        try {
            closeCurrentSessionGracefully(stateBeforeClose);
        } finally {
            state.set(State.CLOSED);
        }
    }

    /**
     * 正常关闭：Provider session close → 必要时关闭 stdin → 等待 → destroy → destroyForcibly。
     */
    private void closeCurrentSessionGracefully(State previousState) {
        Process currentProcess = process;
        boolean closeSent = false;

        if (currentProcess != null && currentProcess.isAlive()
                && writer != null && sessionId != null && !sessionId.trim().isEmpty()) {
            JsonObject params = new JsonObject();
            params.addProperty("sessionId", sessionId);
            try {
                if (previousState == State.BUSY) {
                    JsonObject cancelNotification = new JsonObject();
                    cancelNotification.addProperty("jsonrpc", JSONRPC_VERSION);
                    cancelNotification.addProperty("method", "session/cancel");
                    cancelNotification.add("params", params.deepCopy());
                    sendJson(cancelNotification);
                    logger.info("关闭 BUSY client 前已发送 session/cancel, sessionId={}", sessionId);
                }
                String closeMethod = agentProvider.getSessionCloseMethod();
                if (closeMethod != null && !closeMethod.trim().isEmpty()) {
                    sendJson(buildRequest(closeMethod.trim(), params));
                    closeSent = true;
                    logger.info("已发送 {}, sessionId={}, logicalId={}",
                            closeMethod.trim(), sessionId, clientIdentity.getLogicalId());
                }
                if (agentProvider.closeInputAfterSessionClose()) {
                    closeProcessInput();
                }
            } catch (IOException e) {
                logger.warn("发送 session close 失败，将进入进程关闭兜底, sessionId={}", sessionId, e);
            }
        }

        if (currentProcess != null && currentProcess.isAlive()
                && (closeSent || agentProvider.closeInputAfterSessionClose())
                && awaitProcessExit(currentProcess, gracefulShutdownTimeoutMillis())) {
            closeProcessStreams();
            clearProcessReferences();
            return;
        }

        if (currentProcess != null && currentProcess.isAlive()) {
            logger.warn("ACP 进程未在优雅关闭窗口内退出，执行 destroy, logicalId={}",
                    clientIdentity.getLogicalId());
            currentProcess.destroy();
            awaitProcessExit(currentProcess, destroyShutdownTimeoutMillis());
        }

        if (currentProcess != null && currentProcess.isAlive()) {
            logger.warn("ACP 进程 destroy 后仍未退出，执行 destroyForcibly, logicalId={}",
                    clientIdentity.getLogicalId());
            currentProcess.destroyForcibly();
            awaitProcessExit(currentProcess, forceShutdownTimeoutMillis());
        }

        closeProcessStreams();
        clearProcessReferences();
    }

    private void closeProcessInput() {
        BufferedWriter currentWriter = writer;
        writer = null;
        if (currentWriter == null) return;
        try {
            currentWriter.close();
        } catch (IOException e) {
            logger.debug("关闭 ACP stdin 失败, logicalId={}", clientIdentity.getLogicalId(), e);
        }
    }

    private boolean awaitProcessExit(Process target, long timeoutMillis) {
        try {
            return target.waitFor(Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("等待 ACP 进程退出被中断, logicalId={}", clientIdentity.getLogicalId());
            return false;
        }
    }

    protected long gracefulShutdownTimeoutMillis() {
        return 3000L;
    }

    protected long destroyShutdownTimeoutMillis() {
        return 1000L;
    }

    protected long forceShutdownTimeoutMillis() {
        return 1000L;
    }

    /**
     * 获取当前 lifecycle generation，供异步任务捕获。
     */
    protected long currentLifecycleGeneration() {
        return lifecycleGeneration.get();
    }

    /** Invalidates callbacks belonging to a provider runtime that has just been released. */
    protected void lifecycleBoundary() {
        lifecycleGeneration.incrementAndGet();
    }

    /**
     * generation 未变化且 client 未进入关闭态时才视为有效。
     */
    protected boolean isLifecycleGenerationActive(long generation) {
        State current = state.get();
        return lifecycleGeneration.get() == generation
                && current != State.CLOSING
                && current != State.CLOSED;
    }

    /**
     * generation 有效时执行严格的状态 CAS。
     */
    protected boolean compareAndSetStateIfActive(long generation, State expected, State update) {
        if (!isLifecycleGenerationActive(generation)) {
            return false;
        }
        return state.compareAndSet(expected, update)
                && lifecycleGeneration.get() == generation;
    }

    /**
     * generation 有效时更新状态，关闭开始后不会覆盖 CLOSING/CLOSED。
     */
    protected boolean setStateIfActive(long generation, State update) {
        while (isLifecycleGenerationActive(generation)) {
            State current = state.get();
            if (current == State.CLOSING || current == State.CLOSED) {
                return false;
            }
            if (state.compareAndSet(current, update)) {
                return lifecycleGeneration.get() == generation;
            }
        }
        return false;
    }

    /**
     * 启动失败/备用命令切换时使用的非会话级快速清理。
     */
    private void closeCurrentProcess() {
        Process currentProcess = process;
        closeProcessStreams();
        if (currentProcess != null && currentProcess.isAlive()) {
            currentProcess.destroy();
        }
        clearProcessReferences();
    }

    private void closeProcessStreams() {
        try { if (writer != null) writer.close(); } catch (IOException e) { /* ignore */ }
        try { if (reader != null) reader.close(); } catch (IOException e) { /* ignore */ }
    }

    private void clearProcessReferences() {
        writer = null;
        reader = null;
        process = null;
    }

    // ==================== 协议步骤 ====================

    protected void startProcess() throws IOException {
        startProcess(false);
    }

    private void startProcess(boolean useFallbackCommand) throws IOException {
        ProcessBuilder pb = new ProcessBuilder();
        pb.redirectErrorStream(false);

        String home = System.getProperty("user.home");
        String currentPath = pb.environment().getOrDefault("PATH", "");
        // Windows: 进程可能从精简环境启动（如服务/计划任务），PATH 不完整，从注册表读取完整 PATH
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            String regPath = readWindowsRegistryPath();
            if (regPath != null && !regPath.isEmpty()) {
                pb.environment().put("PATH", regPath);
            }
        } else {
            // Linux / macOS: 通过 login shell 获取完整 PATH，前置追加常用工具路径
            pb.environment().put("PATH", PathResolver.enrichPath(home, currentPath));
        }

        // 追加 provider 特定的额外环境变量（如 OPENCODE_CONFIG_CONTENT）
        Map<String, String> extraEnv = agentProvider.getExtraEnv(robotParamRef);
        if (extraEnv != null && !extraEnv.isEmpty()) {
            pb.environment().putAll(extraEnv);
        }

        // 按 robot 维度注入 HTTP 代理环境变量
        if (robotParamRef != null && robotParamRef.isProxyEnabled()) {
            String proxy = robotParamRef.getHttpProxy();
            if (proxy != null && !proxy.trim().isEmpty()) {
                String url = proxy.contains("://") ? proxy.trim() : "http://" + proxy.trim();
                pb.environment().put("HTTP_PROXY", url);
                pb.environment().put("http_proxy", url);
                pb.environment().put("HTTPS_PROXY", url);
                pb.environment().put("https_proxy", url);
            }
            String noProxy = robotParamRef.getNoProxy();
            if (noProxy != null && !noProxy.trim().isEmpty()) {
                pb.environment().put("NO_PROXY", noProxy.trim());
                pb.environment().put("no_proxy", noProxy.trim());
            }
        }

        // 备用命令是已存在的独立运行路径，不重复准备主 Provider 的托管 runtime。
        // latest 的 registry 检查和安装由后台维护任务完成，普通 ACP 启动只解析本地版本。
        AgentProvider.RuntimeLease preparedRuntimeLease = AgentProvider.RuntimeLease.NONE;
        if (!useFallbackCommand) {
            preparedRuntimeLease = agentProvider.prepareRuntimeLaunch(
                    robotParamRef, pb.environment());
        }

        // prepareLaunch 可能刚刚安装了可执行文件，因此在准备完成后再解析最终启动命令。
        List<String> cmd = new ArrayList<>();
        try {
            cmd.add(useFallbackCommand ? agentProvider.getFallbackCommand()
                    : agentProvider.getCommand(robotParamRef, pb.environment()));
            cmd.addAll(Arrays.asList(useFallbackCommand ? agentProvider.getFallbackArgs()
                    : agentProvider.getArgs(robotParamRef, pb.environment())));

            // 追加 provider 特定的额外参数（如 --model）
            List<String> extraArgs = agentProvider.getExtraArgs(robotParamRef);
            if (extraArgs != null && !extraArgs.isEmpty()) {
                cmd.addAll(extraArgs);
            }
            pb.command(cmd);
        } catch (RuntimeException e) {
            preparedRuntimeLease.close();
            throw e;
        }

        logger.info("启动 ACP 进程: {}, PATH contains node: {}", cmd,
                pb.environment().getOrDefault("PATH", "").contains("node"));
        Process startedProcess;
        try {
            startedProcess = pb.start();
        } catch (IOException | RuntimeException e) {
            preparedRuntimeLease.close();
            throw e;
        }
        if (preparedRuntimeLease != AgentProvider.RuntimeLease.NONE) {
            runtimeLeases.put(startedProcess, preparedRuntimeLease);
        }
        process = startedProcess;
        writer = new BufferedWriter(new OutputStreamWriter(startedProcess.getOutputStream(), StandardCharsets.UTF_8));
        reader = new BufferedReader(new InputStreamReader(startedProcess.getInputStream(), StandardCharsets.UTF_8));

        // stderr 日志转发（用 INFO 级别，方便排查子进程崩溃原因）
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(startedProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = errReader.readLine()) != null) {
                    logger.info("[ACP STDERR][{}] {}", groupId, line);
                }
            } catch (IOException e) {
                // 进程关闭时正常退出
            }
            try {
                int exitCode = startedProcess.waitFor();
                logger.warn("[ACP STDERR][{}] 进程已退出, exitCode={}", groupId, exitCode);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (!startedProcess.isAlive()) {
                    releaseRuntimeLease(startedProcess);
                }
            }
        }, "acp-stderr-" + groupId);
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    private void releaseRuntimeLease(Process exitedProcess) {
        AgentProvider.RuntimeLease lease = runtimeLeases.remove(exitedProcess);
        if (lease != null) lease.close();
    }

    protected void initialize() throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", PROTOCOL_VERSION);

        JsonObject capabilities = new JsonObject();
        JsonObject sessionCapabilities = new JsonObject();
        sessionCapabilities.add("configOptions", new JsonObject());
        if (agentProvider.supportsCompactionUpdates()) {
            sessionCapabilities.add("compaction", new JsonObject());
        }
        capabilities.add("session", sessionCapabilities);
        params.add("clientCapabilities", capabilities);

        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", CLIENT_NAME);
        clientInfo.addProperty("version", CLIENT_VERSION);
        params.add("clientInfo", clientInfo);

        JsonObject response = sendRequest("initialize", params);
        JsonObject result = response.getAsJsonObject("result");
        logger.info("ACP initialize 完成: {}", result);

        // Codex-ACP 兼容：如果 init 响应包含 authMethods，按策略处理认证
        if (result != null && result.has("authMethods") && result.get("authMethods").isJsonArray()) {
            String methodId = resolveAuthMethodId(result.getAsJsonArray("authMethods"));
            if (methodId != null) {
                authenticate(methodId);
            }
        }
    }

    /**
     * 从 authMethods 中选择认证方式。
     * - 有 apiKey → 返回 api-key 类 methodId，立即认证
     * - 无 apiKey → 暂存 OAuth methodId 到 pendingOAuthMethodId，返回 null（不立即认证）
     */
    private String resolveAuthMethodId(JsonArray authMethods) {
        boolean hasApiKey = robotParamRef != null
                && robotParamRef.getApiKey() != null
                && !robotParamRef.getApiKey().trim().isEmpty();

        String apiKeyMethod = null;
        String oauthMethod = null;

        for (JsonElement el : authMethods) {
            if (!el.isJsonObject()) continue;
            JsonObject method = el.getAsJsonObject();
            String id = method.has("id") ? method.get("id").getAsString() : "";
            if ("api-key".equals(id) || "openai-api-key".equals(id) || "codex-api-key".equals(id)) {
                if (apiKeyMethod == null) apiKeyMethod = id;
            } else if ("chatgpt".equals(id) || "chat-gpt".equals(id)) {
                if (oauthMethod == null) oauthMethod = id;
            }
        }

        if (hasApiKey && apiKeyMethod != null) {
            return apiKeyMethod;
        }

        // 无 apiKey：暂存 OAuth methodId，等 session/new 失败时再走
        if (oauthMethod != null) {
            pendingOAuthMethodId = oauthMethod;
            logger.info("未配置 apiKey，暂存 OAuth methodId={}，先尝试直接 session/new", oauthMethod);
        }
        return null;
    }

    /**
     * 执行 ACP authenticate（Codex-ACP 协议要求）。
     */
    protected void authenticate(String methodId) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("methodId", methodId);
        JsonObject response = sendRequest("authenticate", params);
        logger.info("ACP authenticate 完成, methodId={}", methodId);
    }

    // ==================== JSON-RPC 工具方法 ====================

    protected JsonObject sendRequest(String method, JsonObject params) throws IOException {
        JsonObject request = buildRequest(method, params);
        String id = request.get("id").getAsString();
        sendJson(request);

        while (true) {
            String line = reader.readLine();
            if (line == null) {
                throw new IOException("ACP 进程意外关闭");
            }
            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) continue;

            JsonObject resp;
            try {
                resp = JsonParser.parseString(trimmed).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                continue;
            }

            if (!resp.has("id")) continue;
            if (id.equals(resp.get("id").getAsString())) {
                if (resp.has("error")) {
                    throw new IOException("ACP JSON-RPC error: " + resp.get("error"));
                }
                AgentModelCatalog.getInstance().recordAcpResponse(robotParamRef, resp);
                return resp;
            }
        }
    }

    protected JsonObject buildRequest(String method, JsonObject params) {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", JSONRPC_VERSION);
        request.addProperty("id", idCounter.getAndIncrement());
        request.addProperty("method", method);
        request.add("params", params);
        return request;
    }

    protected void sendJson(JsonObject json) throws IOException {
        String text = gson.toJson(json);
        logger.debug("acp输入 {}", redactSensitiveJson(json));
        synchronized (writer) {
            writer.write(text);
            writer.newLine();
            writer.flush();
        }
    }

    /**
     * 在后台线程中写入 JSON 到子进程 stdin，立即返回不阻塞调用方。
     * <p>
     * 用于避免 Windows 管道死锁：当请求体很大时（如含 base64 图片），
     * 同步写入可能因 stdin pipe 缓冲区满而阻塞，而此时如果子进程尝试向
     * stdout 写入通知但 stdout pipe 也满（无人读取），则形成双向死锁。
     * <p>
     * 通过将写入放到独立线程，调用方可以立即开始读取 stdout，确保 stdout
     * pipe 持续被消费，打破死锁条件。
     *
     * @param json 要发送的 JSON-RPC 消息
     * @param writeError 用于捕获写入异常的容器，调用方可在读取循环中检查
     */
    protected void sendJsonInBackground(JsonObject json, java.util.concurrent.atomic.AtomicReference<IOException> writeError) {
        String text = gson.toJson(json);
        logger.debug("acp输入(async) {}", redactSensitiveJson(json));
        Thread writeThread = new Thread(() -> {
            try {
                synchronized (writer) {
                    writer.write(text);
                    writer.newLine();
                    writer.flush();
                }
            } catch (IOException e) {
                logger.error("异步写入 stdin 失败", e);
                if (writeError != null) {
                    writeError.set(e);
                }
            }
        }, "acp-stdin-write");
        writeThread.setDaemon(true);
        writeThread.start();
    }

    private String redactSensitiveJson(JsonObject json) {
        JsonObject copy = json.deepCopy();
        redactSensitiveValues(copy);
        return gson.toJson(copy);
    }

    private void redactSensitiveValues(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                redactSensitiveValues(child);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry
                : new ArrayList<>(object.entrySet())) {
            if (isSensitiveJsonKey(entry.getKey())) {
                object.addProperty(entry.getKey(), "[REDACTED]");
            } else {
                redactSensitiveValues(entry.getValue());
            }
        }
    }

    private boolean isSensitiveJsonKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toUpperCase(Locale.ROOT).replace('-', '_');
        return normalized.contains("API_KEY")
                || normalized.contains("AUTH_TOKEN")
                || normalized.contains("OAUTH_TOKEN")
                || normalized.contains("AUTHORIZATION")
                || normalized.contains("SECRET")
                || "ANTHROPIC_CUSTOM_HEADERS".equals(normalized);
    }

    /**
     * 同步发送 prompt 并阻塞等待完整响应文本。
     * <p>
     * 提取自 MemoryAcpClient / AbilityReflectionAcpClient 的公共逻辑，
     * 供所有"同步阻塞式子 Client"复用。自动处理 permission 请求。
     *
     * @param promptText 发送给 agent 的完整 prompt
     * @param clientName 客户端名称，用于异常消息（如 "Memory ACP"、"SubAgent"）
     * @return agent 的完整回答文本
     * @throws IOException 通信失败
     */
    protected String doSendPromptSync(String promptText, String clientName) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);

        JsonArray prompt = new JsonArray();
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", promptText);
        prompt.add(textBlock);
        params.add("prompt", prompt);

        JsonObject request = buildRequest("session/prompt", params);
        String requestId = request.get("id").getAsString();
        sendJson(request);

        StringBuilder fullResponse = new StringBuilder();
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                throw new IOException(clientName + " 进程意外关闭");
            }

            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) continue;

            JsonObject msg;
            try {
                msg = JsonParser.parseString(trimmed).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                continue;
            }

            // prompt response — turn 结束（JSON-RPC Response 没有 method 字段，排除 Request 误匹配）
            if (!msg.has("method") && msg.has("id") && requestId.equals(msg.get("id").getAsString())) {
                drainLateChunksSync(fullResponse);
                return fullResponse.toString();
            }

            // session/request_permission — 自动 allow
            if (msg.has("method") && "session/request_permission".equals(msg.get("method").getAsString())) {
                autoAllowPermission(msg);
                continue;
            }

            // session/update — 拼接文本
            if (msg.has("method") && "session/update".equals(msg.get("method").getAsString())) {
                String text = extractAgentMessageText(msg);
                if (text != null) {
                    fullResponse.append(text);
                }
            }
        }
    }

    /**
     * 按 Provider/Robot 策略回复 permission 请求。
     */
    protected void autoAllowPermission(JsonObject msg) throws IOException {
        if (!msg.has("id")) return;
        JsonElement permId = msg.get("id");

        logger.info("收到 permission 请求: {}", gson.toJson(msg));

        AgentProvider.PermissionPolicy policy = agentProvider.getPermissionPolicy(robotParamRef);
        String selectedOptionId = null;
        JsonObject params = msg.getAsJsonObject("params");
        if (params != null && params.has("options") && params.get("options").isJsonArray()) {
            selectedOptionId = selectPermissionOption(
                    params.getAsJsonArray("options"), policy);
        }

        JsonObject outcomeObj = new JsonObject();
        if (selectedOptionId == null) {
            // 无可用的安全选项时 fail closed，不伪造 Agent 未提供的 optionId。
            outcomeObj.addProperty("outcome", "cancelled");
        } else {
            outcomeObj.addProperty("outcome", "selected");
            outcomeObj.addProperty("optionId", selectedOptionId);
        }
        JsonObject permResult = new JsonObject();
        permResult.add("outcome", outcomeObj);
        JsonObject permResp = new JsonObject();
        permResp.addProperty("jsonrpc", JSONRPC_VERSION);
        permResp.add("id", permId);
        permResp.add("result", permResult);
        logger.info("回复 permission: policy={}, selectedOptionId={}, response={}",
                policy, selectedOptionId, gson.toJson(permResp));
        sendJson(permResp);
    }

    String selectPermissionOption(JsonArray options, AgentProvider.PermissionPolicy policy) {
        String allowAlways = null;
        String allowOnce = null;
        String reject = null;
        for (JsonElement option : options) {
            if (!option.isJsonObject()) continue;
            JsonObject object = option.getAsJsonObject();
            String kind = object.has("kind") ? object.get("kind").getAsString() : "";
            String optionId = object.has("optionId") ? object.get("optionId").getAsString() : "";
            if (optionId.trim().isEmpty()) continue;
            if ("allow_always".equals(kind) && allowAlways == null) allowAlways = optionId;
            if ("allow_once".equals(kind) && allowOnce == null) allowOnce = optionId;
            if (("reject_once".equals(kind) || "reject_always".equals(kind)) && reject == null) {
                reject = optionId;
            }
        }
        if (policy == AgentProvider.PermissionPolicy.REJECT) return reject;
        if (policy == AgentProvider.PermissionPolicy.ALLOW_ONCE) return allowOnce;
        return allowAlways != null ? allowAlways : allowOnce;
    }

    /**
     * 从 session/update 消息中提取 agent_message_chunk 的文本。
     *
     * @return 文本内容，非 agent_message_chunk 时返回 null
     */
    protected String extractAgentMessageText(JsonObject msg) {
        JsonObject updateParams = msg.getAsJsonObject("params");
        if (updateParams == null) return null;
        JsonObject update = updateParams.getAsJsonObject("update");
        if (update == null) return null;

        String updateType = update.has("sessionUpdate")
                ? update.get("sessionUpdate").getAsString() : "";
        if (!"agent_message_chunk".equals(updateType)) return null;

        JsonObject content = update.getAsJsonObject("content");
        if (content != null && content.has("text")) {
            return content.get("text").getAsString();
        }
        return null;
    }

    /**
     * 排空迟到 chunk（同步版，供 doSendPromptSync 使用）。
     * sleep 让管道里迟到的数据到位，然后一次性抽干 reader 缓冲区。
     */
    private void drainLateChunksSync(StringBuilder fullResponse) throws IOException {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        while (reader.ready()) {
            String line = reader.readLine();
            if (line == null) break;

            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) continue;

            JsonObject msg;
            try {
                msg = JsonParser.parseString(trimmed).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                continue;
            }

            String text = extractAgentMessageText(msg);
            if (text != null) {
                fullResponse.append(text);
            }
        }
    }


    // ==================== Getters ====================

    public String getSessionId() { return sessionId; }
    public String getGroupId() { return groupId; }
    public AcpClientIdentity getClientIdentity() { return clientIdentity; }
    public long getLifecycleGeneration() { return lifecycleGeneration.get(); }
    public State getState() { return state.get(); }
    public String getWorkspacePath() { return workspacePath; }
    public double getContextUsagePercentage() { return contextUsagePercentage; }
    protected void setSessionId(String sessionId) { this.sessionId = sessionId; }

    // ==================== Windows PATH 工具方法 ====================

    /**
     * 从 Windows 注册表读取系统级 + 用户级 PATH，合并返回。
     * 解决从精简环境（服务/计划任务）启动时 PATH 不完整的问题。
     */
    private static String readWindowsRegistryPath() {
        try {
            String systemPath = queryRegistry(
                    "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment", "Path");
            String userPath = queryRegistry("HKCU\\Environment", "Path");
            StringBuilder sb = new StringBuilder();
            if (systemPath != null) sb.append(systemPath);
            if (userPath != null) {
                if (sb.length() > 0) sb.append(File.pathSeparator);
                sb.append(userPath);
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            logger.warn("读取 Windows 注册表 PATH 失败: {}", e.getMessage());
            return null;
        }
    }

    private static String queryRegistry(String key, String valueName) throws IOException {
        Process p = new ProcessBuilder("reg", "query", key, "/v", valueName)
                .redirectErrorStream(true).start();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                // 格式: "    Path    REG_SZ    C:\xxx;C:\yyy"  或 REG_EXPAND_SZ
                if (line.contains("REG_SZ") || line.contains("REG_EXPAND_SZ")) {
                    String[] parts = line.split("(REG_SZ|REG_EXPAND_SZ)", 2);
                    if (parts.length == 2) {
                        return parts[1].trim();
                    }
                }
            }
        }
        return null;
    }
}
