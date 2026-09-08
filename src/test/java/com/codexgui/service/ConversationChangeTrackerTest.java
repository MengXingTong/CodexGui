package com.codexgui.service;

import com.codexgui.model.ChangeEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConversationChangeTrackerTest {
    @TempDir
    Path root;

    @Test
    void firstTouchOwnsBaselineAcrossProviderAndUserEdits() throws IOException {
        var path = write("file.txt", "original\n");
        var tracker = new ConversationChangeTracker(root);

        assertTrue(tracker.trackBeforeWrite("session", "file.txt"));
        write("file.txt", "provider\n");
        assertFalse(tracker.trackBeforeWrite("session", "file.txt"));
        write("file.txt", "user-follow-up\n");

        var summary = tracker.listSummaries("session").getFirst();
        var details = tracker.readDetails("session", path);
        assertEquals(ChangeEntry.Kind.MODIFIED, summary.kind());
        assertEquals(1, summary.addedLines());
        assertEquals(1, summary.deletedLines());
        assertArrayEquals(bytes("original\n"), details.beforeContent());
        assertArrayEquals(bytes("user-follow-up\n"), details.afterContent());

        tracker.revert("session", path);
        assertEquals("original\n", Files.readString(path, StandardCharsets.UTF_8));
        assertTrue(tracker.listSummaries("session").isEmpty());
    }

    @Test
    void summaryCountsOnlyChangedLinesAcrossSeparateRegions() throws IOException {
        var original = String.join("\n",
            "line 1", "line 2", "line 3", "line 4", "line 5", "line 6",
            "line 7", "line 8", "line 9", "line 10", "line 11", "line 12") + "\n";
        var changed = original
            .replace("line 2\n", "updated 2\n")
            .replace("line 11\n", "updated 11\n");
        write("file.txt", original);
        var tracker = new ConversationChangeTracker(root);

        tracker.trackBeforeWrite("session", "file.txt");
        write("file.txt", changed);

        var summary = tracker.listSummaries("session").getFirst();
        assertEquals(2, summary.addedLines());
        assertEquals(2, summary.deletedLines());
    }

    @Test
    void addedAndDeletedFilesRestoreTheirFirstBaseline() throws IOException {
        var added = root.resolve("added.txt");
        var deleted = write("deleted.txt", "before\n");
        var tracker = new ConversationChangeTracker(root);

        assertTrue(tracker.trackBeforeWrite("session", "added.txt"));
        write("added.txt", "created\n");
        assertTrue(tracker.trackBeforeWrite("session", "deleted.txt"));
        Files.delete(deleted);

        var summaries = tracker.listSummaries("session");
        assertEquals(ChangeEntry.Kind.ADDED, summaries.get(0).kind());
        assertEquals(ChangeEntry.Kind.DELETED, summaries.get(1).kind());

        tracker.revertAll("session");
        assertFalse(Files.exists(added));
        assertEquals("before\n", Files.readString(deleted, StandardCharsets.UTF_8));
    }

    @Test
    void acceptClearsBaselineAndNextTouchStartsFromCurrentContent() throws IOException {
        var path = write("file.txt", "original\n");
        var tracker = new ConversationChangeTracker(root);
        tracker.trackBeforeWrite("session", "file.txt");
        write("file.txt", "accepted\n");

        tracker.accept("session", path);
        assertFalse(tracker.isTracked("session", path));
        assertTrue(tracker.trackBeforeWrite("session", "file.txt"));
        write("file.txt", "second\n");

        assertArrayEquals(bytes("accepted\n"), tracker.readDetails("session", path).beforeContent());
    }

    @Test
    void providerDiffCreatesMembershipWithoutScanningUnreportedFiles() throws IOException {
        var tracked = write("tracked.txt", "after\n");
        write("unrelated.txt", "user-only\n");
        var tracker = new ConversationChangeTracker(root);

        tracker.trackProviderDiff("session", textDiff("tracked.txt", "before", "after"));

        assertEquals(1, tracker.listSummaries("session").size());
        assertArrayEquals(bytes("before\n"), tracker.readDetails("session", tracked).beforeContent());
        assertFalse(tracker.isTracked("session", root.resolve("unrelated.txt")));
    }

    @Test
    void staleProviderDiffDoesNotCreateAnInvalidBaseline() throws IOException {
        write("file.txt", "latest\n");
        var tracker = new ConversationChangeTracker(root);

        tracker.trackProviderDiff("session", "turn-1", textDiff("file.txt", "before", "intermediate"));

        assertTrue(tracker.listSummaries("session").isEmpty());
    }

    @Test
    void latestCumulativeDiffReplacesBaselineDerivedDuringTheSameTurn() throws IOException {
        var path = write("file.txt", "new first\nsame\nnew last\n");
        var tracker = new ConversationChangeTracker(root);
        var earlyDiff = textDiff("file.txt", "old first", "new first");
        var cumulativeDiff = """
            diff --git a/file.txt b/file.txt
            --- a/file.txt
            +++ b/file.txt
            @@ -1 +1 @@
            -old first
            +new first
            @@ -3 +3 @@
            -old last
            +new last
            """;

        tracker.trackProviderDiff("session", "turn-1", earlyDiff);
        tracker.trackProviderDiff("session", "turn-1", cumulativeDiff);

        assertArrayEquals(bytes("old first\nsame\nold last\n"),
            tracker.readDetails("session", path).beforeContent());
    }

    @Test
    void binaryProviderModificationIsNotMisclassifiedAsAnAddedFile() throws IOException {
        write("asset.bin", "current\n");
        var tracker = new ConversationChangeTracker(root);
        var diff = """
            diff --git a/asset.bin b/asset.bin
            Binary files a/asset.bin and b/asset.bin differ
            """;

        tracker.trackProviderDiff("session", "turn-1", diff);

        var summary = tracker.listSummaries("session").getFirst();
        assertEquals(ChangeEntry.Kind.MODIFIED, summary.kind());
        assertFalse(summary.reversible());
    }

    @Test
    void malformedAndOutsideWorkspacePathsNeverBecomeMembers() {
        var tracker = new ConversationChangeTracker(root);

        assertFalse(tracker.trackBeforeWrite("session", ""));
        assertFalse(tracker.trackBeforeWrite("session", root.resolveSibling("outside.txt").toString()));
        assertTrue(tracker.listSummaries("session").isEmpty());
    }

    @Test
    void largeFilesAreTrackedButNotReversible() throws IOException {
        var path = root.resolve("large.bin");
        var content = new byte[(int) ConversationChangeTracker.MAX_BASELINE_BYTES + 1];
        Files.write(path, content);
        var tracker = new ConversationChangeTracker(root);
        tracker.trackBeforeWrite("session", "large.bin");
        content[0] = 1;
        Files.write(path, content);

        var summary = tracker.listSummaries("session").getFirst();
        assertFalse(summary.reversible());
        assertThrows(IOException.class, () -> tracker.revert("session", path));
    }

    @Test
    void clearSessionReleasesMembership() throws IOException {
        var path = write("file.txt", "before\n");
        var tracker = new ConversationChangeTracker(root);
        tracker.trackBeforeWrite("session", "file.txt");

        tracker.clearSession("session");

        assertFalse(tracker.isTracked("session", path));
        assertTrue(tracker.listSummaries("session").isEmpty());
    }

    @Test
    void sessionRefreshPublishesChangesWrittenByAnExternalProvider() throws IOException {
        var path = write("file.txt", "before\n");
        var tracker = new ConversationChangeTracker(root);
        var updates = new ArrayList<ConversationChangeTracker.ChangeUpdate>();
        tracker.trackBeforeWrite("session", path.toString());
        tracker.addListener(updates::add);
        write("file.txt", "after\n");

        tracker.refreshSession("session");

        assertEquals(1, updates.size());
        assertEquals(path, updates.getFirst().changes().getFirst().path());
    }

    private Path write(String relativePath, String content) throws IOException {
        var path = root.resolve(relativePath);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private static String textDiff(String path, String before, String after) {
        return """
            diff --git a/%1$s b/%1$s
            --- a/%1$s
            +++ b/%1$s
            @@ -1 +1 @@
            -%2$s
            +%3$s
            """.formatted(path, before, after);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
