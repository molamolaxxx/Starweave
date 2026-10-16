package com.mola.starweave.agent.transport;

import java.io.*;
import java.util.concurrent.TimeUnit;

/** Owns one ACP endpoint, never a shared connection or the host JVM. */
public interface AcpTransport extends Closeable {
    /** Stage centrally uploaded files and return paths readable by this endpoint. */
    default java.util.Map<String, String> stageFiles(java.util.Collection<String> paths) throws IOException {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>(); for (String path : paths) result.put(path, path); return result;
    }
    InputStream input();
    OutputStream output();
    default InputStream errors() { return new ByteArrayInputStream(new byte[0]); }
    boolean isAlive();
    boolean awaitExit(long timeout, TimeUnit unit) throws InterruptedException;
    default int exitValue() { if (isAlive()) throw new IllegalThreadStateException("ACP runtime active"); return 0; }
}
