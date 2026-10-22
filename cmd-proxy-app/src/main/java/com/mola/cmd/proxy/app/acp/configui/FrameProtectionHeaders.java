package com.mola.cmd.proxy.app.acp.configui;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

/** HTTP response policy shared by ConfigUI routes and environment forwarding. */
final class FrameProtectionHeaders {
    private static final String POLICY = "frame-ancestors 'none';";

    private FrameProtectionHeaders() { }

    static void apply(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("X-Frame-Options", "DENY");
        // Multiple CSP policies are enforced together. Preserve upstream restrictions
        // on scripts, styles, etc., while independently prohibiting framing.
        if (headers.get("Content-Security-Policy") == null
                || !headers.get("Content-Security-Policy").contains(POLICY)) {
            headers.add("Content-Security-Policy", POLICY);
        }
    }
}
