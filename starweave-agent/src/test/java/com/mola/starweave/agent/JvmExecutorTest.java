package com.mola.starweave.agent;

import com.google.gson.*;
import org.junit.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class JvmExecutorTest {
    @Test public void executesInHostJvmAndBindsHostObjectAndArguments() throws Exception {
        try (JvmExecutor executor = new JvmExecutor(Collections.singletonMap("app", Collections.singletonMap("name", "host")))) {
            JsonObject result = executor.execute(Json.obj("script", "temporary=42; println 'hello'; return [name:app.name, value:args.value, heap:java.lang.management.ManagementFactory.memoryMXBean.heapMemoryUsage.used]", "args", Json.obj("value", 7), "timeoutMs", 60000), new Cancellation());
            assertTrue(result.toString(), result.get("success").getAsBoolean());
            assertEquals("host", result.getAsJsonObject("result").get("name").getAsString());
            assertEquals(7, result.getAsJsonObject("result").get("value").getAsInt());
            assertTrue(result.getAsJsonObject("result").get("heap").getAsLong() > 0);
            assertEquals("hello\n", result.get("stdout").getAsString().replace("\r\n", "\n"));
            assertTrue(executor.execute(Json.obj("script", "return !binding.hasVariable('temporary')", "timeoutMs", 60000), new Cancellation()).get("result").getAsBoolean());
        }
    }
    @Test public void errorsAndOutputAreBoundedWithoutWalkingHostObjects() throws Exception {
        try (JvmExecutor executor = new JvmExecutor(Collections.emptyMap())) {
            JsonObject result = executor.execute(Json.obj("script", "print('x' * 50000); def x=[:]; x.self=x; return x", "timeoutMs", 60000), new Cancellation());
            assertTrue(result.toString(), result.get("success").getAsBoolean());
            assertEquals(16384, result.get("stdout").getAsString().length());
            assertTrue(result.get("stdoutTruncated").getAsBoolean());
            assertEquals("[circular reference]", result.getAsJsonObject("result").get("self").getAsString());
            result = executor.execute(Json.obj("script", "throw new IllegalStateException('example')", "timeoutMs", 60000), new Cancellation());
            assertFalse(result.get("success").getAsBoolean()); assertEquals("example", result.get("error").getAsString());
            result = executor.execute(Json.obj("script", "return new Object()", "timeoutMs", 60000), new Cancellation());
            assertEquals("java.lang.Object", result.getAsJsonObject("result").get("className").getAsString());
        }
    }
    @Test public void timeoutDoesNotAdmitMoreWorkUntilUncooperativeScriptExits() throws Exception {
        CountDownLatch release = new CountDownLatch(1), entered = new CountDownLatch(1);
        Map<String, Object> bindings = new HashMap<>(); bindings.put("release", release); bindings.put("entered", entered);
        try (JvmExecutor executor = new JvmExecutor(bindings)) {
            // Compile and warm Groovy before the short execution deadline.
            executor.execute(Json.obj("script", "return 1", "timeoutMs", 60000), new Cancellation());
            JsonObject result = executor.execute(Json.obj("script", "entered.countDown(); while(release.count>0) { try { release.await() } catch(InterruptedException ignored) {} }; return 1", "timeoutMs", 1000), new Cancellation());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals("TIMED_OUT", result.get("status").getAsString());
            assertEquals("REJECTED", executor.execute(Json.obj("script", "return 2"), new Cancellation()).get("status").getAsString());
        } finally { release.countDown(); }
    }
    @Test public void toolIsAbsentUntilHostExplicitlyEnablesIt() throws Exception {
        Path cwd = Paths.get(System.getProperty("java.io.tmpdir"));
        AgentToolPolicy policy = AgentToolPolicy.workspace(cwd, cwd, false, false);
        try (Tools tools = new Tools(config(cwd, policy), cwd, new JsonArray(), new Cancellation())) {
            assertFalse(tools.tools.containsKey("jvm_exec"));
        }
        try (Tools tools = new Tools(config(cwd, policy.withJvmExecution(Collections.emptyMap())), cwd, new JsonArray(), new Cancellation())) {
            assertTrue(tools.tools.containsKey("jvm_exec")); assertFalse(tools.tools.containsKey("shell"));
            JsonObject result = tools.tools.get("jvm_exec").execute.run(Json.obj("script", "return 42", "timeoutMs", 60000), new Cancellation(), ignored -> {});
            assertTrue(result.toString().contains("42"));
        }
    }
    @Test public void cancellationInterruptsActiveScript() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        try (JvmExecutor executor = new JvmExecutor(Collections.singletonMap("entered", entered))) {
            Cancellation cancellation = new Cancellation();
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<?> result = caller.submit(() -> {
                    try { executor.execute(Json.obj("script", "entered.countDown(); Thread.sleep(60000)", "timeoutMs", 60000), cancellation); fail("expected cancellation"); }
                    catch (CancellationException expected) { }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
                assertTrue(entered.await(10, TimeUnit.SECONDS)); cancellation.cancel(); result.get(5, TimeUnit.SECONDS);
            } finally { caller.shutdownNow(); }
        }
    }
    private AgentConfig config(Path cwd, AgentToolPolicy policy) { return new AgentConfig("http://localhost/v1", "key", "model", cwd, 4096, 512, Collections.emptyMap(), policy); }
}
