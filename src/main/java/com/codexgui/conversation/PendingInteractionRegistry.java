package com.codexgui.conversation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class PendingInteractionRegistry {
    public enum Type { COMMAND_APPROVAL, FILE_APPROVAL, USER_INPUT, PERMISSIONS_APPROVAL }

    public record PendingInteraction(
        String requestId,
        TurnHandle turnHandle,
        Type type,
        Instant deadline
    ) {
        public PendingInteraction {
            if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("requestId 不能为空");
            Objects.requireNonNull(turnHandle, "turnHandle");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(deadline, "deadline");
        }

        public boolean expired(Instant now) { return !deadline.isAfter(now); }
    }

    private final Map<String, PendingInteraction> interactions = new LinkedHashMap<>();

    public synchronized PendingInteraction register(
        String requestId,
        TurnHandle turnHandle,
        Type type,
        Instant deadline
    ) {
        var interaction = new PendingInteraction(requestId, turnHandle, type, deadline);
        interactions.put(requestId, interaction);
        return interaction;
    }

    public synchronized PendingInteraction take(String requestId) {
        if (requestId == null || requestId.isBlank()) return null;
        return interactions.remove(requestId);
    }

    public synchronized PendingInteraction take(
        String requestId,
        SessionId sessionId,
        String turnId,
        long generation,
        Type type,
        boolean allowLegacyIdentity
    ) {
        var interaction = interactions.get(requestId);
        if (interaction == null || interaction.type() != type) return null;
        var handle = interaction.turnHandle();
        var identityMatches = handle.sessionId().equals(sessionId)
            && handle.generation() == generation
            && Objects.equals(handle.turnId().value(), turnId);
        if (!identityMatches && (!allowLegacyIdentity || !handle.sessionId().equals(sessionId))) return null;
        interactions.remove(requestId);
        return interaction;
    }

    public synchronized PendingInteraction find(String requestId) {
        if (requestId == null || requestId.isBlank()) return null;
        return interactions.get(requestId);
    }

    public synchronized void clearTurn(TurnHandle turnHandle) {
        if (turnHandle == null) return;
        interactions.values().removeIf(interaction -> interaction.turnHandle().equals(turnHandle));
    }

    public synchronized void clearSession(SessionId sessionId) {
        if (sessionId == null) return;
        interactions.values().removeIf(interaction -> interaction.turnHandle().sessionId().equals(sessionId));
    }

    public synchronized void clearExpired(Instant now) {
        interactions.values().removeIf(interaction -> interaction.expired(now));
    }

    public synchronized void clear() { interactions.clear(); }

    public synchronized int size() { return interactions.size(); }
}
