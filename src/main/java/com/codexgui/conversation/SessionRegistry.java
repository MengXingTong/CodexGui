package com.codexgui.conversation;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SessionRegistry {
    private final Map<SessionId, ConversationSession> sessions = new LinkedHashMap<>();
    private SessionId activeSessionId = SessionId.of("default");

    public ConversationSession create(SessionId id) {
        return sessions.computeIfAbsent(id, ConversationSession::new);
    }

    public ConversationSession active() {
        return create(activeSessionId);
    }

    public ConversationSession activate(SessionId id) {
        activeSessionId = id;
        return create(id);
    }

    public ConversationSession find(SessionId id) {
        return sessions.get(id);
    }

    public ConversationSession findByThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) return null;
        return sessions.values().stream()
            .filter(session -> Objects.equals(session.threadId(), threadId))
            .findFirst()
            .orElse(null);
    }

    public ConversationSession remove(SessionId id) {
        var removed = sessions.remove(id);
        if (activeSessionId.equals(id) && !sessions.isEmpty()) activeSessionId = sessions.keySet().iterator().next();
        return removed;
    }

    public SessionId activeSessionId() { return activeSessionId; }
    public Collection<ConversationSession> sessions() { return List.copyOf(sessions.values()); }
}
