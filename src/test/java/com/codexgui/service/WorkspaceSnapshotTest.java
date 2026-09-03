package com.codexgui.service;

import com.codexgui.model.ChangeEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class WorkspaceSnapshotTest {
    @TempDir
    Path root;

    @Test
    void detectsAddedModifiedAndDeletedFilesWithReversibleContents() throws IOException {
        write("modified.txt", "before\n");
        write("deleted.txt", "deleted\n");
        try (var before = WorkspaceSnapshot.capture(root, true, 1024)) {
            write("modified.txt", "after\n");
            Files.delete(root.resolve("deleted.txt"));
            write("added.txt", "added\n");

            try (var after = WorkspaceSnapshot.capture(root, true, 1024)) {
                var changes = before.compare(after).stream().collect(Collectors.toMap(
                    WorkspaceSnapshot.SnapshotChange::relativePath,
                    Function.identity()
                ));

                var modified = changes.get("modified.txt");
                assertEquals(ChangeEntry.Kind.MODIFIED, modified.kind());
                assertArrayEquals(bytes("before\n"), modified.beforeContent());
                assertArrayEquals(bytes("after\n"), modified.afterContent());
                var modifiedDiff = UnifiedDiffBuilder.create(
                    modified.relativePath(),
                    modified.kind(),
                    modified.beforeContent(),
                    modified.afterContent(),
                    modified.beforeHash(),
                    modified.afterHash()
                );
                assertTrue(modifiedDiff.contains("-before"));
                assertTrue(modifiedDiff.contains("+after"));

                assertEquals(ChangeEntry.Kind.DELETED, changes.get("deleted.txt").kind());
                assertArrayEquals(bytes("deleted\n"), changes.get("deleted.txt").beforeContent());
                assertNull(changes.get("deleted.txt").afterContent());

                assertEquals(ChangeEntry.Kind.ADDED, changes.get("added.txt").kind());
                assertNull(changes.get("added.txt").beforeContent());
                assertArrayEquals(bytes("added\n"), changes.get("added.txt").afterContent());
            }
        }
    }

    @Test
    void skipsGeneratedDirectoriesAndUnchangedFiles() throws IOException {
        write("src/Main.java", "class Main {}\n");
        write(".git", "gitdir: elsewhere\n");
        write("build/generated.txt", "before\n");
        try (var before = WorkspaceSnapshot.capture(root, true, 1024)) {
            write(".git", "gitdir: changed\n");
            write("build/generated.txt", "after\n");
            try (var after = WorkspaceSnapshot.capture(root, true, 1024)) {
                assertTrue(before.compare(after).isEmpty());
            }
        }
    }

    @Test
    void excludesGitIgnoredFilesWhenSettingIsDisabled() throws IOException, InterruptedException {
        assumeTrue(new ProcessBuilder("git", "--version").start().waitFor() == 0);
        write(".gitignore", "ignored.txt\n");
        write("ignored.txt", "before\n");
        write("visible.txt", "before\n");
        assertEquals(0, new ProcessBuilder("git", "init", "--quiet").directory(root.toFile()).start().waitFor());

        try (var before = WorkspaceSnapshot.capture(root, false, 1024)) {
            write("ignored.txt", "after\n");
            write("visible.txt", "after\n");
            try (var after = WorkspaceSnapshot.capture(root, false, 1024)) {
                var changes = before.compare(after);

                assertEquals(1, changes.size());
                assertEquals("visible.txt", changes.getFirst().relativePath());
            }
        }
    }

    @Test
    void keepsBaselineFilesVisibleWhenGitIgnoreChangesDuringCapture() throws IOException, InterruptedException {
        assumeTrue(new ProcessBuilder("git", "--version").start().waitFor() == 0);
        write("kept.txt", "content\n");
        assertEquals(0, new ProcessBuilder("git", "init", "--quiet").directory(root.toFile()).start().waitFor());

        try (var before = WorkspaceSnapshot.capture(root, false, 1024)) {
            write(".gitignore", "kept.txt\n");
            try (var after = WorkspaceSnapshot.capture(root, false, 1024, before.paths())) {
                var changes = before.compare(after);

                assertEquals(1, changes.size());
                assertEquals(".gitignore", changes.getFirst().relativePath());
            }
        }
    }

    @Test
    void keepsLargeFileHashesWithoutLoadingRevertContents() throws IOException {
        write("large.bin", "0123456789");
        try (var before = WorkspaceSnapshot.capture(root, true, 4)) {
            write("large.bin", "abcdefghij");
            try (var after = WorkspaceSnapshot.capture(root, true, 4)) {
                var change = before.compare(after).getFirst();

                assertNull(change.beforeContent());
                assertNull(change.afterContent());
                var diff = UnifiedDiffBuilder.create(
                    change.relativePath(),
                    change.kind(),
                    change.beforeContent(),
                    change.afterContent(),
                    change.beforeHash(),
                    change.afterHash()
                );
                assertFalse(diff.isBlank());
                assertTrue(diff.contains("Binary files"));
            }
        }
    }

    @Test
    void createsCompactTextDiffAroundChangedRegion() {
        var diff = UnifiedDiffBuilder.create(
            "file.txt",
            ChangeEntry.Kind.MODIFIED,
            bytes("same\nold\nend\n"),
            bytes("same\nnew\nend\n"),
            new byte[]{1},
            new byte[]{2}
        );

        assertTrue(diff.contains("@@ -2,1 +2,1 @@"));
        assertTrue(diff.contains("-old\n+new"));
        assertFalse(diff.contains("-same"));
        assertFalse(diff.contains("+end"));
    }

    private void write(String relativePath, String content) throws IOException {
        var file = root.resolve(relativePath);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
