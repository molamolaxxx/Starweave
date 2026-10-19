package com.mola.starweave.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.Map;

/**
 * One bounded, isolated Node process per observation. Scripts retain normal workspace permissions.
 */
public final class NodeObservationRunner {
    public static final int MAX_RESULT_BYTES = 1024 * 1024;
    public static final int MAX_SCRIPT_BYTES = 256 * 1024;
    private final long timeoutMillis;
    private static final String RUNNER =
            "const fs=require('fs'),path=require('path'),Module=require('module');const"
                + " output=process.stdout.write.bind(process.stdout);"
                + "process.stdout.write=process.stderr.write.bind(process.stderr);(async()=>{try{const"
                + " input=JSON.parse(fs.readFileSync(0,'utf8'));const"
                + " filename=path.join(process.cwd(),'.starweave-observe.cjs');const m=new"
                + " Module(filename);m.filename=filename;"
                + "m.paths=Module._nodeModulePaths(process.cwd());m._compile(input.script,filename);if(typeof"
                + " m.exports!=='function')throw new TypeError('脚本必须通过 module.exports 导出函数');const"
                + " result=await m.exports();if(typeof result!=='string')throw new"
                + " TypeError('观测结果必须是字符串');if(Buffer.byteLength(result,'utf8')>1048576)throw new"
                + " Error('观测结果超过 1 MiB');"
                + "output(JSON.stringify({success:true,result}),()=>process.exit(0));"
                + "}catch(e){output(JSON.stringify({success:false,error:String(e.stack||e)}),()=>process.exit(1))}})();";

    public NodeObservationRunner() {
        this(20_000);
    }

    public NodeObservationRunner(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public JsonObject execute(String script, Path workDir) {
        return execute(script, workDir, java.util.Collections.emptyMap());
    }

    public JsonObject execute(String script, Path workDir, Map<String, String> environment) {
        long started = System.currentTimeMillis();
        JsonObject result = new JsonObject();
        Process process = null;
        ExecutorService readers =
                Executors.newFixedThreadPool(
                        2,
                        r -> {
                            Thread thread = new Thread(r, "observation-output");
                            thread.setDaemon(true);
                            return thread;
                        });
        try {
            validate(script);
            ProcessBuilder builder = new ProcessBuilder("node", "-e", RUNNER).directory(workDir.toFile());
            builder.environment().putAll(environment);
            process = builder.start();
            final Process child = process;
            Future<String> stdout =
                    readers.submit(() -> read(child.getInputStream(), 8 * MAX_RESULT_BYTES, true));
            Future<String> stderr =
                    readers.submit(() -> read(child.getErrorStream(), 64 * 1024, false));
            JsonObject input = new JsonObject();
            input.addProperty("script", script);
            try (OutputStream stream = process.getOutputStream()) {
                stream.write(input.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("脚本执行超时（" + timeoutMillis + " ms）");
            }
            result = JsonParser.parseString(stdout.get(2, TimeUnit.SECONDS)).getAsJsonObject();
            if (!result.has("success")) throw new IOException("脚本没有返回有效执行结果");
            result.addProperty("logs", stderr.get(2, TimeUnit.SECONDS));
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            result = new JsonObject();
            result.addProperty("success", false);
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            result.addProperty("error", trace.toString());
        } finally {
            if (process != null) {
                process.destroyForcibly();
                try {
                    process.getInputStream().close();
                    process.getErrorStream().close();
                } catch (IOException ignored) {
                }
            }
            readers.shutdownNow();
            result.addProperty("durationMillis", System.currentTimeMillis() - started);
        }
        return result;
    }

    public static void validate(String script) {
        if (script == null || script.trim().isEmpty())
            throw new IllegalArgumentException("观测脚本不能为空");
        if (script.getBytes(StandardCharsets.UTF_8).length > MAX_SCRIPT_BYTES)
            throw new IllegalArgumentException("观测脚本超过 256 KiB");
    }

    private static String read(InputStream stream, int limit, boolean failOnLimit)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        boolean truncated = false;
        while ((count = stream.read(buffer)) != -1) {
            int remaining = limit - output.size();
            if (count > remaining) {
                if (failOnLimit) throw new IOException("执行输出超过限制");
                truncated = true;
            }
            if (remaining > 0) output.write(buffer, 0, Math.min(count, remaining));
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8)
                + (truncated ? "\n[日志已截断]" : "");
    }
}
