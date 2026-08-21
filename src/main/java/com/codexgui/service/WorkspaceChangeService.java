package com.codexgui.service;

import com.codexgui.model.ChangeEntry;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service(Service.Level.PROJECT)
public final class WorkspaceChangeService {
    private static final Logger LOG = Logger.getInstance(WorkspaceChangeService.class);
    private static final long MAX_SNAPSHOT_BYTES = 5L * 1024L * 1024L;
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
        ".git", ".gradle", ".idea", "build", "out", "node_modules", "target", ".next", ".cache"
    );

    private final Project project;
    private final Path root;
    private final CopyOnWriteArrayList<Consumer<List<ChangeEntry>>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean scanRunning = new AtomicBoolean();
    private final Map<String, String> serverDiffs = new java.util.concurrent.ConcurrentHashMap<>();

    private volatile Map<Path, FileState> baseline = Map.of();
    private volatile List<ChangeEntry> changes = List.of();
    private volatile boolean captureActive;

    public WorkspaceChangeService(Project project) {
        this.project = project;
        this.root = project.getBasePath() == null ? Path.of(".").toAbsolutePath().normalize()
            : Path.of(project.getBasePath()).toAbsolutePath().normalize();
    }

    public static WorkspaceChangeService getInstance(@NotNull Project project) {
        return project.getService(WorkspaceChangeService.class);
    }

    public Path getRoot() {
        return root;
    }

    public List<ChangeEntry> getChanges() {
        return changes;
    }

    public void addListener(Consumer<List<ChangeEntry>> listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(Consumer<List<ChangeEntry>> listener) {
        listeners.remove(listener);
    }

    public CompletableFuture<Void> beginCaptureAsync() {
        captureActive = false;
        return CompletableFuture.runAsync(() -> {
            // 未处理修改跨回合保留；列表清空后才以当前工作区建立新基线。
            if (changes.isEmpty()) {
                baseline = takeSnapshot();
                serverDiffs.clear();
            } else {
                refreshBaselinePreservingPending();
            }
            captureActive = true;
        }, AppExecutorUtil.getAppExecutorService());
    }

    public void updateServerDiff(String unifiedDiff) {
        if (!captureActive) return;
        serverDiffs.putAll(UnifiedDiffParser.split(unifiedDiff));
        rescanAsync();
    }

    public void rescanAsync() {
        if (!captureActive || !scanRunning.compareAndSet(false, true)) return;
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                changes = compare(baseline, takeSnapshot());
                fireChanged();
            } finally {
                scanRunning.set(false);
            }
        });
    }

    public CompletableFuture<Void> finishCaptureAsync() {
        if (!captureActive) return CompletableFuture.completedFuture(null);
        captureActive = false;
        return CompletableFuture.runAsync(() -> {
            changes = compare(baseline, takeSnapshot());
            fireChanged();
        }, AppExecutorUtil.getAppExecutorService());
    }

    public void accept(ChangeEntry entry) {
        // 接受只推进该文件的基线，其它尚未处理的修改继续留在列表中。
        updateBaseline(entry.path());
        serverDiffs.remove(relativePath(entry.path()));
        changes = changes.stream().filter(change -> !change.path().equals(entry.path())).toList();
        fireChanged();
    }

    public void acceptAll() {
        // 逐文件推进基线，避免在界面线程重新扫描整个工作区造成卡顿。
        for (var change : changes) updateBaseline(change.path());
        serverDiffs.clear();
        changes = List.of();
        fireChanged();
    }

    public void revert(ChangeEntry entry) throws IOException {
        if (!entry.reversible()) throw new IOException("该文件超过快照大小限制，无法安全撤销");
        restore(entry);
        serverDiffs.remove(relativePath(entry.path()));
        changes = changes.stream().filter(change -> !change.path().equals(entry.path())).toList();
        refresh(entry.path());
        fireChanged();
    }

    public void revertAll() throws IOException {
        var failures = new ArrayList<String>();
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
        changes = changes.stream().filter(change -> failures.stream().anyMatch(text -> text.startsWith(change.path().toString()))).toList();
        serverDiffs.keySet().removeIf(path -> changes.stream().noneMatch(change -> relativePath(change.path()).equals(path)));
        fireChanged();
        if (!failures.isEmpty()) throw new IOException("以下文件无法撤销：\n" + String.join("\n", failures));
    }

    private void restore(ChangeEntry entry) throws IOException {
        // 新文件撤销时删除；已有文件则恢复回合开始前的字节内容。
        if (entry.kind() == ChangeEntry.Kind.ADDED) {
            Files.deleteIfExists(entry.path());
            return;
        }
        var parent = entry.path().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(entry.path(), entry.beforeContent());
    }

    private void refresh(Path path) {
        LocalFileSystem.getInstance().refreshIoFiles(List.of(path.toFile()), true, false, null);
    }

    private Map<Path, FileState> takeSnapshot() {
        var snapshot = new LinkedHashMap<Path, FileState>();
        if (!Files.isDirectory(root)) return snapshot;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    try {
                        var size = attrs.size();
                        byte[] content = size <= MAX_SNAPSHOT_BYTES ? Files.readAllBytes(file) : null;
                        byte[] digest = content == null ? digestFile(file) : digest(content);
                        snapshot.put(file.toAbsolutePath().normalize(), new FileState(content, digest, size));
                    } catch (IOException error) {
                        LOG.debug("无法为文件创建修改快照：" + file, error);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException error) {
            LOG.warn("无法扫描项目修改", error);
        }
        return snapshot;
    }

    private List<ChangeEntry> compare(Map<Path, FileState> before, Map<Path, FileState> after) {
        var paths = new HashSet<Path>();
        paths.addAll(before.keySet());
        paths.addAll(after.keySet());
        var result = new ArrayList<ChangeEntry>();
        for (var path : paths.stream().sorted().toList()) {
            var oldState = before.get(path);
            var newState = after.get(path);
            if (oldState != null && newState != null && Arrays.equals(oldState.digest(), newState.digest())) continue;

            ChangeEntry.Kind kind;
            if (oldState == null) kind = ChangeEntry.Kind.ADDED;
            else if (newState == null) kind = ChangeEntry.Kind.DELETED;
            else kind = ChangeEntry.Kind.MODIFIED;
            var reversible = kind == ChangeEntry.Kind.ADDED || oldState != null && oldState.content() != null;
            var relative = relativePath(path);
            result.add(new ChangeEntry(
                path,
                kind,
                oldState == null ? null : oldState.content(),
                newState == null ? null : newState.content(),
                reversible,
                serverDiffs.getOrDefault(relative, "")
            ));
        }
        return List.copyOf(result);
    }

    private void updateBaseline(Path path) {
        var next = new LinkedHashMap<>(baseline);
        var state = readFileState(path);
        if (state == null) next.remove(path);
        else next.put(path, state);
        baseline = Map.copyOf(next);
    }

    private void refreshBaselinePreservingPending() {
        var previous = baseline;
        var next = new LinkedHashMap<>(takeSnapshot());
        for (var change : changes) {
            // 已在列表中的文件仍与最初状态比较，其它文件从本回合开始重新计量。
            if (previous.containsKey(change.path())) next.put(change.path(), previous.get(change.path()));
            else next.remove(change.path());
        }
        baseline = Map.copyOf(next);
    }

    private FileState readFileState(Path path) {
        if (!Files.isRegularFile(path)) return null;
        try {
            var size = Files.size(path);
            byte[] content = size <= MAX_SNAPSHOT_BYTES ? Files.readAllBytes(path) : null;
            return new FileState(content, content == null ? digestFile(path) : digest(content), size);
        } catch (IOException error) {
            LOG.debug("无法更新文件修改基线：" + path, error);
            return null;
        }
    }

    private String relativePath(Path path) {
        try {
            return root.relativize(path).toString().replace('\\', '/');
        } catch (IllegalArgumentException ignored) {
            return path.toString().replace('\\', '/');
        }
    }

    private byte[] digestFile(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private byte[] digest(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void fireChanged() {
        var snapshot = changes;
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() ->
            listeners.forEach(listener -> listener.accept(snapshot)));
    }

    private record FileState(byte[] content, byte[] digest, long size) {}
}
