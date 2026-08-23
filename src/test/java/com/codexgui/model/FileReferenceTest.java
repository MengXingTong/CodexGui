package com.codexgui.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FileReferenceTest {
    @TempDir
    Path tempDir;

    @Test
    void createsNormalizedFileReference() throws Exception {
        var file = Files.createFile(tempDir.resolve("Example.java"));

        var reference = FileReference.fromPath(file.resolveSibling(".").resolve("Example.java"));

        assertEquals("Example.java", reference.name());
        assertEquals(file.toAbsolutePath().normalize(), reference.path());
        assertFalse(reference.directory());
    }

    @Test
    void keepsDirectoryTypeForProjectTreeFolders() throws Exception {
        var directory = Files.createDirectory(tempDir.resolve("feature"));

        var reference = FileReference.fromPath(directory);

        assertEquals("feature", reference.name());
        assertTrue(reference.directory());
    }
}
