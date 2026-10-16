# 快速构建与 APK 验证

所有命令从仓库根目录执行，除非说明进入 `mobile/`。先确认当前环境和源码版本，下面的工具版本是现有工程基线，升级时以实际依赖和官方要求重新核验。

## 环境

- Node.js 22 / npm，Cordova CLI 来自项目依赖，避免调用不明版本的全局 CLI。
- cordova-android 13 使用 JDK 17、SDK Platform 34 和 Build Tools 34.0.0；当前 minSdk 24。
- 项目 JVM 仍有 Java 8 兼容要求。Android 构建单独设置 `CORDOVA_JAVA_HOME`，不要持久修改整个开发机的默认 Java。
- 保留当前 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 和 `GRADLE_USER_HOME` 的原目录。磁盘只读或权限异常时优先修复原挂载点，不搬缓存到 home。

当前工作站曾使用的路径（先检查存在与可写性）：

```text
JDK 17: /usr/lib/jvm/jdk-17.0.2
SDK: /media/mola/Data/kubuntu/android/android-sdk-linux
Gradle cache: /media/mola/Data/kubuntu/gradleCache
```

先用 `command -v node npm java gradle adb`、`java -version`、SDK 目录和环境变量确认状态。发现 JDK 8 默认环境不是升级后端的理由。

## 迭代与交付

首次恢复或依赖改变：

```bash
cd mobile
npm ci --no-audit --no-fund
npm run prepare:android
```

普通 JS/CSS 迭代：

```bash
npm --prefix mobile test
npm --prefix mobile run test:browser
```

浏览器检查需要可解析的 `playwright` 包和 Chrome。默认 Chrome 路径为 `/usr/bin/google-chrome`，可通过 `CHROME_BIN` 覆盖。用 `PREVIEW_PATH` 导出截图；目标目录需要先存在。没有相应依赖时说明原因，不伪造通过。

本机已存在 Chrome/Playwright 时：

```bash
mkdir -p dist/android
PREVIEW_PATH="$PWD/dist/android/settings-preview.png" npm --prefix mobile run test:browser
```

需要新交付时提升版本并同步 lockfile：

```bash
npm --prefix mobile install --package-lock-only --no-audit --no-fund
CORDOVA_JAVA_HOME=/usr/lib/jvm/jdk-17.0.2 bash scripts/build-android.sh
```

脚本要求 SDK 环境已配置，执行 npm ci、连接测试、prepare、Debug build，将产物从 `mobile/platforms/android/app/build/outputs/apk/debug/app-debug.apk` 复制到 `dist/android/starweave-<version>-debug.apk`，并打印 SHA-256。版本从 `mobile/package.json` 获取。

开发中依赖未变且工程已恢复时，可在 `mobile/` 直接运行 prepare/build，以缩短迭代。交付前使用完整脚本确认可复现。浏览器测试目前不由构建脚本自动执行，应对相关界面修改单独运行。

## 下载和缓存故障

先看首次失败的仓库、SDK 或 wrapper 地址，区分 DNS、TLS、代理、依赖版本和文件写入问题。npm/curl 的 HTTP_PROXY 不等于 Java/Gradle 已走代理；必要时在本次构建进程设置 GRADLE_OPTS 的 `http.proxyHost`、`http.proxyPort`、`https.proxyHost`、`https.proxyPort`，并绕过 localhost。使用已核实的本机代理配置，勿在仓库写入账号或代理凭证。

Cordova 生成 wrapper 默认使用 Gradle 8.7。若系统 Gradle 过旧，可用已有兼容 Gradle 的 `GRADLE_HOME` 和 PATH 完成 bootstrap，再由 wrapper 使用项目版本；不要因此无依据地升级 SDK/AGP。需要旧 SDK 目录中的新版工具时，在原 SDK 内安装相应组件，不新建另一套隐蔽 SDK。

## 验证真实产物

将 APK_PATH 设为本次脚本输出的绝对路径，在 JDK 17 环境执行：

```bash
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --verbose --print-certs "$APK_PATH"
"$ANDROID_HOME/build-tools/34.0.0/aapt" dump badging "$APK_PATH"
sha256sum "$APK_PATH"
```

确认包名 `com.mola.starweave`、版本/versionCode 和 SDK 与本次源码一致。需要覆盖安装时比较旧、新包签名证书摘要；相同包名与更高版本号不能弥补签名不一致。

验证关键前端真正进入 APK，而不是只看到磁盘源码更新：

```bash
unzip -p "$APK_PATH" assets/www/page-controls.js | sha256sum
sha256sum mobile/www/page-controls.js
```

启动修改还要比较 `assets/www/app.js`、`assets/www/index.html` 与源码。原生 hook 改动检查 prepare 后的 Java 和实际编译日志；透明开屏修改用 aapt dump xmltree 检查 APK 中的 `res/drawable/ic_cdv_splashscreen.xml` 为透明 fillColor。

没有改共享 ConfigUI 时不必运行 Maven 全量测试或重启服务器。若改共享页面/协议，需按 cmd-proxy 的链路验证配置、源码、JAR、部署文件和运行实例加载版本，不能用 APK 打包成功代替服务端验收。

## 交付与真机

用户要求下载链接时，发现当前可用的文件上传工具（本环境为 `mcp__scp_upload__upload_file`），传入绝对 APK 路径和带版本文件名；不要硬编码其他 Agent 的目录或上传凭证。上传后实际 GET 下载文件，并与本地产物对比 SHA-256。

可用设备在授权范围内验证：

```bash
adb devices
adb -s DEVICE_SERIAL install -r "$APK_PATH"
```

冷启动、有保存地址/首次配置、修改地址、离线和证书错误、主题、键盘、返回、后台恢复是相关变更的实机场景；选择与本次修改有关的检查。没有连接设备时交付可安装包，并明确原生体验未实测。

收尾报告当前版本、APK 下载链接或本地路径、覆盖安装条件、通过/跳过的检查和真机缺口。后续迭代保留包名和原有地址存储 key，避免无意丢失安装数据。
