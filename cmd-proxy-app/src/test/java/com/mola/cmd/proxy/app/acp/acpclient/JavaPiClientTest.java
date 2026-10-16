package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.agent.*;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;
import com.mola.starweave.agent.EmbeddedAgentRuntime;
import com.sun.net.httpserver.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.util.*;
import static org.junit.Assert.*;

/** Exercises the actual AbstractAcpClient launch, handshake, prompt and shutdown path. */
public class JavaPiClientTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test(timeout=20000) public void existingClientUsesJavaRuntimeWithoutNodeAndPreservesProjections() throws Exception {
        String oldHome = System.getProperty("cmd.proxy.home"); System.setProperty("cmd.proxy.home", temporary.newFolder("home").toString());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            while (exchange.getRequestBody().read() != -1) { }
            byte[] response = ("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Java engine works\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
        try {
            AcpRobotParam robot = new AcpRobotParam(); robot.setAgentProvider("JAVA_PI_ACP"); robot.setName("Java Pi"); robot.setWorkDir(temporary.newFolder("workspace").toString()); robot.setModel("test-model");
            EmbeddedPiConfig config = new EmbeddedPiConfig(); config.setApiKey("isolated-key"); config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort()); config.setStateId("java-pi-test"); robot.setEmbeddedPi(config);
            assertTrue(AgentProviderRouter.getInstance().resolve(robot.getAgentProvider()) instanceof JavaPiAgentProvider);
            try (Client client = new Client(robot)) { client.start(); assertTrue(client.isEmbedded()); assertEquals("Java engine works", client.prompt()); }
            JavaPiAgentProvider provider = new JavaPiAgentProvider(); Map<String,String> environment = new HashMap<>(); environment.put("PATH", ""); provider.prepareLaunch(robot, environment);
            assertFalse(environment.containsKey("STARWEAVE_PI_NODE")); assertTrue(provider.supportsCompactionUpdates()); assertEquals("session/close", provider.getSessionCloseMethod());
        } finally { server.stop(0); if (oldHome == null) System.clearProperty("cmd.proxy.home"); else System.setProperty("cmd.proxy.home", oldHome); }
    }
    private static class Client extends AbstractAcpClient {
        Client(AcpRobotParam robot) { super(new JavaPiAgentProvider(), robot.getWorkDir(), "java-pi-integration", robot); }
        @Override protected void createSession() throws IOException {
            JsonObject params = new JsonObject(); params.addProperty("cwd", workspacePath); params.add("mcpServers", new JsonArray());
            sessionId = sendRequest("session/new", params).getAsJsonObject("result").get("sessionId").getAsString();
        }
        boolean isEmbedded() { return process instanceof EmbeddedAgentRuntime; }
        String prompt() throws IOException { return doSendPromptSync("hello", "Java Pi test"); }
    }
}
