package com.codexgui.conversation;

import java.util.Objects;

public record TurnId(String value) {
    public TurnId {
        value = Objects.requireNonNullElse(value, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Turn ID 不能为空");
    }
}
