package com.mola.cmd.proxy.client.provider

import com.mola.cmd.proxy.client.CmdProxyInvokeService
import com.mola.cmd.proxy.client.CmdProxyCallbackService
import com.mola.cmd.proxy.client.resp.CmdResponseContent
import com.mola.rpc.common.entity.RpcMetaData
import com.mola.rpc.common.interceptor.ReverseProxyRegisterInterceptor
import com.mola.rpc.core.proto.ProtoRpcConfigFactory
import com.mola.rpc.core.proxy.InvokeMethod
import com.mola.rpc.core.remoting.handler.NettyDecoder
import com.mola.rpc.core.remoting.handler.NettyEncoder
import com.mola.rpc.core.remoting.handler.NettyRpcRequestHandler
import com.mola.rpc.core.remoting.handler.NettyRpcResponseHandler
import com.mola.rpc.core.remoting.netty.pool.ChannelFutureWrapper
import com.mola.rpc.core.remoting.protocol.RemotingCommand
import com.mola.rpc.core.remoting.protocol.RemotingCommandCode
import com.mola.rpc.core.system.SystemConsumer
import com.mola.rpc.core.util.BytesUtil
import com.mola.rpc.core.util.RemotingHelper
import io.netty.bootstrap.Bootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.util.concurrent.DefaultEventExecutorGroup
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The legacy MolaChat integration is optional. Business RPCs retain their normal error handling. */
class OptionalReverseRegistration @JvmOverloads constructor(
    private val factory: ProtoRpcConfigFactory,
    private val address: String,
    private val retryMillis: Long = 45_000
) : ReverseProxyRegisterInterceptor(), AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val closed = AtomicBoolean()
    private val kickPending = AtomicBoolean()
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "optional-molachat-registration").apply { isDaemon = true }
    }
    private val io = NioEventLoopGroup(1, java.util.concurrent.ThreadFactory { task ->
        Thread(task, "optional-molachat-io").apply { isDaemon = true }
    })
    private val callbacks = DefaultEventExecutorGroup(2, java.util.concurrent.ThreadFactory { task ->
        Thread(task, "optional-molachat-callback").apply { isDaemon = true }
    })
    @Volatile private var channel: Channel? = null
    private class PendingCallback(val channel: Channel, val reply: CompletableFuture<RemotingCommand>)
    private val pendingCallbacks = ConcurrentHashMap<Int, PendingCallback>()
    private val callbackSlots = Semaphore(16)
    private var nextAttemptNanos = 0L
    private val bootstrap = Bootstrap().group(io).channel(NioSocketChannel::class.java)
        .option(ChannelOption.TCP_NODELAY, true)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
        .handler(object : ChannelInitializer<SocketChannel>() {
            override fun initChannel(ch: SocketChannel) {
                val response = NettyRpcResponseHandler().apply { setNettyRemoteClient(factory.nettyRemoteClient) }
                ch.pipeline().addLast(callbacks, NettyEncoder(), NettyDecoder(),
                    object : ChannelDuplexHandler() {
                        override fun channelInactive(ctx: ChannelHandlerContext) {
                            removeFromPool(ctx.channel())
                            failCallbacks(ctx.channel())
                            log.debug("optional MolaChat connection closed: {}", address)
                            ctx.fireChannelInactive()
                        }
                        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                            log.debug("optional MolaChat connection unavailable: {}", address, cause)
                            ctx.close()
                        }
                        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                            if (msg is RemotingCommand && msg.isResponseType) {
                                val pending = pendingCallbacks[msg.opaque]
                                if (pending != null && pending.channel === ctx.channel()
                                    && pendingCallbacks.remove(msg.opaque, pending)) {
                                    if (msg.crc32Check()) pending.reply.complete(msg)
                                    else pending.reply.completeExceptionally(IllegalStateException("invalid callback response"))
                                    return
                                }
                            }
                            ctx.fireChannelRead(msg)
                        }
                    },
                    NettyRpcRequestHandler(factory.rpcContext) { meta -> meta.providerObject }, response)
            }
        })

    init {
        require(retryMillis > 0)
        worker.schedule({ periodicRefresh() }, retryMillis, TimeUnit.MILLISECONDS)
    }

    private fun periodicRefresh() {
        refresh()
        if (closed.get()) return
        val interval = TimeUnit.MILLISECONDS.toNanos(retryMillis)
        val delay = if (channel?.isActive == true || nextAttemptNanos == 0L) interval
            else (nextAttemptNanos - System.nanoTime()).coerceIn(1_000_000L, interval)
        worker.schedule({ periodicRefresh() }, delay, TimeUnit.NANOSECONDS)
    }

    override fun intercept(meta: RpcMetaData): Boolean {
        if (meta.interfaceClazz != CmdProxyInvokeService::class.java
            || meta.reverseModeConsumerAddress != listOf(address)) return false
        if (!closed.get() && kickPending.compareAndSet(false, true)) {
            try {
                worker.execute {
                    kickPending.set(false)
                    refresh()
                }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                kickPending.set(false)
            }
        }
        return true
    }

    // Preserve earlier availability/policy interceptors before replacing the transport.
    override fun priority(): Int = Int.MAX_VALUE

    /** Best-effort discovery callback; never starts the ordinary RPC client's retry path. */
    @JvmOverloads
    fun tryCallback(cmdName: String, group: String, content: CmdResponseContent, timeoutMillis: Long = 3000): Boolean {
        require(timeoutMillis > 0)
        val connected = channel ?: return false
        if (closed.get() || !connected.isActive || !connected.isWritable || !callbackSlots.tryAcquire()) return false
        var request: RemotingCommand? = null
        try {
            val method = InvokeMethod("callback", arrayOf(String::class.java.name, CmdResponseContent::class.java.name),
                arrayOf<Any>(cmdName, content), "void", CmdProxyCallbackService::class.java.name)
            method.group = group
            method.version = RpcMetaData().version
            request = RemotingCommand().apply { body = BytesUtil.objectToBytes(method.toString()) }
            val pending = PendingCallback(connected, CompletableFuture())
            pendingCallbacks[request.opaque] = pending
            connected.writeAndFlush(request).addListener { future ->
                if (!future.isSuccess) pending.reply.completeExceptionally(
                    future.cause() ?: IllegalStateException("callback write failed"))
            }
            return pending.reply.get(timeoutMillis, TimeUnit.MILLISECONDS).code == RemotingCommandCode.SUCCESS
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } catch (e: Exception) {
            return false
        } finally {
            request?.let { pendingCallbacks.remove(it.opaque) }
            callbackSlots.release()
        }
    }

    private fun failCallbacks(disconnected: Channel) {
        pendingCallbacks.forEach { id, pending ->
            if (pending.channel === disconnected && pendingCallbacks.remove(id, pending)) {
                pending.reply.completeExceptionally(IllegalStateException("MolaChat disconnected"))
            }
        }
    }

    private fun refresh() {
        if (closed.get()) return
        val providers = factory.rpcContext.providerMetaMap.values.filter {
            it.interfaceClazz == CmdProxyInvokeService::class.java && it.reverseMode == true
                && it.reverseModeConsumerAddress == listOf(address)
        }
        if (providers.isEmpty()) return
        try {
            var connected = channel
            if (connected?.isActive != true) {
                val now = System.nanoTime()
                if (nextAttemptNanos != 0L && now - nextAttemptNanos < 0) return
                nextAttemptNanos = now + TimeUnit.MILLISECONDS.toNanos(retryMillis)
                val future = bootstrap.connect(RemotingHelper.string2SocketAddress(address))
                channel = future.channel()
                if (!future.awaitUninterruptibly(3000) || !future.isSuccess) {
                    future.channel().close()
                    log.debug("optional MolaChat endpoint offline: {}", address)
                    return
                }
                connected = future.channel()
                if (closed.get()) {
                    connected.close()
                    return
                }
                factory.nettyConnectPool.addChannelWrapper(address, ChannelFutureWrapper.of(future))
                log.info("MolaChat optional registration connection established: {}", address)
            }
            for (meta in providers) {
                if (closed.get()) return
                if (factory.extensionRegistryManager.getInterceptors(ReverseProxyRegisterInterceptor::class.java)
                        .any { it !== this && it.priority() < priority() && it.intercept(meta) }) continue
                val method = InvokeMethod("register", arrayOf(RpcMetaData::class.java.name),
                    arrayOf<Any>(meta), "void", SystemConsumer.ReverseInvokerCaller::class.java.name)
                val defaults = RpcMetaData()
                method.group = defaults.group
                method.version = defaults.version
                val request = RemotingCommand().apply {
                    body = BytesUtil.objectToBytes(method.toString())
                    markOnewayInvoke()
                }
                val sent = connected!!.writeAndFlush(request)
                if (!sent.awaitUninterruptibly(3000) || !sent.isSuccess) {
                    nextAttemptNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(retryMillis)
                    connected.close()
                    log.debug("optional MolaChat registration write failed: {}", address)
                    return
                }
            }
        } catch (e: Exception) {
            nextAttemptNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(retryMillis)
            channel?.close()
            log.debug("optional MolaChat registration deferred: {}", address, e)
        }
    }

    private fun removeFromPool(closedChannel: Channel) {
        val pool = factory.nettyConnectPool
        if (pool.getChannelFutureWrapper(address)?.channel === closedChannel) {
            try {
                pool.removeChannel(address, closedChannel)
            } catch (e: Exception) {
                log.debug("optional MolaChat channel already removed", e)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.shutdownNow()
        channel?.let { failCallbacks(it); removeFromPool(it); it.close() }
        callbacks.shutdownGracefully()
        io.shutdownGracefully()
    }

    companion object {
        @Volatile private var installed: OptionalReverseRegistration? = null

        @JvmStatic
        fun tryInstalledCallback(cmdName: String, group: String, content: CmdResponseContent): Boolean =
            installed?.tryCallback(cmdName, group, content) ?: false

        @JvmStatic @Synchronized
        fun install(factory: ProtoRpcConfigFactory, address: String) {
            if (installed != null) return
            val registration = OptionalReverseRegistration(factory, address)
            factory.extensionRegistryManager.addInterceptor(registration)
            installed = registration
            Runtime.getRuntime().addShutdownHook(Thread({ registration.close() }, "optional-molachat-shutdown"))
        }
    }
}
