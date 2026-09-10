package com.codexgui.service;

import com.codexgui.settings.CodexSettingsState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
        var endpoint = modelsEndpoint(provider);
        if (provider.channel() == CodexSettingsState.ProviderChannel.CLAUDE) {
            endpoint = withQueryParameter(endpoint, "limit", "1000");
        }
        return listModels(provider, apiKey, endpoint, new LinkedHashSet<>(), new LinkedHashSet<>());
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

    private CompletableFuture<List<String>> listModels(
        CodexSettingsState.ProviderProfileSnapshot provider,
        String apiKey,
        URI endpoint,
        LinkedHashSet<String> models,
        Set<URI> visitedPages
    ) {
        if (!visitedPages.add(endpoint)) {
            return CompletableFuture.failedFuture(new IOException("供应商模型接口返回了重复分页地址"));
        }
        return client.sendAsync(request(provider, apiKey, endpoint), HttpResponse.BodyHandlers.ofString())
            .thenCompose(response -> {
                var pageModels = parseResponse(response.statusCode(), response.body());
                models.addAll(pageModels);
                var nextPage = nextPage(response.body(), endpoint, provider.channel());
                if (nextPage != null) {
                    return listModels(provider, apiKey, nextPage, models, visitedPages);
                }
                if (models.isEmpty()) {
                    return CompletableFuture.failedFuture(new IOException("供应商模型接口未返回可用模型"));
                }
                return CompletableFuture.completedFuture(new ArrayList<>(models));
            });
    }

    private HttpRequest request(
        CodexSettingsState.ProviderProfileSnapshot provider,
        String apiKey,
        URI endpoint
    ) {
        var builder = HttpRequest.newBuilder(endpoint)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .header("User-Agent", "CodeDeck/0.5.3")
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
            return parseModels(body);
        } catch (RuntimeException error) {
            throw new CompletionException(error);
        }
    }

    static URI nextPage(String body, URI current, CodexSettingsState.ProviderChannel channel) {
        var root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return null;
        var object = root.getAsJsonObject();
        var hasMore = object.has("has_more") && object.get("has_more").isJsonPrimitive()
            && object.get("has_more").getAsBoolean();
        if (hasMore && object.has("last_id") && object.get("last_id").isJsonPrimitive()) {
            return withQueryParameter(current, "after_id", object.get("last_id").getAsString());
        }
        for (var key : List.of("next_cursor", "nextCursor")) {
            if (!object.has(key) || object.get(key).isJsonNull()) continue;
            var cursor = object.get(key).getAsString().trim();
            if (!cursor.isBlank()) return withQueryParameter(current,
                channel == CodexSettingsState.ProviderChannel.CLAUDE ? "after_id" : "cursor", cursor);
        }
        return null;
    }

    private static URI withQueryParameter(URI uri, String key, String value) {
        var encoded = URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        var query = uri.getRawQuery();
        var pairs = new ArrayList<String>();
        if (query != null && !query.isBlank()) {
            for (var pair : query.split("&")) {
                if (!pair.startsWith(key + "=")) pairs.add(pair);
            }
        }
        pairs.add(key + "=" + encoded);
        try {
            var source = uri.toASCIIString();
            var queryStart = source.indexOf('?');
            if (queryStart >= 0) source = source.substring(0, queryStart);
            return URI.create(source + "?" + String.join("&", pairs));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("供应商模型分页地址无效", error);
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
