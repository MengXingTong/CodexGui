package com.codexgui.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EditorFileContextTest {
    @Test
    void fileOnlyContextKeepsTheUserTextAndPath() {
        var context = new EditorFileContext("src/App.java", 0, 0, "");

        assertFalse(context.hasSelection());
        assertEquals("src/App.java", context.displayLabel());
        assertEquals("检查这个文件\n\n[当前编辑器上下文]\n文件: src/App.java", context.appendTo("检查这个文件"));
    }

    @Test
    void selectedLinesIncludeRangeAndExactText() {
        var context = new EditorFileContext("src/App.java", 12, 14, "first();\nsecond();");

        assertTrue(context.hasSelection());
        assertEquals("src/App.java:L12-L14", context.displayLabel());
        assertTrue(context.appendTo("解释").endsWith("行号: 12-14\n选中内容:\nfirst();\nsecond();"));
    }
}
