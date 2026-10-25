# Starweave Task 模块契约

2026-10 任务池扩展、六个 MCP 工具及认领执行规则见 [任务池与任务 MCP](starweave-task-pool.md)。以下保留初版设计记录。

2026-09-05 10:50 CST，T1 发布 v1 草案，T2/T3反馈、队长审查。唯一编辑人 T1。

## Java 服务
包 `com.mola.cmd.proxy.app.acp.task`，子包 `service/store/attachment/api/model`。
采用项目现有 fastjson `JSONObject/JSONArray` DTO，所有字段 camelCase。业务服务返回 data，失败抛 `model.TaskException`（getCode/getData/getHttpStatus）。REST/MCP边界负责 envelope。

`TaskService(Path directory, String ownerInstanceId)` implements AutoCloseable。生产 directory 使用 CmdProxyHome.resolve("starweave/tasks").toPath()。

方法签名（均 public）：
- JSONObject create(JSONObject request)
- JSONObject get(String taskId, JSONObject query)
- JSONObject list(JSONObject query)
- JSONObject stats(JSONObject query)
- JSONObject edit(String taskId, JSONObject request)
- JSONObject updateStatus(String taskId, JSONObject request)
- JSONObject addComment(String taskId, JSONObject request)
- JSONObject comments(String taskId, JSONObject query)
- JSONObject history(String taskId, JSONObject query)
- JSONObject retryDelivery(String taskId)
- TaskAttachmentStore getAttachments()
- TaskRepository getRepository()

create: name/contentMarkdown/attachmentIds/target/creatorName/requestId；禁止创建备注。edit: contentMarkdown/attachmentIds/expectedRevision/reopen/requestId；拒绝 name 和未知字段。status: status/expectedRevision/observedContentVersion/requestId/actorName/reopen；MCP必须强制 reopen=false。comment: contentMarkdown/attachmentIds/observedContentVersion/authorName/authorType/requestId。authorType默认 HUMAN，MCP置 AGENT。

状态接口可选携带 `reason` 与 `actorType`；MCP 强制 `actorType=AGENT`。详情额外返回 `statusValues`、`allowedTransitions` 与 `agentAllowedTransitions`。任务 Agent 必须以 `get_task` 的 currentRevision/currentContentVersion/currentStatus 为权威：开始实际工作时持久化 `IN_PROGRESS`，完成最新版本后再次读取并持久化 `COMPLETED`，仅评论或聊天声明不算完成。`SUSPENDED` 要求保存现场并等待新执行事件，`CANCELLED` 要求终止且不得自行恢复。

每个任务 prompt turn 绑定 taskId/eventId/taskEventSeq/revision/contentVersion。挂起或取消仅可对 `activeTaskId` 精确匹配的 BUSY turn 发送 ACP `session/cancel`；不得取消普通对话、其他任务、TalkTo 或定时任务。MAIN 使用中断 pending 在 READY 后投递控制 prompt，Team 使用持久 receipt 重试；Provider 将主动取消表现为异常时仍恢复 READY。

Task DTO: id/ownerInstanceId/name/contentMarkdown/status/revision/contentVersion/target/assignee/creatorName/createdAt/updatedAt/completedAt/attachments；target与assignee为JSON对象。Task读取返回 `{task,currentRevision,currentContentVersion,currentStatus,commentsScope:"CURRENT",comments,history,delivery}`；create/edit/status返回相同结构。comment返回备注对象。

target: `{type:"AGENT"|"TEAM",instanceId,ownerId,agentId,groupId,teamId,mode:"FIXED"|"RANDOM"|"AFFINITY",teamMemberId}`；T2核实并反馈实际稳定配置ID。assignee至少包含 instanceId/ownerId/agentId 或 teamId/teamMemberId，显示快照允许附加。服务持久保存，目标解析与可信路由归 T2。

## REST 与分页
前缀 `/api/starweave/v1/tasks`。沿用 Starweave envelope：`{accepted:true,code:"OK",message:"OK",data:...}`；失败 `{accepted:false,code,message,data:{currentRevision?,currentContentVersion?,currentStatus?}}`。

GET 集合/GET stats；POST 集合；GET/PATCH /{id}；POST /{id}/status；GET/POST /{id}/comments；GET /{id}/history；GET /{id}/history/{revision}；POST /{id}/delivery/retry。

list query: q（名称/ID子串）,status,assignee（assignee JSON子串）,createdFrom/createdTo（UTC ISO）,page（从1）,pageSize（默认20，最大100）。返回 `{items,total,page,pageSize}`。stats使用相同非状态筛选，返回 `{START:n,IN_PROGRESS:n,COMPLETED:n,CANCELLED:n,SUSPENDED:n}`。

comments/history query: cursor（非负整数偏移字符串）、limit（默认20最大100），返回 `{items,total,nextCursor}`（末页null）。get query: revision/contentVersion二选一、commentsCursor/historyCursor/limit；显式分页绝不静默截断。

HTTP 400 INVALID_ARGUMENT/INVALID_TRANSITION；404 NOT_FOUND；409 VERSION_CONFLICT/LATEST_CONTENT_REQUIRED/IDEMPOTENCY_CONFLICT/REOPEN_REQUIRED；413 RESOURCE_LIMIT；503 STORAGE_UNAVAILABLE/NOT_READY。

## 附件
POST /attachments 使用原始二进制 body（不使用multipart），query `fileName`（URL编码），Content-Type为原始MIME。单文件20 MiB，每次业务关联最多10个，单任务所有历史与备注去重累计200 MiB。响应 data为 `{id,fileName,mimeType,size,sha256,createdAt,downloadUrl}`。GET /attachments/{id}/download 始终 attachment disposition + nosniff。ID由服务端生成，不接受路径/URL；附件不可覆盖，历史引用永久保留，暂存24小时可回收。TaskAttachmentStore.upload(String fileName,String mimeType,InputStream)返回JSONObject；open(String id)返回InputStream；metadata(String id)返回JSONObject。

## outbox 存储与原子分配
TaskRepository由服务持有，不直接关闭其共享连接；事务内禁止远程调用。
- List<JSONObject> claimOutbox(String workerId,int limit,long nowMillis,long leaseMillis)：原子领取到期 PENDING/RETRY 或已过期 PROCESSING；按任务seq顺序，存在更早非终结事件时不领取后续事件。返回eventId/taskId/contentVersion/eventType/seq/assignee/state/attempts/leaseToken；每次领取生成独立 fencing token。
- JSONObject assign(String taskId,JSONObject assignee)：CAS首次分配，重试保留原分配；保存任务新revision/history和TASK_ASSIGNED同事务，完成/取消任务不新增可执行通知。
- JSONObject assign(String taskId,List<JSONObject> candidates,String mode)：T2在事务外发现可信候选，数据库事务内按FIXED/RANDOM/AFFINITY选择并保存；AFFINITY同事务检查活跃占用，避免跨任务并发争用空闲优先级。FIXED请只传指定的单候选。
- boolean acknowledge(String eventId,String leaseToken,String receipt)：仅匹配有效领取token的PROCESSING变ACKNOWLEDGED，持久凭据必填。ACK表示接收方持久接纳，不表示执行。
- boolean retry(String eventId,String leaseToken,long nextAttemptAt,String error)：匹配token置RETRY，清理租约。
- boolean discard(String eventId,String leaseToken,String reason)：匹配token置SUPERSEDED。
- List<JSONObject> activeAssignments()：未完成/未取消任务的 taskId/assignee，挂起仍保留占用。
- JSONObject acceptReceipt(String eventId,JSONObject payload)：接收侧持久去重，返回 `{eventId,receipt,duplicate,payload}`；T2接纳后再触发运行，重复不会新增记录。
- List<JSONObject> pendingReceipts(int limit)、boolean completeReceipt(String eventId)：供重启恢复接收工作。

创建事件 TASK_CREATED（待分配，T2 assign后ack）；分配 TASK_ASSIGNED；正文 TASK_CONTENT_CHANGED；状态 TASK_STATUS_CHANGED。TASK_CREATED只用于分配，不作为Agent执行卡。挂起/取消/完成撤销未发出旧执行事件；T2投递前必须再次读取当前任务验证状态和版本。eventId/taskId/contentVersion/eventType/seq是卡片与历史去重基础；详细卡片payload由T2补充。

TaskApiBridge.install(TaskService)/clear(TaskService)/getService()；TaskRestHandler implements HttpHandler，构造 TaskRestHandler(TaskService)。队长将前缀context路由至handler；文件HTTP端点可在独立MCP server复用同一handler。

## HTTP 接线运行约束
任务handler自身限制body字节数。HttpServer创建前由启动层配置请求读取超时（例如JDK HttpServer maxReqTime）和有界executor，防止慢上传无限占据线程。TaskAttachmentStore.cleanupStaged(nowMillis)只清理24小时未引用附件，启动及低频维护调用；关闭先停止dispatch/MCP/HTTP接纳，再clear bridge和close服务。Windows native、打包assembly与实际Provider联调仍需集成验收。
