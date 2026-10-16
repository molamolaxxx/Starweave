package com.mola.starweave.agent.transport;

import java.io.*;
import java.util.concurrent.TimeUnit;

/** Preserves the lifecycle of existing subprocess and embedded endpoints. */
public final class ProcessAcpTransport implements AcpTransport {
    private final Process process;
    public ProcessAcpTransport(Process process) { this.process = process; }
    public InputStream input() { return process.getInputStream(); }
    public OutputStream output() { return process.getOutputStream(); }
    public InputStream errors() { return process.getErrorStream(); }
    public boolean isAlive() { return process.isAlive(); }
    public boolean awaitExit(long timeout, TimeUnit unit) throws InterruptedException { return process.waitFor(timeout, unit); }
    public int exitValue() { return process.exitValue(); }
    public void close() { process.destroy(); }
}
