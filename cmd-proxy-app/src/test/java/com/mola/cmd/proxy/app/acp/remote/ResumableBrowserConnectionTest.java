package com.mola.cmd.proxy.app.acp.remote;
import com.google.gson.*;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import static org.junit.Assert.*;
public class ResumableBrowserConnectionTest {
    static class Socket extends RemoteBrowserServer.BrowserConnection {
        final List<JsonObject> frames = new ArrayList<>();
        Socket() { super(null, new ConcurrentHashMap<>()); }
        public void send(JsonObject f) { frames.add(f.deepCopy()); }
        public void close() { }
    }
    private JsonObject hello() { return JsonParser.parseString("{\"type\":\"register\",\"name\":\"browser\",\"identity\":\"identity\",\"resumeToken\":\"token\",\"resumeReceived\":0}").getAsJsonObject(); }
    private JsonObject frame(long seq) { JsonObject f = new JsonObject(); f.addProperty("type", "resume_frame"); f.addProperty("seq", seq); f.add("payload", JsonParser.parseString("{\"type\":\"acp\",\"message\":{\"id\":1,\"result\":{\"sessionId\":\"same-session\"}}}")); return f; }
    @Test(timeout=3000) public void reconnectKeepsLogicalConnectionAndDeduplicatesLostAck() throws Exception {
        JsonObject h = hello(); ResumableBrowserConnection link = new ResumableBrowserConnection(h, () -> {});
        Socket a = new Socket(); link.attach(a,h,false); link.read();
        link.receive(a,frame(1)); assertEquals("acp",link.read().get("type").getAsString());
        link.detach(a,"test network loss"); assertTrue(link.isAlive());
        JsonObject result = JsonParser.parseString("{\"type\":\"result\"}").getAsJsonObject(); link.send(result);
        Socket b = new Socket(); link.attach(b,h,true);
        assertTrue(b.frames.get(0).get("resumed").getAsBoolean()); assertEquals(1,b.frames.get(0).get("received").getAsLong());
        assertEquals(result,b.frames.get(1).getAsJsonObject("payload"));
        link.receive(b,frame(1)); // Lost client ACK replay must not enqueue twice.
        link.receive(b,frame(2)); assertEquals("acp",link.read().get("type").getAsString());
        JsonObject ack = new JsonObject(); ack.addProperty("type","resume_ack"); ack.addProperty("seq",1); link.receive(b,ack);
        link.detach(b,"again"); Socket c = new Socket(); h.addProperty("resumeReceived",1); link.attach(c,h,true);
        assertEquals(1,c.frames.size()); link.close(); assertFalse(link.isAlive());
    }
    @Test public void rejectsIdentityChangeAndSequenceGaps() throws Exception {
        JsonObject h=hello(); ResumableBrowserConnection link=new ResumableBrowserConnection(h,()->{}); Socket a=new Socket(); link.attach(a,h,false);
        h.addProperty("identity","other"); assertFalse(link.matches(h));
        try { link.receive(a,frame(2)); fail(); } catch(IOException expected) { }
        link.close();
    }
    @Test public void expiredGraceEndsConnection() throws Exception {
        JsonObject h=hello(); ResumableBrowserConnection link=new ResumableBrowserConnection(h,()->{}); Socket a=new Socket(); link.attach(a,h,false); link.detach(a,"test");
        java.lang.reflect.Field field=ResumableBrowserConnection.class.getDeclaredField("detachedAt"); field.setAccessible(true); field.setLong(link,System.nanoTime()-TimeUnit.SECONDS.toNanos(61));
        assertFalse(link.isAlive()); try { link.attach(new Socket(),h,true); fail(); } catch(IOException expected) { }
        link.close();
    }
    @Test public void offlineBacklogUsesBoundedAcknowledgementWindows() throws Exception {
        JsonObject h=hello(); ResumableBrowserConnection link=new ResumableBrowserConnection(h,()->{});
        Socket a=new Socket(); link.attach(a,h,false); link.detach(a,"offline");
        for(int i=0;i<400;i++)link.send(new JsonObject());
        Socket b=new Socket(); link.attach(b,h,true); assertEquals(33,b.frames.size());
        for(int seq=32;seq<=384;seq+=32){JsonObject ack=new JsonObject();ack.addProperty("type","resume_ack");ack.addProperty("seq",seq);link.receive(b,ack);}
        assertEquals(401,b.frames.size());link.close();
    }

}
