# Harness 会话查询 MCP

Harness 在普通及团队成员 ACP client 中提供两个只读工具。查询范围是当前 cmd-proxy 数据目录内的普通会话和团队成员会话，支持已落盘的旧历史、未落盘的活动轮次及团队归档。不跨远程实例查询，不读取其他 Agent 产品自己的原生历史库。

同一实例内的这两类会话允许互查；`agent` 是筛选条件，不是授权字段。内部记忆、能力提取等已标记的非对话作用域不开放。沿用 Harness 的会话认证和 loopback HTTP 入口。

## search_sessions

```json
{"keyword":"反向隧道","agent":"all"}
```

| 入参 | 含义 |
| --- | --- |
| keyword | 可选，匹配标题、正文及工具输入和完整结果。为空时列出最近会话。空白分隔的多个关键词须全部匹配，英文不区分大小写。 |
| agent | 可选，默认 self，查询当前会话身份所属的历次会话。all 查询本实例全部开放会话，也可填准确的源 Agent 名称，覆盖其普通及团队成员记录。 |
| cursor | 可选，原样传入 next_cursor，保持其他条件不变。 |

```json
{
  "sessions": [{
    "session_ref": "opaque-reference",
    "agent": "Cmd Proxy Dev",
    "title": "注册中心反向隧道改造",
    "updated_at": "2026-10-10T15:00:00Z",
    "matches": [{"message_id":"message-id","snippet":"反向隧道的讨论片段"}]
  }],
  "next_cursor": null
}
```

每页最多 10 个会话，按最近落盘时间倒序，同时间按稳定引用排序。每个会话最多返回 3 个命中片段；无关键词时 matches 为空。title 来自首条有效用户正文或助手回复，旧记录及未落盘轮次无法确定时间时 updated_at 为 null。无结果时 sessions 为空数组。

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

## 身份、存储及错误

session_ref 基于历史命名空间和会话 ID 生成不透明稳定引用，团队归档保留相同引用。所有正常返回均不暴露物理路径；调用者不能传目录读取文件。新增 query-owner.json 随历史保存 Agent 名称和作用域，兼容旧 turn 文件；没有元数据的离线旧记录先以目录名识别，所属 client 启动后恢复准确名称。

历史文件是事实来源。查询使用有大小上限的 turn 解析缓存，文件变化时失效；第一版搜索仍需枚举本实例历史，尚未引入独立全文索引。工具结果的关键词检索使用完整原始内容，读取预览缩略不影响命中。为避免历史查询副本污染搜索，两个查询工具自身的调用及结果不参与关键词匹配，但仍可在历史中读取。

游标绑定实例数据目录、调用者和查询条件，有效期 15 分钟。重启、搜索列表变化或读取锚点内容发生变化后，需要重新查询；游标带签名，不能由调用者修改翻页位置。

业务错误通过 MCP isError=true 返回 JSON：

```json
{"error":{"code":"SESSION_NOT_FOUND","message":"会话不存在或已删除"}}
```

错误码包括 INVALID_ARGUMENT、AGENT_NOT_FOUND、SESSION_NOT_FOUND、MESSAGE_NOT_FOUND、CURSOR_EXPIRED、HISTORY_UNAVAILABLE。不返回磁盘路径或底层异常。历史记录是参考资料，不自动成为当前执行指令。
