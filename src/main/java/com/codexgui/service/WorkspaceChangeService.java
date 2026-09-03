package com.codexgui.service;

import com.codexgui.model.ChangeEntry;
import com.codexgui.settings.CodexSettingsState;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Service(Service.Level.PROJECT)
public final class WorkspaceChangeService implements Disposable {
    private static final Logger LOG = Logger.getInstance(WorkspaceChangeService.class);
    private static final long MAX_CAPTURE_BYTES = 5L * 1024L * 1024L;

    private final Path root;
    private final CopyOnWriteArrayList<Consumer<ChangeUpdate>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, List<ChangeEntry>> changesBySession = new HashMap<>();
    /**
     * 服务端 diff 可能是线程累计快照。记录用户已接受的快照，避免下一次累计
     * diff 到达时把同一份修改重新加入列表。
     */
    private final Map<String, Map<Path, AcceptedChange>> acceptedChangesBySession = new HashMap<>();
    private final Map<String, Integer> activeCapturesBySession = new HashMap<>();
    private final Map<String, SnapshotCapture> workspaceSnapshotsBySession = new HashMap<>();
    private final Map<String, Long> captureGenerationsBySession = new HashMap<>();
    private boolean disposed;

    public WorkspaceChangeService(Project project) {
        this(project.getBasePath() == null ? Path.of(".") : Path.of(project.getBasePath()));
    }

    WorkspaceChangeService(Path root) {
        this.root = root.toAbsolutePath().normalize();
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

    public CompletableFuture<Void> beginCaptureAsync(String sessionId) {
        return beginCaptureAsync(sessionId, false, false);
    }

    public CompletableFuture<Void> beginWorkspaceCaptureAsync(String sessionId) {
        return beginWorkspaceCaptureAsync(
            sessionId,
            CodexSettingsState.getInstance().getState().captureIgnoredFiles
        );
    }

    CompletableFuture<Void> beginWorkspaceCaptureAsync(String sessionId, boolean captureIgnoredFiles) {
        return beginCaptureAsync(sessionId, true, captureIgnoredFiles);
    }

    private CompletableFuture<Void> beginCaptureAsync(
        String sessionId,
        boolean captureWorkspaceSnapshot,
        boolean captureIgnoredFiles
    ) {
        var key = sessionKey(sessionId);
        long generation;
        boolean createSnapshot;
        synchronized (this) {
            if (disposed) return CompletableFuture.completedFuture(null);
            var activeCaptures = activeCapturesBySession.getOrDefault(key, 0);
            activeCapturesBySession.put(key, activeCaptures + 1);
            createSnapshot = captureWorkspaceSnapshot && activeCaptures == 0;
            generation = captureGenerationsBySession.getOrDefault(key, 0L);
        }
        if (!createSnapshot) return CompletableFuture.completedFuture(null);

        return CompletableFuture.supplyAsync(
            () -> captureWorkspace(captureIgnoredFiles),
            AppExecutorUtil.getAppExecutorService()
        ).thenAccept(snapshot -> {
            if (snapshot == null) return;
            synchronized (this) {
                // 捕获期间会话可能已关闭，过期快照不能重新创建已确认的修改状态。
                if (!disposed && activeCapturesBySession.getOrDefault(key, 0) > 0
                    && captureGenerationsBySession.getOrDefault(key, 0L) == generation) {
                    var previous = workspaceSnapshotsBySession.put(key, new SnapshotCapture(snapshot, captureIgnoredFiles, generation));
                    if (previous != null) previous.snapshot().close();
                    return;
                }
            }
            snapshot.close();
        });
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

        var detectedChanges = new ArrayList<DetectedChange>();
        for (var fileDiff : UnifiedDiffParser.parse(unifiedDiff, afterContents)) {
            detectedChanges.add(new DetectedChange(
                fileDiff.path(),
                fileDiff.kind(),
                fileDiff.beforeContent(),
                afterContents.get(fileDiff.path()),
                null,
                null,
                fileDiff.unifiedDiff()
            ));
        }
        applyChanges(key, detectedChanges);
    }

    private static ChangeEntry.Kind mergeKind(ChangeEntry.Kind previous, ChangeEntry.Kind current) {
        if (previous == null || previous == current) return current;
        // 新文件后续编辑仍需支持撤销时删除文件，因此保留 ADDED 状态。
        if (previous == ChangeEntry.Kind.ADDED && current == ChangeEntry.Kind.MODIFIED) return ChangeEntry.Kind.ADDED;
        // 已有文件被删除后重新创建，恢复为 MODIFIED 更符合实际状态。
        if (current == ChangeEntry.Kind.ADDED) return ChangeEntry.Kind.MODIFIED;
        // 新增文件随后被删除时回到原始状态，由调用方的净变化判断负责移除条目。
        return current;
    }

    private static byte[] copy(byte[] content) {
        return content == null ? null : Arrays.copyOf(content, content.length);
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

    public CompletableFuture<Void> finishCaptureAsync(String sessionId) {
        var key = sessionKey(sessionId);
        SnapshotCapture capture;
        synchronized (this) {
            var activeCaptures = activeCapturesBySession.getOrDefault(key, 0);
            if (activeCaptures == 0) return CompletableFuture.completedFuture(null);
            var remaining = activeCaptures - 1;
            if (remaining > 0) {
                activeCapturesBySession.put(key, remaining);
                return CompletableFuture.completedFuture(null);
            }
            activeCapturesBySession.remove(key);
            capture = workspaceSnapshotsBySession.remove(key);
        }
        if (capture == null) return CompletableFuture.completedFuture(null);

        return CompletableFuture.supplyAsync(
            () -> captureWorkspace(capture.captureIgnoredFiles(), capture.snapshot().paths()),
            AppExecutorUtil.getAppExecutorService()
        ).thenAccept(after -> {
            try {
                if (after == null) return;
                var changes = capture.snapshot().compare(after);
                synchronized (this) {
                    if (disposed || captureGenerationsBySession.getOrDefault(key, 0L) != capture.generation()) return;
                    applyChanges(key, changes.stream().map(DetectedChange::fromSnapshot).toList());
                }
            } finally {
                capture.snapshot().close();
                if (after != null) after.close();
            }
        });
    }

    public synchronized void discardCapture(String sessionId) {
        var key = sessionKey(sessionId);
        activeCapturesBySession.remove(key);
        var capture = workspaceSnapshotsBySession.remove(key);
        if (capture != null) capture.snapshot().close();
        // 让仍在后台生成的基线或结束快照失效，但保留该会话之前累计的修改。
        captureGenerationsBySession.merge(key, 1L, Long::sum);
    }

    public synchronized void confirmSession(String sessionId) {
        var key = sessionKey(sessionId);
        discardCapture(key);
        changesBySession.remove(key);
        acceptedChangesBySession.remove(key);
        fireChanged(key);
    }

    @Override
    public synchronized void dispose() {
        disposed = true;
        workspaceSnapshotsBySession.values().forEach(capture -> capture.snapshot().close());
        workspaceSnapshotsBySession.clear();
        activeCapturesBySession.clear();
        listeners.clear();
    }

    public synchronized void accept(String sessionId, ChangeEntry entry) {
        var key = sessionKey(sessionId);
        acceptedChangesBySession.computeIfAbsent(key, ignored -> new HashMap<>())
            .put(entry.path(), AcceptedChange.from(entry));
        changesBySession.put(key, changesBySession.getOrDefault(key, List.of()).stream()
            .filter(change -> !change.path().equals(entry.path())).toList());
        fireChanged(key);
    }

    public synchronized void acceptAll(String sessionId) {
        var key = sessionKey(sessionId);
        var accepted = acceptedChangesBySession.computeIfAbsent(key, ignored -> new HashMap<>());
        for (var change : changesBySession.getOrDefault(key, List.of())) {
            accepted.put(change.path(), AcceptedChange.from(change));
        }
        changesBySession.remove(key);
        fireChanged(key);
    }

    public synchronized void revert(String sessionId, ChangeEntry entry) throws IOException {
        if (!entry.reversible()) throw new IOException("该文件缺少 AI 修改前内容，无法安全撤销");
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
        // 新文件撤销时删除；已有文件则恢复 AI 修改前的内容。
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

    private WorkspaceSnapshot captureWorkspace(boolean captureIgnoredFiles) {
        return captureWorkspace(captureIgnoredFiles, Set.of());
    }

    private WorkspaceSnapshot captureWorkspace(boolean captureIgnoredFiles, Set<String> baselinePaths) {
        try {
            return WorkspaceSnapshot.capture(root, captureIgnoredFiles, MAX_CAPTURE_BYTES, baselinePaths);
        } catch (IOException error) {
            LOG.warn("无法捕获工作区快照", error);
            return null;
        }
    }

    private void applyChanges(String sessionId, List<DetectedChange> changes) {
        if (changes.isEmpty()) return;
        var next = new LinkedHashMap<Path, ChangeEntry>();
        for (var change : changesBySession.getOrDefault(sessionId, List.of())) next.put(change.path(), change);
        var accepted = acceptedChangesBySession.get(sessionId);
        for (var change : changes) {
            var target = resolveReportedPath(change.relativePath());
            if (target == null) continue;
            var acceptedChange = accepted == null ? null : accepted.get(target);
            if (acceptedChange != null && acceptedChange.matches(
                change.kind(),
                change.diffFor(change.kind(), change.beforeContent()),
                change.afterContent()
            )) continue;
            if (accepted != null) accepted.remove(target);

            var previous = next.get(target);
            // 两种变化来源统一保留首次未确认修改前的内容，确保累计撤销基线一致。
            var beforeContent = previous != null && previous.beforeContent() != null
                ? copy(previous.beforeContent()) : copy(change.beforeContent());
            if (acceptedChange != null && acceptedChange.afterContent() != null) {
                beforeContent = AcceptedChange.copy(acceptedChange.afterContent());
            }

            // 新文件被删掉或文件恢复到原始内容时，累计净变化已经归零。
            if (previous != null && previous.kind() == ChangeEntry.Kind.ADDED && change.kind() == ChangeEntry.Kind.DELETED) {
                next.remove(target);
                continue;
            }
            if ((previous != null || acceptedChange != null) && beforeContent != null && change.afterContent() != null
                && Arrays.equals(beforeContent, change.afterContent())) {
                next.remove(target);
                continue;
            }

            var mergedKind = mergeKind(previous == null ? null : previous.kind(), change.kind());
            var unifiedDiff = change.diffFor(mergedKind, beforeContent);
            next.put(target, new ChangeEntry(
                target,
                mergedKind,
                beforeContent,
                copy(change.afterContent()),
                mergedKind == ChangeEntry.Kind.ADDED || beforeContent != null,
                unifiedDiff
            ));
        }
        if (accepted != null && accepted.isEmpty()) acceptedChangesBySession.remove(sessionId);
        changesBySession.put(sessionId, List.copyOf(next.values()));
        fireChanged(sessionId);
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
        var notification = (Runnable) () -> listeners.forEach(listener -> listener.accept(snapshot));
        var application = com.intellij.openapi.application.ApplicationManager.getApplication();
        if (application == null) {
            notification.run();
            return;
        }
        application.invokeLater(notification);
    }

    private String sessionKey(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "default" : sessionId;
    }

    private record AcceptedChange(ChangeEntry.Kind kind, String unifiedDiff, byte[] afterContent) {
        private static AcceptedChange from(ChangeEntry change) {
            return new AcceptedChange(change.kind(), change.unifiedDiff(), copy(change.afterContent()));
        }

        private boolean matches(ChangeEntry.Kind currentKind, String currentDiff, byte[] currentAfterContent) {
            return kind == currentKind
                && java.util.Objects.equals(unifiedDiff, currentDiff)
                && Arrays.equals(afterContent, currentAfterContent);
        }

        private static byte[] copy(byte[] content) {
            return content == null ? null : Arrays.copyOf(content, content.length);
        }
    }

    private record SnapshotCapture(
        WorkspaceSnapshot snapshot,
        boolean captureIgnoredFiles,
        long generation
    ) {}

    private record DetectedChange(
        String relativePath,
        ChangeEntry.Kind kind,
        byte[] beforeContent,
        byte[] afterContent,
        byte[] beforeHash,
        byte[] afterHash,
        String providedDiff
    ) {
        private static DetectedChange fromSnapshot(WorkspaceSnapshot.SnapshotChange change) {
            return new DetectedChange(
                change.relativePath(),
                change.kind(),
                change.beforeContent(),
                change.afterContent(),
                change.beforeHash(),
                change.afterHash(),
                null
            );
        }

        private String diffFor(ChangeEntry.Kind mergedKind, byte[] accumulatedBeforeContent) {
            if (providedDiff != null) return providedDiff;
            return UnifiedDiffBuilder.create(
                relativePath,
                mergedKind,
                accumulatedBeforeContent,
                afterContent,
                beforeHash,
                afterHash
            );
        }
    }

    public record ChangeUpdate(String sessionId, List<ChangeEntry> changes) {}
}
