package com.mola.starweave.agent;

import com.google.gson.*;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import java.io.*;
import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.*;

/** Host JVM execution, deliberately not a sandbox. Never replaces global System.out/err. */
final class JvmExecutor implements AutoCloseable {
    private final Map<String, Object> bindings;
    private final ClassLoader loader;
    private final ThreadPoolExecutor worker;
    JvmExecutor(Map<String, Object> bindings) {
        this.bindings = new HashMap<>(bindings);
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        loader = context == null ? JvmExecutor.class.getClassLoader() : context;
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
            Thread t = new Thread(r, "starcore-jvm-exec"); t.setDaemon(true); t.setContextClassLoader(loader); return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
    JsonObject execute(JsonObject request, Cancellation cancel) throws Exception {
        String script = Json.str(request, "script");
        if (script.trim().isEmpty() || script.length() > 65536) throw new IllegalArgumentException("script must contain 1-65536 characters");
        int timeout = Json.integer(request, "timeoutMs", 10000);
        if (timeout < 1 || timeout > 60000) throw new IllegalArgumentException("timeoutMs must be 1-60000");
        cancel.check(); long started = System.nanoTime(); BoundedOutput output = new BoundedOutput();
        Future<JsonElement> task;
        try {
            task = worker.submit(() -> {
                Binding binding = new Binding(new HashMap<>(bindings));
                binding.setVariable("args", new Gson().fromJson(Json.object(request, "args"), Map.class));
                binding.setVariable("out", new PrintWriter(output, true));
                GroovyShell shell = new GroovyShell(loader, binding);
                try {
                    Object result = shell.evaluate(script, "StarcoreScript.groovy");
                    return snapshot(result, 0, new IdentityHashMap<>(), new int[]{200});
                } finally { shell.getClassLoader().clearCache(); shell.getClassLoader().close(); }
            });
        } catch (RejectedExecutionException e) {
            return Json.obj("success", false, "error", "JVM executor busy or closed; a previous timed-out script may still be running", "status", "REJECTED");
        }
        JsonObject reply = new JsonObject();
        try (AutoCloseable hook = cancel.onCancel(() -> task.cancel(true))) {
            JsonElement value = task.get(timeout, TimeUnit.MILLISECONDS);
            String encoded = value.toString();
            if (encoded.length() > 32768) { reply.addProperty("result", encoded.substring(0, 32768)); reply.addProperty("resultTruncated", true); }
            else reply.add("result", value);
            reply.addProperty("success", true);
        } catch (TimeoutException e) {
            task.cancel(true); reply.addProperty("success", false); reply.addProperty("status", "TIMED_OUT");
            reply.addProperty("error", "Wait timed out; interrupt requested, script termination is NOT guaranteed. Do not automatically retry.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause(); reply.addProperty("success", false); reply.addProperty("errorType", cause.getClass().getName());
            reply.addProperty("error", clip(String.valueOf(cause.getMessage())));
        } catch (InterruptedException e) { task.cancel(true); Thread.currentThread().interrupt(); throw e; }
        finally { if (cancel.isCancelled()) task.cancel(true); }
        reply.addProperty("stdout", output.text()); reply.addProperty("stdoutTruncated", output.truncated());
        reply.addProperty("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        return reply;
    }
    private static String clip(String text) { return text.length() > 2048 ? text.substring(0, 2048) + "...[truncated]" : text; }
    private static JsonElement snapshot(Object value, int depth, IdentityHashMap<Object, Boolean> seen, int[] budget) {
        if (value == null) return JsonNull.INSTANCE;
        if (--budget[0] < 0 || depth > 5) return new JsonPrimitive("[truncated]");
        if (value instanceof CharSequence || value instanceof Character || value instanceof Enum) return new JsonPrimitive(clip(value.toString()));
        if (value instanceof Boolean) return new JsonPrimitive((Boolean) value);
        if (value instanceof Number) return new JsonPrimitive(clip(value.toString()).matches("-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?") ? (Number) value : 0);
        if (seen.put(value, Boolean.TRUE) != null) return new JsonPrimitive("[circular reference]");
        if (value instanceof Map) {
            JsonObject object = new JsonObject();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (budget[0] <= 0) { object.addProperty("__truncated", true); break; }
                Object key = entry.getKey(); if (!(key instanceof String)) continue;
                object.add(clip((String) key), snapshot(entry.getValue(), depth + 1, seen, budget));
            }
            return object;
        }
        if (value instanceof Collection || value.getClass().isArray()) {
            JsonArray array = new JsonArray();
            if (value.getClass().isArray()) {
                for (int i = 0; i < Array.getLength(value) && budget[0] > 0; i++) array.add(snapshot(Array.get(value, i), depth + 1, seen, budget));
            } else {
                for (Object item : (Collection<?>) value) { if (budget[0] <= 0) break; array.add(snapshot(item, depth + 1, seen, budget)); }
            }
            if (budget[0] <= 0) array.add("[truncated]"); return array;
        }
        // Do not walk arbitrary host objects or invoke their getters/toString for display.
        return Json.obj("className", value.getClass().getName(), "summary", "Return selected fields in a map to inspect this object");
    }
    @Override public void close() { worker.shutdownNow(); }
    private static final class BoundedOutput extends Writer {
        private final StringBuilder buffer = new StringBuilder(); private boolean truncated;
        @Override public synchronized void write(char[] data, int offset, int length) {
            int accepted = Math.min(length, 16384 - buffer.length()); if (accepted > 0) buffer.append(data, offset, accepted);
            if (accepted < length) truncated = true;
        }
        synchronized String text() { return buffer.toString(); }
        synchronized boolean truncated() { return truncated; }
        @Override public void flush() { }
        @Override public void close() { }
    }
}
