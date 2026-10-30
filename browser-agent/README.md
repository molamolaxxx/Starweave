# Starweave 浏览器星核（MVP）

在 Chrome / Edge 扩展后台运行项目锁定的 Pi 0.75.3 Agent 核心，由 Starweave 管理模型、会话、Skills 和记忆。任务从现有 Starweave 聊天入口发起，页面操作发生在安装插件的浏览器里。

## 构建与安装

需要 Node.js 22+。在仓库根目录运行：

```bash
npm --prefix browser-agent ci
npm --prefix browser-agent run build
```

打开 `chrome://extensions`（Edge 使用 `edge://extensions`），开启开发者模式，点击“加载已解压的扩展程序”，选择 `browser-agent/dist/`。

插件弹窗只需填写 Starweave 地址和智能体名称。中心设置了 `remoteAcp.accessToken` 时，在高级设置填写对应令牌。名称使用字母、数字、点、横线和下划线，不同浏览器配置文件使用不同名称。

保存后，在 Starweave 为自动注册的远程智能体配置模型 API 地址、Key 和模型，并通过现有聊天入口使用。模型接口首版支持与 Java 星核一致的 OpenAI Chat Completions 兼容服务。Key 由中心下发，仅在扩展内存使用，不注入网页、不保存到会话。

浏览器工具和 JS 执行默认开放，不设网站白名单或 JS 开关。Chrome 自身的受保护页面、无痕授权、企业策略等限制仍适用。插件有停止当前执行按钮；已发生的网页修改无法回滚，已进入网页的脚本也不保证随取消立即终止。

## 中心接入

**需要部署本次修改后的中心代码。** Java SDK 的原 TCP 接入保留。

插件请求现有发现入口：

```text
GET /api/remote-acp/discover?transport=websocket
```

中心按需启动浏览器 WebSocket 监听，默认端口 **13531**，路径 `/api/remote-acp/browser/ws`。插件自动发现端口，无需用户填写。直接连接 HTTP 中心时，需要放通此 TCP 端口。

HTTPS 中心请将 WebSocket 路径反向代理到浏览器监听端口，并配置对外 `wss://` URL；浏览器监听本身提供明文 WebSocket，不能直接把 `wss://` 指向该端口。

```json
{
  "remoteAcp": {
    "browserPort": 13531,
    "browserWebSocketUrl": "wss://starweave.example.com/api/remote-acp/browser/ws"
  }
}
```

例如 Nginx 将 `/api/remote-acp/browser/ws` 转发到 `http://127.0.0.1:13531`，开启 HTTP/1.1 和 Upgrade/Connection 头，并给空闲连接设置足够的超时。HTTP 发现地址仍走原 ConfigUI 端口。访问密码、反向代理认证等环境限制需按现有部署放行发现入口；插件首版不实现中心网页登录。

注册沿用 `name + identity` 规则。身份保存在扩展本地，按中心地址和名称区分；不能复制同一身份到多个浏览器充当负载均衡。卸载扩展会丢失身份，重新安装不能静默接管已有同名记录。

## 分类工具（0.4.0）

浏览器工具统一使用 `browser_类别` 名称，通过 `action` 选择具体操作；不再同时暴露旧的零散同功能工具。

| 工具 | action / 能力 |
|---|---|
| `browser_tabs` | list/open/activate/navigate/close/reload/back/forward/duplicate/move/pin/mute/zoom，支持批量关闭与移动 |
| `browser_windows` | list/create/update/close，窗口聚焦、大小、位置、状态及标签页移入新窗口 |
| `browser_page` | read/evaluate/frames/scroll，文字、HTML、元素引用、页面 JS、同进程和跨进程 iframe |
| `browser_mouse` | click/double_click/right_click/hover/drag，元素引用、CSS 选择器或坐标 |
| `browser_keyboard` | press/down/up/type，按键、组合键和文字输入 |
| `browser_form` | fill/select/check/focus，文本、富文本、下拉选项、复选框/单选框 |
| `browser_wait` | element/text/url/ready/delay，可取消，最多 60 秒 |
| `browser_dialog` | get/handle/arm/clear，alert/confirm/prompt/beforeunload；arm 设置下一次弹窗的一次性响应 |
| `browser_capture` | viewport/full_page/element/region，PNG 返回模型，最多 6400 万像素 |
| `browser_network` | start/stop/list/body/clear，网络请求、响应状态与有界正文 |
| `browser_console` | start/stop/list/clear，控制台、JS 错误、浏览器日志 |
| `browser_storage` | list/get/set/remove/clear/cookies/set_cookie/remove_cookie，local/sessionStorage 与当前 frame origin 的 Cookie |
| `browser_files` | set_input/inspect，浏览器本机路径、内联文件内容或 Starweave 文件中转 |
| `browser_downloads` | start/list/pause/resume/cancel/show/remove_file/erase，浏览器电脑上的下载及记录 |
| `browser_bookmarks` | tree/search/create/update/move/remove，书签与文件夹 |
| `browser_history` | search/visits/delete_url/delete_range，浏览记录与指定范围删除 |
| `browser_clipboard` | read/write，浏览器电脑的纯文本剪贴板 |

例如：

```json
{"action":"close","tabIds":[123,124]}
```

上例传给 `browser_tabs`。网页跳转是异步的，先 `browser_wait url` 等到目标地址，再 `browser_wait ready` 等页面加载。`browser_page read` 返回的元素引用在重新观察或导航后应重新获取。`browser_page frames` 返回 `contextId` 和跨进程 `frameSessionId`，可传给页面、表单、键盘、文件和存储工具；鼠标坐标以顶层页面视口为准，iframe 内优先使用表单工具或 JS。

网络和控制台采集先 start，再执行任务，最后 list/body；每个标签页最多保留 300 个请求和 500 条日志，最多保留 16 个标签页的诊断记录。重新附加后需再次 start。响应正文返回最多 200000 字符，不可用或已淘汰时明确报错，不自动重发请求。

文件上传使用网页的文件输入框，无需点击系统文件选择框。`paths` 是浏览器电脑上的绝对路径；`hostPaths` 是 Starweave 执行端上的路径，由中心中转，需配套新版中心；`files` 可直接提供文件名和 Base64。内联与中转文件合计最多 8 MiB，不把中转内容写入模型上下文。下载、书签、历史、剪贴板均作用于浏览器配置文件或浏览器电脑；浏览器受保护页面、系统弹窗、企业策略和下载安全检查仍遵循 Chrome 本身限制。

0.4.0 新增 downloads、bookmarks、history、clipboardRead、clipboardWrite、offscreen 权限。剪贴板通过扩展离屏文档处理。宿主 `read/write/edit/shell` 与 MCP 工具仍由 Starweave 代理执行，作用于执行机器，名称保持不变。0.3.0 已移除的 `read_resource` 不再提供。

同一浏览器一次只执行一个主会话任务，冲突请求明确返回忙碌。中心原权限策略继续生效。记忆提取使用独立的无工具会话，不能操作网页。

## Skills、记忆与会话

在 Starweave 智能体配置中设置执行机器上的真实工作目录，注册和重连不会覆盖已配置目录。默认使用中心进程工作目录。系统提示词明确说明浏览器工具与宿主工具的执行位置、操作系统、工作目录和 Shell。

Skills 沿用普通内嵌星核机制，从工作目录 `.agents/skills/` 和智能体引擎目录 `skills/` 扫描；AGENTS.md 沿用普通星核加载规则。正文、参考文件和记忆明细直接通过标准 `read` 读取真实路径。记忆概要与提取继续沿用普通 ACP 服务，不再进行浏览器专用路径改写。旧版浏览器专用目录中的 Skills 需要通过资源管理导出后导入普通目录；恢复旧会话时使用当前配置的工作目录，保留历史上下文；旧快照目录不同不会阻止恢复。

完整模型上下文和历史存于插件 IndexedDB，模型响应结束后持久化；后台上传中心，备份失败在弹窗明确显示。中心保存于 `remote-resources/<资源集>/browser-sessions/`，与 Java 会话目录隔离。使用哈希和基础版本检查冲突，保留双方内容。浏览器版只恢复 `pi-browser-0.75.3` 格式，不能加载 Java 引擎快照。

模型和网页请求使用浏览器自身网络/代理配置；宿主 Shell 和 MCP 使用 Starweave 执行端的环境和代理配置。

支持新建、恢复、历史重放、取消、模型切换、`/compact` 与按估算上下文大小触发压缩；压缩通过现有 ACP 元数据通知中心。工具界面预览限制 64 KiB，省略媒体 Base64，模型结果与持久化原件保留。

0.2.0 支持同一后台进程内的 60 秒断线续连，必须配合新版中心：连接恢复时保留原 ACP runtime/session ID，不刷新智能体，不插入会话切换卡片。双方按消息序号确认并补传未确认帧，重复帧不再次交付；不重发用户任务或页面操作。断线期间模型生成结果暂存，下一次工具调用等待连接恢复。每方向最多缓存 8192 帧、64 MiB，单帧不超过 32 MiB。

超过恢复期或缓存限制会中断当前执行；浏览器退出、扩展重载、后台被回收或中心重启不能续接旧进程内的执行，只能按原 session ID 恢复持久化上下文。远程原会话恢复失败时保留记录并报错，不再自动创建空白会话。恢复后先重新观察标签页与 DOM。未成功持久化或上传的最后一段数据不保证恢复。旧插件仍可连接新版中心，但不具备续连能力。

## 验证

```bash
npm --prefix browser-agent test
npm --prefix browser-agent run build
npm --prefix browser-agent run test:browser
npm --prefix browser-agent run test:tools
mvn -pl cmd-proxy-app -Dtest=RemoteBrowserIntegrationTest,RemoteBrowserChromeTest test
```

`test:browser` 使用真实安装的 Chrome、真实 MV3 扩展、页面 DOM/CDP，以及模拟中心、模型和 MCP。默认 Chrome 路径 `/usr/bin/google-chrome`，可通过 `CHROME_BIN` 覆盖；Chrome 可执行文件需支持开发扩展加载。

`RemoteBrowserChromeTest` 将真实扩展连接到临时 Java 中心，验证注册、配置、ACP、中心 Skill 读取和备份、模型工具循环及网页修改。缺少 Chrome、构建产物或 npm 开发依赖时该测试跳过，不会自动安装或启动线上服务。

当前暂不包含独立侧栏聊天、聊天文件附件透传、Shell/Node 观测脚本、并行页面任务、系统文件选择框自动化和扩展商店发布。网页文件上传与浏览器下载已由分类工具提供。
