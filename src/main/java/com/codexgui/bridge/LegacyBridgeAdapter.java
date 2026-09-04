package com.codexgui.bridge;

import com.codexgui.conversation.SessionId;
import com.google.gson.JsonObject;

import java.util.UUID;

final class LegacyBridgeAdapter {
    BridgeCommand adapt(JsonObject legacy) {
        var type = BridgeCommand.Type.fromWireName(string(legacy, "action"));
        if (type == null) return null;
        var payload = legacy.deepCopy();
        payload.remove("action");
        payload.remove("requestId");
        payload.remove("sessionId");
        payload.remove("turnId");
        payload.remove("generation");
        return new BridgeCommand.V1(
            type,
            value(legacy, "requestId", "legacy-" + UUID.randomUUID()),
            SessionId.of(value(legacy, "sessionId", "default")),
            value(legacy, "turnId", ""),
            longValue(legacy, "generation", 0),
            payload,
            true
        );
    }

    private String string(JsonObject object, String key) {
        return value(object, key, "");
    }

    private String value(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            var value = object.get(key).getAsString();
            return value.isBlank() ? fallback : value;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private long longValue(JsonObject object, String key, long fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return Math.max(0, object.get(key).getAsLong());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
