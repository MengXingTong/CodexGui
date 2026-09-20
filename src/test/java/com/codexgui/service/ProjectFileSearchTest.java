package com.codexgui.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectFileSearchTest {
    @TempDir
    Path root;

    @Test
    void ranksPathAndFileNameMatchesAheadOfFuzzyMatches() throws IOException {
        create("src/main/resources/web/app.js");
        create("src/test/java/AppServiceTest.java");
        create("docs/application-notes.md");

        var matches = ProjectFileSearch.find(root, "app", 10);

        assertEquals(root.resolve("src/main/resources/web/app.js").toAbsolutePath().normalize(), matches.getFirst().path());
        assertEquals("src/main/resources/web/app.js", matches.getFirst().displayPath());
        assertEquals("src/test/java/AppServiceTest.java", matches.get(1).displayPath());
    }

    @Test
    void acceptsSlashQueriesAndFuzzySubsequences() throws IOException {
        create("src/main/java/com/codexgui/ui/CodexToolWindowPanel.java");
        create("README.md");

        assertEquals("src/main/java/com/codexgui/ui/CodexToolWindowPanel.java",
            ProjectFileSearch.find(root, "ui/codextool", 10).getFirst().displayPath());
        assertEquals("README.md", ProjectFileSearch.find(root, "rdm", 10).getFirst().displayPath());
    }

    @Test
    void includesUnrealContentAndSkipsGeneratedDirectories() throws IOException {
        create("src/First.java");
        create("src/Second.java");
        create("Content/Characters/Hero.uasset");
        create("build/Generated.java");
        create("node_modules/package/index.js");
        create("Binaries/Win64/Game.dll");
        create("DerivedDataCache/Shader.ddc");
        create("Intermediate/Build/Generated.cpp");
        create("Saved/Logs/Game.log");

        var contentMatches = ProjectFileSearch.find(root, "hero", 10);
        var allFiles = ProjectFileSearch.list(root);

        assertEquals("Content/Characters/Hero.uasset", contentMatches.getFirst().displayPath());
        assertTrue(allFiles.stream().anyMatch(item -> item.displayPath().equals("Content/Characters/Hero.uasset")));
        assertFalse(allFiles.stream().anyMatch(item -> item.displayPath().startsWith("build/")
            || item.displayPath().startsWith("node_modules/")
            || item.displayPath().startsWith("Binaries/")
            || item.displayPath().startsWith("DerivedDataCache/")
            || item.displayPath().startsWith("Intermediate/")
            || item.displayPath().startsWith("Saved/")));
    }

    @Test
    void honorsResultLimit() throws IOException {
        create("src/First.java");
        create("src/Second.java");

        assertEquals(1, ProjectFileSearch.find(root, "", 1).size());
    }

    private void create(String relativePath) throws IOException {
        var file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "test");
    }
}
