package com.codexgui.provider;

import com.codexgui.conversation.SessionId;
import com.codexgui.conversation.TurnHandle;
import com.codexgui.conversation.TurnId;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class TurnEventTest {
    @Test
    void everyEventRetainsTheCompleteTurnIdentity() {
        var handle = new TurnHandle(new SessionId("session-b"), new TurnId("turn-b"), 7);
        var input = new LinkedHashMap<String, Object>();
        input.put("path", "src/Main.java");

        var event = new TurnEvent.Tool(handle, "tool-1", "Edit", input);
        input.put("path", "changed");

        assertEquals(handle, event.handle());
        assertEquals("src/Main.java", event.input().get("path"));
        assertThrows(UnsupportedOperationException.class, () -> event.input().put("other", true));
    }
}
