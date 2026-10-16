# 远程星核 Java SDK

业务应用通过 Maven 嵌入星核，在自己的 JVM 执行模型与工具。Starweave 保存智能体配置、管理会话、权限、记忆和 Team；业务应用主动建立 TCP 连接，不需要开放入站端口。SDK 不提供业务侧 prompt API。

## 引入与启动

要求 Java 8 或更高版本。构建并安装到本机 Maven 仓库：

```bash
mvn -f starweave-agent/pom.xml install
mvn -f starweave-agent-sdk/pom.xml install
```

```xml
<dependency>
  <groupId>com.mola</groupId>
  <artifactId>starweave-agent-sdk</artifactId>
  <version>1.0.0</version>
</dependency>
```

```java
import com.mola.starweave.sdk.StarcoreClient;

StarcoreClient client = StarcoreClient.start(
    "http://your-starweave:10528", "order-service-prod-01");
// 应用生命周期结束时调用：
client.close();
```

`start` 会创建本地数据目录并检查独占锁，网络连接在后台建立，Starweave 离线不阻塞业务启动。初始化本地目录失败仍会抛 IOException。

name 以英文字母或数字开头，包含 1～80 个字母、数字、点、下划线或横线。它在一个 Starweave 环境中唯一，页面不能改名。多副本应用使用不同 name；每个身份的数据目录独占。

默认工作目录为 `user.dir`，数据目录为 `${user.home}/.starweave/agents/<name>/`。生产应用可显式配置：

```java
StarcoreClient client = StarcoreClient.builder()
    .starweaveUrl("http://your-starweave:10528")
    .name("order-service-prod-01")
    .workspace(java.nio.file.Paths.get("/opt/order-service"))
    .dataDirectory(java.nio.file.Paths.get("/data/starcore"))
    .maxRuntimes(8)
    .allowFileWrites(true) // 默认 false
    .allowShell(true)      // 默认 false；以业务应用系统账户执行
    .onStateChange(state -> System.out.println("StarCore: " + state))
    .start();
```

最简调用默认只允许工作区和 SDK skills 目录中的文件读取，写入、edit 和 Shell 由宿主显式开放；中心权限不能突破这个上限。路径策略会检查现有父目录的符号链接，但不是操作系统沙箱，允许 Shell 后应按宿主账户权限评估执行范围。

可通过 `getState()`、`getLastError()` 查询连接状态，或使用 `awaitReady(timeout, TimeUnit.SECONDS)` 等待模型配置通过 SDK 校验。READY 代表配置校验成功，模型服务与 MCP 的实际可用性在创建会话和执行时确认。回调应快速返回。

Spring Boot：

```java
@Bean(destroyMethod = "close")
public StarcoreClient starcoreClient() throws java.io.IOException {
    return StarcoreClient.start("http://your-starweave:10528", "order-service-prod-01");
}
```

## Starweave 配置

首次 SDK 发现接入地址时按需监听 TCP，默认端口 **13530**，与现有网关默认端口 10529、注册中心隧道默认端口 10530 分离。可通过 `remoteAcp.port` 覆盖默认值，服务器需放通对应的入站 TCP 端口。ConfigUI 必须启用；ConfigUI 关闭时不会提供远程接入发现及 MCP 转发入口。

可在 acpConfig.json 配置：

```json
{
  "remoteAcp": {
    "enabled": true,
    "bindHost": "0.0.0.0",
    "port": 13530,
    "publicHost": "starweave.example.com",
    "publicMcpBaseUrl": "https://starweave.example.com"
  }
}
```

`publicHost`、`publicMcpBaseUrl` 可省略，默认分别使用 SDK 提供的 Starweave 主机和 Base URL。映射到不同外部 TCP 端口时通过 `publicPort` 指定。公网/不可信网络可配置 `tlsKeyStore`（PKCS12）、`tlsKeyStorePassword`，SDK 使用 JVM 信任库并检查服务端主机名；接入限制使用可选 `accessToken`，SDK 对应 `.accessToken(...)`。未配置 TLS 的 TCP 用于可信网络，API Key 随配置经该连接下发。HTTP 反向代理需放行 `/api/remote-acp/`，TCP 端口需另外转发；不能把 TCP 连接指向 HTTP 端口。监听地址、端口、TLS 修改后重启 Starweave。

远程星核自动创建 `REMOTE_STARCORE_ACP` 记录，首次可以没有模型/Key，页面显示生效但待配置。配置模型、API Key、记忆等后自动同步。后台约每 2 秒检查配置变化，已有回复完成后走现有单智能体刷新链路；其他智能体不重启。页面手动刷新仍使用既有刷新语义，可能取消该智能体正在执行的任务。

注册只更新 SDK 上报的工作目录、平台和连接状态，不能覆盖中心已保存的模型及 Key。身份凭据保存在数据目录，中心仅保存摘要；相同身份可替换旧连接，不同身份不能接管同名记录。身份文件丢失时，先停止原应用，删除失效记录，再重新注册。删除后再次启动同名应用会新建记录。

生效/失效由心跳和连接维护，用户不能切换。生效记录禁止删除，后端保存时再次核验；失效记录允许删除。心跳间隔 10 秒，约 30 秒无数据失效；SDK 同时检测对端无响应，带退避与抖动重连。心跳不写配置文件，连接状态变化才落盘。Starweave 启动时不信任上次保存的在线标记。

## ACP、MCP、记忆和队伍

每个 ACP 客户端对应独立 runtimeId，通过一条共享 TCP 连接复用。关闭聊天/记忆/Team 的单个实例不会关闭 SDK 连接或退出业务 JVM。宿主默认最多 8 个实例，可调整；超过上限明确报错。

普通聊天、Team 和记忆子客户端沿用现有 ACP 创建入口和配置继承。注册到 A 的远程星核在来源归属上属于 A；本地队伍直接使用该来源，混合队伍继续通过 A 的现有注册中心/共享权限转发。不同成员、聊天与记忆使用独立会话。

MCP 通过 SDK 可访问的 Starweave HTTP/SSE URL 注入。中心本机 Harness、任务等 MCP 仍维持原监听方式，远程通过 runtime 专属、不可枚举的临时路由访问。转发包含 Header、会话 ID 和旧 SSE 的 POST endpoint；实例关闭后路由撤销。第三方外部网络 MCP 保留原 URL。中心配置里的 stdio MCP 不在业务机器启动，创建会话时会明确提示改成网络 MCP。

工具操作业务机器的文件。普通聊天和队伍上传的附件在发送时通过 TCP 暂存到 SDK 数据目录的 `uploads/`，prompt 中替换为远程路径；单文件最多 16 MiB。该目录为保持会话恢复可读性而保留，请按应用存储策略清理。页面原有附件预览/下载仍使用中心保存的原件；业务机器上新生成文件的完整浏览与下载不在第一版范围。Skills 从业务机器 `.agents/skills` 和 SDK 数据目录的 `skills/` 加载。第一版不支持页面对远程 Skills 的导入、导出或文件浏览；避免误操作中心机器的同名路径。记忆数据由 Starweave 管理，默认按智能体隔离。

断线后取消 SDK 中所有运行实例，中心结束对应流，不自动重放任务。已经发生的外部副作用不回滚。重连后复用身份、配置及本地会话存储，由现有会话恢复/替换逻辑处理；恢复失败不会假装保留旧会话。

## 验证

```bash
mvn -f starweave-agent/pom.xml test
mvn -f starweave-agent-sdk/pom.xml test
mvn -pl cmd-proxy-app -Dtest=RemoteAcpIntegrationTest test
node cmd-proxy-app/src/test/js/remote-starcore-browser-test.js
```

上述集成测试使用真实 SDK、TCP、既有 AbstractAcpClient 与模拟模型/MCP HTTP 服务；本机测试不等同于跨机器、防火墙、反向代理、生产模型和跨平台部署验收。Maven install 仅安装到本机仓库，未自动发布 Maven Central 或企业仓库。

### 宿主 JVM Groovy 工具

SDK 默认不开放 `jvm_exec`。业务宿主可在 builder 上调用 `.allowJvmExecution(true)`，并通过 `.jvmBindings(Collections.singletonMap("app", applicationContext))` 注入业务对象；SDK 不依赖 Spring。此开关不能由中心下发配置覆盖。

工具参数为 `script`、可选对象 `args`、`timeoutMs`（默认 10000，最大 60000）。返回执行结果、脚本 println 输出、错误及耗时；返回对象须由脚本提取为 map/list/scalar。使用独立 Binding 和宿主上下文 ClassLoader，不修改全局标准输出。超时只请求中断，不保证终止，禁止自动重试有副作用的脚本。每个会话执行器只允许一个活动脚本，不能通过超时不断创建新线程。此功能不提供方法 trace/watch，且不构成权限沙箱。
