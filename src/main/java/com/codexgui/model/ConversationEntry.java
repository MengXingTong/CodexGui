package com.codexgui.model;

import java.util.List;

public record ConversationEntry(Kind kind, String title, String body, String itemId, List<String> fileReferencePaths) {
    public ConversationEntry {
        fileReferencePaths = fileReferencePaths == null ? List.of() : List.copyOf(fileReferencePaths);
    }

    public ConversationEntry(Kind kind, String title, String body, String itemId) {
        this(kind, title, body, itemId, List.of());
    }

    public enum Kind { USER, ASSISTANT, REASONING, PLAN, COMMAND, MCP, NOTICE, ERROR }
}
