package com.codexgui.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodexToolWindowPanelTest {
    @Test
    void normalizesWindowsDrivePrefixAndMarkdownEscapes() {
        assertEquals(
            "E:/UnrealProjects/BoundaryDreamland/Source/BoundaryDreamland_MagicTower/MTGameplay/MTPlayerState/MTPSComponent/MTTaskComponent.h",
            CodexToolWindowPanel.normalizeReportedFilePath(
                "/E:/UnrealProjects/BoundaryDreamland/Source/BoundaryDreamland\\_MagicTower/MTGameplay/MTPlayerState/MTPSComponent/MTTaskComponent.h"
            )
        );
    }
}
