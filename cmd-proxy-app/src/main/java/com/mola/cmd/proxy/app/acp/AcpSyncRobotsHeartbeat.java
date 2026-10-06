package com.mola.cmd.proxy.app.acp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 周期重放 cmd-proxy 的完整 discovery 快照。
 *
 * <p>MolaChat 重启后，固定 {@code acpSyncRobots} group 的主动 send 只能命中一个
 * provider。每个 cmd-proxy 实例主动 heartbeat，才能让接收端重新发现全部 instanceId
 * 与实例级 Team transport。</p>
 */
public final class AcpSyncRobotsHeartbeat implements AutoCloseable {

    private static final Logger logger =
            LoggerFactory.getLogger(AcpSyncRobotsHeartbeat.class);

    private final AcpSyncRobotsSnapshot snapshot;
    private final Consumer<Map<String, String>> publisher;
    private final long interval;
    private final TimeUnit timeUnit;
    private final AtomicBoolean started = new AtomicBoolean();
    private ScheduledExecutorService executor;

    public AcpSyncRobotsHeartbeat(AcpSyncRobotsSnapshot snapshot,
                                  Consumer<Map<String, String>> publisher,
                                  long interval, TimeUnit timeUnit) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        if (interval <= 0L) {
            throw new IllegalArgumentException("interval must be positive");
        }
        this.interval = interval;
        this.timeUnit = Objects.requireNonNull(timeUnit, "timeUnit");
    }

    public synchronized void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "acp-sync-robots-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                this::publishSafely, interval, interval, timeUnit);
    }

    /** 供生命周期测试与诊断使用；与周期任务执行完全相同的 ready 防护。 */
    public void publishOnce() {
        publishSafely();
    }

    private void publishSafely() {
        if (!started.get() || !snapshot.isBusinessCommandsReady()) {
            return;
        }
        try {
            publisher.accept(snapshot.resultMap());
        } catch (RuntimeException e) {
            logger.info("acpSyncRobots heartbeat 未完成，等待下一轮，原因={}",
                    e.getClass().getSimpleName());
        }
    }

    @Override
    public synchronized void close() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
