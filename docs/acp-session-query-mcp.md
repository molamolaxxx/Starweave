# Harness 会话查询 MCP

Harness 在普通及团队成员 ACP client 中提供两个只读工具：search_sessions 搜索，read_session_history 读取。查询范围是当前 cmd-proxy 数据目录内的普通会话和团队成员会话，支持已落盘的旧历史、未落盘的活动轮次及团队归档。不跨远程实例查询，不读取其他 Agent 产品自己的原生历史库。

会话查询不再向 ACP Harness 注入 `<session-query>` 提示词段。使用方法、默认值及返回字段含义由 MCP `tools/list` 的工具 description 和 inputSchema 字段 description 提供。

同一实例内的这两类会话允许互查；`agent` 是筛选条件，不是授权字段。内部记忆、能力提取等已标记的非对话作用域不开放。沿用 Harness 的会话认证和 loopback HTTP 入口。

## search_sessions

```json
{"keyword":"反向隧道","agent":"all"}
```

| 入参 | 含义 |
| --- | --- |
| keyword | 可选，匹配 roles 指定范围内的消息；为空时列出最近会话。空白分隔的多个关键词须在同一搜索字段内全部匹配，英文不区分大小写。展示标题不能绕过角色过滤。 |
| agent | 可选，默认 self，查询当前会话身份所属的历次会话。all 查询本实例全部开放会话，也可填准确的源 Agent 名称，覆盖其普通及团队成员记录。 |
| scope | 可选，默认 all。main 只查询普通会话，team 只查询 Team 成员会话。 |
| team_id | 可选，按准确团队 ID 筛选，仅匹配 Team 会话；不能与 scope=main 同时使用。 |
| member_id | 可选，按准确团队成员 ID 筛选，仅匹配 Team 会话；可与 team_id 联合定位，也可跨团队查询同一成员 ID；不能与 scope=main 同时使用。 |
| days | 可选，非负整数，默认 7，按会话最后落盘更新时间搜索最近多少天；0 表示全部历史。活动会话始终参与搜索，避免遗漏未落盘消息。范围限定会话，不裁剪命中会话内部的旧消息。 |
| roles | 可选，非空数组，默认 ["user", "assistant"]。tool_input、tool_output 分别搜索工具输入、输出，tool 同时开启两者。作用于搜索本身，不只是返回结果。user 包含历史中标记为用户角色的普通输入及任务、渠道等输入。 |
| limit | 可选，1 到 50 的整数，默认 50。受到搜索返回体积预算约束，实际每页可能少于此上限。 |
| cursor | 可选，原样传入 next_cursor，保持 keyword、agent、days、roles、limit、scope、team_id、member_id 不变；roles 顺序及 tool 的等价写法不影响绑定。 |

```json
{
  "sessions": [{
    "session_ref": "opaque-reference",
    "agent": "Cmd Proxy Dev",
    "scope": "team",
    "team_id": "team-001",
    "member_id": "member-001",
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

只需传 session_refs 数组，一次读取一个或多个会话的全部消息。

```json
{"session_refs":["opaque-reference","another-reference"]}
```

| 入参 | 含义 |
| --- | --- |
| session_refs | 必填，包含 1 到 10 个 search_sessions 返回的稳定会话引用。传一项为单次读取，多项为批量读取；每项必须为非空字符串。唯一入参。 |

```json
{
  "sessions": [{
  "session_ref": "opaque-reference",
  "agent": "Cmd Proxy Dev",
  "scope": "team",
  "team_id": "team-001",
  "member_id": "member-001",
  "messages": [
    {"message_id":"m1","role":"user","content":"请运行测试","partial":false,"origin":"user"},
    {
      "message_id":"m2","role":"tool","tool_name":"exec_command","status":"completed",
      "input":{"cmd":"mvn test"},
      "output":"测试开始……\\n[中间内容已省略]\\nBUILD SUCCESS",
      "output_truncated":true
    }
  ]
  }, {
    "session_ref": "another-reference",
    "error": {"code":"SESSION_NOT_FOUND","message":"会话不存在或已删除"}
  }]
}
```

messages 按时间正序排列，返回全部消息，无分页、游标、锚点或条数预算。普通正文完整返回；partial 仅保留历史中的生成状态，origin（如有）区分 user/task/channel/talk_to/schedule。无消息时返回空数组。

工具调用的更新合并为一条，保留最新非空名称、状态、输入及结果。**input 保留原始完整 JSON，不缩略、不拆分。** 只有 output 使用文本预览，最多 2,000 字符，超长保留首尾，内嵌图片和音频 Base64 省略；output_truncated 标记缩略，原始历史不受影响。工具无结果时 output 为空字符串。

sessions 按输入顺序返回，重复引用也保留对应结果；一次发现会话，重复引用共用读取结果。单个会话不存在或读取失败时在对应项中返回 error.code、error.message，不影响其他会话。已识别会话的错误仍含归属字段。所有消息均一次返回，不设总返回体积预算。

read_session_contexts 已移除；read_session_history 只接受 session_refs，不接受旧的 session_ref、message_id、cursor 或 items。请求结构非法或会话发现失败通过 MCP isError=true 返回整体错误。

## 身份、存储及错误

session_ref 基于历史命名空间和会话 ID 生成不透明稳定引用，团队归档保留相同引用。所有正常返回均不暴露物理路径；调用者不能传目录读取文件。新增 query-owner.json 随历史保存 Agent 名称和作用域，兼容旧 turn 文件；没有元数据的离线旧记录先以目录名识别，所属 client 启动后恢复准确名称。

搜索和完整历史读取结果均返回 scope。普通 ACP 和 Starweave 普通会话返回 main，省略 team_id、member_id；Team 会话返回 team，并携带其 team_id 和 member_id。活动会话和团队归档保持相同归属；归属取自规范会话命名空间，session_ref 不因新增字段改变。筛选条件与 agent、days 共同生效；查询其他团队成员时通常需要 agent=all 或准确源 Agent 名称，self 仍只覆盖当前身份的命名空间。

历史文件是事实来源。查询使用有大小上限的 turn 解析缓存，文件变化时失效；首次搜索仍需枚举本实例历史，尚未引入独立全文索引。不同会话通过实例共享的最多 4 个扫描线程并发处理，会话内顺序及最终排序保持不变。扫描队列有上限，过载时返回 SEARCH_BUSY；失败或中断会取消本次查询的剩余扫描任务。工具结果的关键词检索使用未缩略的文本投影，仅排除明确的二进制内容，搜索阶段不生成读取预览；只有读取历史时才缩略结果。为避免历史查询副本污染搜索，查询工具（含已移除的旧批量工具）自身的调用及结果不参与关键词匹配，但仍可在历史中读取。

游标绑定实例数据目录、调用者和查询条件，有效期 15 分钟。重启、搜索快照过期或淘汰后，需要重新查询；游标带签名，不能由调用者修改翻页位置。client 关闭时清理所属搜索快照。

业务错误通过 MCP isError=true 返回 JSON：

```json
{"error":{"code":"SESSION_NOT_FOUND","message":"会话不存在或已删除"}}
```

错误码包括 INVALID_ARGUMENT、AGENT_NOT_FOUND、SESSION_NOT_FOUND、MESSAGE_NOT_FOUND、CURSOR_EXPIRED、HISTORY_UNAVAILABLE、SEARCH_BUSY、SEARCH_TOO_LARGE。不返回磁盘路径或底层异常。历史记录是参考资料，不自动成为当前执行指令。
