package com.mola.cmd.proxy.app.acp

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.mola.cmd.proxy.app.acp.ability.AbilityReflectionService
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientFeatureInitializer
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry
import com.mola.cmd.proxy.app.acp.acpclient.AcpSleepStateStore
import com.mola.cmd.proxy.app.acp.acpclient.MainSessionApplicationService
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProviderRouter
import com.mola.cmd.proxy.app.acp.common.PathUtils
import com.mola.cmd.proxy.app.acp.memory.MemoryManager
import com.mola.cmd.proxy.app.acp.memory.MemoryManagerRegistry
import com.mola.cmd.proxy.app.acp.memory.PendingMemoryExtractionRecovery
import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig
import com.mola.cmd.proxy.app.acp.subagent.SubAgentContextInjector
import com.mola.cmd.proxy.app.acp.subagent.SubAgentDispatcher
import com.mola.cmd.proxy.app.acp.schedule.ScheduleTaskManager
import com.mola.cmd.proxy.app.acp.schedule.ScheduleContextInjector
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleOwnerKey
import com.mola.cmd.proxy.app.acp.acpclient.PromptOptions
import com.mola.cmd.proxy.app.acp.acpclient.PromptCommandResult
import com.mola.cmd.proxy.app.acp.talkto.TalkToContextInjector
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher
import com.mola.cmd.proxy.app.acp.channel.ChannelManager
import com.mola.cmd.proxy.app.acp.channel.model.ChannelConfig
import com.mola.cmd.proxy.app.acp.channel.model.ChannelStatus
import com.mola.cmd.proxy.app.acp.channel.wecom.WeComChannelAdapter
import com.mola.cmd.proxy.app.acp.gateway.AgentGatewayManager
import com.mola.cmd.proxy.app.acp.gateway.AgentGatewayAdminBridge
import com.mola.cmd.proxy.app.acp.gateway.AgentGatewayServer
import com.mola.cmd.proxy.app.acp.gateway.GatewayEventBridge
import com.mola.cmd.proxy.app.acp.gateway.GatewayRuntime
import com.mola.cmd.proxy.app.acp.gateway.model.AgentGatewayConfig
import com.mola.cmd.proxy.app.acp.gateway.model.AgentGatewayServerConfig
import com.mola.cmd.proxy.app.acp.gateway.model.GatewayTarget
import com.mola.cmd.proxy.app.acp.team.TeamClientRegistry
import com.mola.cmd.proxy.app.acp.team.TeamCommandHandler
import com.mola.cmd.proxy.app.acp.team.TeamManager
import com.mola.cmd.proxy.app.acp.team.TeamStore
import com.mola.cmd.proxy.app.acp.team.MapTeamSourceRobotResolver
import com.mola.cmd.proxy.app.acp.starweave.StarweaveIdentity
import com.mola.cmd.proxy.app.acp.team.TeamStartupCoordinator
import com.mola.cmd.proxy.app.acp.team.TeamSharedSourceIds
import com.mola.cmd.proxy.app.acp.team.TeamSharingStatusRegistry
import com.mola.cmd.proxy.app.acp.team.TeamSourceEligibility
import com.mola.cmd.proxy.app.acp.team.event.RpcTeamEventSink
import com.mola.cmd.proxy.app.acp.team.listener.TeamAcpResponseListener
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberSourceDescriptor
import com.mola.cmd.proxy.app.acp.team.protocol.RemoteTeamMemberSourceDescriptor
import com.mola.cmd.proxy.app.acp.talkto.ContactRemarkResolver
import com.mola.cmd.proxy.app.acp.team.protocol.TeamTransportDescriptor
import com.mola.cmd.proxy.app.acp.team.protocol.TeamTransportProtocol
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberCommand
import com.mola.cmd.proxy.app.acp.team.protocol.TeamCommandResult
import com.mola.cmd.proxy.app.acp.team.talkto.TeamTalkToContextInjector
import com.mola.cmd.proxy.app.acp.starweave.StarweaveSessionManager
import com.mola.cmd.proxy.app.acp.starweave.StarweaveSessionApiBridge
import com.mola.cmd.proxy.app.acp.starweave.StarweaveTeamApiBridge
import com.mola.cmd.proxy.app.acp.starweave.StarweaveTeamGateway
import com.mola.cmd.proxy.app.utils.CmdProxyHome
import com.mola.cmd.proxy.client.provider.CmdReceiver
import com.mola.cmd.proxy.client.resp.CmdResponseContent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object AcpProxy {

    /** ConfigUI 记忆查看弹窗的手动整理入口，使用当前运行的记忆 manager。 */
    private fun memoryDreamOwner(robotName: String): Pair<MemoryManager, String>? {
        val configured = configuredRobotRegistry[robotName] ?: return null
        val owners = registry.getGroupIdsByRobot(robotName).asSequence() +
            globalGroupRobotRegistry.entries.asSequence()
                .filter { it.value.name == robotName }.map { it.key } +
            sequenceOf("subagent:$robotName")
        return owners.distinct().mapNotNull { groupId ->
            val manager = memoryManagers.get(groupId).orElse(null)
            val workspace = registry.getClient(groupId)?.workspacePath ?: configured.workDir
            if (manager == null || workspace.isNullOrBlank()) null
            else Pair(manager, workspace)
        }.firstOrNull()
    }

    @JvmStatic
    fun memoryDreamStatus(robotName: String): Map<String, Boolean> {
        val owner = memoryDreamOwner(robotName)
        return mapOf(
            "available" to (owner?.first?.canDream() ?: false),
            "running" to (owner?.let { it.first.isDreaming(it.second) } ?: false)
        )
    }

    @JvmStatic
    fun triggerMemoryDream(robotName: String): Boolean {
        val owner = memoryDreamOwner(robotName)
            ?: throw IllegalStateException("该智能体没有正在运行的记忆服务")
        return owner.first.triggerDreamIfIdle(owner.second)
    }

    private val log: Logger = LoggerFactory.getLogger(AcpProxy::class.java)

    private val registry: AcpClientRegistry = AcpClientRegistry.getInstance()

    /** Ordinary MAIN lifecycle boundary shared by MolaChat RPC and Starweave REST. */
    private val mainSessionService = MainSessionApplicationService(registry)

    /** MAIN logical clients that were sleeping before the previous process stopped. */
    private val sleepStateStore = AcpSleepStateStore()

    /** Persisted sleepers are restored one at a time to cap transient provider memory. */
    private val sleepingRecoveryPermit = Semaphore(1, true)

    /** 普通、Team 与 sub-agent 共用的 manager/存储锁注册表。 */
    private val memoryManagers = MemoryManagerRegistry()

    /** 防止共享 history namespace 的多个 client 在同一代际重复恢复同一 pending。 */
    private val pendingMemoryRecoveryClaims = ConcurrentHashMap<String, String>()

    /** groupId -> AbilityReflectionService */
    private val abilityServices = ConcurrentHashMap<String, AbilityReflectionService>()

    /** 全局 robot 注册表，name -> AcpRobotParam */
    private val globalRobotRegistry = ConcurrentHashMap<String, AcpRobotParam>()

    /** 包含禁用项的完整配置注册表，供记忆执行 Robot 等配置引用解析。 */
    private val configuredRobotRegistry = ConcurrentHashMap<String, AcpRobotParam>()

    /** 定时任务管理器（全局单例） */
    private val scheduleTaskManager = ScheduleTaskManager()
    private var observationManager = com.mola.cmd.proxy.app.acp.observation.ObservationManager(
        CmdProxyHome.resolve("observations"))

    /** robotName → groupId 反向索引，用于 talkTo 查找目标 client */
    private val robotToGroupIdMap = ConcurrentHashMap<String, String>()

    /** Team 可选 sourceGroupId → robot 配置，只供 Team 创建和恢复。 */
    private val globalGroupRobotRegistry = ConcurrentHashMap<String, AcpRobotParam>()

    /** TalkTo 消息投递器（全局单例） */
    private lateinit var talkToDispatcher: TalkToDispatcher

    /** 当前实例的外部信道生命周期；只绑定普通 MAIN client。 */
    private var channelManager: ChannelManager? = null

    /** Authenticated external Agent transport and shared HTTP/WebSocket listener. */
    private var agentGatewayManager: AgentGatewayManager? = null
    private var agentGatewayServer: AgentGatewayServer? = null

    /** 普通与 Team client 共用的完整能力挂点编排器。 */
    private lateinit var featureInitializer: AcpClientFeatureInitializer

    /** Starweave 环境本地 MAIN 会话；不注册 MolaChat RPC group/callback。 */
    private var starweaveSessionManager: StarweaveSessionManager? = null

    /** Team 权威 runtime；本阶段只恢复定义与负责统一关闭。 */
    private var teamManager: TeamManager? = null

    /** 当前 cmd-proxy 实例的稳定 Team transport 描述。 */
    private lateinit var teamTransportDescriptor: TeamTransportDescriptor

    /** 普通 ACP 与 Team discovery 共用的可重放同步快照。 */
    private lateinit var acpSyncRobotsSnapshot: AcpSyncRobotsSnapshot

    /** MolaChat 重启后用于重新发现每个 cmd-proxy 实例的周期快照重放。 */
    private var acpSyncRobotsHeartbeat: AcpSyncRobotsHeartbeat? = null

    /** 使用对象字段保证热重载后既有 RPC handler 转发到新 TeamManager。 */
    private var teamCommandHandler: TeamCommandHandler? = null

    /** robot 级重载不得悄悄改变全实例 chatter 维度。 */
    private var activeChatterIds: List<String> = emptyList()

    /** 包含禁用项的配置身份集合，用于允许既有 robot 做角色级热重载。 */
    private val configuredRobotNames = ConcurrentHashMap.newKeySet<String>()

    private val shutdownHookRegistered = AtomicBoolean(false)

    private var taskService: com.mola.cmd.proxy.app.acp.task.service.TaskService? = null
    private var taskMcpServer: com.mola.cmd.proxy.app.acp.task.mcp.TaskMcpServer? = null
    private var taskMcpDescriptor: com.google.gson.JsonObject? = null
    private var taskDeliveryAdapter:
        com.mola.cmd.proxy.app.acp.task.dispatch.LocalTaskDeliveryAdapter? = null
    private var taskDispatchService:
        com.mola.cmd.proxy.app.acp.task.dispatch.TaskDispatchService? = null
    private val taskRobotRegistry = ConcurrentHashMap<String, AcpRobotParam>()

    private var autoNewSessionExecutor: ScheduledExecutorService? = null
    private val autoNewSessionLastChecks = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun start(
        cmdGroupList: List<String>,
        robotsJson: String? = null,
        chatterIdsJson: String? = null,
        groupWorkDirMap: Map<String, String> = emptyMap(),
        groupRobotMap: Map<String, AcpRobotParam> = emptyMap(),
        teamSourceGroupRobotMap: Map<String, AcpRobotParam> = emptyMap(),
        configuredRobots: Collection<AcpRobotParam> = emptyList(),
        channels: List<ChannelConfig> = emptyList(),
        agentGatewayServerConfig: AgentGatewayServerConfig = AgentGatewayServerConfig(),
        agentGateways: List<AgentGatewayConfig> = emptyList()
    ) {
        ensureShutdownHook()
        AgentGatewayAdminBridge.install(
            { createAgentGatewayRuntime().targets() },
            { agentGatewayManager?.adminStatus() ?: JSONObject(true).also { it["status"] = "STOPPED" } })
        if (teamManager != null) {
            throw IllegalStateException(
                "AcpProxy is already started; stop it before starting a new generation")
        }
        // Full service refresh creates fresh executors and reloads durable observation state.
        observationManager.close()
        observationManager = com.mola.cmd.proxy.app.acp.observation.ObservationManager(
            CmdProxyHome.resolve("observations"))
        if (taskService == null) {
            try {
                val service = com.mola.cmd.proxy.app.acp.task.service.TaskService(
                    CmdProxyHome.resolve("starweave/tasks"), CmdProxyHome.instanceId()) { target ->
                        if (target.getString("type") == "TEAM") {
                            val teamId = target.getString("teamId")?.trim().orEmpty()
                            if (teamId.isEmpty()) {
                                throw com.mola.cmd.proxy.app.acp.task.model.TaskException(
                                    "INVALID_ARGUMENT", "target.teamId is required", 400)
                            }
                            val manager = teamManager
                                ?: throw com.mola.cmd.proxy.app.acp.task.model.TaskException(
                                    "TASK_TARGETS_NOT_READY",
                                    "Starweave Team targets are not ready",
                                    503
                                )
                            val runtime = manager.getRuntime(teamId).orElse(null)
                                ?: throw com.mola.cmd.proxy.app.acp.task.model.TaskException(
                                    "TASK_TARGET_UNAVAILABLE",
                                    "Starweave Team target is unavailable",
                                    400
                                )
                            if (runtime.definition.isMixedPlacement) {
                                throw com.mola.cmd.proxy.app.acp.task.model.TaskException(
                                    "MIXED_TEAM_NOT_SUPPORTED",
                                    "Starweave tasks cannot be assigned to mixed Teams",
                                    400
                                )
                            }
                            if (runtime.definition.isCaptainMode) {
                                val mode = target.getString("mode")?.trim()?.uppercase()
                                    .orEmpty().ifEmpty { "FIXED" }
                                val memberId = target.getString("teamMemberId")?.trim().orEmpty()
                                if (mode != "FIXED" ||
                                    memberId != runtime.definition.captainTeamMemberId) {
                                    throw com.mola.cmd.proxy.app.acp.task.model.TaskException(
                                        "CAPTAIN_ONLY_TASK_TARGET",
                                        "Captain Team tasks must target the fixed captain",
                                        400
                                    )
                                }
                            }
                        }
                    }
                taskService = service
                com.mola.cmd.proxy.app.acp.task.api.TaskApiBridge.install(service)
                val mcp = com.mola.cmd.proxy.app.acp.task.mcp.TaskMcpServer(
                    java.net.InetSocketAddress("127.0.0.1", 0), service)
                mcp.start()
                taskMcpServer = mcp
                taskMcpDescriptor =
                    com.mola.cmd.proxy.app.acp.task.mcp.TaskMcpServer.acpServerDescriptor(
                        "http://127.0.0.1:${mcp.port}")
                log.info("Starweave 任务 MCP 已启动, port={}", mcp.port)
            } catch (e: Exception) {
                log.error("任务存储初始化失败，任务 API 将返回未就绪", e)
                taskMcpServer?.close()
                taskMcpServer = null
                taskMcpDescriptor = null
            }
        }
        activeChatterIds = if (chatterIdsJson.isNullOrBlank()) {
            emptyList()
        } else {
            JSON.parseArray(chatterIdsJson, String::class.java).toList()
        }
        configuredRobots.filter { it.name.isNotBlank() }.forEach {
            configuredRobotRegistry[it.name] = it
            configuredRobotNames.add(it.name)
        }
        // 注册命令
        CmdReceiver.register("acp", "acp") { param ->
            mutableMapOf<String, String>()
        }

        // 构建全局 robot 注册表（在冷加载之前，供子 Agent 派发使用）
        for ((groupId, robot) in groupRobotMap) {
            if (robot != null && robot.name.isNotBlank()) {
                globalRobotRegistry[robot.name] = robot
            }
        }
        // Starweave-only deployments have no chatterId-derived group map. Keep
        // the same enabled robot catalog available to sub Agent and TalkTo setup.
        configuredRobots.filter {
            it.isEnabled && !it.isOnlyTeamMember && it.name.isNotBlank()
        }.forEach { globalRobotRegistry.putIfAbsent(it.name, it) }
        globalGroupRobotRegistry.putAll(teamSourceGroupRobotMap)
        taskRobotRegistry.putAll(groupRobotMap)
        registerStarweaveTeamSources(configuredRobots)
        registerSharedTeamSources(configuredRobots)

        // 构建 robotName → groupId 反向索引（取第一个）
        for ((groupId, robot) in groupRobotMap) {
            if (robot != null && robot.name.isNotBlank()) {
                robotToGroupIdMap.putIfAbsent(robot.name, groupId)
                robotToGroupIdMap.putIfAbsent("MOLACHAT:${robot.name}", groupId)
                activeChatterIds.firstOrNull { chatterId ->
                    mainGroupId(chatterId, robot.name) == groupId
                }?.let { chatterId ->
                    robotToGroupIdMap["MOLACHAT:$chatterId:${robot.name}"] = groupId
                }
            }
        }

        // 初始化 TalkTo 消息投递器
        talkToDispatcher = TalkToDispatcher(
            globalRobotRegistry, registry, robotToGroupIdMap
        )
        featureInitializer = createFeatureInitializer()
        starweaveSessionManager = StarweaveSessionManager(
            CmdProxyHome.instanceId(), registry,
            { robotName -> configuredRobotRegistry[robotName] },
            { groupId, client, robot ->
                featureInitializer.initialize(
                    AcpClientFeatureInitializer.Context.main(groupId), client, robot)
                // Enables local same-surface routing when no MolaChat MAIN slot
                // has already claimed this robot name. Full multi-surface routing
                // remains tracked separately by SW-604.
                robotToGroupIdMap.putIfAbsent(robot.name, groupId)
                robotToGroupIdMap["STARWEAVE:${robot.name}"] = groupId
                robotToGroupIdMap[
                    "STARWEAVE:${StarweaveIdentity.ownerId(CmdProxyHome.instanceId())}:${robot.name}"
                ] = groupId
                globalGroupRobotRegistry[groupId] = robot
                taskRobotRegistry[groupId] = robot
            }
        )
        talkToDispatcher.setStarweaveSessionStarter { robotName ->
            starweaveSessionManager!!.open(robotName)
        }
        StarweaveSessionApiBridge.install(starweaveSessionManager!!)
        initializeTeamTransport()
        val starweaveRecovery = starweaveSessionManager!!.recoverActiveSessions(
            sleepStateStore::isSleeping)
        log.info(
            "Starweave ACTIVE 会话恢复完成, attempted={}, recovered={}, failed={}",
            starweaveRecovery.getIntValue("attempted"),
            starweaveRecovery.getIntValue("recoveredCount"),
            starweaveRecovery.getIntValue("failedCount")
        )
        if (starweaveRecovery.getIntValue("failedCount") > 0) {
            log.warn("部分 Starweave 会话恢复失败，保留 CLOSED 状态供页面重试: {}",
                starweaveRecovery.getJSONArray("failed"))
        }
        acpSyncRobotsSnapshot = AcpSyncRobotsSnapshot(
            robotsJson, chatterIdsJson, teamTransportDescriptor)

        // 同一个命令既建立 callback consumer，也作为 MolaChat 重连后的主动握手。
        // handler 只在 Team 恢复与 13 个 routeTag 注册完成后才暴露，避免首次 sync
        // 观察到 businessCommandsReady=false 的中间态。
        CmdReceiver.register(
            "acpSyncRobots", "acpSyncRobots",
            "Replay ordinary ACP robots and Fast Team discovery after reconnect"
        ) { param ->
            log.info("acpSyncRobots 主动握手, requestId={}", param.cmdId)
            acpSyncRobotsSnapshot.resultMap()
        }

        // 注册 crossTalkTo callback 通道（建立到 MolaChat 的 RPC 连接）
        CmdReceiver.register("crossTalkTo", "crossTalkTo") { param ->
            // dummy handler：MolaChat 不会 send crossTalkTo 到 cmd-proxy
            // 注册的目的是建立 RPC 连接，使 CmdReceiver.callback() 有可用的 consumer
            mutableMapOf<String, String>("result" to "ok")
        }

        // 注册 cmdGroupList 维度的命令处理器
        registerGroupCommands(cmdGroupList)

        // 冷加载：并行启动所有 groupId 的 client
        val latch = CountDownLatch(cmdGroupList.size)
        val executor = if (cmdGroupList.isEmpty()) null else Executors.newFixedThreadPool(
            minOf(cmdGroupList.size, Runtime.getRuntime().availableProcessors() * 2)
        )

        for (groupId in cmdGroupList) {
            executor?.submit {
                try {
                    val workDir = groupWorkDirMap[groupId]
                    val robot = groupRobotMap[groupId]

                    // onlySubAgent 的 robot 不启动 AcpClient，只做 ability 反思
                    if (robot != null && robot.isOnlySubAgent) {
                        initAbilityReflectionStandalone(groupId, robot)
                        log.info("onlySubAgent robot 跳过 client 创建, groupId={}, robot={}", groupId, robot.name)
                        return@submit
                    }

                    val restoreSleeping = sleepStateStore.isSleeping(groupId)
                    if (restoreSleeping) sleepingRecoveryPermit.acquire()
                    try {
                        val client = mainSessionService.create(groupId, workDir, robot) { created ->
                            featureInitializer.initialize(
                                AcpClientFeatureInitializer.Context.main(groupId), created, robot)
                        }
                        if (restoreSleeping) client.restoreSleepAfterRestart()
                    } finally {
                        if (restoreSleeping) sleepingRecoveryPermit.release()
                    }

                    log.info("ACP client 冷加载完成, groupId={}, robot={}, workDir={}, memory={}, subAgents={}",
                        groupId, robot?.name ?: "unknown", workDir ?: "default",
                        robot?.isMemoryEnabled ?: false,
                        robot?.subAgents?.map { it.name } ?: emptyList<String>())
                } catch (e: Exception) {
                    log.error("ACP client 冷加载失败, groupId={}", groupId, e)
                } finally {
                    latch.countDown()
                }
            }
        }

        // 等待所有 client 启动完成
        latch.await()
        executor?.shutdown()
        log.info("所有 ACP client 冷加载完成, 共 {} 个", cmdGroupList.size)

        startTaskExecutionRuntime()

        // client 和普通 TalkTo dispatcher 均就绪后再开放外部消息入口。
        channelManager = ChannelManager(
            channels,
            CmdProxyHome.instanceId(),
            registry,
            talkToDispatcher,
            teamManager,
            ChannelManager.AdapterFactory { channel, bridge ->
                WeComChannelAdapter(channel, bridge)
            }
        ).also { it.start() }
        log.info("外部信道启动完成, configured={}, statuses={}",
            channels.size, channelManager?.statuses ?: emptyMap<String, ChannelStatus>())

        if (agentGatewayServerConfig.isEnabled && agentGateways.any { it.isEnabled }) {
            try {
                val manager = AgentGatewayManager(
                    agentGatewayServerConfig, agentGateways, createAgentGatewayRuntime())
                GatewayEventBridge.install(manager)
                val server = AgentGatewayServer(manager)
                server.start()
                agentGatewayManager = manager
                agentGatewayServer = server
                log.info("智能体网关已启动, bind={}:{}, configured={}",
                    agentGatewayServerConfig.bindHost, server.port, agentGateways.size)
            } catch (e: Exception) {
                agentGatewayServer?.close()
                agentGatewayServer = null
                agentGatewayManager?.close()
                agentGatewayManager = null
                log.error("智能体网关启动失败", e)
            }
        }

        // 启动定时任务调度器
        startScheduler(groupRobotMap)
        startAutoNewSession()

        // 会话注册完成后发布完整 discovery。Starweave-only 实例虽然没有
        // MolaChat chatterId，仍是 Mixed Team participant，必须持续向协调器续租。
        if (!robotsJson.isNullOrBlank() && !chatterIdsJson.isNullOrBlank()) {
            try {
                if (publishAcpSyncRobots()) log.info("acpSyncRobots 回调已发送")
            } catch (e: Exception) {
                log.info("acpSyncRobots 回调未完成，等待下一轮，原因={}", e.javaClass.simpleName)
            }
        }
        acpSyncRobotsHeartbeat = AcpSyncRobotsHeartbeat(
            acpSyncRobotsSnapshot,
            { snapshot -> publishAcpSyncRobots(snapshot) },
            20L,
            TimeUnit.SECONDS
        ).also { it.start() }
        log.info("acpSyncRobots heartbeat 已启动, intervalSeconds=20, visibleChatterCount={}",
            activeChatterIds.size)

        log.info("AcpProxy 命令注册完成")
    }

    /** 发布与主动握手完全相同的权威快照。 */
    private fun publishAcpSyncRobots(): Boolean = publishAcpSyncRobots(acpSyncRobotsSnapshot.resultMap())

    private fun publishAcpSyncRobots(snapshot: Map<String, String>): Boolean {
        val sent = CmdReceiver.callbackOptional(
            "acpSyncRobots", "acpSyncRobots",
            CmdResponseContent(
                UUID.randomUUID().toString(),
                snapshot
            )
        )
        if (!sent) log.info("acpSyncRobots 心跳未发送：MolaChat 未连接或回调未确认，等待下一轮")
        return sent
    }

    /** 注册实例级稳定 transport，并仅在权威状态恢复成功后开放业务命令。 */
    private fun initializeTeamTransport() {
        teamTransportDescriptor = TeamTransportDescriptor.forInstance(
            CmdProxyHome.instanceId(), coordinatorTeamMemberSourceDescriptors(),
            remoteTeamMemberSourceDescriptors())
        val sourceResolver = MapTeamSourceRobotResolver(globalGroupRobotRegistry)
        val rpcEventSink = RpcTeamEventSink()
        val teamRegistry = TeamClientRegistry()
        val managerHolder = AtomicReference<TeamManager>()
        val eventSink = object : com.mola.cmd.proxy.app.acp.team.event.TeamEventSink {
            override fun publish(event: com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope) {
                tryPublish(event)
            }

            override fun tryPublish(
                event: com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope
            ): Boolean {
                GatewayEventBridge.publishTeam(event)
                if (com.mola.cmd.proxy.app.acp.team.coordinator.TeamCoordinationBridge.isAssigned(event.teamId)) {
                    return try {
                        com.mola.cmd.proxy.app.acp.team.coordinator.TeamCoordinationBridge.capture(event)
                    } catch (e: RuntimeException) {
                        log.warn("团队事件持久化投递未接收, teamId={}", event.teamId)
                        false
                    }
                }
                val publishedLocally = StarweaveTeamApiBridge.publishIfOwned(event)
                return if (publishedLocally) {
                    if (StarweaveTeamApiBridge.requiresCoordinator(event)) {
                        rpcEventSink.tryPublish(event)
                    } else true
                } else rpcEventSink.tryPublish(event)
            }

            override fun close() {
                rpcEventSink.close()
            }
        }
        val startupCoordinator = TeamStartupCoordinator(
            sourceResolver,
            { runtime, member, source, options, onClientCreated ->
                val team = runtime.definition
                val robot = source.copyRobotParam()
                val identity = AcpClientIdentity.team(
                    member.acpClientId,
                    team.transportGroup,
                    "team/${team.teamId}/${member.teamMemberId}",
                    team.ownerChatterId,
                    team.teamId,
                    member.teamMemberId,
                    member.sourceRobotName
                )
                val client = AcpClient(robot.workDir, identity, robot)
                onClientCreated.accept(client)
                try {
                    val teamListener = TeamAcpResponseListener(
                        runtime, member, eventSink,
                        { teamId, memberId, state, error ->
                            managerHolder.get()?.onMemberState(teamId, memberId, state, error)
                        }, client.historyManager
                    )
                    client.globalListener = teamListener
                    // Listener completion is not a lifecycle boundary: action/follow-up
                    // rounds such as talk_to may complete without the same callback path.
                    // Publish READY only after AcpClient itself wins BUSY -> READY.
                    client.setAfterTurnReady(teamListener::onClientReady)
                    client.setSessionRotationListener { previousSessionId, newSessionId ->
                        managerHolder.get()?.onMemberSessionRotated(
                            team.teamId, member.teamMemberId,
                            previousSessionId, newSessionId)
                    }
                    client.setNewSessionListener { previousSessionId, newSessionId, prompt, messageId ->
                        val manager = managerHolder.get()
                            ?: throw IllegalStateException("Team manager is unavailable")
                        manager.onMemberNewSession(team.teamId, member.teamMemberId,
                            client, previousSessionId, newSessionId, prompt, messageId)
                    }
                    client.setForceNewSession(options.isForceNewSession)
                    if (options.targetRestoreSessionId != null) {
                        client.setTargetRestoreSessionId(options.targetRestoreSessionId)
                    }
                    featureInitializer.initialize(
                        AcpClientFeatureInitializer.Context.team(
                            member.acpClientId, member.sourceGroupId,
                            team.teamId, member.teamMemberId),
                        client, robot
                    )
                    val restoreSleeping = member.state ==
                        com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.SLEEP
                    if (restoreSleeping) sleepingRecoveryPermit.acquire()
                    try {
                        client.start()
                        if (restoreSleeping) client.restoreSleepAfterRestart()
                    } finally {
                        if (restoreSleeping) sleepingRecoveryPermit.release()
                    }
                    client
                } catch (e: Exception) {
                    try {
                        client.close()
                    } catch (_: Exception) {
                    }
                    throw e
                }
            },
            teamRegistry,
            minOf(4, maxOf(1, Runtime.getRuntime().availableProcessors())),
            360_000L
        )
        val manager = TeamManager(
            TeamStore(),
            teamRegistry,
            sourceResolver,
            eventSink,
            startupCoordinator
        )
        manager.setScheduleCleanup(scheduleTaskManager::cleanupTeam)
        manager.setScheduleTaskManager(scheduleTaskManager)
        manager.setScheduleOrphanCleanup(
            scheduleTaskManager::cleanupOrphanTeams,
            scheduleTaskManager::teamOwnerCount,
            scheduleTaskManager::teamOwnerCount
        )
        manager.setMemoryDreamTrigger { sourceGroupId, workspacePath ->
            val memoryManager = memoryManagers.get(sourceGroupId).orElse(null)
                ?: return@setMemoryDreamTrigger false
            if (!memoryManager.config.isWriteEnabled) {
                return@setMemoryDreamTrigger false
            }
            memoryManager.triggerDream(workspacePath)
            true
        }
        managerHolder.set(manager)
        StarweaveTeamApiBridge.install(
            manager, CmdProxyHome.instanceId(),
            java.util.function.Supplier { starweaveTeamMemberSourceDescriptors() },
            StarweaveTeamGateway(CmdProxyHome.instanceId(),
                teamTransportDescriptor.transportGroup))
        TeamSharingStatusRegistry.setSupplier { teamSharingStatuses() }
        // 恢复流程会异步启动成员，initializer 必须在恢复前就能解析到权威 manager。
        teamManager = manager
        com.mola.cmd.proxy.app.acp.team.coordinator.TeamCoordinationBridge.installParticipant(
            manager, java.util.function.Supplier { teamTransportDescriptor })
        var recovered = false
        try {
            val recoveredCount = manager.recoverPersistedDefinitions(
                teamTransportDescriptor.transportGroup)
            manager.reconcileRevokedGrants()
            manager.startResourceReaper()
            recovered = true
            log.info("Team 持久定义恢复完成, count={}", recoveredCount)
        } catch (e: Exception) {
            log.error("Team 持久定义恢复失败；业务命令保持未开放", e)
        }
        teamCommandHandler = null
        if (recovered) {
            teamTransportDescriptor =
                TeamTransportDescriptor.readyForBusiness(
                    CmdProxyHome.instanceId(), coordinatorTeamMemberSourceDescriptors(),
                    remoteTeamMemberSourceDescriptors())
            teamCommandHandler =
                TeamCommandHandler(manager, teamTransportDescriptor.transportGroup)
            CmdReceiver.register(
                com.mola.cmd.proxy.app.acp.team.coordinator.TeamCoordinationBridge.RPC_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Proxy authenticated MolaChat Team requests to the registry coordinator"
            ) { param ->
                com.mola.cmd.proxy.app.acp.team.coordinator.TeamCoordinationBridge.handleRpc(
                    param.cmdId, param.cmdArgs)
            }
        }

        CmdReceiver.register(
            TeamTransportProtocol.DESCRIBE_COMMAND,
            teamTransportDescriptor.transportGroup,
            "Fast Team transport discovery"
        ) { param ->
            TeamTransportProtocol.describeResult(
                param.cmdId, teamTransportDescriptor, manager.metricsSnapshot()
            )
        }
        if (recovered) {
            CmdReceiver.register(
                TeamTransportProtocol.CREATE_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Create a persisted Fast Team definition"
            ) { param ->
                teamCommandHandler?.handleCreate(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.UPDATE_MEMBERS_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Update Fast Team membership and renew member sessions"
            ) { param ->
                teamCommandHandler?.handleUpdateMembers(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.LIST_COMMAND,
                teamTransportDescriptor.transportGroup,
                "List owner-scoped Fast Team definitions"
            ) { param ->
                teamCommandHandler?.handleList(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.GET_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Get one owner-scoped Fast Team definition"
            ) { param ->
                teamCommandHandler?.handleGet(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.DELETE_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Delete one Fast Team with an idempotent DELETING barrier"
            ) { param ->
                teamCommandHandler?.handleDelete(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.SEND_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Send a message to one READY Team member"
            ) { param ->
                teamCommandHandler?.handleSend(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.CANCEL_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Cancel one Team member prompt"
            ) { param ->
                teamCommandHandler?.handleCancel(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.NEW_SESSION_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Create a new session for one Team member"
            ) { param ->
                teamCommandHandler?.handleNewSession(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.LIST_SESSIONS_COMMAND,
                teamTransportDescriptor.transportGroup,
                "List sessions for one Team member"
            ) { param ->
                teamCommandHandler?.handleListSessions(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.GET_SESSION_HISTORY_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Get current session history for one Team member"
            ) { param ->
                teamCommandHandler?.handleGetSessionHistory(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.RESTORE_SESSION_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Restore one Team member session"
            ) { param ->
                teamCommandHandler?.handleRestoreSession(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.GET_STATUS_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Get one Team member status"
            ) { param ->
                teamCommandHandler?.handleGetStatus(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.GET_CONTEXT_USAGE_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Get one Team member context usage"
            ) { param ->
                teamCommandHandler?.handleGetContextUsage(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.MEMORY_DREAM_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Trigger memory dream for one Team member using its source robot memory owner"
            ) { param ->
                teamCommandHandler?.handleMemoryDream(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.READ_TEXT_FILE_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Read one text file from a Team member workspace"
            ) { param ->
                teamCommandHandler?.handleReadTextFile(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.TALK_TO_DELIVER_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Deliver one routed mixed-Team talkTo message to a local member"
            ) { param ->
                teamCommandHandler?.handleTalkToDeliver(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                TeamTransportProtocol.TALK_TO_CIRCUIT_OPEN_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Close a mixed-Team communication cascade without starting an Agent"
            ) { param ->
                teamCommandHandler?.handleTalkToCircuitOpen(param.cmdId, param.cmdArgs)
                    ?: teamUnavailableResult(param.cmdId)
            }
            CmdReceiver.register(
                StarweaveTeamGateway.RESULT_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Complete one authenticated Starweave Team coordinator request"
            ) { param ->
                StarweaveTeamApiBridge.acceptGatewayResult(param.cmdId, param.cmdArgs)
            }
            CmdReceiver.register(
                StarweaveTeamGateway.EVENT_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Project one owner-validated coordinated Starweave Team event"
            ) { param ->
                StarweaveTeamApiBridge.acceptGatewayEvent(param.cmdId, param.cmdArgs)
            }
            CmdReceiver.register(
                StarweaveTeamGateway.READY_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Activate the authenticated Starweave Team coordinator bridge"
            ) { param ->
                StarweaveTeamApiBridge.acceptGatewayReady(param.cmdId, param.cmdArgs)
            }
        }
        log.info("Fast Team transport 已注册, instanceId={}, transportGroup={}, commands={}",
            teamTransportDescriptor.cmdProxyInstanceId,
            teamTransportDescriptor.transportGroup,
            teamTransportDescriptor.commands)
    }

    private fun teamUnavailableResult(requestId: String): Map<String, String> {
        return linkedMapOf(
            "schemaVersion" to "1",
            "requestId" to requestId,
            "accepted" to "false",
            "code" to "TEAM_NOT_READY",
            "message" to "Fast Team runtime is not ready"
        )
    }

    private fun promptCommandResult(result: PromptCommandResult): Map<String, String> {
        return linkedMapOf(
            "accepted" to result.isAccepted.toString(),
            "code" to result.code,
            // 保留旧调用一直读取的 result 字段。
            "result" to result.result
        )
    }

    /**
     * 为指定 groupId 列表注册所有 cmdGroupList 维度的命令处理器。
     * 此方法在 {@link #start} 和 {@link #reloadRobot} 中复用，
     * 确保新启用的 robot 也能接受 MolaChat 的命令。
     */
    private fun registerGroupCommands(groupIds: List<String>) {
        CmdReceiver.register("readTextFile", groupIds,
            "读取当前ACP workspace内的受限文本文件") { params ->
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                val client = if (groupId.isNullOrBlank()) null else registry.getClient(groupId)
                if (client == null) {
                    com.mola.cmd.proxy.app.acp.filepreview.TextFilePreviewResult.error(
                        params.cmdId, "SESSION_NOT_FOUND", "ACP client not found", false
                    ).toResultMap()
                } else {
                    com.mola.cmd.proxy.app.acp.filepreview.TextFilePreviewReader.read(
                        params.cmdId, client.workspacePath, param.getString("path"),
                        param.getInteger("maxBytes"), param.getString("charset")
                    ).toResultMap()
                }
            } catch (e: Exception) {
                log.warn("readTextFile 失败, requestId={}", params.cmdId, e)
                com.mola.cmd.proxy.app.acp.filepreview.TextFilePreviewResult.error(
                    params.cmdId, "INVALID_ARGUMENT", "invalid readTextFile request", false
                ).toResultMap()
            }
        }

        CmdReceiver.register("crossTalkToCircuitOpen", groupIds, "关闭跨 Chatter 通信链（不触发 Agent）") { params ->
            try {
                val json = com.alibaba.fastjson.JSON.parseObject(params.cmdArgs[0])
                val cascadeId = json.getString("cascadeId") ?: throw IllegalArgumentException("cascadeId required")
                require(cascadeId.matches(Regex("[a-zA-Z0-9._-]{1,128}")))
                val startedAt = json.getLongValue("cascadeStartedAt")
                require(startedAt > 0 && startedAt <= System.currentTimeMillis() + 60_000L)
                val targetGroup = robotToGroupIdMap[json.getString("targetRobotName")]
                val client = targetGroup?.let { registry.getClient(it) }
                    ?: throw IllegalArgumentException("target not active")
                val trace = com.mola.cmd.proxy.app.acp.talkto.model.TalkToTrace(
                    cascadeId, json.getString("messageId"), json.getString("parentMessageId"),
                    json.getIntValue("depth"), startedAt
                ).withCascadeIds(json.getString("cascadeIds")?.let {
                    com.alibaba.fastjson.JSON.parseArray(it, String::class.java)
                })
                talkToDispatcher.openRemoteCircuit(client, trace, json.getString("reason") ?: "REMOTE_CIRCUIT_OPEN")
                mapOf("success" to "true", "result" to "cascade closed without prompt")
            } catch (e: Exception) {
                mapOf("success" to "false", "result" to (e.message ?: "invalid circuit control"))
            }
        }

        // 注册 crossTalkToDeliver 命令处理器（接收 MolaChat 转发的跨 chatter 消息）
        CmdReceiver.register("crossTalkToDeliver", groupIds, "接收跨chatter的talkTo消息") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val jsonStr = params.cmdArgs[0]
                val json = com.alibaba.fastjson.JSON.parseObject(jsonStr)
                val senderChatterId = json.getString("senderChatterId") ?: ""
                val senderRobotName = json.getString("senderRobotName") ?: ""
                val targetRobotName = json.getString("targetRobotName") ?: ""
                val content = json.getString("content") ?: ""
                val depth = json.getIntValue("depth")
                val cascadeId = json.getString("cascadeId")
                // Legacy gateways forwarded the sender request depth and receivers incremented it.
                // New gateways forward the server-owned message hop directly.
                val receivedHop = if (cascadeId.isNullOrBlank()) depth + 1 else depth
                val trace = com.mola.cmd.proxy.app.acp.talkto.model.TalkToTrace(
                    cascadeId,
                    json.getString("messageId"),
                    json.getString("parentMessageId"),
                    receivedHop,
                    json.getLongValue("cascadeStartedAt")
                ).withCascadeIds(json.getString("cascadeIds")?.let {
                    com.alibaba.fastjson.JSON.parseArray(it, String::class.java)
                })
                val authPrincipalId = json.getString("authPrincipalId")
                val authContext = if (authPrincipalId.isNullOrBlank()) null else
                    com.mola.cmd.proxy.app.acp.mcpauth.AuthPrincipalContext(
                        authPrincipalId,
                        json.getString("authPrincipalName") ?: "",
                        json.getString("authSourceType") ?: "CHANNEL",
                        json.getString("authSourceId") ?: "")

                val targetGroupId = robotToGroupIdMap[targetRobotName]
                val targetClient = if (targetGroupId != null) registry.getClient(targetGroupId) else null
                val targetRobot = targetClient?.robotParam

                if (targetClient == null || targetRobot == null) {
                    log.warn("crossTalkToDeliver 目标不存在: targetRobotName={}", targetRobotName)
                    resultMap["result"] = "目标 robot '$targetRobotName' 不存在或未启动"
                    resultMap["success"] = "false"
                    return@register resultMap
                }

                val senderFullName = "$senderChatterId:$senderRobotName"
                val message = com.mola.cmd.proxy.app.acp.talkto.model.TalkToMessage(
                    senderFullName, content, trace.hopCount, emptyList(), authContext, trace
                )

                val delivery = talkToDispatcher.deliverInbound(targetGroupId, targetClient, message)
                resultMap["result"] = when (delivery.status) {
                    com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher.InboundDeliveryResult.Status.DIRECT -> "已直接投递"
                    com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher.InboundDeliveryResult.Status.QUEUED -> "目标忙碌，已放入 inbox"
                    else -> delivery.reason ?: "投递被拒绝"
                }
                resultMap["success"] = (delivery.status !=
                    com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher.InboundDeliveryResult.Status.REJECTED).toString()
                log.info("crossTalkToDeliver: {}:{} → {}, status={}, cascadeId={}",
                    senderChatterId, senderRobotName, targetRobot.name, delivery.status,
                    trace.cascadeId)
            } catch (e: Exception) {
                log.error("crossTalkToDeliver 处理失败", e)
                resultMap["result"] = "处理异常: ${e.message}"
                resultMap["success"] = "false"
            }
            resultMap
        }

        CmdReceiver.register("acpCancelPrompt", groupIds, "取消ACP当前正在进行的prompt turn，groupId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    resultMap["accepted"] = "false"
                    resultMap["code"] = "REJECTED_STATE"
                    return@register resultMap
                }
                resultMap.putAll(promptCommandResult(mainSessionService.cancel(groupId)))
            } catch (e: Exception) {
                log.error("acpCancelPrompt 失败", e)
                resultMap["result"] = "取消失败: ${e.message}"
                resultMap["accepted"] = "false"
                resultMap["code"] = "CANCEL_FAILED"
            }
            resultMap
        }

        CmdReceiver.register("acpNewSession", groupIds, "会话上下文清除，groupId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    return@register resultMap
                }
                val client = registry.getClient(groupId)
                if (client == null || (client.state != AbstractAcpClient.State.READY
                        && client.state != AbstractAcpClient.State.SLEEP)) {
                    resultMap["result"] = "当前client状态不为READY或SLEEP，不允许清除上下文"
                    return@register resultMap
                }

                val newClient = replaceMainSession(groupId, client, null)
                resultMap["result"] = if (newClient == null) {
                    "会话状态已变化，未重复开启新会话"
                } else {
                    notifyMainSessionChanged(
                        groupId, client.sessionId, newClient.sessionId, "MANUAL")
                    "已开启新会话"
                }
            } catch (e: Exception) {
                log.error("acpNewSession 失败", e)
                resultMap["result"] = "开启新会话失败: ${e.message}"
            }
            resultMap
        }

        CmdReceiver.register("acpSendMessage", groupIds,
            "向ACP会话发送消息，groupId和message必填，files可选，busyPolicy可选INTERRUPT") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                val message = param.getString("message")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    resultMap["accepted"] = "false"
                    resultMap["code"] = "REJECTED_STATE"
                    return@register resultMap
                }
                if (message.isNullOrBlank()) {
                    resultMap["result"] = "message不能为空"
                    resultMap["accepted"] = "false"
                    resultMap["code"] = "REJECTED_STATE"
                    return@register resultMap
                }
                val files: MutableList<Map<String, String>>? = if (param.containsKey("files")) {
                    param.getJSONArray("files")?.map { item ->
                        val jsonObj = item as JSONObject
                        val map = mutableMapOf<String, String>()
                        for (key in jsonObj.keys) {
                            map[key] = jsonObj.getString(key)
                        }
                        map as Map<String, String>
                    }?.toMutableList()
                } else null
                resultMap.putAll(mainSessionService.send(
                    groupId, message, files,
                    param.getString("busyPolicy")).let(::promptCommandResult))
            } catch (e: Exception) {
                log.error("acpSendMessage 失败", e)
                resultMap["result"] = "发送消息失败: ${e.message}"
                resultMap["accepted"] = "false"
                resultMap["code"] = "REJECTED_STATE"
            }
            resultMap
        }

        CmdReceiver.register("acpGetStatus", groupIds, "获取ACP会话状态，groupId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    return@register resultMap
                }
                val status = mainSessionService.status(groupId)
                if (status != null) {
                    resultMap["result"] = status.name
                }
            } catch (e: Exception) {
                log.error("acpGetStatus 失败", e)
                resultMap["result"] = "获取状态失败: ${e.message}"
            }
            resultMap
        }

        CmdReceiver.register("acpGetContextUsage", groupIds, "获取ACP会话上下文使用占比，groupId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                resultMap["result"] = mainSessionService
                    .contextUsagePercentage(groupId).toString()
            } catch (e: Exception) {
                log.error("acpGetContextUsage 失败", e)
                resultMap["result"] = "-1"
            }
            resultMap
        }

        CmdReceiver.register("acpListSessions", groupIds, "获取最近N个会话列表，groupId必填，limit可选(默认7)") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    return@register resultMap
                }
                val limit = param.getIntValue("limit").let { if (it <= 0) 7 else it }
                val currentSessionId = mainSessionService.currentSessionId(groupId)
                if (currentSessionId == null) {
                    resultMap["result"] = "会话不存在"
                    return@register resultMap
                }
                val sessions = mainSessionService.listSessions(groupId, limit)
                val arr = com.alibaba.fastjson.JSONArray()
                for (s in sessions) {
                    if (arr.size >= limit) break
                    val obj = JSONObject()
                    obj["sessionId"] = s.sessionId
                    val isCurrent = s.sessionId == currentSessionId
                    obj["preview"] = s.preview
                    obj["lastModified"] = s.lastModified
                    obj["current"] = isCurrent
                    arr.add(obj)
                }
                resultMap["result"] = arr.toJSONString()
            } catch (e: Exception) {
                log.error("acpListSessions 失败", e)
                resultMap["result"] = "查询失败: ${e.message}"
            }
            resultMap
        }

        CmdReceiver.register("acpRestoreSession", groupIds, "恢复指定历史会话，groupId和sessionId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                val sessionId = param.getString("sessionId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    return@register resultMap
                }
                if (sessionId.isNullOrBlank()) {
                    resultMap["result"] = "sessionId不能为空"
                    return@register resultMap
                }
                val client = registry.getClient(groupId)
                if (client?.state == AbstractAcpClient.State.SLEEP) {
                    registry.wakeIfSleeping(groupId)
                }
                if (client == null || client.state != AbstractAcpClient.State.READY) {
                    resultMap["result"] = "当前client状态不允许恢复会话"
                    return@register resultMap
                }

                if (sessionId == client.sessionId) {
                    resultMap["result"] = "当前已是该会话，无需切换"
                    return@register resultMap
                }

                mainSessionService.restore(groupId, sessionId) { restored ->
                    featureInitializer.initialize(
                        AcpClientFeatureInitializer.Context.main(groupId),
                        restored, restored.robotParam)
                }

                val newClient = registry.getClient(groupId)
                if (newClient != null) {
                    Thread {
                        try {
                            replaySessionSnapshot(groupId, newClient)
                        } catch (e: Exception) {
                            log.error("发送会话快照失败, groupId={}, sessionId={}", groupId, sessionId, e)
                        }
                    }.start()
                }
            } catch (e: Exception) {
                log.error("acpRestoreSession 失败", e)
                resultMap["result"] = "恢复失败: ${e.message}"
            }
            resultMap
        }

        // ==================== 记忆管理命令 ====================

        CmdReceiver.register("acpMemoryList", groupIds, "列出当前项目的所有记忆，groupId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    return@register resultMap
                }
                val mgr = memoryManagers.get(groupId).orElse(null)
                if (mgr == null) {
                    resultMap["result"] = "该 robot 未启用记忆系统"
                    return@register resultMap
                }
                val client = registry.getClient(groupId)
                if (client == null) {
                    resultMap["result"] = "会话不存在"
                    return@register resultMap
                }
                val memories = mgr.listMemories(client.workspacePath)
                if (memories.isEmpty()) {
                    resultMap["result"] = "暂无记忆"
                } else {
                    val sb = StringBuilder()
                    for ((i, entry) in memories.withIndex()) {
                        sb.append("${i + 1}. [${entry.type}] ${entry.title}：${entry.summary} (id=${entry.id})\n")
                    }
                    resultMap["result"] = sb.toString()
                }
            } catch (e: Exception) {
                log.error("acpMemoryList 失败", e)
                resultMap["result"] = "查询记忆失败: ${e.message}"
            }
            resultMap
        }

        CmdReceiver.register("acpMemoryDelete", groupIds, "删除指定记忆，groupId和memoryId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                val memoryId = param.getString("memoryId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    return@register resultMap
                }
                if (memoryId.isNullOrBlank()) {
                    resultMap["result"] = "memoryId不能为空"
                    return@register resultMap
                }
                val mgr = memoryManagers.get(groupId).orElse(null)
                if (mgr == null) {
                    resultMap["result"] = "该 robot 未启用记忆系统"
                    return@register resultMap
                }
                val client = registry.getClient(groupId)
                if (client == null) {
                    resultMap["result"] = "会话不存在"
                    return@register resultMap
                }
                val success = mgr.deleteMemory(client.workspacePath, memoryId)
                resultMap["result"] = if (success) "记忆已删除: $memoryId" else "记忆不存在: $memoryId"
            } catch (e: Exception) {
                log.error("acpMemoryDelete 失败", e)
                resultMap["result"] = "删除记忆失败: ${e.message}"
            }
            resultMap
        }

        CmdReceiver.register("acpMemoryClean", groupIds, "清理过期记忆，groupId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    return@register resultMap
                }
                val mgr = memoryManagers.get(groupId).orElse(null)
                if (mgr == null) {
                    resultMap["result"] = "该 robot 未启用记忆系统"
                    return@register resultMap
                }
                val client = registry.getClient(groupId)
                if (client == null) {
                    resultMap["result"] = "会话不存在"
                    return@register resultMap
                }
                val count = mgr.cleanExpiredMemories(client.workspacePath)
                resultMap["result"] = "已清理 $count 条过期记忆"
            } catch (e: Exception) {
                log.error("acpMemoryClean 失败", e)
                resultMap["result"] = "清理记忆失败: ${e.message}"
            }
            resultMap
        }

        CmdReceiver.register("acpMemoryDream", groupIds, "手动触发记忆整理（Memory Dream），groupId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    return@register resultMap
                }
                val mgr = memoryManagers.get(groupId).orElse(null)
                if (mgr == null) {
                    resultMap["result"] = "该 robot 未启用记忆系统"
                    return@register resultMap
                }
                val client = registry.getClient(groupId)
                if (client == null) {
                    resultMap["result"] = "会话不存在"
                    return@register resultMap
                }
                mgr.triggerDream(client.workspacePath)
                resultMap["result"] = "记忆整理已触发，将在后台执行"
            } catch (e: Exception) {
                log.error("acpMemoryDream 失败", e)
                resultMap["result"] = "触发记忆整理失败: ${e.message}"
            }
            resultMap
        }
    }

    /**
     * 将恢复的会话历史以快照形式异步推送给 molachat，让用户回忆聊天细节。
     * <p>
     * USER、ASSISTANT 和 TOOL 消息按持久化角色回放；MCP 专属事件由事件历史投影。
     */
    private fun replaySessionSnapshot(groupId: String, client: AcpClient) {
        val listener = client.globalListener ?: return
        val sessionId = client.sessionId ?: return
        val history = client.historyManager.getFullHistory(sessionId)
        if (history.isEmpty()) return

        // 整个回放过程开启缓冲，最后一次性发送
        val bufferListener = listener as? com.mola.cmd.proxy.app.acp.acpclient.listener.DefaultAcpResponseListener
        bufferListener?.beginBuffer()

        for (msg in history) {
            when (msg.role) {
                com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage.Role.USER -> {
                    val content = msg.content ?: continue
                    listener.onMessage("**🧑 用户：**\n${content}\n\n---\n\n")
                }
                com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage.Role.ASSISTANT -> {
                    val content = msg.content ?: continue
                    if (content.isNotBlank()) {
                        listener.onMessage("${content}\n\n---\n\n")
                    }
                }
                com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage.Role.TOOL -> {
                    val update = com.google.gson.JsonObject()
                    if (msg.rawInput != null) update.add("rawInput", msg.rawInput)
                    if (msg.rawOutput != null) update.add("rawOutput", msg.rawOutput)
                    listener.onToolCall(
                        msg.toolCallId ?: "",
                        msg.toolName ?: "tool",
                        "completed",
                        update
                    )
                }
                com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage.Role.EVENT -> {
                    if (msg.eventType == "TASK_EVENT" && msg.eventData != null) {
                        listener.onTaskEvent(msg.eventData)
                    }
                }
            }
        }

        // 回放结束，flush 缓冲后再发终止帧
        bufferListener?.flushBuffer()
        listener.onComplete("")
    }

    /**
     * 为 client 初始化记忆系统（如果该 robot 开启了记忆）。
     */
    private fun initMemoryForClient(groupId: String, client: AcpClient, robot: AcpRobotParam?) {
        if (robot == null || !robot.isMemoryEnabled) return

        val memCfg = robot.memory
        val executionRobot = resolveMemoryExecutionRobot(robot)
        val executionWorkDir = if (memCfg.isRobotExecution) {
            executionRobot?.workDir
        } else {
            null
        }
        val mgr = memoryManagers.getOrCreate(
            groupId, memCfg, robot, executionRobot, executionWorkDir)
        client.setMemoryManager(mgr)
        setupTurnCallback(client, memCfg, mgr)
        client.setAfterSessionReady { recoverPendingMemoryExtractions(client, mgr) }
    }

    /**
     * Client 已启动且 MemoryManager 已注入后，异步补偿上次 stop 留下的全量提取。
     * 提交本身不阻塞 client READY；只有模型任务真正成功后才清除 pending。
     */
    private fun recoverPendingMemoryExtractions(client: AcpClient, manager: MemoryManager) {
        PendingMemoryExtractionRecovery.recover(
            client.historyManager,
            client.clientIdentity.historyNamespace,
            client.workspacePath,
            manager,
            pendingMemoryRecoveryClaims,
            if (client.isRestoredSession) client.sessionId else null,
            if (client.isRestoredSession) client.conversationHistory.size else 0
        )
    }

    /**
     * 为 AcpClient 注册每 N 轮触发记忆提取的回调。
     */
    private fun setupTurnCallback(client: AcpClient, memoryConfig: MemoryConfig, memoryManager: MemoryManager) {
        val interval = memoryConfig.extractIntervalTurns
        if (interval <= 0) return

        val turnCount = AtomicInteger(0)
        client.historyManager.setOnTurnFlushed {
            if (turnCount.incrementAndGet() % interval == 0) {
                log.info("每 {} 轮触发记忆提取, groupId={}", interval, client.groupId)
                memoryManager.submitExtract(
                    client.sessionId,
                    client.workspacePath,
                    client.conversationHistory
                )
            }
        }
    }

    /**
     * 初始化能力反思服务。
     * 触发时机：AcpClient 初始化后发现 ability.md 不存在时立即触发。
     * dream 完成后也会触发（通过 MemoryManager 的回调）。
     */
    private fun initAbilityReflection(groupId: String, client: AcpClient, robot: AcpRobotParam?) {
        if (robot == null || robot.name.isBlank()) return
        if (!robot.isAbilityAutoRefresh) return

        val timeoutSeconds = if (robot.isMemoryEnabled) robot.memory.subClientTimeout else 120
        val mcpConfigPaths = client.mcpConfigPaths
        val memoryManager: MemoryManager? = if (robot.isMemoryEnabled) {
            memoryManagers.get(groupId).orElse(null)
        } else null

        val service = abilityServices.getOrPut(groupId) {
            AbilityReflectionService(
                robot.name, client.workspacePath, robot,
                timeoutSeconds, mcpConfigPaths, memoryManager
            )
        }

        // 统一由 submitReflection 内部判断是否需要执行
        service.submitReflection()
    }

    /**
     * 为 onlySubAgent 的 robot 初始化能力反思（不依赖 AcpClient）。
     */
    private fun initAbilityReflectionStandalone(groupId: String, robot: AcpRobotParam) {
        if (robot.name.isBlank()) return
        if (!robot.isAbilityAutoRefresh) return

        val agentProvider = robot.agentProvider ?: "KIRO_CLI"
        val workDir = robot.workDir ?: return
        val mcpConfigPaths = AgentProviderRouter.getInstance().resolve(agentProvider)
            .getMcpConfigPaths(workDir)
        val timeoutSeconds = if (robot.isMemoryEnabled) robot.memory.subClientTimeout else 120

        val service = abilityServices.getOrPut(groupId) {
            AbilityReflectionService(
                robot.name, workDir, robot,
                timeoutSeconds, mcpConfigPaths, null
            )
        }
        service.submitReflection()
    }

    /**
     * 初始化子 Agent 派发器（如果该 robot 配置了 subAgents）。
     */
    private fun initSubAgentDispatcher(groupId: String, client: AcpClient, robot: AcpRobotParam?) {
        if (robot == null || !robot.hasSubAgents()) return

        // 校验子 Agent 引用的有效性
        val allowedNames = mutableSetOf<String>()
        for (ref in robot.subAgents) {
            if (!globalRobotRegistry.containsKey(ref.name)) {
                log.warn("robot '{}' 引用了不存在的子 Agent '{}'，跳过", robot.name, ref.name)
            } else {
                allowedNames.add(ref.name)
            }
        }

        if (allowedNames.isEmpty()) {
            log.warn("robot '{}' 的所有子 Agent 引用均无效，跳过派发器初始化", robot.name)
            return
        }

        val dispatcher = SubAgentDispatcher(
            globalRobotRegistry,
            allowedNames,
            1080
        )

        // 构建子 Agent 的记忆管理器映射（robot name -> MemoryManager）
        val subAgentMemoryMap = mutableMapOf<String, com.mola.cmd.proxy.app.acp.acpclient.MemoryManagerBridge>()
        for (name in allowedNames) {
            val targetRobot = globalRobotRegistry[name] ?: continue
            if (targetRobot.isMemoryEnabled) {
                val executionRobot = resolveMemoryExecutionRobot(targetRobot)
                val executionWorkDir = if (targetRobot.memory.isRobotExecution) {
                    executionRobot?.workDir
                } else {
                    null
                }
                val memMgr = memoryManagers.getOrCreate(
                    "subagent:$name", targetRobot.memory, targetRobot,
                    executionRobot, executionWorkDir)
                subAgentMemoryMap[name] = memMgr
            }
        }
        if (subAgentMemoryMap.isNotEmpty()) {
            dispatcher.setMemoryManagers(subAgentMemoryMap)
            log.info("子 Agent 记忆管理器注入完成, agents={}", subAgentMemoryMap.keys)
        }

        val injector = SubAgentContextInjector()

        client.setSubAgentSupport(dispatcher, injector, globalRobotRegistry)

        log.info("子 Agent 派发器初始化完成, groupId={}, subAgents={}",
            groupId, allowedNames)
    }

    /** 配置中的普通智能体无需先打开会话即可观测，事件出现后再创建会话。 */
    private fun syncConfiguredObservationTargets() {
        observationManager.clearConfiguredTargets()
        for (robot in configuredRobotRegistry.values) {
            if (robot.isOnlySubAgent || robot.isOnlyTeamMember || robot.workDir.isBlank()) continue
            val identity = StarweaveIdentity.identity(CmdProxyHome.instanceId(), robot.name)
            val owner = ScheduleOwnerKey.main(identity.ownerId, identity.surface, identity.logicalId, robot.name)
            observationManager.registerConfigured(owner, robot.workDir, {
                configuredRobotRegistry[robot.name]?.let {
                    it.isEnabled && it.isObservationEnabled && !it.isOnlySubAgent && !it.isOnlyTeamMember
                } ?: false
            }) { prompt ->
                if (registry.getClient(identity.logicalId) == null) {
                    checkNotNull(starweaveSessionManager).open(robot.name)
                }
                registry.sendObservation(identity.logicalId, prompt)
            }
        }
    }

    /** 初始化定时任务和观测能力。 */
    private fun initScheduleSupport(
        context: AcpClientFeatureInitializer.Context,
        client: AcpClient,
        robot: AcpRobotParam?
    ) {
        val injector = ScheduleContextInjector()
        val owner = if (context.scope == AcpClientFeatureInitializer.Scope.TEAM) {
            val team = teamManager?.getRuntime(context.teamId)
                ?.orElseThrow {
                    IllegalStateException("Team runtime 不存在: ${context.teamId}")
                }?.definition
                ?: throw IllegalStateException("TeamManager 尚未初始化")
            ScheduleOwnerKey.team(
                team.ownerChatterId,
                context.teamId,
                context.teamMemberId,
                robot?.name
            )
        } else {
            if (client.clientIdentity.isStarweave) {
                ScheduleOwnerKey.main(
                    client.clientIdentity.ownerId,
                    client.clientIdentity.surface,
                    client.clientIdentity.logicalId,
                    robot?.name ?: context.featureOwnerKey
                )
            } else {
                // Preserve the legacy MolaChat schedules/{robotName} namespace.
                ScheduleOwnerKey.main(robot?.name ?: context.featureOwnerKey)
            }
        }
        client.setScheduleSupport(scheduleTaskManager, injector, owner)
        val observationOwner = if (owner.isTeam) owner else ScheduleOwnerKey.main(
            client.clientIdentity.ownerId ?: client.clientIdentity.logicalId,
            client.clientIdentity.surface, client.clientIdentity.logicalId,
            robot?.name ?: context.featureOwnerKey)
        client.setObservationSupport(observationManager, observationOwner) { prompt ->
            if (observationOwner.isTeam) teamManager?.sendObservation(observationOwner, prompt) ?: false
            else registry.sendObservation(context.sourceGroupId, prompt)
        }
        log.info("定时任务支持初始化完成, owner={}, scheduleEnabled={}",
            owner, robot?.isScheduleEnabled ?: true)
    }

    /**
     * 初始化 TalkTo 支持（如果该 robot 配置了通讯录或系统中有多个 robot）。
     */
    private fun initTalkToSupport(groupId: String, client: AcpClient, robot: AcpRobotParam?) {
        // dispatcher 同时提供动态外部通讯录；信道在 client 初始化后注册也能在首轮被发现。
        val injector = TalkToContextInjector(talkToDispatcher, groupId)
        client.setTalkToSupport(talkToDispatcher, injector, globalRobotRegistry)
        log.info("TalkTo 支持初始化完成, groupId={}, contacts={}",
            groupId, robot?.contacts?.map { it.name } ?: emptyList<String>())
    }

    /**
     * 统一能力挂点：Team 的 memory/ability 复用来源 owner，schedule 保持正常能力；
     * Team talkTo 使用严格队内 memberId 路由，绝不进入普通 robot registry。
     */
    private fun createFeatureInitializer(): AcpClientFeatureInitializer {
        return AcpClientFeatureInitializer(
            { context, client, robot ->
                if (context.scope == AcpClientFeatureInitializer.Scope.MAIN) {
                    val logicalId = client.clientIdentity.logicalId
                    client.setSleepStateListener(object : AcpClient.SleepStateListener {
                        override fun onSleeping() {
                            sleepStateStore.markSleeping(logicalId)
                        }

                        override fun onWaking() {
                            sleepStateStore.markAwake(logicalId)
                        }
                    })
                }
                taskMcpDescriptor?.takeIf {
                    com.mola.cmd.proxy.app.acp.task.mcp.TaskMcpEligibility.isEligible(
                        client.clientIdentity)
                }?.let { descriptor ->
                    val servers = com.google.gson.JsonArray()
                    servers.add(descriptor.deepCopy())
                    client.setAdditionalMcpServers(servers)
                }
                initMemoryForClient(context.sourceGroupId, client, robot)
            },
            { context, client, robot ->
                initAbilityReflection(context.sourceGroupId, client, robot)
            },
            { context, client, robot ->
                initSubAgentDispatcher(context.featureOwnerKey, client, robot)
            },
            { context, client, robot ->
                initScheduleSupport(context, client, robot)
            },
            { context, client, robot ->
                if (context.scope == AcpClientFeatureInitializer.Scope.MAIN) {
                    initTalkToSupport(context.featureOwnerKey, client, robot)
                } else {
                    initTeamTalkToExtension(context, client, robot)
                }
            }
        )
    }

    /** Starts local durable task admission only after MAIN and Team clients have recovered. */
    private fun startTaskExecutionRuntime() {
        if (taskDispatchService != null || taskDeliveryAdapter != null) return
        val service = taskService ?: return
        val manager = teamManager ?: return
        if (taskMcpDescriptor == null) {
            log.warn("任务 MCP 未就绪，保持 outbox 待处理，不启动任务投递")
            return
        }
        var adapter: com.mola.cmd.proxy.app.acp.task.dispatch.LocalTaskDeliveryAdapter? = null
        var dispatch: com.mola.cmd.proxy.app.acp.task.dispatch.TaskDispatchService? = null
        try {
            val directory = com.mola.cmd.proxy.app.acp.task.dispatch.LocalTaskExecutionDirectory(
                CmdProxyHome.instanceId(),
                StarweaveIdentity.ownerId(CmdProxyHome.instanceId()),
                taskRobotRegistry,
                manager,
                null
            )
            val projector =
                com.mola.cmd.proxy.app.acp.task.dispatch.ListenerTaskCardProjector(
                    registry, manager)
            val promptSink = com.mola.cmd.proxy.app.acp.task.dispatch.LocalTaskPromptSink(
                registry, manager, projector) { assignee ->
                val robotName = assignee.getString("robotNameSnapshot")
                val expectedGroup = StarweaveIdentity.identity(
                    CmdProxyHome.instanceId(), robotName).logicalId
                require(expectedGroup == assignee.getString("groupId")) {
                    "Task session identity does not match Starweave target"
                }
                checkNotNull(starweaveSessionManager).open(robotName)
            }
            adapter = com.mola.cmd.proxy.app.acp.task.dispatch.LocalTaskDeliveryAdapter(
                service, promptSink)
            val localAdapter = adapter
            val router = com.mola.cmd.proxy.app.acp.task.dispatch.TaskDeliveryRouter { assignee ->
                val targetInstance = assignee?.getString("instanceId")?.trim().orEmpty()
                val placement = assignee?.getString("placement")?.trim().orEmpty()
                if ((targetInstance.isEmpty() || targetInstance == CmdProxyHome.instanceId())
                    && (placement.isEmpty() || placement == "LOCAL")) localAdapter else null
            }
            dispatch = com.mola.cmd.proxy.app.acp.task.dispatch.TaskDispatchService(
                service,
                com.mola.cmd.proxy.app.acp.task.dispatch.TaskTargetResolver(directory),
                router,
                "/#tasks/"
            )
            taskDeliveryAdapter = adapter
            taskDispatchService = dispatch
            adapter.start()
            dispatch.start()
            log.info("Starweave 本地任务投递已启动")
        } catch (e: Exception) {
            dispatch?.close()
            adapter?.close()
            taskDispatchService = null
            taskDeliveryAdapter = null
            log.error("Starweave 本地任务投递启动失败，outbox 将保持待处理", e)
        }
    }

    private fun initTeamTalkToExtension(
        context: AcpClientFeatureInitializer.Context,
        client: AcpClient,
        robot: AcpRobotParam?
    ) {
        val manager = teamManager
            ?: throw IllegalStateException("TeamManager 尚未初始化")
        val runtime = manager.getRuntime(context.teamId)
            .orElseThrow { IllegalStateException("Team runtime 不存在: ${context.teamId}") }
        val dispatcher = manager.getOrCreateTalkToDispatcher(context.teamId)
        val channelOwnerKey = "team:${context.teamId}:${context.teamMemberId}"
        val injector = TeamTalkToContextInjector(
            runtime, context.teamMemberId, dispatcher, channelOwnerKey)
        // emptyMap 刻意阻断普通/跨 chatter registry；Team injector 不读取该参数。
        client.setTalkToSupport(dispatcher, injector, emptyMap())
        log.info("Team talkTo 严格队内支持初始化完成, teamId={}, memberId={}, contacts={}, robot={}",
            context.teamId, context.teamMemberId, injector.contacts().size,
            robot?.name ?: "unknown")
    }

    /**
     * 启动定时任务调度器（在所有 client 初始化完成后调用）。
     */
    fun startScheduler(groupRobotMap: Map<String, AcpRobotParam>) {
        syncConfiguredObservationTargets()
        // 设置执行回调：检查 client 状态，空闲则新建 session 并执行
        scheduleTaskManager.setScopedExecutionCallback { owner, taskId, groupName, prompt, authPrincipal, channelDelivery ->
            if (owner.isTeam) {
                val manager = teamManager
                if (manager == null) {
                    log.error("Team 定时任务执行失败：TeamManager 不存在, owner={}", owner)
                    false
                } else {
                    manager.executeScheduledPrompt(
                        owner, taskId, groupName, prompt, authPrincipal, channelDelivery)
                }
            } else {
                val robotName = owner.robotName
                    ?: throw IllegalStateException("MAIN schedule owner 缺少 robotName")
                val targetGroupId = owner.logicalId
                    ?: groupRobotMap.entries
                        .firstOrNull { it.value?.name == robotName }?.key

                if (targetGroupId == null) {
                    log.error("定时任务执行失败：找不到 robot '{}' 对应的 groupId", robotName)
                    throw RuntimeException("找不到 robot '$robotName' 对应的 groupId")
                }

                val client = registry.getClient(targetGroupId)
                    ?: throw RuntimeException("client 不存在, groupId=$targetGroupId")
                if (client.state == AbstractAcpClient.State.SLEEP) {
                    registry.wakeIfSleeping(targetGroupId)
                }
                if (client.state != AbstractAcpClient.State.READY) {
                    log.info("定时任务跳过：client 忙碌, robot={}, state={}",
                        robotName, client.state)
                    false
                } else {
                    val boundSessionId = scheduleTaskManager
                        .findGroupSession(owner, groupName)
                    if (boundSessionId == null) {
                        val replacement = mainSessionService.replaceIfCurrent(
                            targetGroupId, client, null
                        ) { replacement ->
                            initializeMainReplacement(targetGroupId, replacement)
                        }
                            ?: return@setScopedExecutionCallback false
                        if (!groupName.isNullOrBlank()) {
                            scheduleTaskManager.bindGroupSession(
                                owner, groupName, replacement.sessionId)
                        }
                        notifyMainSessionChanged(
                            targetGroupId, client.sessionId,
                            replacement.sessionId, "SCHEDULE")
                        val delivery = registry.sendMessageWithResult(
                            targetGroupId, prompt, null, "REJECT",
                            replacement.promptOptionsForScheduleExecution(
                                authPrincipal, channelDelivery)
                                .setScheduleExecutionId(scheduleTaskManager.activeExecutionId(
                                    owner, taskId)))
                        if (!delivery.isAccepted) {
                            log.info("定时任务投递被拒绝, robot={}, code={}, message={}",
                                robotName, delivery.code, delivery.result)
                            return@setScopedExecutionCallback false
                        }
                    } else if (boundSessionId == client.sessionId) {
                        val delivery = registry.sendMessageWithResult(
                            targetGroupId, prompt, null, "REJECT",
                            client.promptOptionsForScheduleExecution(
                                authPrincipal, channelDelivery)
                                .setScheduleExecutionId(scheduleTaskManager.activeExecutionId(
                                    owner, taskId)))
                        if (!delivery.isAccepted) {
                            log.info("定时任务投递被拒绝, robot={}, code={}, message={}",
                                robotName, delivery.code, delivery.result)
                            return@setScopedExecutionCallback false
                        }
                    } else {
                        try {
                            val replacement = mainSessionService.replaceIfCurrent(
                                targetGroupId, client, boundSessionId
                            ) { replacement ->
                                initializeMainReplacement(targetGroupId, replacement)
                            }
                                ?: throw IllegalStateException(
                                    "恢复分组会话后 client 不存在")
                            notifyMainSessionChanged(
                                targetGroupId, client.sessionId,
                                replacement.sessionId, "SCHEDULE_RESTORE")
                            val delivery = registry.sendMessageWithResult(
                                targetGroupId, prompt, null, "REJECT",
                                replacement.promptOptionsForScheduleExecution(
                                    authPrincipal, channelDelivery)
                                    .setScheduleExecutionId(scheduleTaskManager.activeExecutionId(
                                        owner, taskId)))
                            if (!delivery.isAccepted) {
                                log.info("定时任务恢复会话后投递被拒绝, robot={}, code={}, message={}",
                                    robotName, delivery.code, delivery.result)
                                return@setScopedExecutionCallback false
                            }
                        } catch (e: Exception) {
                            log.error(
                                "定时会话分组恢复失败，等待下一轮重试, owner={}, groupName={}, sessionId={}",
                                owner, groupName, boundSessionId, e)
                            return@setScopedExecutionCallback false
                        }
                    }
                    true
                }
            }
        }

        scheduleTaskManager.start()
        observationManager.start()
        com.mola.cmd.proxy.app.acp.observation.ObservationAdminApiBridge.install(observationManager)
        com.mola.cmd.proxy.app.acp.schedule.ScheduleAdminApiBridge.install(scheduleTaskManager)
        log.info("定时任务调度器已启动")
    }

    private fun replaceMainSession(
        groupId: String,
        expected: AcpClient,
        targetRestoreSessionId: String?
    ): AcpClient? = mainSessionService.replaceIfCurrent(
        groupId, expected, targetRestoreSessionId
    ) { replacement ->
        initializeMainReplacement(groupId, replacement)
    }

    private fun initializeMainReplacement(groupId: String, client: AcpClient) {
        if (client.clientIdentity.isStarweave) {
            StarweaveSessionApiBridge.initializeReplacement(
                groupId, client, client.robotParam)
        } else {
            featureInitializer.initialize(
                AcpClientFeatureInitializer.Context.main(groupId),
                client, client.robotParam)
        }
    }

    private fun startAutoNewSession() {
        autoNewSessionExecutor?.shutdownNow()
        autoNewSessionExecutor = null
        autoNewSessionLastChecks.clear()
        autoNewSessionExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "acp-auto-new-session").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay({
                val now = System.currentTimeMillis()
                var mainRotated = 0
                var mainSlept = 0
                registry.snapshotClients().forEach { (groupId, observed) ->
                    try {
                        val sleepConfig = observed.robotParam?.autoSleep
                        if (sleepConfig != null && sleepConfig.isEnabled
                            && observed.sleepIfIdle(now, TimeUnit.MINUTES.toMillis(
                                sleepConfig.idleMinutes.toLong()))) {
                            mainSlept++
                        }
                        val config = observed.robotParam?.autoNewSession
                        if (config == null || !config.isEnabled) {
                            autoNewSessionLastChecks.remove(groupId)
                            return@forEach
                        }
                        val lastCheck = autoNewSessionLastChecks.putIfAbsent(groupId, now)
                        if (lastCheck == null || now - lastCheck < TimeUnit.MINUTES.toMillis(
                                config.checkIntervalMinutes.toLong())) {
                            return@forEach
                        }
                        if (!autoNewSessionLastChecks.replace(groupId, lastCheck, now)) {
                            return@forEach
                        }
                        val idleMillis = TimeUnit.MINUTES.toMillis(config.idleMinutes.toLong())
                        if (observed.state == AbstractAcpClient.State.SLEEP) {
                            // 睡眠会话在唤醒时按最新空闲时长判断是否新建。
                            return@forEach
                        }
                        val replacement = mainSessionService.replaceIdleIfCurrent(
                            groupId, observed, now, idleMillis
                        ) { newClient ->
                            initializeMainReplacement(groupId, newClient)
                        }
                        if (replacement != null) {
                            mainRotated++
                            notifyMainSessionChanged(
                                groupId, observed.sessionId,
                                replacement.sessionId, "AUTO_IDLE")
                        }
                    } catch (e: Exception) {
                        log.warn("MAIN 空闲会话自动轮转失败, groupId={}", groupId, e)
                    }
                }
                val teamSlept = teamManager?.sleepIdleClients(now) ?: 0
                val teamRotated = teamManager?.rotateIdleSessions(now) ?: 0
                log.info("ACP 空闲检查完成, mainSlept={}, teamSlept={}, mainRotated={}, teamRotated={}",
                    mainSlept, teamSlept, mainRotated, teamRotated)
            }, 1L, 1L, TimeUnit.MINUTES)
        }
        log.info("实例级自动新建 session 检查器已启动")
    }

    private fun notifyMainSessionChanged(
        groupId: String,
        oldSessionId: String?,
        newSessionId: String?,
        reason: String
    ) {
        if (newSessionId.isNullOrBlank()) return
        val current = registry.getClient(groupId)
        if (current?.clientIdentity?.isStarweave == true) {
            try {
                StarweaveSessionApiBridge.onSessionReplaced(
                    groupId, oldSessionId, newSessionId, reason)
                log.info(
                    "已发布 Starweave 会话切换, groupId={}, oldSessionId={}, newSessionId={}, reason={}",
                    groupId, oldSessionId, newSessionId, reason)
            } catch (e: Exception) {
                log.warn(
                    "发布 Starweave 会话切换失败, groupId={}, newSessionId={}, reason={}",
                    groupId, newSessionId, reason, e)
            }
            return
        }
        val result = linkedMapOf<String, String?>(
            "schemaVersion" to "1",
            "instanceId" to CmdProxyHome.instanceId(),
            "groupId" to groupId,
            "oldSessionId" to oldSessionId,
            "newSessionId" to newSessionId,
            "reason" to reason,
            "timestamp" to System.currentTimeMillis().toString()
        )
        try {
            CmdReceiver.callback(
                "acpSessionChanged", "acp",
                CmdResponseContent(UUID.randomUUID().toString(), result)
            )
            log.info(
                "已通知 MolaChat 会话切换, groupId={}, oldSessionId={}, newSessionId={}, reason={}",
                groupId, oldSessionId, newSessionId, reason)
        } catch (e: Exception) {
            // 通知属于状态投影，不回滚已经成功完成的 session 切换。
            log.warn(
                "通知 MolaChat 会话切换失败, groupId={}, newSessionId={}, reason={}",
                groupId, newSessionId, reason, e)
        }
    }

    /**
     * 停止所有 ACP 服务，用于热重载前清理。
     */
    @Synchronized
    fun stop() {
        log.info("正在停止 ACP 服务...")

        // Stop task producers/consumers before revoking the shared service bridge.
        taskDispatchService?.close()
        taskDispatchService = null
        taskDeliveryAdapter?.close()
        taskDeliveryAdapter = null
        taskMcpServer?.close()
        taskMcpServer = null
        taskMcpDescriptor = null

        val closingTaskService = taskService
        closingTaskService?.let {
            com.mola.cmd.proxy.app.acp.task.api.TaskApiBridge.clear(it)
        }
        taskService = null

        autoNewSessionExecutor?.shutdownNow()
        autoNewSessionExecutor = null
        autoNewSessionLastChecks.clear()

        // 先关闭外部入口、心跳和重连，避免 ACP client 关闭后仍收到新事件。
        try {
            agentGatewayServer?.close()
            agentGatewayManager?.close()
        } catch (e: Exception) {
            log.warn("停止智能体网关失败", e)
        }
        agentGatewayServer = null
        agentGatewayManager = null
        try {
            channelManager?.close()
        } catch (e: Exception) {
            log.warn("停止外部信道失败", e)
        }
        channelManager = null

        // 先停止周期发布，避免 stop 撤销 ready 后旧代际 heartbeat 继续重放。
        acpSyncRobotsHeartbeat?.close()
        acpSyncRobotsHeartbeat = null

        // 热重载窗口内先撤销 ready 宣告。RPC provider 是进程级常驻对象，不能继续
        // 用上一代 ready snapshot 误导 MolaChat。
        if (::teamTransportDescriptor.isInitialized) {
            teamTransportDescriptor =
                TeamTransportDescriptor.forInstance(
                    CmdProxyHome.instanceId(), coordinatorTeamMemberSourceDescriptors(),
                    remoteTeamMemberSourceDescriptors())
            if (::acpSyncRobotsSnapshot.isInitialized) {
                acpSyncRobotsSnapshot.updateTeamDescriptor(teamTransportDescriptor)
            }
            CmdReceiver.register(
                TeamTransportProtocol.DESCRIBE_COMMAND,
                teamTransportDescriptor.transportGroup,
                "Fast Team transport discovery"
            ) { param ->
                TeamTransportProtocol.describeResult(param.cmdId, teamTransportDescriptor)
            }
        }

        // 停止定时任务调度器
        com.mola.cmd.proxy.app.acp.observation.ObservationAdminApiBridge.clear(observationManager)
        observationManager.close()
        try {
            com.mola.cmd.proxy.app.acp.schedule.ScheduleAdminApiBridge.clear(scheduleTaskManager)
            scheduleTaskManager.stop()
        } catch (e: Exception) {
            log.warn("停止定时任务调度器失败", e)
        }

        // 关闭所有 AcpClient
        starweaveSessionManager?.let { StarweaveSessionApiBridge.clear(it) }
        starweaveSessionManager = null
        registry.closeAllForShutdown()

        // Team client 使用独立 registry，不能混入普通 registry 的 closeAll。
        val closingTeamManager = teamManager
        closingTeamManager?.closeForShutdown()
        closingTeamManager?.let { StarweaveTeamApiBridge.clear(it) }
        closingTeamManager?.let {
            com.mola.cmd.proxy.app.acp.team.coordinator.TeamCoordinationBridge.clearParticipant(it)
        }
        teamManager = null
        teamCommandHandler = null
        TeamSharingStatusRegistry.clear()

        try {
            closingTaskService?.close()
        } catch (e: Exception) {
            log.warn("停止任务存储失败", e)
        }

        // 清理内部状态
        memoryManagers.shutdownAllNow()
        pendingMemoryRecoveryClaims.clear()
        abilityServices.clear()
        globalRobotRegistry.clear()
        configuredRobotRegistry.clear()
        globalGroupRobotRegistry.clear()
        taskRobotRegistry.clear()
        robotToGroupIdMap.clear()
        activeChatterIds = emptyList()
        configuredRobotNames.clear()

        log.info("ACP 服务已停止")
    }

    @JvmStatic
    fun channelStatuses(): Map<String, ChannelStatus> =
        channelManager?.statuses ?: emptyMap()

    @JvmStatic
    fun channelErrors(): Map<String, String> =
        channelManager?.errors ?: emptyMap()

    @JvmStatic
    fun channelTeamBindingTargets(): List<Map<String, Any>> {
        val manager = teamManager ?: return emptyList()
        return manager.snapshotDefinitions()
            .filter { team ->
                team.state == com.mola.cmd.proxy.app.acp.team.model.TeamState.CREATING
                        || team.state == com.mola.cmd.proxy.app.acp.team.model.TeamState.READY
                        || team.state == com.mola.cmd.proxy.app.acp.team.model.TeamState.RECOVERING
            }
            .sortedBy { it.teamId }
            .map { team ->
                linkedMapOf<String, Any>(
                    "id" to team.teamId,
                    "name" to team.name,
                    "state" to team.state.name,
                    "mode" to team.mode.name,
                    "captainTeamMemberId" to (team.captainTeamMemberId ?: ""),
                    "members" to team.members
                        .filter { member ->
                            member.state != com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.CLOSING
                                    && member.state != com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.CLOSED
                                    && (!team.isCaptainMode
                                    || team.isCaptain(member.teamMemberId))
                        }
                        .sortedWith(compareBy({ it.order }, { it.teamMemberId }))
                        .map { member ->
                            linkedMapOf<String, Any>(
                                "id" to member.teamMemberId,
                                "name" to member.displayName,
                                "state" to member.state.name
                            )
                        }
                )
            }
    }

    @JvmStatic
    fun agentGatewayTargets(): com.alibaba.fastjson.JSONArray =
        agentGatewayManager?.targetCatalog() ?: createAgentGatewayRuntime().targets()

    @JvmStatic
    fun agentGatewayStatus(): JSONObject =
        agentGatewayManager?.adminStatus() ?: JSONObject(true).also {
            it["status"] = "STOPPED"
        }

    private fun createAgentGatewayRuntime(): GatewayRuntime = object : GatewayRuntime {
        override fun targets(): com.alibaba.fastjson.JSONArray {
            val result = com.alibaba.fastjson.JSONArray()
            registry.snapshotClients().values
                .filter { it.clientIdentity.scope == AcpClientIdentity.Scope.MAIN }
                .sortedBy { it.clientIdentity.logicalId }
                .forEach { client ->
                    val identity = client.clientIdentity
                    result.add(JSONObject(true).also { value ->
                        value["type"] = if (identity.isStarweave)
                            GatewayTarget.STARWEAVE_MAIN else GatewayTarget.MOLACHAT_MAIN
                        value["instanceId"] = CmdProxyHome.instanceId()
                        value["ownerId"] = identity.ownerId ?: identity.ownerChatterId
                        value["groupId"] = identity.logicalId
                        value["robotId"] = identity.sourceRobotName
                        value["displayName"] = identity.sourceRobotName ?: identity.logicalId
                        value["surface"] = identity.surface.name
                        value["sessionId"] = client.sessionId
                        value["state"] = client.state.name
                    })
                }
            val manager = teamManager
            manager?.snapshotDefinitions()?.sortedBy { it.teamId }?.forEach { team ->
                team.members
                    .filter { member ->
                        (!team.isCaptainMode || team.isCaptain(member.teamMemberId)) &&
                            manager.clientRegistry.get(team.teamId, member.teamMemberId).isPresent
                    }
                    .sortedWith(compareBy({ it.order }, { it.teamMemberId }))
                    .forEach { member ->
                        val client = manager.clientRegistry.get(
                            team.teamId, member.teamMemberId).orElse(null)
                        result.add(JSONObject(true).also { value ->
                            value["type"] = GatewayTarget.TEAM_MEMBER
                            value["instanceId"] = CmdProxyHome.instanceId()
                            value["ownerId"] = team.ownerChatterId
                            value["teamId"] = team.teamId
                            value["teamName"] = team.name
                            value["teamMemberId"] = member.teamMemberId
                            value["acpClientId"] = member.acpClientId
                            value["robotId"] = member.sourceRobotName
                            value["displayName"] = "${team.name} · ${member.displayName}"
                            value["surface"] = "TEAM"
                            value["sessionId"] = client?.sessionId ?: member.sessionId
                            value["state"] = client?.state?.name ?: member.state.name
                            value["captain"] = team.isCaptain(member.teamMemberId)
                        })
                    }
            }
            return result
        }

        override fun status(target: GatewayTarget): JSONObject {
            if (target.type == GatewayTarget.TEAM_MEMBER) {
                return teamStatus(target, requireTeamManager().getStatus(
                    UUID.randomUUID().toString(), teamCommand(target, null, null)))
            }
            val client = registry.getClient(target.groupId)
                ?: throw IllegalStateException("Agent target is unavailable")
            require(target.matches(client.clientIdentity)) { "Agent target identity mismatch" }
            return JSONObject(true).also {
                it["sessionId"] = client.sessionId
                it["state"] = client.state.name
                it["contextUsagePercentage"] = client.contextUsagePercentage
                it["displayName"] = client.clientIdentity.sourceRobotName ?: target.robotId
                it["surface"] = client.clientIdentity.surface.name
            }
        }

        override fun send(target: GatewayTarget, message: String,
                          files: List<Map<String, String>>?, busyPolicy: String?): JSONObject {
            if (target.type == GatewayTarget.TEAM_MEMBER) {
                return teamResult(requireTeamManager().send(
                    UUID.randomUUID().toString(), teamCommand(target, message, files)))
            }
            return promptResult(mainSessionService.send(target.groupId, message, files, busyPolicy))
        }

        override fun cancel(target: GatewayTarget): JSONObject {
            if (target.type == GatewayTarget.TEAM_MEMBER) {
                return teamResult(requireTeamManager().cancel(
                    UUID.randomUUID().toString(), teamCommand(target, null, null)))
            }
            return promptResult(mainSessionService.cancel(target.groupId))
        }

        override fun newSession(target: GatewayTarget): JSONObject {
            if (target.type == GatewayTarget.TEAM_MEMBER) {
                return teamResult(requireTeamManager().newSession(
                    UUID.randomUUID().toString(), teamCommand(target, null, null)))
            }
            val current = registry.getClient(target.groupId)
                ?: return rejectedGatewayResult("TARGET_UNAVAILABLE", "Agent target is unavailable")
            if (current.state == AbstractAcpClient.State.SLEEP) {
                registry.wakeIfSleeping(target.groupId)
            }
            if (!target.matches(current.clientIdentity) ||
                current.state != AbstractAcpClient.State.READY) {
                return rejectedGatewayResult("REJECTED_STATE", "Agent target is not READY")
            }
            val replacement = replaceMainSession(target.groupId, current, null)
                ?: return rejectedGatewayResult("SESSION_CONFLICT", "Agent session changed")
            notifyMainSessionChanged(
                target.groupId, current.sessionId, replacement.sessionId, "AGENT_GATEWAY")
            return acceptedGatewayResult("SESSION_CREATED", "New session created")
        }
    }

    private fun requireTeamManager(): TeamManager = teamManager
        ?: throw IllegalStateException("Team service is not running")

    private fun teamCommand(target: GatewayTarget, message: String?,
                            files: List<Map<String, String>>?): TeamMemberCommand {
        val value = JSONObject(true)
        value["schemaVersion"] = com.mola.cmd.proxy.app.acp.team.model.TeamDefinition.SCHEMA_VERSION
        value["ownerChatterId"] = target.ownerId
        value["teamId"] = target.teamId
        value["teamMemberId"] = target.teamMemberId
        value["acpClientId"] = target.acpClientId
        value["message"] = message
        value["files"] = files
        return com.google.gson.Gson().fromJson(value.toJSONString(), TeamMemberCommand::class.java)
    }

    private fun teamStatus(target: GatewayTarget, result: TeamCommandResult): JSONObject {
        if (!result.isAccepted) throw IllegalStateException(result.message)
        val data = JSON.parseObject(JSON.toJSONString(result.data))
        return JSONObject(true).also {
            it["sessionId"] = data.getString("sessionId")
            it["state"] = data.getString("clientState") ?: data.getString("memberState")
            it["contextUsagePercentage"] = data.getDoubleValue("contextUsagePercentage")
            it["displayName"] = data.getString("displayName") ?: target.teamMemberId
            it["surface"] = "TEAM"
            it["teamVersion"] = result.teamVersion
        }
    }

    private fun teamResult(result: TeamCommandResult): JSONObject = JSONObject(true).also {
        it["accepted"] = result.isAccepted
        it["code"] = result.code
        it["message"] = result.message
        it["data"] = result.data
    }

    private fun promptResult(result: PromptCommandResult): JSONObject = JSONObject(true).also {
        it["accepted"] = result.isAccepted
        it["code"] = result.code
        it["message"] = result.result
    }

    private fun acceptedGatewayResult(code: String, message: String) = JSONObject(true).also {
        it["accepted"] = true; it["code"] = code; it["message"] = message
    }

    private fun rejectedGatewayResult(code: String, message: String) = JSONObject(true).also {
        it["accepted"] = false; it["code"] = code; it["message"] = message
    }

    @JvmStatic
    fun teamSharingStatuses(): List<Map<String, Any>> {
        val definitions = teamManager?.snapshotDefinitions() ?: emptyList()
        return globalGroupRobotRegistry.values
            .filter { TeamSourceEligibility.isEligible(it) }
            .distinctBy { it.name }.sortedBy { it.name }
            .flatMap { robot ->
                val sourceRobotId = "acp-" + robot.name.replace(" ", "_")
                    .replace("\u3000", "_")
                val sharedGroup = TeamSharedSourceIds.groupId(
                    CmdProxyHome.instanceId(), sourceRobotId)
                val grantedOwners = robot.teamSharedWithChatterIds.map { it.trim() }
                    .filter { it.isNotEmpty() }.toSet()
                val fragmentOwners = definitions.filter { team ->
                    team.isMixedPlacement
                            && team.members.any { it.sourceGroupId == sharedGroup }
                }.map { it.ownerChatterId }.toSet()
                (grantedOwners + fragmentOwners).sorted().map { owner ->
                        val teams = definitions.filter { team ->
                            team.isMixedPlacement && team.ownerChatterId == owner
                                    && team.members.any { it.sourceGroupId == sharedGroup }
                        }
                        linkedMapOf<String, Any>(
                            "granteeOwnerChatterId" to owner,
                            "robotName" to robot.name,
                            "teamCount" to teams.size,
                            "memberCount" to teams.sumOf { team ->
                                team.members.count { it.sourceGroupId == sharedGroup }
                            },
                            "state" to if (!grantedOwners.contains(owner)) "REVOKED_CLEANUP"
                                else if (teams.isEmpty()) "GRANTED" else "RUNNING",
                            "cleanupPending" to (teams.any {
                                it.state == com.mola.cmd.proxy.app.acp.team.model.TeamState.DELETING
                            } || teams.any {
                                teamManager?.isRevokedGrantCleanupPending(it.teamId) == true
                            })
                        )
                    }
            }
    }

    @JvmStatic
    fun setChannelInboundEnabled(channelId: String, enabled: Boolean): Boolean =
        channelManager?.setInboundEnabled(channelId, enabled)
            ?: throw IllegalStateException("channel service is not running")

    @JvmStatic
    fun setChannelPrivateChatEnabled(channelId: String, enabled: Boolean): Boolean =
        channelManager?.setPrivateChatEnabled(channelId, enabled)
            ?: throw IllegalStateException("channel service is not running")

    @JvmStatic
    @Synchronized
    fun reloadChannel(previousChannelId: String, config: ChannelConfig?) {
        channelManager?.reloadChannel(previousChannelId, config)
            ?: throw IllegalStateException("channel service is not running")
        registry.snapshotClients().values.forEach { it.requestAcpHarnessReinjection() }
        teamManager?.requestAcpHarnessReinjectionForAllClients()
    }

    private fun ensureShutdownHook() {
        if (shutdownHookRegistered.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(
                Thread({
                    try {
                        stop()
                    } catch (e: Exception) {
                        log.warn("JVM shutdown 时关闭 ACP/Team 资源失败", e)
                    }
                }, "acp-team-shutdown")
            )
        }
    }

    /**
     * 按 robot 维度热重载：只重建指定 robot 的 ACP 进程，不影响其他 robot。
     */
    @Synchronized
    fun reloadRobot(previousRobotName: String, robot: AcpRobotParam,
                    chatterIds: List<String>,
                    configuredRobots: Collection<AcpRobotParam>) {
        val robotName = robot.name.trim()
        val requestedPreviousName = previousRobotName.trim()
        val runtimeRobotName = when {
            configuredRobotNames.contains(requestedPreviousName) -> requestedPreviousName
            configuredRobotNames.contains(robotName) -> robotName
            else -> ""
        }
        log.info("开始 robot 级热重载: previousRobot={}, robot={}, enabled={}, onlySubAgent={}, onlyTeamMember={}",
            runtimeRobotName, robotName, robot.isEnabled, robot.isOnlySubAgent,
            robot.isOnlyTeamMember)

        require(!(robot.isOnlySubAgent && robot.isOnlyTeamMember)) {
            "onlySubAgent and onlyTeamMember cannot both be true"
        }

        if (chatterIds != activeChatterIds) {
            throw IllegalStateException(
                "chatterIds changed; use full ACP service refresh instead of robot refresh")
        }
        if (runtimeRobotName != robotName && configuredRobotNames.contains(robotName)) {
            throw IllegalStateException(
                "robot '$robotName' is already active")
        }

        // 单 Robot 刷新也同步完整配置快照，使记忆所有者能立即读取被引用
        // Robot 的最新配置；这里只更新配置对象，不额外重建其它 Robot。
        val latestConfiguredRobots = configuredRobots
            .filter { it.name.isNotBlank() }
            .associateBy { it.name }
        configuredRobotRegistry.entries.removeIf {
            !latestConfiguredRobots.containsKey(it.key)
        }
        configuredRobotRegistry.putAll(latestConfiguredRobots)
        syncConfiguredObservationTargets()
        configuredRobotNames.clear()
        configuredRobotNames.addAll(latestConfiguredRobots.keys)

        // 1. 找到该 robot 的所有旧 MAIN/Starweave client 与 Team 来源槽位。
        // Team-only Robot 没有 MAIN client，也必须清掉来源维度的旧功能实例，
        // 否则新 Team client 仍会复用保存前的记忆/能力配置。
        val oldSourceGroupIds = if (runtimeRobotName.isEmpty()) emptyList()
            else globalGroupRobotRegistry.entries
                .filter { it.value.name == runtimeRobotName }
                .map { it.key }
                .distinct()
        val oldGroupIds = if (runtimeRobotName.isEmpty()) emptyList()
            else registry.getGroupIdsByRobot(runtimeRobotName)
        for (groupId in oldGroupIds) {
            registry.closeByGroupId(groupId)
            // 新 client 必须按最新记忆执行方式重建 manager；旧 Team 引用由
            // MemoryManagerRegistry 延迟到全局 stop 再关闭。
            memoryManagers.remove(groupId)
            abilityServices.remove(groupId)
            log.info("robot '{}' 旧 client 已关闭, groupId={}", runtimeRobotName, groupId)
        }
        oldSourceGroupIds.forEach { sourceGroupId ->
            memoryManagers.remove(sourceGroupId)
            abilityServices.remove(sourceGroupId)
        }
        if (runtimeRobotName.isNotEmpty()) {
            globalGroupRobotRegistry.entries.removeIf { it.value.name == runtimeRobotName }
            taskRobotRegistry.entries.removeIf { it.value.name == runtimeRobotName }
            robotToGroupIdMap.remove(runtimeRobotName)
            robotToGroupIdMap.remove("MOLACHAT:$runtimeRobotName")
            robotToGroupIdMap.remove("STARWEAVE:$runtimeRobotName")
            robotToGroupIdMap.keys.removeIf { key ->
                key.startsWith("MOLACHAT:") && key.endsWith(":$runtimeRobotName") ||
                    key.startsWith("STARWEAVE:") && key.endsWith(":$runtimeRobotName")
            }
            memoryManagers.remove("subagent:$runtimeRobotName")
            if (runtimeRobotName != robotName) {
                val previousAcpId = "acp-" + runtimeRobotName.replace(" ", "_")
                    .replace("\u3000", "_")
                teamManager?.disableSourceRobot(previousAcpId)
            }
        }

        // 2. 更新全局 robot 注册表
        if (runtimeRobotName.isNotEmpty()) {
            globalRobotRegistry.remove(runtimeRobotName)
        }
        configuredRobotRegistry[robotName] = robot
        if (robot.isEnabled && !robot.isOnlyTeamMember) {
            globalRobotRegistry[robotName] = robot
        } else {
            globalRobotRegistry.remove(robotName)
        }

        val acpId = "acp-" + robot.name.replace(" ", "_")
            .replace("\u3000", "_")
        val sourceGroupIds = chatterIds.map { chatterId ->
            listOf(chatterId, acpId).sorted().joinToString("")
        }
        if (TeamSourceEligibility.isEligible(robot)) {
            sourceGroupIds.forEach { groupId -> globalGroupRobotRegistry[groupId] = robot }
            registerStarweaveTeamSources(listOf(robot))
            registerSharedTeamSources(listOf(robot))
        } else {
            teamManager?.disableSourceRobot(acpId)
        }
        teamManager?.reconcileRevokedGrants()

        // 3. 只有普通 MAIN robot 才重建 client；Team-only 仅保留来源配置。
        val startupFailures = mutableListOf<String>()
        if (robot.isEnabled && !robot.isOnlySubAgent && !robot.isOnlyTeamMember) {
            val newGroupIds = sourceGroupIds

            for (groupId in newGroupIds) {
                taskRobotRegistry[groupId] = robot
                try {
                    mainSessionService.create(groupId, robot.workDir, robot) { created ->
                        featureInitializer.initialize(
                            AcpClientFeatureInitializer.Context.main(groupId), created, robot)
                    }
                    if (registry.getClient(groupId) == null) {
                        startupFailures.add("$groupId: ACP client was not registered")
                        continue
                    }

                    // 更新 robotToGroupIdMap（取第一个新 groupId）
                    robotToGroupIdMap.putIfAbsent(robotName, groupId)
                    robotToGroupIdMap.putIfAbsent("MOLACHAT:$robotName", groupId)
                    chatterIds.firstOrNull { chatterId ->
                        mainGroupId(chatterId, robotName) == groupId
                    }?.let { chatterId ->
                        robotToGroupIdMap["MOLACHAT:$chatterId:$robotName"] = groupId
                    }

                    log.info("robot '{}' 新 client 已创建, groupId={}", robotName, groupId)
                } catch (e: Exception) {
                    log.error("robot '{}' client 重建失败, groupId={}", robotName, groupId, e)
                    startupFailures.add("$groupId: ${e.message ?: e.javaClass.simpleName}")
                }
            }

            // 为新 groupId 注册命令处理器，确保 MolaChat 能发送命令到此 robot
            registerGroupCommands(newGroupIds)
        } else {
            // 被禁用、Team-only 或 onlySubAgent：不启动 MAIN client。
            if (robot.isEnabled && robot.isOnlySubAgent) {
                val groupId = chatterIds.firstOrNull()?.let { chatterId ->
                    listOf(chatterId, acpId).sorted().joinToString("")
                }
                if (groupId != null && robot.workDir.isNotBlank()) {
                    initAbilityReflectionStandalone(groupId, robot)
                }
            }
        }

        // 4. 恢复被本次 Robot 重建影响的 Starweave/Team 会话。
        // Starweave 只恢复刷新前已经 ACTIVE 的槽位；Team member 则按来源
        // Robot 精确替换 client，并通过 session/load 继续原会话。
        if (robot.isEnabled && !robot.isOnlySubAgent && !robot.isOnlyTeamMember) {
            try {
                val previousStarweaveRobot = if (runtimeRobotName.isEmpty()) {
                    robotName
                } else runtimeRobotName
                val reopened = starweaveSessionManager?.reopenAfterRobotRefresh(
                    previousStarweaveRobot, robotName)
                if (reopened != null) {
                    log.info("Starweave 会话已随 robot 自动刷新并开启: robot={}, sessionId={}",
                        robotName, reopened.getString("sessionId"))
                }
            } catch (e: Exception) {
                log.error("Starweave 会话随 robot 自动刷新失败: robot={}", robotName, e)
                startupFailures.add("Starweave: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        if (TeamSourceEligibility.isEligible(robot)) {
            try {
                val refreshedMembers = teamManager?.refreshSourceRobot(acpId) ?: 0
                if (refreshedMembers > 0) {
                    log.info("Team member 已随 robot 自动刷新并开启: robot={}, members={}",
                        robotName, refreshedMembers)
                }
            } catch (e: Exception) {
                log.error("Team member 随 robot 自动刷新失败: robot={}", robotName, e)
                startupFailures.add("Fast Team: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        // 5. 触发 acpSyncRobots 通知 MolaChat 更新 robot 信息
        try {
            val allRobots = globalRobotRegistry.values.filter {
                it.isEnabled && !it.isOnlySubAgent && !it.isOnlyTeamMember
            }
            val robotsJson = JSON.toJSONString(allRobots)
            val chatterIdsJson = JSON.toJSONString(chatterIds)
            acpSyncRobotsSnapshot.updateOrdinary(robotsJson, chatterIdsJson)
            teamTransportDescriptor = TeamTransportDescriptor.readyForBusiness(
                CmdProxyHome.instanceId(), coordinatorTeamMemberSourceDescriptors(),
                remoteTeamMemberSourceDescriptors())
            acpSyncRobotsSnapshot.updateTeamDescriptor(teamTransportDescriptor)
            publishAcpSyncRobots()
            log.info("acpSyncRobots 回调已发送 (robot级重载后)")
        } catch (e: Exception) {
            log.error("acpSyncRobots 回调发送失败 (robot级重载)", e)
        }

        if (startupFailures.isNotEmpty()) {
            throw IllegalStateException(
                "robot '$robotName' failed to start: ${startupFailures.joinToString("; ")}")
        }
        log.info("robot 级热重载完成: robot={}", robotName)
    }

    private fun mainGroupId(chatterId: String, robotName: String): String {
        val acpId = "acp-" + robotName.replace(" ", "_").replace("\u3000", "_")
        return listOf(chatterId, acpId).sorted().joinToString("")
    }

    /**
     * 解析记忆模型真正使用的 Robot。完整配置注册表故意包含禁用 Robot；
     * 引用不存在时禁止静默回退，避免记忆跑到错误的供应商。
     */
    private fun resolveMemoryExecutionRobot(ownerRobot: AcpRobotParam): AcpRobotParam? {
        val memory = ownerRobot.memory
        if (!memory.isRobotExecution) return ownerRobot
        val targetName = memory.robotName?.trim().orEmpty()
        val target = configuredRobotRegistry[targetName]
        if (target == null) {
            log.error(
                "robot '{}' 的记忆执行 Robot 不存在: '{}'，本实例记忆功能不初始化",
                ownerRobot.name, targetName)
        }
        return target
    }

    private fun teamMemberSourceDescriptors(): List<TeamMemberSourceDescriptor> {
        val result = mutableListOf<TeamMemberSourceDescriptor>()
        val robotsByName = globalGroupRobotRegistry.values
            .filter { TeamSourceEligibility.isEligible(it) }
            .associateBy { it.name }
        for (chatterId in activeChatterIds.sorted()) {
            for (robot in robotsByName.values.sortedBy { it.name }) {
                val sourceRobotId = "acp-" + robot.name.replace(" ", "_")
                    .replace("\u3000", "_")
                val sourceGroupId = listOf(chatterId, sourceRobotId)
                    .sorted().joinToString("")
                if (globalGroupRobotRegistry[sourceGroupId] !== robot
                    && globalGroupRobotRegistry[sourceGroupId]?.name != robot.name) {
                    continue
                }
                result.add(TeamMemberSourceDescriptor(
                    chatterId, sourceGroupId, sourceRobotId, robot.name,
                    robot.name, robot.avatar,
                    ContactRemarkResolver.resolve(null, robot), robot.isOnlyTeamMember
                ))
            }
        }
        return result
    }

    /** Sources advertised to MolaChat include both MolaChat and Starweave owners. */
    private fun coordinatorTeamMemberSourceDescriptors(): List<TeamMemberSourceDescriptor> =
        (teamMemberSourceDescriptors() + starweaveTeamMemberSourceDescriptors())
            .distinctBy { it.ownerChatterId + "\n" + it.sourceGroupId }

    /** Starweave reuses the same Fast Team source contract without requiring MAIN sessions. */
    private fun starweaveTeamMemberSourceDescriptors(): List<TeamMemberSourceDescriptor> {
        val ownerId = StarweaveIdentity.ownerId(CmdProxyHome.instanceId())
        return configuredRobotRegistry.values
            .filter { TeamSourceEligibility.isEligible(it) }
            .distinctBy { it.name }
            .sortedBy { it.name }
            .map { robot ->
                val identity = StarweaveIdentity.identity(
                    CmdProxyHome.instanceId(), robot.name)
                TeamMemberSourceDescriptor(
                    ownerId, identity.logicalId, StarweaveIdentity.acpId(robot.name),
                    robot.name, robot.name, robot.avatar,
                    ContactRemarkResolver.resolve(null, robot), robot.isOnlyTeamMember
                )
            }
    }

    private fun registerStarweaveTeamSources(robots: Collection<AcpRobotParam>) {
        robots.filter { TeamSourceEligibility.isEligible(it) }
            .distinctBy { it.name }
            .forEach { robot ->
                val groupId = StarweaveIdentity.identity(
                    CmdProxyHome.instanceId(), robot.name).logicalId
                globalGroupRobotRegistry[groupId] = robot
                if (!robot.isOnlySubAgent && !robot.isOnlyTeamMember) {
                    taskRobotRegistry[groupId] = robot
                }
            }
    }

    private fun registerSharedTeamSources(robots: Collection<AcpRobotParam>) {
        val instanceId = CmdProxyHome.instanceId()
        robots.filter { TeamSourceEligibility.isEligible(it) }
            .distinctBy { it.name }
            .forEach { robot ->
                val sourceRobotId = "acp-" + robot.name.replace(" ", "_")
                    .replace("\u3000", "_")
                if (robot.teamSharedWithChatterIds.isNotEmpty()) {
                    globalGroupRobotRegistry[
                        TeamSharedSourceIds.groupId(instanceId, sourceRobotId)
                    ] = robot
                }
            }
    }

    private fun remoteTeamMemberSourceDescriptors(): List<RemoteTeamMemberSourceDescriptor> {
        val instanceId = CmdProxyHome.instanceId()
        val result = mutableListOf<RemoteTeamMemberSourceDescriptor>()
        globalGroupRobotRegistry.values
            .filter { TeamSourceEligibility.isEligible(it) }
            .distinctBy { it.name }
            .sortedBy { it.name }
            .forEach { robot ->
                val sourceRobotId = "acp-" + robot.name.replace(" ", "_")
                    .replace("\u3000", "_")
                val sourceGroupId = TeamSharedSourceIds.groupId(instanceId, sourceRobotId)
                robot.teamSharedWithChatterIds.map { it.trim() }
                    .filter { it.isNotEmpty() }.distinct().sorted()
                    .forEach { owner -> result.add(RemoteTeamMemberSourceDescriptor(
                        owner, instanceId, sourceGroupId, sourceRobotId,
                        robot.name, robot.name, robot.avatar,
                        ContactRemarkResolver.resolve(null, robot)
                    )) }
            }
        return result
    }

    /**
     * 从 crossTalkToDeliver 命令参数中找到目标 client。
     * MolaChat 通过 CmdSender.send(cmdName, targetGroupId, args) 路由到正确的 group，
     * 但 CmdReceiver 的 handler 是按 cmdGroupList 注册的，需要从 groupRobotMap 中匹配。
     */
    private fun findClientForDelivery(
        params: com.mola.cmd.proxy.client.param.CmdInvokeParam,
        groupRobotMap: Map<String, AcpRobotParam>
    ): AcpClient? {
        // CmdReceiver 的 invoke 会通过 routeTag 路由到正确的 group
        // 但 handler 内部无法直接获取 group 信息，需要通过 cmdArgs 中的信息反查
        // 实际上 MolaChat send 时指定了 targetGroupId，RPC 框架会路由到对应 group 的 provider
        // 这里遍历所有 client 找到匹配的（单 cmd-proxy 实例内 robot 数量有限，性能无问题）
        for ((groupId, robot) in groupRobotMap) {
            if (robot == null) continue
            val client = registry.getClient(groupId) ?: continue
            // 返回第一个匹配的 client（单 chatter 场景下每个 robot 只有一个 client）
            return client
        }
        return null
    }

}
