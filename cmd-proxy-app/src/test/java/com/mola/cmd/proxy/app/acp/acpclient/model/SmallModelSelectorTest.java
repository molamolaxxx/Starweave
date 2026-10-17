package com.mola.cmd.proxy.app.acp.acpclient.model;

import com.alibaba.fastjson.JSON;
import org.junit.Test;
import static org.junit.Assert.*;

public class SmallModelSelectorTest {
    @Test public void recognizesSmallTextModelsIncludingLunaWithoutGuessingOtherModels() {
        for (String id : new String[]{"gpt-6-luna", "GPT-6-LUNA", "chat-mini", "chat-nano", "claude-haiku-4", "gemini-flash", "chat-lite", "chat-small"}) assertTrue(id, SmallModelSelector.isSmallModel(id));
        for (String id : new String[]{"main-large", "minimax-m2", "embedding-mini", "image-flash", "audio-mini", ""}) assertFalse(id, SmallModelSelector.isSmallModel(id));
        assertFalse(SmallModelSelector.isSmallModel(null));
    }
    @Test public void onlyDiscoveredModelsParticipateAndDisplayNamesCanIdentifySmallVariants() {
        assertNull(SmallModelSelector.select(JSON.parseArray("[{id:'chat-mini',source:'history'},{id:'chat-luna',source:'configured'}]")));
        assertEquals("private-id", SmallModelSelector.select(JSON.parseArray("[{id:'main-large',source:'api'},{id:'private-id',name:'Private Mini',source:'api'}]")));
        assertNull(SmallModelSelector.select(JSON.parseArray("[{id:'image-mini',source:'api'}]")));
    }
}
