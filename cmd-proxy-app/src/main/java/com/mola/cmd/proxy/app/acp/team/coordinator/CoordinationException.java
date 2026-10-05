package com.mola.cmd.proxy.app.acp.team.coordinator;

/** Stable error code shared by the HTTP and RPC adapters. */
public final class CoordinationException extends IllegalStateException {
    private final String code;
    public CoordinationException(String code, String message) {
        super(message);
        this.code = code;
    }
    public String getCode() { return code; }
}
