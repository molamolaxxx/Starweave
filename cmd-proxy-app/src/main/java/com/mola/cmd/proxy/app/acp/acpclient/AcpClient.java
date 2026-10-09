package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.AutoNewSessionConfig;
import com.mola.cmd.proxy.app.acp.action.ActionRuntimeRegistry;
import com.mola.cmd.proxy.app.acp.action.ActionToolService;
import com.mola.cmd.proxy.app.acp.action.CmdProxyMcpHttpHandler;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProviderRouter;
import com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage;
import com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;
import com.mola.cmd.proxy.app.acp.acpclient.listener.DefaultAcpResponseListener;
import com.mola.cmd.proxy.app.acp.acpclient.listener.LifecycleGuardedAcpResponseListener;
import com.mola.cmd.proxy.app.acp.gateway.CompositeAcpResponseListener;
import com.mola.cmd.proxy.app.acp.gateway.GatewayAcpResponseListener;
import com.mola.cmd.proxy.app.acp.schedule.ScheduleContextInjector;
import com.mola.cmd.proxy.app.acp.observation.ObservationManager;
import com.mola.cmd.proxy.app.acp.schedule.ScheduleTaskManager;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleOwnerKey;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelTurnContext;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelDeliveryContext;
import com.mola.cmd.proxy.app.acp.mcpauth.McpAuthManager;
import com.mola.cmd.proxy.app.acp.subagent.SubAgentContextInjector;
import com.mola.cmd.proxy.app.acp.subagent.SubAgentDispatcher;
import com.mola.cmd.proxy.app.acp.subagent.model.SubAgentResult;
import com.mola.cmd.proxy.app.acp.subagent.model.SubAgentTask;
import com.mola.cmd.proxy.app.acp.talkto.TalkToContextInjector;
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToMessage;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * ACP 主 Client，继承 {@link AbstractAcpClient}，负责：
 * <ul>
 *   <li>MCP Server 配置加载</li>
 *   <li>流式 prompt 读取与回调</li>
 *   <li>文件处理与会话历史管理</li>
 *   <li>记忆系统集成（通过 MemoryManager 注入）</li>
 * </ul>
 */
public class AcpClient extends AbstractAcpClient {

    private static final Logger logger = LoggerFactory.getLogger(AcpClient.class);
    private static final String CHANNEL_REPLY_TARGET = "回复";
    private static final long CHANNEL_REPLY_CONTINUATION_TTL_MS = 10L * 60L * 1000L;
    private static final int MAX_CONSECUTIVE_INBOX_TURNS = 2;
    private static final long INBOX_FAIRNESS_DELAY_MS = 250L;
    private static final ScheduledExecutorService INBOX_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "acp-inbox-scheduler");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * Pending channel origins keyed by the internal contact expected to answer this client.
     * The route stays local to the owning client and is never exposed to the contacted Agent.
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, PendingChannelReply>>
            pendingChannelReplies = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "acp-send-worker");
        t.setDaemon(true);
        return t;
    });

    /** MCP 配置文件路径列表，按优先级从低到高排列 */
    private final List<Path> mcpConfigPaths;
    /** Composition-root supplied runtime MCP servers, snapshotted before session start. */
    private volatile JsonArray additionalMcpServers = new JsonArray();
    private final String authSessionId;
    /** Active logical turn exposed to the built-in cmd-proxy MCP server. */
    private final AtomicReference<AcpResponseListener> activeMcpListener = new AtomicReference<>();
    private final AtomicReference<PromptOptions> activeMcpOptions = new AtomicReference<>();
    /** Set before executor admission so task cancellation cannot race the worker startup gap. */
    private final AtomicReference<PromptOptions> acceptedPromptOptions = new AtomicReference<>();
    /** Allows a task-control cancellation to recover provider cancel errors back to READY. */
    private final AtomicBoolean queuedWorkCancellationPending = new AtomicBoolean(false);
    private final AtomicInteger consecutiveInboxTurns = new AtomicInteger();
    private final AtomicBoolean deferredInboxScheduled = new AtomicBoolean();

    /** 会话上下文管理器 */
    private final ConversationHistoryManager historyManager;

    private AcpResponseListener globalListener;
    /** External gateway projection is composed per callback without replacing the owning surface listener. */
    private final AcpResponseListener gatewayProjection;

    /** 记忆管理器，通过 setter 注入，未启用时为 null */
    private MemoryManagerBridge memoryManager;

    /** 子 Agent 派发器，通过 setter 注入，未配置子 Agent 时为 null */
    private volatile SubAgentDispatcher subAgentDispatcher;

    /** 子 Agent 上下文注入器，通过 setter 注入 */
    private SubAgentContextInjector subAgentContextInjector;

    /** 全局 robot 注册表引用，用于子 Agent 上下文构建 */
    private Map<String, AcpRobotParam> globalRobotRegistry;

    /** 定时任务管理器，通过 setter 注入，未启用时为 null */
    private volatile ScheduleTaskManager scheduleTaskManager;
    private volatile ObservationManager observationManager;
    private ScheduleOwnerKey observationOwner;

    /** 定时任务上下文注入器，通过 setter 注入 */
    private ScheduleContextInjector scheduleContextInjector;

    /** 定时任务显式 owner；MAIN 与 TEAM 不再共享 robotName key。 */
    private ScheduleOwnerKey scheduleOwnerKey;

    /** TalkTo 消息投递器，通过 setter 注入，未配置通讯录时为 null */
    private volatile TalkToDispatcher talkToDispatcher;

    /** TalkTo 上下文注入器，通过 setter 注入 */
    private TalkToContextInjector talkToContextInjector;

    /** TalkTo 专用 robot 注册表；与 subAgent registry 分离，避免 Team 白名单串扰。 */
    private Map<String, AcpRobotParam> talkToRobotRegistry;

    /** 绑定的 robot 参数，构造时传入，不可变 */
    private final AcpRobotParam robotParam;

    /** 强制创建新会话，跳过历史会话恢复（用于 clearContext 场景） */
    private boolean forceNewSession = false;

    /** 指定恢复的目标 sessionId（用于 acpRestoreSession 场景） */
    private String targetRestoreSessionId;

    /** session/new 或 session/load 完成并进入 READY 后执行的非阻塞生命周期回调。 */
    private Runnable afterSessionReady;

    /** 每个 prompt turn 真正完成 BUSY -> READY 后执行。 */
    private Runnable afterTurnReady;

    /** prompt turn 异常终止并离开可投递生命周期时执行。 */
    private Runnable afterTurnFailed;

    /** 中断 pending 存在时，将 provider 的取消异常恢复成可继续投递的 READY。 */
    private BooleanSupplier recoverTurnFailureToReady;

    /** 当前 client 是否通过 session/load 恢复。 */
    private volatile boolean restoredSession;

    /** session 加载完成时的 turn 数，用于 close 时判断是否有新对话 */
    private int initialTurnCount;

    /** 当前 session 最后一条用户或 assistant 消息时间；无消息时为 0。 */
    private final AtomicLong lastMessageAt = new AtomicLong(0L);

    /** 最近一次真正处于可接收请求状态的时间，用于计算空闲时长。 */
    private final AtomicLong lastActivityAt = new AtomicLong(0L);

    /** 睡眠唤醒并创建新会话后，通知持有者同步外部会话身份（可为 null）。 */
    private volatile SessionRotationListener sessionRotationListener;

    /** MAIN lifecycle persistence; Team sleep state is already part of TeamDefinition. */
    private volatile SleepStateListener sleepStateListener;

    /** Codex/Claude/Kiro 压缩完成后置为 true，下一次 prompt 完整重注入 ACP harness。 */
    private final AtomicBoolean acpHarnessReinjectionPending = new AtomicBoolean(false);

    /** 当前 provider 是否已报告上下文压缩开始。 */
    private boolean compactionInProgress;

    /**
     * 使用指定 AgentProvider 创建 AcpClient（包级私有，供未来扩展）。
     */
    AcpClient(AgentProvider agentProvider, String workspacePath, String groupId, AcpRobotParam robotParam) {
        this(agentProvider, workspacePath,
                AcpClientIdentity.main(
                        groupId,
                        robotParam != null && !robotParam.getName().isEmpty()
                                ? robotParam.getName() : groupId,
                        robotParam != null ? robotParam.getName() : null),
                robotParam);
    }

    /**
     * 使用显式 Client 身份创建，供 TEAM 等隔离作用域使用。
     */
    AcpClient(AgentProvider agentProvider, String workspacePath,
              AcpClientIdentity clientIdentity, AcpRobotParam robotParam) {
        this(agentProvider, workspacePath, clientIdentity, robotParam,
                new ConversationHistoryManager(clientIdentity));
    }

    /** 包级测试挂点：允许注入隔离的历史目录，不改变生产构造行为。 */
    AcpClient(AgentProvider agentProvider, String workspacePath,
              AcpClientIdentity clientIdentity, AcpRobotParam robotParam,
              ConversationHistoryManager historyManager) {
        super(agentProvider, workspacePath, clientIdentity, robotParam);
        this.robotParam = robotParam;
        this.historyManager = Objects.requireNonNull(historyManager, "historyManager");
        this.globalListener = new DefaultAcpResponseListener(clientIdentity.getTransportGroup());
        this.gatewayProjection = new GatewayAcpResponseListener(this);
        this.mcpConfigPaths = agentProvider.getMcpConfigPaths(this.workspacePath, robotParam);
        this.authSessionId = McpAuthManager.getInstance().createSession(
                clientIdentity.getTransportGroup());
        ActionToolService actionToolService = new ActionToolService(
                this::executeMcpDispatchSubagent,
                args -> executeMcpSchedule("schedule_task", args),
                args -> executeMcpSchedule("manage_schedule", args),
                this::executeMcpTalkTo,
                this::executeMcpNewSession,
                args -> executeMcpObservation("manage_observation_channels", args),
                args -> executeMcpObservation("test_observation_script", args),
                args -> executeMcpObservation("query_observation_events", args));
        ActionRuntimeRegistry.getInstance().register(authSessionId,
                actionToolService::execute, this::availableActionTools);
    }

    /**
     * 使用默认 AgentProvider 创建 AcpClient。
     * 如果 robotParam 指定了 agentProvider，通过路由器解析。
     */
    public AcpClient(String workspacePath, String groupId, AcpRobotParam robotParam) {
        this(AgentProviderRouter.getInstance().resolve(
                        robotParam != null ? robotParam.getAgentProvider() : null),
                workspacePath, groupId, robotParam);
    }

    /**
     * 使用显式身份创建 AcpClient。
     */
    public AcpClient(String workspacePath, AcpClientIdentity clientIdentity,
                     AcpRobotParam robotParam) {
        this(AgentProviderRouter.getInstance().resolve(
                        robotParam != null ? robotParam.getAgentProvider() : null),
                workspacePath, clientIdentity, robotParam);
    }

    /**
     * 注入记忆管理器。通过桥接接口解耦，避免 acpclient 包直接依赖 memory 包。
     */
    public void setMemoryManager(MemoryManagerBridge memoryManager) {
        this.memoryManager = memoryManager;
    }

    public void setAfterSessionReady(Runnable afterSessionReady) {
        this.afterSessionReady = afterSessionReady;
    }

    public void setAfterTurnReady(Runnable afterTurnReady) {
        this.afterTurnReady = afterTurnReady;
    }

    public void setAfterTurnFailed(Runnable afterTurnFailed) {
        this.afterTurnFailed = afterTurnFailed;
    }

    public void setRecoverTurnFailureToReady(BooleanSupplier recoverTurnFailureToReady) {
        this.recoverTurnFailureToReady = recoverTurnFailureToReady;
    }

    /**
     * 注册睡眠唤醒轮转回调：当 {@link #wakeIfSleeping()} 按空闲时长创建全新会话时，
     * 以旧/新 sessionId 通知持有者同步其外部会话索引，保证后续请求不会命中过期身份。
     */
    public void setSessionRotationListener(SessionRotationListener sessionRotationListener) {
        this.sessionRotationListener = sessionRotationListener;
    }

    /** 睡眠唤醒触发本地新会话时的一次性通知。 */
    @FunctionalInterface
    public interface SessionRotationListener {
        void onSessionRotatedOnWake(String previousSessionId, String newSessionId);
    }

    public interface SleepStateListener {
        void onSleeping();

        void onWaking();
    }

    public void setSleepStateListener(SleepStateListener sleepStateListener) {
        this.sleepStateListener = sleepStateListener;
    }

    public boolean isRestoredSession() {
        return restoredSession;
    }

    @Override
    public void start() throws IOException {
        super.start();
        lastActivityAt.set(System.currentTimeMillis());
        notifySessionReady();
    }

    private void notifySessionReady() {
        Runnable callback = afterSessionReady;
        if (callback != null) {
            try {
                callback.run();
            } catch (RuntimeException e) {
                logger.warn("session READY 后处理失败, sessionId={}", sessionId, e);
            }
        }
    }

    @FunctionalInterface
    public interface NewSessionListener {
        void onNewSession(String previousSessionId, String newSessionId,
                          String prompt, String messageId);
    }

    private NewSessionListener newSessionListener;
    private PendingNewSession pendingNewSession;
    private boolean newSessionCancelled;

    private static final class PendingNewSession {
        final String sessionId;
        final long generation;
        final String prompt;
        final PromptOptions options;
        PendingNewSession(String sessionId, long generation, String prompt, PromptOptions options) {
            this.sessionId = sessionId;
            this.generation = generation;
            this.prompt = prompt;
            this.options = options;
        }
    }

    public void setNewSessionListener(NewSessionListener listener) {
        this.newSessionListener = listener;
    }

    private synchronized String executeMcpNewSession(JsonObject arguments) {
        PromptOptions options = requireActiveMcpTurn();
        if (newSessionCancelled) throw new IllegalStateException("当前轮已被取消");
        if (arguments.size() != 1 || !arguments.has("prompt")
                || !arguments.get("prompt").isJsonPrimitive()
                || !arguments.getAsJsonPrimitive("prompt").isString()
                || arguments.get("prompt").getAsString().trim().isEmpty()) {
            throw new IllegalArgumentException("prompt 必须是非空字符串，且只能提供 prompt 参数");
        }
        if (pendingNewSession != null) {
            throw new IllegalStateException("已有待执行的新会话请求");
        }
        pendingNewSession = new PendingNewSession(sessionId, currentLifecycleGeneration(),
                arguments.get("prompt").getAsString(), options.forNewSession());
        return "已接收，将在本轮结束后创建新会话并执行提示词。";
    }

    /** Called only after the old turn has released its MCP/auth context. */
    void finishSuccessfulTurn(long generation, PromptOptions options) {
        PendingNewSession request;
        synchronized (this) {
            request = pendingNewSession;
            pendingNewSession = null;
            if (request != null && (request.generation != generation
                    || !java.util.Objects.equals(request.sessionId, sessionId)
                    || !compareAndSetStateIfActive(generation, State.BUSY, State.STARTING))) {
                request = null;
            }
        }
        if (request != null) {
            try {
                boolean restarted = false;
                synchronized (this) {
                    if (!newSessionCancelled && getState() == State.STARTING) {
                        restartForNewSession();
                        state.set(State.STARTING);
                        restarted = true;
                    }
                }
                if (!restarted) {
                    finishCancelledNewSession(request);
                    return;
                }
                // Owner callbacks can acquire manager locks; never hold the client monitor.
                if (newSessionListener != null) {
                    newSessionListener.onNewSession(request.sessionId, sessionId,
                            request.prompt, request.options.getClientMessageId());
                }
                synchronized (this) {
                    if (!newSessionCancelled && getState() == State.STARTING) {
                        state.set(State.READY);
                        send(request.prompt, null, request.options);
                        return;
                    }
                }
                finishCancelledNewSession(request);
            } catch (Exception failure) {
                releaseChannelTurn(request.options);
                if (setStateIfActive(currentLifecycleGeneration(), State.ERROR)) {
                    notifyAfterTurnFailed();
                    getLiveOutputListener().onError(new IOException("创建新会话并运行失败", failure));
                }
            }
            return;
        }
        if (compareAndSetStateIfActive(generation, State.BUSY, State.READY)) {
            lastActivityAt.set(System.currentTimeMillis());
            notifyAfterTurnReady();
            if (getState() == State.READY) deliverInboxAfterTurn(generation, options);
        }
    }

    private void finishCancelledNewSession(PendingNewSession request) {
        releaseChannelTurn(request.options);
        if (state.compareAndSet(State.STARTING, State.READY)) notifyAfterTurnReady();
    }

    /** Retains logical identity and feature wiring, but creates a fresh provider runtime. */
    protected void restartForNewSession() throws IOException {
        historyManager.forceFlush(sessionId);
        if (memoryManager != null && historyManager.getTurnCount() > initialTurnCount) {
            final String oldSession = sessionId;
            final int oldTurns = historyManager.getTurnCount();
            historyManager.markMemoryExtractionPending(oldSession, oldTurns);
        }
        releaseRuntimeForSleep();
        lifecycleBoundary();
        historyManager.reset();
        initialTurnCount = 0;
        lastMessageAt.set(0L);
        contextUsagePercentage = -1D;
        compactionInProgress = false;
        acpHarnessReinjectionPending.set(false);
        restoredSession = false;
        targetRestoreSessionId = null;
        forceNewSession = true;
        state.set(State.SLEEP);
        try {
            wakeRuntimeSession();
        } finally {
            forceNewSession = false;
        }
        lastActivityAt.set(System.currentTimeMillis());
        notifySessionReady();
    }

    /**
     * 会话身份轮转属于投影同步，不参与生命周期回滚；同步失败只记录告警，
     * 避免已经成功启动的运行态被错误地退回 SLEEP。
     */
    private void notifySessionRotatedOnWake(String previousSessionId, String newSessionId) {
        SessionRotationListener listener = sessionRotationListener;
        if (listener == null) return;
        try {
            listener.onSessionRotatedOnWake(previousSessionId, newSessionId);
        } catch (RuntimeException e) {
            logger.warn("睡眠唤醒会话轮转通知失败, previousSessionId={}, newSessionId={}",
                    previousSessionId, newSessionId, e);
        }
    }

    /** Atomically sleeps an idle READY client without closing its logical resources. */
    public synchronized boolean sleepIfIdle(long nowMillis, long idleMillis) {
        long observed = lastActivityAt.get();
        if (!agentProvider.supportsSessionLoad() || observed <= 0L
                || nowMillis < observed || nowMillis - observed < idleMillis
                || !state.compareAndSet(State.READY, State.SLEEP)) {
            return false;
        }
        lifecycleBoundary();
        releaseRuntimeForSleep();
        notifySleeping();
        logger.info("ACP client 已进入睡眠, logicalId={}, sessionId={}", groupId, sessionId);
        return true;
    }

    /** Restores a persisted SLEEP state after session/load without waiting for idle timeout. */
    public synchronized boolean restoreSleepAfterRestart() {
        if (!agentProvider.supportsSessionLoad()
                || !state.compareAndSet(State.READY, State.SLEEP)) {
            notifyWaking();
            return false;
        }
        lifecycleBoundary();
        releaseRuntimeForSleep();
        notifySleeping();
        logger.info("ACP client 已恢复重启前睡眠状态, logicalId={}, sessionId={}",
                groupId, sessionId);
        return true;
    }

    /** Wakes a sleeping client in-place. Concurrent callers share the same lifecycle lock. */
    public synchronized boolean wakeIfSleeping() throws IOException {
        if (getState() != State.SLEEP) return false;
        notifyWaking();
        // 在新消息更新时间之前判断，覆盖重启和后台检查尚未执行的情况。
        long sleepingLastMessageAt = lastMessageAt.get();
        long now = System.currentTimeMillis();
        AutoNewSessionConfig config = robotParam == null ? null : robotParam.getAutoNewSession();
        boolean createNew = config != null && config.isEnabled()
                && sleepingLastMessageAt > 0L && now >= sleepingLastMessageAt
                && now - sleepingLastMessageAt >= TimeUnit.MINUTES.toMillis(config.getIdleMinutes());
        String sleepingSessionId = sessionId;
        int sleepingTurnCount = historyManager.getTurnCount();
        int sleepingInitialTurnCount = initialTurnCount;
        List<ContextMessage> sleepingHistory = createNew && sleepingSessionId != null
                ? historyManager.getFullHistory(sleepingSessionId) : Collections.emptyList();
        if (createNew) {
            historyManager.reset();
            initialTurnCount = 0;
            lastMessageAt.set(0L);
        }
        forceNewSession = createNew;
        targetRestoreSessionId = null;
        restoredSession = false;
        long startedAt = System.currentTimeMillis();
        try {
            wakeRuntimeSession();
            forceNewSession = false;
            // 恢复失败回退 session/new 也会改变身份，不能只同步自动轮转分支。
            boolean sessionChanged = !java.util.Objects.equals(sleepingSessionId, sessionId);
            if (sessionChanged) {
                notifySessionRotatedOnWake(sleepingSessionId, sessionId);
            }
            lastActivityAt.set(System.currentTimeMillis());
            notifySessionReady();
            if (createNew && memoryManager != null && sleepingSessionId != null
                    && sleepingTurnCount > sleepingInitialTurnCount) {
                historyManager.markMemoryExtractionPending(
                        sleepingSessionId, sleepingTurnCount);
                memoryManager.submitExtractFull(
                        sleepingSessionId, workspacePath,
                        sleepingHistory,
                        () -> {
                            historyManager.clearMemoryExtractionPending(
                                    sleepingSessionId, sleepingTurnCount);
                            memoryManager.incrementSessionCount(workspacePath);
                        },
                        error -> logger.warn(
                                "睡眠轮转后的记忆提取未完成，保留 pending, sessionId={}",
                                sleepingSessionId, error));
            }
            getLiveOutputListener().onLifecycleEvent(
                    "AGENT_WAKE", "SLEEP", "READY",
                    System.currentTimeMillis() - startedAt, sessionChanged);
            return true;
        } catch (IOException | RuntimeException failure) {
            notifySleeping();
            forceNewSession = false;
            if (createNew && sleepingSessionId != null) {
                historyManager.restoreState(sleepingSessionId);
                initialTurnCount = sleepingInitialTurnCount;
            }
            lastMessageAt.set(sleepingLastMessageAt);
            throw failure;
        }
    }

    private void notifySleeping() {
        SleepStateListener listener = sleepStateListener;
        if (listener == null) return;
        try {
            listener.onSleeping();
        } catch (RuntimeException error) {
            logger.warn("记录 ACP 睡眠状态失败, logicalId={}", groupId, error);
        }
    }

    private void notifyWaking() {
        SleepStateListener listener = sleepStateListener;
        if (listener == null) return;
        try {
            listener.onWaking();
        } catch (RuntimeException error) {
            logger.warn("清除 ACP 睡眠状态失败, logicalId={}", groupId, error);
        }
    }

    /**
     * 注入子 Agent 派发器和上下文注入器。
     *
     * @param dispatcher      子 Agent 派发器
     * @param injector        上下文注入器
     * @param robotRegistry   全局 robot 注册表
     */
    public void setSubAgentSupport(SubAgentDispatcher dispatcher,
                                   SubAgentContextInjector injector,
                                   Map<String, AcpRobotParam> robotRegistry) {
        this.subAgentDispatcher = dispatcher;
        this.subAgentContextInjector = injector;
        this.globalRobotRegistry = robotRegistry;
    }

    /**
     * 注入定时任务支持。
     */
    public void setObservationSupport(ObservationManager manager, ScheduleOwnerKey owner,
                                      ObservationManager.Delivery delivery) {
        this.observationManager = manager;
        this.observationOwner = owner;
        manager.register(this, owner, workspacePath,
                () -> robotParam != null && robotParam.isEnabled() && robotParam.isObservationEnabled(), delivery);
    }

    private String executeMcpObservation(String tool, JsonObject arguments) {
        requireActiveMcpTurn();
        if (observationManager == null || robotParam == null || !robotParam.isObservationEnabled())
            throw new IllegalStateException("OBSERVATION_DISABLED");
        return observationManager.executeTool(tool, arguments, observationOwner.getPersistencePath());
    }

    public void setScheduleSupport(ScheduleTaskManager taskManager,
                                   ScheduleContextInjector contextInjector) {
        setScheduleSupport(taskManager, contextInjector, null);
    }

    public void setScheduleSupport(ScheduleTaskManager taskManager,
                                   ScheduleContextInjector contextInjector,
                                   ScheduleOwnerKey ownerKey) {
        this.scheduleTaskManager = taskManager;
        this.scheduleContextInjector = contextInjector;
        this.scheduleOwnerKey = ownerKey;
        if (taskManager != null && ownerKey != null) {
            taskManager.register(ownerKey);
        }
    }

    /**
     * 注入 TalkTo 支持。
     *
     * @param dispatcher      TalkTo 消息投递器
     * @param injector        TalkTo 上下文注入器
     * @param robotRegistry   全局 robot 注册表
     */
    public void setTalkToSupport(TalkToDispatcher dispatcher,
                                 TalkToContextInjector injector,
                                 Map<String, AcpRobotParam> robotRegistry) {
        this.talkToDispatcher = dispatcher;
        this.talkToContextInjector = injector;
        this.talkToRobotRegistry = robotRegistry;
    }

    /**
     * Adds process-local MCP endpoints such as starweave-tasks. Call before {@link #start()} so
     * both session/new and session/load observe the same immutable snapshot.
     */
    public void setAdditionalMcpServers(JsonArray servers) {
        JsonArray snapshot = new JsonArray();
        if (servers != null) {
            for (JsonElement server : servers) snapshot.add(server.deepCopy());
        }
        this.additionalMcpServers = snapshot;
    }

    // ==================== 生命周期 ====================



    @Override
    protected void createSession() throws IOException {
        // 指定恢复目标 sessionId（acpRestoreSession 场景）
        if (targetRestoreSessionId != null) {
            if (!agentProvider.supportsSessionLoad()) {
                throw new IOException("Provider 不支持 session/load: " + agentProvider.getName());
            }
            try {
                loadSession(targetRestoreSessionId);
            } catch (IOException e) {
                if (isActiveSessionConflict(e)) {
                    logger.warn("目标 session 正被其他进程使用；安全策略禁止自动终止未知进程, sessionId={}",
                            targetRestoreSessionId);
                }
                throw e;
            }
            historyManager.saveLastSessionId(targetRestoreSessionId);
            return;
        }

        // clearContext 场景下强制创建新会话，跳过历史恢复
        if (!forceNewSession && agentProvider.supportsSessionLoad()) {
            String latestSessionId = historyManager.findLatestSessionId();
            if (latestSessionId != null) {
                try {
                    loadSession(latestSessionId);
                    return;
                } catch (IOException e) {
                    if (isActiveSessionConflict(e)) {
                        logger.warn("最近 session 正被其他进程使用；不终止占用进程，安全回退到 session/new, sessionId={}",
                                latestSessionId);
                    } else {
                        logger.warn("session/load 失败，回退到 session/new, sessionId={}", latestSessionId, e);
                    }
                    historyManager.reset();
                }
            }
        } else if (!forceNewSession) {
            // Provider 无恢复能力时，旧 sessionId 不能作为下一次启动的恢复基线。
            historyManager.reset();
        }

        newSession();
    }

    /**
     * 检查异常是否为 session 被其它进程占用。
     * <p>
     * 这里只做识别和安全回退，绝不根据错误文本中的 PID 终止未知进程。
     */
    static boolean isActiveSessionConflict(IOException e) {
        String message = e.getMessage();
        return message != null && message.contains("Session is active in another process");
    }


    /**
     * 通过 session/load 恢复历史会话。
     * Agent 会通过 session/update 回放完整对话历史，全部回放完成后返回响应。
     */
    private void loadSession(String targetSessionId) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", targetSessionId);
        params.addProperty("cwd", workspacePath);

        JsonArray mcpServers = loadMcpServersFromConfigs();
        params.add("mcpServers", mcpServers);
        logger.info("session/load 尝试恢复会话: {}, 携带 {} 个 MCP server", targetSessionId, mcpServers.size());

        JsonObject request = buildRequest("session/load", params);
        String requestId = request.get("id").getAsString();
        sendJson(request);

        // 读取 agent 回放的 session/update 通知，直到收到 load 响应
        int replayedMessages = 0;
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                throw new IOException("ACP 进程在 session/load 期间意外关闭");
            }
            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) continue;

            JsonObject msg;
            try {
                msg = JsonParser.parseString(trimmed).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                continue;
            }

            // 匹配到 load 响应，回放结束
            if (msg.has("id") && requestId.equals(msg.get("id").getAsString())) {
                if (msg.has("error")) {
                    throw new IOException("session/load 返回错误: " + msg.get("error"));
                }
                setSessionId(targetSessionId);
                historyManager.restoreState(targetSessionId);
                restoredSession = true;
                initialTurnCount = historyManager.getTurnCount();
                lastMessageAt.set(historyManager.getLastMessageAt(targetSessionId));
                logger.info("session/load 成功，已恢复会话: {}, 回放消息数={}", targetSessionId, replayedMessages);
                return;
            }

            // 处理回放的 session/update 通知（静默消费，不推送给 listener）
            if (msg.has("method") && "session/update".equals(msg.get("method").getAsString())) {
                replayedMessages++;
                logger.debug("session/load 回放消息: {}", trimmed);
            }
        }
    }

    /**
     * 创建全新的 ACP 会话。
     */
    private void newSession() throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("cwd", workspacePath);

        JsonArray mcpServers = loadMcpServersFromConfigs();
        params.add("mcpServers", mcpServers);
        logger.info("session/new 携带 {} 个 MCP server", mcpServers.size());

        JsonObject response = sendRequest("session/new", params);
        JsonObject result = response.getAsJsonObject("result");
        setSessionId(result.get("sessionId").getAsString());
        historyManager.saveLastSessionId(getSessionId());
        logger.info("ACP session 创建成功: {}", getSessionId());
    }


    public void send(String userInput, List<Map<String, String>> files) {
        send(userInput, files, PromptOptions.defaults());
    }

    public void send(String userInput, List<Map<String, String>> files, PromptOptions options) {
        sendInternal(userInput, files, Collections.emptyList(), options);
    }

    public boolean isActiveTask(String taskId) {
        PromptOptions options = acceptedPromptOptions.get();
        return taskId != null && getState() == State.BUSY && options != null
                && options.isTaskTurn() && taskId.equals(options.getTaskId());
    }

    public PromptOptions getActiveTaskOptions() {
        PromptOptions options = acceptedPromptOptions.get();
        return options != null && options.isTaskTurn() ? options : null;
    }

    /** Cancel matching work while preserving a durable caller-owned pending notification. */
    public void cancelForQueuedWork() throws IOException {
        queuedWorkCancellationPending.set(true);
        AcpResponseListener listener = executionListener();
        try {
            listener.markNextTermination("TASK_CONTROL_INTERRUPTED");
            cancel();
        } catch (IOException | RuntimeException failure) {
            listener.clearNextTermination();
            queuedWorkCancellationPending.set(false);
            throw failure;
        }
    }

    public void sendLocalFiles(String userInput, List<String> localFiles) {
        sendInternal(userInput, null, localFiles, PromptOptions.defaults());
    }

    public void sendLocalFiles(String userInput, List<String> localFiles, PromptOptions options) {
        sendInternal(userInput, null, localFiles, options);
    }

    /** Returns admission, including lifecycle CAS and executor rejection, for durable background work. */
    public boolean trySendObservation(String prompt) {
        return sendInternal(prompt, null, Collections.emptyList(), PromptOptions.defaults());
    }

    private boolean sendInternal(String userInput, List<Map<String, String>> files,
                              Collection<String> localFiles, PromptOptions options) {
        AcpResponseListener outputListener = executionListener();
        if (userInput == null || userInput.trim().isEmpty()) {
            outputListener.onError(new IllegalArgumentException("用户输入不能为空"));
            return false;
        }
        final PromptOptions effectiveOptions = options == null ? PromptOptions.defaults() : options;
        if (!effectiveOptions.isInboundTalkTo()) consecutiveInboxTurns.set(0);
        final long generation;
        synchronized (this) {
            if (getState() == State.SLEEP) {
                try {
                    wakeIfSleeping();
                } catch (IOException | RuntimeException failure) {
                    outputListener.onError(new IOException("唤醒智能体失败", failure));
                    return false;
                }
            }
            generation = currentLifecycleGeneration();
            if (!compareAndSetStateIfActive(generation, State.READY, State.BUSY)) {
                releaseChannelTurn(effectiveOptions);
                outputListener.onError(new IllegalStateException(
                        "当前 client 状态不允许发送消息: " + state.get()));
                return false;
            }
            newSessionCancelled = false;
        }
        acceptedPromptOptions.set(effectiveOptions);
        lastMessageAt.set(System.currentTimeMillis());

        // 直接使用本轮真实保存或注册的路径，避免同名附件被集合差值吞掉。
        List<String> currentFilePaths = new ArrayList<>(historyManager.saveFiles(sessionId, files));
        currentFilePaths.addAll(historyManager.registerLocalFiles(localFiles));
        Set<String> newImagePaths = new LinkedHashSet<>();
        for (String path : currentFilePaths) {
            if (isImageFile(path)) newImagePaths.add(path);
        }
        List<String> attachmentNames = new ArrayList<>();
        if (files != null) {
            for (Map<String, String> file : files) {
                if (file != null) attachmentNames.addAll(file.keySet());
            }
        }
        if (localFiles != null) {
            for (String localFile : localFiles) {
                if (localFile == null || localFile.trim().isEmpty()) continue;
                try {
                    attachmentNames.add(java.nio.file.Paths.get(localFile).getFileName().toString());
                } catch (RuntimeException ignored) {
                    attachmentNames.add(localFile);
                }
            }
        }

        // Only admitted turns may appear in a UI snapshot before the provider starts.
        historyManager.acceptUiMessage(effectiveOptions.getClientMessageId(),
                userInput, attachmentNames);

        AcpResponseListener guardedListener = new LifecycleGuardedAcpResponseListener(
                outputListener, () -> isLifecycleGenerationActive(generation));
        notifyScheduleExecutionStarted(
                guardedListener, userInput, effectiveOptions);
        try {
            executor.submit(() -> {
                boolean succeeded = false;
                try {
                    activeMcpListener.set(guardedListener);
                    activeMcpOptions.set(effectiveOptions);
                    com.mola.cmd.proxy.app.acp.mcpauth.AuthPrincipalContext authContext =
                            effectiveOptions.getAuthPrincipalContext();
                    if (authContext == null) {
                        McpAuthManager.getInstance().clearBinding(authSessionId);
                    } else {
                        McpAuthManager.getInstance().bind(
                                authSessionId, authContext, effectiveOptions.getAuthTurnId());
                    }
                    sendPrompt(userInput, historyManager.getFileAbsolutePaths(), newImagePaths,
                            attachmentNames, guardedListener, effectiveOptions);
                    completeScheduleExecution(effectiveOptions, true, null);
                    releaseMcpAuthBinding(effectiveOptions);
                    succeeded = true;
                } catch (Exception e) {
                    synchronized (this) { pendingNewSession = null; }
                    completeScheduleExecution(effectiveOptions, false,
                            e.getClass().getName() + ": " + e.getMessage());
                    releaseMcpAuthBinding(effectiveOptions);
                    logger.error("ACP send 失败", e);
                    releaseChannelTurn(effectiveOptions);
                    if (!recoverInterruptedTurnFailure(generation, guardedListener, e)
                            && setStateIfActive(generation, State.ERROR)) {
                        notifyAfterTurnFailed();
                        guardedListener.onError(e);
                    }
                } finally {
                    activeMcpOptions.compareAndSet(effectiveOptions, null);
                    activeMcpListener.compareAndSet(guardedListener, null);
                    acceptedPromptOptions.compareAndSet(effectiveOptions, null);
                    queuedWorkCancellationPending.set(false);
                }
                if (succeeded) finishSuccessfulTurn(generation, effectiveOptions);
            });
        } catch (RejectedExecutionException e) {
            completeScheduleExecution(effectiveOptions, false,
                    e.getClass().getName() + ": " + e.getMessage());
            acceptedPromptOptions.compareAndSet(effectiveOptions, null);
            queuedWorkCancellationPending.set(false);
            releaseMcpAuthBinding(effectiveOptions);
            releaseChannelTurn(effectiveOptions);
            if (setStateIfActive(generation, State.ERROR)) {
                notifyAfterTurnFailed();
                guardedListener.onError(e);
            }
            return false;
        }
        return true;
    }

    private void completeScheduleExecution(PromptOptions options, boolean success,
                                           String detail) {
        ScheduleTaskManager manager = scheduleTaskManager;
        if (manager == null || options == null || !options.isScheduleExecution()) return;
        manager.completeExecutionTurn(options.getScheduleExecutionId(), success, detail);
    }

    private void notifyAfterTurnReady() {
        Runnable callback = afterTurnReady;
        if (callback == null) return;
        try {
            callback.run();
        } catch (RuntimeException e) {
            logger.warn("turn READY 后处理失败, sessionId={}", sessionId, e);
        }
    }

    private void notifyAfterTurnFailed() {
        Runnable callback = afterTurnFailed;
        if (callback == null) return;
        try {
            callback.run();
        } catch (RuntimeException e) {
            logger.warn("turn 失败后处理失败, sessionId={}", sessionId, e);
        }
    }

    private boolean shouldRecoverTurnFailureToReady() {
        if (queuedWorkCancellationPending.get()) return true;
        BooleanSupplier callback = recoverTurnFailureToReady;
        if (callback == null) return false;
        try {
            return callback.getAsBoolean();
        } catch (RuntimeException e) {
            logger.warn("检查中断 pending 失败, sessionId={}", sessionId, e);
            return false;
        }
    }

    /** Package-private deterministic seam for interrupted-provider failure ordering tests. */
    boolean recoverInterruptedTurnFailure(long generation,
                                          AcpResponseListener listener,
                                          Exception error) {
        if (!shouldRecoverTurnFailureToReady()) return false;
        // 仍为 BUSY 时先终止旧流，避免新请求抢入后才收到旧 turn error。
        if (queuedWorkCancellationPending.get()) {
            listener.onComplete("");
        } else {
            listener.onError(error);
        }
        if (compareAndSetStateIfActive(generation, State.BUSY, State.READY)) {
            lastActivityAt.set(System.currentTimeMillis());
            notifyAfterTurnReady();
            if (getState() == State.READY) checkAndDeliverInbox();
        } else {
            notifyAfterTurnFailed();
        }
        return true;
    }

    static void notifyScheduleExecutionStarted(AcpResponseListener listener,
                                                String userInput,
                                                PromptOptions options) {
        if (listener == null || options == null
                || !options.isScheduleExecution()) {
            return;
        }
        listener.onScheduleEvent("SCHEDULE_EXECUTE", userInput, true);
    }

    public synchronized void cancel() throws IOException {
        pendingNewSession = null;
        newSessionCancelled = true;
        McpAuthManager.getInstance().clearBinding(authSessionId);
        if (sessionId == null) {
            logger.warn("cancel 调用时 sessionId 为空，忽略");
            return;
        }

        // 先取消所有子 Agent
        if (subAgentDispatcher != null) {
            subAgentDispatcher.cancelAll();
        }

        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);

        JsonObject notification = new JsonObject();
        notification.addProperty("jsonrpc", JSONRPC_VERSION);
        notification.addProperty("method", "session/cancel");
        notification.add("params", params);
        sendJson(notification);
        logger.info("已发送 session/cancel, sessionId={}", sessionId);
    }

    @Override
    public void close() throws IOException {
        close(false);
    }

    /**
     * 全局 stop 专用：历史和 pending 标记照常落盘，但不在关机路径提交记忆模型调用。
     */
    public void closeForShutdown() throws IOException {
        close(true);
    }

    private synchronized void close(boolean deferMemoryExtraction) throws IOException {
        pendingNewSession = null;
        newSessionCancelled = true;
        if (!beginClose()) {
            return;
        }

        try {
            if (deferMemoryExtraction) {
                // forceFlush 会触发 onTurnFlushed；关机时必须先阻断增量提取提交。
                historyManager.setOnTurnFlushed(null);
            }
            // 先落盘未保存的上下文，确保数据持久化
            historyManager.forceFlush(sessionId);

            // 先持久化 pending，再决定当前进程执行还是留给下次启动恢复。
            if (memoryManager != null && sessionId != null) {
                try {
                    if (historyManager.getTurnCount() > initialTurnCount) {
                        final String closingSessionId = sessionId;
                        final int pendingTurnCount = historyManager.getTurnCount();
                        historyManager.markMemoryExtractionPending(
                                closingSessionId, pendingTurnCount);
                        if (deferMemoryExtraction) {
                            logger.info("关机延迟记忆提取, sessionId={}, turnCount={}",
                                    closingSessionId, pendingTurnCount);
                        } else {
                            memoryManager.submitExtractFull(
                                    closingSessionId,
                                    workspacePath,
                                    historyManager.getFullHistory(closingSessionId),
                                    () -> {
                                        historyManager.clearMemoryExtractionPending(
                                                closingSessionId, pendingTurnCount);
                                        memoryManager.incrementSessionCount(workspacePath);
                                    },
                                    error -> logger.warn(
                                            "全量记忆提取未完成，保留 pending, sessionId={}",
                                            closingSessionId, error));
                        }
                    } else {
                        logger.info("本次 session 无新对话，跳过记忆提取, sessionId={}", sessionId);
                    }
                } catch (Exception e) {
                    logger.warn("关闭时提交记忆提取失败", e);
                }
            }

            // 关闭子 Agent 派发器
            if (subAgentDispatcher != null) {
                subAgentDispatcher.close();
            }

            releaseAllPendingChannelReplies();
            ActionRuntimeRegistry.getInstance().unregister(authSessionId);
            if (observationManager != null && observationOwner != null)
                observationManager.unregister(this, observationOwner);
            McpAuthManager.getInstance().removeSession(authSessionId);
            executor.shutdownNow();
        } finally {
            closeAfterBegin();
        }
    }

    // ==================== MCP 配置加载 ====================

    private JsonArray loadMcpServersFromConfigs() {
        if (!agentProvider.supportsClientMcpServers()) {
            logger.info("Provider 不接受 Client MCP 注入，session 将使用空 mcpServers: {}",
                    agentProvider.getName());
            return new JsonArray();
        }
        String baseUrl = McpAuthManager.getInstance().getBaseUrl();
        JsonArray servers = McpConfigLoader.loadFromPaths(mcpConfigPaths, authSessionId,
                baseUrl, workspacePath);
        appendAdditionalMcpServers(servers, additionalMcpServers);
        appendBuiltInMcpServer(servers, baseUrl, authSessionId);
        return servers;
    }

    static void appendAdditionalMcpServers(JsonArray servers, JsonArray additions) {
        if (additions == null) return;
        Set<String> names = new HashSet<>();
        for (JsonElement element : servers) {
            if (element.isJsonObject() && element.getAsJsonObject().has("name")) {
                names.add(element.getAsJsonObject().get("name").getAsString());
            }
        }
        for (JsonElement element : additions) {
            if (!element.isJsonObject() || !element.getAsJsonObject().has("name")) {
                throw new IllegalArgumentException("Additional MCP server requires a name");
            }
            JsonObject server = element.getAsJsonObject();
            String name = server.get("name").getAsString();
            if (name == null || name.trim().isEmpty() || !names.add(name)) {
                throw new IllegalStateException("Duplicate MCP server name: " + name);
            }
            servers.add(server.deepCopy());
        }
    }

    static void appendBuiltInMcpServer(JsonArray servers, String baseUrl,
                                       String authSessionId) {
        for (JsonElement element : servers) {
            if (element.isJsonObject()
                    && CmdProxyMcpHttpHandler.SERVER_NAME.equals(
                    element.getAsJsonObject().has("name")
                            ? element.getAsJsonObject().get("name").getAsString() : "")) {
                throw new IllegalStateException("MCP Server 名称 '"
                        + CmdProxyMcpHttpHandler.SERVER_NAME
                        + "' 为 ACP harness 保留名称，请重命名用户配置中的同名 Server");
            }
        }
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalStateException("ACP harness MCP 控制服务未启动");
        }
        JsonObject server = new JsonObject();
        server.addProperty("name", CmdProxyMcpHttpHandler.SERVER_NAME);
        server.addProperty("type", "http");
        server.addProperty("url", baseUrl + CmdProxyMcpHttpHandler.PATH);
        JsonArray headers = new JsonArray();
        JsonObject authHeader = new JsonObject();
        authHeader.addProperty("name", CmdProxyMcpHttpHandler.AUTH_SESSION_HEADER);
        authHeader.addProperty("value", authSessionId);
        headers.add(authHeader);
        server.add("headers", headers);
        servers.add(server);
    }

    Set<String> availableActionTools() {
        LinkedHashSet<String> tools = new LinkedHashSet<>();
        if (clientIdentity.getScope() == AcpClientIdentity.Scope.MAIN
                || clientIdentity.isTeam()) tools.add("new_session");
        if (subAgentDispatcher != null) tools.add("dispatch_subagent");
        if (scheduleTaskManager != null
                && (robotParam == null || robotParam.isScheduleEnabled())) {
            tools.add("schedule_task");
            tools.add("manage_schedule");
        }
        if (talkToDispatcher != null) tools.add("talk_to");
        if (observationManager != null && robotParam != null && robotParam.isObservationEnabled()) {
            tools.add("manage_observation_channels");
            tools.add("test_observation_script");
            tools.add("query_observation_events");
        }
        return Collections.unmodifiableSet(tools);
    }

    private PromptOptions requireActiveMcpTurn() {
        AcpResponseListener listener = activeMcpListener.get();
        PromptOptions options = activeMcpOptions.get();
        if (listener == null || options == null || getState() != State.BUSY) {
            throw new IllegalStateException("NO_ACTIVE_TURN");
        }
        return options;
    }

    private String executeMcpDispatchSubagent(JsonObject arguments) {
        PromptOptions options = requireActiveMcpTurn();
        AcpResponseListener listener = activeMcpListener.get();
        if (subAgentDispatcher == null) throw new IllegalStateException("SUBAGENT_DISABLED");
        JsonArray taskArray = arguments.getAsJsonArray("tasks");
        if (taskArray == null || taskArray.size() == 0) {
            throw new IllegalArgumentException("tasks must not be empty");
        }
        List<SubAgentTask> tasks = new ArrayList<>();
        for (JsonElement element : taskArray) {
            JsonObject task = element.getAsJsonObject();
            String agent = requiredString(task, "agent");
            String title = task.has("title") ? task.get("title").getAsString() : null;
            String prompt = requiredString(task, "prompt");
            tasks.add(new SubAgentTask(agent, title, prompt));
        }
        List<SubAgentResult> results = subAgentDispatcher.dispatch(tasks, listener,
                workspacePath, options.getAuthPrincipalContext());
        listener.onSubAgentEvent("DISPATCH_COMPLETE", null, "子 Agent 任务已完成");
        return SubAgentDispatcher.formatResults(results);
    }

    private String executeMcpSchedule(String toolName, JsonObject arguments) {
        PromptOptions options = requireActiveMcpTurn();
        AcpResponseListener listener = activeMcpListener.get();
        if (scheduleTaskManager == null) throw new IllegalStateException("SCHEDULE_DISABLED");
        String robotName = robotParam != null ? robotParam.getName() : groupId;
        ScheduleOwnerKey owner = scheduleOwnerKey != null
                ? scheduleOwnerKey : ScheduleOwnerKey.main(robotName);
        String result = scheduleTaskManager.executeTool(toolName, arguments, owner,
                options.getAuthPrincipalContext(),
                ChannelDeliveryContext.from(options.getChannelTurnContext()));
        listener.onScheduleEvent("schedule_task".equals(toolName)
                ? "SCHEDULE_CREATE" : "SCHEDULE_MANAGE", result,
                "schedule_task".equals(toolName));
        return result;
    }

    private String executeMcpTalkTo(JsonObject arguments) {
        PromptOptions options = requireActiveMcpTurn();
        AcpResponseListener listener = activeMcpListener.get();
        if (talkToDispatcher == null) throw new IllegalStateException("TALK_TO_DISABLED");
        String displayTarget = requiredString(arguments, "target");
        TalkToRequest request = new TalkToRequest(displayTarget,
                requiredString(arguments, "content"),
                arguments.has("_depth") ? arguments.get("_depth").getAsInt() : 0);
        request = request.withParentTrace(options.talkToParentFor(displayTarget));
        request = resolveChannelReplyTarget(request, options);
        if (CHANNEL_REPLY_TARGET.equals(request.getTarget())) {
            return unresolvedChannelReplyResult();
        }
        List<com.mola.cmd.proxy.app.acp.talkto.model.ContactRef> contacts =
                robotParam != null ? robotParam.getContacts() : null;
        String result = talkToDispatcher.deliver(request, talkToRoutingName(),
                extractChatterId(), groupId, contacts, options.getAuthPrincipalContext());
        recordPendingChannelReply(request, options, result);
        publishOrdinaryTalkToResult(listener, displayTarget, request.getContent(), result);
        return result;
    }

    private static String requiredString(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()
                || object.get(name).getAsString().trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return object.get(name).getAsString();
    }

    public PromptOptions promptOptionsForScheduleExecution(
            com.mola.cmd.proxy.app.acp.mcpauth.AuthPrincipalContext authContext,
            ChannelDeliveryContext deliveryContext) {
        ChannelTurnContext restored = talkToDispatcher == null ? null
                : talkToDispatcher.restoreChannelTurn(deliveryContext, groupId);
        return PromptOptions.forScheduleExecution(authContext, restored);
    }

    private void releaseMcpAuthBinding(PromptOptions options) {
        if (options != null && options.getAuthPrincipalContext() != null) {
            McpAuthManager.getInstance().unbind(authSessionId, options.getAuthTurnId());
        } else {
            McpAuthManager.getInstance().clearBinding(authSessionId);
        }
    }

    public String getAuthSessionId() { return authSessionId; }

    // ==================== Prompt ====================


    private void sendPrompt(String userInput, Collection<String> filePaths, AcpResponseListener listener) throws IOException {
        sendPrompt(userInput, filePaths, Collections.emptySet(), Collections.emptyList(),
                listener, PromptOptions.defaults());
    }

    private void sendPrompt(String userInput, Collection<String> filePaths,
                            Collection<String> newImagePaths, List<String> attachmentNames,
                            AcpResponseListener listener, PromptOptions options) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);

        // 首轮注入完整上下文；provider 压缩上下文后，也在下一次 prompt 完整重注入。
        // 正常后续 turn 仍只带时间+用户输入，保留 prompt prefix caching 的收益。
        boolean isFirstTurn = (historyManager.getTurnCount() == initialTurnCount);
        boolean reInjectAfterCompaction = acpHarnessReinjectionPending.getAndSet(false);
        boolean shouldInjectFullAcpHarness = isFirstTurn || reInjectAfterCompaction;

        StringBuilder fullTextBuilder = new StringBuilder();

        if (shouldInjectFullAcpHarness) {
            if (reInjectAfterCompaction) {
                logger.info("上下文压缩后完整重注入 ACP harness, provider={}, sessionId={}, turn={}",
                        agentProvider.getName(), sessionId, historyManager.getTurnCount());
            }
            // ==================== 全局系统能力（通过内置 cmd-proxy MCP 工具调用） ====================
            fullTextBuilder.append("\n<acp-harness>\n");
            fullTextBuilder.append("<workspace>").append(workspacePath)
                    .append("</workspace>\n\n");
            fullTextBuilder.append("以下内容由 ACP harness 注入，包含当前会话的运行环境以及已启用能力的相关信息。\n\n");
            if (!availableActionTools().isEmpty()) {
                fullTextBuilder.append("ACP harness 通过 MCP 提供当前会话已启用的系统工具。\n");
                fullTextBuilder.append("需要执行这些系统操作时，请直接调用对应 MCP 工具。\n\n");
            }

            // 注入子 Agent 上下文
            if (subAgentContextInjector != null && robotParam != null
                    && robotParam.hasSubAgents() && globalRobotRegistry != null) {
                try {
                    String subAgentContext = subAgentContextInjector.buildContext(
                            robotParam.getSubAgents(), globalRobotRegistry, robotParam.getName());
                    if (!subAgentContext.isEmpty()) fullTextBuilder.append(subAgentContext).append("\n");
                } catch (Exception e) {
                    logger.warn("构建子 Agent 上下文失败，跳过", e);
                }
            }

            // 注入定时任务上下文
            if (scheduleContextInjector != null) {
                try {
                    boolean scheduleEnabled = robotParam == null || robotParam.isScheduleEnabled();
                    String scheduleContext = scheduleContextInjector.buildContext(scheduleEnabled, options.isScheduleExecution());
                    if (!scheduleContext.isEmpty()) fullTextBuilder.append(scheduleContext).append("\n");
                } catch (Exception e) {
                    logger.warn("构建定时任务上下文失败，跳过", e);
                }
            }

            // 注入 TalkTo 通讯录上下文
            if (talkToContextInjector != null && robotParam != null
                    && talkToRobotRegistry != null) {
                try {
                    String talkToContext = talkToContextInjector.buildContext(
                            robotParam.getContacts(), talkToRobotRegistry, robotParam.getName());
                    if (!talkToContext.isEmpty()) fullTextBuilder.append(talkToContext).append("\n");
                } catch (Exception e) {
                    logger.warn("构建 TalkTo 上下文失败，跳过", e);
                }
            }

            if (observationManager != null && robotParam != null && robotParam.isObservationEnabled())
                fullTextBuilder.append(ObservationManager.CONTEXT).append("\n");

            // 注入记忆上下文
            if (memoryManager != null) {
                try {
                    String memoryContext = memoryManager.buildMemoryPrompt(workspacePath);
                    if (!memoryContext.isEmpty()) fullTextBuilder.append(memoryContext).append("\n");
                } catch (Exception e) {
                    logger.warn("构建记忆上下文失败，跳过", e);
                }
            }

            fullTextBuilder.append("</acp-harness>\n");
        }

        // 文件路径每次都带
        if (filePaths != null && !filePaths.isEmpty()) {
            StringBuilder fb = new StringBuilder("[Attached Files]\n");
            for (String path : filePaths) {
                fb.append("- ").append(path).append("\n");
            }
            fullTextBuilder.append(fb).append("\n");
        }

        // 时间上下文每次都带（定时任务等场景需要精确时间）
        String timeContext = String.format("[Current Time: %s]\n",
                ZonedDateTime.now().format(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z (EEEE)")));
        fullTextBuilder.append(timeContext).append(userInput);

        JsonArray prompt = new JsonArray();
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", fullTextBuilder.toString());
        prompt.add(textBlock);

        // Claude provider: 将本轮新上传的图片作为 image content block 内嵌
        if (agentProvider.needsInlineImages() && newImagePaths != null && !newImagePaths.isEmpty()) {
            for (String imgPath : newImagePaths) {
                try {
                    byte[] imgBytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(imgPath));
                    String base64Data = Base64.getEncoder().encodeToString(imgBytes);
                    JsonObject imageBlock = new JsonObject();
                    imageBlock.addProperty("type", "image");
                    imageBlock.addProperty("data", base64Data);
                    imageBlock.addProperty("mimeType", detectImageMediaType(imgPath));
                    prompt.add(imageBlock);
                    logger.info("已内嵌图片 image block: {}", imgPath);
                } catch (IOException e) {
                    logger.warn("读取图片文件失败，跳过内嵌: {}", imgPath, e);
                }
            }
        }

        params.add("prompt", prompt);

        JsonObject request = buildRequest("session/prompt", params);
        String requestId = request.get("id").getAsString();

        // 使用后台线程写入 stdin，避免 Windows 管道死锁
        // 当请求体很大时（base64 图片 + 系统上下文），同步写入可能阻塞在 pipe buffer 满，
        // 而子进程可能同时尝试写 stdout（如 metadata 通知），形成双向死锁。
        // 将写入放到后台线程，当前线程立即开始读 stdout，保持 stdout pipe 畅通。
        AtomicReference<IOException> stdinWriteError = new AtomicReference<>();
        sendJsonInBackground(request, stdinWriteError);

        historyManager.addUserMessage(userInput, historyOrigin(options), attachmentNames,
                options.takeClientMessageId());

        // 流式读取
        StringBuilder fullResponse = new StringBuilder();
        // 缓存 toolCallId → title，防止后续 update 中 title 为空
        Map<String, String> toolTitleCache = new HashMap<>();
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                releaseChannelTurn(options);
                IOException writeErr = stdinWriteError.get();
                if (writeErr != null) {
                    throw new IOException("ACP stdin 写入失败: " + writeErr.getMessage(), writeErr);
                } else {
                    throw new IOException("ACP 进程意外关闭");
                }
            }

            String trimmed = line.trim();
            logger.debug("acp输出 {}", trimmed);
            if (!trimmed.startsWith("{")) continue;

            JsonObject msg;
            try {
                msg = JsonParser.parseString(trimmed).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                logger.warn("跳过非法 JSON: {}", line);
                continue;
            }

            AgentProvider.CompactionSignal compactionSignal = observeCompactionSignal(msg, listener);

            // prompt response（JSON-RPC Response 没有 method 字段，排除 Request 误匹配）
            if (!msg.has("method") && msg.has("id") && requestId.equals(msg.get("id").getAsString())) {
                if (msg.has("error")) throw new IOException("ACP prompt 返回错误: " + msg.get("error"));
                String stopReason = "unknown";
                if (msg.has("result") && msg.getAsJsonObject("result").has("stopReason")) {
                    stopReason = msg.getAsJsonObject("result").get("stopReason").getAsString();
                }
                logger.info("ACP prompt turn 结束, stopReason={}, msg = {}", stopReason, trimmed);
                if ("cancelled".equals(stopReason)) {
                    synchronized (this) { pendingNewSession = null; }
                }

                // 排空迟到 chunk（OpenCode ACP bug workaround）
                // sleep 让管道里迟到的数据到位，然后一次抽干 reader 缓冲区
                drainLateChunks(fullResponse, listener, toolTitleCache);

                historyManager.addAssistantMessage(fullResponse.toString());
                historyManager.flushTurn(sessionId);
                lastMessageAt.set(System.currentTimeMillis());

                if (options.hasChannelTurnContext() && !hasPendingNewSession()) {
                    if (!options.hasChannelReplyAttempt()
                            && !hasPendingChannelReply(options.getChannelTurnContext())
                            && deliverAutomaticChannelReply(
                                    fullResponse.toString(), listener, options)) return;
                    releaseChannelTurn(options);
                }

                listener.onComplete(fullResponse.toString());
                return;
            }

            // session/request_permission — 自动回复 allow_always
            if (msg.has("method") && "session/request_permission".equals(msg.get("method").getAsString())) {
                autoAllowPermission(msg);
                continue;
            }

            // session/update
            if (msg.has("method") && "session/update".equals(msg.get("method").getAsString())) {
                processSessionUpdate(msg, fullResponse, listener, toolTitleCache,
                        compactionSignal);
            } else {
                double usage = agentProvider.extractContextUsage(msg);
                if (usage >= 0) {
                    contextUsagePercentage = usage;
                } else if (compactionSignal != AgentProvider.CompactionSignal.NONE) {
                    // Kiro 的压缩状态属于自定义 notification，不是标准 session/update。
                    // 信号已处理，无需再记录为未匹配输出。
                } else {
                    logger.warn("ACP 输出未匹配任何处理分支, msg={}", msg);
                }
            }
        }
    }

    static com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage.UserOrigin
    historyOrigin(PromptOptions options) {
        if (options == null) {
            return com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage.UserOrigin.USER;
        }
        if (options.isTaskTurn()) {
            return com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage.UserOrigin.TASK;
        }
        if (options.isInboundTalkTo()) {
            return com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage.UserOrigin.TALK_TO;
        }
        if (options.hasChannelTurnContext()) {
            return com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage.UserOrigin.CHANNEL;
        }
        if (options.isScheduleExecution()) {
            return com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage.UserOrigin.SCHEDULE;
        }
        return com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage.UserOrigin.USER;
    }

    public long getLastMessageAt() {
        return lastMessageAt.get();
    }

    public long getLastActivityAt() {
        return lastActivityAt.get();
    }

    /** Test/subclass seam for deterministic lifecycle timing. */
    protected void markActivityAt(long timestampMillis) {
        lastActivityAt.set(timestampMillis);
    }

    public boolean isIdleForAutoSleep(long nowMillis, long idleMillis) {
        long last = lastActivityAt.get();
        return state.get() == State.READY && last > 0L
                && nowMillis >= last && nowMillis - last >= idleMillis;
    }

    public boolean hasSessionMessages() {
        return lastMessageAt.get() > 0L;
    }

    /** Rechecks readiness and idle age immediately before an automatic rotation. */
    public boolean isIdleForAutoNewSession(long nowMillis, long idleMillis) {
        long last = lastMessageAt.get();
        return state.get() == State.READY && last > 0L
                && nowMillis >= last && nowMillis - last >= idleMillis;
    }

    /** Atomically blocks a concurrent send before an automatic session replacement. */
    public boolean tryReserveIdleForAutoNewSession(long nowMillis, long idleMillis) {
        long observedLast = lastMessageAt.get();
        if (observedLast <= 0L || nowMillis < observedLast
                || nowMillis - observedLast < idleMillis) return false;
        long generation = currentLifecycleGeneration();
        if (!compareAndSetStateIfActive(generation, State.READY, State.STARTING)) return false;
        long confirmedLast = lastMessageAt.get();
        if (confirmedLast != observedLast) {
            compareAndSetStateIfActive(generation, State.STARTING, State.READY);
            return false;
        }
        return true;
    }

    /**
     * 处理 session/update 通知。
     * 主循环和 drain 阶段共用此方法。
     */
    private void processSessionUpdate(JsonObject msg, StringBuilder fullResponse,
                                      AcpResponseListener listener,
                                      Map<String, String> toolTitleCache,
                                      AgentProvider.CompactionSignal compactionSignal) {
        JsonObject updateParams = msg.getAsJsonObject("params");
        if (updateParams == null) return;
        JsonObject update = updateParams.getAsJsonObject("update");
        if (update == null) return;

        String updateType = update.has("sessionUpdate")
                ? update.get("sessionUpdate").getAsString() : "";

        if ("agent_message_chunk".equals(updateType)) {
            JsonObject content = update.getAsJsonObject("content");
            if (content != null && content.has("text")) {
                String text = content.get("text").getAsString();
                if (agentProvider.isAgentMessageControlArtifact(text)) {
                    logger.debug("过滤 Agent 控制流程伪消息, provider={}, text={}",
                            agentProvider.getName(), text.trim());
                    return;
                }
                fullResponse.append(text);
                listener.onMessage(text);
            }
        } else if ("tool_call".equals(updateType) || "tool_call_update".equals(updateType)) {
            String toolCallId = update.has("toolCallId") ? update.get("toolCallId").getAsString() : "";
            String title = update.has("title") ? update.get("title").getAsString() : "";
            String status = update.has("status") ? update.get("status").getAsString() : "pending";
            if (!title.isEmpty()) {
                toolTitleCache.put(toolCallId, title);
            } else {
                title = toolTitleCache.getOrDefault(toolCallId, "");
            }
            JsonObject updateLog = update.deepCopy();
            updateLog.remove("rawInput");
            updateLog.remove("rawOutput");
            updateLog.remove("_meta");
            logger.info("工具调用: {}", updateLog);
            // Context compaction is a provider lifecycle event, not a business tool
            // result. Keep it out of persisted tool history as well as the generic UI
            // card so memory extraction and session projections see only the dedicated
            // compaction event.
            if (compactionSignal == AgentProvider.CompactionSignal.NONE
                    && "completed".equals(status)) {
                JsonElement rawInputEl = update.get("rawInput");
                JsonElement rawOutputEl = update.get("rawOutput");
                JsonObject rawInput = (rawInputEl != null && rawInputEl.isJsonObject())
                        ? rawInputEl.getAsJsonObject() : null;
                JsonObject rawOutput = (rawOutputEl != null && rawOutputEl.isJsonObject())
                        ? rawOutputEl.getAsJsonObject() : null;
                historyManager.addToolMessage(toolCallId, title, status, rawInput, rawOutput);
                detectMemoryAccess(rawInput);
            }
            // cmd-proxy action tools already publish their existing specialized cards through
            // onSubAgentEvent/onScheduleEvent/onTalkToEvent. Suppress the provider's generic
            // MCP card to avoid duplicate UI while preserving generic cards for external MCPs.
            // codex-acp 1.7+ represents context compaction as a synthetic tool call.
            // Its lifecycle is rendered through the dedicated compaction card, so do
            // not leak the provider's generic "Compact conversation" tool card.
            if (shouldPublishGenericToolCard(compactionSignal, title, update)) {
                listener.onToolCall(toolCallId, title, status, update);
            }
        } else if ("usage_update".equals(updateType)) {
            if (update.has("used") && update.has("size")) {
                double used = update.get("used").getAsDouble();
                double size = update.get("size").getAsDouble();
                if (size > 0) {
                    contextUsagePercentage = (used / size) * 100;
                }
            }
        } else if (agentProvider.supportsCompactionUpdates()
                && ("compaction_update".equals(updateType)
                || "compaction_summary_chunk".equals(updateType))) {
            // Lifecycle was handled by observeCompactionSignal. The provider's
            // summary is internal context, not an assistant reply or tool card.
        } else if ("agent_thought_chunk".equals(updateType)) {
            // 不处理思考
        } else {
            logger.warn("ACP IN session/update 输出未匹配任何处理分支, msg={}", msg);
        }
    }

    static boolean isCmdProxyActionToolCall(String title, JsonObject update) {
        StringBuilder searchable = new StringBuilder(title == null ? "" : title);
        if (update != null && update.has("_meta")) {
            searchable.append(' ').append(update.get("_meta"));
        }
        String normalized = searchable.toString().toLowerCase(Locale.ROOT)
                .replace('_', '-');
        if (!normalized.contains("acp-harness")
                && !normalized.contains("cmd-proxy")) return false;
        return normalized.contains("dispatch-subagent")
                || normalized.contains("schedule-task")
                || normalized.contains("manage-schedule")
                || normalized.contains("talk-to");
    }

    static boolean shouldPublishGenericToolCard(AgentProvider.CompactionSignal compactionSignal,
                                                String title, JsonObject update) {
        return compactionSignal == AgentProvider.CompactionSignal.NONE
                && !isCmdProxyActionToolCall(title, update);
    }

    /**
     * 消费 provider 专用的上下文压缩事件，并在完成时安排下一次 prompt 的完整 Harness 重注入。
     */
    private AgentProvider.CompactionSignal observeCompactionSignal(JsonObject msg, AcpResponseListener listener) {
        if (msg.has("method")
                && "_kiro.dev/compaction/status".equals(msg.get("method").getAsString())) {
            // Kiro 的扩展文档未固定公开 payload schema。保留完整报文日志，既便于
            // 验证 status 字段映射，也能在出现 sessionId 迁移时及时发现。
            logger.info("Kiro 上下文压缩通知: {}", msg);
        }
        AgentProvider.CompactionSignal signal = agentProvider.detectCompactionSignal(msg);
        switch (signal) {
            case STARTED:
                compactionInProgress = true;
                logger.info("Agent 开始压缩上下文, provider={}, sessionId={}",
                        agentProvider.getName(), sessionId);
                break;
            case COMPLETED:
                markCompactionCompleted(listener,
                        "Agent 已完成上下文压缩；下一次 prompt 将完整重注入 ACP harness");
                break;
            case FAILED:
                compactionInProgress = false;
                logger.warn("Agent 上下文压缩失败, provider={}, sessionId={}",
                        agentProvider.getName(), sessionId);
                break;
            case CONTEXT_USAGE_REFRESHED:
                // Claude adapter 的 compact_boundary 被转换为 usage_update。普通 usage_update
                // 不触发重注入，只有已看到 Compaction STARTED 才代表压缩完成。
                if (compactionInProgress) {
                    markCompactionCompleted(listener,
                            "Agent 压缩后的上下文用量已刷新；下一次 prompt 将完整重注入 ACP harness");
                }
                break;
            case NONE:
            default:
                break;
        }
        return signal;
    }

    /**
     * New Codex adapters may emit both a structured contextCompaction item and the
     * historical agent-message marker. Keep the next-turn reinjection and the
     * cmd-proxy-specific completion card idempotent across both representations.
     */
    private void markCompactionCompleted(AcpResponseListener listener, String message) {
        compactionInProgress = false;
        if (!acpHarnessReinjectionPending.compareAndSet(false, true)) {
            logger.debug("忽略重复的上下文压缩完成信号, provider={}, sessionId={}",
                    agentProvider.getName(), sessionId);
            return;
        }
        if (listener != null) {
            listener.onCompactionEvent("COMPACTION_COMPLETED", agentProvider.getName());
        }
        logger.info("{}, provider={}, sessionId={}",
                message, agentProvider.getName(), sessionId);
    }

    /** Rebuilds dynamic ACP harness capabilities on the next accepted prompt. */
    public void requestAcpHarnessReinjection() {
        acpHarnessReinjectionPending.set(true);
    }

    /**
     * 排空迟到 chunk（OpenCode ACP bug workaround：session/update 通知可能在
     * end_turn RPC response 之后送达）。
     * sleep 让管道里迟到的数据到位，然后一次性抽干 reader 缓冲区。
     */
    private void drainLateChunks(StringBuilder fullResponse,
                                 AcpResponseListener listener, Map<String, String> toolTitleCache)
            throws IOException {
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

            AgentProvider.CompactionSignal compactionSignal =
                    observeCompactionSignal(msg, listener);

            if (msg.has("method") && "session/update".equals(msg.get("method").getAsString())) {
                processSessionUpdate(msg, fullResponse, listener, toolTitleCache,
                        compactionSignal);
            } else if (msg.has("method") && "session/request_permission".equals(msg.get("method").getAsString())) {
                autoAllowPermission(msg);
            }
        }
    }

    TalkToRequest resolveChannelReplyTarget(TalkToRequest request,
                                            PromptOptions options) {
        ChannelTurnContext context = options == null ? null : options.getChannelTurnContext();
        if (context == null || request == null || request.getTarget() == null) {
            return request;
        }
        String target = request.getTarget();
        if (!CHANNEL_REPLY_TARGET.equals(target)) return request;
        options.markChannelReplyAttempt();
        return new TalkToRequest(context.getReplyTarget(), request.getContent(),
                request.getDepth(), request.getParentTrace());
    }

    private static String unresolvedChannelReplyResult() {
        return "[talkTo 结果]\n发送失败：当前 turn 没有可唯一恢复的原始信道会话，"
                + "已拒绝按最近对象或主动推送目标猜测发送。";
    }

    public void onTalkToCircuitOpened(String result) {
        if (globalListener != null) publishOrdinaryTalkToResult(executionListener(),
                "智能体通讯", result, result);
    }

    private void publishOrdinaryTalkToResult(AcpResponseListener listener,
                                             String target, String content,
                                             String result) {
        if (talkToDispatcher == null || talkToDispatcher.managesTalkToEvents()) return;
        if (isTalkToCircuitOpen(result)) {
            if (talkToDispatcher.claimCircuitNotification(result)) {
                JsonObject data = new JsonObject();
                data.addProperty("eventType", "TALK_TO_CIRCUIT_OPENED");
                data.addProperty("robotName", target);
                data.addProperty("content", result);
                historyManager.addEventMessage("TALK_TO_CIRCUIT_OPENED", data);
                listener.onTalkToEvent("TALK_TO_CIRCUIT_OPENED", target, result);
            }
        } else if (result != null && !result.contains("发送失败")) {
            listener.onTalkToEvent("TALK_TO_SEND", target, content);
        }
    }

    private static boolean isTalkToCircuitOpen(String result) {
        return result != null && result.startsWith("[TALK_TO_CIRCUIT_OPENED]");
    }

    private boolean deliverAutomaticChannelReply(String content, AcpResponseListener listener,
                                                 PromptOptions options) {
        ChannelTurnContext context = options == null ? null : options.getChannelTurnContext();
        if (context == null || talkToDispatcher == null || content == null
                || content.trim().isEmpty()) {
            return false;
        }
        TalkToRequest request = new TalkToRequest(context.getReplyTarget(), content, 0);
        String senderName = talkToRoutingName();
        String resultText = talkToDispatcher.deliver(request, senderName, extractChatterId(),
                groupId, robotParam != null ? robotParam.getContacts() : null);
        if (!talkToDispatcher.managesTalkToEvents()) {
            listener.onTalkToEvent("TALK_TO_SEND", "channel:" + context.getChannelId(), content);
        }
        logger.info("automatic channel origin reply completed: turnId={}, channelId={}",
                context.getTurnId(), context.getChannelId());
        releaseChannelTurn(options);
        listener.onComplete(resultText);
        return true;
    }

    void releaseChannelTurn(PromptOptions options) {
        if (options == null || !options.hasChannelTurnContext()
                || !options.closeChannelTurnOnce()) return;
        ChannelTurnContext context = options.getChannelTurnContext();
        cleanupExpiredPendingChannelReplies();
        if (hasPendingChannelReply(context)) {
            logger.info("channel turn suspended for pending TalkTo return: turnId={}, channelId={}",
                    context.getTurnId(), context.getChannelId());
            return;
        }
        if (talkToDispatcher != null) {
            talkToDispatcher.releaseExternalTarget(context.getReplyTarget());
        }
        logger.info("channel turn released: turnId={}, channelId={}, replyAttempts={}",
                context.getTurnId(), context.getChannelId(), options.getChannelReplyAttempts());
    }

    private synchronized boolean hasPendingNewSession() {
        return pendingNewSession != null;
    }

    /**
     * 检查并投递 inbox 中的待处理消息。
     * 在 turn 结束 state 变为 READY 后调用。
     */
    private void checkAndDeliverInbox() {
        if (talkToDispatcher == null) return;
        String robotName = talkToRoutingName();
        if (robotName == null || robotName.isEmpty()) return;

        TalkToMessage pending = talkToDispatcher.pollInboxBatch(
                robotName, groupId, TalkToDispatcher.INBOX_BATCH_SIZE);
        if (pending != null && talkToDispatcher.canDeliverMessage(pending)) {
            int batchSize = pending instanceof
                    com.mola.cmd.proxy.app.acp.talkto.model.TalkToBatchMessage
                    ? ((com.mola.cmd.proxy.app.acp.talkto.model.TalkToBatchMessage) pending)
                    .getMessages().size() : 1;
            logger.info("从 inbox 投递消息: from={}, to={}, batchSize={}",
                    pending.getSender(), robotName, batchSize);
            // 先推送来信卡片到前端
            talkToDispatcher.pushIncomingMessageCard(this, pending);
            // 再发送消息，会再次进入 BUSY 状态
            TalkToDispatcher.sendInboundMessage(this, pending);
        }
    }

    private void deliverInboxAfterTurn(long generation, PromptOptions options) {
        int consecutive = options != null && options.isInboundTalkTo()
                ? consecutiveInboxTurns.incrementAndGet() : 0;
        if (consecutive < MAX_CONSECUTIVE_INBOX_TURNS) {
            checkAndDeliverInbox();
            return;
        }
        if (!deferredInboxScheduled.compareAndSet(false, true)) return;
        logger.info("TalkTo inbox fairness delay scheduled: sessionId={}, consecutiveTurns={}",
                sessionId, consecutive);
        INBOX_SCHEDULER.schedule(() -> {
            deferredInboxScheduled.set(false);
            if (!isLifecycleGenerationActive(generation) || getState() != State.READY) return;
            consecutiveInboxTurns.set(0);
            checkAndDeliverInbox();
        }, INBOX_FAIRNESS_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Records only successfully admitted internal TalkTo deliveries originating from a channel
     * turn. Stable/precise channel targets are outbound sends, not delegations.
     */
    void recordPendingChannelReply(TalkToRequest request, PromptOptions options,
                                   String deliveryResult) {
        ChannelTurnContext context = options == null ? null : options.getChannelTurnContext();
        if (context == null || request == null || request.getTarget() == null
                || request.getTarget().startsWith("channel:")
                || deliveryResult == null || deliveryResult.contains("发送失败")) {
            return;
        }
        cleanupExpiredPendingChannelReplies();
        String responder = request.getTarget().trim();
        if (responder.isEmpty()) return;
        long expiresAt = System.currentTimeMillis() + CHANNEL_REPLY_CONTINUATION_TTL_MS;
        PendingChannelReply pending = new PendingChannelReply(
                sessionId, responder, context, expiresAt);
        pendingChannelReplies.computeIfAbsent(responder, ignored -> new ConcurrentHashMap<>())
                .put(context.getTurnId(), pending);
        logger.info("channel TalkTo continuation registered: turnId={}, responder={}, expiresAt={}",
                context.getTurnId(), responder, expiresAt);
    }

    /** Returns channel options only when the verified sender maps to one unique live origin. */
    public PromptOptions promptOptionsForInboundTalkTo(TalkToMessage message) {
        if (message instanceof com.mola.cmd.proxy.app.acp.channel.ChannelTalkToMessage) {
            return withTalkToParent(PromptOptions.forChannelReply(
                    ((com.mola.cmd.proxy.app.acp.channel.ChannelTalkToMessage) message)
                            .getTurnContext()), message);
        }
        cleanupExpiredPendingChannelReplies();
        if (message == null || message.getSender() == null) return PromptOptions.defaults();
        String responder = message.getSender().trim();
        LinkedHashSet<String> responderKeys = new LinkedHashSet<>();
        responderKeys.add(responder);
        int colon = responder.indexOf(':');
        if (colon >= 0 && colon + 1 < responder.length()) {
            responderKeys.add(responder.substring(colon + 1).trim());
        }

        Map<String, PendingChannelReply> liveByTurn = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (String responderKey : responderKeys) {
            ConcurrentHashMap<String, PendingChannelReply> candidates =
                    pendingChannelReplies.get(responderKey);
            if (candidates == null) continue;
            for (PendingChannelReply candidate : candidates.values()) {
                if (candidate.expiresAt > now && Objects.equals(sessionId, candidate.sessionId)) {
                    liveByTurn.put(candidate.context.getTurnId(), candidate);
                }
            }
        }
        if (liveByTurn.isEmpty()) return withTalkToParent(PromptOptions.defaults(), message);
        if (liveByTurn.size() != 1) {
            logger.warn("channel TalkTo continuation is ambiguous: responder={}, candidates={}",
                    responder, liveByTurn.size());
            return withTalkToParent(PromptOptions.defaults(), message);
        }

        PendingChannelReply resolved = liveByTurn.values().iterator().next();
        ConcurrentHashMap<String, PendingChannelReply> candidates =
                pendingChannelReplies.get(resolved.responderKey);
        if (candidates == null) return withTalkToParent(PromptOptions.defaults(), message);
        if (!candidates.remove(resolved.context.getTurnId(), resolved)) {
            return withTalkToParent(PromptOptions.defaults(), message);
        }
        if (candidates.isEmpty()) {
            pendingChannelReplies.remove(resolved.responderKey, candidates);
        }
        logger.info("channel TalkTo continuation restored: turnId={}, responder={}",
                resolved.context.getTurnId(), responder);
        return withTalkToParent(
                PromptOptions.forRestoredChannelReply(resolved.context), message);
    }

    private static PromptOptions withTalkToParent(PromptOptions options,
                                                   TalkToMessage message) {
        if (message != null) {
            options.addTalkToParent(message.getSender(), message.getTrace());
            if (!(message instanceof com.mola.cmd.proxy.app.acp.channel.ChannelTalkToMessage)) {
                options.setInboundTalkTo(true);
            }
        }
        return options;
    }

    private boolean hasPendingChannelReply(ChannelTurnContext context) {
        if (context == null) return false;
        String turnId = context.getTurnId();
        for (ConcurrentHashMap<String, PendingChannelReply> replies
                : pendingChannelReplies.values()) {
            if (replies.containsKey(turnId)) return true;
        }
        return false;
    }

    private void cleanupExpiredPendingChannelReplies() {
        long now = System.currentTimeMillis();
        Map<String, ChannelTurnContext> expiredContexts = new LinkedHashMap<>();
        for (Map.Entry<String, ConcurrentHashMap<String, PendingChannelReply>> byResponder
                : pendingChannelReplies.entrySet()) {
            ConcurrentHashMap<String, PendingChannelReply> replies = byResponder.getValue();
            for (PendingChannelReply pending : replies.values()) {
                if (pending.expiresAt <= now && replies.remove(
                        pending.context.getTurnId(), pending)) {
                    expiredContexts.put(pending.context.getTurnId(), pending.context);
                }
            }
            if (replies.isEmpty()) pendingChannelReplies.remove(byResponder.getKey(), replies);
        }
        releaseOrphanedChannelContexts(expiredContexts.values());
    }

    private void releaseAllPendingChannelReplies() {
        Map<String, ChannelTurnContext> contexts = new LinkedHashMap<>();
        for (ConcurrentHashMap<String, PendingChannelReply> replies
                : pendingChannelReplies.values()) {
            for (PendingChannelReply pending : replies.values()) {
                contexts.put(pending.context.getTurnId(), pending.context);
            }
        }
        pendingChannelReplies.clear();
        releaseOrphanedChannelContexts(contexts.values());
    }

    private void releaseOrphanedChannelContexts(Collection<ChannelTurnContext> contexts) {
        if (talkToDispatcher == null) return;
        for (ChannelTurnContext context : contexts) {
            if (!hasPendingChannelReply(context)) {
                talkToDispatcher.releaseExternalTarget(context.getReplyTarget());
                logger.info("expired channel TalkTo continuation released: turnId={}",
                        context.getTurnId());
            }
        }
    }

    private static final class PendingChannelReply {
        private final String sessionId;
        private final String responderKey;
        private final ChannelTurnContext context;
        private final long expiresAt;

        private PendingChannelReply(String sessionId, String responderKey,
                                    ChannelTurnContext context, long expiresAt) {
            this.sessionId = sessionId;
            this.responderKey = responderKey;
            this.context = context;
            this.expiresAt = expiresAt;
        }
    }

    /**
     * 普通 client 沿用 robotName；Team client 必须使用不可变 teamMemberId，
     * 防止同一来源 robot 被多个 Team/member 复用时发生路由冲突。
     */
    private String talkToRoutingName() {
        if (clientIdentity != null && clientIdentity.isTeam()) {
            return clientIdentity.getTeamMemberId();
        }
        return robotParam != null ? robotParam.getName() : groupId;
    }


    /**
     * 检测工具调用是否读取了记忆明细文件，触发访问强化。
     if (braceStart < 0) return null;

     int braces = 0;
     boolean inString = false;
     boolean escaped = false;

     for (int i = braceStart; i < fullResponse.length(); i++) {
     char c = fullResponse.charAt(i);
     if (escaped) { escaped = false; continue; }
     if (c == '\\' && inString) { escaped = true; continue; }
     if (c == '"') { inString = !inString; continue; }
     if (inString) continue;

     if (c == '{') braces++;
     else if (c == '}') {
     braces--;
     if (braces == 0) {
     return fullResponse.substring(braceStart, i + 1);
     }
     }
     }
     return null;
     }


    /**
     * 检测工具调用是否引用了记忆明细文件，更新访问统计。
     */
    private void detectMemoryAccess(JsonObject rawInput) {
        if (memoryManager == null || rawInput == null) return;
        try {
            List<String> inputStrings = new ArrayList<>();
            collectToolInputStrings(rawInput, inputStrings);
            memoryManager.onMemoryToolInput(workspacePath, inputStrings);
        } catch (Exception e) {
            logger.debug("检测记忆访问失败", e);
        }
    }

    /**
     * 收集工具输入中的全部字符串叶子。结构化 Read 工具的 path 和 Bash/exec
     * 工具的 cmd 都会进入结果；是否引用真实记忆文件由 MemoryManager 按当前
     * 索引中的绝对路径判断，避免在 ACP client 中解析 shell 语法。
     */
    static void collectToolInputStrings(JsonElement element,
                                        Collection<String> output) {
        if (element == null || element.isJsonNull() || output == null) return;
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isString()) output.add(primitive.getAsString());
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectToolInputStrings(child, output);
            }
            return;
        }
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry
                    : element.getAsJsonObject().entrySet()) {
                collectToolInputStrings(entry.getValue(), output);
            }
        }
    }


    // ==================== Getters ====================

    public void setGlobalListener(AcpResponseListener listener) {
        if (listener != null) {
            this.globalListener = listener;
        }
    }

    public void setForceNewSession(boolean forceNewSession) {
        this.forceNewSession = forceNewSession;
    }

    public void setTargetRestoreSessionId(String targetRestoreSessionId) {
        this.targetRestoreSessionId = targetRestoreSessionId;
    }

    public AcpResponseListener getGlobalListener() {
        return globalListener;
    }

    /** Live callback fan-out for out-of-band cards; history replay must keep using globalListener. */
    public AcpResponseListener getLiveOutputListener() {
        return executionListener();
    }

    private AcpResponseListener executionListener() {
        return new CompositeAcpResponseListener(globalListener, gatewayProjection);
    }

    public ConversationHistoryManager getHistoryManager() {
        return historyManager;
    }

    public List<ContextMessage> getConversationHistory() {
        return historyManager.getFullHistory(sessionId);
    }

    public AcpRobotParam getRobotParam() {
        return robotParam;
    }

    public List<Path> getMcpConfigPaths() {
        return Collections.unmodifiableList(mcpConfigPaths);
    }

    /**
     * 从 groupId 中提取 chatterId。
     * groupId = sort(chatterId, acpId).join("")，acpId 以 "acp-" 开头。
     * 通过去掉 acpId 部分得到 chatterId。
     */
    private String extractChatterId() {
        if (clientIdentity != null && clientIdentity.isStarweave()) {
            return clientIdentity.getOwnerId();
        }
        if (groupId == null || groupId.isEmpty()) return "";
        // acpId 格式: "acp-" + robotName.replace(" ", "_")
        String acpId = "";
        if (robotParam != null && robotParam.getName() != null) {
            acpId = "acp-" + robotParam.getName().replace(" ", "_").replace("\u3000", "_");
        }
        if (acpId.isEmpty()) return groupId;
        // groupId 是 sort(chatterId, acpId) 后拼接的，去掉 acpId 部分就是 chatterId
        if (groupId.startsWith(acpId)) {
            return groupId.substring(acpId.length());
        } else if (groupId.endsWith(acpId)) {
            return groupId.substring(0, groupId.length() - acpId.length());
        }
        return groupId;
    }

    // ==================== 图片工具方法 ====================

    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg"));

    private static boolean isImageFile(String path) {
        if (path == null) return false;
        int dot = path.lastIndexOf('.');
        if (dot < 0) return false;
        return IMAGE_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase());
    }

    private static String detectImageMediaType(String path) {
        String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase();
        switch (ext) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "svg": return "image/svg+xml";
            default: return "application/octet-stream";
        }
    }
}
