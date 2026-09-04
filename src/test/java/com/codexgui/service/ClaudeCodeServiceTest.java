package com.codexgui.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClaudeCodeServiceTest {
    @TempDir
    Path temporaryDirectory;

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

    @Test
    void writesHookSettingsToJsonFileWithoutCommandLineEscaping() throws Exception {
        var settings = "{\"hooks\":{\"PreToolUse\":[{\"command\":\"powershell.exe -Command \\\"Write-Output ok\\\"\"}]}}";
        var path = ClaudeCodeService.writeHookSettings(settings);
        try {
            assertTrue(path.isAbsolute());
            assertTrue(path.getFileName().toString().endsWith(".json"));
            assertEquals(settings, Files.readString(path, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void redactsSecretsAndBoundsClaudeDiagnostics() {
        var apiKey = ClaudeCodeService.sanitizeDiagnostic("ANTHROPIC_API_KEY=sk-ant-secret request failed");
        var bearer = ClaudeCodeService.sanitizeDiagnostic("Authorization: Bearer token-value");
        var jsonKey = ClaudeCodeService.sanitizeDiagnostic("{\"api_key\":\"sk-json-secret\",\"status\":401}");
        var longLine = ClaudeCodeService.sanitizeDiagnostic("x".repeat(2_000));

        assertFalse(apiKey.contains("sk-ant-secret"));
        assertFalse(bearer.contains("token-value"));
        assertFalse(jsonKey.contains("sk-json-secret"));
        assertTrue(apiKey.contains("<redacted>"));
        assertTrue(bearer.contains("<redacted>"));
        assertTrue(jsonKey.contains("<redacted>"));
        assertTrue(longLine.length() < 2_000);
        assertTrue(longLine.endsWith("..."));
    }

    @Test
    void timeoutMessageExplainsRecoveryAndIncludesRecentError() {
        var output = new StringBuilder("HTTP 504 Gateway Timeout");

        var message = ClaudeCodeService.firstResponseTimeoutMessage(1234, output);

        assertTrue(message.contains(ClaudeCodeService.FIRST_RESPONSE_TIMEOUT_SECONDS + " 秒"));
        assertTrue(message.contains("PID 1234"));
        assertTrue(message.contains("供应商接口、模型名称和网络代理"));
        assertTrue(message.contains("HTTP 504 Gateway Timeout"));
    }

    @Test
    void localInitializationDoesNotDisableFirstResponseTimeout() {
        assertFalse(ClaudeCodeService.isResponseEvent("system"));
        assertFalse(ClaudeCodeService.isResponseEvent("hook_event"));
        assertTrue(ClaudeCodeService.isResponseEvent("stream_event"));
        assertTrue(ClaudeCodeService.isResponseEvent("assistant"));
        assertTrue(ClaudeCodeService.isResponseEvent("result"));
    }

    @Test
    void listsProjectClaudeHistoryByFirstUserMessageAndSearch() throws Exception {
        var olderId = "11111111-1111-4111-8111-111111111111";
        var newerId = "22222222-2222-4222-8222-222222222222";
        writeHistory(olderId, "早期重构讨论");
        writeHistory(newerId, "修复供应商历史隔离");
        Files.setLastModifiedTime(temporaryDirectory.resolve(olderId + ".jsonl"), FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(temporaryDirectory.resolve(newerId + ".jsonl"), FileTime.fromMillis(2_000));

        var all = ClaudeCodeService.readHistoryItems(temporaryDirectory, "");
        var searched = ClaudeCodeService.readHistoryItems(temporaryDirectory, "供应商");

        assertEquals(2, all.size());
        assertEquals(newerId, all.getFirst().id());
        assertEquals("修复供应商历史隔离", searched.getFirst().title());
    }

    @Test
    void restoresClaudeUserAssistantThinkingAndToolEntries() throws Exception {
        var sessionId = "33333333-3333-4333-8333-333333333333";
        var content = String.join("\n",
            "{\"type\":\"user\",\"isSidechain\":false,\"uuid\":\"user-1\",\"timestamp\":\"2026-09-04T08:00:00Z\",\"message\":{\"content\":\"检查历史恢复\"}}",
            "{\"type\":\"user\",\"isMeta\":true,\"uuid\":\"meta-1\",\"message\":{\"content\":\"内部提示词\"}}",
            "{\"type\":\"assistant\",\"isSidechain\":false,\"uuid\":\"assistant-1\",\"timestamp\":\"2026-09-04T08:00:01Z\",\"message\":{\"content\":[{\"type\":\"thinking\",\"thinking\":\"分析会话来源\"},{\"type\":\"text\",\"text\":\"已经找到原因\"},{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"README.md\"}}]}}",
            "{\"type\":\"assistant\",\"isSidechain\":true,\"uuid\":\"side-1\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"旁路消息\"}]}}"
        );
        Files.writeString(temporaryDirectory.resolve(sessionId + ".jsonl"), content, StandardCharsets.UTF_8);

        var history = ClaudeCodeService.readHistoryConversation(temporaryDirectory, sessionId);

        assertEquals("检查历史恢复", history.title());
        assertEquals(4, history.entries().size());
        assertEquals(ClaudeCodeService.HistoryEntryKind.USER, history.entries().get(0).kind());
        assertEquals(ClaudeCodeService.HistoryEntryKind.REASONING, history.entries().get(1).kind());
        assertEquals(ClaudeCodeService.HistoryEntryKind.ASSISTANT, history.entries().get(2).kind());
        assertEquals(ClaudeCodeService.HistoryEntryKind.COMMAND, history.entries().get(3).kind());
        assertTrue(history.entries().get(3).body().contains("README.md"));
    }

    private void writeHistory(String sessionId, String firstPrompt) throws IOException {
        var content = "{\"type\":\"user\",\"isSidechain\":false,\"uuid\":\"user\","
            + "\"message\":{\"content\":\"" + firstPrompt + "\"}}";
        Files.writeString(temporaryDirectory.resolve(sessionId + ".jsonl"), content, StandardCharsets.UTF_8);
    }
}
