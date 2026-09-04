package com.codexgui.provider;

import com.codexgui.conversation.TurnHandle;

import java.util.Map;
import java.util.Objects;

public sealed interface TurnEvent permits
    TurnEvent.Started,
    TurnEvent.ModelSelected,
    TurnEvent.Delta,
    TurnEvent.Tool,
    TurnEvent.Item,
    TurnEvent.Change,
    TurnEvent.Usage,
    TurnEvent.Completed,
    TurnEvent.Failed {

    TurnHandle handle();

    record Started(TurnHandle handle, String conversationId, String providerTurnId) implements TurnEvent {
        public Started {
            Objects.requireNonNull(handle, "handle");
            conversationId = Objects.requireNonNullElse(conversationId, "");
            providerTurnId = Objects.requireNonNullElse(providerTurnId, "");
        }
    }

    record ModelSelected(TurnHandle handle, String model) implements TurnEvent {
        public ModelSelected {
            Objects.requireNonNull(handle, "handle");
            model = Objects.requireNonNullElse(model, "");
        }
    }

    record Delta(TurnHandle handle, Kind kind, String itemId, String text) implements TurnEvent {
        public enum Kind { TEXT, THINKING, PLAN, COMMAND }

        public Delta {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(kind, "kind");
            itemId = Objects.requireNonNullElse(itemId, "");
            text = Objects.requireNonNullElse(text, "");
        }

        public Delta(TurnHandle handle, Kind kind, String text) {
            this(handle, kind, "", text);
        }
    }

    record Tool(TurnHandle handle, String id, String name, Map<String, Object> input) implements TurnEvent {
        public Tool {
            Objects.requireNonNull(handle, "handle");
            id = Objects.requireNonNullElse(id, "");
            name = Objects.requireNonNullElse(name, "");
            input = java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(Objects.requireNonNullElse(input, Map.of())));
        }
    }

    record Item(TurnHandle handle, Phase phase, String id, String kind, Map<String, Object> data) implements TurnEvent {
        public enum Phase { STARTED, UPDATED, COMPLETED }

        public Item {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(phase, "phase");
            id = Objects.requireNonNullElse(id, "");
            kind = Objects.requireNonNullElse(kind, "");
            data = java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(Objects.requireNonNullElse(data, Map.of())));
        }
    }

    record Change(TurnHandle handle, String unifiedDiff) implements TurnEvent {
        public Change {
            Objects.requireNonNull(handle, "handle");
            unifiedDiff = Objects.requireNonNullElse(unifiedDiff, "");
        }
    }

    record Usage(TurnHandle handle, long usedTokens, long maxTokens) implements TurnEvent {
        public Usage {
            Objects.requireNonNull(handle, "handle");
            usedTokens = Math.max(0, usedTokens);
            maxTokens = Math.max(0, maxTokens);
        }
    }

    record Completed(TurnHandle handle, String conversationId, String model, String finalText) implements TurnEvent {
        public Completed {
            Objects.requireNonNull(handle, "handle");
            conversationId = Objects.requireNonNullElse(conversationId, "");
            model = Objects.requireNonNullElse(model, "");
            finalText = Objects.requireNonNullElse(finalText, "");
        }
    }

    record Failed(TurnHandle handle, Throwable error, boolean cancelled) implements TurnEvent {
        public Failed {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(error, "error");
        }
    }
}
