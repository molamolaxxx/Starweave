package com.mola.cmd.proxy.app.acp.acpclient;

import com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * 记忆管理器桥接接口，定义在 acpclient 包中，由 memory 包实现。
 * 目的：避免 acpclient 包直接依赖 memory 包，实现依赖倒置。
 */
public interface MemoryManagerBridge {

    /**
     * 构建记忆概要文本，注入到 prompt 前面。
     */
    String buildMemoryPrompt(String workspacePath);

    /**
     * 提交增量记忆提取任务到异步队列。
     * 只分析上次提取之后的新对话。每 N 轮触发时使用。
     */
    void submitExtract(String sourceSessionId, String workspacePath,
                       List<ContextMessage> history);

    /** Request background runtime sleep after admitted extraction work, retaining progress. */
    default void sleepSession(String sourceSessionId) {
    }

    /**
     * 登记恢复会话已经加载的历史基线。基线之前的消息不应再次参与增量提取。
     */
    default void resumeSession(String sourceSessionId, int historySize) {
    }

    /**
     * 补偿当前仍在使用的恢复会话。成功后保留专属 Memory session 和提取游标，
     * 供后续增量提取继续复用；失败时不得推进游标。
     */
    default void submitRecoverActive(String sourceSessionId,
                                     String workspacePath,
                                     List<ContextMessage> history,
                                     Runnable onSuccess,
                                     Consumer<Throwable> onFailure) {
        submitExtractFull(sourceSessionId, workspacePath, history,
                onSuccess, onFailure);
    }

    /**
     * 提交会话结束记忆提取任务到异步队列。
     * 实现应只分析尚未成功提取的尾部；恢复时没有游标则以完整历史兜底。
     */
    default void submitExtractFull(String sourceSessionId,
                                   String workspacePath,
                                   List<ContextMessage> history) {
        submitExtractFull(sourceSessionId, workspacePath, history, () -> {
        }, ignored -> {
        });
    }

    /**
     * 提交可确认完成结果的会话结束提取任务。只有记忆索引实际更新完成（或模型判断
     * 无需更新）后才调用 onSuccess；提交失败、队列取消或执行异常调用 onFailure。
     */
    void submitExtractFull(String sourceSessionId, String workspacePath,
                           List<ContextMessage> history,
                           Runnable onSuccess, Consumer<Throwable> onFailure);

    /**
     * 递增 session 计数，用于 Dream（记忆整理）触发条件判断。
     * 在 AcpClient.close() 中调用。
     */
    void incrementSessionCount(String workspacePath);

    /**
     * 记录一次记忆访问（访问强化）。
     * 当 Agent 读取了 memories/ 目录下的明细文件时调用。
     *
     * @param workspacePath 当前工作目录
     * @param filePath      被读取的文件路径
     */
    void onMemoryAccessed(String workspacePath, String filePath);

    /**
     * 记录一次工具调用中出现的记忆明细引用。
     * <p>
     * 与 {@link #onMemoryAccessed(String, String)} 的结构化 path 入口不同，
     * inputStrings 也可能包含 Bash/exec 的完整命令。实现方只应匹配当前索引中
     * 已知的明细文件绝对路径，同一次工具调用对同一记忆最多计数一次。
     */
    default void onMemoryToolInput(String workspacePath,
                                   Collection<String> inputStrings) {
    }
}
