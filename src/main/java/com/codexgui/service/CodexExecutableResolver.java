package com.codexgui.service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class CodexExecutableResolver {
    private CodexExecutableResolver() {
    }

    static String resolve(String configured, boolean windows) {
        var userHome = Path.of(System.getProperty("user.home", "."));
        return resolve(configured, windows, System.getenv(), userHome);
    }

    static String resolve(String configured, boolean windows, Map<String, String> environment, Path userHome) {
        var requested = configured == null ? "" : configured.trim();
        if (!requested.isBlank() && !requested.equalsIgnoreCase("codex")) return requested;
        if (!windows) return "codex";

        var candidates = new ArrayList<Path>();
        addPathCandidates(candidates, environment.get("PATH"));

        var appData = path(environment.get("APPDATA"));
        if (appData != null) candidates.add(appData.resolve("npm").resolve("codex.cmd"));

        var configuredHome = path(environment.get("CODEX_HOME"));
        var codexHome = configuredHome == null ? userHome.resolve(".codex") : configuredHome;
        candidates.add(codexHome.resolve("bin").resolve("codex.exe"));
        candidates.add(codexHome.resolve(".sandbox-bin").resolve("codex.exe"));
        candidates.add(codexHome.resolve("plugins").resolve(".plugin-appserver").resolve("codex.exe"));

        // WindowsApps 中的桌面应用副本需要应用身份，普通 JetBrains 进程无法直接启动。
        return candidates.stream()
            .filter(Files::isRegularFile)
            .filter(candidate -> !isWindowsApps(candidate))
            .map(Path::toString)
            .findFirst()
            .orElse("codex");
    }

    private static void addPathCandidates(List<Path> candidates, String pathValue) {
        if (pathValue == null || pathValue.isBlank()) return;
        for (String entry : pathValue.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            var directory = path(stripQuotes(entry));
            if (directory == null) continue;
            candidates.add(directory.resolve("codex.cmd"));
            candidates.add(directory.resolve("codex.exe"));
            candidates.add(directory.resolve("codex.bat"));
        }
    }

    private static Path path(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Path.of(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String stripQuotes(String value) {
        var trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean isWindowsApps(Path candidate) {
        return candidate.toAbsolutePath().normalize().toString().toLowerCase().contains("\\windowsapps\\");
    }
}
