package com.mola.cmd.proxy.app.acp.registry.tunnel;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.registry.RemoteEnvironmentRegistry;
import io.netty.bootstrap.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.group.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.*;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.ssl.*;
import io.netty.handler.timeout.*;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** 一条 TLS 控制连接，每个 TCP 流独立回连；不缓存完整 HTTP 请求、不重放业务流。 */
public final class NettyTunnelProvider implements TunnelProvider {
    private static final int MAX_FRAME = 16384, MAX_STREAMS = 256;
    private NioEventLoopGroup serverGroup, clientGroup;
    private Channel server;
    private volatile Channel client;
    private volatile boolean clientReady;
    private ChannelGroup serverChannels, clientChannels;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private TunnelTls tls;
    private boolean closed;
    private volatile RealtimeReceiver realtimeReceiver;
    private final Map<String, RealtimePeer> realtimePeers = new ConcurrentHashMap<>();
    private volatile RealtimePeer clientRealtime = new RealtimePeer();
    private String realtimeIdentity = "";
    private DefaultEventExecutorGroup serverRealtimeWorkers, clientRealtimeWorkers;

    @Override public void realtimeReceiver(RealtimeReceiver receiver) { realtimeReceiver = receiver; }
    @Override public boolean realtimeClientAlive() { return clientRealtime.connected(); }
    @Override public boolean sendRealtime(String environmentId, JSONObject event) {
        RealtimePeer peer = environmentId == null ? clientRealtime : realtimePeers.get(environmentId);
        return peer != null && peer.offer(event);
    }

    @Override public synchronized void startServer(int port, String token, Authorizer authorizer) throws IOException {
        ensureOpen(); stopServer();
        if (tls == null) tls = new TunnelTls();
        serverGroup = group("registry-tunnel-server");
        serverRealtimeWorkers = realtimeWorkers("registry-realtime-server");
        serverChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        final NioEventLoopGroup group = serverGroup;
        final ChannelGroup channels = serverChannels;
        try {
            server = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel ch) {
                            channels.add(ch);
                            ch.pipeline().addLast("ssl", tls.context.newHandler(ch.alloc()));
                            framed(ch);
                            ch.pipeline().addLast("handshake", new ServerHandshake(token, authorizer, group, channels));
                        }
                    }).bind(new InetSocketAddress("0.0.0.0", port)).sync().channel();
            channels.add(server);
        } catch (Exception e) { stopServer(); throw failure(e); }
    }
    @Override public synchronized String serverCertificate() { return tls == null ? "" : tls.certificate; }

    @Override public synchronized void startClient(String host, int port, String token, String id, String lease,
                                                  int remotePort, int localPort, String certificate) throws IOException {
        ensureOpen(); stopClient();
        // Registry leases rotate on reconnect; queued display events still belong to the same center.
        String identity = host + ":" + port;
        if (!identity.equals(realtimeIdentity)) { clientRealtime.close(); clientRealtime = new RealtimePeer(); realtimeIdentity = identity; }
        SslContext context = TunnelTls.client(certificate);
        clientGroup = group("registry-tunnel-client");
        clientRealtimeWorkers = realtimeWorkers("registry-realtime-client");
        clientChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        final NioEventLoopGroup group = clientGroup;
        final ChannelGroup channels = clientChannels;
        ClientControl control = new ClientControl(host, port, token, id, lease, remotePort, localPort, context, group, channels);
        try {
            client = connect(group, channels, host, port, ch -> {
                client = ch;
                ch.pipeline().addLast("ssl", context.newHandler(ch.alloc(), host, port));
                framed(ch); ch.pipeline().addLast("control", control);
            }).sync().channel();
            // TCP 已连接并不代表通过 TLS 和租约校验；clientAlive 只报告收到 ACK 的连接。
        } catch (Exception e) { stopClient(); throw failure(e); }
    }
    @Override public synchronized boolean serverAlive() { return server != null && server.isActive(); }
    @Override public boolean clientAlive() { Channel ch = client; return clientReady && ch != null && ch.isActive(); }
    @Override public synchronized void stopServer() {
        for (Session session : sessions.values()) session.close();
        sessions.clear();
        for (RealtimePeer peer : realtimePeers.values()) peer.close();
        realtimePeers.clear();
        shutdown(serverChannels, serverGroup); serverChannels = null; serverGroup = null; server = null;
        if (serverRealtimeWorkers != null) serverRealtimeWorkers.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly();
        serverRealtimeWorkers = null;
    }
    @Override public synchronized void stopClient() {
        clientReady = false; client = null;
        shutdown(clientChannels, clientGroup); clientChannels = null; clientGroup = null;
        if (clientRealtimeWorkers != null) clientRealtimeWorkers.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly();
        clientRealtimeWorkers = null;
    }
    @Override public synchronized void close() { closed = true; stopClient(); stopServer(); clientRealtime.close(); }
    private void ensureOpen() throws IOException { if (closed) throw new IOException("隧道已关闭"); }
    private static NioEventLoopGroup group(String name) {
        return new NioEventLoopGroup(1, (java.util.concurrent.ThreadFactory) r -> { Thread t = new Thread(r, name); t.setDaemon(true); return t; });
    }
    private static DefaultEventExecutorGroup realtimeWorkers(String name) {
        return new DefaultEventExecutorGroup(2, (java.util.concurrent.ThreadFactory) r -> {
            Thread t = new Thread(r, name); t.setDaemon(true); return t;
        });
    }
    private static void shutdown(ChannelGroup channels, NioEventLoopGroup group) {
        if (channels != null) channels.close().awaitUninterruptibly();
        if (group != null) group.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly();
    }
    private static IOException failure(Exception e) {
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        return new IOException("隧道连接失败，请检查地址和监听端口", e);
    }
    private interface Initializer { void init(SocketChannel ch); }
    private static ChannelFuture connect(NioEventLoopGroup group, ChannelGroup channels, String host, int port, Initializer init) {
        return new Bootstrap().group(group).channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) { channels.add(ch); init.init(ch); }
                }).connect(host, port);
    }
    private static void framed(Channel ch) {
        ch.pipeline().addLast("idle", new IdleStateHandler(30, 10, 0));
        ch.pipeline().addLast("frames", new LengthFieldBasedFrameDecoder(MAX_FRAME, 0, 4, 0, 4));
    }
    private static JSONObject message(String type) { JSONObject m = new JSONObject(); m.put("type", type); m.put("version", 1); return m; }
    private static ChannelFuture send(Channel ch, JSONObject m) {
        byte[] bytes = m.toJSONString().getBytes(StandardCharsets.UTF_8);
        return ch.writeAndFlush(ch.alloc().buffer(bytes.length + 4).writeInt(bytes.length).writeBytes(bytes));
    }
    private abstract static class Messages extends SimpleChannelInboundHandler<ByteBuf> {
        @Override protected final void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
            JSONObject m = JSON.parseObject(frame.toString(StandardCharsets.UTF_8));
            if (m == null || m.getIntValue("version") != 1) { ctx.close(); return; }
            receive(ctx, m);
        }
        abstract void receive(ChannelHandlerContext ctx, JSONObject m);
        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) { ctx.close(); }
        @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
            if (event instanceof IdleStateEvent) {
                if (((IdleStateEvent) event).state() == IdleState.READER_IDLE) ctx.close();
                else send(ctx.channel(), message("PING"));
            } else ctx.fireUserEventTriggered(event);
        }
    }
    private final class ServerHandshake extends Messages {
        final String token;
        final Authorizer authorizer;
        final NioEventLoopGroup group;
        final ChannelGroup channels;
        ServerHandshake(String token, Authorizer authorizer, NioEventLoopGroup group, ChannelGroup channels) {
            this.token = token; this.authorizer = authorizer; this.group = group; this.channels = channels;
        }
        @Override public void handlerAdded(ChannelHandlerContext ctx) {
            ctx.executor().schedule(() -> { if (ctx.pipeline().context(this) != null) ctx.close(); }, 10, TimeUnit.SECONDS);
        }
        @Override void receive(ChannelHandlerContext ctx, JSONObject m) {
            String type = m.getString("type"), id = m.getString("environmentId"), lease = m.getString("lease");
            if (!RemoteEnvironmentRegistry.equal(token, m.getString("token"))) { ctx.close(); return; }
            if ("CONTROL".equals(type)) {
                String run = UUID.randomUUID().toString(); int port = m.getIntValue("remotePort");
                if (!authorizer.authorize(id, lease, port, null)) { ctx.close(); return; }
                Session old = sessions.remove(id); if (old != null) old.close();
                authorizer.connected(id, lease, run);
                Session session = new Session(id, lease, run, port, ctx.channel(), authorizer, group, channels);
                sessions.put(id, session);
                ctx.pipeline().replace(this, "control", new ServerControl(session));
                new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                        .childOption(ChannelOption.AUTO_READ, false).childOption(ChannelOption.TCP_NODELAY, true)
                        .childHandler(new ChannelInitializer<SocketChannel>() {
                            @Override protected void initChannel(SocketChannel ch) { channels.add(ch); session.open(ch); }
                        }).bind("127.0.0.1", port).addListener((ChannelFutureListener) f -> {
                            if (!f.isSuccess() || !session.control.isActive() || sessions.get(id) != session) {
                                if (f.isSuccess()) f.channel().close(); session.close(); return;
                            }
                            session.listener = f.channel(); channels.add(f.channel());
                            JSONObject ack = message("ACK"); ack.put("realtimeVersion", 1); send(session.control, ack);
                        });
            } else if ("REALTIME".equals(type)) {
                Session session = sessions.get(id);
                if (session == null || !RemoteEnvironmentRegistry.equal(session.lease, lease)
                        || !authorizer.authorize(id, lease, session.port, session.run)) { ctx.close(); return; }
                RealtimePeer peer = realtimePeers.computeIfAbsent(id, ignored -> new RealtimePeer());
                session.realtime = ctx.channel();
                realtimeFrames(ctx.channel());
                ctx.pipeline().remove(this);
                ctx.pipeline().addLast(serverRealtimeWorkers, "realtime", new RealtimeMessages(peer, id, lease,
                        () -> sessions.get(id) == session && authorizer.authorize(id, lease, session.port, session.run)));
                send(ctx.channel(), message("REALTIME_READY"));
                peer.attach(ctx.channel());
            } else if ("DATA".equals(type)) {
                Session session = sessions.get(id);
                if (session == null || !RemoteEnvironmentRegistry.equal(session.lease, lease)
                        || !authorizer.authorize(id, lease, session.port, session.run)) { ctx.close(); return; }
                Channel pending = session.pending.remove(m.getString("stream"));
                if (pending == null || !pending.isActive()) { ctx.close(); return; }
                session.streams.add(ctx.channel());
                ctx.channel().config().setAutoRead(false);
                send(ctx.channel(), message("ACK")).addListener((ChannelFutureListener) f -> {
                    if (!f.isSuccess()) { pending.close(); ctx.close(); return; }
                    raw(ctx.channel(), pending, "handshake");
                    raw(pending, ctx.channel(), null);
                    pending.config().setAutoRead(true); ctx.channel().config().setAutoRead(true);
                });
            } else ctx.close();
        }
    }
    private final class Session {
        final String id, lease, run;
        final int port;
        final Channel control;
        final Authorizer authorizer;
        final NioEventLoopGroup group;
        final ChannelGroup channels;
        final ChannelGroup streams = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        final Map<String, Channel> pending = new ConcurrentHashMap<>();
        volatile Channel listener;
        volatile Channel realtime;
        Session(String id, String lease, String run, int port, Channel control, Authorizer authorizer,
                NioEventLoopGroup group, ChannelGroup channels) {
            this.id = id; this.lease = lease; this.run = run; this.port = port;
            this.control = control; this.authorizer = authorizer; this.group = group; this.channels = channels;
        }
        void open(Channel ch) {
            if (!control.isActive() || streams.size() >= MAX_STREAMS || !authorizer.authorize(id, lease, port, run)) { ch.close(); return; }
            streams.add(ch); String stream = UUID.randomUUID().toString(); pending.put(stream, ch);
            ch.closeFuture().addListener(f -> pending.remove(stream, ch));
            control.eventLoop().schedule(() -> { Channel waiting = pending.remove(stream); if (waiting != null) waiting.close(); }, 10, TimeUnit.SECONDS);
            JSONObject m = message("OPEN"); m.put("stream", stream);
            send(control, m).addListener((ChannelFutureListener) f -> { if (!f.isSuccess()) ch.close(); });
        }
        void close() {
            if (listener != null) listener.close(); if (realtime != null) realtime.close(); streams.close(); pending.clear(); control.close();
        }
    }
    private final class ServerControl extends Messages {
        final Session session;
        ServerControl(Session session) { this.session = session; }
        @Override void receive(ChannelHandlerContext ctx, JSONObject m) {
            if (sessions.get(session.id) != session || !session.authorizer.authorize(session.id, session.lease, session.port, session.run)) { ctx.close(); return; }
            if ("PING".equals(m.getString("type"))) send(ctx.channel(), message("PONG"));
            else if (!"PONG".equals(m.getString("type"))) ctx.close();
        }
        @Override public void channelInactive(ChannelHandlerContext ctx) {
            sessions.remove(session.id, session); session.close();
            session.authorizer.disconnected(session.id, session.lease, session.run);
        }
    }
    private final class ClientControl extends Messages {
        private boolean realtimeConnecting;
        final String host, token, id, lease;
        final int port, remotePort, localPort;
        final SslContext ssl;
        final NioEventLoopGroup group;
        final ChannelGroup channels;
        final ChannelGroup streams = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        ClientControl(String host, int port, String token, String id, String lease, int remotePort, int localPort,
                      SslContext ssl, NioEventLoopGroup group, ChannelGroup channels) {
            this.host = host; this.port = port; this.token = token; this.id = id; this.lease = lease;
            this.remotePort = remotePort; this.localPort = localPort; this.ssl = ssl; this.group = group; this.channels = channels;
        }
        JSONObject hello(String type) {
            JSONObject m = message(type); m.put("token", token); m.put("environmentId", id); m.put("lease", lease); m.put("remotePort", remotePort); return m;
        }
        @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
            if (event instanceof SslHandshakeCompletionEvent) {
                if (((SslHandshakeCompletionEvent) event).isSuccess()) send(ctx.channel(), hello("CONTROL")); else ctx.close();
            } else super.userEventTriggered(ctx, event);
        }
        @Override void receive(ChannelHandlerContext ctx, JSONObject m) {
            String type = m.getString("type");
            if ("ACK".equals(type)) { if (client == ctx.channel()) { clientReady = true; if (m.getIntValue("realtimeVersion") == 1) openRealtime(); } }
            else if ("PING".equals(type)) send(ctx.channel(), message("PONG"));
            else if ("OPEN".equals(type)) {
                if (streams.size() >= MAX_STREAMS) { ctx.close(); return; }
                String stream = m.getString("stream");
                if (stream == null || !stream.matches("[a-f0-9-]{36}")) { ctx.close(); return; }
                connect(group, channels, host, port, ch -> {
                    streams.add(ch); ch.pipeline().addLast("ssl", ssl.newHandler(ch.alloc(), host, port));
                    framed(ch); ch.pipeline().addLast("handshake", new ClientData(this, stream));
                });
            } else if (!"PONG".equals(type)) ctx.close();
        }
        @Override public void channelInactive(ChannelHandlerContext ctx) { if (client == ctx.channel()) clientReady = false; streams.close(); }
        void openRealtime() {
            if (realtimeConnecting || !clientAlive() || group.isShuttingDown()) return;
            realtimeConnecting = true;
            connect(group, channels, host, port, ch -> {
                ch.pipeline().addLast("ssl", ssl.newHandler(ch.alloc(), host, port));
                framed(ch); realtimeFrames(ch); ch.pipeline().addLast("handshake", new ClientRealtime(this));
            }).addListener((ChannelFutureListener) future -> {
                future.channel().closeFuture().addListener(ignored -> {
                    realtimeConnecting = false;
                    if (!group.isShuttingDown()) group.schedule(this::openRealtime, 1, TimeUnit.SECONDS);
                });
            });
        }
    }
    private final class ClientRealtime extends Messages {
        final ClientControl control;
        ClientRealtime(ClientControl control) { this.control = control; }
        @Override public void handlerAdded(ChannelHandlerContext ctx) {
            ctx.executor().schedule(() -> { if (ctx.pipeline().context(this) != null) ctx.close(); }, 10, TimeUnit.SECONDS);
        }
        @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
            if (event instanceof SslHandshakeCompletionEvent) {
                if (((SslHandshakeCompletionEvent) event).isSuccess()) send(ctx.channel(), control.hello("REALTIME"));
                else ctx.close();
            } else super.userEventTriggered(ctx, event);
        }
        @Override void receive(ChannelHandlerContext ctx, JSONObject m) {
            if (!"REALTIME_READY".equals(m.getString("type"))) { ctx.close(); return; }
            realtimeFrames(ctx.channel()); ctx.pipeline().remove(this);
            ctx.pipeline().addLast(clientRealtimeWorkers, "realtime", new RealtimeMessages(clientRealtime, null, control.lease,
                    () -> clientAlive() && clientGroup == control.group));
            clientRealtime.attach(ctx.channel());
        }
    }
    private static void realtimeFrames(Channel channel) {
        channel.pipeline().replace("frames", "frames", new LengthFieldBasedFrameDecoder(RealtimePeer.MAX_FRAME, 0, 4, 0, 4));
    }
    private final class RealtimeMessages extends Messages {
        final RealtimePeer peer;
        final String environmentId, lease;
        final java.util.function.BooleanSupplier authorized;
        RealtimeMessages(RealtimePeer peer, String id, String lease, java.util.function.BooleanSupplier authorized) {
            this.peer = peer; this.environmentId = id; this.lease = lease; this.authorized = authorized;
        }
        @Override void receive(ChannelHandlerContext ctx, JSONObject frame) {
            if (!authorized.getAsBoolean()) { ctx.close(); return; }
            String type = frame.getString("type");
            if ("EVENT".equals(type)) {
                RealtimeReceiver receiver = realtimeReceiver;
                if (receiver == null || !peer.receive(frame, event -> receiver.receive(environmentId, lease, event))) { ctx.close(); return; }
                JSONObject ack = message("EVENT_ACK"); ack.put("epoch", frame.getString("epoch")); ack.put("sequence", frame.getLongValue("sequence"));
                send(ctx.channel(), ack);
            } else if ("EVENT_ACK".equals(type)) peer.acknowledge(frame);
            else if ("PING".equals(type)) send(ctx.channel(), message("PONG"));
            else if (!"PONG".equals(type)) ctx.close();
        }
        @Override public void channelWritabilityChanged(ChannelHandlerContext ctx) { peer.writable(); ctx.fireChannelWritabilityChanged(); }
        @Override public void channelInactive(ChannelHandlerContext ctx) { peer.detach(ctx.channel()); }
    }
    private static final class ClientData extends Messages {
        final ClientControl control;
        final String stream;
        ClientData(ClientControl control, String stream) { this.control = control; this.stream = stream; }
        @Override public void handlerAdded(ChannelHandlerContext ctx) {
            ctx.executor().schedule(() -> { if (ctx.pipeline().context(this) != null) ctx.close(); }, 10, TimeUnit.SECONDS);
        }
        @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
            if (event instanceof SslHandshakeCompletionEvent) {
                if (!((SslHandshakeCompletionEvent) event).isSuccess()) { ctx.close(); return; }
                JSONObject m = control.hello("DATA"); m.put("stream", stream); send(ctx.channel(), m);
            } else super.userEventTriggered(ctx, event);
        }
        @Override void receive(ChannelHandlerContext ctx, JSONObject m) {
            if (!"ACK".equals(m.getString("type"))) { ctx.close(); return; }
            ctx.channel().config().setAutoRead(false);
            PendingRelay waiting = new PendingRelay();
            ctx.pipeline().replace(this, "waiting", waiting);
            ctx.pipeline().remove("idle"); ctx.pipeline().remove("frames");
            connect(control.group, control.channels, "127.0.0.1", control.localPort,
                    ch -> { control.streams.add(ch); ch.config().setAutoRead(false); })
                    .addListener((ChannelFutureListener) f -> {
                        if (!f.isSuccess() || !ctx.channel().isActive()) { f.channel().close(); ctx.close(); return; }
                        raw(ctx.channel(), f.channel(), "waiting"); raw(f.channel(), ctx.channel(), null);
                        waiting.forward(f.channel());
                        f.channel().config().setAutoRead(true); ctx.channel().config().setAutoRead(true);
                    });
        }
    }
    private static final class PendingRelay extends ChannelInboundHandlerAdapter {
        final List<ByteBuf> buffered = new ArrayList<>();
        int bytes;
        @Override public void channelRead(ChannelHandlerContext ctx, Object data) {
            ByteBuf buf = (ByteBuf) data; bytes += buf.readableBytes();
            if (bytes > 65536) { buf.release(); release(); ctx.close(); }
            else buffered.add(buf);
        }
        void forward(Channel peer) { for (ByteBuf buf : buffered) peer.write(buf); buffered.clear(); peer.flush(); }
        void release() { for (ByteBuf buf : buffered) buf.release(); buffered.clear(); }
        @Override public void channelInactive(ChannelHandlerContext ctx) { release(); }
        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) { release(); ctx.close(); }
    }
    private static void raw(Channel ch, Channel peer, String handler) {
        // 先装转发器再移除帧解码器，确保解码器中已到达的后续字节也能转发。
        if (handler != null) ch.pipeline().replace(handler, "relay", new Relay(peer));
        else ch.pipeline().addLast("relay", new Relay(peer));
        if (ch.pipeline().get("idle") != null) ch.pipeline().remove("idle");
        if (ch.pipeline().get("frames") != null) ch.pipeline().remove("frames");
    }
    private static final class Relay extends ChannelInboundHandlerAdapter {
        final Channel peer;
        int writes;
        Relay(Channel peer) { this.peer = peer; }
        @Override public void channelRead(ChannelHandlerContext ctx, Object data) {
            if (!peer.isActive()) { ReferenceCountUtil.release(data); ctx.close(); return; }
            // 每批读取仅在对端写完后继续，慢速下载不会产生无界写队列。
            ctx.channel().config().setAutoRead(false);
            writes++;
            peer.writeAndFlush(data).addListener((ChannelFutureListener) f -> {
                writes--;
                if (!f.isSuccess()) { peer.close(); ctx.close(); }
                else if (writes == 0 && ctx.channel().isActive()) ctx.channel().config().setAutoRead(true);
            });
        }
        @Override public void channelInactive(ChannelHandlerContext ctx) { peer.close(); }
        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) { peer.close(); ctx.close(); }
    }
}
