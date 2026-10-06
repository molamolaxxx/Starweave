package com.mola.cmd.proxy.app.acp.team.protocol;

import java.util.List;
import java.util.Map;

public final class TeamMemberCommand {
    private String clientMessageId;
    public String getClientMessageId() { return clientMessageId; }

    private String schemaVersion;
    private String ownerChatterId;
    private String teamId;
    private String teamMemberId;
    private String acpClientId;
    private String message;
    private List<Map<String, String>> files;
    private String sessionId;
    private Integer limit;
    private String before;
    public String getBefore() { return before; }
    private String path;
    private Integer maxBytes;
    private String charset;

    @SuppressWarnings("unused")
    private TeamMemberCommand() {
    }

    public String getSchemaVersion() { return schemaVersion; }
    public String getOwnerChatterId() { return ownerChatterId; }
    public String getTeamId() { return teamId; }
    public String getTeamMemberId() { return teamMemberId; }
    public String getAcpClientId() { return acpClientId; }
    public String getMessage() { return message; }
    public List<Map<String, String>> getFiles() { return files; }
    public String getSessionId() { return sessionId; }
    public Integer getLimit() { return limit; }
    public String getPath() { return path; }
    public Integer getMaxBytes() { return maxBytes; }
    public String getCharset() { return charset; }
}
