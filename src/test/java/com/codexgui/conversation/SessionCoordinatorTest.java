package com.codexgui.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SessionCoordinatorTest {
    @Test
    void routesInterleavedTurnsWithoutChangingUiSelection() {
        var registry = new SessionRegistry();
        var coordinator = new SessionCoordinator(registry);
        var a = SessionId.of("A");
        var b = SessionId.of("B");
        var c = SessionId.of("C");
        var turnA = coordinator.beginTurn(a);
        var turnB = coordinator.beginTurn(b);
        registry.activate(c);

        assertTrue(coordinator.apply(turnB, session -> session.transcript().add(entry("B"))));
        assertTrue(coordinator.apply(turnA, session -> session.transcript().add(entry("A"))));

        assertEquals(c, registry.activeSessionId());
        assertEquals("A", registry.find(a).transcript().getFirst().body());
        assertEquals("B", registry.find(b).transcript().getFirst().body());
    }

    @Test
    void rejectsCancelledAndClosedTurnEvents() {
        var registry = new SessionRegistry();
        var coordinator = new SessionCoordinator(registry);
        var sessionId = SessionId.of("A");
        var turn = coordinator.beginTurn(sessionId);

        coordinator.cancel(sessionId);
        assertFalse(coordinator.apply(turn, session -> session.title("late")));

        coordinator.close(sessionId);
        assertNull(registry.find(sessionId));
        assertFalse(coordinator.apply(turn, session -> session.title("restored")));
    }

    @Test
    void completingTurnRejectsLateEventsAndKeepsQueuedStateOwnedBySession() {
        var registry = new SessionRegistry();
        var coordinator = new SessionCoordinator(registry);
        var sessionId = SessionId.of("A");
        var turn = coordinator.beginTurn(sessionId);
        var session = registry.find(sessionId);
        session.queuedInputs().add(new QueuedInput("next", "next", null, null));

        assertTrue(coordinator.complete(turn, current -> current.title("completed")));
        assertFalse(coordinator.apply(turn, current -> current.title("late")));
        assertEquals("completed", session.title());
        assertEquals(1, session.queuedInputs().size());
        assertFalse(session.busy());
    }

    @Test
    void newerGenerationRejectsOldTurnAndCloseReleasesSessionState() {
        var registry = new SessionRegistry();
        var coordinator = new SessionCoordinator(registry);
        var sessionId = SessionId.of("A");
        var oldTurn = coordinator.beginTurn(sessionId);
        var session = registry.find(sessionId);
        session.transcript().add(entry("old"));
        session.queuedInputs().add(new QueuedInput("next", "next", null, null));

        var newTurn = coordinator.beginTurn(sessionId);
        assertFalse(coordinator.apply(oldTurn, current -> current.title("stale")));
        assertTrue(coordinator.apply(newTurn, current -> current.title("current")));

        coordinator.close(sessionId);
        assertNull(registry.find(sessionId));
        assertTrue(session.transcript().isEmpty());
        assertTrue(session.queuedInputs().isEmpty());
        assertNull(session.handle());
        assertFalse(session.busy());
    }

    private static com.codexgui.model.ConversationEntry entry(String body) {
        return new com.codexgui.model.ConversationEntry(
            com.codexgui.model.ConversationEntry.Kind.ASSISTANT, "测试", body, "item"
        );
    }
}
