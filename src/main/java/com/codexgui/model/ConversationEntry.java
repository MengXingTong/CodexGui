package com.codexgui.model;

public record ConversationEntry(Kind kind, String title, String body, String itemId) {
    public enum Kind { USER, ASSISTANT, REASONING, PLAN, COMMAND, MCP, NOTICE, ERROR }
}
