package com.mola.cmd.proxy.app.acp.acpclient.agent;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.starweave.agent.AgentConfig;
import com.mola.starweave.agent.EmbeddedAgentRuntime;
import java.io.IOException;
import java.util.Map;

/** Java 8 Pi port, sharing model configuration and ACP projections with the reference Node engine. */
public final class JavaPiAgentProvider extends EmbeddedPiAgentProvider {
    @Override public boolean isInProcess() { return true; }
    @Override public String getName() { return "内嵌引擎 · 星核"; }
    @Override public String getCommand() { return "starweave-java-agent"; }
    @Override public String getCommand(AcpRobotParam robot, Map<String, String> env) { return getCommand(); }
    @Override public String[] getArgs(AcpRobotParam robot, Map<String, String> env) { return new String[0]; }
    @Override public void prepareLaunch(AcpRobotParam robot, Map<String, String> env) throws IOException {
        try {
            if (robot == null || robot.getEmbeddedPi() == null) throw new IllegalArgumentException("请配置星核模型接口");
            robot.getEmbeddedPi().validate(robot.getModel());
        } catch (IllegalArgumentException e) { throw new IOException(e.getMessage(), e); }
    }
    @Override public Process openEmbeddedRuntime(AcpRobotParam robot, Map<String, String> env) throws IOException {
        EmbeddedPiConfig pi = robot.getEmbeddedPi();
        return new EmbeddedAgentRuntime(new AgentConfig(pi.getBaseUrl(), pi.getApiKey(), robot.getModel(), agentHome(robot), pi.getContextWindow(), pi.getMaxTokens(), env));
    }
}
