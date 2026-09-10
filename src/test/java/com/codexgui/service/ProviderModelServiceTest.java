package com.codexgui.service;

import com.codexgui.settings.CodexSettingsState;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ProviderModelServiceTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void loadsAndDeduplicatesModelsWithoutAStoredSelection() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/models", exchange -> {
            assertEquals("Bearer secret-gpt", exchange.getRequestHeaders().getFirst("Authorization"));
            var body = """
                {"data":[{"id":"gateway-model-a"},{"id":"gateway-model-b"},{"id":"gateway-model-a"}]}
                """;
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();

        var profile = profile(CodexSettingsState.CODEX_CHANNEL, "/api/v1");
        profile.model = "";
        var models = new ProviderModelService().listModels(profile.snapshot(), "secret-gpt").get();

        assertEquals(List.of("gateway-model-a", "gateway-model-b"), models);
    }

    @Test
    void usesClaudeModelsPathAndBearerToken() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            assertNull(exchange.getRequestHeaders().getFirst("x-api-key"));
            assertEquals("Bearer secret-claude", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("2023-06-01", exchange.getRequestHeaders().getFirst("anthropic-version"));
            assertEquals("limit=1000", exchange.getRequestURI().getQuery());
            var bytes = "{\"data\":[{\"id\":\"claude-gateway-model\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();

        var profile = profile(CodexSettingsState.CLAUDE_CHANNEL, "");
        var models = new ProviderModelService().listModels(profile.snapshot(), "secret-claude").get();

        assertEquals(List.of("claude-gateway-model"), models);
    }

    @Test
    void loadsEveryClaudeModelPage() throws Exception {
        var requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            requests.incrementAndGet();
            var secondPage = exchange.getRequestURI().getQuery().contains("after_id=claude-page-1");
            var body = secondPage
                ? "{\"data\":[{\"id\":\"claude-page-2\"}],\"has_more\":false}"
                : "{\"data\":[{\"id\":\"claude-page-1\"}],\"has_more\":true,\"last_id\":\"claude-page-1\"}";
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();

        var profile = profile(CodexSettingsState.CLAUDE_CHANNEL, "");
        var models = new ProviderModelService().listModels(profile.snapshot(), "secret-claude").get();

        assertEquals(List.of("claude-page-1", "claude-page-2"), models);
        assertEquals(2, requests.get());
    }

    @Test
    void rejectsProviderWithoutAnyAvailableModels() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            var bytes = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();

        var profile = profile(CodexSettingsState.CODEX_CHANNEL, "");
        var error = assertThrows(ExecutionException.class,
            () -> new ProviderModelService().listModels(profile.snapshot(), "secret-gpt").get());

        assertEquals("供应商模型接口未返回可用模型", error.getCause().getMessage());
    }

    private CodexSettingsState.ProviderProfile profile(String channel, String path) {
        var profile = new CodexSettingsState.ProviderProfile("provider-test", channel, "测试供应商", false);
        profile.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + path;
        profile.model = "configured-model";
        return profile;
    }
}
