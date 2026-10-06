package com.mola.cmd.proxy.app.acp.acpclient.context;

import com.mola.cmd.proxy.app.acp.starweave.StarweaveIdentity;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class ConversationUiProjectionTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private ConversationHistoryManager history() throws Exception {
        return new ConversationHistoryManager(StarweaveIdentity.identity("test", "robot"),
                temp.newFolder().toPath());
    }
    @Test public void acceptedUserIsVisibleBeforeProviderPromptAndThenMergesById() throws Exception {
        ConversationHistoryManager h = history();
        h.acceptUiMessage("send-1", "你好", Collections.singletonList("image.png"));
        assertEquals(1, h.getUiHistory("s").size());
        assertTrue(h.getFullHistory("s").isEmpty());
        h.addUserMessage("你好", ContextMessage.UserOrigin.USER,
                Collections.singletonList("image.png"), "send-1");
        assertEquals(1, h.getUiHistory("s").size());
        assertEquals("send-1", h.getUiHistory("s").get(0).getMessageId());
        h.addUserMessage("你好", ContextMessage.UserOrigin.USER, Collections.emptyList(), "send-2");
        assertEquals(2, h.getUiHistory("s").size());
    }
    @Test public void partialAssistantHasStableIdentityAndFinalHistoryKeepsIt() throws Exception {
        ConversationHistoryManager h = history();
        ContextMessage first = h.appendUiAssistant("hello");
        ContextMessage second = h.appendUiAssistant(" world");
        assertEquals(first.getMessageId(), second.getMessageId());
        assertTrue(second.getRevision() > first.getRevision());
        assertTrue(h.getFullHistory("s").isEmpty());
        assertEquals("hello world", h.getUiHistory("s").get(0).getContent());
        h.addAssistantMessage("hello world");
        List<ContextMessage> snapshot = h.getUiHistory("s");
        assertEquals(1, snapshot.size());
        assertEquals(first.getMessageId(), snapshot.get(0).getMessageId());
        assertTrue(snapshot.get(0).getRevision() > second.getRevision());
        h.flushTurn("s");
        assertEquals(first.getMessageId(), h.getUiHistory("s").get(0).getMessageId());
    }
    @Test public void sessionResetClearsAllTransientProjection() throws Exception {
        ConversationHistoryManager h = history();
        h.acceptUiMessage("send", "hi", Collections.emptyList());
        h.appendUiAssistant("partial");
        h.reset();
        assertTrue(h.getUiHistory("new").isEmpty());
    }
    @Test public void interruptedPartialDoesNotJoinTheNextReply() throws Exception {
        ConversationHistoryManager h = history();
        ContextMessage interrupted = h.appendUiAssistant("partial");
        h.finishUiAssistant();
        ContextMessage next = h.appendUiAssistant("next");
        assertNotEquals(interrupted.getMessageId(), next.getMessageId());
        assertEquals("next", next.getContent());
        assertEquals(2, h.getUiHistory("s").size());
    }
    @Test public void identitiesAndAttachmentsSurviveDiskReload() throws Exception {
        java.nio.file.Path root = temp.newFolder().toPath();
        ConversationHistoryManager h = new ConversationHistoryManager(
                StarweaveIdentity.identity("test", "robot"), root);
        h.addUserMessage("你好", ContextMessage.UserOrigin.USER,
                Collections.singletonList("photo.png"), "send-1");
        String replyId = h.appendUiAssistant("reply").getMessageId();
        h.addAssistantMessage("reply");
        h.flushTurn("s");
        ConversationHistoryManager restored = new ConversationHistoryManager(
                StarweaveIdentity.identity("test", "robot"), root);
        List<ContextMessage> messages = restored.getUiHistory("s");
        assertEquals(2, messages.size());
        assertEquals("send-1", messages.get(0).getMessageId());
        assertEquals(Collections.singletonList("photo.png"), messages.get(0).getAttachments());
        assertEquals(replyId, messages.get(1).getMessageId());
    }
    @Test public void fallbackIdentitiesStayStableWhenUnstructuredMessagesFlushToDisk() throws Exception {
        ConversationHistoryManager h = history();
        h.addUserMessage("legacy input"); h.addAssistantMessage("unstructured reply");
        List<ContextMessage> first = h.getUiHistory("s");
        String userId = first.get(0).getMessageId(), replyId = first.get(1).getMessageId();
        assertNotNull(userId); assertNotNull(replyId);
        h.flushTurn("s");
        assertEquals(userId, h.getUiHistory("s").get(0).getMessageId());
        assertEquals(replyId, h.getUiHistory("s").get(1).getMessageId());
    }
}
