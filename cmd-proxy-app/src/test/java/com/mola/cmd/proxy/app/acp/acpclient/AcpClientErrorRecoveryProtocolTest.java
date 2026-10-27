package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AcpClientErrorRecoveryProtocolTest {
    @Test public void queueFullEndsTurnThenAllowsNextPromptInSameRuntime() throws Exception {
        exercise("queue-full", false);
    }
    @Test public void deadRuntimeRestoresExactSessionBeforeNextPrompt() throws Exception {
        exercise("exit", true);
    }
    @Test public void failedRestoreStaysErrorAndDoesNotCreateEmptySession() throws Exception {
        AcpClient client = client();
        try {
            client.start();
            client.setSessionId("missing-session");
            failTurn(client, "exit");
            for (int i = 0; i < 2; i++) {
                try { client.prepareForMessage(); fail("restore must fail"); }
                catch (IOException expected) { assertTrue(expected.getMessage().contains("session/load")); }
                assertEquals(AbstractAcpClient.State.ERROR, client.getState());
                assertEquals("missing-session", client.getSessionId());
            }
        } finally { client.close(); }
    }
    @Test public void simultaneousMessagesShareOneRuntimeRecovery() throws Exception {
        AcpClient client = client();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            client.start();
            String session = client.getSessionId();
            failTurn(client, "exit");
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> recover = () -> { start.await(); return client.prepareForMessage(); };
            Future<Boolean> first = callers.submit(recover), second = callers.submit(recover);
            start.countDown();
            assertTrue(first.get(15, TimeUnit.SECONDS) ^ second.get(15, TimeUnit.SECONDS));
            assertEquals(AbstractAcpClient.State.READY, client.getState());
            assertEquals(session, client.getSessionId());
        } finally { callers.shutdownNow(); client.close(); }
    }
    @Test public void onlyStructuredTransientErrorsAreImmediatelyRetryable() {
        assertTrue(new AcpPromptResponseException(JsonParser.parseString(
                "{\"code\":-32603,\"message\":\"Internal error: {\\\"code\\\":503,\\\"type\\\":\\\"SERVICE_UNAVAILABLE\\\"}\"}"))
                .isRetryableRequestFailure());
        assertFalse(new AcpPromptResponseException(JsonParser.parseString(
                "{\"code\":-32603,\"message\":\"unknown session\"}"))
                .isRetryableRequestFailure());
    }
    private void exercise(String first, boolean runtimeFailed) throws Exception {
        AcpClient client = client();
        try {
            client.start();
            String session = client.getSessionId();
            failTurn(client, first);
            assertEquals(runtimeFailed ? AbstractAcpClient.State.ERROR : AbstractAcpClient.State.READY,
                    client.getState());
            if (runtimeFailed) assertTrue(client.prepareForMessage());
            assertEquals(session, client.getSessionId());
            CountDownLatch ready = new CountDownLatch(1);
            AtomicInteger errors = new AtomicInteger();
            client.setAfterTurnReady(ready::countDown);
            client.setGlobalListener(new Listener() {
                @Override public void onError(Exception error) { errors.incrementAndGet(); ready.countDown(); }
            });
            client.send("continue", null);
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            assertEquals(0, errors.get());
            assertEquals(AbstractAcpClient.State.READY, client.getState());
            assertEquals(session, client.getSessionId());
        } finally { client.close(); }
    }
    private void failTurn(AcpClient client, String prompt) throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        client.setAfterTurnReady(() -> { events.add("ready"); ended.countDown(); });
        client.setAfterTurnFailed(ended::countDown);
        client.setGlobalListener(new Listener() {
            @Override public void onError(Exception error) { events.add("error"); }
        });
        client.send(prompt, null);
        assertTrue(ended.await(15, TimeUnit.SECONDS));
        // Fatal failures notify their owner before error delivery; drain the serial executor.
        CountDownLatch delivered = new CountDownLatch(1);
        java.lang.reflect.Field executorField = AcpClient.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        ((ExecutorService) executorField.get(client)).submit(delivered::countDown);
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        assertEquals("error", events.get(0));
        if ("queue-full".equals(prompt)) assertEquals(Arrays.asList("error", "ready"), events);
    }
    private AcpClient client() throws Exception {
        AcpRobotParam robot = new AcpRobotParam(); robot.setName("error-recovery-test");
        AcpClient client = new AcpClient(new Provider(), Files.createTempDirectory("acp-recovery").toString(),
                "error-recovery-" + UUID.randomUUID(), robot);
        client.setForceNewSession(true);
        return client;
    }
    private static class Listener implements AcpResponseListener {
        public void onMessage(String text) { }
        public void onToolCall(String id, String title, String status, JsonObject update) { }
        public void onComplete(String response) { }
        public void onError(Exception error) { }
    }
    private static class Provider implements AgentProvider {
        public String getName() { return "error-recovery-test"; }
        public String getCommand() { return Paths.get(System.getProperty("java.home"), "bin", "java").toString(); }
        public String[] getArgs() { return new String[]{"-cp", System.getProperty("java.class.path"), FakeAgent.class.getName()}; }
        public boolean supportsSessionLoad() { return true; }
        public boolean supportsClientMcpServers() { return false; }
        public List<Path> getMcpConfigPaths(String workspace) { return Collections.emptyList(); }
    }
    public static class FakeAgent {
        public static void main(String[] args) throws Exception {
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = input.readLine()) != null) {
                JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                String method = request.get("method").getAsString();
                if ("session/end".equals(method)) return;
                if (!request.has("id")) continue;
                JsonObject result = new JsonObject(), error = null;
                if ("session/new".equals(method)) result.addProperty("sessionId", UUID.randomUUID().toString());
                if ("session/load".equals(method) && "missing-session".equals(
                        request.getAsJsonObject("params").get("sessionId").getAsString())) {
                    error = new JsonObject(); error.addProperty("code", -32000); error.addProperty("message", "session not found");
                }
                if ("session/prompt".equals(method)) {
                    String prompt = request.getAsJsonObject("params").getAsJsonArray("prompt")
                            .get(0).getAsJsonObject().get("text").getAsString();
                    if (prompt.contains("exit")) return;
                    if (prompt.contains("queue-full")) {
                        error = new JsonObject(); error.addProperty("code", -32603);
                        error.addProperty("message", "Internal error: {\"code\":503,\"type\":\"SERVICE_UNAVAILABLE\"}");
                    } else result.addProperty("stopReason", "end_turn");
                }
                JsonObject response = new JsonObject(); response.addProperty("jsonrpc", "2.0");
                response.add("id", request.get("id")); response.add(error == null ? "result" : "error", error == null ? result : error);
                System.out.println(response); System.out.flush();
            }
        }
    }
}
