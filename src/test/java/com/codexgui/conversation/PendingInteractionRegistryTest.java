package com.codexgui.conversation;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class PendingInteractionRegistryTest {
    @Test
    void isolatesRequestsBySessionTurnGenerationAndType() {
        var registry = new PendingInteractionRegistry();
        var first = handle("session-a", "turn-a", 1);
        var second = handle("session-b", "turn-b", 4);
        registry.register("41", first, PendingInteractionRegistry.Type.USER_INPUT, Instant.now().plusSeconds(30));
        registry.register("42", second, PendingInteractionRegistry.Type.USER_INPUT, Instant.now().plusSeconds(30));

        assertNull(registry.take("41", SessionId.of("session-b"), "turn-b", 4,
            PendingInteractionRegistry.Type.USER_INPUT, false));
        assertNull(registry.take("41", SessionId.of("session-a"), "turn-a", 2,
            PendingInteractionRegistry.Type.USER_INPUT, false));
        assertSame(first, registry.take("41", SessionId.of("session-a"), "turn-a", 1,
            PendingInteractionRegistry.Type.USER_INPUT, false).turnHandle());
        assertEquals(1, registry.size());
    }

    @Test
    void clearsOnlyMatchingTurnSessionOrExpiredRequest() {
        var registry = new PendingInteractionRegistry();
        var first = handle("session-a", "turn-a", 1);
        var second = handle("session-a", "turn-b", 2);
        var third = handle("session-b", "turn-c", 1);
        registry.register("1", first, PendingInteractionRegistry.Type.COMMAND_APPROVAL, Instant.now().plusSeconds(30));
        registry.register("2", second, PendingInteractionRegistry.Type.FILE_APPROVAL, Instant.now().plusSeconds(30));
        registry.register("3", third, PendingInteractionRegistry.Type.USER_INPUT, Instant.now().minusSeconds(1));

        registry.clearTurn(first);
        registry.clearExpired(Instant.now());
        assertEquals(1, registry.size());
        registry.clearSession(SessionId.of("session-a"));
        assertEquals(0, registry.size());
    }

    private TurnHandle handle(String session, String turn, long generation) {
        return new TurnHandle(SessionId.of(session), new TurnId(turn), generation);
    }
}
