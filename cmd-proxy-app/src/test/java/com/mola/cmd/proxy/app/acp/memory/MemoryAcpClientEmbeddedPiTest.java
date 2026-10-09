package com.mola.cmd.proxy.app.acp.memory;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.agent.EmbeddedPiConfig;
import org.junit.Test;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

public class MemoryAcpClientEmbeddedPiTest {
    @Test public void memoryModelOverrideRetainsIndependentPiConnectionAndState() throws Exception {
        AcpRobotParam robot = new AcpRobotParam(); robot.setName("Pi"); robot.setWorkDir("/isolated workspace");
        robot.setAgentProvider("EMBEDDED_PI_ACP"); robot.setModel("main-model");
        EmbeddedPiConfig config = new EmbeddedPiConfig(); config.setStateId("pi-state"); config.setApiKey("test-key"); robot.setEmbeddedPi(config);
        Method method = MemoryAcpClient.class.getDeclaredMethod("buildEffectiveParam", AcpRobotParam.class, String.class); method.setAccessible(true);
        AcpRobotParam effective = (AcpRobotParam) method.invoke(null, robot, "memory-model");
        assertEquals("memory-model", effective.getModel()); assertEquals("main-model", robot.getModel());
        assertSame(config, effective.getEmbeddedPi()); assertEquals(robot.getWorkDir(), effective.getWorkDir());
        effective.getEmbeddedPi().validate(effective.getModel());
    }
}
