package com.codexgui.service;

import com.codexgui.model.ChangeEntry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

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

    @Test
    void reconstructsPreviousTextFromCurrentContentAndUnifiedDiff() {
        var diff = """
            diff --git a/README.md b/README.md
            index 1111111..2222222 100644
            --- a/README.md
            +++ b/README.md
            @@ -1,3 +1,3 @@
             first
            -old
            +new
             last
            """;

        var parsed = UnifiedDiffParser.parse(diff, Map.of(
            "README.md", "first\nnew\nlast\n".getBytes(StandardCharsets.UTF_8)
        ));

        assertEquals(1, parsed.size());
        assertEquals("first\nold\nlast\n", new String(parsed.getFirst().beforeContent(), StandardCharsets.UTF_8));
    }

    @Test
    void marksBinaryDiffAsNotReversible() {
        var diff = """
            diff --git a/texture.uasset b/texture.uasset
            index 1111111..2222222 100644
            Binary files a/texture.uasset and b/texture.uasset differ
            """;

        var parsed = UnifiedDiffParser.parse(diff, Map.of("texture.uasset", new byte[]{1, 2, 3}));

        assertEquals(1, parsed.size());
        assertEquals(null, parsed.getFirst().beforeContent());
    }

    @Test
    void classifiesAddedAndDeletedFiles() {
        var diff = """
            diff --git a/new.txt b/new.txt
            new file mode 100644
            --- /dev/null
            +++ b/new.txt
            @@ -0,0 +1 @@
            +new
            diff --git a/old.txt b/old.txt
            deleted file mode 100644
            --- a/old.txt
            +++ /dev/null
            @@ -1 +0,0 @@
            -old
            """;

        var parsed = UnifiedDiffParser.parse(diff, Map.of());

        assertEquals(ChangeEntry.Kind.ADDED, parsed.get(0).kind());
        assertEquals(ChangeEntry.Kind.DELETED, parsed.get(1).kind());
        assertEquals("old\n", new String(parsed.get(1).beforeContent(), StandardCharsets.UTF_8));
    }
}
