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

工具操作业务机器的文件。普通聊天和队伍上传的附件在发送时通过 TCP 暂存到 SDK 数据目录的 `uploads/`，prompt 中替换为远程路径；单文件最多 16 MiB。该目录为保持会话恢复可读性而保留，请按应用存储策略清理。页面原有附件预览/下载仍使用中心保存的原件；业务机器上新生成文件的完整浏览与下载不在第一版范围。Skills 继续从业务工作目录 `.agents/skills/`（以及兼容的 SDK `skills/`）扫描；新版 SDK 会将工作目录 `.agents/skills/` 与 Starweave 的智能体资源目录双向同步。兼容的 SDK `skills/` 仍可读取，但不属于同步目录。记忆正文同步到 `<SDK 数据目录>/managed/memory/`，中心索引和注入路径会映射到容器路径。本地星核维持原目录扫描和记忆逻辑。

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

## 远程 Skills 和记忆双向同步

需同时更新中心和 SDK。注册握手以 `resourceSyncVersion=1` 协商；旧客户端/中心沿用原 ACP 行为，不会假装已经具备同步能力。

- Skills：业务工作目录 `.agents/skills/`，Agent 和人工在这里的创建、修改、删除都会参加同步；不增加额外的 Skill 写入说明提示词。
- 记忆：`<SDK 数据目录>/managed/memory/`，仅此类资源放在 managed 中。容器索引为 `index.json`，由中心生成，只读；正文支持写回并更新概要和中心索引。中心原有 `MEMORY_INDEX.json` 路径不变。
- 中心：Skills 保存在 ACP 配置文件旁的 `remote-resources/<稳定资源集 ID>/skills/`；记忆继续使用原记忆服务的工作区/scope 目录。资源集 ID 来源于已保存的智能体 stateId，不使用临时 runtimeId。
- 每约 3 秒扫描一次，单独线程处理文件；通过现有 TCP 的独立队列、32 KiB 分块传输。创建远程执行实例前等待一轮同步确认。
- 只同步明确资源根内的普通文件；不跟随符号链接、路径穿越或隐藏文件。每文件最大 512 KiB、每资源集最多 1000 文件且总内容最多 8 MiB。脚本文件内容同步，权限位不作为同步内容。
- 初始空副本只恢复中心内容，不发送批量删除。保留 SDK 状态但工作目录重新变空的冷启动也会恢复；运行中的删除仍会提交。删除记录阻止新容器从旧镜像静默复活已删除的文件。
- 用内容哈希作修改前版本校验，上传重试幂等。冲突保留中心和容器两份，不按时间覆盖。智能体资源弹窗可查看冲突、保留中心版或采用容器版；再次编辑本地冲突文件可提交合并结果。中心版本变化后旧的冲突选择会被拒绝。
- 中心文件使用原子替换，记忆索引与原记忆服务共用存储锁。覆盖或删除的旧内容保留在资源 history/记忆 archive 下；冲突记录和副本在中心及 SDK 的 resource-conflicts 下保留，供排障和恢复。
- 会话开启时重新发现 Skill 清单；新增 Skill 后请新建/刷新会话。现有会话不会主动重写已有模型上下文；文件读操作使用同步后的当前文件，不提供整个会话的版本快照隔离。
- 页面查看远程 Skill/记忆时展示中心持久副本，容器离线也可查看。页面显示同步状态与冲突数量。导入、导出远程 Skill 使用中心资源根，不会误读中心机器上与远端工作目录同名的路径。

容器本地写入成功不等于中心已持久保存；未同步就销毁的修改无法保证恢复。状态只表明最近一次完成的同步，不表示网络断开后的本地修改已经保存。

本改造复用现有 name/identity 注册规则：恢复同一智能体时仍需使用其被接受的身份数据；它没有放开同名多节点注册或自动继承不同 name 的资源。容器无持久化身份时，需另行解决稳定注册身份问题，不能仅凭本次文件同步绕过身份冲突检查。

## 远程观测执行

开启观测能力后，中心通过共享连接发送观测脚本，SDK 在自己的 `workspace` 下启动本机 Node 子进程执行，并返回结果、日志、错误及耗时。宿主必须安装 Node 并设置 `.allowShell(true)`，否则明确返回权限错误。继承宿主环境，并应用中心下发的该智能体代理环境配置。

观测使用独立的有界工作线程，最多并发 4 次，不占用模型 runtime，不影响会话睡眠状态。脚本最多执行 20 秒，脚本上限 256 KiB，结果上限 1 MiB；中心等待远端响应最多 25 秒。断线或取消会中断关联观测；重连后不接收旧连接结果。中心不向旧 SDK 下发观测，也不在失败时改为本机执行。

协议通过注册帧中的 `observationVersion: 1` 和 `observationAllowed` 声明能力，使用 `observation_execute`、`observation_result`、`observation_cancel` 请求与响应，并用 `requestId` 关联。大脚本与结果复用分块传输，避免阻塞心跳。更新此能力需要同时更新中心、`starweave-agent` 和 `starweave-agent-sdk`。
