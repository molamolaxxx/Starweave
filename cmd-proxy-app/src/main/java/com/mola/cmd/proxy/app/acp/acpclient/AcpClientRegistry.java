package com.mola.cmd.proxy.app.acp.acpclient;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AcpClient 注册中心，按 groupId 维护 AcpClient 实例。
 * <p>
 * 每个 groupId 对应一个独立的 AcpClient（独立子进程 + 独立会话）。
 * 提供三个核心接口：创建会话、清除会话、发送消息。
 */
public class AcpClientRegistry {

    private static final Logger logger = LoggerFactory.getLogger(AcpClientRegistry.class);

    private static final AcpClientRegistry INSTANCE = new AcpClientRegistry();

    private final ConcurrentHashMap<String, AcpClient> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();
    private final MainAcpPromptCoordinator promptCoordinator = new MainAcpPromptCoordinator();
    private final ClientFactory clientFactory;

    interface ClientFactory {
        AcpClient create(String workspacePath, String groupId, AcpRobotParam robotParam);
        AcpClient create(String workspacePath, AcpClientIdentity identity,
                         AcpRobotParam robotParam);
    }

    @FunctionalInterface
    public interface ClientInitializer {
        void initialize(AcpClient client) throws Exception;
    }

    private AcpClientRegistry() {
        this(new ClientFactory() {
            @Override
            public AcpClient create(String workspacePath, String groupId,
                                    AcpRobotParam robotParam) {
                return new AcpClient(workspacePath, groupId, robotParam);
            }

            @Override
            public AcpClient create(String workspacePath, AcpClientIdentity identity,
                                    AcpRobotParam robotParam) {
                return new AcpClient(workspacePath, identity, robotParam);
            }
        });
    }

    AcpClientRegistry(ClientFactory clientFactory) {
        this.clientFactory = java.util.Objects.requireNonNull(clientFactory, "clientFactory");
    }

    public static AcpClientRegistry getInstance() {
        return INSTANCE;
    }

    // ==================== 核心接口 ====================

    /**
     * 创建会话：为指定 groupId 创建并启动一个 AcpClient。
     * 如果该 groupId 已有 client，会先关闭旧的再创建新的。
     *
     * @param groupId       分组标识
     * @param workspacePath 工作目录
     * @param robotParam    绑定的 robot 参数
     * @throws IOException 启动失败时抛出
     */
    public void createSession(String groupId, String workspacePath,
                              AcpRobotParam robotParam) throws IOException {
        try {
            createSession(groupId, workspacePath, robotParam, null);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("初始化 AcpClient 失败", e);
        }
    }

    /** Creates, fully wires, and only then starts a client. */
    public AcpClient createSession(String groupId, String workspacePath,
                                   AcpRobotParam robotParam,
                                   ClientInitializer initializer) throws Exception {
        synchronized (sessionLock(groupId)) {
            AcpClient old = clients.get(groupId);
            boolean isClearContext = false;
            if (old != null) {
                logger.info("groupId={} 已有 client，准备替换旧实例", groupId);
                isClearContext = true;
                if (workspacePath == null || workspacePath.trim().isEmpty()) {
                    workspacePath = old.getWorkspacePath();
                }
                if (robotParam == null) {
                    robotParam = old.getRobotParam();
                }
            }

            AcpClient client = clientFactory.create(workspacePath, groupId, robotParam);
            wirePromptLifecycle(groupId, client);
            if (isClearContext) {
                client.setForceNewSession(true);
            }
            try {
                if (initializer != null) initializer.initialize(client);
                client.start();
                promptCoordinator.clear(groupId);
                clients.put(groupId, client);
                closeReplaced(groupId, old);
                logger.info("groupId={} 会话创建成功, sessionId={}", groupId, client.getSessionId());
                return client;
            } catch (Exception e) {
                try { client.close(); } catch (IOException closeError) {
                    logger.warn("关闭启动失败的 AcpClient 失败, groupId={}", groupId, closeError);
                }
                throw e;
            }
        }
    }

    /** Creates a client with an explicit surface-aware identity. */
    public AcpClient createSession(AcpClientIdentity identity, String workspacePath,
                                   AcpRobotParam robotParam, boolean forceNewSession,
                                   ClientInitializer initializer) throws Exception {
        String groupId = identity.getLogicalId();
        synchronized (sessionLock(groupId)) {
            AcpClient old = clients.get(groupId);
            if (old != null) {
                if (workspacePath == null || workspacePath.trim().isEmpty()) {
                    workspacePath = old.getWorkspacePath();
                }
                if (robotParam == null) robotParam = old.getRobotParam();
            }

            AcpClient client = clientFactory.create(workspacePath, identity, robotParam);
            wirePromptLifecycle(groupId, client);
            client.setForceNewSession(forceNewSession || old != null);
            try {
                if (initializer != null) initializer.initialize(client);
                client.start();
                promptCoordinator.clear(groupId);
                clients.put(groupId, client);
                closeReplaced(groupId, old);
                logger.info("groupId={} 显式身份会话创建成功, surface={}, sessionId={}",
                        groupId, identity.getSurface(), client.getSessionId());
                return client;
            } catch (Exception e) {
                try { client.close(); } catch (IOException closeError) {
                    logger.warn("关闭启动失败的 AcpClient 失败, groupId={}", groupId, closeError);
                }
                throw e;
            }
        }
    }

    /** Atomically replaces the exact READY client observed by the caller. */
    public AcpClient replaceSessionIfCurrent(String groupId, AcpClient expected,
                                             String targetRestoreSessionId,
                                             ClientInitializer initializer) throws Exception {
        synchronized (sessionLock(groupId)) {
            AcpClient current = clients.get(groupId);
            if (current == null || current != expected
                    || (current.getState() != AbstractAcpClient.State.READY
                    && !(targetRestoreSessionId == null
                    && current.getState() == AbstractAcpClient.State.SLEEP)
                    && current.getState() != AbstractAcpClient.State.STARTING)) {
                return null;
            }
            String workspacePath = current.getWorkspacePath();
            AcpRobotParam robotParam = current.getRobotParam();
            AcpClientIdentity identity = current.getClientIdentity();
            AcpClient replacement = clientFactory.create(workspacePath, identity, robotParam);
            wirePromptLifecycle(groupId, replacement);
            if (targetRestoreSessionId == null) {
                replacement.setForceNewSession(true);
            } else {
                replacement.setTargetRestoreSessionId(targetRestoreSessionId);
            }
            try {
                if (initializer != null) initializer.initialize(replacement);
                replacement.start();
                if (clients.get(groupId) != current) {
                    try { replacement.close(); } catch (IOException ignored) { }
                    return null;
                }
                promptCoordinator.clear(groupId);
                clients.put(groupId, replacement);
                closeReplaced(groupId, current);
                logger.info("groupId={} 会话替换成功, sessionId={}",
                        groupId, replacement.getSessionId());
                return replacement;
            } catch (Exception e) {
                try { replacement.close(); } catch (IOException ignored) { }
                throw e;
            }
        }
    }

    /** Same replacement primitive with the idle predicate rechecked inside the group lock. */
    public AcpClient replaceIdleSessionIfCurrent(String groupId, AcpClient expected,
                                                 long nowMillis, long idleMillis,
                                                 ClientInitializer initializer) throws Exception {
        synchronized (sessionLock(groupId)) {
            if (clients.get(groupId) != expected
                    || !expected.tryReserveIdleForAutoNewSession(nowMillis, idleMillis)) {
                return null;
            }
            return replaceSessionIfCurrent(groupId, expected, null, initializer);
        }
    }

    public Map<String, AcpClient> snapshotClients() {
        return new java.util.LinkedHashMap<>(clients);
    }

    private Object sessionLock(String groupId) {
        if (groupId == null || groupId.trim().isEmpty()) {
            throw new IllegalArgumentException("groupId must not be blank");
        }
        return sessionLocks.computeIfAbsent(groupId, ignored -> new Object());
    }


    /**
     * 发送消息：向指定 groupId 的 AcpClient 发送用户消息，可附带文件。
     */
    public void sendMessage(String groupId, String message, List<Map<String, String>> files) {
        PromptCommandResult result = sendMessageWithResult(groupId, message, files, null);
        if (!result.isAccepted()) {
            throw new IllegalStateException(result.getResult());
        }
    }

    public PromptCommandResult sendMessageWithResult(String groupId, String message,
                                                     List<Map<String, String>> files,
                                                     String busyPolicy) {
        return sendMessageWithResult(groupId, message, files, busyPolicy,
                PromptOptions.defaults());
    }

    public PromptCommandResult sendMessageWithResult(String groupId, String message,
                                                     List<Map<String, String>> files,
                                                     String busyPolicy,
                                                     PromptOptions options) {
        synchronized (sessionLock(groupId)) {
            AcpClient client = clients.get(groupId);
            if (client == null) {
                return PromptCommandResult.rejected(
                        "REJECTED_STATE", "groupId=" + groupId + " 的会话不存在，请先调用 createSession");
            }
            PromptCommandResult wakeFailure = wakeForRequest(client);
            if (wakeFailure != null) return wakeFailure;
            return promptCoordinator.send(groupId, port(client), message, files,
                    MainAcpPromptCoordinator.BusyPolicy.from(busyPolicy),
                    options == null ? PromptOptions.defaults() : options);
        }
    }

    /** Current MAIN session lookup and observation admission share the lifecycle lock. */
    public boolean sendObservation(String groupId, String prompt) {
        synchronized (sessionLock(groupId)) {
            AcpClient client = clients.get(groupId);
            if (client == null || client.getRobotParam() == null
                    || !client.getRobotParam().isObservationEnabled()) return false;
            if (wakeForRequest(client) != null || client.getState() != AbstractAcpClient.State.READY) return false;
            return client.trySendObservation(prompt);
        }
    }

    /** Task control may interrupt only the currently running turn for the same task. */
    public PromptCommandResult sendTaskMessageWithResult(String groupId, String message,
                                                         PromptOptions options) {
        synchronized (sessionLock(groupId)) {
            AcpClient client = clients.get(groupId);
            if (client == null) {
                return PromptCommandResult.rejected("REJECTED_STATE",
                        "groupId=" + groupId + " task session does not exist");
            }
            PromptCommandResult wakeFailure = wakeForRequest(client);
            if (wakeFailure != null) return wakeFailure;
            if (client.getState() == AbstractAcpClient.State.BUSY) {
                if (options == null || !options.isTaskControl()
                        || !client.isActiveTask(options.getTaskId())) {
                    return PromptCommandResult.rejected("UNRELATED_BUSY_TURN",
                            "Busy turn does not belong to this task");
                }
                return promptCoordinator.send(groupId, port(client), message, null,
                        MainAcpPromptCoordinator.BusyPolicy.INTERRUPT, options);
            }
            return promptCoordinator.send(groupId, port(client), message, null,
                    MainAcpPromptCoordinator.BusyPolicy.REJECT, options);
        }
    }

    public void sendMessage(String groupId, String message, List<Map<String, String>> files,
                            PromptOptions options) {
        synchronized (sessionLock(groupId)) {
            AcpClient client = clients.get(groupId);
            if (client == null) {
                throw new IllegalStateException(
                        "groupId=" + groupId + " 的会话不存在，请先调用 createSession");
            }
            try {
                client.wakeIfSleeping();
            } catch (IOException e) {
                throw new IllegalStateException("唤醒智能体失败", e);
            }
            client.send(message, files, options);
        }
    }

    /** Wakes one MAIN client under its authoritative group lifecycle lock. */
    public boolean wakeIfSleeping(String groupId) throws IOException {
        synchronized (sessionLock(groupId)) {
            AcpClient client = clients.get(groupId);
            return client != null && client.wakeIfSleeping();
        }
    }

    private PromptCommandResult wakeForRequest(AcpClient client) {
        if (client.getState() != AbstractAcpClient.State.SLEEP) return null;
        try {
            client.wakeIfSleeping();
            return null;
        } catch (IOException | RuntimeException failure) {
            return PromptCommandResult.rejected(
                    "WAKE_FAILED", "唤醒智能体失败: " + failure.getMessage());
        }
    }

    /**
     * 取消指定 groupId 当前正在进行的 prompt turn。
     */
    public void cancelPrompt(String groupId) throws IOException {
        PromptCommandResult result = cancelPromptWithResult(groupId);
        if (!result.isAccepted()) {
            if ("CANCEL_FAILED".equals(result.getCode())) {
                throw new IOException(result.getResult());
            }
            throw new IllegalStateException(result.getResult());
        }
    }

    public PromptCommandResult cancelPromptWithResult(String groupId) {
        synchronized (sessionLock(groupId)) {
            AcpClient client = clients.get(groupId);
            if (client == null) {
                return PromptCommandResult.rejected(
                        "REJECTED_STATE", "groupId=" + groupId + " 的会话不存在，请先调用 createSession");
            }
            return promptCoordinator.cancel(port(client));
        }
    }

    /**
     * 查找指定 robotName 对应的所有 groupId。
     */
    public List<String> getGroupIdsByRobot(String robotName) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, AcpClient> entry : clients.entrySet()) {
            AcpClient client = entry.getValue();
            if (client != null && client.getRobotParam() != null
                    && robotName.equals(client.getRobotParam().getName())) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * 获取指定 groupId 的 AcpClient（可能为 null）
     */
    public AcpClient getClient(String groupId) {
        return clients.get(groupId);
    }

    private void closeReplaced(String groupId, AcpClient old) {
        if (old == null) return;
        try {
            old.close();
        } catch (IOException e) {
            logger.warn("关闭已替换的 AcpClient 失败, groupId={}", groupId, e);
        }
    }

    /**
     * 关闭并移除指定 groupId 的 client。
     */
    public void closeByGroupId(String groupId) {
        synchronized (sessionLock(groupId)) {
            promptCoordinator.clear(groupId);
            AcpClient client = clients.remove(groupId);
            if (client != null) {
                try {
                    client.close();
                } catch (IOException e) {
                    logger.warn("关闭 AcpClient 失败, groupId={}", groupId, e);
                }
            }
        }
    }

    /**
     * 恢复指定 sessionId 的会话：关闭旧 client，创建新 client 并指定恢复目标。
     */
    public void restoreSession(String groupId, String targetSessionId) throws IOException {
        try {
            restoreSession(groupId, targetSessionId, null);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("恢复 AcpClient 失败", e);
        }
    }

    public void restoreSession(String groupId, String targetSessionId,
                               ClientInitializer initializer) throws Exception {
        synchronized (sessionLock(groupId)) {
            AcpClient old = clients.get(groupId);
            String workspacePath = null;
            AcpRobotParam robotParam = null;
            AcpClientIdentity identity = null;
            if (old != null) {
                workspacePath = old.getWorkspacePath();
                robotParam = old.getRobotParam();
                identity = old.getClientIdentity();
            }

            AcpClient client = identity == null
                    ? clientFactory.create(workspacePath, groupId, robotParam)
                    : clientFactory.create(workspacePath, identity, robotParam);
            wirePromptLifecycle(groupId, client);
            client.setTargetRestoreSessionId(targetSessionId);
            try {
                if (initializer != null) initializer.initialize(client);
                client.start();
                promptCoordinator.clear(groupId);
                clients.put(groupId, client);
                closeReplaced(groupId, old);
                logger.info("groupId={} 会话恢复成功, sessionId={}",
                        groupId, client.getSessionId());
            } catch (Exception e) {
                try { client.close(); } catch (IOException closeError) {
                    logger.warn("关闭恢复失败的 AcpClient 失败, groupId={}", groupId, closeError);
                }
                throw e;
            }
        }
    }

    /**
     * 关闭所有 client，用于热重载前清理。
     */
    public void closeAll() {
        closeAll(false);
    }

    /** 全局 stop 专用：各 client 只落盘 pending，不提交新的记忆模型调用。 */
    public void closeAllForShutdown() {
        closeAll(true);
    }

    private void closeAll(boolean deferMemoryExtraction) {
        for (Map.Entry<String, AcpClient> entry : snapshotClients().entrySet()) {
            String groupId = entry.getKey();
            synchronized (sessionLock(groupId)) {
                promptCoordinator.clear(groupId);
                AcpClient client = clients.remove(groupId);
                if (client == null) continue;
                try {
                    if (deferMemoryExtraction) {
                        client.closeForShutdown();
                    } else {
                        client.close();
                    }
                } catch (IOException e) {
                    logger.warn("关闭 AcpClient 失败, groupId={}", groupId, e);
                }
            }
        }
        promptCoordinator.clearAll();
        clients.clear();
        logger.info("所有 AcpClient 已关闭");
    }

    private void wirePromptLifecycle(String groupId, AcpClient client) {
        client.setAfterTurnReady(() -> onClientTurnReady(groupId, client));
        client.setAfterTurnFailed(() -> clearPendingIfCurrent(groupId, client));
        client.setRecoverTurnFailureToReady(
                () -> hasInterruptPendingForCurrent(groupId, client));
    }

    private void onClientTurnReady(String groupId, AcpClient client) {
        synchronized (sessionLock(groupId)) {
            if (clients.get(groupId) != client) {
                promptCoordinator.clear(groupId);
                return;
            }
            promptCoordinator.onReady(groupId, port(client));
        }
    }

    private void clearPendingIfCurrent(String groupId, AcpClient client) {
        synchronized (sessionLock(groupId)) {
            if (clients.get(groupId) == client) promptCoordinator.clear(groupId);
        }
    }

    private boolean hasInterruptPendingForCurrent(String groupId, AcpClient client) {
        synchronized (sessionLock(groupId)) {
            return clients.get(groupId) == client
                    && promptCoordinator.hasPending(groupId, port(client));
        }
    }

    private static MainAcpPromptCoordinator.ClientPort port(AcpClient client) {
        return new MainAcpPromptCoordinator.ClientPort() {
            @Override public Object identity() { return client; }
            @Override public AbstractAcpClient.State state() { return client.getState(); }
            @Override public void send(String message, List<Map<String, String>> files) {
                client.send(message, files);
            }
            @Override public void send(String message, List<Map<String, String>> files,
                                       PromptOptions options) {
                if (options == null || !options.isTaskTurn()) {
                    client.send(message, files);
                    return;
                }
                client.send(message, files, options);
            }
            @Override public void cancel() throws IOException { client.cancel(); }
            @Override public void markNextTermination(String termination) {
                client.getLiveOutputListener().markNextTermination(termination);
            }
            @Override public void clearNextTermination() {
                client.getLiveOutputListener().clearNextTermination();
            }
        };
    }
}
