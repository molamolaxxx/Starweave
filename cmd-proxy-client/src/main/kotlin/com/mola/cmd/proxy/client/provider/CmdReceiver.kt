package com.mola.cmd.proxy.client.provider

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.TypeReference
import com.mola.cmd.proxy.client.CmdProxyCallbackService
import com.mola.cmd.proxy.client.CmdProxyInvokeService
import com.mola.cmd.proxy.client.conf.CmdProxyConf
import com.mola.cmd.proxy.client.param.CmdInvokeParam
import com.mola.cmd.proxy.client.resp.CmdInvokeResponse
import com.mola.cmd.proxy.client.resp.CmdResponseContent
import com.mola.rpc.common.entity.RpcMetaData
import com.mola.rpc.common.utils.JSONUtil
import com.mola.rpc.core.properties.RpcProperties
import com.mola.rpc.core.proto.ProtoRpcConfigFactory
import com.mola.rpc.core.proto.RpcInvoker
import com.mola.rpc.core.system.ReverseInvokeHelper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap


/**
 * @Project: cmd-proxy
 * @Description:
 * @author : molamola
 * @date : 2023-08-06 15:38
 **/
object CmdReceiver {

    private val log: Logger = LoggerFactory.getLogger(CmdReceiver::class.java)

    private val receiverProviderMapByGroup: MutableMap<String, CmdProxyInvokeService> = mutableMapOf()

    private val callbackConsumerMapByGroup: MutableMap<String, CmdProxyCallbackService> = mutableMapOf()

    private val receiverFuncMap:
        MutableMap<String, (param: CmdInvokeParam) -> Map<String, String?>> = ConcurrentHashMap()

    private fun init(serverPort: Int) {
        val protoRpcConfigFactory = ProtoRpcConfigFactory.fetch()
        if (protoRpcConfigFactory.initialized()) {
            return
        }
        val prop = RpcProperties()
        prop.startConfigServer = false
        prop.serverPort = serverPort
        prop.maxClientTimeout =  60 * 1000 * 10
        protoRpcConfigFactory.init(prop)
    }

    private fun start(cmdName: String, cmdGroup: String, description: String) {
        try {
            init(CmdProxyConf.serverPort)
            // 接收器provider
            startReceiverProvider(cmdName, cmdGroup, description)
            // 回调consumer
            startCallbackConsumer(cmdGroup)
        } catch (e: Exception) {
            log.error("ProxyProviderStarter start exception, msg = ${e.message}", e)
        }
    }

    private fun startReceiverProvider(cmdName: String, cmdGroup: String, description: String) {
        synchronized(this) {
            if (receiverProviderMapByGroup.containsKey(cmdGroup)) {
                val providerImpl = receiverProviderMapByGroup[cmdGroup] as CmdProxyInvokeServiceImpl
                val metaData = providerImpl.fetchMetaData()
                metaData.routeTags.add(cmdName)
                if (description.isNotBlank()) {
                    metaData.description = appendCmdDescription(cmdName, metaData.description, description)
                }
                ReverseInvokeHelper.instance().registerProviderToServer(providerImpl.fetchMetaData())
                return
            }
        }
        val rpcMetaData = RpcMetaData()
        rpcMetaData.reverseMode = true
        rpcMetaData.reverseModeConsumerAddress = arrayListOf(CmdProxyConf.Receiver.listenedSenderAddress)
        rpcMetaData.group = cmdGroup
        rpcMetaData.routeTags = mutableSetOf(cmdName)
        if (description.isNotBlank()) {
            rpcMetaData.description = appendCmdDescription(cmdName, rpcMetaData.description, description)
        }
        val provider = CmdProxyInvokeServiceImpl(cmdGroup, rpcMetaData)
        RpcInvoker.provider(
                CmdProxyInvokeService::class.java,
                provider,
                rpcMetaData
        )
        receiverProviderMapByGroup[cmdGroup] = provider
    }

    private fun appendCmdDescription(cmdName: String, original: String?, toAppend: String) : String? {
        if (original == null) {
            return JSONUtil.toJSONString(mutableMapOf(cmdName to toAppend))
        }
        val descriptionMap : MutableMap<String, String> =
            JSONUtil.parseObject(original, object : TypeReference<MutableMap<String, String>>() {})
        descriptionMap[cmdName] = toAppend
        return JSONUtil.toJSONString(descriptionMap)
    }

    private fun startCallbackConsumer(group: String) {
        if (callbackConsumerMapByGroup.containsKey(group)) {
            return
        }
        val rpcMetaData = RpcMetaData()
        rpcMetaData.appointedAddress = arrayListOf(CmdProxyConf.Receiver.listenedSenderAddress)
        rpcMetaData.group = group
        rpcMetaData.clientTimeout = 60 * 1000
        callbackConsumerMapByGroup[group] = RpcInvoker.consumer(
                CmdProxyCallbackService::class.java,
                rpcMetaData,
                "cmdProxyCallbackService#$group"
        )
    }

    fun register(cmdName: String, cmdGroup: String, description: String,
                 receiver: (param: CmdInvokeParam) -> Map<String, String?>) {
        start(cmdName, cmdGroup, description)
        val key = "$cmdName$cmdGroup"
        if (receiverFuncMap.put(key, receiver) != null) {
            log.info("registerCallback replaced, key = {}", key)
        }
    }

    fun register(cmdName: String, cmdGroupList: List<String>, description: String,
                 receiver: (param: CmdInvokeParam) -> Map<String, String?>) {
        for (cmdGroup in cmdGroupList) {
            start(cmdName, cmdGroup, description)
            val key = "$cmdName$cmdGroup"
            if (receiverFuncMap.put(key, receiver) != null) {
                log.info("registerCallback replaced, key = {}", key)
            }
        }
    }

    fun register(cmdName: String, cmdGroup: String,
                 receiver: (param: CmdInvokeParam) -> Map<String, String?>) {
        register(cmdName, cmdGroup, "", receiver)
    }

    fun register(cmdName: String, cmdGroupList: List<String>,
                 receiver: (param: CmdInvokeParam) -> Map<String, String?>) {
        register(cmdName, cmdGroupList, "", receiver)
    }

    fun hasCallbackConsumer(cmdGroup: String): Boolean = callbackConsumerMapByGroup.containsKey(cmdGroup)

    fun callback(cmdName: String, cmdGroup: String, response: CmdResponseContent) {
        val cmdProxyCallbackService = callbackConsumerMapByGroup[cmdGroup]
        if (cmdProxyCallbackService == null) {
            log.warn("receiver callback cannot find consumer, group = $cmdGroup")
            return
        }
        cmdProxyCallbackService.callback(cmdName, response)
    }

    class CmdProxyInvokeServiceImpl(
        private var cmdGroup: String,
        private var rpcMetaData: RpcMetaData
    ) : CmdProxyInvokeService {

        override fun invoke(param: CmdInvokeParam): CmdInvokeResponse<CmdResponseContent?> {
            val startedAt = System.nanoTime()
            val funcKey = "${param.cmdName}${cmdGroup}"
            log.info("cmd invoke entered, cmdId={}, command={}, group={}",
                param.cmdId, param.cmdName, cmdGroup)
            if (!receiverFuncMap.containsKey(funcKey)) {
                return CmdInvokeResponse.error("not available cmd ${param.cmdName} " +
                        "in group $cmdGroup")
            }
            if (log.isDebugEnabled) {
                log.debug("execute cmd : {}, param : {}",
                        param.cmdName, JSON.toJSONString(param))
            }
            val resultMap = receiverFuncMap[funcKey]?.invoke(param)!!
            log.info("cmd invoke completed, cmdId={}, command={}, group={}, elapsedMs={}",
                param.cmdId, param.cmdName, cmdGroup,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
            return CmdInvokeResponse.success(CmdResponseContent(param.cmdId, resultMap))
        }

        open fun fetchMetaData(): RpcMetaData{
            return rpcMetaData
        }
    }
}
