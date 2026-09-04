package com.codexgui.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Utf8IO {
    private Utf8IO() {}

    public static String read(InputStream input) throws IOException {
        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    public static void write(Path path, CharSequence content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
