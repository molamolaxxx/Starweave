package com.mola.cmd.proxy.app.acp.acpclient.agent;

import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Agent 提供者接口，抽象底层 agent CLI 的配置。
 * <p>
 * 将 command、args、MCP 配置路径等与具体 agent 实现（如 kiro-cli）绑定的内容
 * 统一收口到此接口，使 AcpClient 体系与具体 agent 解耦。
 * <p>
 * 不同的 agent 实现只需提供此接口的实现类即可接入。
 */
public interface AgentProvider {

    /**
     * Keeps a version-managed Provider runtime protected for the lifetime of its
     * child process. Ordinary providers use the no-op lease.
     */
    interface RuntimeLease extends AutoCloseable {
        RuntimeLease NONE = () -> { };

        @Override
        void close();
    }

    enum PermissionPolicy {
        REJECT,
        ALLOW_ONCE,
        ALLOW_ALWAYS;

        public static PermissionPolicy fromString(String value, PermissionPolicy fallback) {
            if (value == null || value.trim().isEmpty()) {
                return fallback;
            }
            try {
                return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Provider 对上下文压缩生命周期的标准化描述。
     * <p>
     * ACP 本身没有统一的 context-compaction 事件；不同 provider 会通过
     * session/update 文本、自定义 notification 或 usage 更新表达该状态。
     */
    enum CompactionSignal {
        NONE,
        STARTED,
        COMPLETED,
        FAILED,
        /**
         * 压缩后刷新上下文用量。仅当此前收到 {@link #STARTED} 时，Client 才应将其视为完成。
         */
        CONTEXT_USAGE_REFRESHED
    }

    /**
     * agent 可执行命令路径，如 ~/.local/bin/kiro-cli
     */
    String getCommand();

    /**
     * Resolve the executable after {@link #prepareLaunch(AcpRobotParam, Map)}.
     * Version-managed providers use the prepared absolute path from the child environment;
     * ordinary providers retain the historical command.
     */
    default String getCommand(AcpRobotParam robotParam, Map<String, String> environment) {
        return getCommand();
    }

    /**
     * agent 命令参数，如 ["acp"]
     */
    String[] getArgs();

    /**
     * Resolve arguments for the current Robot after launch preparation.
     * Providers whose version is encoded in the command line can override this method.
     */
    default String[] getArgs(AcpRobotParam robotParam, Map<String, String> environment) {
        return getArgs();
    }

    /**
     * 是否提供备用启动命令。主命令启动或初始化失败时，客户端会自动尝试备用命令。
     */
    default boolean hasFallbackCommand() {
        return false;
    }

    /**
     * 备用启动命令，仅当 {@link #hasFallbackCommand()} 为 true 时调用。
     */
    default String getFallbackCommand() {
        throw new UnsupportedOperationException("No fallback command configured");
    }

    /**
     * 备用启动命令参数。
     */
    default String[] getFallbackArgs() {
        return new String[0];
    }

    /**
     * 获取 MCP 配置文件路径列表，按优先级从低到高排列。
     *
     * @param workspacePath 当前工作目录
     * @return MCP 配置文件路径列表
     */
    List<Path> getMcpConfigPaths(String workspacePath);

    /**
     * 获取与指定 robot 对应的 MCP 配置文件路径。
     * 默认沿用 provider 的工作目录级实现；需要 robot 级状态隔离的 provider 可覆写。
     */
    default List<Path> getMcpConfigPaths(String workspacePath, AcpRobotParam robotParam) {
        return getMcpConfigPaths(workspacePath);
    }

    /**
     * 在 Provider 自身 MCP 配置路径之后追加统一共享配置（.cmd-proxy/mcp.json）。
     * <p>
     * 所有 Provider 共享同一份 JSON 格式的 MCP 配置：用户级
     * {@code $CMD_PROXY_HOME/mcp.json}（默认 {@code ~/.cmd-proxy/mcp.json}）与工作区级
     * {@code <workspace>/.cmd-proxy/mcp.json}。共享配置追加在 Provider 自身配置之后，
     * 优先级最低：McpConfigLoader 按列表顺序加载，先加载的 server 占位，因此同名时
     * Provider 自身配置优先。
     *
     * @param paths         Provider 自身的 MCP 配置路径（按优先级从高到低）
     * @param workspacePath 当前工作目录
     * @return 追加共享配置后的完整路径列表
     */
    default List<Path> appendSharedMcpConfigPaths(List<Path> paths, String workspacePath) {
        List<Path> result = new ArrayList<>(paths);
        result.add(CmdProxyHome.resolve("mcp.json"));
        if (workspacePath != null && !workspacePath.trim().isEmpty()) {
            result.add(Paths.get(workspacePath, ".cmd-proxy", "mcp.json"));
        }
        return result;
    }

    /**
     * agent 的显示名称，用于日志和调试。
     */
    String getName();

    /**
     * 从 JSON-RPC 消息中提取 context usage 百分比。
     * 不同 agent 实现有各自的上下文使用量推送格式，由子类覆写解析逻辑。
     *
     * @param msg 完整的 JSON-RPC 消息
     * @return context usage 百分比（0~100），无法提取时返回 -1
     */
    default double extractContextUsage(JsonObject msg) {
        return -1;
    }

    /**
     * 从完整 ACP JSON-RPC 消息中识别上下文压缩生命周期事件。
     * <p>
     * 必须接收完整消息而不是仅接收 session/update 的 update 字段：部分 provider
     * （例如 Kiro）使用自定义 JSON-RPC notification 发送压缩状态。
     *
     * @param msg 完整的 JSON-RPC 消息
     * @return 识别出的压缩信号；不支持或无关消息返回 {@link CompactionSignal#NONE}
     */
    default CompactionSignal detectCompactionSignal(JsonObject msg) {
        return CompactionSignal.NONE;
    }

    /** Whether this client consumes the provider's dedicated ACP compaction updates. */
    default boolean supportsCompactionUpdates() {
        return false;
    }

    /**
     * agent 的 skills 目录相对于 workspacePath 的路径。
     * 如 kiro-cli 为 ".kiro/skills"，opencode 为 ".opencode/skills"。
     */
    default String getSkillsRelativePath() {
        return ".kiro/skills";
    }

    /**
     * 返回该 Provider 会识别的 Skill 根目录，按用户级、工作区级顺序排列。
     * ConfigUI 的 Skill 查看器与运行时使用同一份 Provider 路径约定，避免在
     * 页面层复制不同 Agent 的目录规则。
     */
    default List<Path> getSkillPaths(String workspacePath, AcpRobotParam robotParam) {
        List<Path> paths = new ArrayList<>();
        if (workspacePath != null && !workspacePath.trim().isEmpty()) {
            paths.add(Paths.get(workspacePath, getSkillsRelativePath()));
        }
        return paths;
    }

    /**
     * 根据 robot 配置返回额外的命令行参数。
     * 如 Kiro CLI 通过 --model 参数指定模型。
     *
     * @param robotParam robot 配置，可能为 null
     * @return 额外参数列表，空列表表示无额外参数
     */
    default List<String> getExtraArgs(AcpRobotParam robotParam) {
        return Collections.emptyList();
    }

    /**
     * 根据 robot 配置返回额外的环境变量。
     * 如 OpenCode 通过 OPENCODE_CONFIG_CONTENT 注入模型配置。
     *
     * @param robotParam robot 配置，可能为 null
     * @return 额外环境变量 map，空 map 表示无额外环境变量
     */
    default Map<String, String> getExtraEnv(AcpRobotParam robotParam) {
        return Collections.emptyMap();
    }

    /**
     * 在创建 Agent 子进程前准备 Provider 所需的本地运行时。
     * <p>
     * 默认无需准备；需要一次性安装 CLI、初始化 profile 等操作的 Provider 可覆写。
     * 传入的是最终子进程环境，准备命令应复用其中的 PATH、HOME、代理和 Provider home。
     */
    default void prepareLaunch(AcpRobotParam robotParam, Map<String, String> environment)
            throws IOException {
        // no-op
    }

    /**
     * Prepare a Provider runtime and reserve it before the child process starts.
     * The caller must close the lease only after the corresponding process has
     * actually exited.
     */
    default RuntimeLease prepareRuntimeLaunch(AcpRobotParam robotParam,
                                              Map<String, String> environment)
            throws IOException {
        prepareLaunch(robotParam, environment);
        return RuntimeLease.NONE;
    }

    /**
     * Whether ACP clients using this provider must serialize their startup sequence per workspace.
     * <p>
     * The serialized window ends when the client reaches READY (or startup fails); it does not
     * cover the lifetime of the provider process. Providers that share workspace-scoped bootstrap
     * state can opt in without reducing concurrency for other providers or other workspaces.
     */
    default boolean requiresSerializedWorkspaceLaunch() {
        return false;
    }

    /**
     * 获取 ACP session 创建或恢复后需要立即应用的配置项。
     * <p>
     * 返回值会通过标准 {@code session/set_config_option} 请求逐项设置，适用于
     * 需要在 session 级别选择模型、推理强度等配置的 Agent。
     *
     * @param robotParam robot 配置，可能为 null
     * @return configId 到配置值的映射，空 map 表示无需设置
     */
    default Map<String, String> getInitialSessionConfigOptions(AcpRobotParam robotParam) {
        return Collections.emptyMap();
    }

    /**
     * 是否需要将图片以 base64 image block 内嵌到 prompt 中。
     * 某些 agent 的 Read 工具不支持返回图片内容，需要在 prompt 层面直接传递。
     */
    default boolean needsInlineImages() {
        return false;
    }

    /**
     * 是否为 Provider 在取消/中断等控制流程中生成的展示伪消息。
     * 这类文本不是 Assistant 的业务回答，不应写入历史或推送到对话框。
     */
    default boolean isAgentMessageControlArtifact(String text) {
        return false;
    }

    /**
     * 是否支持通过 {@code session/load} 恢复会话。
     */
    default boolean supportsSessionLoad() {
        return true;
    }

    /**
     * 是否接受 ACP Client 在 session/new/session/load 中传入 MCP Server。
     */
    default boolean supportsClientMcpServers() {
        return true;
    }

    /**
     * 当前 Provider 用于释放单个 session 的协议方法；返回 null 表示没有单 session 关闭方法。
     */
    default String getSessionCloseMethod() {
        return "session/end";
    }

    /**
     * 关闭 session 后是否需要关闭 stdin，以触发 connection-owned ACP Server 优雅退出。
     */
    default boolean closeInputAfterSessionClose() {
        return false;
    }

    /**
     * 权限请求的自动处理策略。既有 Provider 保持历史行为；高风险 Provider 可覆写安全默认值。
     */
    default PermissionPolicy getPermissionPolicy(AcpRobotParam robotParam) {
        String configured = robotParam != null ? robotParam.getPermissionPolicy() : null;
        return PermissionPolicy.fromString(configured, PermissionPolicy.ALLOW_ALWAYS);
    }
}
