package com.mola.cmd.proxy.app.acp.registry.tunnel;

import com.alibaba.fastjson.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RealtimePeerTest {
    @Test public void disconnectedAdmissionIsBoundedAndCloseRejectsFurtherEvents() {
        RealtimePeer peer = new RealtimePeer(); JSONObject payload = new JSONObject(); payload.put("content", "text");
        for (int i = 0; i < 2048; i++) assertTrue(peer.offer(payload));
        assertFalse(peer.offer(payload)); peer.close(); assertFalse(peer.offer(payload));
    }
    @Test public void oversizedFrameIsRejectedWithoutConsumingAdmission() {
        RealtimePeer peer = new RealtimePeer(); JSONObject payload = new JSONObject();
        char[] text = new char[RealtimePeer.MAX_FRAME]; java.util.Arrays.fill(text, 'x'); payload.put("content", new String(text));
        assertFalse(peer.offer(payload)); assertTrue(peer.offer(new JSONObject())); peer.close();
    }
}
