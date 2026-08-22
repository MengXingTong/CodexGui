package com.codexgui.model;

import java.nio.file.Path;

public record Attachment(Kind kind, String name, Path path) {
    public enum Kind { FILE, IMAGE }
}
