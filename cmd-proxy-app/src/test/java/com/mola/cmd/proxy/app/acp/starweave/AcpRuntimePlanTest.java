package com.mola.cmd.proxy.app.acp.starweave;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class AcpRuntimePlanTest {

    @Test
    public void localMainRobotStartsCoreWithoutMolaChatChatter() {
        AcpRobotParam robot = robot("Local", true, false, false);

        AcpRuntimePlan plan = AcpRuntimePlan.build(
                Collections.emptyList(), Collections.singletonList(robot),
                Collections.emptyList());

        assertEquals(0, plan.getMolaChatTargetCount());
        assertEquals(Collections.singletonList("Local"),
                plan.getStarweaveEligibleRobots());
        assertTrue(plan.shouldStartCoreRuntime());
    }

    @Test
    public void molaChatTargetCountKeepsCartesianProduct() {
        AcpRuntimePlan plan = AcpRuntimePlan.build(
                Arrays.asList("one", "two"),
                Arrays.asList(robot("A", true, false, false),
                        robot("B", true, false, false)),
                Collections.emptyList());

        assertEquals(4, plan.getMolaChatTargetCount());
        assertEquals(2, plan.getStarweaveEligibleRobots().size());
    }

    @Test
    public void teamOnlyAndDisabledRobotsDoNotCreateLocalTargets() {
        AcpRuntimePlan plan = AcpRuntimePlan.build(
                Collections.emptyList(),
                Arrays.asList(robot("Team", true, false, true),
                        robot("Off", false, false, false)),
                Collections.emptyList());

        assertTrue(plan.getStarweaveEligibleRobots().isEmpty());
        assertTrue(plan.shouldStartCoreRuntime());
    }

    @Test
    public void firstRunStartsEnvironmentServicesWithoutRobotTargets() {
        AcpRuntimePlan plan = AcpRuntimePlan.build(null, null, null);
        assertEquals(0, plan.getMolaChatTargetCount());
        assertEquals(0, plan.getEnabledChannelCount());
        assertTrue(plan.getStarweaveEligibleRobots().isEmpty());
        assertTrue(plan.shouldStartCoreRuntime());
    }

    private static AcpRobotParam robot(String name, boolean enabled,
                                       boolean onlySubAgent,
                                       boolean onlyTeamMember) {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName(name);
        robot.setEnabled(enabled);
        robot.setOnlySubAgent(onlySubAgent);
        robot.setOnlyTeamMember(onlyTeamMember);
        return robot;
    }
}
