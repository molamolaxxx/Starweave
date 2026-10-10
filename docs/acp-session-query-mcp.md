# Harness 会话查询 MCP

Harness 在普通及团队成员 ACP client 中提供三个只读工具。查询范围是当前 cmd-proxy 数据目录内的普通会话和团队成员会话，支持已落盘的旧历史、未落盘的活动轮次及团队归档。不跨远程实例查询，不读取其他 Agent 产品自己的原生历史库。

同一实例内的这两类会话允许互查；`agent` 是筛选条件，不是授权字段。内部记忆、能力提取等已标记的非对话作用域不开放。沿用 Harness 的会话认证和 loopback HTTP 入口。

## search_sessions

```json
{"keyword":"反向隧道","agent":"all"}
```

| 入参 | 含义 |
| --- | --- |
| keyword | 可选，匹配 roles 指定范围内的消息；为空时列出最近会话。空白分隔的多个关键词须在同一搜索字段内全部匹配，英文不区分大小写。展示标题不能绕过角色过滤。 |
| agent | 可选，默认 self，查询当前会话身份所属的历次会话。all 查询本实例全部开放会话，也可填准确的源 Agent 名称，覆盖其普通及团队成员记录。 |
| days | 可选，非负整数，默认 7，按会话最后落盘更新时间搜索最近多少天；0 表示全部历史。活动会话始终参与搜索，避免遗漏未落盘消息。范围限定会话，不裁剪命中会话内部的旧消息。 |
| roles | 可选，非空数组，默认 ["user", "assistant"]。tool_input、tool_output 分别搜索工具输入、输出，tool 同时开启两者。作用于搜索本身，不只是返回结果。user 包含历史中标记为用户角色的普通输入及任务、渠道等输入。 |
| limit | 可选，1 到 50 的整数，默认 50。受到搜索返回体积预算约束，实际每页可能少于此上限。 |
| cursor | 可选，原样传入 next_cursor，保持 keyword、agent、days、roles、limit 不变；roles 顺序及 tool 的等价写法不影响绑定。 |

```json
{
  "sessions": [{
    "session_ref": "opaque-reference",
    "agent": "Cmd Proxy Dev",
    "title": "注册中心反向隧道改造",
    "updated_at": "2026-10-10T15:00:00Z",
    "matches": [{"message_id":"message-id","role":"user","source":"user","snippet":"反向隧道的讨论片段"}]
  }],
  "total_sessions": 1,
  "next_cursor": null
}
```

每页最多 50 个会话，目标返回预算为 64 KiB，达到预算时提前分页。按最近落盘时间倒序，同时间按稳定引用排序。每个会话最多返回 2 个命中消息片段，正文截取命中附近最多 300 字符，不拼接远处尾部；role 是原消息角色，source 区分 user、assistant、tool_input、tool_output。同一工具消息的两个字段都命中时只返回一个片段。无关键词时 matches 为空，roles 不隐藏最近会话列表。title 来自搜索角色范围内的首条有效用户正文或助手回复；只有工具命中时可能为“(空会话)”。旧记录及未落盘轮次无法确定时间时 updated_at 为 null。无结果时 sessions 为空数组。total_sessions 是本次快照的命中会话总数。

搜索固定排除明确的图片、音频、Base64、二进制数据字段和 data URL，不基于字符串长度猜测普通代码或正文是否为 Base64；不修改原始历史和读取时的完整工具输入。默认不搜索工具日志，这是相较旧接口的行为变化；排查工具日志时请显式指定 roles。

第一次查询建立搜索结果快照，后续游标不枚举目录、不读取历史文件。新消息及删除不会改变已有快照；需要最新结果时重新搜索，旧命中读取时可能返回 SESSION_NOT_FOUND 或 MESSAGE_NOT_FOUND。快照最多保留 15 分钟，每个调用 client 最多 16 个、总计 8 MiB；超限淘汰旧快照，游标返回 CURSOR_EXPIRED。单次结果超过缓存容量时返回 SEARCH_TOO_LARGE，请缩小范围。

## read_session_history

```json
{"session_ref":"opaque-reference","message_id":"message-id"}
```

| 入参 | 含义 |
| --- | --- |
| session_ref | 必填，搜索返回的会话引用。 |
| message_id | 可选，读取命中消息及其前后上下文，默认读取最近一页。 |
| cursor | 可选，原样传入 before_cursor 或 after_cursor，与 message_id 互斥。 |

```json
{
  "session_ref": "opaque-reference",
  "agent": "Cmd Proxy Dev",
  "messages": [
    {"message_id":"m1","role":"user","content":"请运行测试","partial":false,"origin":"user"},
    {
      "message_id":"m2","role":"tool","tool_name":"exec_command","status":"completed",
      "input":{"cmd":"mvn test"},
      "output":"测试开始……\n[中间内容已省略]\nBUILD SUCCESS",
      "output_truncated":true
    }
  ],
  "before_cursor": "opaque-cursor",
  "after_cursor": null
}
```

消息始终正序排列，每页最多 30 条，正文预算 24,000 字符。普通长消息按字符位置续读，保持同一 message_id，partial 表示分段或仍在生成。origin（如有）区分 user/task/channel/talk_to/schedule。

工具调用的更新合并为一条，保留最新非空名称、状态、输入及结果。**input 保留原始完整 JSON，不缩略、不拆分。** 单条工具输入超过页预算时独占一页，允许超过预算。output 是结果的文本预览，最多 2,000 字符，超长保留首尾，内嵌图片和音频 Base64 省略；output_truncated 标记缩略。原始历史不受影响。

before_cursor/after_cursor 为 null 表示已到对应方向边界。入参可选字段应直接省略，不传 null。工具消息未返回 output 时为空字符串。

## read_session_contexts

```json
{"items":[{"session_ref":"opaque-reference","message_id":"message-id"}]}
```

items 必填，包含 1 到 10 个位置，每项必须包含 session_ref、message_id。一次发现会话，同一会话的多个位置共用一次历史读取。按入参顺序返回，默认命中消息前 2 条、后 3 条，包含工具消息；历史边界和正文预算可能减少实际条数，命中消息会保留在返回页中。before_cursor、after_cursor 可交给 read_session_history 继续读取完整历史。

```json
{
  "contexts": [
    {"session_ref":"opaque-reference","message_id":"message-id","agent":"Agent","messages":[],"before_cursor":null,"after_cursor":null},
    {"session_ref":"missing","message_id":"m2","error":{"code":"SESSION_NOT_FOUND","message":"会话不存在或已删除"}}
  ],
  "remaining_items": []
}
```

每项独立返回错误，不影响其他位置。总返回目标预算为 96 KiB，剩余未返回的请求保留在 remaining_items，原样作为下一次调用的 items；至少返回一项。单条完整工具输入超过预算时允许独占返回，绝不截断输入。工具结果仍使用 read_session_history 的缩略规则。非法请求结构通过 MCP isError=true 返回 INVALID_ARGUMENT；逐项读取失败则在 contexts 对应项内返回 error。

## 身份、存储及错误

session_ref 基于历史命名空间和会话 ID 生成不透明稳定引用，团队归档保留相同引用。所有正常返回均不暴露物理路径；调用者不能传目录读取文件。新增 query-owner.json 随历史保存 Agent 名称和作用域，兼容旧 turn 文件；没有元数据的离线旧记录先以目录名识别，所属 client 启动后恢复准确名称。

历史文件是事实来源。查询使用有大小上限的 turn 解析缓存，文件变化时失效；首次搜索仍需枚举本实例历史，尚未引入独立全文索引。不同会话通过实例共享的最多 4 个扫描线程并发处理，会话内顺序及最终排序保持不变。扫描队列有上限，过载时返回 SEARCH_BUSY；失败或中断会取消本次查询的剩余扫描任务。工具结果的关键词检索使用未缩略的文本投影，仅排除明确的二进制内容，搜索阶段不生成读取预览；只有读取历史时才缩略结果。为避免历史查询副本污染搜索，三个查询工具自身的调用及结果不参与关键词匹配，但仍可在历史中读取。

游标绑定实例数据目录、调用者和查询条件，有效期 15 分钟。重启、搜索快照过期或淘汰、读取锚点内容发生变化后，需要重新查询；游标带签名，不能由调用者修改翻页位置。client 关闭时清理所属搜索快照。

业务错误通过 MCP isError=true 返回 JSON：

```json
{"error":{"code":"SESSION_NOT_FOUND","message":"会话不存在或已删除"}}
```

错误码包括 INVALID_ARGUMENT、AGENT_NOT_FOUND、SESSION_NOT_FOUND、MESSAGE_NOT_FOUND、CURSOR_EXPIRED、HISTORY_UNAVAILABLE、SEARCH_BUSY、SEARCH_TOO_LARGE。不返回磁盘路径或底层异常。历史记录是参考资料，不自动成为当前执行指令。
