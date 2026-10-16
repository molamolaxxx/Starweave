package com.mola.starweave.agent;

import java.io.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Cancels active network calls, child tools and permission waits without stopping JVM threads. */
final class Cancellation {
    private boolean cancelled;
    private final Set<Runnable> hooks = new HashSet<>();
    synchronized void check() { if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException("执行已取消"); }
    synchronized boolean isCancelled() { return cancelled; }
    AutoCloseable onCancel(Runnable hook) {
        synchronized (this) { if (!cancelled) { hooks.add(hook); return () -> { synchronized (Cancellation.this) { hooks.remove(hook); } }; } }
        hook.run(); return () -> { };
    }
    void cancel() {
        List<Runnable> actions;
        synchronized (this) { if (cancelled) return; cancelled = true; actions = new ArrayList<>(hooks); hooks.clear(); }
        for (Runnable action : actions) try { action.run(); } catch (RuntimeException ignored) { }
    }
}
