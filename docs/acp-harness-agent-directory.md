# Harness Agent 查询与任务来源

## list_agents

调用 `list_agents({})`，不接受参数，不分页。一次返回当前调用身份所属范围内已配置的普通 Agent 和未删除团队的成员。没有创建会话的普通 Agent 也会列出。内部子 Agent、记忆和能力执行不作为普通成员列出。

范围由服务端配置和运行态决定：Starweave 仅同实例 Starweave；MolaChat 仅同实例同一 owner 的 MolaChat 普通成员和团队成员。不会因为共享来源 Robot 而跨团队或跨渠道混合身份。身份不能确认时失败，不退回全局目录。agent_ref 是实例、渠道、owner、普通/团队身份的稳定不透明引用，显示名称或 session ID 变化不改变引用；修改普通智能体配置名可能改变其逻辑身份，需要重新查询。

返回 `agents`、`observed_at`。每项包括 agent_ref、instance_id、agent_id（来源智能体 ID）、agent（来源名称）、display_name、scope（main/team）、enabled、state、is_self、execution_location、connection_state、task_assignable、current_session。团队成员额外含 team_id、team_name、member_id、is_captain 和 team_state。

状态复用 CREATED、STARTING、READY、BUSY、SLEEP、ERROR、CLOSING、CLOSED；无客户端为 NOT_STARTED，远程已断线为 UNKNOWN。连接断开与禁用配置单独表达。task_assignable 只表示查询时符合任务分配条件，发布时仍重新校验；MolaChat 条目为 false。队长模式仅队长可以作为任务目标，混合团队沿用现有任务系统限制。

current_session 无会话时为 null；否则包括 session_id、session_ref、history_available。session_ref 由现有会话查询服务生成，可直接传 `read_session_history({session_refs:[...]})`。没有本地历史服务或历史归属不能确认时返回不可读及原因，不用远端 session ID 伪造引用，不暴露文件路径。

历史查询共用归属规则；已知其他范围的 session_ref 也无法绕过校验。新保存的历史 owner 元数据支持团队归档后继续按原 owner 查询。旧历史缺乏可信归属、或多个 MolaChat owner 共用旧普通历史目录时，不推测归属、不开放混合目录，标为不可读。原始文件保持不变。

查询不创建、唤醒 Agent，不发起远程 RPC 或投递消息。返回的是带时间的快照。

## publish_task 指定执行者

`publish_task` 增加可选 assignee_ref，填入 list_agents 返回的 agent_ref，不能传名称、自行构造 ID 或显式 target。目标必须 task_assignable=true；服务端在发布时重新解析并验证，失效返回 TARGET_UNAVAILABLE，要求刷新名单。普通 Agent 映射为 AGENT 目标；团队成员映射为 TEAM/FIXED 目标。无需启动目标会话来查询名单。

不传 assignee_ref：保持 OPEN，无人认领，不投递。传入：保存 DIRECT 和可信目标，经原任务派发器分配并投递，发布者不应重复执行。claim_task 的成功后继续执行且不额外投递规则保持不变。

幂等键绑定原请求，包括 assignee_ref。已成功提交的重试在重新解析目标前返回已保存结果，目标后续删除或当前来源轮次结束不导致重复创建。改为不同目标需使用新的请求编号。

## 来源与页面溯源

ClientTaskCaller 从活动会话已绑定的 taskId 获取来源，传入任务服务；工具参数不允许 sourceTaskId/sourceTask。来源必须属于当前发布者，校验、来源快照、新任务和幂等结果在同一事务内保存。普通对话没有来源；发布后不改变调用者正在执行的原任务。

任务保存 sourceTask 的 id、name、revision 和 contentVersion。get_task 返回 sourceTask（附 deleted）及 derivedTasks（items/total/page/pageSize，默认 20）。GET `/api/starweave/v1/tasks/{id}/derived?page=2&pageSize=20` 可继续读取派生任务。关系只用于溯源，不联动任务状态、完成条件或执行次序。

页面在任务详情中展示来源链接和派生任务链接，支持双向跳转和更多派生记录加载；有未保存正文时先提示。来源删除保留名称和 ID 快照并显示“来源任务已删除”，不提供失效链接。旧任务没有来源字段时正常展示。

工具说明和使用约束由 MCP Server 的工具描述及返回表达，不增加固定 Harness 提示词段落。
