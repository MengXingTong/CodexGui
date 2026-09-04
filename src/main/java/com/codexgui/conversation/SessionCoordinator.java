package com.codexgui.conversation;

import java.util.function.Consumer;

/** Session state is called from the EDT, which acts as the serialized mailbox. */
public final class SessionCoordinator {
    private final SessionRegistry registry;

    public SessionCoordinator(SessionRegistry registry) {
        this.registry = registry;
    }

    public synchronized TurnHandle beginTurn(SessionId sessionId) {
        return registry.create(sessionId).beginTurn();
    }

    public synchronized boolean apply(TurnHandle handle, Consumer<ConversationSession> change) {
        var session = registry.find(handle.sessionId());
        if (session == null || !session.matches(handle)) return false;
        change.accept(session);
        return true;
    }

    public synchronized boolean isCurrent(TurnHandle handle) {
        var session = registry.find(handle.sessionId());
        return session != null && session.matches(handle);
    }

    public synchronized boolean complete(TurnHandle handle, Consumer<ConversationSession> change) {
        var session = registry.find(handle.sessionId());
        if (session == null || !session.matches(handle)) return false;
        session.completeTurn(handle);
        change.accept(session);
        return true;
    }

    public synchronized void cancel(SessionId sessionId) {
        var session = registry.find(sessionId);
        if (session != null) session.invalidateTurn();
    }

    public synchronized void close(SessionId sessionId) {
        var session = registry.remove(sessionId);
        if (session != null) session.clearConversation();
    }
}
