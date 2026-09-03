package com.codexgui.service;

import com.codexgui.model.ChangeEntry;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class WorkspaceSnapshot implements AutoCloseable {
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
        ".git", ".gradle", ".idea", ".vs", ".cache", ".next",
        "binaries", "build", "deriveddatacache", "intermediate", "node_modules", "out", "saved", "target"
    );

    private final Path storageDirectory;
    private final Map<String, FileState> files;

    private WorkspaceSnapshot(Path storageDirectory, Map<String, FileState> files) {
        this.storageDirectory = storageDirectory;
        this.files = Map.copyOf(files);
    }

    static WorkspaceSnapshot capture(Path root, boolean captureIgnoredFiles, long maxCaptureBytes) throws IOException {
        return capture(root, captureIgnoredFiles, maxCaptureBytes, Set.of());
    }

    static WorkspaceSnapshot capture(
        Path root,
        boolean captureIgnoredFiles,
        long maxCaptureBytes,
        Set<String> baselinePaths
    ) throws IOException {
        var normalizedRoot = root.toAbsolutePath().normalize();
        var storageDirectory = Files.createTempDirectory("codexgui-workspace-");
        var visibleFiles = captureIgnoredFiles ? null : listGitVisibleFiles(normalizedRoot);
        var files = new HashMap<String, FileState>();
        var storedFileIndex = new int[]{0};
        try {
            Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!directory.equals(normalizedRoot) && isSkippedDirectory(directory)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (!attributes.isRegularFile()) return FileVisitResult.CONTINUE;
                    if (file.getFileName() != null && file.getFileName().toString().equalsIgnoreCase(".git")) return FileVisitResult.CONTINUE;
                    var relativePath = relativePath(normalizedRoot, file);
                    if (visibleFiles != null && !visibleFiles.contains(relativePath) && !baselinePaths.contains(relativePath)) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        files.put(relativePath, captureFile(file, storageDirectory, storedFileIndex, maxCaptureBytes));
                    } catch (IOException ignored) {
                        // 文件在扫描期间消失或不可读时跳过，避免阻断 Claude 回合。
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    return FileVisitResult.CONTINUE;
                }
            });
            return new WorkspaceSnapshot(storageDirectory, files);
        } catch (IOException | RuntimeException error) {
            deleteDirectory(storageDirectory);
            throw error;
        }
    }

    List<SnapshotChange> compare(WorkspaceSnapshot after) {
        var paths = new TreeSet<String>();
        paths.addAll(files.keySet());
        paths.addAll(after.files.keySet());
        var result = new ArrayList<SnapshotChange>();
        for (var relativePath : paths) {
            var beforeState = files.get(relativePath);
            var afterState = after.files.get(relativePath);
            if (beforeState != null && afterState != null && Arrays.equals(beforeState.hash(), afterState.hash())) continue;

            // 只为实际变化的文件加载内容，扫描阶段的大部分数据始终保留在临时目录中。
            var beforeContent = readStoredContent(beforeState);
            var afterContent = after.readStoredContent(afterState);
            var kind = beforeState == null ? ChangeEntry.Kind.ADDED
                : afterState == null ? ChangeEntry.Kind.DELETED : ChangeEntry.Kind.MODIFIED;
            result.add(new SnapshotChange(
                relativePath,
                kind,
                beforeContent,
                afterContent,
                beforeState == null ? null : beforeState.hash(),
                afterState == null ? null : afterState.hash()
            ));
        }
        return List.copyOf(result);
    }

    Set<String> paths() {
        return files.keySet();
    }

    private byte[] readStoredContent(FileState state) {
        if (state == null || state.storedFile() == null) return null;
        try {
            return Files.readAllBytes(state.storedFile());
        } catch (IOException ignored) {
            return null;
        }
    }

    private static FileState captureFile(Path file, Path storageDirectory, int[] storedFileIndex, long maxCaptureBytes) throws IOException {
        var size = Files.size(file);
        if (size <= maxCaptureBytes) {
            var content = Files.readAllBytes(file);
            var storedFile = storageDirectory.resolve("file-" + storedFileIndex[0]++);
            Files.write(storedFile, content);
            return new FileState(hash(content), storedFile);
        }

        // 大文件只流式计算指纹，不保存内容，避免临时空间和内存无上限增长。
        var digest = digest();
        try (var input = Files.newInputStream(file)) {
            input.transferTo(new DigestOutputStream(digest));
        }
        return new FileState(digest.digest(), null);
    }

    private static Set<String> listGitVisibleFiles(Path root) {
        try {
            var process = new ProcessBuilder("git", "-C", root.toString(), "ls-files", "--cached", "--others", "--exclude-standard", "-z")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            var output = process.getInputStream().readAllBytes();
            if (process.waitFor() != 0) return null;
            var result = new LinkedHashSet<String>();
            var start = 0;
            for (var index = 0; index < output.length; index++) {
                if (output[index] != 0) continue;
                if (index > start) result.add(new String(output, start, index - start, StandardCharsets.UTF_8).replace('\\', '/'));
                start = index + 1;
            }
            return result;
        } catch (IOException ignored) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static boolean isSkippedDirectory(Path directory) {
        var name = directory.getFileName();
        return name != null && SKIPPED_DIRECTORIES.contains(name.toString().toLowerCase(Locale.ROOT));
    }

    private static String relativePath(Path root, Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static byte[] hash(byte[] content) {
        return digest().digest(content);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("当前 Java 运行时不支持 SHA-256", error);
        }
    }

    @Override
    public void close() {
        deleteDirectory(storageDirectory);
    }

    private static void deleteDirectory(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时快照由系统临时目录兜底清理，单个文件占用不影响主流程。
                }
            });
        } catch (IOException ignored) {
            // 临时目录清理失败不影响修改捕获结果。
        }
    }

    record SnapshotChange(
        String relativePath,
        ChangeEntry.Kind kind,
        byte[] beforeContent,
        byte[] afterContent,
        byte[] beforeHash,
        byte[] afterHash
    ) {}

    private record FileState(byte[] hash, Path storedFile) {}

    private static final class DigestOutputStream extends OutputStream {
        private final MessageDigest digest;

        private DigestOutputStream(MessageDigest digest) {
            this.digest = digest;
        }

        @Override
        public void write(int value) {
            digest.update((byte) value);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            digest.update(buffer, offset, length);
        }
    }
}
