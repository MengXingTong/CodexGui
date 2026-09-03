package com.codexgui.service;

import com.codexgui.model.ChangeEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorkspaceChangeServiceTest {
    @TempDir
    Path root;

    @Test
    void claudeSnapshotCapturePopulatesAndAccumulatesSessionChanges() throws IOException {
        write("file.txt", "original\n");
        var service = new WorkspaceChangeService(root);
        service.beginWorkspaceCaptureAsync("claude-session", true).join();
        write("file.txt", "first\n");
        service.finishCaptureAsync("claude-session").join();

        service.beginWorkspaceCaptureAsync("claude-session", true).join();
        write("file.txt", "second\n");
        service.finishCaptureAsync("claude-session").join();

        var changes = service.getChanges("claude-session");
        assertEquals(1, changes.size());
        assertEquals(ChangeEntry.Kind.MODIFIED, changes.getFirst().kind());
        assertArrayEquals(bytes("original\n"), changes.getFirst().beforeContent());
        assertArrayEquals(bytes("second\n"), changes.getFirst().afterContent());
    }

    @Test
    void codexAndWorkspaceSnapshotUseTheSameAccumulationState() throws IOException {
        write("file.txt", "original\n");
        var service = new WorkspaceChangeService(root);

        service.beginCaptureAsync("codex-session").join();
        write("file.txt", "first\n");
        service.updateServerDiff("codex-session", textDiff("original", "first"));
        service.finishCaptureAsync("codex-session").join();
        service.beginCaptureAsync("codex-session").join();
        write("file.txt", "second\n");
        service.updateServerDiff("codex-session", textDiff("first", "second"));
        service.finishCaptureAsync("codex-session").join();

        write("file.txt", "original\n");
        service.beginWorkspaceCaptureAsync("claude-session", true).join();
        write("file.txt", "first\n");
        service.finishCaptureAsync("claude-session").join();
        service.beginWorkspaceCaptureAsync("claude-session", true).join();
        write("file.txt", "second\n");
        service.finishCaptureAsync("claude-session").join();

        var codexChange = service.getChanges("codex-session").getFirst();
        var claudeChange = service.getChanges("claude-session").getFirst();
        assertEquals(codexChange.kind(), claudeChange.kind());
        assertArrayEquals(codexChange.beforeContent(), claudeChange.beforeContent());
        assertArrayEquals(codexChange.afterContent(), claudeChange.afterContent());
    }

    @Test
    void nestedWorkspaceCaptureOnlyComparesAfterTheLastFinish() throws IOException {
        write("file.txt", "before\n");
        var service = new WorkspaceChangeService(root);
        service.beginWorkspaceCaptureAsync("session", true).join();
        service.beginWorkspaceCaptureAsync("session", true).join();
        write("file.txt", "after\n");

        service.finishCaptureAsync("session").join();
        assertTrue(service.getChanges("session").isEmpty());

        service.finishCaptureAsync("session").join();
        assertEquals(1, service.getChanges("session").size());
    }

    @Test
    void discardedCaptureKeepsPreviousChangesAndIgnoresTheCurrentRound() throws IOException {
        write("file.txt", "original\n");
        var service = new WorkspaceChangeService(root);
        service.beginWorkspaceCaptureAsync("session", true).join();
        write("file.txt", "captured\n");
        service.finishCaptureAsync("session").join();

        service.beginWorkspaceCaptureAsync("session", true).join();
        write("file.txt", "not-captured\n");
        service.discardCapture("session");
        service.finishCaptureAsync("session").join();

        var change = service.getChanges("session").getFirst();
        assertArrayEquals(bytes("original\n"), change.beforeContent());
        assertArrayEquals(bytes("captured\n"), change.afterContent());
    }

    private void write(String relativePath, String content) throws IOException {
        Files.writeString(root.resolve(relativePath), content, StandardCharsets.UTF_8);
    }

    private static String textDiff(String before, String after) {
        return """
            diff --git a/file.txt b/file.txt
            --- a/file.txt
            +++ b/file.txt
            @@ -1 +1 @@
            -%s
            +%s
            """.formatted(before, after);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
