package com.mola.starweave.agent.transport;

import java.io.*;
import java.util.concurrent.TimeUnit;

/** Compatibility boundary for ACP clients which still expose Process to subclasses. */
public final class TransportProcess extends Process {
    private final AcpTransport transport;
    public TransportProcess(AcpTransport transport) { this.transport = transport; }
    public AcpTransport transport() { return transport; }
    public OutputStream getOutputStream() { return transport.output(); }
    public InputStream getInputStream() { return transport.input(); }
    public InputStream getErrorStream() { return transport.errors(); }
    public boolean isAlive() { return transport.isAlive(); }
    public int exitValue() { return transport.exitValue(); }
    public int waitFor() throws InterruptedException { while (!transport.awaitExit(1, TimeUnit.DAYS)) { } return transport.exitValue(); }
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException { return transport.awaitExit(timeout, unit); }
    public void destroy() { try { transport.close(); } catch (IOException ignored) { } }
    public Process destroyForcibly() { destroy(); return this; }
}
