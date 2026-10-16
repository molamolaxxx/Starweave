# 内嵌引擎 · 星核（StarCore）

`starweave-agent` 是 Pi 0.75.3 的最小 Java 8 行为移植。目标是承接 Starweave
当前内嵌 Pi 的会话、模型、工具、MCP、Skills 和压缩契约，不包含外部应用注册 SDK、
远程 ACP 服务、Pi TUI、Web UI、扩展和包管理器，也不引入第三方 Agent 框架。

## 使用

在智能体「运行引擎」中选择 **内嵌引擎 · 星核**（`JAVA_PI_ACP`）。填写模型、
API 地址及独立 API Key，沿用 `embeddedPi` 配置、stateId、代理和上下文预算。
原来的 `EMBEDDED_PI_ACP` 仍可选，默认值保持不变，便于对照和回退。

Java 路径不检查、下载或启动 Node，不启动独立 Java Agent 进程，也不监听新端口。
业务工具中的 Shell 和 stdio MCP 仍按工具的含义启动相应子进程。

## 模块边界

模块不继承根项目的运行依赖，运行时依赖 Gson 2.10.1 和用于可选 JVM 执行工具的 Groovy 3.0.25；不依赖 cmd-proxy-app、RPC、
Kotlin、Spring、Netty 或 HTTP Server。测试使用 JUnit 4 和 JDK HttpServer。

`EmbeddedAgentRuntime` 在 JVM 内提供 ACP JSON-RPC 字节流。它继承 `Process` 只为兼容
现有 `AbstractAcpClient` 的流与生命周期接口，不创建 OS Agent 进程。
Provider 通过可选的 `openEmbeddedRuntime` 接口接入；其他 Provider 继续走原启动路径。
会话、权限、Team、记忆、定时任务、观测和渠道仍由现有 Starweave 管理层拥有。

| 文件 | 职责 |
| --- | --- |
| `AgentConfig` | 独立模型配置、代理与 no_proxy；不修改 JVM 全局设置 |
| `OpenAiModel` / `Http` | OpenAI 兼容 Chat Completions、SSE/JSON、文本/思考/工具参数增量 |
| `PiAgent` | 工具循环、权限请求、ACP 事件、压缩、取消与历史回放 |
| `Tools` | read/write/edit/shell，工具失败及文件 diff |
| `McpConnection` | stdio、Streamable HTTP、旧 SSE，分页发现及真实工具调用 |
| `Resources` | agent skills、工作区 `.agents/skills`、祖先 `AGENTS.md` |
| `SessionStore` | 原子快照、旧 Pi 分支导入 |
| `EmbeddedAgentRuntime` | 有界 ACP 队列、控制请求及权限应答、关闭生命周期 |

## 功能契约

- 模型只来自当前智能体配置，发送配置的 `max_tokens`，支持切换模型。
- 工具调用经过当前 ACP Client 的权限策略；工具开始/进度/结束事件保序。
- 工具预览限制到 64 KiB 内，Base64 从展示事件移除；存储和模型工具结果保留完整文本。
- 图片输入和 read/MCP 返回图片转换为模型图片块；音频和不可读取的资源明确报错。
- Shell 使用 POSIX `/bin/sh` 或 Windows 编码 PowerShell，支持输出、失败、超时和取消。
- MCP 接收 ACP 注入的 headers/env，保留授权会话；loopback 始终直连，避免代理本机 harness。
- 支持 new/load/resume/list/close；load 回放，resume 不回放；取消后可以继续下一轮。
- Skills 按目录展示名称、说明和文件路径，通过 read 按需读取；支持 `/skill:name`。
- 手动 `/compact`、阈值自动压缩、上下文超限后一次压缩重试。使用
  `_meta.starweaveCompaction` 的开始/完成/失败事件；仅成功提交后发 completed，
  继续复用现有下一轮 harness 重注入逻辑。

压缩保留完整 user turn 边界，避免拆散工具调用和结果。Token 用量优先使用模型返回值，
新输入和本地预算使用估算，不引入 tokenizer。模型提供商的上下文超限错误仍是最终边界。
工具循环每次 prompt 最多 100 轮，MCP/模型/权限等待均有超时。

## 存储与旧会话

同一 stateId 下继续使用原 agent home：

```text
$CMD_PROXY_HOME/agents/<stateId>/embedded-pi/
  skills/
  sessions/       # 原始 Pi JSONL，只读
  java-sessions/  # Java 原子快照
```

Java load 找不到自身快照时，可读取原 Pi JSONL 的当前父链、消息、模型和最近压缩摘要，
转换后另存 Java 快照，不覆盖原文件。取消或崩溃遗留的缺失工具结果在恢复时补齐失败记录。
Java 产生的后续历史不会写回 Node Pi，所以切回原 Pi 不会获得 Java 期间的新消息。
此导入针对当前捆绑 Pi v3 JSONL；不承诺所有历史 Pi 版本或插件自定义消息兼容。

## 验证与构建

```bash
mvn -f starweave-agent/pom.xml install
mvn -pl cmd-proxy-app -Dtest=JavaPiClientTest test
node cmd-proxy-app/src/test/js/embedded-pi-browser-test.js
mvn -pl cmd-proxy-app -DskipTests package
```

模块测试使用本机模拟 OpenAI/MCP，不需要真实 Key、Node 或外部模型服务。
覆盖真实 ACP 字节流、三种 MCP、文件差异、权限、图片、恢复、压缩、长输出和取消。
配置页浏览器测试覆盖两种 Pi 的桌面/移动端配置保存、预算和切换。
跨平台 CI 配置独立运行模块测试；源码支持与实际 Windows/macOS 验收须分别记录。

上游版本、范围和 MIT 声明随模块 JAR 放入 `META-INF`。

## 嵌入其他 Java 应用

通过独立的 `starweave-agent-sdk` Maven 依赖，可将星核嵌入业务 JVM 并作为远程 ACP 注册到 Starweave。参见 [远程星核 SDK 接入说明](remote-starcore-sdk.md)。SDK 不提供业务侧 prompt API。
