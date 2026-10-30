package com.mola.cmd.proxy.app.acp.remote;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.channel.ChannelConfigFileStore;
import com.mola.starweave.agent.transport.AcpTransport;
import com.mola.starweave.sdk.Wire;
import com.mola.starweave.sdk.sync.ResourceFiles;
import com.sun.net.httpserver.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Actual Chrome extension -> actual Java WebSocket/ACP manager -> mock model/MCP -> real page. */
public class RemoteBrowserChromeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test(timeout=90000) public void extensionUsesRealCenterAndRealPage() throws Exception {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.isDirectory(root.resolve("browser-agent"))) root=root.getParent();
        Path browser=root.resolve("browser-agent");
        Assume.assumeTrue("Build browser-agent and install its dev dependencies for Chrome E2E", Files.exists(browser.resolve("dist/background.js"))&&Files.exists(browser.resolve("node_modules/playwright-core/index.mjs"))&&Files.exists(Paths.get("/usr/bin/google-chrome")));
        Path config=temp.newFile("config.json").toPath();Files.write(config,"{\"robots\":[],\"remoteAcp\":{\"browserPort\":0,\"bindHost\":\"127.0.0.1\"}}".getBytes(StandardCharsets.UTF_8));
        RemoteAcpManager manager=new RemoteAcpManager(config,(a,b)->{});
        HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ExecutorService executor=Executors.newCachedThreadPool();http.setExecutor(executor);
        Fixture fixture=new Fixture(config,manager);
        http.createContext("/api/remote-acp/discover",manager::handleDiscovery);
        http.createContext("/test/disconnect",fixture::disconnect);http.createContext("/test/backup",fixture::backup);http.createContext("/test/config",fixture::configure);http.createContext("/test/acp",fixture::acp);http.start();
        Path log=temp.newFile("chrome.log").toPath();Process process=null;
        try {
            ProcessBuilder builder=new ProcessBuilder("node",browser.resolve("test/browser-e2e.mjs").toString());builder.directory(root.toFile());
            builder.environment().put("STARWEAVE_TEST_CENTER","http://127.0.0.1:"+http.getAddress().getPort());builder.redirectErrorStream(true).redirectOutput(log.toFile());process=builder.start();
            assertTrue("Chrome E2E timeout: "+new String(Files.readAllBytes(log),StandardCharsets.UTF_8),process.waitFor(70,TimeUnit.SECONDS));
            String output=new String(Files.readAllBytes(log),StandardCharsets.UTF_8);assertEquals(output,0,process.exitValue());assertTrue(output,output.contains("\"passed\":true"));
            assertTrue(Files.exists(RemoteResources.skillRoot(fixture.robot()).getParent().resolve("browser-sessions")));
        } finally {
            if(process!=null&&process.isAlive())process.destroyForcibly();fixture.close();manager.close();http.stop(0);executor.shutdownNow();
        }
    }
    private static final class Fixture {
        final Path config;final RemoteAcpManager manager;AcpTransport transport;BufferedReader reader;
        Fixture(Path config,RemoteAcpManager manager){this.config=config;this.manager=manager;}
        AcpRobotParam robot()throws IOException{return JSON.parseObject(new String(Files.readAllBytes(config),StandardCharsets.UTF_8)).getJSONArray("robots").getJSONObject(0).toJavaObject(AcpRobotParam.class);}
        JsonObject body(HttpExchange e){return JsonParser.parseReader(new InputStreamReader(e.getRequestBody(),StandardCharsets.UTF_8)).getAsJsonObject();}
        void reply(HttpExchange e,int status,JsonObject value)throws IOException{byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();}
        void configure(HttpExchange e)throws IOException{
            JsonObject value=body(e),response=new JsonObject();
            if(!RemoteAcpManager.online("browser-e2e")){reply(e,409,response);return;}
            ChannelConfigFileStore.updateConfig(config,root->{JSONObject r=RemoteAcpManager.find(root.getJSONArray("robots"),"browser-e2e");r.put("model",Wire.str(value,"model"));r.put("workDir",config.getParent().toString());r.getJSONObject("embeddedPi").put("baseUrl",Wire.str(value,"baseUrl"));r.getJSONObject("embeddedPi").put("apiKey",Wire.str(value,"apiKey"));r.getJSONObject("embeddedPi").put("contextWindow",100000);r.getJSONObject("embeddedPi").put("maxTokens",2000);});
            Path skills=config.getParent().resolve(".agents/skills");ResourceFiles.write(skills.resolve("test-orders/SKILL.md"),"---\nname: test-orders\ndescription: Read orders using browser tools\n---\nRead references/status.md".getBytes(StandardCharsets.UTF_8));ResourceFiles.write(skills.resolve("test-orders/references/status.md"),"pending means waiting".getBytes(StandardCharsets.UTF_8));response.addProperty("ok",true);response.addProperty("cwd",config.getParent().toString());reply(e,200,response);
        }
        void disconnect(HttpExchange e)throws IOException{
            JsonObject result=new JsonObject();
            try {
                java.lang.reflect.Field pf=RemoteAcpManager.class.getDeclaredField("peers");pf.setAccessible(true);
                Object peer=((java.util.Map<?,?>)pf.get(manager)).get("browser-e2e");
                java.lang.reflect.Field lf=peer.getClass().getDeclaredField("link");lf.setAccessible(true);
                Object link=lf.get(peer);
                java.lang.reflect.Field sf=ResumableBrowserConnection.class.getDeclaredField("socket");sf.setAccessible(true);
                ((RemoteBrowserServer.BrowserConnection)sf.get(link)).close();
                result.addProperty("ok",true);reply(e,200,result);
            } catch(Exception error){result.addProperty("error",error.toString());reply(e,500,result);}
        }
        void backup(HttpExchange e)throws IOException{
            JsonObject result=new JsonObject();String id=com.mola.starweave.sdk.sync.SessionSnapshots.id(e.getRequestURI().getQuery());
            Path file=RemoteResources.skillRoot(robot()).getParent().resolve("browser-sessions").resolve(id+".json");
            result.addProperty("exists",Files.isRegularFile(file));reply(e,200,result);
        }
        synchronized void acp(HttpExchange e)throws IOException{
            JsonObject request=body(e),response=new JsonObject();JsonArray events=new JsonArray();
            try{
                if(transport==null){transport=manager.open(robot());reader=new BufferedReader(new InputStreamReader(transport.input(),StandardCharsets.UTF_8));}
                write(request);
                for(;;){String line=reader.readLine();if(line==null)throw new EOFException("ACP closed");JsonObject msg=JsonParser.parseString(line).getAsJsonObject();

                    if("session/request_permission".equals(Wire.str(msg,"method"))){JsonObject answer=new JsonObject();answer.addProperty("jsonrpc","2.0");answer.add("id",msg.get("id"));answer.add("result",JsonParser.parseString("{\"outcome\":{\"outcome\":\"selected\",\"optionId\":\"allow_once\"}}"));write(answer);}
                    else if("session/update".equals(Wire.str(msg,"method")))events.add(msg.getAsJsonObject("params").get("update"));
                    else if(msg.has("id")&&msg.get("id").equals(request.get("id"))){if(msg.has("error"))response.add("error",msg.get("error"));else response.add("result",msg.get("result"));break;}
                }
                response.add("events",events);reply(e,200,response);
            }catch(Exception error){response.addProperty("message",error.getMessage());reply(e,500,response);}
        }
        void write(JsonObject message)throws IOException{transport.output().write((message+"\n").getBytes(StandardCharsets.UTF_8));}
        void close(){if(transport!=null)try{transport.close();}catch(IOException ignored){}}
    }
}
