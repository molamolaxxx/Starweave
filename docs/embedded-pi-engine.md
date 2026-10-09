# 内嵌引擎 · Pi

新建智能体默认选择「内嵌引擎 · Pi」。填写模型 ID、OpenAI 兼容 API 的 Base URL 和独立 API Key 即可使用；现有智能体保留原运行引擎。高级设置默认折叠，包含上下文窗口（128000 Token）和最大输出（8192 Token）。上下文窗口包含输入和输出，压缩预算额外预留系统提示及工具结果空间。模型必须支持流式 Chat Completions 和工具调用；图片取决于实际模型能力。

## 分发与构建

Pi 0.75.3 源码快照、ACP 适配参考源码、许可证、锁定依赖及 Node 24.21.0 运行资源位于 `cmd-proxy-app/src/main/resources/embedded/pi/`。项目维护的 ACP 适配层位于 `integration/`；不运行参考适配器的 CLI 或 daemon。

JAR 包含 Linux x64 和 Windows x64 运行包，启动时无需下载、安装 npm/Pi 或配置全局 Pi。Linux 使用 Node 项目 unofficial-builds 的 glibc 2.17 兼容构建；Windows 使用官方 Node 构建。运行包在维护者构建时按发布 SHA-256 核验，启动解压时再核验内嵌资源 SHA-256。目前不包含 ARM 或 macOS 运行包。

```bash
node scripts/build-embedded-pi.mjs
mvn -pl cmd-proxy-client -DskipTests install
mvn -pl cmd-proxy-app -DskipTests package
```

维护者构建需要网络、Node、JDK；从现有资源直接进行 Maven 打包无需重新下载引擎。Maven 不打包工作目录中的 `node_modules` 或测试文件，只打包锁文件、源码快照、适配脚本和压缩运行资源。

运行资源释放到 `$CMD_PROXY_HOME/runtimes/embedded-pi/<版本-平台-指纹>/`，使用跨进程文件锁、校验和、临时目录和完成标记。目录不可变，不在会话运行时覆盖。智能体状态位于 `$CMD_PROXY_HOME/agents/<stateId>/embedded-pi/`；复制智能体产生新的 stateId，重命名保留 stateId。模型凭据仅来自该智能体配置，通过子进程环境传递，不放入启动参数。页面读取和保存响应均使用掩码；服务器保存时保留未修改密钥。

## ACP 适配清单

| 功能 | 实现与验证 |
| --- | --- |
| 初始化 | ACP v1，明确声明 load/image/resource/MCP/list/close/resume 能力 |
| 新建与恢复 | 每会话独立 Pi AgentSession；持久化历史；load 回放当前分支历史，resume 不回放 |
| 关闭与取消 | 取消模型、权限等待、工具和压缩；关闭 MCP；释放 Pi 会话；stdin 关闭退出 |
| 模型配置 | 初始自定义模型；set_config_option 和兼容 set_model；实际请求使用配置的 max_tokens |
| 流式回复 | 文本与思考事件分别映射；通知串行投递，完成前等待队列排空 |
| 工具 | 起止与更新、原始参数、位置、文件 diff、权限请求；失败和超时投影为 failed |
| Shell | Linux POSIX Shell；Windows 系统 PowerShell、UTF-8、EncodedCommand；取消终止子进程树 |
| MCP | stdio、Streamable HTTP、SSE；真实连接、分页发现、工具调用、超时和关闭 |
| Skills | 智能体隔离目录和工作区 `.agents/skills`；不加载全局 Pi 配置或项目插件 |
| 上下文用量 | usage_update；配置窗口与输出额度用于模型和压缩预算 |
| 压缩 | 自动阈值/溢出及 `/compact`；开始/完成/失败使用 ACP v1 工具事件元数据 |
| Harness 重注入 | Provider 识别压缩元数据，复用现有 AcpClient；成功后下一轮重注入，失败不重注入 |
| 内容 | 文本、图片、文本资源和资源链接；音频及无法读取的内容明确返回错误 |
| 异常恢复 | 模型错误脱敏并结束当前回复；取消和请求错误后允许下一轮继续 |

压缩元数据为 `_meta.starweaveCompaction`。当前 ACP v1 SDK 不统一接受 `compaction_update`，因此采用合法工具事件承载；Starweave 将其识别为专用压缩事件，不创建普通工具卡片或工具历史。Provider 同时保留对专用 `compaction_update` 的识别能力。

聊天、团队、记忆、定时任务、观测、渠道、子智能体、睡眠和会话轮转复用现有管理层。模型地址、密钥及预算通过现有「保存并自动刷新智能体」链路应用，不另建配置热更新机制。运行中的服务需要更新 JAR 并重启，源码或磁盘产物更新不会自动改变已运行实例。

## 验证

```bash
node scripts/test-embedded-pi.mjs
node cmd-proxy-app/src/test/js/embedded-pi-browser-test.js
```

前者解压并执行实际捆绑 Node 与依赖，使用临时工作区和模拟 OpenAI/MCP 服务，不依赖真实密钥。GitHub Actions 在 Linux 和 Windows 分别运行。后者使用 Playwright/Chrome 验证桌面和移动端配置表单。Java 测试运行时使用显式临时 `CMD_PROXY_HOME`，避免读取开发机认证和注册配置。
