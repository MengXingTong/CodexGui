package com.codexgui.bridge;

import com.codexgui.conversation.SessionId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public sealed interface BridgeEvent permits BridgeEvent.V1 {
    int version();
    Type type();
    String requestId();
    SessionId sessionId();
    String turnId();
    long generation();
    JsonObject payload();

    record V1(
        Type type,
        String requestId,
        SessionId sessionId,
        String turnId,
        long generation,
        JsonObject payload
    ) implements BridgeEvent {
        @Override public int version() { return 1; }
    }

    final class Builder {
        private final Type type;
        private final SessionId sessionId;
        private final String turnId;
        private final long generation;
        private final JsonObject payload = new JsonObject();
        private String requestId = UUID.randomUUID().toString();

        public Builder(Type type, SessionId sessionId, String turnId, long generation) {
            this.type = Objects.requireNonNull(type, "type");
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            this.turnId = Objects.requireNonNullElse(turnId, "");
            this.generation = Math.max(0, generation);
        }

        public Builder requestId(String value) {
            if (value != null && !value.isBlank()) requestId = value;
            return this;
        }

        public void add(String key, JsonElement value) { payload.add(key, value); }
        public void addProperty(String key, String value) { payload.addProperty(key, value); }
        public void addProperty(String key, boolean value) { payload.addProperty(key, value); }
        public void addProperty(String key, Number value) { payload.addProperty(key, value); }

        public BridgeEvent build() {
            return new V1(type, requestId, sessionId, turnId, generation, payload.deepCopy());
        }
    }

    enum Type {
        BOOTSTRAP("bootstrap"),
        CONNECTION("connection"),
        BUSY("busy"),
        QUEUE("queue"),
        CLEAR("clear"),
        MESSAGE("message"),
        REPLACE_MESSAGE("replaceMessage"),
        APPEND_MESSAGE("appendMessage"),
        HISTORY("history"),
        CHANGES("changes"),
        ATTACHMENTS("attachments"),
        FILE_REFERENCES("fileReferences"),
        FILE_CONTEXT("fileContext"),
        PROJECT_FILES("projectFiles"),
        USAGE("usage"),
        THREAD("thread"),
        PROVIDERS("providers"),
        MCP_SERVERS("mcpServers"),
        MCP_LOG("mcpLog"),
        SKILL_ENABLED("skillEnabled"),
        SKILLS("skills"),
        QUESTION("question"),
        TOAST("toast"),
        NATIVE_DRAG("nativeDrag"),
        NATIVE_DROP("nativeDrop"),
        PROTOCOL_ERROR("protocol.error");

        private final String wireName;

        Type(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() { return wireName; }

        public static Type fromWireName(String value) {
            return Arrays.stream(values()).filter(type -> type.wireName.equals(value)).findFirst().orElse(null);
        }
    }
}
