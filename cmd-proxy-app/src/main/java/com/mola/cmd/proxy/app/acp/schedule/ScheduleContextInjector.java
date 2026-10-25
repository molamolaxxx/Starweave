package com.mola.cmd.proxy.app.acp.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 定时任务上下文注入器。
 * <p>
 * 在主 Agent 的 sendPrompt() 中，向 prompt 注入定时任务的能力描述和操作格式，
 * 让 LLM 自主判断何时需要设置/管理定时任务。
 * <p>
 * 不注入的场景：
 * <ul>
 *   <li>robot 配置 scheduleEnabled = false</li>
 * </ul>
 */
public class ScheduleContextInjector {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleContextInjector.class);

    /**
     * 构建定时任务能力描述文本，注入到主 Agent prompt 中。
     *
     * @param scheduleEnabled     该 robot 是否启用定时任务
     * @param isScheduleExecution 当前是否为定时任务触发的执行场景；执行场景也需要
     *                            保留该能力上下文，便于后续人工接续会话
     * @return 格式化的能力描述文本，不注入时返回 ""
     */
    public String buildContext(boolean scheduleEnabled, boolean isScheduleExecution) {
        if (!scheduleEnabled) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n<scheduled-tasks>\n");
        sb.append("你可以为用户创建和管理定时任务，在指定时间或按周期发送提醒、触发 Agent 执行。\n\n");
        sb.append("创建定时任务时直接调用 schedule_task MCP 工具；查询、取消或更新定时任务时直接调用 manage_schedule MCP 工具。\n");
        sb.append("</scheduled-tasks>\n");
        return sb.toString();
    }
}
