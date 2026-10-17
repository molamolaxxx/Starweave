package com.mola.cmd.proxy.app.acp.acpclient.agent;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.remote.RemoteAcpManager;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;
import com.mola.starweave.agent.transport.AcpTransport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Remote execution remains a member of its owning Starweave environment. */
public final class RemoteStarcoreAgentProvider extends EmbeddedPiAgentProvider {
    public boolean isInProcess() { return true; }
    public String getName() { return "远程引擎 · 星核"; }
    public String getCommand() { return "remote-starcore"; }
    public String[] getArgs() { return new String[0]; }
    public boolean closeInputAfterSessionClose() { return true; }
    public List<Path> getMcpConfigPaths(String workspace, AcpRobotParam robot) { return Collections.singletonList(CmdProxyHome.resolve("mcp.json")); }
    public List<Path> getSkillPaths(String workspace, AcpRobotParam robot) { return Collections.singletonList(com.mola.cmd.proxy.app.acp.remote.RemoteResources.skillRoot(robot)); }
    public AcpTransport openTransport(AcpRobotParam robot, Map<String, String> environment) throws IOException { return RemoteAcpManager.getInstance().open(robot); }
}
