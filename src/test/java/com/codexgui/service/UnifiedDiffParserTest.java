package com.codexgui.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class UnifiedDiffParserTest {
    @Test
    void splitsAggregatedDiffByDestinationPath() {
        var diff = """
            diff --git a/src/Old.java b/src/New.java
            similarity index 90%
            rename from src/Old.java
            rename to src/New.java
            diff --git a/README.md b/README.md
            index 1111111..2222222 100644
            --- a/README.md
            +++ b/README.md
            @@ -1 +1 @@
            -old
            +new
            """;

        var files = UnifiedDiffParser.split(diff);

        assertEquals(2, files.size());
        assertTrue(files.containsKey("src/New.java"));
        assertTrue(files.get("README.md").contains("+new"));
    }

    @Test
    void ignoresTextBeforeFirstFileHeader() {
        var files = UnifiedDiffParser.split("notice\ndiff --git a/a.txt b/a.txt\n+value\n");

        assertEquals(1, files.size());
        assertTrue(files.get("a.txt").startsWith("diff --git"));
    }
}
