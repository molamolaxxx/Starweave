package com.mola.cmd.proxy.app.acp.configui;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.acpclient.model.AgentModelCatalog;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class EmbeddedPiModelDiscoveryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void discoversFromIndependentEndpointAndRetainsManualModelOnFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/custom/v1/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] data = "{\"data\":[{\"id\":\"private-model\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length); exchange.getResponseBody().write(data); exchange.close();
        }); server.start();
        try {
            AgentModelCatalog catalog = new AgentModelCatalog(temporary.newFolder().toPath().resolve("models.json"));
            ProviderModelDiscoveryService service = new ProviderModelDiscoveryService(catalog);
            JSONObject request = JSON.parseObject("{\"provider\":\"EMBEDDED_PI_ACP\",\"model\":\"manual-model\",\"embeddedPi\":{\"apiKey\":\"pi-key\"}}");
            request.getJSONObject("embeddedPi").put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/custom/v1/");
            JSONObject result = service.models(request, true);
            assertEquals("Bearer pi-key", authorization.get()); assertTrue(result.toJSONString().contains("private-model"));
            assertFalse(result.toJSONString().contains("pi-key"));
            request.getJSONObject("embeddedPi").put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/another");
            result = service.models(request, true); assertTrue(result.toJSONString().contains("manual-model"));
            assertFalse(result.toJSONString().contains("private-model")); assertNotNull(result.getString("warning"));
        } finally { server.stop(0); }
    }
}
