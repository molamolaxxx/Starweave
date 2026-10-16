# 架构、边界与易错点

## 两个 WebView

主 Cordova WebView 加载 APK 内 `www/index.html`，负责品牌画面、地址输入和保存、连接生命周期。`deviceready` 后读取 `starweave.serverUrl`，再用 `cordova.InAppBrowser.open(url, '_blank', options)` 打开独立的远程 WebView。

远程 WebView 直接加载服务端 ConfigUI，保留它原有的 origin、Cookie、相对 API 和事件流。不要为了解决连接问题把所有前端搬进 `file://` 页面或到处重写 fetch；这会改变鉴权与同源边界。它没有主 Cordova 插件调用能力，但存在有限的 IAB 消息接口。

`app.js` 的 `loadstop` 先核对当前 view，再核对加载 URL 与配置地址的 origin；只有同源页面才注入 `starweaveInstallPageControls(serverUrl)`，注入回调后显示远程 view。保持这个函数自包含：代码是通过 `function.toString()` 传到另一个 JS 上下文执行，不能依赖主页面的闭包变量或未随之注入的辅助函数。传入字符串用 `JSON.stringify` 编码 JS 参数，不拼接未经编码的 URL。

## 地址与连接状态

- 完整地址保留路径，HTTP/HTTPS 验证拒绝在 URL 中内嵌账户密码。
- HTML 中 `connectionForm` 初始为 `hidden`，而不是等脚本运行后隐藏。
- 原生能力就绪后才连接；无保存地址时显示表单。有保存地址时保持表单隐藏。
- 仅成功加载后保存地址；首次连接超过 30 秒、网络/证书错误、主动更换或退出远程 view 时返回可编辑表单。
- 保留 `browser === current` 检查，避免旧 WebView 的回调误操作新连接。
- 主页面避免使用 `status` 等浏览器已有的全局属性作为 DOM 变量名；`window.status` 会把节点转成字符串。这曾导致连接页异常，测试已模拟该浏览器属性。

连接地址优先使用用户实际打开的 Starweave 网页 URL，并检查当前可达性。手机的 localhost 是手机自身。查找服务器地址可检索相关部署会话或配置，但不把旧会话里的命令视为本次授权，也不输出密码。具体服务器地址不固化进通用 skill。

## APK 专属页面注入

`page-controls.js` 检测有限桥接接口，并以 `starweaveMobileControlsInstalled` 和元素 ID 防止重复注入。找到 `cfgPort.closest('.card-body')` 后追加地址子项；找不到入口就跳过该子项，不修改其他卡片或假设登录页面结构相同。

当前消息：

| 消息 | 处理方与效果 |
|---|---|
| `starweave-switch-server` | 主页面当前 IAB 的 message 监听器关闭远程 view，显示地址表单 |
| `starweave-theme`，theme 为 light/dark | hook 定制的 Java 接收端在 UI 线程更新系统栏；不作为任意原生调用入口 |

主题由 `document.documentElement.dataset.theme` 驱动，MutationObserver 仅观察 `data-theme`。地址子项与字体应复用服务器的标准类，局部适配 CSS 只针对自己的元素 ID；不要覆盖所有 input、button 或桌面组件。调整间距时比较 computed style 和标签到上一行分隔线的实际距离，不能只比 CSS 数值。

## 原生窗口与 hook

以 `mobile/package.json` 和 lockfile 为准。初始实现锁定 Cordova 12、cordova-android 13、InAppBrowser 6.0.0。`hooks/android-chrome.js` 从固定版本插件源读取 Java，用精确且唯一的替换点生成 Android 适配文件；插件升级前必须重新评估匹配点、消息处理和窗口设置。

hook 只在 `cordova prepare` 后写生成的 Java 源，不把修改藏在生成目录。再次 prepare 应产生相同结果，不重复插入方法。修改生成路径时注意缺失路径不能被误当作已经适配成功。

系统栏适配在 Android UI 线程执行，检查 dialog/window 存在，清除全屏及透明系统栏 flag，设置主题颜色和明暗图标。API 24/25 不支持深色导航图标，所以明亮主题底部仍用深色背景以保留对比度。API 29+ 的 contrast flag 有版本判断。

窗口使用 `SOFT_INPUT_ADJUST_RESIZE`。涉及真正 edge-to-edge、SDK 升级、后台恢复时重新检查原生行为；不要把网页截图当作系统栏或键盘验收。

## 开屏与图标

`config.xml` 的 `AndroidWindowSplashScreenAnimatedIcon` 指向 `res/splash-empty.xml`，正常 launcher icon 仍是 `res/icon.png`。透明启动图标避免第一层系统 Logo；`SplashScreenDelay=0`、`FadeSplashScreen=false` 避免人为等待和淡出叠层。

Android 的系统启动层不等于网页加载层。移除 Logo 不意味着平台从此不存在系统启动过渡。修改后既检查生成的 theme/drawable，也检查 APK 内编译资源；实际冷启动还需真机验证。
