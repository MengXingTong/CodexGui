package com.codexgui.model;

import java.nio.file.Path;

public record ChangeEntry(
    Path path,
    Kind kind,
    byte[] beforeContent,
    byte[] afterContent,
    boolean reversible,
    String unifiedDiff
) {
    public enum Kind { ADDED, MODIFIED, DELETED }

    public String displayName(Path root) {
        try {
            return root.relativize(path).toString();
        } catch (IllegalArgumentException ignored) {
            return path.toString();
        }
    }
}
