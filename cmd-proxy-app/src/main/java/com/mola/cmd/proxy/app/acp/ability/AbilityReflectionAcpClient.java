package com.mola.cmd.proxy.app.acp.ability;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProviderRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.*;

/**
 * 能力反思专用子 Client，继承 {@link AbstractAcpClient}。
 * <p>
 * 与 MemoryAcpClient 类似，不加载任何 MCP Server，同步阻塞模式。
 * 提取材料由调用方显式提供；提示词排除引擎自动加载的用户级通用 Skills。
 */
public class AbilityReflectionAcpClient extends AbstractAcpClient {

    private static final Logger logger = LoggerFactory.getLogger(AbilityReflectionAcpClient.class);

    private final int timeoutSeconds;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ability-reflection-worker");
        t.setDaemon(true);
        return t;
    });

    public AbilityReflectionAcpClient(String workspacePath, String groupId,
                                      int timeoutSeconds, AcpRobotParam robotParam, String smallModel) {
        super(AgentProviderRouter.getInstance().resolve(robotParam.getAgentProvider()),
                workspacePath, groupId, buildEffectiveParam(robotParam, smallModel));
        this.timeoutSeconds = timeoutSeconds;
    }

    static AcpRobotParam buildEffectiveParam(AcpRobotParam original, String smallModel) {
        if (smallModel == null || smallModel.trim().isEmpty()) {
            throw new IllegalArgumentException("能力提取必须指定已选择的小模型");
        }
        AcpRobotParam copy = new Gson().fromJson(new Gson().toJson(original), AcpRobotParam.class);
        copy.setModel(smallModel.trim());
        return copy;
    }

    @Override
    protected void createSession() throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("cwd", workspacePath);
        params.add("mcpServers", new JsonArray());
        JsonObject response = sendRequest("session/new", params);
        JsonObject result = response.getAsJsonObject("result");
        setSessionId(result.get("sessionId").getAsString());
        logger.info("Ability reflection session 创建成功: {}", getSessionId());
    }

    /**
     * 同步发送 prompt，阻塞等待完整响应文本。
     */
    public String sendPromptSync(String promptText) throws IOException {
        Future<String> future = executor.submit(() -> doSendPromptSync(promptText, "Ability reflection ACP"));
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("能力反思超时（" + timeoutSeconds + "s）", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("能力反思失败", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("能力反思被中断", e);
        }
    }

    @Override
    public void close() throws IOException {
        executor.shutdownNow();
        super.close();
    }
}
