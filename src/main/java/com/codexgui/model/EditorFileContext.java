package com.codexgui.model;

import java.util.Objects;

public record EditorFileContext(String path, int startLine, int endLine, String selectedText) {
    public EditorFileContext {
        path = Objects.requireNonNullElse(path, "");
        selectedText = Objects.requireNonNullElse(selectedText, "");
        if (selectedText.isEmpty()) {
            startLine = 0;
            endLine = 0;
        }
    }

    public boolean hasSelection() {
        return !selectedText.isEmpty();
    }

    public String displayLabel() {
        if (!hasSelection()) return path;
        return path + ":" + (startLine == endLine ? "L" + startLine : "L" + startLine + "-L" + endLine);
    }

    public String appendTo(String userText) {
        var context = new StringBuilder(userText == null ? "" : userText);
        context.append("\n\n[当前编辑器上下文]\n文件: ").append(path);
        if (hasSelection()) {
            context.append("\n行号: ").append(startLine);
            if (endLine != startLine) context.append('-').append(endLine);
            context.append("\n选中内容:\n").append(selectedText);
        }
        return context.toString().trim();
    }
}
