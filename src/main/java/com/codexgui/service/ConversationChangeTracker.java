package com.codexgui.service;

import com.codexgui.model.ChangeEntry;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Service(Service.Level.PROJECT)
public final class ConversationChangeTracker implements Disposable {
    static final long MAX_BASELINE_BYTES = 5L * 1024L * 1024L;

    public record ChangeSummary(
        Path path,
        ChangeEntry.Kind kind,
        boolean reversible,
        int addedLines,
        int deletedLines
    ) {
        public String displayName(Path root) {
            try {
                return root.relativize(path).toString();
            } catch (IllegalArgumentException ignored) {
                return path.toString();
            }
        }
    }

    public record ChangeUpdate(String sessionId, List<ChangeSummary> changes) {}

    private enum BaselineKind { PRESENT, ABSENT }

    private record Baseline(BaselineKind kind, byte[] bytes, byte[] hash) {
        private boolean reversible() { return kind == BaselineKind.ABSENT || bytes != null; }
    }

    private record CurrentContent(boolean present, byte[] bytes, byte[] hash) {}
    private record LineStats(int added, int deleted) {}

    private final Project project;
    private final Path root;
    private final WorkspacePathPolicy pathPolicy;
    private final Map<String, LinkedHashMap<Path, Baseline>> baselinesBySession = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Consumer<ChangeUpdate>> listeners = new CopyOnWriteArrayList<>();

    public ConversationChangeTracker(Project project) {
        this.project = project;
        this.root = Path.of(project.getBasePath() == null ? "." : project.getBasePath()).toAbsolutePath().normalize();
        this.pathPolicy = new WorkspacePathPolicy(root);
        installListeners();
    }

    ConversationChangeTracker(Path root) {
        this.project = null;
        this.root = root.toAbsolutePath().normalize();
        this.pathPolicy = new WorkspacePathPolicy(this.root);
    }

    public static ConversationChangeTracker getInstance(@NotNull Project project) {
        return project.getService(ConversationChangeTracker.class);
    }

    public Path getRoot() { return root; }

    public void addListener(Consumer<ChangeUpdate> listener) { listeners.addIfAbsent(listener); }
    public void removeListener(Consumer<ChangeUpdate> listener) { listeners.remove(listener); }

    public synchronized boolean trackBeforeWrite(String sessionId, String reportedPath) {
        var target = resolveReportedPath(reportedPath);
        if (target == null) return false;
        var current = readCurrent(target);
        if (current.present() && current.hash() == null) return false;
        return trackBaseline(sessionKey(sessionId), target, current);
    }

    Path resolveWorkspacePath(String reportedPath) { return resolveReportedPath(reportedPath); }

    public synchronized void trackProviderDiff(String sessionId, String unifiedDiff) {
        if (unifiedDiff == null || unifiedDiff.isBlank()) return;
        var afterContents = new LinkedHashMap<String, byte[]>();
        for (var reportedPath : UnifiedDiffParser.split(unifiedDiff).keySet()) {
            var target = resolveReportedPath(reportedPath);
            if (target == null) continue;
            afterContents.put(reportedPath, readCurrent(target).bytes());
        }
        for (var fileDiff : UnifiedDiffParser.parse(unifiedDiff, afterContents)) {
            var target = resolveReportedPath(fileDiff.path());
            if (target == null) continue;
            var baseline = fileDiff.beforeContent() == null
                ? new Baseline(BaselineKind.ABSENT, null, null)
                : new Baseline(BaselineKind.PRESENT, copy(fileDiff.beforeContent()), hash(fileDiff.beforeContent()));
            trackBaseline(sessionKey(sessionId), target, baseline);
        }
    }

    public synchronized void trackProviderFile(
        String sessionId,
        String reportedPath,
        String kind,
        String diff
    ) {
        if (reportedPath == null || reportedPath.isBlank() || diff == null || diff.isBlank()) return;
        var header = "diff --git a/" + reportedPath + " b/" + reportedPath + "\n";
        var normalizedDiff = diff.startsWith("diff --git ") ? diff : switch (kind) {
            case "add" -> header + "new file mode 100644\n--- /dev/null\n+++ b/" + reportedPath + "\n" + diff;
            case "delete" -> header + "deleted file mode 100644\n--- a/" + reportedPath + "\n+++ /dev/null\n" + diff;
            default -> header + "--- a/" + reportedPath + "\n+++ b/" + reportedPath + "\n" + diff;
        };
        trackProviderDiff(sessionId, normalizedDiff);
    }

    public synchronized List<ChangeSummary> listSummaries(String sessionId) {
        var baselines = baselinesBySession.get(sessionKey(sessionId));
        if (baselines == null || baselines.isEmpty()) return List.of();
        var summaries = new ArrayList<ChangeSummary>();
        for (var entry : baselines.entrySet()) {
            var change = createChange(entry.getKey(), entry.getValue());
            if (change == null) continue;
            var stats = lineStats(change.unifiedDiff());
            summaries.add(new ChangeSummary(
                change.path(), change.kind(), change.reversible(), stats.added(), stats.deleted()));
        }
        return List.copyOf(summaries);
    }

    public synchronized ChangeEntry readDetails(String sessionId, Path path) {
        var target = normalizePath(path);
        var baselines = baselinesBySession.get(sessionKey(sessionId));
        if (target == null || baselines == null) return null;
        var baseline = baselines.get(target);
        return baseline == null ? null : createChange(target, baseline);
    }

    public synchronized void accept(String sessionId, Path path) {
        removeBaseline(sessionKey(sessionId), normalizePath(path));
    }

    public synchronized void acceptAll(String sessionId) {
        var key = sessionKey(sessionId);
        baselinesBySession.remove(key);
        fireChanged(key);
    }

    public synchronized void revert(String sessionId, Path path) throws IOException {
        var key = sessionKey(sessionId);
        var target = normalizePath(path);
        var baseline = target == null ? null : baseline(key, target);
        if (baseline == null) return;
        restore(target, baseline);
        removeBaseline(key, target);
        refresh(target);
    }

    public synchronized void revertAll(String sessionId) throws IOException {
        var key = sessionKey(sessionId);
        var baselines = baselinesBySession.get(key);
        if (baselines == null || baselines.isEmpty()) return;
        var failures = new ArrayList<String>();
        for (var entry : List.copyOf(baselines.entrySet())) {
            try {
                restore(entry.getKey(), entry.getValue());
                baselines.remove(entry.getKey());
                refresh(entry.getKey());
            } catch (IOException error) {
                failures.add(entry.getKey() + "（" + error.getMessage() + "）");
            }
        }
        if (baselines.isEmpty()) baselinesBySession.remove(key);
        fireChanged(key);
        if (!failures.isEmpty()) throw new IOException("以下文件无法撤销：\n" + String.join("\n", failures));
    }

    public synchronized void clearSession(String sessionId) {
        var key = sessionKey(sessionId);
        baselinesBySession.remove(key);
        fireChanged(key);
    }

    public synchronized boolean isTracked(String sessionId, Path path) {
        var baselines = baselinesBySession.get(sessionKey(sessionId));
        var target = normalizePath(path);
        return target != null && baselines != null && baselines.containsKey(target);
    }

    public void refreshSession(String sessionId) {
        var key = sessionKey(sessionId);
        List<Path> paths;
        synchronized (this) {
            var baselines = baselinesBySession.get(key);
            if (baselines == null || baselines.isEmpty()) return;
            paths = List.copyOf(baselines.keySet());
        }

        // 外部 CLI 写盘后主动刷新已登记文件，避免依赖 IDE 是否恰好收到 VFS 通知。
        if (project == null || ApplicationManager.getApplication() == null) {
            fireChanged(key);
            return;
        }
        LocalFileSystem.getInstance().refreshNioFiles(paths, true, false, () -> fireChanged(key));
    }

    private boolean trackBaseline(String sessionId, Path target, CurrentContent current) {
        var baseline = current.present()
            ? new Baseline(BaselineKind.PRESENT, copy(current.bytes()), copy(current.hash()))
            : new Baseline(BaselineKind.ABSENT, null, null);
        return trackBaseline(sessionId, target, baseline);
    }

    private boolean trackBaseline(String sessionId, Path target, Baseline baseline) {
        var baselines = baselinesBySession.computeIfAbsent(sessionId, ignored -> new LinkedHashMap<>());
        if (baselines.putIfAbsent(target, baseline) != null) return false;
        fireChanged(sessionId);
        return true;
    }

    private Baseline baseline(String sessionId, Path path) {
        var baselines = baselinesBySession.get(sessionId);
        return baselines == null ? null : baselines.get(path);
    }

    private void removeBaseline(String sessionId, Path path) {
        if (path == null) return;
        var baselines = baselinesBySession.get(sessionId);
        if (baselines == null || baselines.remove(path) == null) return;
        if (baselines.isEmpty()) baselinesBySession.remove(sessionId);
        fireChanged(sessionId);
    }

    private ChangeEntry createChange(Path path, Baseline baseline) {
        var current = readCurrent(path);
        if (baseline.kind() == BaselineKind.ABSENT && !current.present()) return null;
        if (baseline.kind() == BaselineKind.PRESENT && current.present()
            && Arrays.equals(baseline.hash(), current.hash())) return null;

        var kind = baseline.kind() == BaselineKind.ABSENT
            ? ChangeEntry.Kind.ADDED
            : current.present() ? ChangeEntry.Kind.MODIFIED : ChangeEntry.Kind.DELETED;
        var relativePath = root.relativize(path).toString().replace('\\', '/');
        var before = baseline.kind() == BaselineKind.PRESENT ? copy(baseline.bytes()) : null;
        var after = current.present() ? copy(current.bytes()) : null;
        var diff = UnifiedDiffBuilder.create(relativePath, kind, before, after, baseline.hash(), current.hash());
        return new ChangeEntry(path, kind, before, after, baseline.reversible(), diff);
    }

    private CurrentContent readCurrent(Path path) {
        var documentBytes = readDocument(path);
        if (documentBytes != null) return content(documentBytes);
        if (!Files.isRegularFile(path)) return new CurrentContent(false, null, null);
        try {
            if (Files.size(path) <= MAX_BASELINE_BYTES) return content(Files.readAllBytes(path));
            return new CurrentContent(true, null, hash(path));
        } catch (IOException error) {
            return new CurrentContent(true, null, null);
        }
    }

    private byte[] readDocument(Path path) {
        if (project == null || ApplicationManager.getApplication() == null) return null;
        var file = LocalFileSystem.getInstance().findFileByNioFile(path);
        if (file == null) return null;
        var document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) return null;
        return ApplicationManager.getApplication().runReadAction(
            (Computable<byte[]>) () -> document.getText().getBytes(StandardCharsets.UTF_8));
    }

    private CurrentContent content(byte[] bytes) {
        return bytes.length <= MAX_BASELINE_BYTES
            ? new CurrentContent(true, copy(bytes), hash(bytes))
            : new CurrentContent(true, null, hash(bytes));
    }

    private void restore(Path path, Baseline baseline) throws IOException {
        if (!baseline.reversible()) throw new IOException("文件超过 5 MiB，未保存可撤销基线");
        if (baseline.kind() == BaselineKind.ABSENT) {
            delete(path);
            return;
        }
        var parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        write(path, baseline.bytes());
    }

    private void write(Path path, byte[] content) throws IOException {
        if (project != null && ApplicationManager.getApplication() != null) {
            var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            var document = file == null ? null : FileDocumentManager.getInstance().getDocument(file);
            if (document != null) {
                WriteAction.run(() -> document.setText(new String(content, StandardCharsets.UTF_8)));
                FileDocumentManager.getInstance().saveDocument(document);
                return;
            }
        }
        Files.write(path, content);
    }

    private void delete(Path path) throws IOException {
        if (project != null && ApplicationManager.getApplication() != null) {
            var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            if (file != null) {
                final IOException[] failure = new IOException[1];
                WriteAction.run(() -> {
                    try {
                        file.delete(this);
                    } catch (IOException error) {
                        failure[0] = error;
                    }
                });
                if (failure[0] != null) throw failure[0];
                return;
            }
        }
        Files.deleteIfExists(path);
    }

    private void installListeners() {
        if (ApplicationManager.getApplication() == null) return;
        project.getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                for (var event : events) {
                    var file = event.getFile();
                    // ChangeSet 只处理能够映射到本地工作区的 VFS 事件。
                    if (file == null || !file.isInLocalFileSystem()) continue;
                    refreshTracked(file.toNioPath());
                }
            }
        });
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                var file = FileDocumentManager.getInstance().getFile(event.getDocument());
                if (file != null) refreshTracked(file.toNioPath());
            }
        }, this);
    }

    private synchronized void refreshTracked(Path changedPath) {
        var target = normalizePath(changedPath);
        if (target == null) return;
        for (var entry : baselinesBySession.entrySet()) {
            if (entry.getValue().containsKey(target)) fireChanged(entry.getKey());
        }
    }

    private void refresh(Path path) {
        if (ApplicationManager.getApplication() == null) return;
        LocalFileSystem.getInstance().refreshNioFiles(List.of(path), true, false, null);
    }

    private Path resolveReportedPath(String reportedPath) {
        return pathPolicy.resolve(reportedPath);
    }

    private Path normalizePath(Path path) {
        return pathPolicy.normalize(path);
    }

    private LineStats lineStats(String diff) {
        var added = 0;
        var deleted = 0;
        var inHunk = false;
        for (var line : diff.split("\\R")) {
            if (line.startsWith("@@")) {
                inHunk = true;
                continue;
            }
            if (!inHunk) continue;
            if (line.startsWith("+")) added++;
            if (line.startsWith("-")) deleted++;
        }
        return new LineStats(added, deleted);
    }

    private void fireChanged(String sessionId) {
        var update = new ChangeUpdate(sessionId, listSummaries(sessionId));
        listeners.forEach(listener -> listener.accept(update));
    }

    private String sessionKey(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "default" : sessionId;
    }

    private static byte[] hash(byte[] content) {
        if (content == null) return null;
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static byte[] hash(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                var buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static byte[] copy(byte[] content) {
        return content == null ? null : Arrays.copyOf(content, content.length);
    }

    @Override
    public synchronized void dispose() {
        baselinesBySession.clear();
        listeners.clear();
    }
}
