# Fast Team 本机与 Remote ACP 混选 MVP

> 历史方案：2026-08-08 混合队伍 MVP。2026-10-05 起，全局协调已迁入 Starweave 注册中心，
> MolaChat 不再持有混合队伍权威；当前实现与验证范围见[注册中心混合队伍改造](registry-mixed-team-refactor.md)。
> 下文保留原方案背景，不作为当前协调职责的说明。
> 兼容：现有纯本机 Fast Team 保持 V1 原路径不变

> cmd-proxy 实施状态：已完成首轮实现与回归。已落地 standing allowlist、独立 remote source
> discovery、mixed fragment roster 持久模型及跨实例 talkTo route/deliver 基础闭环；
> 保持 V1 additive compatibility。撤销授权会等待当前 BUSY turn 收尾后自动进入既有
> delete 状态机，并由 reaper 重试失败清理。最终全量回归为 216/216 通过。

## 1. 最终产品语义

新建跨实例 Team 必须同时满足：

- 至少选择一个当前 owner 的本机 cmd-proxy ACP；
- 至少选择一个 remote cmd-proxy ACP，可以来自一个或多个 remote 实例；
- Team 通用成员数为 1～10；混选 Team 因必须同时包含本机和 remote，实际至少 2 人；
  同一来源 ACP 在同一 Team 中不重复；
- 禁止只选择 remote ACP 创建 Team；
- 纯本机成员组队继续走现有单实例 V1，不强制升级为混选协议。

混选 Team 的 participants 固定为“本机实例 + N 个 remote 实例”，其中 `N >= 1`，
不设置“最多一个 remote”的额外限制；唯一总量约束来自全队最多 6 个成员。

“本机”由 MolaChat 后端依据当前 owner 的可信主实例绑定/discovery 判定，不能由前端
提交 `isLocal=true` 自报。前端可以隐藏原始 `cmdProxyInstanceId/transportGroup`，但
后端必须继续使用它们做 placement 和路由校验。

此前的方案 A（整支 Team 选择一个 remote placement、owner 单活 placement）已废弃。
此前短暂讨论的“多个互不相关的单实例 Team 仅靠 teamId 路由聚合”也不能满足同队混选，
不得作为替代实现。

## 2. 当前代码可复用的基础

cmd-proxy 已具备：

- 实例级稳定 `cmdProxyInstanceId` 与 `transportGroup=team-acp-{instanceId}`；
- 每实例独立 `TeamStore`、`TeamManager`、`TeamClientRegistry` 和恢复流程；
- `acpTeamCreate/Get/List/Delete/Send/...` 的幂等与成员级本地路由；
- Team member 独立进程、session、history、schedule、memory 与本地 inbox；
- `TeamEventEnvelope` 的 `teamId/teamMemberId/transportGroup/eventId/eventSeq`；
- MolaChat 已按 instanceId 保存多份 discovery，并已能汇总多个实例的 candidates。

因此不需要复制 remote ACP 配置，不需要 cmd-proxy 之间直连，也不需要重写 ACPClient
生命周期。跨实例成员仍在各自来源 cmd-proxy 上启动和运行。

现有实现不能直接满足的部分是：一个 `TeamDefinition` 只包含本实例启动的成员，
`TeamTalkToContextInjector` 和 `TeamTalkToDispatcher` 也只认识本地成员；MolaChat 的
Team 权威和后续命令仍假设单 transport。故 cmd-proxy 不再是“0 改动”。

## 3. 最少权威模型

### 3.1 MolaChat：混选 Team 的轻量全局权威

混选 Team 必须有一个可持久恢复的全局记录，否则 MolaChat 重启后无法知道
`teamMemberId` 位于哪个实例，也无法安全完成 delete。MVP 可复用现有
`KeyValueFactoryInterface`，按 `teamId` 保存一份 JSON，不要求旧 V2 的专用 CAS Store。

最少字段：

```json
{
  "schemaVersion": "mixed-1",
  "teamId": "uuid",
  "ownerChatterId": "owner",
  "name": "研发小队",
  "state": "CREATING|READY|RECOVERING|DELETING|FAILED|PENDING_CLEANUP|DELETED",
  "requestId": "uuid",
  "payloadHash": "sha256",
  "homeInstanceId": "local-instance",
  "participants": [
    {
      "instanceId": "local-instance",
      "transportGroup": "team-acp-local-instance",
      "state": "CREATING|READY|FAILED|DELETING|DELETED",
      "memberIds": ["member-1"]
    },
    {
      "instanceId": "remote-instance-a",
      "transportGroup": "team-acp-remote-instance-a",
      "state": "CREATING|READY|FAILED|DELETING|DELETED",
      "memberIds": ["member-2"]
    },
    {
      "instanceId": "remote-instance-b",
      "transportGroup": "team-acp-remote-instance-b",
      "state": "CREATING|READY|FAILED|DELETING|DELETED",
      "memberIds": ["member-3"]
    }
  ],
  "members": [
    {
      "teamMemberId": "member-1",
      "acpClientId": "team-acp-member-1",
      "participantInstanceId": "local-instance",
      "sourceRobotId": "acp-codex",
      "sourceGroupId": "...",
      "displayName": "Codex",
      "order": 0,
      "state": "STARTING|READY|BUSY|ERROR|CLOSED"
    }
  ],
  "lastError": null
}
```

MVP 假定单个 MolaChat 协调进程，并以 team/owner 进程内锁串行 create/delete/state
更新。通用 KV 负责重启恢复，不承诺多 MolaChat 节点的 CAS、租约或选主。

### 3.2 cmd-proxy：复用 V1 TeamDefinition 作为本地 fragment

每个参与实例继续保存同一个 `teamId`，但其现有 `TeamDefinition.members` 只包含该实例
实际启动的 local members。`participantInstanceId` 本身就是最小 fragment key，不新增
独立 `fragmentId`、FragmentStore 或第二套成员 runtime。

为使本地 Agent 看见全队并识别 remote target，fragment 额外持久化全队最小 roster：

```text
teamMemberId, acpClientId, displayName, remark, order
```

roster 不保存 remote 的 sourceGroupId、凭据或完整配置。本地成员是否存在仍以
`TeamDefinition.members` 为准；roster 中存在但不在 local members 的条目就是 remote
contact。

MolaChat 是混选 Team 的全局状态与 placement 权威；每个 cmd-proxy 仍是其本地
fragment、ACPClient、session、inbox 和资源清理的权威。纯本机 V1 Team 仍由单个
cmd-proxy 完整权威管理。

## 4. 最小协议扩展

### 4.1 Discovery capability

`TeamTransportDescriptor` 增加可选 capability，例如：

```json
{"mixedTeamFragment":true,"mixedTeamTalkToDeliver":true}
```

只有本机和全部被选 remote participant 都声明 capability 时，MolaChat 才允许确认混选。
旧 cmd-proxy 不受影响，仍能创建纯本机 V1 Team。

### 4.2 扩展现有 acpTeamCreate

继续使用 `acpTeamCreate`，不新增 prepare/commit 命令。混选请求对每个 participant 使用
稳定的 fragment requestId，携带：

- 相同的 `teamId/ownerChatterId/name`；
- 仅属于目标实例的 `members`；
- `mixedPlacement=true`；
- 全队 `roster[{teamMemberId,acpClientId,displayName,remark,order}]`。

cmd-proxy 必须校验 local members 都在 roster 中、ID/顺序唯一、roster 为 1～10 人；
`mixedPlacement=true` 时 MolaChat 还必须保证全局 roster 同时包含可信本机与 remote
placement，因此混选自然至少 2 人。roster 随本地 TeamDefinition 持久化。没有
`mixedPlacement/roster` 的旧请求保持现有语义。

### 4.3 新增 acpTeamTalkToDeliver

新增一个目标 participant 命令：

```json
{
  "schemaVersion": "mixed-1",
  "requestId": "uuid",
  "messageId": "uuid",
  "ownerChatterId": "owner",
  "teamId": "team-1",
  "senderTeamMemberId": "member-a",
  "targetTeamMemberId": "member-b",
  "content": "...",
  "depth": 1,
  "expiresAt": 0
}
```

目标 cmd-proxy 必须确认 Team/roster/target 均存在、target 是本地 member、Team 可接收、
TTL/depth 合法，并按 `teamId + messageId` 在进程内去重窗口中幂等。READY 直投，BUSY
复用本地 Team inbox；响应为 `DELIVERED/QUEUED/REJECTED/EXPIRED`。跨重启 exactly-once
不属于 MVP。

跨实例 route 请求复用现有 `acpTeamEvent`，新增事件类型
`TALK_TO_ROUTE_REQUEST`，不再增加单独 callback/ack 通道。事件携带 messageId、sender、
target、content、depth、expiresAt。MolaChat 从已认证 callback transport 反查发送实例，
再按全局记录调用目标 `acpTeamTalkToDeliver`。

MVP 中发送方工具立即得到“已提交 Team 网关”，实际投递结果通过 Team event/UI 体现；
单独的 route ACK 回传协议延后。

## 5. 端到端数据流

### 5.1 Create：直接创建 + 补偿式 saga

1. MolaChat 验证 token 和最新 discovery candidate，确认通用成员总数 1～10；混选请求
   必须包含可信本机 placement，且至少包含一个 remote placement，所以实际至少 2 人。
   remote 可以来自多个不同 cmd-proxy；remote-only 请求后端拒绝。
2. 生成全局 teamId/teamMemberId，按 instanceId 分组，在任何 RPC 前持久化全局
   `CREATING` 记录和 payloadHash。
3. 并行向每个 participant 发送扩展后的幂等 `acpTeamCreate`，每次只含其 local members，
   但 roster 相同。
4. 每个 cmd-proxy 复用现有全有或全无本地启动；MolaChat 只在全部 fragment READY 后
   把全局 Team 置为 READY并创建统一投影。
5. 任一 fragment create/启动失败时，MolaChat 对所有已接受 fragment 调用现有幂等
   `acpTeamDelete`。全部清理后全局 FAILED；有实例离线则保存 PENDING_CLEANUP，禁止 send。

因此 fragment 和 saga 在语义上不可省，但 MVP 不实现旧 V2 的 prepare/commit/abort、
prepare lease、coordinatorVersion 或两阶段提交。现有 create/delete 幂等能力加全局记录
足以构成最小补偿 saga。全局记录先落盘，MolaChat 重启后可按 participants 逐个
`acpTeamGet`，继续等待、补偿或删除。

### 5.2 Send/session/status

纯本机 V1 Team 继续走现有 owner/单 transport 路由。混选 Team 先查全局记录：

```text
teamId + teamMemberId
  -> participantInstanceId + transportGroup
  -> 现有 acpTeamSend/Cancel/NewSession/ListSessions/
     RestoreSession/GetStatus/GetContextUsage/MemoryDream
  -> participant TeamClientRegistry(teamId, teamMemberId)
```

只有全局 READY 且目标 participant READY 才允许新命令。显示名、robotName、sourceGroupId
均不得参与运行路由。

### 5.3 List/get

`GET /team` 合并两类数据：

- 现有本机 V1 Team：继续通过当前本机 `acpTeamList` 获取；
- 混选 Team：以 MolaChat 持久化全局记录为列表基线，并按已知 participants 定向调用
  `acpTeamGet` 刷新 fragment 状态，不扫描未知实例猜测 placement。

单个 remote 不可达时，全局 Team 保持可见并进入 RECOVERING；不删除 placement 和
成员。MVP 不支持在 RECOVERING 时继续给其他成员发送新 prompt，以简化一致性。

### 5.4 Event

MolaChat 注册每个 participant transport 的现有 `acpTeamEvent` callback。处理混选事件时：

1. 由 callback 注册闭包确定真实 participantInstanceId，不信任 payload 自报来源；
2. 校验全局记录包含该 participant，member placement 与 callback instance 一致；
3. `eventId` 全局去重，`eventSeq/teamVersion` 只在 participant 内比较，不能再按 teamId
   把不同 fragment 的 version 当成同一序列；
4. MESSAGE 事件投影到对应 member；fragment/member state 聚合后更新全局 Team；
5. 非法 placement、未知 member 或已删除 Team 的事件直接拒绝。

### 5.5 TalkTo

- 目标在本 fragment：继续使用现有 `TeamTalkToDispatcher` 直投/排队；
- 目标只在全队 roster：dispatcher 发布 `TALK_TO_ROUTE_REQUEST`；
- MolaChat 校验 sender/target placement 后调用目标 `acpTeamTalkToDeliver`；
- 目标 fragment 再次校验并复用 `deliverInbound`/inbox，发布 RECEIVE/QUEUED/REJECTED；
- 网关或目标不可用时明确失败，严禁回退普通 crossTalkTo 或按名字寻找 robot。

### 5.6 Delete

1. MolaChat 先持久化全局 DELETING，立即拒绝 send、session 和 talkTo；
2. 按全局 participants 使用稳定 delete requestId 并行调用现有 `acpTeamDelete`；
3. 全部返回删除/tombstone 后置 DELETED并移除 Team 投影；
4. 任一实例不可达时置 PENDING_CLEANUP并保留路由，允许用户重试；participant 恢复后
   继续幂等删除。

后台无限重试、自动 reaper 和跨节点协调可延后，但不能在局部删除失败时宣告全局
DELETED或丢弃 participant placement。

## 6. 可以延后的可靠性能力

以下旧 V2 能力不进入 MVP：

- 独立 `TeamDefinitionV2Store/OperationStore`、数据库 CAS 和多 MolaChat 节点支持；
- prepare/commit/abort 两阶段创建、prepare lease 和 reconcile 命令；
- `fragmentId`、`instanceEpoch`、coordinatorVersion、fragmentVersion、rosterDigest fencing；
- remote chatter 注册、pairing code、candidateRef 和来源管理 UI；MVP 仅使用当前 owner
  已经可见的多 cmd-proxy discovery；
- participant 离线时其余成员继续工作；MVP 统一进入 RECOVERING并门禁新请求；
- 自动后台补偿/reaper、离线事件补拉、eventSeq 缺口修复；
- 跨实例 talkTo 的同步 ACK 回传、全局 BUSY inbox 或 exactly-once；
- placement 迁移、动态增删成员、远端授权撤销、广播和多 coordinator 高可用。

不能延后的底线：全局 placement 持久化、create 前全局记录落盘、幂等 fragment create、
失败补偿、delete 屏障、事件来源校验、talkTo 目标二次校验，以及 remote-only 后端拒绝。

## 7. 最少代码文件与符号

### 7.1 cmd-proxy

生产代码最小改动集中在：

- `team/protocol/TeamCreateCommand.java`：增加 mixedPlacement 与 roster；
- `team/protocol/TeamContactCreateSpec.java`（新增）：全队最小 contact DTO；
- `team/model/TeamDefinition.java`：持久化 roster；
- `team/model/TeamContactRef.java`：必要时补 order/序列化字段；
- `team/TeamManager.java`：create roster 校验、mixed talkTo deliver 入口；
- `team/protocol/TeamTalkToDeliverCommand.java`（新增）：目标投递 DTO；
- `team/TeamCommandHandler.java`：注册/解析 deliver；
- `team/protocol/TeamTransportProtocol.java` 与
  `TeamTransportDescriptor.java`：capability 和 `acpTeamTalkToDeliver`；
- `team/event/TeamEventType.java`：`TALK_TO_ROUTE_REQUEST`；
- `team/talkto/TeamTalkToContextInjector.java`：通讯录读取全队 roster；
- `team/talkto/TeamTalkToDispatcher.java`：本地直投、remote route request、目标幂等投递；
- `acp/AcpProxy.kt`：注册新命令并把 mixed gateway/handler 接入 TeamManager。

对应测试至少扩展 `TeamCommandHandlerTest`、`TeamStartupCoordinatorTest`、
`TeamTalkToDispatcherTest`、`TeamTransportProtocolTest`、`TeamLifecycleStartupTest`。

不新增第二套 AcpClient、TeamClientRegistry、TeamStore 或 FragmentStore。

### 7.2 MolaChat

最少生产文件预计为：

- `team/solution/TeamGatewaySolution.java`：识别 LOCAL/MIXED、create/delete 补偿编排、
  member placement 路由；
- `team/solution/MixedTeamStore.java` 与 `MixedTeamRecord.java`（新增）：封装现有 KV JSON；
- `team/solution/TeamEventSolution.java` 或独立 `MixedTeamEventSolution.java`：participant
  事件校验、局部序列和全局状态聚合、talkTo route；
- `team/dto/TeamDTO.java`、`TeamMemberDTO.java`：内部 placement/participant 状态；
- `team/dto/TeamCreateRequest.java`、`TeamCreateMemberRequest.java`：沿用现有 instance/source
  字段并增加后端 LOCAL/MIXED 校验，不接受前端自报 local；
- `team/solution/TeamAcpExecSolution.java`：所有混选 member 命令经 teamId/memberId 路由；
- `static/js/chat/team.js`：允许跨 placement 多选、要求至少一个本机和一个 remote、禁止
  remote-only，且不必展示 raw instanceId/transportGroup。

`TeamController` 可保持现有 HTTP 路径；不新增来源注册 API。对应测试集中扩展
`TeamGatewaySolutionTest`、`TeamEventSolutionTest`、`TeamAcpExecSolutionTest` 和创建请求
校验测试。

## 8. MVP 验收

1. 纯本机 1～10 人 Team 创建、send、session、talkTo、delete 与重启恢复保持原行为。
2. 本机 1 人 + remote 1 人创建成功；本机和 remote 各只启动自己的 member，MolaChat
   全局 Team 在两个 fragment READY 后才 READY。
3. 本机成员 + 两个不同 remote 实例成员创建成功，member 命令分别命中正确 transport；
   同名 robot 不串线。
4. 只选 remote、只选本机却请求 MIXED、伪造 local 标记、篡改 instance/source route
   均被后端拒绝。
5. 第二个 fragment create/启动失败时，第一个已接受 fragment 被补偿删除；离线导致
   无法补偿时全局 PENDING_CLEANUP且不可 send。
6. MolaChat 在 CREATING 后重启，可从 KV 读取 participants并通过定向 get 继续聚合或补偿。
7. send/cancel/new/list/restore/status/context/dream 按 `teamId+teamMemberId` 命中 member
   placement；remote 离线时 Team RECOVERING且所有新请求门禁。
8. 本地 member talkTo 仍直投；本机到 remote、remote 到本机、remote A 到 remote B
   均经 MolaChat gateway 到达正确 target，BUSY 在目标本地 inbox 排队，重复 messageId
   不重复注入。
9. 伪造 sender/target、错误 callback transport、跨 Team target、超 TTL/depth 的 talkTo
   被拒绝，且不回退普通 crossTalkTo。
10. delete 对全部 participants 建立屏障；一个 participant 离线时保持 PENDING_CLEANUP，
    重连重试后全部 client/process/executor/inbox/schedule 回到基线。
11. 不同 participant 的相同 eventSeq/teamVersion 不互相覆盖，消息和状态按 placement
    正确聚合。
12. cmd-proxy 和 MolaChat 的 V1 Fast Team、普通 ACP、普通 talkTo/schedule/memory 回归通过。

## 9. 与旧完整 V2 方案的关系

旧跨 chatter V2 中“全局权威 + 本地 fragment + 补偿”三个概念仍然必要，但 MVP 将其
压缩为：现有 KV 全局记录、复用 V1 TeamDefinition 的本地 fragment、直接 create 加
delete 补偿。两阶段提交、专用 CAS Store、instanceEpoch/fencing、来源配对和完整恢复
状态机全部延后。

若未来开放 remote chatter 授权、participant 离线时部分可用、多 MolaChat 节点或强
一致自动恢复，再以旧 V2 文档为增强设计输入；不得反向扩大本 MVP 的首轮实现范围。
