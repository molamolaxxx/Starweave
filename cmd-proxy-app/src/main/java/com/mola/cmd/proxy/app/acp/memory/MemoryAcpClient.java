package com.mola.cmd.proxy.app.acp.memory;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProviderRouter;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 记忆提取专用子 Client，继承 {@link AbstractAcpClient}。
 * <p>
 * 与主 AcpClient 的关键差异：
 * <ul>
 *   <li>不加载任何 MCP Server，加速启动</li>
 *   <li>同步阻塞模式，返回完整响应文本</li>
 *   <li>内置超时控制</li>
 *   <li>无图片处理、无会话历史、无 listener 回调</li>
 * </ul>
 */
public class MemoryAcpClient extends AbstractAcpClient
        implements MemoryExtractor.MemoryExtractionClient {

    private static final Logger logger = LoggerFactory.getLogger(MemoryAcpClient.class);

    private final int timeoutSeconds;
    private final AtomicBoolean resetAfterRestoreTimeout = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "memory-acp-worker");
        t.setDaemon(true);
        return t;
    });

    public MemoryAcpClient(String workspacePath, String groupId, int timeoutSeconds,
                           AcpRobotParam robotParam, String memoryModel) {
        this(AgentProviderRouter.getInstance().resolve(robotParam.getAgentProvider()),
                workspacePath, groupId, timeoutSeconds, robotParam, memoryModel);
    }

    MemoryAcpClient(AgentProvider provider, String workspacePath, String groupId,
                    int timeoutSeconds, AcpRobotParam robotParam, String memoryModel) {
        super(provider, workspacePath, groupId, buildEffectiveParam(robotParam, memoryModel));
        this.timeoutSeconds = timeoutSeconds;
        String effectiveModel = (memoryModel != null && !memoryModel.trim().isEmpty())
                ? memoryModel.trim() : (robotParam.getModel() != null ? robotParam.getModel() : "default");
        logger.info("Memory子Client使用模型: {} ({})", effectiveModel,
                (memoryModel != null && !memoryModel.trim().isEmpty()) ? "记忆专用" : "继承主Robot");
    }

    private static AcpRobotParam buildEffectiveParam(AcpRobotParam original, String memoryModel) {
        if (memoryModel == null || memoryModel.trim().isEmpty()) {
            return original;
        }
        AcpRobotParam override = new AcpRobotParam();
        override.setName(original.getName());
        override.setWorkDir(original.getWorkDir());
        override.setAgentProvider(original.getAgentProvider());
        override.setProviderVersion(original.getProviderVersion());
        override.setEmbeddedPi(original.getEmbeddedPi());
        override.setApiKey(original.getApiKey());
        override.setCodexHome(original.getCodexHome());
        override.setDeepSeekBaseUrl(original.getDeepSeekBaseUrl());
        override.setDshHome(original.getDshHome());
        override.setDshAgentPreset(original.getDshAgentPreset());
        override.setPermissionPolicy(original.getPermissionPolicy());
        override.setProxyEnabled(original.isProxyEnabled());
        override.setHttpProxy(original.getHttpProxy());
        override.setNoProxy(original.getNoProxy());
        override.setModel(memoryModel.trim());
        return override;
    }

    @Override
    protected void createSession() throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("cwd", workspacePath);
        if ("REMOTE_STARCORE_ACP".equals(robotParamRef.getAgentProvider())) params.addProperty("_starweaveRole", "memory");
        params.add("mcpServers", new JsonArray());  // 不加载任何 MCP
        if (sessionId != null && agentProvider.supportsSessionLoad()) {
            params.addProperty("sessionId", sessionId);
            sendRequest("session/load", params);
            logger.info("Memory session 恢复成功: {}", sessionId);
            return;
        }
        JsonObject response = sendRequest("session/new", params);
        JsonObject result = response.getAsJsonObject("result");
        setSessionId(result.get("sessionId").getAsString());
        logger.info("Memory session 创建成功: {}", getSessionId());
    }

    /**
     * 同步发送 prompt，阻塞等待完整响应文本。
     *
     * @param promptText 发送给子 Client 的完整 prompt
     * @return agent 的完整回答文本
     * @throws IOException 通信失败或超时
     */
    public String sendPromptSync(String promptText) throws IOException {
        final boolean restoring = getState() == State.SLEEP || resetAfterRestoreTimeout.get();
        Future<String> future = executor.submit(() -> {
            if (getState() == State.CLOSING || getState() == State.CLOSED) {
                throw new IOException("Memory client 已关闭");
            }
            // The previous timed-out worker may have finished startup after cancellation.
            // Reset on this same worker before retrying the retained session identity.
            if (resetAfterRestoreTimeout.getAndSet(false)) {
                releaseRuntimeForSleep();
                state.set(State.SLEEP);
            }
            if (getState() == State.SLEEP) {
                try {
                    wakeRuntimeSession();
                } catch (IOException | RuntimeException failure) {
                    throw new SessionRestoreException(failure);
                }
            }
            return doSendPromptSync(promptText, "Memory ACP");
        });
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            IOException timeout = new IOException("记忆提取超时（" + timeoutSeconds + "s）", e);
            if (restoring) {
                resetAfterRestoreTimeout.set(true);
                releaseRuntimeForSleep();
                throw new SessionRestoreException(timeout);
            }
            throw timeout;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("记忆提取失败", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("记忆提取被中断", e);
        }
    }

    /** Called only by the extraction queue after outstanding prompts have completed. */
    @Override
    public void sleep() {
        if (state.compareAndSet(State.READY, State.SLEEP)) {
            releaseRuntimeForSleep();
            logger.info("后台 Memory session 已休眠: {}", sessionId);
        }
    }

    static final class SessionRestoreException extends IOException {
        SessionRestoreException(Throwable cause) {
            super("记忆原会话恢复失败，保留会话等待后续重试", cause);
        }
    }

    @Override
    public void close() throws IOException {
        executor.shutdownNow();
        super.close();
    }
}
