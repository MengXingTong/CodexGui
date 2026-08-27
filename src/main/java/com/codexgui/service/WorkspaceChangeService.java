package com.codexgui.service;

import com.codexgui.model.ChangeEntry;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

@Service(Service.Level.PROJECT)
public final class WorkspaceChangeService {
    private static final long MAX_CAPTURE_BYTES = 5L * 1024L * 1024L;

    private final Path root;
    private final CopyOnWriteArrayList<Consumer<ChangeUpdate>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, List<ChangeEntry>> changesBySession = new HashMap<>();
    private final Map<String, Integer> activeCapturesBySession = new HashMap<>();

    public WorkspaceChangeService(Project project) {
        this.root = project.getBasePath() == null ? Path.of(".").toAbsolutePath().normalize()
            : Path.of(project.getBasePath()).toAbsolutePath().normalize();
    }

    public static WorkspaceChangeService getInstance(@NotNull Project project) {
        return project.getService(WorkspaceChangeService.class);
    }

    public Path getRoot() {
        return root;
    }

    public synchronized List<ChangeEntry> getChanges(String sessionId) {
        return changesBySession.getOrDefault(sessionKey(sessionId), List.of());
    }

    public void addListener(Consumer<ChangeUpdate> listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(Consumer<ChangeUpdate> listener) {
        listeners.remove(listener);
    }

    public synchronized CompletableFuture<Void> beginCaptureAsync(String sessionId) {
        var key = sessionKey(sessionId);
        activeCapturesBySession.merge(key, 1, Integer::sum);
        return CompletableFuture.completedFuture(null);
    }

    public synchronized void updateServerDiff(String sessionId, String unifiedDiff) {
        var key = sessionKey(sessionId);
        if (activeCapturesBySession.getOrDefault(key, 0) == 0 || unifiedDiff == null || unifiedDiff.isBlank()) return;

        // 只读取 Codex 协议明确报告过的文件，不再扫描项目目录。
        var afterContents = new LinkedHashMap<String, byte[]>();
        for (var path : UnifiedDiffParser.split(unifiedDiff).keySet()) {
            var target = resolveReportedPath(path);
            if (target == null || !Files.isRegularFile(target)) continue;
            afterContents.put(path, readCaptureContent(target));
        }

        var next = new LinkedHashMap<Path, ChangeEntry>();
        for (var change : changesBySession.getOrDefault(key, List.of())) next.put(change.path(), change);
        for (var fileDiff : UnifiedDiffParser.parse(unifiedDiff, afterContents)) {
            var target = resolveReportedPath(fileDiff.path());
            if (target == null) continue;

            var previous = next.get(target);
            var afterContent = afterContents.get(fileDiff.path());
            var beforeContent = fileDiff.beforeContent() != null
                ? fileDiff.beforeContent()
                : previous == null ? null : previous.beforeContent();
            var reversible = fileDiff.kind() == ChangeEntry.Kind.ADDED || beforeContent != null;
            next.put(target, new ChangeEntry(
                target,
                fileDiff.kind(),
                beforeContent,
                afterContent,
                reversible,
                fileDiff.unifiedDiff()
            ));
        }
        changesBySession.put(key, List.copyOf(next.values()));
        fireChanged(key);
    }

    public synchronized void updateFileDiff(String sessionId, String relativePath, String kind, String diff) {
        var key = sessionKey(sessionId);
        if (activeCapturesBySession.getOrDefault(key, 0) == 0 || relativePath == null || relativePath.isBlank() || diff == null || diff.isBlank()) return;
        var header = "diff --git a/" + relativePath + " b/" + relativePath + "\n";
        var normalizedDiff = diff.startsWith("diff --git ") ? diff : switch (kind) {
            case "add" -> header + "new file mode 100644\n--- /dev/null\n+++ b/" + relativePath + "\n" + diff;
            case "delete" -> header + "deleted file mode 100644\n--- a/" + relativePath + "\n+++ /dev/null\n" + diff;
            default -> header + "--- a/" + relativePath + "\n+++ b/" + relativePath + "\n" + diff;
        };
        updateServerDiff(key, normalizedDiff);
    }

    public synchronized CompletableFuture<Void> finishCaptureAsync(String sessionId) {
        var key = sessionKey(sessionId);
        var remaining = Math.max(0, activeCapturesBySession.getOrDefault(key, 0) - 1);
        if (remaining == 0) activeCapturesBySession.remove(key);
        else activeCapturesBySession.put(key, remaining);
        return CompletableFuture.completedFuture(null);
    }

    public synchronized void confirmSession(String sessionId) {
        var key = sessionKey(sessionId);
        activeCapturesBySession.remove(key);
        changesBySession.remove(key);
        fireChanged(key);
    }

    public synchronized void accept(String sessionId, ChangeEntry entry) {
        var key = sessionKey(sessionId);
        changesBySession.put(key, changesBySession.getOrDefault(key, List.of()).stream()
            .filter(change -> !change.path().equals(entry.path())).toList());
        fireChanged(key);
    }

    public synchronized void acceptAll(String sessionId) {
        var key = sessionKey(sessionId);
        changesBySession.remove(key);
        fireChanged(key);
    }

    public synchronized void revert(String sessionId, ChangeEntry entry) throws IOException {
        if (!entry.reversible()) throw new IOException("该文件缺少 Codex 修改前内容，无法安全撤销");
        restore(entry);
        var key = sessionKey(sessionId);
        changesBySession.put(key, changesBySession.getOrDefault(key, List.of()).stream()
            .filter(change -> !change.path().equals(entry.path())).toList());
        refresh(entry.path());
        fireChanged(key);
    }

    public synchronized void revertAll(String sessionId) throws IOException {
        var key = sessionKey(sessionId);
        var failures = new ArrayList<String>();
        var changes = changesBySession.getOrDefault(key, List.of());
        for (var entry : List.copyOf(changes)) {
            if (!entry.reversible()) {
                failures.add(entry.path().toString());
                continue;
            }
            try {
                restore(entry);
                refresh(entry.path());
            } catch (IOException error) {
                failures.add(entry.path() + "（" + error.getMessage() + "）");
            }
        }
        var remaining = new ArrayList<ChangeEntry>();
        for (var change : changes) {
            var failed = failures.stream().anyMatch(text -> text.startsWith(change.path().toString()));
            if (failed) remaining.add(change);
        }
        changesBySession.put(key, List.copyOf(remaining));
        fireChanged(key);
        if (!failures.isEmpty()) throw new IOException("以下文件无法撤销：\n" + String.join("\n", failures));
    }

    private void restore(ChangeEntry entry) throws IOException {
        // 新文件撤销时删除；已有文件则恢复 Codex 修改前的内容。
        if (entry.kind() == ChangeEntry.Kind.ADDED) {
            Files.deleteIfExists(entry.path());
            return;
        }
        var beforeContent = entry.beforeContent();
        if (beforeContent == null) throw new IOException("缺少修改前内容");
        var parent = entry.path().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(entry.path(), beforeContent);
    }

    private byte[] readCaptureContent(Path path) {
        try {
            if (Files.size(path) > MAX_CAPTURE_BYTES) return null;
            return Files.readAllBytes(path);
        } catch (IOException ignored) {
            return null;
        }
    }

    private Path resolveReportedPath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return null;
        var target = root.resolve(relativePath.replace('/', root.getFileSystem().getSeparator().charAt(0))).normalize();
        return target.startsWith(root) ? target : null;
    }

    private void refresh(Path path) {
        LocalFileSystem.getInstance().refreshIoFiles(List.of(path.toFile()), true, false, null);
    }

    private void fireChanged(String sessionId) {
        var snapshot = new ChangeUpdate(sessionId, changesBySession.getOrDefault(sessionId, List.of()));
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() ->
            listeners.forEach(listener -> listener.accept(snapshot)));
    }

    private String sessionKey(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "default" : sessionId;
    }

    public record ChangeUpdate(String sessionId, List<ChangeEntry> changes) {}
}
