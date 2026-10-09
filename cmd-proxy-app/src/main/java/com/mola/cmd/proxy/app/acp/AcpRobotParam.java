package com.mola.cmd.proxy.app.acp;

import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig;
import com.mola.cmd.proxy.app.acp.subagent.model.SubAgentRef;
import com.mola.cmd.proxy.app.acp.talkto.model.ContactRef;

import java.util.List;
import java.util.Collections;

public class AcpRobotParam {
    private String name = "";
    private String signature = "";
    private String workDir = "";
    private String avatar = "";
    private boolean enabled = true;
    private MemoryConfig memory;
    private String agentProvider = "KIRO_CLI";
    private String providerVersion;
    private boolean abilityAutoRefresh = true;
    private List<SubAgentRef> subAgents;
    private boolean onlySubAgent;
    private boolean onlyTeamMember;
    /** Owners allowed to borrow this robot as a remote mixed-Team fragment source. */
    private List<String> teamSharedWithChatterIds;
    private boolean scheduleEnabled = false;
    private boolean observationEnabled = false;

    public boolean isObservationEnabled() { return observationEnabled; }
    public void setObservationEnabled(boolean observationEnabled) { this.observationEnabled = observationEnabled; }
    private AutoNewSessionConfig autoNewSession;
    private AutoSleepConfig autoSleep;
    private List<ContactRef> contacts;
    private String model;
    private com.mola.cmd.proxy.app.acp.acpclient.agent.EmbeddedPiConfig embeddedPi;
    private String apiKey;
    private String codexHome;
    private String deepSeekBaseUrl;
    private String dshHome;
    private String dshAgentPreset;
    private String permissionPolicy;

    private boolean proxyEnabled;
    private String httpProxy;
    private String noProxy;

    public AcpRobotParam() {
    }

    public AcpRobotParam(String name, String signature, String workDir, String avatar) {
        this.name = name;
        this.signature = signature;
        this.workDir = workDir;
        this.avatar = avatar;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getWorkDir() {
        return workDir;
    }

    public void setWorkDir(String workDir) {
        this.workDir = workDir;
    }

    public String getAvatar() {
        if (avatar != null && !avatar.isEmpty()) {
            return avatar;
        }
        if ("EMBEDDED_PI_ACP".equalsIgnoreCase(agentProvider)) {
            return "img/pi.png";
        }
        if ("OPENCODE".equalsIgnoreCase(agentProvider)) {
            return "img/opencode.png";
        }
        if ("CLAUDE_AGENT_ACP".equalsIgnoreCase(agentProvider)) {
            return "img/claude.png";
        }
        if ("CODEX_ACP".equalsIgnoreCase(agentProvider)) {
            return "img/codex.png";
        }
        if ("DEEPSEEK_HARNESS_ACP".equalsIgnoreCase(agentProvider)) {
            return "img/deepseek.png";
        }
        return "img/kiro.png";
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public MemoryConfig getMemory() {
        return memory;
    }

    public void setMemory(MemoryConfig memory) {
        this.memory = memory;
    }

    /**
     * 该 robot 是否开启了记忆。
     * Claude Code 自带原生记忆，默认不启用 cmd-proxy 记忆模块，但允许手动开启。
     */
    public boolean isMemoryEnabled() {
        return memory != null && memory.isEnabled();
    }

    public boolean isAbilityAutoRefresh() {
        return abilityAutoRefresh;
    }

    public void setAbilityAutoRefresh(boolean abilityAutoRefresh) {
        this.abilityAutoRefresh = abilityAutoRefresh;
    }

    public String getAgentProvider() {
        return agentProvider;
    }

    public void setAgentProvider(String agentProvider) {
        this.agentProvider = agentProvider;
    }

    public String getProviderVersion() { return providerVersion; }
    public void setProviderVersion(String providerVersion) { this.providerVersion = providerVersion; }

    public List<SubAgentRef> getSubAgents() {
        return subAgents;
    }

    public void setSubAgents(List<SubAgentRef> subAgents) {
        this.subAgents = subAgents;
    }

    /**
     * 该 robot 是否配置了子 Agent。
     */
    public boolean hasSubAgents() {
        return subAgents != null && !subAgents.isEmpty();
    }

    public boolean isOnlySubAgent() {
        return onlySubAgent;
    }

    public void setOnlySubAgent(boolean onlySubAgent) {
        this.onlySubAgent = onlySubAgent;
    }

    public boolean isOnlyTeamMember() {
        return onlyTeamMember;
    }

    public void setOnlyTeamMember(boolean onlyTeamMember) {
        this.onlyTeamMember = onlyTeamMember;
    }

    public List<String> getTeamSharedWithChatterIds() {
        return teamSharedWithChatterIds == null
                ? Collections.emptyList() : Collections.unmodifiableList(teamSharedWithChatterIds);
    }

    public void setTeamSharedWithChatterIds(List<String> teamSharedWithChatterIds) {
        this.teamSharedWithChatterIds = teamSharedWithChatterIds;
    }

    public boolean isTeamSharedWith(String ownerChatterId) {
        if (ownerChatterId == null) return false;
        String owner = ownerChatterId.trim();
        for (String allowed : getTeamSharedWithChatterIds()) {
            if (allowed != null && owner.equals(allowed.trim())) return true;
        }
        return false;
    }

    public boolean isScheduleEnabled() {
        return scheduleEnabled;
    }

    public void setScheduleEnabled(boolean scheduleEnabled) {
        this.scheduleEnabled = scheduleEnabled;
    }

    public AutoNewSessionConfig getAutoNewSession() {
        return autoNewSession;
    }

    public void setAutoNewSession(AutoNewSessionConfig autoNewSession) {
        this.autoNewSession = autoNewSession;
    }

    public boolean isAutoNewSessionEnabled() {
        return autoNewSession != null && autoNewSession.isEnabled();
    }

    public AutoSleepConfig getAutoSleep() {
        return autoSleep;
    }

    public void setAutoSleep(AutoSleepConfig autoSleep) {
        this.autoSleep = autoSleep;
    }

    public boolean isAutoSleepEnabled() {
        return autoSleep != null && autoSleep.isEnabled();
    }

    public List<ContactRef> getContacts() {
        return contacts;
    }

    public void setContacts(List<ContactRef> contacts) {
        this.contacts = contacts;
    }

    /**
     * 该 robot 是否配置了通讯录。
     */
    public boolean hasContacts() {
        return contacts != null && !contacts.isEmpty();
    }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public com.mola.cmd.proxy.app.acp.acpclient.agent.EmbeddedPiConfig getEmbeddedPi() { return embeddedPi; }
    public void setEmbeddedPi(com.mola.cmd.proxy.app.acp.acpclient.agent.EmbeddedPiConfig value) { embeddedPi = value; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getCodexHome() { return codexHome; }
    public void setCodexHome(String codexHome) { this.codexHome = codexHome; }

    public String getDeepSeekBaseUrl() { return deepSeekBaseUrl; }
    public void setDeepSeekBaseUrl(String deepSeekBaseUrl) { this.deepSeekBaseUrl = deepSeekBaseUrl; }

    public String getDshHome() { return dshHome; }
    public void setDshHome(String dshHome) { this.dshHome = dshHome; }

    public String getDshAgentPreset() { return dshAgentPreset; }
    public void setDshAgentPreset(String dshAgentPreset) { this.dshAgentPreset = dshAgentPreset; }

    public String getPermissionPolicy() { return permissionPolicy; }
    public void setPermissionPolicy(String permissionPolicy) { this.permissionPolicy = permissionPolicy; }

    public boolean isProxyEnabled() { return proxyEnabled; }
    public void setProxyEnabled(boolean proxyEnabled) { this.proxyEnabled = proxyEnabled; }

    public String getHttpProxy() { return httpProxy; }
    public void setHttpProxy(String httpProxy) { this.httpProxy = httpProxy; }

    public String getNoProxy() { return noProxy; }
    public void setNoProxy(String noProxy) { this.noProxy = noProxy; }
}
