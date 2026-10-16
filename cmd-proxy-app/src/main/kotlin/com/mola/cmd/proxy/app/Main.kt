package com.mola.cmd.proxy.app

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.alibaba.fastjson.serializer.SerializerFeature
import com.mola.cmd.proxy.app.acp.AcpProxy
import com.mola.cmd.proxy.app.acp.AcpRobotParam
import com.mola.cmd.proxy.app.acp.action.CmdProxyControlServer
import com.mola.cmd.proxy.app.acp.acpclient.agent.NpmProviderRuntimeManager
import com.mola.cmd.proxy.app.acp.common.InstanceRegistry
import com.mola.cmd.proxy.app.acp.configui.ConfigUiServer
import com.mola.cmd.proxy.app.acp.channel.ChannelConfigFileStore
import com.mola.cmd.proxy.app.acp.starweave.StarweaveIdentity
import com.mola.cmd.proxy.app.acp.starweave.AcpRuntimePlan
import com.mola.cmd.proxy.app.acp.gateway.model.AgentGatewayConfig
import com.mola.cmd.proxy.app.acp.gateway.model.AgentGatewayServerConfig
import com.mola.cmd.proxy.app.acp.team.TeamSourceEligibility
import com.mola.cmd.proxy.app.mcp.McpProxy
import com.mola.cmd.proxy.app.utils.CmdProxyHome
import com.mola.cmd.proxy.app.utils.LogUtil
import com.mola.cmd.proxy.app.utils.McpFileUtils
import com.mola.cmd.proxy.app.utils.PortAllocator
import com.mola.cmd.proxy.client.conf.CmdProxyConf
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess


/**
 * @Project: cmd-proxy
 * @Description:
 * @author : molamola
 * @date : 2023-08-06 23:19
 **/

private val log: Logger = LoggerFactory.getLogger(McpProxy::class.java)

/** 热重载防重入标志 */
private val reloading = AtomicBoolean(false)

/** 当前实例实际使用的 ConfigUI 端口，热重载时复用，避免重复分配 */
private var activeConfigUiPort = 0

/** 与 ConfigUI 解耦、始终监听回环地址的 MCP/鉴权运行控制面。 */
private var cmdProxyControlServer: CmdProxyControlServer? = null

fun main(args: Array<String>) {
    val mode = args.firstOrNull {
        it.equals("acp", ignoreCase = true) || it.equals("mcp", ignoreCase = true)
    }?.lowercase() ?: "mcp"
    log.info("启动模式: {}", mode)

    LogUtil.debugReject()
    CmdProxyHome.logSummary()
    CmdProxyConf.configureRemoteHost(args)
    CmdProxyConf.Receiver.listenedSenderAddress = CmdProxyConf.REMOTE_ADDRESS
    log.info("远端服务地址: {}", CmdProxyConf.REMOTE_ADDRESS)

    when (mode) {
        "acp" -> startAcp()
        else -> startMcp()
    }
}

private fun startMcp() {
    CmdProxyConf.serverPort = PortAllocator.allocate("RPC", CmdProxyHome.rpcPort())
    val file = File(CmdProxyHome.pathOf("cmdGroupList.txt"))
    if (!file.exists()) {
        McpFileUtils.createFileSmart(file.absolutePath)
    }
    var keys = file.readText(Charset.forName("UTF-8"))
    while (keys.isBlank()) {
        print("请输入group编码（执行fetch命令获取）：")
        keys = readln()
    }
    file.bufferedWriter().use { writer -> writer.write(keys) }
    val keyList = keys.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    log.info("注册的groups: {}", keyList)
    McpProxy.start(keyList)
}

private fun startAcp() {
    // 尽早抢占本环境所有权，使同一环境重复启动快速失败
    try {
        InstanceRegistry.acquireOwnership()
    } catch (e: IllegalStateException) {
        abort("环境重复启动", e)
    }

    val file = File(CmdProxyHome.pathOf("acpConfig.json"))
    if (!file.exists()) {
        McpFileUtils.createFileSmart(file.absolutePath)
    }
    var content = file.readText(Charset.forName("UTF-8"))
    var firstInit = false

    if (content.isBlank()) {
        // 首次初始化：写入空配置骨架，端口在下一步统一分配后回写
        val defaultConfig = JSONObject()
        defaultConfig["robots"] = JSON.parseArray("[]")
        defaultConfig["chatterIds"] = JSON.parseArray("[]")
        defaultConfig["channels"] = JSON.parseArray("[]")
        defaultConfig["externalTaskApis"] = JSON.parseArray("[]")
        defaultConfig["agentGateways"] = JSON.parseArray("[]")
        defaultConfig["agentGatewayServer"] = JSON.parseObject(
            """{"enabled":false,"bindHost":"127.0.0.1","port":10529}""")
        defaultConfig["configUi"] = JSON.parseObject("""{"enabled":true}""")
        content = JSON.toJSONString(defaultConfig, SerializerFeature.PrettyFormat)
        file.bufferedWriter().use { writer -> writer.write(content) }
        firstInit = true
        log.info("首次启动，已生成默认配置文件: {}", file.absolutePath)
    }

    if (ChannelConfigFileStore.ensureArchiveIds()) {
        content = file.readText(Charset.forName("UTF-8"))
    }
    val config: JSONObject = JSON.parseObject(content)

    // 端口自动分配：跳过其它存活实例已登记的端口，再实测 bind
    val reserved = InstanceRegistry.reservedPorts().toMutableSet()
    val rpcPort = PortAllocator.allocate("RPC", CmdProxyHome.rpcPort(), reserved)
    CmdProxyConf.serverPort = rpcPort
    reserved.add(rpcPort)
    val configUiEnabled = config.getJSONObject("configUi")?.getBoolean("enabled") ?: true
    if (configUiEnabled) {
        activeConfigUiPort = PortAllocator.allocate("ConfigUI", desiredConfigUiPort(config), reserved)
        // 端口漂移时回写配置文件，保证配置页展示与实际监听一致
        persistConfigUiPort(file, config, activeConfigUiPort)
    } else {
        // 未开启配置页则不占用端口，登记为 0，其它环境据此判定“不可远程编辑”
        activeConfigUiPort = 0
        log.info("ConfigUI 已禁用，跳过端口分配")
    }

    // MCP 和 MCP 鉴权属于 ACP 运行面，不能依赖可选的 ConfigUI。
    startCmdProxyControlServer()

    // 多环境冲突检查：与主机上其它存活实例比对 chatterIds / robot 名称
    try {
        registerInstance(config)
    } catch (e: IllegalStateException) {
        abort("多环境冲突", e)
    }

    if (firstInit) {
        println("========================================")
        println("  ACP 配置文件已初始化")
        println("  配置文件路径：${file.absolutePath}")
        println("  请通过浏览器访问配置页面完成配置：")
        println("  http://localhost:$activeConfigUiPort")
        println("========================================")
    }

    // 启动 ConfigUI 服务（无论是否有 robots 配置都启动，方便用户配置）
    startConfigUiServer(config)

    // 环境级服务独立于 robot 配置；首次启动也要开放 Team、观测与任务 API。
    startAcpServices(config)
}

private fun startCmdProxyControlServer() {
    if (cmdProxyControlServer != null) return
    try {
        val controlServer = CmdProxyControlServer()
        controlServer.start()
        cmdProxyControlServer = controlServer
        Runtime.getRuntime().addShutdownHook(Thread({
            try {
                controlServer.close()
            } catch (e: Exception) {
                log.warn("关闭 cmd-proxy 控制服务失败", e)
            }
        }, "cmd-proxy-control-shutdown"))
    } catch (e: Exception) {
        abort("cmd-proxy MCP 控制服务启动失败", e)
    }
}

private fun abort(reason: String, e: Exception): Nothing {
    log.error("{}，启动终止", reason, e)
    println("========================================")
    println(e.message)
    println("========================================")
    exitProcess(1)
}

/** ConfigUI 期望端口：配置文件优先，缺失或非法时用 CmdProxyHome 的默认值作为分配基准 */
private fun desiredConfigUiPort(config: JSONObject): Int {
    val port = config.getJSONObject("configUi")?.getIntValue("port") ?: 0
    return if (port <= 0) CmdProxyHome.configUiPort() else port
}

/** 实际端口与配置文件不一致时回写，使页面上的端口值与真实监听一致 */
private fun persistConfigUiPort(file: File, config: JSONObject, actualPort: Int) {
    val configUi = config.getJSONObject("configUi") ?: JSONObject().also { config["configUi"] = it }
    if (configUi.getIntValue("port") == actualPort) {
        return
    }
    configUi["port"] = actualPort
    try {
        file.bufferedWriter().use { writer ->
            writer.write(JSON.toJSONString(config, SerializerFeature.PrettyFormat))
        }
        log.info("ConfigUI 端口已回写配置文件: port={}, file={}", actualPort, file.absolutePath)
    } catch (e: Exception) {
        log.warn("ConfigUI 端口回写失败, port={}", actualPort, e)
    }
}

/**
 * 向主机级实例注册表登记当前环境，并校验与其它存活环境的冲突。
 * 冲突时抛出 IllegalStateException，由调用方决定是终止启动还是拒绝本次重载。
 */
private fun registerInstance(config: JSONObject) {
    val chatterIds = config.getJSONArray("chatterIds")
        ?.toJavaList(String::class.java) ?: emptyList()
    StarweaveIdentity.validateMolaChatChatterIds(chatterIds)
    val robotNames = config.getJSONArray("robots")
        ?.toJavaList(AcpRobotParam::class.java)
        ?.filter { it.isEnabled }
        ?.map { it.name } ?: emptyList()
    InstanceRegistry.checkAndRegister(
        chatterIds, robotNames, CmdProxyConf.serverPort, activeConfigUiPort)
}

/**
 * 根据配置启动 ACP 核心服务（AcpProxy）。
 */
private fun startAcpServices(config: JSONObject) {
    config.getJSONArray("robots")?.forEach { value ->
        val robot = value as? JSONObject
        if (robot?.getString("agentProvider") == "REMOTE_STARCORE_ACP") {
            robot["enabled"] = com.mola.cmd.proxy.app.acp.remote.RemoteAcpManager.online(robot.getString("name"))
        }
    }
    val robotsArray = config.getJSONArray("robots") ?: JSONArray()
    val allRobots = robotsArray.toJavaList(AcpRobotParam::class.java)
    NpmProviderRuntimeManager.getInstance().configureAutoLatest(allRobots)
    val chatterIdsArray = config.getJSONArray("chatterIds") ?: JSONArray()

    val chatterIdsJsonStr = chatterIdsArray.toJSONString()
    val enabledRobots = allRobots.filter { it.isEnabled }
    require(enabledRobots.none { it.isOnlySubAgent && it.isOnlyTeamMember }) {
        "onlySubAgent and onlyTeamMember cannot both be true"
    }
    // 普通运行集合保留 onlySubAgent 的独立 Ability 初始化，但排除 Team-only robot。
    val runtimeRobots = enabledRobots.filter { !it.isOnlyTeamMember }
    val mainRobots = runtimeRobots.filter { !it.isOnlySubAgent }
    // 所有启用且非 onlySubAgent 的 robot 都可作为 Fast Team 来源。
    val teamSourceRobots = enabledRobots.filter { TeamSourceEligibility.isEligible(it) }
    val robotsJsonStr = JSON.toJSONString(mainRobots)
    val chatterIds = chatterIdsArray.toJavaList(String::class.java)
    val channels = config.getJSONArray("channels")
        ?.toJavaList(com.mola.cmd.proxy.app.acp.channel.model.ChannelConfig::class.java)
        ?: emptyList()
    val agentGateways = config.getJSONArray("agentGateways")
        ?.toJavaList(AgentGatewayConfig::class.java) ?: emptyList()
    val agentGatewayServerConfig = config.getJSONObject("agentGatewayServer")
        ?.toJavaObject(AgentGatewayServerConfig::class.java) ?: AgentGatewayServerConfig()

    val runtimePlan = AcpRuntimePlan.build(chatterIds, allRobots, channels)
    log.info("ACP 运行计划: molaChatTargets={}, starweaveEligibleRobots={}, enabledChannels={}",
        runtimePlan.molaChatTargetCount,
        runtimePlan.starweaveEligibleRobots,
        runtimePlan.enabledChannelCount)

    // 笛卡尔积生成 groupId 列表: chatterId 和 acpId 字典序排序后拼接
    val groupIdList = chatterIds.flatMap { chatterId ->
        runtimeRobots.map { robot ->
            val acpId = "acp-" + robot.name.replace(" ", "_")
                .replace("\u3000", "_")
            listOf(chatterId, acpId).sorted().joinToString("")
        }
    }

    // 构建 groupId -> workDir 映射
    val groupWorkDirMap = chatterIds.flatMap { chatterId ->
        runtimeRobots.filter { it.workDir.isNotBlank() }.map { robot ->
            val acpId = "acp-" + robot.name.replace(" ", "_")
                .replace("\u3000", "_")
            val groupId = listOf(chatterId, acpId).sorted().joinToString("")
            groupId to robot.workDir
        }
    }.toMap()

    log.info("ACP 注册的groups: {}", groupIdList)

    // 构建 groupId -> AcpRobotParam 映射
    val groupRobotMap = chatterIds.flatMap { chatterId ->
        runtimeRobots.map { robot ->
            val acpId = "acp-" + robot.name.replace(" ", "_")
                .replace("\u3000", "_")
            val groupId = listOf(chatterId, acpId).sorted().joinToString("")
            groupId to robot
        }
    }.toMap()

    // Team 来源注册表与普通 runtime 分离；onlyTeamMember 不创建 MAIN client。
    val teamSourceGroupRobotMap = chatterIds.flatMap { chatterId ->
        teamSourceRobots.map { robot ->
            val acpId = "acp-" + robot.name.replace(" ", "_")
                .replace("\u3000", "_")
            val groupId = listOf(chatterId, acpId).sorted().joinToString("")
            groupId to robot
        }
    }.toMap()

    AcpProxy.start(
        groupIdList, robotsJsonStr, chatterIdsJsonStr,
        groupWorkDirMap, groupRobotMap, teamSourceGroupRobotMap,
        allRobots, channels, agentGatewayServerConfig, agentGateways)
}

private fun startConfigUiServer(config: JSONObject) {
    val configUi = config.getJSONObject("configUi")
    val enabled = configUi?.getBoolean("enabled") ?: true
    if (!enabled) {
        log.info("ConfigUI 已禁用，跳过启动")
        return
    }
    val actualPort = if (activeConfigUiPort > 0) activeConfigUiPort
        else PortAllocator.allocate("ConfigUI", desiredConfigUiPort(config))
    try {
        val server = ConfigUiServer(
            actualPort,
            { reloadAcpServices() },
            { previousName, name -> reloadRobot(previousName, name) },
            { AcpProxy.channelStatuses().mapValues { it.value.name } },
            { AcpProxy.channelErrors() },
            { channelId, inboundAllowed ->
                AcpProxy.setChannelInboundEnabled(channelId, inboundAllowed)
            },
            { channelId, privateChatAllowed ->
                AcpProxy.setChannelPrivateChatEnabled(channelId, privateChatAllowed)
            },
            { AcpProxy.channelTeamBindingTargets() },
            { previousChannelId, channelId ->
                reloadChannel(previousChannelId, channelId)
            }
        )
        server.setMemoryDreamHandler(AcpProxy::memoryDreamStatus, AcpProxy::triggerMemoryDream)
        server.start()
    } catch (e: Exception) {
        log.error("ConfigUI 启动失败, port={}", actualPort, e)
    }
}

/**
 * ACP 服务热重载：重新读取配置文件，停止旧服务，启动新服务。
 * 使用 AtomicBoolean 防止重复点击导致并发重载。
 * 异步执行，不阻塞 HTTP 线程。
 */
private fun reloadAcpServices() {
    if (!reloading.compareAndSet(false, true)) {
        log.warn("ACP 服务正在重载中，忽略重复请求")
        throw IllegalStateException("服务正在重载中，请稍后再试")
    }
    try {
        log.info("开始 ACP 服务热重载...")

        // 1. 停止现有 ACP 服务
        AcpProxy.stop()
        log.info("旧 ACP 服务已停止")

        // 2. 重新读取配置文件
        val file = File(CmdProxyHome.pathOf("acpConfig.json"))
        val content = file.readText(Charset.forName("UTF-8"))
        if (content.isBlank()) {
            log.warn("配置文件为空，跳过重载")
            return
        }
        val config: JSONObject = JSON.parseObject(content)

        // 3. 多环境冲突检查：配置改动可能引入与其它环境相同的 chatterIds 或重名 robot
        registerInstance(config)

        // 4. 启动新服务（内部并行启动，阻塞直到所有 client 就绪）
        startAcpServices(config)
        log.info("ACP 服务热重载完成")
    } catch (e: Exception) {
        log.error("ACP 服务热重载失败", e)
        throw e
    } finally {
        reloading.set(false)
    }
}

/**
 * 按 robot 维度热重载：重新读取配置文件，只重建指定 robot 的 ACP 进程。
 */
private fun reloadRobot(previousRobotName: String, robotName: String) {
    try {
        val file = File(CmdProxyHome.pathOf("acpConfig.json"))
        val content = file.readText(Charset.forName("UTF-8"))
        if (content.isBlank()) {
            log.warn("配置文件为空，跳过 robot 级重载")
            return
        }
        val config: JSONObject = JSON.parseObject(content)

        val robotsArray = config.getJSONArray("robots") ?: run {
            log.warn("配置中无 robots，跳过 robot 级重载")
            throw IllegalArgumentException("配置中无 robots 字段")
        }
        val chatterIdsArray = config.getJSONArray("chatterIds") ?: com.alibaba.fastjson.JSONArray()

        val allRobots = robotsArray.toJavaList(AcpRobotParam::class.java)
        NpmProviderRuntimeManager.getInstance().configureAutoLatest(allRobots)
        val robot = allRobots.firstOrNull { it.name == robotName }
            ?: if (com.mola.cmd.proxy.app.acp.remote.RemoteAcpManager.wasRegistered(robotName)) {
                // A disconnected record may be deleted before its queued cleanup runs.
                AcpRobotParam().apply {
                    name = robotName
                    agentProvider = "REMOTE_STARCORE_ACP"
                    isEnabled = false
                }
            } else throw IllegalArgumentException("robot '$robotName' 在配置中不存在")

        val chatterIds = chatterIdsArray.toJavaList(String::class.java)

        AcpProxy.reloadRobot(previousRobotName, robot, chatterIds, allRobots)
    } catch (e: Exception) {
        log.error("robot 级热重载失败, robot={}", robotName, e)
        throw e
    }
}

/** Reloads one external channel from the persisted configuration. */
private fun reloadChannel(previousChannelId: String, channelId: String) {
    try {
        val file = File(CmdProxyHome.pathOf("acpConfig.json"))
        val content = file.readText(Charset.forName("UTF-8"))
        if (content.isBlank()) throw IllegalArgumentException("配置文件为空")
        val config: JSONObject = JSON.parseObject(content)
        val channels = config.getJSONArray("channels")
            ?.toJavaList(com.mola.cmd.proxy.app.acp.channel.model.ChannelConfig::class.java)
            ?: emptyList()
        val channel = channels.firstOrNull { it.id?.trim() == channelId }
        if (channel == null && channelId.isNotBlank()) {
            throw IllegalArgumentException("channel '$channelId' 在配置中不存在")
        }
        AcpProxy.reloadChannel(previousChannelId, channel)
    } catch (e: Exception) {
        log.error("channel 级热重载失败, previousChannelId={}, channelId={}",
            previousChannelId, channelId, e)
        throw e
    }
}
