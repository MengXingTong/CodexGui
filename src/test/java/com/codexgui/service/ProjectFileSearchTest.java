package com.codexgui.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProjectFileSearchTest {
    @TempDir
    Path root;

    @Test
    void ranksPathAndFileNameMatchesAheadOfFuzzyMatches() throws IOException {
        create("src/main/resources/web/app.js");
        create("src/test/java/AppServiceTest.java");
        create("docs/application-notes.md");

        var matches = ProjectFileSearch.find(root, "app", 10);

        assertEquals("src/main/resources/web/app.js", matches.getFirst().path());
        assertEquals("src/test/java/AppServiceTest.java", matches.get(1).path());
    }

    @Test
    void acceptsSlashQueriesAndFuzzySubsequences() throws IOException {
        create("src/main/java/com/codexgui/ui/CodexToolWindowPanel.java");
        create("README.md");

        assertEquals("src/main/java/com/codexgui/ui/CodexToolWindowPanel.java",
            ProjectFileSearch.find(root, "ui/codextool", 10).getFirst().path());
        assertEquals("README.md", ProjectFileSearch.find(root, "rdm", 10).getFirst().path());
    }

    @Test
    void skipsGeneratedAndDependencyDirectoriesAndHonorsLimit() throws IOException {
        create("src/First.java");
        create("src/Second.java");
        create("build/Generated.java");
        create("node_modules/package/index.js");

        var matches = ProjectFileSearch.find(root, "", 1);

        assertEquals(1, matches.size());
        assertFalse(matches.stream().anyMatch(item -> item.path().startsWith("build/") || item.path().startsWith("node_modules/")));
    }

    private void create(String relativePath) throws IOException {
        var file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "test");
    }
}
