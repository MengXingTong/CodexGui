package com.codexgui.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class WorkspacePathPolicyTest {
    @TempDir Path root;

    @Test
    void acceptsRelativeAndAbsolutePathsInsideTheWorkspace() throws Exception {
        Files.createDirectories(root.resolve("src"));
        var policy = new WorkspacePathPolicy(root);

        assertEquals(root.resolve("src/Main.java").normalize(), policy.resolve("src/Main.java"));
        assertEquals(root.resolve("src/Main.java").normalize(), policy.normalize(root.resolve("src/Main.java")));
    }

    @Test
    void rejectsTheRootAndTraversalOutsideTheWorkspace() {
        var policy = new WorkspacePathPolicy(root);

        assertNull(policy.normalize(root));
        assertNull(policy.resolve("../outside.txt"));
        assertNull(policy.resolve(""));
    }
}
