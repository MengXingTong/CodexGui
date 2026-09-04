package com.codexgui.conversation;

public record TurnHandle(SessionId sessionId, TurnId turnId, long generation) {
    public TurnHandle {
        if (sessionId == null) throw new IllegalArgumentException("Turn 必须属于 Session");
        if (turnId == null) throw new IllegalArgumentException("Turn ID 不能为空");
        if (generation < 1) throw new IllegalArgumentException("Turn generation 必须大于零");
    }
}
