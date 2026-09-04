package com.codexgui.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

public final class WorkspacePathPolicy {
    private final Path root;
    private final Path realRoot;

    public WorkspacePathPolicy(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.realRoot = realPath(this.root);
    }

    public Path root() { return root; }

    public Path resolve(String reportedPath) {
        if (reportedPath == null || reportedPath.isBlank()) return null;
        try {
            var candidate = Path.of(reportedPath.replace('/', root.getFileSystem().getSeparator().charAt(0)));
            return normalize(candidate.isAbsolute() ? candidate : root.resolve(candidate));
        } catch (RuntimeException error) {
            return null;
        }
    }

    public Path normalize(Path path) {
        if (path == null) return null;
        var normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root)) return null;

        // 最近的已存在父路径决定符号链接边界，新文件也必须留在真实工作区内。
        var existing = normalized;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (existing == null) return null;
        try {
            return existing.toRealPath().startsWith(realRoot) ? normalized : null;
        } catch (IOException error) {
            return null;
        }
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException error) {
            return path.toAbsolutePath().normalize();
        }
    }
}
