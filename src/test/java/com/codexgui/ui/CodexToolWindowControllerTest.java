package com.codexgui.ui;

import org.junit.jupiter.api.Test;

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
}
