package com.codexgui.provider;

import com.codexgui.conversation.SessionId;
import com.codexgui.conversation.TurnHandle;
import com.codexgui.conversation.TurnId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class ClaudeConversationProviderTest {
    private final TurnHandle handle = new TurnHandle(new SessionId("session-a"), new TurnId("turn-a"), 1);

    @Test
    void assistantSegmentsUseDistinctStableItemIds() {
        assertEquals("claude:turn-a:assistant:0", ClaudeConversationProvider.assistantItemId(handle, 0));
        assertEquals("claude:turn-a:assistant:1", ClaudeConversationProvider.assistantItemId(handle, 1));
        assertNotEquals(
            ClaudeConversationProvider.assistantItemId(handle, 0),
            ClaudeConversationProvider.thinkingItemId(handle));
    }
}
