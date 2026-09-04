package com.codexgui.provider;

import com.codexgui.conversation.TurnHandle;
import com.codexgui.service.CodexAppServerService;
import com.codexgui.service.CodexEventListener;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class CodexConversationProvider implements ConversationProvider, CodexEventListener, AutoCloseable {
    private record ActiveTurn(String threadId, String providerTurnId, TurnEventSink sink) {}

    private static final Gson GSON = new Gson();
    private static final TypeToken<Map<String, Object>> ITEM_DATA_TYPE = new TypeToken<>() {};

    private static final Set<ProviderCapability> CAPABILITIES = Set.of(
        ProviderCapability.HISTORY,
        ProviderCapability.SKILLS,
        ProviderCapability.MCP,
        ProviderCapability.APPROVAL,
        ProviderCapability.USAGE
    );

    private final CodexAppServerService service;
    private final ConcurrentHashMap<TurnHandle, ActiveTurn> activeTurns = new ConcurrentHashMap<>();

    public CodexConversationProvider(CodexAppServerService service) {
        this.service = service;
        service.addListener(this);
    }

    @Override public String id() { return "codex"; }

    @Override
    public TurnHandle startTurn(TurnRequest request, TurnEventSink sink) {
        var handle = request.handle();
        var thread = request.conversationId().isBlank()
            ? service.startThread(
                request.model(), request.effort(), request.serviceTier(), request.approvalPolicy(),
                request.sandboxMode(), request.instructions())
                .thenApply(result -> result.getAsJsonObject("thread").get("id").getAsString())
            : java.util.concurrent.CompletableFuture.completedFuture(request.conversationId());
        thread.thenCompose(threadId -> {
            activeTurns.put(handle, new ActiveTurn(threadId, "", sink));
            sink.accept(new TurnEvent.Started(handle, threadId, ""));
            return service.startTurn(
                threadId,
                request.input(),
                request.attachments(),
                request.fileReferences(),
                request.model(),
                request.effort(),
                request.serviceTier(),
                request.approvalPolicy(),
                request.sandboxMode()
            ).thenApply(result -> {
                var turn = result.getAsJsonObject("turn");
                var turnId = turn == null || !turn.has("id") ? "" : turn.get("id").getAsString();
                activeTurns.computeIfPresent(handle, (ignored, active) ->
                    new ActiveTurn(threadId, turnId, active.sink()));
                sink.accept(new TurnEvent.Started(handle, threadId, turnId));
                return result;
            });
        }).exceptionally(error -> {
            activeTurns.remove(handle);
            var cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
            sink.accept(new TurnEvent.Failed(handle, cause, cause instanceof java.util.concurrent.CancellationException));
            return null;
        });
        return handle;
    }

    @Override
    public boolean cancel(TurnHandle handle) {
        var turn = activeTurns.remove(handle);
        if (turn == null || turn.providerTurnId().isBlank()) return false;
        service.interruptTurn(turn.threadId(), turn.providerTurnId());
        return true;
    }

    public void complete(TurnHandle handle) { activeTurns.remove(handle); }

    @Override
    public void onConnectionChanged(boolean connected, String detail) {
        if (connected) return;
        var error = new IllegalStateException(detail == null || detail.isBlank() ? "Codex app-server 连接已断开" : detail);
        activeTurns.forEach((handle, active) -> active.sink().accept(new TurnEvent.Failed(handle, error, false)));
        activeTurns.clear();
    }

    @Override
    public void onNotification(String method, JsonObject params) {
        var active = findActive(params);
        if (active == null) return;
        var event = decodeNotification(active.getKey(), method, params);
        if (event == null) return;
        active.getValue().sink().accept(event);
        if (event instanceof TurnEvent.Started started && !started.providerTurnId().isBlank()) {
            activeTurns.computeIfPresent(active.getKey(), (ignored, current) ->
                new ActiveTurn(current.threadId(), started.providerTurnId(), current.sink()));
        }
        if (event instanceof TurnEvent.Completed || event instanceof TurnEvent.Failed) {
            activeTurns.remove(active.getKey());
        }
    }

    static TurnEvent decodeNotification(TurnHandle handle, String method, JsonObject params) {
        if (params == null) return null;
        return switch (method == null ? "" : method) {
            case "turn/started" -> {
                var turn = object(params, "turn");
                yield new TurnEvent.Started(handle, string(params, "threadId", string(turn, "threadId", "")), string(turn, "id", ""));
            }
            case "item/agentMessage/delta" -> delta(handle, TurnEvent.Delta.Kind.TEXT, params);
            case "item/plan/delta" -> delta(handle, TurnEvent.Delta.Kind.PLAN, params);
            case "item/commandExecution/outputDelta" -> delta(handle, TurnEvent.Delta.Kind.COMMAND, params);
            case "item/started" -> item(handle, TurnEvent.Item.Phase.STARTED, object(params, "item"));
            case "item/completed" -> item(handle, TurnEvent.Item.Phase.COMPLETED, object(params, "item"));
            case "item/fileChange/patchUpdated" -> item(handle, TurnEvent.Item.Phase.UPDATED, params);
            case "turn/diff/updated" -> new TurnEvent.Change(handle, string(params, "diff", ""));
            case "thread/tokenUsage/updated" -> usage(handle, params);
            case "turn/completed" -> new TurnEvent.Completed(handle, string(params, "threadId", ""), "", "");
            case "error" -> new TurnEvent.Failed(handle, new IllegalStateException(string(params, "message", params.toString())), false);
            default -> null;
        };
    }

    private Map.Entry<TurnHandle, ActiveTurn> findActive(JsonObject params) {
        var threadId = threadId(params);
        var turnId = turnId(params);
        for (var entry : activeTurns.entrySet()) {
            var active = entry.getValue();
            if (!threadId.isBlank() && !threadId.equals(active.threadId())) continue;
            if (!turnId.isBlank() && !active.providerTurnId().isBlank() && !turnId.equals(active.providerTurnId())) continue;
            if (threadId.isBlank() && turnId.isBlank()) continue;
            return entry;
        }
        return null;
    }

    private static TurnEvent.Delta delta(TurnHandle handle, TurnEvent.Delta.Kind kind, JsonObject params) {
        return new TurnEvent.Delta(handle, kind, string(params, "itemId", ""), string(params, "delta", ""));
    }

    private static TurnEvent.Item item(TurnHandle handle, TurnEvent.Item.Phase phase, JsonObject source) {
        if (source == null) return null;
        Map<String, Object> data = GSON.fromJson(source, ITEM_DATA_TYPE.getType());
        return new TurnEvent.Item(handle, phase, string(source, "id", string(source, "itemId", "")),
            string(source, "type", "fileChange"), data == null ? Map.of() : data);
    }

    private static TurnEvent.Usage usage(TurnHandle handle, JsonObject params) {
        var tokenUsage = object(params, "tokenUsage");
        var last = object(tokenUsage, "last");
        var modelContextWindow = number(tokenUsage, "modelContextWindow", number(params, "modelContextWindow", 0));
        var used = number(last, "totalTokens", number(tokenUsage, "totalTokens", 0));
        return new TurnEvent.Usage(handle, used, modelContextWindow);
    }

    private static String threadId(JsonObject params) {
        var direct = string(params, "threadId", "");
        if (!direct.isBlank()) return direct;
        var turn = object(params, "turn");
        direct = string(turn, "threadId", "");
        if (!direct.isBlank()) return direct;
        return string(object(params, "item"), "threadId", "");
    }

    private static String turnId(JsonObject params) {
        var direct = string(params, "turnId", "");
        if (!direct.isBlank()) return direct;
        var turn = object(params, "turn");
        direct = string(turn, "id", "");
        if (!direct.isBlank()) return direct;
        return string(object(params, "item"), "turnId", "");
    }

    private static JsonObject object(JsonObject source, String key) {
        if (source == null || !source.has(key) || !source.get(key).isJsonObject()) return null;
        return source.getAsJsonObject(key);
    }

    private static String string(JsonObject source, String key, String fallback) {
        if (source == null || !source.has(key) || source.get(key).isJsonNull()) return fallback;
        try {
            return source.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long number(JsonObject source, String key, long fallback) {
        if (source == null || !source.has(key) || source.get(key).isJsonNull()) return fallback;
        try {
            return source.get(key).getAsLong();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    @Override
    public ProviderHealth health() {
        return new ProviderHealth(
            service.isConnected() ? ProviderHealth.Status.READY : ProviderHealth.Status.UNAVAILABLE,
            service.lifecycleState().name()
        );
    }

    @Override public Set<ProviderCapability> capabilities() { return CAPABILITIES; }

    @Override
    public void close() {
        service.removeListener(this);
        activeTurns.clear();
    }
}
