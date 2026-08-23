package com.codexgui.model;

import java.nio.file.Files;
import java.nio.file.Path;

public record FileReference(String name, Path path, boolean directory) {
    public static FileReference fromPath(Path path) {
        var normalized = path.toAbsolutePath().normalize();
        var fileName = normalized.getFileName();
        return new FileReference(fileName == null ? normalized.toString() : fileName.toString(), normalized, Files.isDirectory(normalized));
    }
}
