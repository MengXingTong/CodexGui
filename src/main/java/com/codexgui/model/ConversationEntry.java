package com.codexgui.model;

import java.util.List;

public record ConversationEntry(
    Kind kind,
    String title,
    String body,
    String itemId,
    List<String> fileReferencePaths,
    long createdAtEpochMs
) {
    public ConversationEntry {
        fileReferencePaths = fileReferencePaths == null ? List.of() : List.copyOf(fileReferencePaths);
        if (createdAtEpochMs <= 0) createdAtEpochMs = System.currentTimeMillis();
    }

    public ConversationEntry(Kind kind, String title, String body, String itemId, List<String> fileReferencePaths) {
        this(kind, title, body, itemId, fileReferencePaths, System.currentTimeMillis());
    }

    public ConversationEntry(Kind kind, String title, String body, String itemId) {
        this(kind, title, body, itemId, List.of(), System.currentTimeMillis());
    }

    public enum Kind { USER, ASSISTANT, REASONING, PLAN, COMMAND, MCP, NOTICE, ERROR }
}
