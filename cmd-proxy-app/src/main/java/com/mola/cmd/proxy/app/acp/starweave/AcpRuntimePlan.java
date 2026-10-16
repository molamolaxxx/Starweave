package com.mola.cmd.proxy.app.acp.starweave;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** Immutable startup plan separating MolaChat targets from local Starweave targets. */
public final class AcpRuntimePlan {

    private final int molaChatTargetCount;
    private final List<String> starweaveEligibleRobots;
    private final int enabledChannelCount;

    private AcpRuntimePlan(int molaChatTargetCount,
                           List<String> starweaveEligibleRobots,
                           int enabledChannelCount) {
        this.molaChatTargetCount = molaChatTargetCount;
        this.starweaveEligibleRobots = Collections.unmodifiableList(
                new ArrayList<>(starweaveEligibleRobots));
        this.enabledChannelCount = enabledChannelCount;
    }

    public static AcpRuntimePlan build(Collection<String> chatterIds,
                                       Collection<AcpRobotParam> robots,
                                       Collection<ChannelConfig> channels) {
        int chatterCount = chatterIds == null ? 0 : chatterIds.size();
        int ordinaryRobotCount = 0;
        List<String> localEligible = new ArrayList<>();
        if (robots != null) {
            for (AcpRobotParam robot : robots) {
                if (robot == null || !robot.isEnabled()) continue;
                if (robot.isOnlySubAgent() && robot.isOnlyTeamMember()) {
                    throw new IllegalArgumentException(
                            "onlySubAgent and onlyTeamMember cannot both be true");
                }
                if (!robot.isOnlyTeamMember()) ordinaryRobotCount++;
                if (!robot.isOnlySubAgent() && !robot.isOnlyTeamMember()
                        && robot.getName() != null && !robot.getName().trim().isEmpty()) {
                    localEligible.add(robot.getName());
                }
            }
        }
        int enabledChannels = 0;
        if (channels != null) {
            for (ChannelConfig channel : channels) {
                if (channel != null && channel.isEnabled()) enabledChannels++;
            }
        }
        return new AcpRuntimePlan(
                chatterCount * ordinaryRobotCount, localEligible, enabledChannels);
    }

    public int getMolaChatTargetCount() {
        return molaChatTargetCount;
    }

    public List<String> getStarweaveEligibleRobots() {
        return starweaveEligibleRobots;
    }

    public int getEnabledChannelCount() {
        return enabledChannelCount;
    }

    /** Environment APIs and Team participation must work even before the first robot is configured. */
    public boolean shouldStartCoreRuntime() {
        return true;
    }
}
