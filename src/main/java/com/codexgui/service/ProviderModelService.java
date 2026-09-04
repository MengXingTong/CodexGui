package com.codexgui.service;

import com.codexgui.settings.CodexSettingsState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class ProviderModelService {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private final HttpClient client;

    public ProviderModelService() {
        this(HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build());
    }

    ProviderModelService(HttpClient client) {
        this.client = client;
    }

    public CompletableFuture<List<String>> listModels(
        CodexSettingsState.ProviderProfileSnapshot provider,
        String apiKey
    ) {
        var request = request(provider, apiKey);
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> parseResponse(response.statusCode(), response.body()));
    }

    static URI modelsEndpoint(CodexSettingsState.ProviderProfileSnapshot provider) {
        var base = URI.create(provider.baseUrl());
        var path = base.getPath() == null ? "" : base.getPath().replaceFirst("/+$", "");
        if (provider.channel() == CodexSettingsState.ProviderChannel.CLAUDE
            && !path.endsWith("/v1") && !path.endsWith("/v1/models")) {
            path += "/v1";
        }
        if (!path.endsWith("/models")) path += "/models";
        if (!path.startsWith("/")) path = "/" + path;
        try {
            return new URI(base.getScheme(), base.getUserInfo(), base.getHost(), base.getPort(), path, null, null);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("供应商模型接口地址无效", error);
        }
    }

    static List<String> parseModels(String body) {
        var root = JsonParser.parseString(body);
        JsonArray items;
        if (root.isJsonArray()) {
            items = root.getAsJsonArray();
        } else {
            var object = root.getAsJsonObject();
            items = array(object, "data");
            if (items.isEmpty()) items = array(object, "models");
        }

        // 兼容 OpenAI、Anthropic 及常见中转站格式，并保持接口返回顺序。
        var ids = new LinkedHashSet<String>();
        for (var item : items) {
            var id = modelId(item);
            if (!id.isBlank()) ids.add(id);
        }
        return new ArrayList<>(ids);
    }

    private HttpRequest request(CodexSettingsState.ProviderProfileSnapshot provider, String apiKey) {
        var builder = HttpRequest.newBuilder(modelsEndpoint(provider))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .header("User-Agent", "CodeDeck/0.5.1")
            .GET();

        // 两类渠道沿用各自 CLI 的认证约定，避免模型请求与实际会话使用不同凭据。
        if (provider.channel() == CodexSettingsState.ProviderChannel.CLAUDE) {
            builder.header("anthropic-version", "2023-06-01");
            builder.header("Authorization", "Bearer " + apiKey);
        } else {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    private static List<String> parseResponse(int statusCode, String body) {
        if (statusCode < 200 || statusCode >= 300) {
            throw new CompletionException(new IOException("供应商模型接口返回 HTTP " + statusCode));
        }
        try {
            var models = parseModels(body);
            if (models.isEmpty()) throw new IOException("供应商模型接口未返回可用模型");
            return models;
        } catch (RuntimeException | IOException error) {
            throw new CompletionException(error);
        }
    }

    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray();
    }

    private static String modelId(JsonElement item) {
        if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) return item.getAsString().trim();
        if (!item.isJsonObject()) return "";
        var object = item.getAsJsonObject();
        for (var key : List.of("id", "model", "slug")) {
            if (object.has(key) && object.get(key).isJsonPrimitive()) return object.get(key).getAsString().trim();
        }
        return "";
    }
}
