package com.mola.cmd.proxy.app.acp.memory;

import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient.State;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class MemoryAcpClientSleepTest {
    @Test
    public void sleepReleasesRuntimeAndNextPromptLoadsOriginalMemorySession() throws Exception {
        try (FakeClient client = new FakeClient()) {
            client.start();
            assertEquals("memory-original", client.getSessionId());
            assertEquals("[]", client.sendPromptSync("first"));
            client.sleep();
            client.sleep();
            assertEquals(State.SLEEP, client.getState());
            assertEquals(1, client.releases);
            assertEquals(1, client.starts);
            assertEquals("[]", client.sendPromptSync("second"));
            assertEquals(State.READY, client.getState());
            assertEquals(2, client.starts);
            assertEquals("memory-original", client.getSessionId());
            assertEquals(java.util.Arrays.asList("session/new", "session/load"), client.methods);
            assertEquals(2, client.prompts);
        }
    }

    @Test
    public void failedRestoreKeepsIdentityAndCanRetryWithoutCreatingNewSession() throws Exception {
        try (FakeClient client = new FakeClient()) {
            client.start();
            client.sleep();
            client.failLoad = true;
            try {
                client.sendPromptSync("must-not-run");
                fail("expected load failure");
            } catch (MemoryAcpClient.SessionRestoreException expected) {
                assertTrue(expected.getCause().getMessage().contains("planned load failure"));
            }
            assertEquals(State.SLEEP, client.getState());
            assertEquals("memory-original", client.getSessionId());
            assertEquals(0, client.prompts);
            client.failLoad = false;
            assertEquals("[]", client.sendPromptSync("retry"));
            assertEquals(java.util.Arrays.asList("session/new", "session/load", "session/load"), client.methods);
            assertEquals(1, client.prompts);
        }
    }

    @Test
    public void providerWithoutLoadCreatesNewMemorySessionOnlyWhenNeeded() throws Exception {
        try (FakeClient client = new FakeClient(false)) {
            client.start();
            client.sleep();
            assertEquals(1, client.starts);
            client.sendPromptSync("next batch");
            assertEquals(java.util.Arrays.asList("session/new", "session/new"), client.methods);
            assertEquals(1, client.prompts);
        }
    }

    @Test
    public void restoreTimeoutRetainsIdentityForNextAttempt() throws Exception {
        try (FakeClient client = new FakeClient(true, 1)) {
            client.start();
            client.sleep();
            client.blockLoad = true;
            try {
                client.sendPromptSync("timed-out");
                fail("expected restore timeout");
            } catch (MemoryAcpClient.SessionRestoreException expected) {
                assertTrue(expected.getCause().getMessage().contains("超时"));
            }
            assertEquals("memory-original", client.getSessionId());
            client.blockLoad = false;
            assertEquals("[]", client.sendPromptSync("retry"));
            assertEquals(java.util.Arrays.asList("session/new", "session/load", "session/load"), client.methods);
            assertEquals(1, client.prompts);
        }
    }

    private static final class FakeClient extends MemoryAcpClient {
        private int starts;
        private int releases;
        private int prompts;
        private boolean failLoad;
        private volatile boolean blockLoad;
        private final List<String> methods = new ArrayList<>();

        FakeClient() { this(true); }

        FakeClient(boolean supportsLoad) { this(supportsLoad, 5); }

        FakeClient(boolean supportsLoad, int timeoutSeconds) {
            super(new AgentProvider() {
                @Override public String getName() { return "memory-sleep-fake"; }
                @Override public String getCommand() { return "unused"; }
                @Override public String[] getArgs() { return new String[0]; }
                @Override public java.util.List<java.nio.file.Path> getMcpConfigPaths(String path) {
                    return java.util.Collections.emptyList();
                }
                @Override public boolean supportsSessionLoad() { return supportsLoad; }
            }, "/tmp", "memory-sleep-test", timeoutSeconds, robot(), null);
        }

        private static AcpRobotParam robot() {
            AcpRobotParam robot = new AcpRobotParam();
            robot.setName("memory-sleep-test");
            robot.setAgentProvider("JAVA_PI_ACP");
            return robot;
        }

        @Override protected void startProcess() { starts++; }
        @Override protected void initialize() { }
        @Override protected void releaseRuntimeForSleep() { releases++; }
        @Override protected JsonObject sendRequest(String method, JsonObject params) throws IOException {
            methods.add(method);
            assertEquals(0, params.getAsJsonArray("mcpServers").size());
            if ("session/load".equals(method)) {
                assertEquals("memory-original", params.get("sessionId").getAsString());
                if (failLoad) throw new IOException("planned load failure");
                if (blockLoad) {
                    try {
                        new java.util.concurrent.CountDownLatch(1).await();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IOException("load interrupted", error);
                    }
                }
            } else {
                assertEquals("session/new", method);
            }
            JsonObject result = new JsonObject();
            result.addProperty("sessionId", "memory-original");
            JsonObject response = new JsonObject();
            response.add("result", result);
            return response;
        }
        @Override protected String doSendPromptSync(String text, String name) {
            prompts++;
            return "[]";
        }
    }
}
