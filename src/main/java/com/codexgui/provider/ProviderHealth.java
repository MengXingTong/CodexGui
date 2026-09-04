package com.codexgui.provider;

import java.util.Objects;

public record ProviderHealth(Status status, String detail) {
    public enum Status { READY, UNAVAILABLE, FAILED, DISPOSED }

    public ProviderHealth {
        Objects.requireNonNull(status, "status");
        detail = Objects.requireNonNullElse(detail, "");
    }
}
