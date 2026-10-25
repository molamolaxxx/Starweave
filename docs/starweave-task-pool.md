# Starweave 任务池与任务 MCP

任务 MCP `starweave-tasks` 提供六个工具。任务规则放在工具描述、参数说明和工具返回中，不向 `<acp-harness>` 添加任务提示词。

| 工具 | 行为 |
|---|---|
| list_tasks | 查询当前环境所有人的任务，默认非终态；支持搜索、状态、分配情况、执行者、创建时间及分页 |
| publish_task | 默认发布到待认领池；可通过 assignee_ref 指定普通 Agent 或团队成员并投递 |
| claim_task | 当前调用 Agent 原子认领；成功后继续执行，可组织团队协作，不额外投递执行消息 |
| get_task | 查询任务明细、版本、当前状态、评论和历史 |
| update_task_status | 以最新版本更新状态；Agent 不能重开终态任务 |
| add_task_comment | 保存进度、阻塞与结果，不能替代状态更新 |

新增无参数 Agent 目录、指定执行者发布及任务来源溯源详见 [Harness Agent 查询与任务来源](acp-harness-agent-directory.md)。

## 查询

`list_tasks` 默认 `page=1,pageSize=20`，最多 100 条，按更新时间倒序、ID 排序。默认排除 COMPLETED/CANCELLED，包含 START/IN_PROGRESS/SUSPENDED。

可传 `q`、`statuses` 数组或单个 `status`、`includeTerminal`、`assignmentState`、`onlyMine`、`claimableOnly`、`assigneeInstanceId`、`assigneeAgentId`、`assigneeTeamId`、`assigneeTeamMemberId`、`assigneeName`、`createdFrom`、`createdTo`、`page`、`pageSize`。显式 statuses 不可与 status/includeTerminal 同传。跨环境身份过滤需带所属实例；onlyMine 使用完整服务端身份，区分同一来源 Agent 的普通会话和不同团队成员。

返回 items/total/page/pageSize；列表正文仅返回最多 300 字符 preview，完整要求由 get_task 获取。canClaim 是查询时的参考，实际认领以事务校验为准。查询不会跨环境聚合。

## 发布与页面创建

publish_task 参数：name、requestId 必填；contentMarkdown、attachmentIds、assignee_ref 可选。assignee_ref 来自 Harness list_agents 的可分配条目，指定后经现有任务派发器投递；未指定则进入待认领池。发布者由服务端注入，不能通过参数伪造。相同发布者、requestId 和参数幂等返回原任务。

页面创建默认选择“发布待认领任务”，不要求可用 Agent。选择“指定智能体”或“指定团队”时沿用原直接分配机制。待认领任务可在页面分配，分配与 Agent 认领竞争同一个数据库事务边界。

任务新增 dispatchMode：OPEN 为任务池，DIRECT 为直接分配；assignee=null 的 OPEN 任务为 UNCLAIMED，DIRECT 为 PENDING_ASSIGNMENT，存在 assignee 为 ASSIGNED。分配情况与 START/IN_PROGRESS/SUSPENDED/COMPLETED/CANCELLED 执行状态分开。

旧记录没有 dispatchMode 时按 DIRECT 读取和筛选，不重写全库或变更旧历史。原外部任务 API 继续要求其配置中的固定目标。

待认领任务的发布、修改正文、挂起、取消、重开和投递重试不会触发自动选人。无人分配的 OPEN 任务不能直接变成 IN_PROGRESS，必须先认领或分配。未认领任务挂起后恢复为 START，重新进入任务池。OPEN 已有执行者后的正文更新、恢复和重开沿用原执行通知机制，继续归原执行者。

HTTP：POST `/api/starweave/v1/tasks` 可省略 target 或传 null；POST `/api/starweave/v1/tasks/publish` 明确发布、不允许 target；POST `/{taskId}/assign` 接收 target、expectedRevision、requestId 和可选 actorName。人工分配后生成 TASK_ASSIGNED，经现有 outbox 自动通知执行者。认领仅由携带可信 Agent 身份的 MCP 工具处理。

## 认领与继续执行

指定任务：`claim_task({taskId,expectedRevision,observedContentVersion,requestId})`，应先读取明细。省略 taskId 时不传版本，由数据库按创建时间、ID 原子领取最早的 OPEN、START、无人认领任务。

服务端在同一事务中检查版本和归属、保存 assignee、增加 revision、保存 CLAIMED 历史及幂等结果。contentVersion 不变，不生成 TASK_ASSIGNED 执行事件。返回 claimed、continueExecution、最新任务明细、版本和执行说明。

成功后继续执行，可组织团队成员协作；开始实际工作时更新 IN_PROGRESS，保存进度和结果，完成前再次 get_task，确认最新要求后更新 COMPLETED。不必等另一条通知，评论和聊天声明不能替代完成状态。

调用会话的执行归属会绑定到认领任务，支持精准挂起和取消；同一活动执行不能覆盖另一项未完成任务。队长模式由队长认领；团队成员仍可查看和发布任务。业务身份来自中心配置/团队运行态，不使用模型填写的名字。

相同 requestId 与参数重试返回原认领任务，不领取第二条；每次重试重新读取当前状态，已挂起/完成/取消时 continueExecution=false，不要求重复执行。幂等范围使用稳定业务身份，不绑定临时凭据或 session ID。

若调用期间会话中断，认领可能已经成功；返回 claimed=true、continueExecution=false、continuationInterrupted=true，并要求使用原 requestId 确认后继续，不另领任务。该机制不承诺进程中断后自动重跑已发生外部副作用的工作。

## 失败与凭据

竞争失败 TASK_ALREADY_ASSIGNED；版本变化 VERSION_CONFLICT/LATEST_CONTENT_REQUIRED；不可认领 TASK_NOT_CLAIMABLE；身份不符 CALLER_NOT_ELIGIBLE；当前另有任务 CURRENT_TASK_ACTIVE；请求参数变化 IDEMPOTENCY_CONFLICT。这些结果不授权继续执行。

NO_CLAIMABLE_TASKS 是正常无任务结果，claimed=false、isError=false，无需立即循环重试。存储异常或请求超时必须以原 requestId 和原参数确认，不得把未知结果当作失败后重新领取。

任务 MCP 为各 client 注入独立的不透明 Authorization Header，绑定服务端调用身份，关闭 client 后撤销。HTTP 和 SSE 均校验凭据；SSE 消息不能借用其他 caller 的 transport session。原有三个工具维持现有业务访问行为，新增写操作和本人筛选需要可信身份。调试日志隐藏 Header 值。

远程执行复用既有 runtime 专属 MCP 转发，Header 必须完整保留；不把 Agent 名称作为身份凭据。新工具说明随 MCP 工具发现提供，已有会话应刷新/重新连接 MCP 以发现新工具和凭据。
