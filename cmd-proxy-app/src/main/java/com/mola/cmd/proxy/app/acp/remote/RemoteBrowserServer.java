package com.mola.cmd.proxy.app.acp.remote;

import com.google.gson.*;
import com.mola.starweave.sdk.*;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.servlet.*;
import org.eclipse.jetty.websocket.api.*;
import org.eclipse.jetty.websocket.servlet.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Browser transport only: registration and ownership remain in RemoteAcpManager. */
final class RemoteBrowserServer implements AutoCloseable {
    static final String PATH = "/api/remote-acp/browser/ws";
    private final Server server = new Server();
    private final ConcurrentMap<String, ResumableBrowserConnection> resumable = new ConcurrentHashMap<>();
    RemoteBrowserServer(RemoteAcpManager manager, String host, int port) {
        ServerConnector connector = new ServerConnector(server);
        connector.setHost(host == null || host.trim().isEmpty() ? "0.0.0.0" : host); connector.setPort(port); server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS); context.setContextPath("/");
        context.addServlet(new ServletHolder(new WebSocketServlet() {
            public void configure(WebSocketServletFactory factory) {
                factory.getPolicy().setMaxTextMessageSize(Wire.MAX_FRAME);
                factory.getPolicy().setIdleTimeout(30000);
                factory.setCreator((request, response) -> {
                    String origin = request.getHeader("Origin");
                    if (origin != null && !origin.startsWith("chrome-extension://") && !origin.startsWith("moz-extension://")) {
                        try { response.sendForbidden("Extension origin required"); } catch (IOException ignored) { } return null;
                    }
                    return new BrowserConnection(manager, resumable);
                });
            }
        }), PATH);
        server.setHandler(context);
    }
    void start() throws Exception { server.start(); }
    int getPort() { return ((ServerConnector)server.getConnectors()[0]).getLocalPort(); }
    public void close() { for (ResumableBrowserConnection link : resumable.values()) link.close(); try { server.stop(); } catch (Exception ignored) { } }

    static class BrowserConnection extends WebSocketAdapter implements AgentConnection {
        private final RemoteAcpManager manager;
        private final BlockingQueue<JsonObject> incoming = new ArrayBlockingQueue<>(64), outgoing = new ArrayBlockingQueue<>(128);
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicLong queuedBytes = new AtomicLong(), incomingBytes = new AtomicLong();
        private final ConcurrentMap<String, ResumableBrowserConnection> resumable;
        private ResumableBrowserConnection logical;
        private boolean started;
        BrowserConnection(RemoteAcpManager manager, ConcurrentMap<String, ResumableBrowserConnection> resumable) { this.manager = manager; this.resumable = resumable; }
        @Override public void onWebSocketConnect(Session session) {
            super.onWebSocketConnect(session);
            FramedConnection.daemon("browser-writer", () -> {
                try {
                    while (isAlive()) {
                        JsonObject frame = outgoing.poll(1, TimeUnit.SECONDS);
                        if (frame == null) continue;
                        String text = frame.toString();
                        try { getRemote().sendStringByFuture(text).get(10, TimeUnit.SECONDS); }
                        finally { queuedBytes.addAndGet(-text.getBytes(StandardCharsets.UTF_8).length); }
                    }
                } catch (Exception e) { if (logical != null) logical.detach(this, "writer " + e.getClass().getSimpleName()); close(); }
            });

        }
        @Override public void onWebSocketText(String text) {
            try {
                JsonObject frame = JsonParser.parseString(text).getAsJsonObject();
                if (!started) {
                    started = true;
                    String token = Wire.str(frame, "resumeToken");
                    if (!token.isEmpty()) {
                        if (!token.matches("[a-zA-Z0-9-]{64,128}") || !"register".equals(Wire.str(frame, "type"))) throw new IOException("Invalid resume registration");
                        ResumableBrowserConnection fresh = new ResumableBrowserConnection(frame, () -> resumable.remove(token));
                        ResumableBrowserConnection previous = resumable.putIfAbsent(token, fresh);
                        logical = previous == null ? fresh : previous;
                        if (!logical.matches(frame)) throw new IOException("Resume identity mismatch");
                        logical.attach(this, frame, previous != null);
                        if (previous == null) {
                            try { manager.acceptBrowser(logical); } catch (IOException error) { logical.close(); throw error; }
                        }
                        return;
                    }
                    manager.acceptBrowser(this);
                }
                if (logical != null) { logical.receive(this, frame); return; }
                int bytes = text.getBytes(StandardCharsets.UTF_8).length;
                if (bytes > Wire.MAX_FRAME || incomingBytes.addAndGet(bytes) > 64L * 1024 * 1024 || !incoming.offer(frame)) close();
            } catch (RuntimeException | IOException e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Browser protocol rejected: {}", e.getMessage()); close(); }
        }
        @Override public void onWebSocketClose(int status, String reason) { if (logical != null) logical.detach(this, "WebSocket " + status + ": " + reason); close(); super.onWebSocketClose(status, reason); }
        @Override public void onWebSocketError(Throwable cause) { if (logical != null) logical.detach(this, cause.getClass().getSimpleName()); close(); }
        public void send(JsonObject frame) throws IOException {
            int bytes = frame.toString().getBytes(StandardCharsets.UTF_8).length;
            if (!isAlive() || bytes > Wire.MAX_FRAME || queuedBytes.addAndGet(bytes) > 64L * 1024 * 1024 || !outgoing.offer(frame.deepCopy())) {
                close(); throw new IOException("Browser connection closed or queue full");
            }
        }
        public JsonObject read() throws IOException {
            try {
                JsonObject frame = incoming.poll(30, TimeUnit.SECONDS);
                if (frame == null || !isAlive()) throw new EOFException("Browser disconnected or registration timed out");
                incomingBytes.addAndGet(-frame.toString().getBytes(StandardCharsets.UTF_8).length);
                return frame;
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
        }
        public boolean isAlive() { return !closed.get() && isConnected(); }
        public void close() {
            if (closed.compareAndSet(false, true)) {
                if (logical != null) logical.detach(this, "physical transport closed");
                incoming.clear(); incoming.offer(new JsonObject()); outgoing.clear();
                Session session = getSession(); if (session != null && session.isOpen()) session.close();
            }
        }
    }
}
