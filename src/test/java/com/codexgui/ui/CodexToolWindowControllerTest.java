package com.codexgui.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
