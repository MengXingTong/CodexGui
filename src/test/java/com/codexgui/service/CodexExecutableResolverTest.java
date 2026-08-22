package com.codexgui.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CodexExecutableResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesExplicitExecutable() {
        var configured = temporaryDirectory.resolve("custom-codex.exe").toString();

        assertEquals(configured, CodexExecutableResolver.resolve(configured, true, Map.of(), temporaryDirectory));
    }

    @Test
    void discoversCodexDesktopCliWhenPathHasNoUsableCli() throws IOException {
        var cli = temporaryDirectory.resolve(".codex").resolve(".sandbox-bin").resolve("codex.exe");
        Files.createDirectories(cli.getParent());
        Files.createFile(cli);

        assertEquals(cli.toString(), CodexExecutableResolver.resolve("codex", true, Map.of(), temporaryDirectory));
    }

    @Test
    void skipsRestrictedWindowsAppsCandidate() throws IOException {
        var windowsApps = temporaryDirectory.resolve("WindowsApps");
        Files.createDirectories(windowsApps);
        Files.createFile(windowsApps.resolve("codex.exe"));
        var desktopCli = temporaryDirectory.resolve(".codex").resolve(".sandbox-bin").resolve("codex.exe");
        Files.createDirectories(desktopCli.getParent());
        Files.createFile(desktopCli);

        assertEquals(desktopCli.toString(), CodexExecutableResolver.resolve("codex", true,
            Map.of("PATH", windowsApps.toString()), temporaryDirectory));
    }

    @Test
    void prefersCliFoundOnPath() throws IOException {
        var bin = temporaryDirectory.resolve("bin");
        var cli = bin.resolve("codex.cmd");
        Files.createDirectories(bin);
        Files.createFile(cli);

        assertEquals(cli.toString(), CodexExecutableResolver.resolve("codex", true,
            Map.of("PATH", bin.toString()), temporaryDirectory));
    }

    @Test
    void leavesDefaultCommandUnchangedOutsideWindows() {
        assertEquals("codex", CodexExecutableResolver.resolve("", false, Map.of(), temporaryDirectory));
    }
}
