package com.mola.starweave.sdk;

import com.google.gson.JsonObject;
import java.io.Closeable;
import java.io.IOException;

/** Shared message boundary for TCP and browser WebSocket hosts. */
public interface AgentConnection extends Closeable {
    void send(JsonObject frame) throws IOException;
    JsonObject read() throws IOException;
    boolean isAlive();
    void close();
}
