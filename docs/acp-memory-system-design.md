# ACP 记忆系统设计文档

## 1. 背景与目标

当前 ACP 模块中，每次 session 的对话上下文通过 `ConversationHistoryManager` 以 turn 文件形式落盘，但跨 session 之间没有记忆延续能力。用户在新 session 中需要重复描述偏好、项目背景等信息。

本方案在现有 ACP 架构基础上，集成一套基于文件的长期记忆系统，使主 AcpClient 能够跨 session 感知历史交互中的关键信息。

### 核心设计原则

- **记少不记多**：参考 Claude Code 的设计哲学，记忆系统的失败模式不是"忘记"，而是"记太多"。只提取真正跨 session 有价值的信息
- **概要常驻 + agent 自主读取**：索引概要（含文件绝对路径）始终注入 prompt，主 agent 自行判断是否需要读取明细文件
- **子 Client 隔离**：记忆的提取由独立的子 AcpClient 完成，不干扰主对话流

---

## 2. 系统架构总览

```
┌──────────────────────────────────────────────────────────────┐
│                        主 AcpClient                           │
│                                                              │
│  ┌──────────┐   ┌──────────────────┐   ┌─────────────────┐  │
│  │ 用户输入  │──▶│ 拼接记忆索引概要   │──▶│ sendPrompt()    │  │
│  └──────────┘   │ (含明细文件路径)   │   └────────┬────────┘  │
│                 └──────────────────┘            │            │
│                                                 │            │
│                 主 agent 在对话中自主决定         │            │
│                 是否通过 tool call 读取明细文件 ◀──┘            │
│                                                              │
│              ┌─────────────────────┐                         │
│              │   记忆文件存储层      │                         │
│              │  ~/.cmd-proxy/      │                         │
│              │    memory/          │                         │
│              └──────────┬──────────┘                         │
│                         │ 写入                               │
│                         ▼                                    │
│              ┌─────────────────────┐                         │
│              │   子 AcpClient       │                         │
│              │  (记忆提取专用)       │                         │
│              └─────────────────────┘                         │
└──────────────────────────────────────────────────────────────┘
```

---

## 3. 记忆存储

### 3.1 触发时机

| 触发场景 | 说明 | 优先级 |
|---------|------|-------|
| Session 结束时 | `AcpClient.close()` 或 `acpClearContext` 被调用时触发 | 高（必须实现） |
| 每 N 轮对话后 | 可配置阈值（建议默认 N=5），在 `flushTurn` 中计数触发 | 中（推荐实现） |
| 用户显式指令 | 用户说"记住这个"/"帮我记下"等关键词时立即触发 | 高（必须实现） |

### 3.2 子 AcpClient 的使用方式

创建一个专用的"记忆提取子 Client"，与主 Client 共享 `workspacePath` 但使用独立的 session。

记忆提取子 Client **不加载任何 MCP Server**，因为它只需要分析对话文本并输出结构化 JSON，不需要任何工具调用能力。不加载 MCP 还能显著加快子 Client 的启动速度。

当前记忆提取子 Client 按主会话延迟创建并复用。主 Agent 进入自动休眠或恢复重启前
的休眠状态时，异步通知记忆模块；提取队列完成已提交的工作后，释放对应记忆子 Client
的运行时，保留 Memory session ID 和成功提取游标。休眠请求独立于有界提取队列保存，
队列满时不会丢失。主 Agent 唤醒不会立即启动记忆子 Client，下次实际提取时才恢复。
支持 `session/load` 的 Provider 加载原 Memory session；不支持时新建 Memory session。
恢复失败保留原身份和游标，等待后续提取重试，不自动重跑失败任务。
此行为同时适用于指定模型和指定 Agent 两种执行模式，只作用于该主会话的独立
记忆运行时。所有状态与错误只写后台日志，不新增前端状态、通知或对话卡片。
Dream 整理仍按单次任务创建并关闭，不参与此复用生命周期。


```java
// 在 AcpClientRegistry 中注册一个内部 groupId
String memoryGroupId = groupId + "__memory_extractor";

// 创建子 Client 时，清空 MCP 配置路径，确保不加载任何 MCP Server
AcpClient memoryClient = new AcpClient(command, args, workDir, memoryGroupId);
memoryClient.setMcpConfigPaths(Collections.emptyList());  // 不加载任何 MCP
memoryClient.start();
```

### 3.3 给子 Client 的上下文

子 Client 的 prompt 包含以下内容：

```
你是一个记忆提取助手。请分析以下对话内容，提取值得跨 session 保留的记忆。

## 对话内容
{从 ConversationHistoryManager.getFullHistory() 获取，序列化为文本}

## 已有记忆索引
{当前 MEMORY_INDEX.json 的内容，用于去重和更新判断}

## 提取规则
请按以下分类提取记忆，只提取跨 session 有价值的信息：

1. **user（用户画像）**：用户的角色、技术栈偏好、工作习惯
2. **feedback（行为反馈）**：用户对 agent 行为的纠正或肯定
3. **project（项目上下文）**：项目目标、架构决策、技术约束等非代码可推导的信息
4. **reference（外部引用）**：外部系统地址、文档链接、工具配置等

## 不应存储的内容
- 代码片段、文件路径、项目结构（可从代码库直接获取）
- 临时性的调试过程和中间状态
- 本次对话中已解决且不会复现的问题
- Git 历史可查的变更记录

## 输出格式
请以 JSON 数组返回，每条记忆格式如下：
[
  {
    "action": "ADD" | "UPDATE" | "DELETE" | "NOOP",
    "id": "memory_xxx",          // UPDATE/DELETE 时为已有记忆 ID
    "type": "user|feedback|project|reference",
    "title": "简短标题",
    "summary": "一句话概要（用于索引）",
    "detail": "详细内容，包含 Why 和 How to apply",
    "tags": ["标签1", "标签2"]
  }
]
如果没有值得保存的记忆，返回空数组 []。
```

### 3.4 写入记忆的判断标准

参考 Claude Code 和 Mem0 的设计，判断标准如下：

| 维度 | 应该记忆 | 不应该记忆 |
|------|---------|-----------|
| 时效性 | 跨 session 仍有价值的信息 | 仅当前 session 有用的临时信息 |
| 可推导性 | 无法从代码/git 推导的隐性知识 | 代码结构、文件路径等可直接读取的信息 |
| 重复性 | 用户反复提及或纠正的偏好 | 一次性的操作指令 |
| 影响范围 | 影响未来交互方式的信息 | 仅影响当前任务的细节 |

子 Client 通过 LLM 的理解能力来综合判断，而非硬编码规则。

---

## 4. 记忆格式与文件存储

### 4.1 存储目录结构

```
~/.cmd-proxy/memory/
├── {workspacePath_hash}/              # 按项目隔离
│   ├── MEMORY_INDEX.json              # 记忆索引文件（概要层）
│   ├── memories/                      # 记忆明细目录
│   │   ├── user_profile.md            # 用户画像类记忆
│   │   ├── feedback_code_style.md     # 行为反馈类记忆
│   │   ├── project_auth_rewrite.md    # 项目上下文类记忆
│   │   └── reference_jira_board.md    # 外部引用类记忆
│   └── archive/                       # 归档（过期/删除的记忆）
│       └── ...
└── global/                            # 跨项目的全局记忆（用户偏好等）
    ├── MEMORY_INDEX.json
    └── memories/
        └── user_general_preferences.md
```

### 4.2 为什么不按天/按时间存储

按天存储适合日志场景，但记忆系统的核心需求是"按语义检索"而非"按时间回溯"。参考 Claude Code 的设计：

- 按**类型（type）**组织：user / feedback / project / reference
- 按**主题（topic）**命名文件：`feedback_no_mock_in_tests.md`
- 在文件内记录时间戳用于过期判断

这样做的好处：
1. 同一主题的记忆可以被 UPDATE 而非不断 ADD，避免冗余
2. 检索时可以按类型快速过滤
3. 文件名本身就是语义索引的一部分

### 4.3 索引文件格式（MEMORY_INDEX.json）

索引文件是记忆系统的"目录"，其概要信息始终被注入主 Client 的 prompt 上下文中。
索引中的 `file` 字段使用**绝对路径**，以便主 agent 可以直接通过 tool call 读取明细文件。

```json
{
  "version": 1,
  "lastUpdated": "2026-04-05T10:30:00+08:00",
  "memories": [
    {
      "id": "memory_001",
      "type": "user",
      "title": "用户技术背景",
      "summary": "资深 Java/Kotlin 开发者，熟悉 Spring 生态，正在学习 AI Agent 开发",
      "file": "/home/user/.cmd-proxy/memory/a1b2c3d4/memories/user_profile.md",
      "tags": ["java", "kotlin", "agent"],
      "createdAt": "2026-04-01T14:00:00+08:00",
      "updatedAt": "2026-04-05T10:30:00+08:00"
    },
    {
      "id": "memory_002",
      "type": "feedback",
      "title": "代码风格偏好",
      "summary": "偏好简洁代码，不喜欢过多注释，回答用中文",
      "file": "/home/user/.cmd-proxy/memory/a1b2c3d4/memories/feedback_code_style.md",
      "tags": ["style", "language"],
      "createdAt": "2026-04-02T09:00:00+08:00",
      "updatedAt": "2026-04-02T09:00:00+08:00"
    }
  ]
}
```

### 4.4 记忆明细文件格式（Markdown + Frontmatter）

```markdown
---
id: memory_002
type: feedback
title: 代码风格偏好
tags: [style, language]
createdAt: 2026-04-02T09:00:00+08:00
updatedAt: 2026-04-02T09:00:00+08:00
sourceSession: session_abc123
---

用户偏好简洁的代码风格，回答使用中文。

**Why:** 用户多次要求精简回答，去掉不必要的解释性文字。在第一次交互中明确表示"用中文回复"。

**How to apply:** 生成代码时减少冗余注释，回答问题时直奔主题，所有交互使用中文。
```

选择 Markdown + Frontmatter 的原因：
- 人类可读可编辑（用户可以手动修改记忆）
- Frontmatter 提供结构化元数据，便于程序解析
- 正文部分支持富文本，适合存储复杂的上下文描述
- 与 Claude Code 的 MEMORY.md 方案保持理念一致

---

## 5. 记忆读取

### 5.1 读取策略：概要常驻 + agent 自主读取明细

记忆读取不再使用子 Client 筛选，而是完全依赖主 agent 的判断能力：

```
每次 prompt 发送前：
  读取 MEMORY_INDEX.json → 拼接概要文本（含明细文件绝对路径）→ 注入 prompt
                                          │
                                          ▼
                              主 agent 在对话过程中
                              自行判断是否需要读取某条明细
                              如需要 → 通过 tool call 读取文件路径
```

这样做的核心优势：
- **零额外开销**：不需要启动子 Client 做筛选，省掉一次 LLM 调用
- **agent 更懂上下文**：主 agent 处于完整对话上下文中，比独立的筛选子 Client 更能判断哪条记忆相关
- **改动最小**：只需在 `sendPrompt()` 前拼一段文本，不需要新增筛选相关的类
- **天然按需**：agent 只在真正需要时才读文件，不需要的记忆不会浪费 token

### 5.2 概要注入格式

在每次 `session/prompt` 发送前，将索引概要注入到用户输入前面：

```java
// 在 AcpClient.sendPrompt() 中
String memoryContext = memoryLoader.buildMemoryPrompt(workspacePath);
String enrichedInput = memoryContext + "\n" + userInput;
```

注入到 prompt 中的文本格式：

```
[记忆上下文]
你有以下跨 session 的长期记忆。每条包含概要和明细文件的绝对路径。
概要信息可直接参考；当你需要某条记忆的完整细节时，直接读取对应路径的文件即可。

1. [user] 用户技术背景：资深 Java/Kotlin 开发者，主要负责 ACP 模块
   📄 /home/mola/.cmd-proxy/memory/a1b2c3d4/memories/user_profile.md

2. [feedback] 代码风格：偏好简洁代码，不要过多注释，回答用中文
   📄 /home/mola/.cmd-proxy/memory/a1b2c3d4/memories/feedback_code_style.md

3. [feedback] 测试偏好：JUnit5 + H2，不 mock 数据库
   📄 /home/mola/.cmd-proxy/memory/a1b2c3d4/memories/feedback_testing.md

4. [project] 认证模块重构：安全合规驱动，session token 存储改造
   📄 /home/mola/.cmd-proxy/memory/a1b2c3d4/memories/project_auth_rewrite.md

5. [project] API 迁移计划：v1 API 将在 2026-04-30 下线，新接口走 v2
   📄 /home/mola/.cmd-proxy/memory/a1b2c3d4/memories/project_api_migration.md

6. [reference] 测试覆盖率看板：http://sonar.internal/dashboard/cmd-proxy
   📄 /home/mola/.cmd-proxy/memory/a1b2c3d4/memories/reference_sonar.md
```

### 5.3 完整示例：从用户输入到主 agent 上下文

**场景**：用户积累了 6 条记忆，在新 session 中输入"帮我把上次那个认证模块的单元测试补一下"。

**主 agent 实际收到的完整 prompt**：

```
[记忆上下文]
你有以下跨 session 的长期记忆。每条包含概要和明细文件的绝对路径。
概要信息可直接参考；当你需要某条记忆的完整细节时，直接读取对应路径的文件即可。

1. [user] 用户技术背景：资深 Java/Kotlin 开发者，主要负责 ACP 模块
   📄 /home/mola/.cmd-proxy/memory/a1b2c3d4/memories/user_profile.md

2. [feedback] 代码风格：偏好简洁代码，不要过多注释，回答用中文
   📄 /home/mola/.cmd-proxy/memory/a1b2c3d4/memories/feedback_code_style.md

3. [feedback] 测试偏好：JUnit5 + H2，不 mock 数据库
   📄 /home/mola/.cmd-proxy/memory/a1b2c3d4/memories/feedback_testing.md

4. [project] 认证模块重构：安全合规驱动，session token 存储改造
   📄 /home/mola/.cmd-proxy/memory/a1b2c3d4/memories/project_auth_rewrite.md

5. [project] API 迁移计划：v1 API 将在 2026-04-30 下线，新接口走 v2
   📄 /home/mola/.cmd-proxy/memory/a1b2c3d4/memories/project_api_migration.md

6. [reference] 测试覆盖率看板：http://sonar.internal/dashboard/cmd-proxy
   📄 /home/mola/.cmd-proxy/memory/a1b2c3d4/memories/reference_sonar.md

[Current Time: 2026-04-05 21:30:00 CST (Sunday)]
帮我把上次那个认证模块的单元测试补一下
```

**主 agent 的行为**：

agent 看到概要后，判断第 3 条（测试偏好）和第 4 条（认证模块重构）与当前任务高度相关，于是主动发起两次 tool call 读取明细文件：

```
tool_call: read_file("/home/mola/.cmd-proxy/memory/a1b2c3d4/memories/feedback_testing.md")
→ 得到：JUnit5 + H2，不 mock 数据库，断言用 AssertJ，原因是之前 mock 导致线上 bug

tool_call: read_file("/home/mola/.cmd-proxy/memory/a1b2c3d4/memories/project_auth_rewrite.md")
→ 得到：基于 AuthV2Service，session token 从 cookie 迁移到 Redis + JWT，需覆盖 token 刷新和过期场景
```

然后 agent 基于这些信息，直接用 JUnit5 + H2 为 AuthV2Service 编写测试，覆盖 token 刷新和过期场景，不需要用户再解释任何背景。

第 1、2、5、6 条记忆的明细文件则完全没有被读取，不浪费任何 token。

### 5.4 索引概要的容量控制

- 索引概要控制在 **200 行以内**（参考 Claude Code 的 MEMORY.md 200 行限制）
- 每条概要约 2-3 行（类型+标题+概要+路径），200 行可容纳约 50-60 条记忆
- 超出限制时，按 `updatedAt` 排序，只注入最近更新的记忆，其余省略并提示：
  ```
  ... 还有 12 条较早的记忆未列出。如需查看完整列表，请读取索引文件：
  📄 /home/mola/.cmd-proxy/memory/a1b2c3d4/MEMORY_INDEX.json
  ```

---

## 6. 记忆生命周期管理

### 6.1 记忆衰减与清理

| 策略 | 说明 |
|------|------|
| 时间衰减 | project 类型记忆超过 30 天未更新，标记为"可能过期" |
| 冲突检测 | 当新记忆与旧记忆矛盾时，子 Client 返回 `UPDATE` 或 `DELETE` 操作 |
| 手动管理 | 用户可通过指令"忘记xxx"/"清除记忆"来删除特定记忆 |

### 6.2 记忆容量限制

- 单个项目：最多 50 条记忆（索引概要控制在 200 行以内）
- 全局记忆：最多 20 条
- 单条记忆明细：建议不超过 500 字
- 超出限制时，按 `updatedAt` 排序，归档最不活跃的记忆

---

## 7. 与现有架构的集成点

### 7.1 需要修改的现有类

| 类 | 修改内容 |
|----|---------|
| `AcpClient` | 在 `sendPrompt()` 中注入记忆概要上下文（含文件绝对路径）；在 `close()` 中触发记忆提取 |
| `ConversationHistoryManager` | 增加 turn 计数回调，支持每 N 轮触发记忆提取 |
| `AcpClientRegistry` | 支持创建内部子 Client（记忆提取专用） |
| `AcpProxy` | 增加记忆管理相关命令（查看记忆、删除记忆等） |

### 7.2 需要新增的类

| 类 | 职责 |
|----|------|
| `MemoryManager` | 记忆系统核心管理器，协调提取和存储 |
| `MemoryExtractor` | 封装子 Client 的记忆提取逻辑 |
| `MemoryLoader` | 负责从文件加载记忆索引，构建注入 prompt 的概要文本 |
| `MemoryIndex` | 索引文件的 POJO 模型 |
| `MemoryEntry` | 单条记忆的 POJO 模型 |
| `MemoryConfig` | 记忆系统配置（触发阈值、容量限制等） |

### 7.3 数据流

```
用户发送消息
    │
    ▼
AcpProxy.acpSendMessage()
    │
    ▼
AcpClient.send()
    │
    ├──▶ MemoryLoader.buildMemoryPrompt()     ← 读取索引，构建概要文本（含绝对路径）
    │         │
    │         ▼
    ├──▶ sendPrompt(memoryContext + userInput)  ← 概要注入 prompt
    │
    ▼
对话进行中...
    │
    ├──▶ 主 agent 自主判断，通过 tool call 读取需要的明细文件
    │
    ├──▶ ConversationHistoryManager.flushTurn()
    │         │
    │         ▼ (每 N 轮)
    │    MemoryExtractor.extract()             ← 子 Client 提取记忆
    │         │
    │         ▼
    │    MemoryManager.save()                  ← 写入索引 + 明细文件
    │
    ▼
Session 结束 (AcpClient.close())
    │
    ▼
MemoryExtractor.extract()                     ← 最终一次记忆提取
    │
    ▼
MemoryManager.save()
```

---

## 8. 配置项

记忆配置集成在现有的 `~/.cmd-proxy/acpConfig.json` 中，作为 `memory` 字段。不配置时使用默认值，向后兼容。

```json
{
  "robots": [...],
  "chatterIds": [...],
  "memory": {
    "enabled": false,
    "baseDir": "~/.cmd-proxy/memory",
    "extractIntervalTurns": 5,
    "indexMaxLines": 200,
    "maxEntriesPerProject": 50,
    "maxEntriesGlobal": 20,
    "projectExpireDays": 30,
    "subClientTimeout": 30
  }
}
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enabled` | boolean | `false` | 记忆系统开关，需显式开启 |
| `baseDir` | string | `~/.cmd-proxy/memory` | 记忆文件存储根目录 |
| `extractIntervalTurns` | int | `5` | 每 N 轮对话触发一次记忆提取，0 表示仅 session 结束时提取 |
| `indexMaxLines` | int | `200` | 注入 prompt 的索引概要最大行数 |
| `maxEntriesPerProject` | int | `50` | 单项目最大记忆条数 |
| `maxEntriesGlobal` | int | `20` | 跨项目全局记忆最大条数 |
| `projectExpireDays` | int | `30` | project 类型记忆过期天数 |
| `subClientTimeout` | int | `30` | 记忆提取子 Client 超时时间（秒） |

加载逻辑在 `Main.kt` 的 `startAcp()` 中，解析 `acpConfig.json` 时一并读取 `memory` 字段，构建 `MemoryConfig` 对象传给 `AcpProxy.start()`：

```kotlin
// Main.kt startAcp() 中
val memoryConfig = if (config.containsKey("memory")) {
    config.getJSONObject("memory").toJavaObject(MemoryConfig::class.java)
} else {
    MemoryConfig()  // 全部走默认值
}
AcpProxy.start(groupIdList, robotsJsonStr, chatterIdsJsonStr, groupWorkDirMap, memoryConfig)
```

---

## 9. 参考设计

| 系统 | 关键设计点 | 本方案借鉴 |
|------|-----------|-----------|
| [Claude Code Memory](https://rajrajhans.com/2026/03/claude-codes-memory-model/) | 4 种记忆类型（user/feedback/project/reference）；MEMORY.md 索引 + 独立文件存储；200 行索引限制；"记少不记多"哲学 | 记忆分类体系、索引+明细分层、容量限制策略 |
| [claude-mem](https://github.com/thedotmack/claude-mem) | 自动捕获 session 工具调用，AI 压缩为语义摘要（~500 token），注入未来 session | session 结束时自动提取、压缩存储的思路 |
| [Mem0](https://arxiv.org/html/2504.19413) | 两阶段管道（提取+更新）；向量相似度去重；ADD/UPDATE/DELETE/NOOP 操作 | 记忆的 CRUD 操作设计、去重与冲突处理 |
| [Anthropic Session Memory Compaction](https://platform.claude.com/cookbook/misc-session-memory-compaction) | 长对话的上下文压缩策略 | 对话内容压缩后再提取记忆的思路 |

Content was rephrased for compliance with licensing restrictions.
