package com.codexgui.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClaudeCodeServiceTest {
    @Test
    void keepsCancellationDistinctFromExecutionFailure() {
        var cancellation = new CancellationException("stopped");

        assertSame(cancellation, ClaudeCodeService.executionFailure("claude", cancellation));
        assertInstanceOf(IllegalStateException.class,
            ClaudeCodeService.executionFailure("claude", new IOException("broken")));
    }

    @Test
    void mapsPermissionAndEffortArguments() {
        assertEquals("manual", ClaudeCodeService.permissionMode("untrusted"));
        assertEquals("acceptEdits", ClaudeCodeService.permissionMode("on-request"));
        assertEquals("bypassPermissions", ClaudeCodeService.permissionMode("never"));
        assertEquals("low", ClaudeCodeService.effort("minimal"));
        assertEquals("max", ClaudeCodeService.effort("ultra"));
        assertEquals("high", ClaudeCodeService.effort("unexpected"));
    }

    @Test
    void requiresClaudeCodeVersionTwoOneTwoHundredTen() {
        assertFalse(ClaudeCodeService.supportsVersion("2.1.209 (Claude Code)"));
        assertTrue(ClaudeCodeService.supportsVersion("2.1.210 (Claude Code)"));
        assertTrue(ClaudeCodeService.supportsVersion("2.2.0"));
        assertTrue(ClaudeCodeService.supportsVersion("3.0.0-beta"));
        assertFalse(ClaudeCodeService.supportsVersion("Claude Code unknown"));
    }
}
