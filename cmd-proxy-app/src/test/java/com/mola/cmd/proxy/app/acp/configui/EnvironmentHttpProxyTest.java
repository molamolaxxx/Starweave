package com.mola.cmd.proxy.app.acp.configui;

import com.sun.net.httpserver.HttpServer;
import com.mola.cmd.proxy.app.acp.registry.RegistryManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class EnvironmentHttpProxyTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static byte[] read(InputStream input) throws IOException { try(InputStream source=input;ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=source.read(b))>=0)output.write(b,0,n);return output.toByteArray();} }
    @Test public void forwardsBinaryUploadDownloadHeadersErrorsAndEncodedQueries() throws Exception {
        HttpServer target=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        HttpServer gateway=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        EnvironmentHttpProxy proxy=new EnvironmentHttpProxy();
        byte[] binary=new byte[]{0,1,(byte)255,(byte)128,10,13};AtomicInteger calls=new AtomicInteger();
        target.createContext("/download", exchange->{
            calls.incrementAndGet();assertEquals("name=a%2Fb&x=%E4%B8%AD",exchange.getRequestURI().getRawQuery());
            assertEquals("1",exchange.getRequestHeaders().getFirst(EnvironmentHttpProxy.PROXY_HEADER));
            assertEquals("Bearer test",exchange.getRequestHeaders().getFirst("Authorization"));
            assertArrayEquals(binary,read(exchange.getRequestBody()));
            exchange.getResponseHeaders().set("Content-Type","application/octet-stream");
            exchange.getResponseHeaders().set("Content-Disposition","attachment; filename=test.bin");
            exchange.getResponseHeaders().set("ETag","test-etag");
            exchange.getResponseHeaders().set("X-Frame-Options", "SAMEORIGIN");
            exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; frame-ancestors *;");
            exchange.sendResponseHeaders(206,binary.length);try(OutputStream output=exchange.getResponseBody()){output.write(binary);}
        });
        target.createContext("/error",exchange->{byte[] data="failed".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(409,data.length);try(OutputStream output=exchange.getResponseBody()){output.write(data);}});
        gateway.createContext("/",exchange->proxy.forward(exchange,target.getAddress().getPort()));
        target.start();gateway.start();
        try {
            HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+gateway.getAddress().getPort()+"/download?%69nstance=remote&name=a%2Fb&x=%E4%B8%AD").openConnection();
            connection.setReadTimeout(5000);connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Authorization","Bearer test");
            try(OutputStream output=connection.getOutputStream()){output.write(binary);}
            assertEquals(206,connection.getResponseCode());assertArrayEquals(binary,read(connection.getInputStream()));
            assertEquals("DENY", connection.getHeaderField("X-Frame-Options"));
            java.util.List<String> policies = connection.getHeaderFields().entrySet().stream()
                    .filter(entry -> "Content-Security-Policy".equalsIgnoreCase(entry.getKey()))
                    .findFirst().get().getValue();
            assertTrue(policies.contains("frame-ancestors 'none';"));
            assertTrue(policies.contains("default-src 'self'; frame-ancestors *;"));
            assertEquals("attachment; filename=test.bin",connection.getHeaderField("Content-Disposition"));assertEquals("test-etag",connection.getHeaderField("ETag"));assertEquals(1,calls.get());connection.disconnect();
            connection=(HttpURLConnection)new URL("http://127.0.0.1:"+gateway.getAddress().getPort()+"/error").openConnection();assertEquals(409,connection.getResponseCode());assertEquals("failed",new String(read(connection.getErrorStream()),StandardCharsets.UTF_8));connection.disconnect();
        } finally {proxy.close();gateway.stop(0);target.stop(0);}
    }
    @Test public void preservesBrowserOriginAcrossProxyHopsAndRejectsCrossSiteRequests() throws Exception {
        // 此测试验证 Origin 转发，使用独立的无密码注册配置，不读取本机设置。
        java.nio.file.Path registryDirectory = temporary.newFolder("registry").toPath();
        ConfigUiServer target = new ConfigUiServer(0, () -> { }, ignored -> { }) {
            @Override RegistryManager createRegistryManager(int boundPort) throws IOException {
                return new RegistryManager(registryDirectory, boundPort, "origin-proxy-test");
            }
        };
        HttpServer gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer relay = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        EnvironmentHttpProxy proxy = new EnvironmentHttpProxy(), relayProxy = new EnvironmentHttpProxy();
        okhttp3.OkHttpClient browser = new okhttp3.OkHttpClient();
        target.start();
        relay.createContext("/", exchange -> relayProxy.forward(exchange, target.getBoundPort()));
        gateway.createContext("/", exchange -> proxy.forward(exchange, relay.getAddress().getPort()));
        relay.start(); gateway.start();
        String base = "http://127.0.0.1:" + gateway.getAddress().getPort();
        try {
            okhttp3.Request.Builder request = new okhttp3.Request.Builder()
                    .url(base + "/api/starweave/v1/sessions?instance=remote")
                    .header("Host", "registry.example:8443")
                    .header("Origin", "https://registry.example:8443")
                    .header("Sec-Fetch-Site", "same-origin");
            try (okhttp3.Response response = browser.newCall(request.build()).execute()) {
                assertEquals(200, response.code());
            }
            request.url(base + "/api/starweave/v1/sessions/open?instance=remote")
                    .post(okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json"),
                            "{\"requestId\":\"proxy-chat\",\"robotName\":\"Robot\"}"));
            try (okhttp3.Response response = browser.newCall(request.build()).execute()) {
                assertEquals(503, response.code());
                assertEquals("SERVICE_UNAVAILABLE", com.alibaba.fastjson.JSON.parseObject(response.body().string()).getString("code"));
            }
            request.get().url(base + "/api/starweave/v1/sessions?instance=remote")
                    .header("Origin", "https://attacker.invalid")
                    .header(EnvironmentHttpProxy.PROXY_HEADER, "1");
            try (okhttp3.Response response = browser.newCall(request.build()).execute()) {
                assertEquals(403, response.code());
                assertEquals("ORIGIN_REJECTED", com.alibaba.fastjson.JSON.parseObject(response.body().string()).getString("code"));
            }
            request.header("Origin", "https://registry.example:8443").header("Sec-Fetch-Site", "cross-site");
            try (okhttp3.Response response = browser.newCall(request.build()).execute()) {
                assertEquals(403, response.code());
            }
        } finally {
            proxy.close(); relayProxy.close(); gateway.stop(0); relay.stop(0); target.stop();
            browser.connectionPool().evictAll(); browser.dispatcher().executorService().shutdownNow();
        }
    }
    @Test public void flushesSseBeforeTargetFinishesAndCloseCancelsStream() throws Exception {
        HttpServer target=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0),gateway=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        EnvironmentHttpProxy proxy=new EnvironmentHttpProxy();CountDownLatch release=new CountDownLatch(1),first=new CountDownLatch(1);
        ExecutorService executor=Executors.newCachedThreadPool();target.setExecutor(executor);gateway.setExecutor(executor);
        target.createContext("/stream",exchange->{exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);try(OutputStream output=exchange.getResponseBody()){output.write("data: hello\n\n".getBytes(StandardCharsets.UTF_8));output.flush();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}});
        gateway.createContext("/",exchange->proxy.forward(exchange,target.getAddress().getPort()));target.start();gateway.start();
        try {
            Future<?> reader=executor.submit(()->{try{HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+gateway.getAddress().getPort()+"/stream").openConnection();c.setReadTimeout(3000);assertEquals("DENY",c.getHeaderField("X-Frame-Options"));assertEquals("frame-ancestors 'none';",c.getHeaderField("Content-Security-Policy"));try(BufferedReader input=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){assertEquals("data: hello",input.readLine());first.countDown();input.readLine();input.readLine();}c.disconnect();}catch(IOException expected){}});
            assertTrue("SSE must arrive before upstream finishes",first.await(3,TimeUnit.SECONDS));proxy.close();reader.get(3,TimeUnit.SECONDS);
        } finally {release.countDown();proxy.close();gateway.stop(0);target.stop(0);executor.shutdownNow();}
    }
}
