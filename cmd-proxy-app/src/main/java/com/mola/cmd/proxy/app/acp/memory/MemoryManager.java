package com.mola.cmd.proxy.app.acp.memory;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage;
import com.mola.cmd.proxy.app.acp.acpclient.MemoryManagerBridge;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 记忆系统门面类，主 Client 与记忆模块的唯一交互入口。
 * <p>
 * 实现 {@link MemoryManagerBridge} 接口，通过依赖倒置与 acpclient 包解耦。
 * 对外暴露 3 类操作：读取概要、触发提取、管理记忆。
 */
public class MemoryManager implements MemoryManagerBridge {

    private static final Logger logger = LoggerFactory.getLogger(MemoryManager.class);

    private final MemoryConfig config;
    private final MemoryExtractor extractor;
    private final MemoryLoader loader;
    private final MemoryFileStore fileStore;
    private final MemoryDreamer dreamer;
    private final MemoryScopeLockRegistry locks;

    public MemoryManager(MemoryConfig config, AcpRobotParam robotParam) {
        this(config, robotParam, new MemoryScopeLockRegistry());
    }

    public MemoryManager(MemoryConfig config, AcpRobotParam robotParam,
                         MemoryScopeLockRegistry locks) {
        this(config, robotParam, robotParam, null, locks);
    }

    public MemoryManager(MemoryConfig config, AcpRobotParam ownerRobot,
                         AcpRobotParam executionRobot, String executionWorkDir,
                         MemoryScopeLockRegistry locks) {
        this.config = config;
        this.locks = locks;
        String effectiveRobotName = config.isRobotScope() ? ownerRobot.getName() : null;
        this.fileStore = new MemoryFileStore(config.getBaseDir(), effectiveRobotName);
        this.loader = new MemoryLoader(fileStore, config);
        this.extractor = executionRobot == null ? null : new MemoryExtractor(
                config, fileStore, executionRobot, executionWorkDir, locks);
        this.dreamer = executionRobot == null ? null : new MemoryDreamer(
                config, fileStore, executionRobot, executionWorkDir, locks);
    }

    // ==================== MemoryManagerBridge 实现 ====================

    @Override
    public String buildMemoryPrompt(String workspacePath) {
        if (!config.isReadEnabled()) return "";
        ReentrantLock lock = locks.lockFor(fileStore, workspacePath);
        lock.lock();
        try {
            // 检查是否需要自动整理
            checkAndTriggerDream(workspacePath);
            return loader.buildMemoryPrompt(workspacePath);
        } catch (Exception e) {
            logger.error("构建记忆概要失败", e);
            return "";
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void submitExtract(String sourceSessionId, String workspacePath,
                              List<ContextMessage> history) {
        if (!config.isWriteEnabled() || extractor == null
                || history == null || history.isEmpty()) return;
        extractor.submitExtract(sourceSessionId, workspacePath, history);
    }

    @Override
    public void sleepSession(String sourceSessionId) {
        // Cleanup remains necessary even if writes were disabled after client creation.
        if (extractor != null) extractor.sleepSession(sourceSessionId);
    }

    @Override
    public void resumeSession(String sourceSessionId, int historySize) {
        if (!config.isWriteEnabled() || extractor == null) return;
        extractor.resumeSession(sourceSessionId, historySize);
    }

    @Override
    public void submitRecoverActive(String sourceSessionId, String workspacePath,
                                    List<ContextMessage> history,
                                    Runnable onSuccess,
                                    Consumer<Throwable> onFailure) {
        if (!config.isWriteEnabled() || extractor == null
                || history == null || history.isEmpty()) {
            if (onSuccess != null) onSuccess.run();
            return;
        }
        extractor.submitRecoverActive(sourceSessionId, workspacePath, history,
                onSuccess, onFailure);
    }

    @Override
    public void submitExtractFull(String sourceSessionId, String workspacePath,
                                  List<ContextMessage> history,
                                  Runnable onSuccess,
                                  Consumer<Throwable> onFailure) {
        if (!config.isWriteEnabled() || extractor == null
                || history == null || history.isEmpty()) {
            if (onSuccess != null) onSuccess.run();
            return;
        }
        extractor.submitExtractFull(sourceSessionId, workspacePath, history,
                onSuccess, onFailure);
    }

    @Override
    public void incrementSessionCount(String workspacePath) {
        if (!config.isWriteEnabled()) return;
        ReentrantLock lock = locks.lockFor(fileStore, workspacePath);
        lock.lock();
        try {
            fileStore.incrementDreamSessionCount(workspacePath);
        } catch (Exception e) {
            logger.warn("递增 session 计数失败", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onMemoryAccessed(String workspacePath, String filePath) {
        if (!config.isWriteEnabled() || filePath == null) return;
        ReentrantLock lock = locks.lockFor(fileStore, workspacePath);
        lock.lock();
        try {
            fileStore.touchMemory(workspacePath, filePath);
        } catch (Exception e) {
            logger.warn("记忆访问强化失败: {}", filePath, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onMemoryToolInput(String workspacePath,
                                  Collection<String> inputStrings) {
        if (!config.isWriteEnabled() || inputStrings == null
                || inputStrings.isEmpty()) return;
        ReentrantLock lock = locks.lockFor(fileStore, workspacePath);
        lock.lock();
        try {
            fileStore.touchMemoriesReferenced(workspacePath, inputStrings);
        } catch (Exception e) {
            logger.warn("记忆明细访问检测失败", e);
        } finally {
            lock.unlock();
        }
    }

    // ==================== 记忆管理 ====================

    /**
     * 删除指定记忆。
     *
     * @param workspacePath 当前工作目录
     * @param memoryId      记忆 ID
     * @return true 如果成功删除
     */
    public boolean deleteMemory(String workspacePath, String memoryId) {
        ReentrantLock lock = locks.lockFor(fileStore, workspacePath);
        lock.lock();
        try {
            return fileStore.deleteMemory(workspacePath, memoryId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 列出项目的所有记忆。
     *
     * @param workspacePath 当前工作目录
     * @return 记忆列表
     */
    public List<MemoryEntry> listMemories(String workspacePath) {
        ReentrantLock lock = locks.lockFor(fileStore, workspacePath);
        lock.lock();
        try {
            return fileStore.listMemories(workspacePath);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 清理过期的 project 类型记忆。
     *
     * @param workspacePath 当前工作目录
     * @return 清理的记忆数量
     */
    public int cleanExpiredMemories(String workspacePath) {
        ReentrantLock lock = locks.lockFor(fileStore, workspacePath);
        lock.lock();
        try {
            return fileStore.cleanExpiredMemories(
                    workspacePath, config.getProjectExpireDays());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取记忆系统配置。
     */
    public MemoryConfig getConfig() {
        return config;
    }

    /**
     * 获取上次 auto dream 的时间。
     * 供能力反思服务判断记忆是否发生过整理。
     *
     * @return ISO 格式时间字符串，从未整理过时返回 null
     */
    public String getLastDreamTime(String workspacePath) {
        ReentrantLock lock = locks.lockFor(fileStore, workspacePath);
        lock.lock();
        try {
            return fileStore.loadDreamState(workspacePath).getLastDreamTime();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 构建纯记忆条目概要，不含主 agent 专用的警告和操作提示。
     * 供能力反思等子系统使用。
     */
    public String buildMemorySummary(String workspacePath) {
        if (!config.isReadEnabled()) return "";
        ReentrantLock lock = locks.lockFor(fileStore, workspacePath);
        lock.lock();
        try {
            return loader.buildMemorySummary(workspacePath);
        } catch (Exception e) {
            logger.error("构建记忆概要失败", e);
            return "";
        } finally {
            lock.unlock();
        }
    }

    /**
     * 手动触发记忆整理（供 acpMemoryDream 命令调用）。
     */
    public void triggerDream(String workspacePath) {
        if (!config.isWriteEnabled() || dreamer == null) return;
        dreamer.submitDream(workspacePath);
    }

    public boolean triggerDreamIfIdle(String workspacePath) {
        if (!canDream()) {
            throw new IllegalStateException("该智能体未启用可写记忆整理");
        }
        return dreamer.submitDream(workspacePath);
    }

    public boolean canDream() {
        return config.isWriteEnabled() && dreamer != null;
    }

    public boolean isDreaming(String workspacePath) {
        return dreamer != null && dreamer.isDreaming(workspacePath);
    }

    /**
     * 检查并触发自动整理。
     */
    private void checkAndTriggerDream(String workspacePath) {
        if (dreamer == null) return;
        try {
            if (dreamer.shouldDream(workspacePath)) {
                logger.info("满足自动整理条件，触发 Memory Dream, workspacePath={}", workspacePath);
                dreamer.submitDream(workspacePath);
            }
        } catch (Exception e) {
            logger.warn("检查整理条件失败", e);
        }
    }

    /**
     * 关闭记忆系统，释放资源。
     */
    public void shutdown() {
        if (extractor != null) extractor.shutdown();
        if (dreamer != null) dreamer.shutdown();
    }

    /** 进程 stop 使用：取消队列，不等待任何模型调用完成。 */
    public void shutdownNow() {
        if (extractor != null) extractor.shutdownNow();
        if (dreamer != null) dreamer.shutdownNow();
    }
}
