package com.codexgui.ui;

import com.codexgui.model.FileReference;
import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class CodexToolWindowControllerTest {
    @Test
    void normalizesWindowsDrivePrefixAndMarkdownEscapes() {
        assertEquals(
            "E:/UnrealProjects/BoundaryDreamland/Source/BoundaryDreamland_MagicTower/MTGameplay/MTPlayerState/MTPSComponent/MTTaskComponent.h",
            CodexToolWindowController.normalizeReportedFilePath(
                "/E:/UnrealProjects/BoundaryDreamland/Source/BoundaryDreamland\\_MagicTower/MTGameplay/MTPlayerState/MTPSComponent/MTTaskComponent.h"
            )
        );
    }

    @Test
    void resolvesProviderModelFromLastSelectionSuggestionOrFirstItem() {
        var models = List.of("first-model", "suggested-model", "saved-model");

        assertEquals("saved-model", CodexToolWindowController.selectProviderModel(
            models, "saved-model", "suggested-model"));
        assertEquals("suggested-model", CodexToolWindowController.selectProviderModel(
            models, "missing-model", "suggested-model"));
        assertEquals("first-model", CodexToolWindowController.selectProviderModel(
            models, "missing-model", "missing-suggestion"));
        assertEquals("", CodexToolWindowController.selectProviderModel(
            List.of(), "saved-model", "suggested-model"));
    }

    @Test
    void validatesAndAppliesEditorReferenceOrderAtomically() {
        var first = new FileReference("first", "First.cpp", Path.of("D:/Project/First.cpp"), false);
        var second = new FileReference("second", "Second.cpp", Path.of("D:/Project/Second.cpp"), false);

        assertEquals(List.of(second, first), CodexToolWindowController.orderedSendReferences(
            List.of(first, second), "左\uFFFC中\uFFFC右", ids("second", "first")));
        assertNull(CodexToolWindowController.orderedSendReferences(
            List.of(first, second), "只有一个\uFFFC", ids("first", "second")));
        assertNull(CodexToolWindowController.orderedSendReferences(
            List.of(first, second), "\uFFFC\uFFFC", ids("first", "first")));
        assertNull(CodexToolWindowController.orderedSendReferences(
            List.of(first, second), "\uFFFC\uFFFC", ids("first", "unknown")));
    }

    private JsonArray ids(String... values) {
        var result = new JsonArray();
        for (var value : values) result.add(value);
        return result;
    }
}
