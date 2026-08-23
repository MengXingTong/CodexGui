package com.codexgui.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public record FileReference(String id, String name, Path path, boolean directory) {
    public static FileReference fromPath(Path path) {
        var normalized = path.toAbsolutePath().normalize();
        var fileName = normalized.getFileName();
        return new FileReference(
            UUID.randomUUID().toString(),
            fileName == null ? normalized.toString() : fileName.toString(),
            normalized,
            Files.isDirectory(normalized)
        );
    }
}
