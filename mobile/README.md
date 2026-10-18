# Starweave 移动客户端（Cordova）

Android 与 iOS 共用此 `mobile/` 工程的 `www/`、连接逻辑和页面注入代码。
当前已实现 Android 客户端；iOS 平台尚未添加，需在 Mac 上完成平台配置、构建和真机验证。

沿用 MolaChat 的 Apache Cordova + Android WebView 技术栈，独立包名 `com.mola.starweave`。
安装后填写手机可以访问的 Starweave 网页地址，例如 `https://starweave.example.com` 或
`http://192.168.1.10:10528`，然后连接。已有服务端登录页会正常显示。
地址会在网页成功加载后保存在手机本地，下次启动自动连接，启动期间隐藏地址表单；
仅首次配置、主动更换服务或连接异常时显示地址设置。
「系统设置 → 管理页面 → 服务地址」显示完整服务地址，「更换服务地址」返回地址设置。
没有额外的浏览器工具栏，保留手机时间和电量，状态栏及底部系统栏随页面明暗主题切换。
系统栏占据正常安全区域；键盘弹出时原生窗口缩小，页面不延伸到系统栏或刘海区域。
服务不可达、证书错误或首次加载超过 30 秒会返回设置页，允许修改地址和重试。

菜单通过点击切换，不安装左右滑屏手势。服务地址标签的字体与上方全局网络代理一致。
系统启动层使用透明图标、零额外延时并关闭淡出，避免连续显示两套 Logo 开屏；
连接表单前的 Starweave 品牌页面继续用于服务加载。

客户端使用独立 InAppBrowser WebView 加载服务端页面，保留同源 API、登录 Cookie、事件流
和现有移动端界面；远程页面不能调用主 Cordova WebView 的原生插件。
手机和电脑在同一局域网时使用电脑的局域网 IP；`localhost` 指向手机自身。
服务端需运行且允许手机访问对应端口。HTTP 局域网连接已启用，HTTPS 使用系统证书校验。
Agent、Node 和 JVM 在服务端运行，APK 是移动客户端，需要网络连接。

1.0.4 起，APK 将退后台和回前台事件转发到当前同源服务页面。更新后的 ConfigUI 会在回前台、
页面重新显示或网络恢复时重新核对会话、补齐历史，再续接普通/团队会话事件流；后台已结束的回复也会同步完整内容。
草稿、已加载历史和上滑阅读位置保留，不通过整页重载恢复。此修复需要同时更新 APK 与服务端前端资源，
仅安装新 APK 不会更新服务器页面。Android 实际后台恢复效果仍需在目标设备上验证。

1.0.5 起，会话中的新窗口链接、站外链接和下载由系统浏览器接管，应用内页面和锚点继续在当前会话中打开。
APK 下载也会转交系统浏览器。浏览器中的登录状态与应用独立，受登录保护的附件可能需要重新登录。
此链接处理只需更新 APK；Android 系统浏览器唤起仍需目标设备验收。

1.0.6 起，APK 关闭 WebView 默认的矩形触摸高亮，按钮和链接使用轻微透明度变化提供按压反馈；
保留键盘焦点、输入框聚焦和文字选择。此调整只需更新 APK，不依赖服务端发布。

## Android 构建

- Node.js 22、npm
- JDK 17（`JAVA_HOME` 或 `CORDOVA_JAVA_HOME`）
- Android SDK Platform 34、Build Tools 34.0.0、Platform Tools
- Gradle（Cordova 会生成 Gradle 8.7 wrapper）

```bash
# 在仓库根目录执行；路径根据本机环境设置
CORDOVA_JAVA_HOME=/path/to/jdk-17 ANDROID_HOME=/path/to/android-sdk bash scripts/build-android.sh
```

脚本安装锁定依赖、测试连接流程、恢复 Android 工程并构建。
产物为 `dist/android/starweave-1.0.6-debug.apk`，支持 Android 7.0 / API 24 及以上。
该包由本机构建环境的 Android debug key 签名，可直接安装用于验证。
正式发布应使用独立、持久保存的发布签名配置；不要把 keystore 或密码提交到仓库。
此构建目标是直接安装 APK，未配置应用商店发布。

```bash
adb install -r dist/android/starweave-1.0.6-debug.apk
```

`config.xml`、`www/`、`res/` 和 `package-lock.json` 是源文件；
`platforms/`、`plugins/` 和 `node_modules/` 由 Cordova 恢复，不提交。
桌面 ConfigUI 和 MolaChat 工程无需修改。

`www/page-controls.js` 只在 APK 内注入同源页面，增加客户端设置与主题消息。
`hooks/android-chrome.js` 在每次 `cordova prepare` 后对固定版本 InAppBrowser 6.0.0
生成的 Java 源码应用窗口适配：状态栏、导航栏颜色及图标明暗，和键盘 `adjustResize`。
升级插件前必须核对该适配；脚本在版本或源码不匹配时明确失败。

`npm test` 检查启动、连接、异常恢复及切换服务流程。
安装 Playwright 和 Chrome 的环境可运行 `npm run test:browser`，检查服务地址子项、
主题同步、行间距、滑屏撤回及点击导航。浏览器检查不能替代手机原生窗口实测。

## iOS 开发接入（Mac）

拉取本仓库后直接复用 `mobile/`，不要另建一份网页代码。安装 Node.js 22、Xcode
及所选 cordova-ios 版本要求的工具，按 [Cordova 官方 iOS 指南](https://cordova.apache.org/docs/en/latest/guide/platforms/ios/index.html)
核对工具版本。

```bash
cd mobile
npm ci --no-audit --no-fund
npm test
npx cordova platform add ios
npx cordova requirements ios
npx cordova build ios
```

用 Xcode 打开生成的 `platforms/ios/` 下的 workspace，配置开发团队与签名后在模拟器或
iPhone 上运行。确定兼容的 cordova-ios 版本后，提交 `package.json`、`package-lock.json`
及 `config.xml` 的源码配置；生成的原生工程、个人签名配置、证书和密码不要提交。

iOS 适配重点：

- `www/app.js` 中按平台配置 InAppBrowser 参数；iOS 需要 `toolbar=no` 隐藏默认工具栏。
  `fullscreen`、`hardwareback` 等 Android 参数不能作为 iOS 窗口适配依据。
- 复用 `www/page-controls.js` 的服务地址入口与有限消息协议。它已兼容 iOS 的
  `webkit.messageHandlers.cordova_iab`；系统栏主题的原生接收与应用需要另做 iOS 实现。
- `hooks/android-chrome.js` 仅用于 Android 生成的 Java 源码。iOS prepare 必须跳过
  Android 定制，即使开发目录中同时存在 Android 平台；不要直接修改生成目录保存适配。
- 在 `config.xml` 的 iOS 平台配置中补齐图标、启动画面和网络策略；HTTP 服务、局域网
  地址与 HTTPS 证书需分别验证，不沿用 Android Manifest 的明文网络配置。
- 验证安全区域、状态栏、键盘与聊天输入、滚动、后台恢复、自动连接、切换服务、
  登录 Cookie 与事件流。保持无常驻原生工具栏、通过设置更换服务的交互。

主 Cordova WebView 管理连接，独立 InAppBrowser 加载远程服务；继续保留同源注入检查、
当前 WebView 回调归属检查和注入幂等性。共享网页修改应同时跑连接测试与浏览器检查，
原生窗口行为分别在 Android 和 iOS 设备上验收。此 Linux 环境不提供 iOS 构建验证。
