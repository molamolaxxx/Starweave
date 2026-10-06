package com.mola.cmd.proxy.client.provider;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.mola.cmd.proxy.client.CmdProxyInvokeService;
import com.mola.cmd.proxy.client.param.CmdInvokeParam;
import com.mola.cmd.proxy.client.resp.CmdInvokeResponse;
import com.mola.rpc.common.entity.RpcMetaData;
import com.mola.rpc.common.interceptor.ReverseProxyRegisterInterceptor;
import com.mola.rpc.core.properties.RpcProperties;
import com.mola.rpc.core.proto.ProtoRpcConfigFactory;
import com.mola.rpc.core.proxy.InvokeMethod;
import com.mola.rpc.core.remoting.handler.NettyDecoder;
import com.mola.rpc.core.remoting.handler.NettyEncoder;
import com.mola.rpc.core.remoting.protocol.RemotingCommand;
import com.mola.rpc.core.util.BytesUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.*;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class OptionalReverseRegistrationTest {
    private static ProtoRpcConfigFactory factory;
    private OptionalReverseRegistration registration;
    private RpcMetaData meta;
    private final Queue<ILoggingEvent> warnings = new ConcurrentLinkedQueue<>();
    private AppenderBase<ILoggingEvent> appender;

    @BeforeClass public static void initializeIsolatedRpc() {
        // Explicit in-memory RPC configuration; never reads the developer's configuration.
        RpcProperties properties = new RpcProperties();
        properties.setStartConfigServer(false);
        properties.setServerPort(0);
        factory = ProtoRpcConfigFactory.fetch();
        factory.init(properties);
    }

    @AfterClass public static void shutdownRpc() { factory.shutdown(); }

    @Before public void captureWarnings() {
        appender = new AppenderBase<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                if (event.getLevel().isGreaterOrEqual(Level.WARN)) warnings.add(event);
            }
        };
        appender.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(appender);
    }

    @After public void cleanup() {
        if (registration != null) { registration.close(); registration.close(); }
        if (meta != null) factory.getRpcContext().getProviderMetaMap().remove(meta.fetchReverseServiceKey());
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(appender);
        appender.stop();
    }

    @Test public void offlineRegistrationIsQuietAndThrottledAcrossRepeatedRefreshes() throws Exception {
        String address = "127.0.0.1:" + freePort();
        registration = new OptionalReverseRegistration(factory, address, 45_000);
        meta = provider(address);
        assertTrue(registration.intercept(meta));
        CountDownLatch drained = drainWorker();
        assertTrue(drained.await(5, TimeUnit.SECONDS));
        long retryAt = field("nextAttemptNanos").getLong(registration);
        assertTrue(retryAt != 0);
        for (int i = 0; i < 100; i++) assertTrue(registration.intercept(meta));
        assertTrue(drainWorker().await(5, TimeUnit.SECONDS));
        assertEquals("refreshes must not cause another connect attempt", retryAt,
                field("nextAttemptNanos").getLong(registration));
        assertTrue("offline integration must not emit warnings: " + warnings, warnings.isEmpty());
    }

    @Test public void reconnectAutomaticallyRegistersAndSupportsReverseInvocations() throws Exception {
        int port = freePort();
        String address = "127.0.0.1:" + port;
        registration = new OptionalReverseRegistration(factory, address, 100);
        meta = provider(address);
        assertTrue(registration.intercept(meta));
        assertTrue(drainWorker().await(5, TimeUnit.SECONDS));
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch registeredAgain = new CountDownLatch(1);
        CountDownLatch response = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Channel> accepted = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger connectionCount = new java.util.concurrent.atomic.AtomicInteger();
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        EventLoopGroup serverIo = new NioEventLoopGroup(1);
        Channel server = null;
        try {
            server = new ServerBootstrap().group(serverIo).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        int connectionNumber = connectionCount.incrementAndGet();
                        accepted.set(ch);
                        ch.pipeline().addLast(new NettyEncoder(), new NettyDecoder(),
                            new SimpleChannelInboundHandler<RemotingCommand>() {
                                private boolean invoked;
                                @Override protected void channelRead0(ChannelHandlerContext ctx, RemotingCommand request) {
                                    try {
                                        if (request.isResponseType()) {
                                            assertTrue(request.crc32Check());
                                            assertEquals(1, request.getCode());
                                            assertTrue(((CmdInvokeResponse<?>) BytesUtil.bytesToObject(
                                                    request.getBody(), CmdInvokeResponse.class)).isSuccess());
                                            response.countDown();
                                            return;
                                        }
                                        assertTrue(request.crc32Check());
                                        assertTrue(request.isOnewayInvoke());
                                        InvokeMethod method = InvokeMethod.newInstance((String) BytesUtil.bytesToObject(request.getBody()));
                                        assertEquals("register", method.getMethodName());
                                        RpcMetaData received = (RpcMetaData) BytesUtil.bytesToObject(method.getSerializedArguments()[0], RpcMetaData.class);
                                        assertEquals(meta.getGroup(), received.getGroup());
                                        registered.countDown();
                                        if (connectionNumber > 1) registeredAgain.countDown();
                                        if (!invoked && connectionNumber == 1) {
                                            invoked = true;
                                            InvokeMethod reverse = new InvokeMethod("invoke",
                                                new String[]{CmdInvokeParam.class.getName()}, new Object[]{new CmdInvokeParam()},
                                                CmdInvokeResponse.class.getName(), CmdProxyInvokeService.class.getName());
                                            reverse.setGroup(meta.getGroup()); reverse.setVersion(meta.getVersion());
                                            RemotingCommand call = new RemotingCommand();
                                            call.setBody(BytesUtil.objectToBytes(reverse.toString()));
                                            ctx.writeAndFlush(call);
                                        }
                                    } catch (Throwable error) { errors.add(error); response.countDown(); }
                                }
                            });
                    }
                }).bind("127.0.0.1", port).sync().channel();
            assertTrue("registration must resume automatically", registered.await(5, TimeUnit.SECONDS));
            assertTrue("MolaChat must still be able to call the provider", response.await(5, TimeUnit.SECONDS));
            assertTrue("protocol errors: " + errors, errors.isEmpty());
            assertNotNull(factory.getNettyConnectPool().getChannel(address));
            accepted.get().close().sync();
            assertTrue("lost connections must automatically register again",
                    registeredAgain.await(5, TimeUnit.SECONDS));
            assertTrue("offline/recovery must be quiet: " + warnings, warnings.isEmpty());
        } finally {
            registration.close();
            if (server != null) server.close().sync();
            serverIo.shutdownGracefully().sync();
        }
    }

    @Test public void unrelatedProvidersAndMultipleAddressesKeepTheirExistingRpcBehavior() throws Exception {
        String address = "127.0.0.1:" + freePort();
        registration = new OptionalReverseRegistration(factory, address);
        RpcMetaData other = new RpcMetaData();
        other.setInterfaceClazz(Runnable.class);
        other.setReverseModeConsumerAddress(java.util.Collections.singletonList(address));
        assertFalse(registration.intercept(other));
        other.setInterfaceClazz(CmdProxyInvokeService.class);
        other.setReverseModeConsumerAddress(java.util.Arrays.asList(address, "127.0.0.1:1"));
        assertFalse(registration.intercept(other));
    }

    @Test public void backgroundRefreshRespectsEarlierRegistrationPolicies() throws Exception {
        String address = "127.0.0.1:" + freePort();
        registration = new OptionalReverseRegistration(factory, address);
        meta = provider(address);
        EmbeddedChannel connected = new EmbeddedChannel();
        field("channel").set(registration, connected);
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        ReverseProxyRegisterInterceptor blocker = new ReverseProxyRegisterInterceptor() {
            @Override public boolean intercept(RpcMetaData provider) { checks.incrementAndGet(); return true; }
        };
        factory.getExtensionRegistryManager().addInterceptor(blocker);
        try {
            assertTrue(registration.intercept(meta));
            assertTrue(drainWorker().await(5, TimeUnit.SECONDS));
            assertTrue(checks.get() > 0);
            assertNull("blocked providers must not be registered", connected.readOutbound());
        } finally {
            Field interceptors = factory.getExtensionRegistryManager().getClass().getDeclaredField("rpcInterceptors");
            interceptors.setAccessible(true);
            ((java.util.List<?>) interceptors.get(factory.getExtensionRegistryManager())).remove(blocker);
            registration.close();
            connected.finishAndReleaseAll();
        }
    }

    private RpcMetaData provider(String address) {
        RpcMetaData provider = new RpcMetaData();
        provider.setInterfaceClazz(CmdProxyInvokeService.class);
        provider.setGroup("optional-registration-test");
        provider.setReverseMode(true);
        provider.setReverseModeConsumerAddress(java.util.Collections.singletonList(address));
        provider.setProviderObject((CmdProxyInvokeService) parameter -> CmdInvokeResponse.Companion.success());
        factory.getRpcContext().getProviderMetaMap().put(provider.fetchReverseServiceKey(), provider);
        return provider;
    }

    private Field field(String name) throws Exception {
        Field field = OptionalReverseRegistration.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private CountDownLatch drainWorker() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        ((java.util.concurrent.ExecutorService) field("worker").get(registration)).execute(latch::countDown);
        return latch;
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }
}
