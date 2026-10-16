---
name: cmd-proxy-mobile
description: 迭代 Starweave 的 Cordova Android 客户端，处理 APK 专属界面、服务地址与启动流程、WebView 注入、系统栏及键盘适配，构建、验证和交付可覆盖安装的 APK。用于本仓库的移动端改造与 Android 打包；通用后端问题使用 cmd-proxy，其他项目先核对其现有移动端技术栈。
---

# Starweave 移动端迭代

从现有 `mobile/` 工程继续迭代。它沿用 MolaChat 的 Cordova + Android WebView 技术栈，是连接 Starweave 服务端的客户端；Agent、JVM 和 Node 不在手机内运行。

## 快速定位

先确认仓库根目录，运行 `git status --short`，保留已有改动。以下路径均相对仓库根目录：

| 改动 | 入口 |
|---|---|
| 地址表单、品牌加载画面 | `mobile/www/index.html`、`app.css` |
| 保存地址、自动连接、错误恢复、返回操作 | `mobile/www/app.js` |
| APK 专属的管理页面子项、主题消息 | `mobile/www/page-controls.js` |
| 系统栏、键盘窗口、插件 Java 适配 | `mobile/hooks/android-chrome.js` |
| 包名、版本号、SDK、开屏资源 | `mobile/config.xml`、`mobile/res/` |
| 锁定依赖、测试和构建命令 | `mobile/package.json`、`package-lock.json` |
| 连接流程测试 | `mobile/test/connection.test.js` |
| 启动可见性、布局、主题、点击导航检查 | `mobile/test/page-controls-browser.js` |
| 完整可复现构建和产物复制 | `scripts/build-android.sh` |
| 网页与 APK 共用的界面 | `cmd-proxy-app/src/main/resources/configui/` |

原生、启动或桥接改动先读 [架构与界面约定](references/architecture-and-ui.md)。准备构建、排查环境或交付时读 [构建与验证](references/build-and-verify.md)。用户操作说明在仓库 `mobile/README.md`；不要把 README 中的版本当作当前源码版本。

## 选择改动边界

- APK 专属需求优先改 `mobile/`，通过同源页面注入实现，不要求重新发布服务端。
- 网页与 APK 都应生效的需求改 ConfigUI 源码，并区分前端资源、JAR、实际服务实例的生效链路。构建 APK 不会更新服务器网页；部署服务端也不会更新已安装 APK。
- Android 窗口行为通过受版本检查保护的 `after_prepare` hook 维护。不要只修改 `platforms/`、`plugins/` 或 `node_modules/` 中的生成代码，它们会在恢复工程时被覆盖。
- 修改其他项目时先确认现有框架、入口、鉴权和服务地址约定。借用这里的分层和验证方法，不直接覆盖对方配置，也不默认迁移到 Cordova。

## 已确认的 Starweave 交互约定

这些是本项目当前默认；用户后续明确的新要求优先。

- 普通全屏：没有常驻“切换服务”工具栏，保留时间、电量和系统手势区域。IAB 使用 `location=no,fullscreen=no`，系统栏随页面主题变化。
- 服务地址及更换入口放在“系统设置 → 管理页面”中，作为子项；标签复用 `.toggle-label`，字体、颜色和顶部分隔线间距与“全局网络代理”一致。窄屏输入框可收缩，按钮保持完整。
- 地址表单在 HTML 首次绘制前就隐藏。有保存地址时直接连接，只在首次配置、主动更换或异常时显示，避免先显示表单再跳转。
- 系统启动层不显示 Logo：使用透明启动图标、零额外等待、关闭淡出；保留网页内的品牌加载界面，避免两套 Logo 开屏。应用桌面图标仍使用正常图标。
- 左右滑屏菜单切换已经被用户撤回。保留点击菜单和原有滚动，不把此前的滑屏实现恢复为默认。
- 保留输入、键盘、滚动和现有消息节点的行为；原生窗口采用 `adjustResize`。聊天改动还要核对移动端 Enter 换行、发送按钮与输入框高度调整。

## 迭代步骤

1. 读相关入口和测试，判断是 APK、共享网页还是原生窗口问题；对连接问题先检查服务地址、网络、证书和现有配置。
2. 修改拥有该行为的源码。APK 页面注入保持幂等，同源检查和当前 WebView 回调归属检查不能丢失；跨层修改同时核对 JS 消息与 Java 接收端。
3. 按行为选择验证：连接/启动运行 `npm --prefix mobile test`；样式/注入运行 `npm --prefix mobile run test:browser`，并查看截图。若必要依赖缺失，先确认安装位置；不要把跳过检查报告为通过。
4. 开发迭代：依赖未变且工程已恢复时，可在 `mobile/` 运行 `npm run prepare:android`、`npm run build:android`，不用每次重新安装依赖。只修改注入 JS/CSS 不必重建服务端 JAR。
5. 交付新 APK 时同步提升 `config.xml` 的版本与单调递增的 `android-versionCode`、`package.json` 和 lockfile 根版本；用仓库构建脚本生成带版本文件名的产物，核验签名、打包资源和下载文件。
6. 交付包含 APK 路径或下载链接、可覆盖安装条件、实际验证结果和真机检查缺口。保留 Debug 包属性；未配置发布密钥时不称为正式发布签名包。

## 范围与收尾

只执行当前任务授权的构建、上传或部署。仅创建 skill、评估方案或改文档不需要重建 APK。用户要求交付 APK 下载链接时，可用当前可用的文件上传工具上传本次产物并回下载核验；不要顺便重启服务端或发布其他改动。

浏览器测试能证明 DOM、样式和触摸输入路径，不能证明 Android 系统开屏、刘海、IME、后台恢复或实际 WebView 生命周期。没有真机或模拟器时明确说明。必要时用 `adb devices` 确认目标并在授权范围内覆盖安装。
