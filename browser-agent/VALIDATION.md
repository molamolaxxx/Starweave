# MVP 验证记录

验证日期：2026-10-29。所有浏览器测试均使用隔离的临时配置文件；未部署或重启线上中心。

- `npm --prefix browser-agent run build`：通过，生成可加载的 MV3 扩展。
- `npm --prefix browser-agent test`：5 项通过。覆盖配置校验、资源元数据、输出限额、快照冲突、真实模型流式协议、工具循环、记忆工具隔离、忙碌拒绝、压缩、取消及恢复。
- `npm --prefix browser-agent run test:browser`：真实 Chrome 133.0.6943.98 通过，模拟中心和模型/MCP。实际执行 8 次工具，模型请求 9 次，MCP 调用 1 次。
- `RemoteBrowserIntegrationTest`：5 项通过。真实 WebSocket 注册、配置、ACP 转发、断线、身份冲突、Origin、资源读取权限/路径/符号链接边界、快照备份/拉取/冲突。
- `RemoteBrowserChromeTest`：1 项通过。真实插件连接真实 Java 中心，执行页面读取、填写、点击、JS、截图、中心 Skill 读取和网络 MCP；清空临时浏览器本地快照后从中心恢复，恢复不增加模型调用且页面修改次数保持为 1。
- `mvn -pl starweave-agent-sdk test`：25 项通过。
- 原 Java 远程星核 `RemoteAcpIntegrationTest`：20 项通过；`RemoteResourcesTest` 7 项通过；`MemoryAcpClientEmbeddedPiTest` 1 项通过。
- `mvn -pl cmd-proxy-app test`：971 项，962 项通过、9 项失败、0 错误、0 跳过。失败位于 ConfigUI 的 4 个 HTTP 测试类，涉及认证 401；独立 HEAD worktree 复跑对应 15 项，同样复现这 9 项失败。未将它们归因于浏览器实现，也未修改这些测试或认证行为。
- `mvn -pl cmd-proxy-app -DskipTests package`：通过。依赖完整 JAR 已核对包含浏览器传输类、共用连接接口及当前前端资源，类文件保持 Java 8 格式。
- 插件 ZIP：已核对 Manifest、默认权限及包内脚本与 dist 目录一致。
- `agents.js` 语法检查及 `git diff --check`：通过。

完整应用回归运行之后增加了 Chrome 端到端测试，随后单独运行新增的 6 项中心集成测试，均通过。

## 构建失败修复后的完整验证

此前 9 项 HTTP 失败已定位为测试继承了本机真实 CMD_PROXY_HOME，其中注册配置启用了环境密码。已在 cmd-proxy-app/pom.xml 为 Surefire 配置每次构建独立的 target/test-runtime/<时间戳> 数据目录，不修改产品认证行为。相关 HTTP 与密码保护测试 19 项全部通过。再次执行原发布脚本的完整应用回归：972 项，0 失败、0 错误、0 跳过。

尚未进行 Edge 实机、扩展商店审核、生产模型、生产公网反向代理与用户实际业务站点验收。自动化使用本地模拟模型/MCP，不代表任意网站或模型的任务成功率。

原 build.sh 已完成完整构建和公共下载包发布；源 JAR、部署目录和远端文件哈希一致，远端文件权限为 644；服务器 HTTPS 返回的完整 JAR SHA-256 与发布包一致，公网 MD5 正确。

## 浏览器 stdio MCP 兼容修复

共享配置包含 fetch、groovy 等本机 stdio MCP 时，浏览器会话创建被中心转发层拒绝，外层显示 Invalid ACP message。现仅在浏览器会话转发时跳过宿主 stdio MCP 并记录名称，保留网络 MCP 与内置控制工具；Java 远程节点继续保留原校验。SDK 错误保留底层原因，并在转换前清空帧缓冲，避免失败帧污染下一条消息。

核验现有 Surefire 报告：应用 973 项、SDK 26 项全部通过，零失败、零错误、零跳过。其中浏览器集成测试 6 项、真实 Chrome 与 Java 中心端到端 1 项通过；端到端用例包含宿主 stdio 配置。完整依赖 JAR 已生成并核验包含两处修复，SHA-256 为 `954f22c5f2e1c7821455867606e76734b6bd902c24d70751b34dfbaac6844eee`。

2026-10-29 23:25 核验：本机 PID 691 的运行目录为 `/home/mola/my-application/cmd-proxy`，该目录中的 cmd-proxy.jar 不含此修复，SHA-256 为 `55f0794cf6fe2a0fb090ced2d8a5c81f16120c0a9e1b7d210a00bce56414c7fa`。本次仅核验并补充记录，未替换运行包、重启服务或发布公共下载包；运行态验收仍待部署后进行。

## 2026-10-30 浏览器上下文用量修复

- 仅修改插件：以最近有效 assistant usage（含缓存和输出）为基准，补计之后新增消息；无有效 usage 时估算当前系统提示词、工具定义和消息。
- 截图 Base64 不作为文本计数；未收到模型反馈的图片暂按每张 1200 Token 估算，真实图片用量以模型下一次返回为准。
- 自动压缩和界面上报使用同一计数，压缩完成立即上报新值；恢复旧快照直接从已有 usage 重算。
- `npm test`：10 项通过，覆盖截图体积、缓存、失败响应、快照恢复、压缩后基准及 ACP usage 上报。
- 构建通过；真实 Chrome 133 插件测试通过（模拟中心，8 次浏览器工具调用、网络 MCP 调用、恢复不重放）。本次未启动真实 Java 中心测试，服务端没有改动。
- 对本机已有会话只读重算：原约 84% 的会话为 37,641 Token，即配置的 100 万窗口的约 3.76%。未修改历史或清空会话。

## 2026-10-30 断线续连（0.2.0）

- 插件和中心使用同一后台进程生命周期内的续连令牌，校验原注册身份；保留原逻辑连接和 ACP 实例 60 秒。
- 双向序号、确认和有界未确认缓存补传，重复帧不再次交付；工具调用前等待连接恢复，已有点击或提交不重放。
- 延迟显示插件重连状态，短暂断线不触发智能体刷新、会话替换或执行错误；恢复期结束才按既有故障路径终止执行。
- 远程 session/load 失败不再静默回退 session/new，保留历史记录供重试或手动新建。
- 插件单元测试 14 项通过；Java 连接及集成测试 11 项通过（含真实 Chrome 对接真实 Java 中心）。真实 Chrome 在点击后及空闲时各主动断开一次，原 sessionId 可继续使用，页面修改仅一次，重连后心跳仍工作。
- 浏览器关闭/插件重载/后台回收/中心重启无法续接已销毁进程的执行，仅能从已有快照恢复；未承诺跨进程执行恢复。

- 额外覆盖 400 帧离线积压，以 32 帧确认窗口逐批补传，避免重连补发挤满物理发送队列。
- 最终执行 `/home/mola/my-application/cmd-proxy/build.sh` 成功：根项目 clean package，应用 977 项、SDK 26 项测试零失败，JAR/ZIP/MD5 上传完成。源码产物、本机部署副本、远端 JAR/ZIP 和 MD5 校验一致；脚本未执行服务重启。
- 最终插件 ZIP HTTP 200，SHA-256：80a482b75d308729182d79b7de89efcf8c57e9075b5319f739243f445d4dc7d1。

## 2026-10-30 普通 ACP 宿主工具代理（0.3.0）

本节取代早期浏览器资源专用读取、跳过 stdio MCP 的行为说明。

- 移除 `read_resource`、资源专用 `read` 和插件 MCP 客户端；`read/write/edit/shell` 复用 Java 星核执行器，通过浏览器现有可靠连接交给 Starweave 执行。MCP 配置与凭据留在中心，stdio、HTTP、SSE 统一由中心连接和调用。
- 浏览器 Agent 可保存中心上的真实工作目录，重连不覆盖。Skills、AGENTS.md、记忆使用普通 ACP/内嵌星核路径与处理逻辑。配置页的资源浏览、导入导出使用普通目录，不显示容器同步与冲突入口。系统提示词区分用户浏览器与宿主执行环境。
- 请求绑定已注册 Peer、运行实例和服务端生成的工具会话；重复调用 ID 拒绝再次执行。取消可终止宿主命令并继续后续调用；关闭可中断 MCP 初始化，不等待初始化锁。
- `mvn -pl cmd-proxy-app -am test`：应用 978 项、SDK 26 项、星核 22 项，全部通过，零失败、零错误、零跳过。另行运行新增 `HostedToolSessionTest` 2 项，全部通过。
- 浏览器单元测试 16 项通过；真实 Chrome 133 分别连接模拟中心及 Java 中心的端到端测试通过，覆盖网页输入、点击、JS、截图、普通路径 Skill 读取、代理 MCP、断线续连和中心快照恢复。Java 浏览器链路 7 项集成测试通过，包含 stdio MCP、四工具、取消、重复 ID、工作目录和归属检查。
- ConfigUI 浏览器测试在 1440px、390px 两种宽度通过，覆盖浏览器工作目录修改和保存、宿主代理配置可编辑、普通 Skill 资源入口；原 Java 远程星核目录仍只读。
- `mvn -pl cmd-proxy-app -am -DskipTests package` 与插件构建通过。核对完整 JAR 包含 Java 8 的 HostedToolSession 与当前 ConfigUI 资源；`starweave-browser-agent-0.3.0.zip` 与 dist 内容一致。
- 本次没有部署、重启或更新公共下载包。中心与插件需要配套更新；旧浏览器专用 Skills 与 `/browser/...` 工作目录快照的迁移限制见 README。
- 最终额外复跑真实 Chrome → Java 中心：在 MCP 请求处理期间主动断开连接，加上页面点击后及空闲断线，共覆盖 3 次断线；MCP 调用次数保持 1，恢复后页面修改次数保持 1，测试通过。

## 2026-10-31 分类浏览器工具（0.4.0）

- 以 17 个 `browser_*` 分类工具取代零散浏览器工具，使用 `action` 选择操作。工具列表和参数说明、系统提示词、实时及历史工具标题使用同一分类。宿主四工具和 MCP 名称保持不变。
- 覆盖标签页、窗口、页面与 iframe、鼠标、键盘、表单、等待、弹窗、截图、网络、日志、存储、文件上传、下载、书签、历史和剪贴板。新增 downloads/bookmarks/history/clipboardRead/clipboardWrite/offscreen 权限；剪贴板由独立离屏文档处理。
- 文件上传支持浏览器本机绝对路径、内联内容、Starweave 文件中转（需新版中心 read_files 操作）；中转遵循原宿主读取权限，合计不超过 8 MiB，文件内容不进入模型上下文。未开放聊天文件附件或系统文件选择框自动化。
- 同进程 iframe 使用 contextId，跨进程 iframe 自动附加并使用 frameSessionId。鼠标坐标保持顶层视口语义，frame 内可用表单/键盘/JS/文件/存储工具。
- 导航后先等待目标 URL 再等待 ready。前进后退按 CDP 实际历史条目切换，避免 Chrome Tabs API 跳过未交互页面。按键有 down/up 操作，任务结束会释放仍按下的键。
- 网络请求、日志、截图和文件均有大小/数量边界；等待可取消；不自动重放页面修改。沿用原可靠连接与恢复逻辑。
- 插件单元测试 24 项全部通过；`npm run test:tools` 在真实 Chrome 133 的独立临时扩展和临时配置文件中执行 83 次调用，覆盖全部分类（含真实跨进程 iframe、拖拽、弹窗、剪贴板、下载清理、浏览器本机与中转上传），通过。
- `npm run test:browser` 的模型循环/快照/断线恢复测试通过；真实 Chrome 对接真实 Java 中心同样通过，包含普通宿主文件上传到网页输入框。
- 完整 `mvn -pl cmd-proxy-app -am test`：应用 986 项、SDK 26 项、星核 25 项，零失败、零错误、零跳过。根 reactor package 和插件构建通过。未重启服务或发布中心公共 JAR/ZIP/MD5。
