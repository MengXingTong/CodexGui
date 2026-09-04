package com.codexgui.provider;

import com.codexgui.conversation.TurnHandle;
import com.codexgui.service.ClaudeCodeService;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

public final class ClaudeConversationProvider implements ConversationProvider {
    private static final Gson GSON = new Gson();
    private static final TypeToken<Map<String, Object>> TOOL_INPUT_TYPE = new TypeToken<>() {};

    private final ClaudeCodeService service;

    public ClaudeConversationProvider(ClaudeCodeService service) {
        this.service = service;
    }

    @Override public String id() { return "claude"; }

    @Override
    public TurnHandle startTurn(TurnRequest request, TurnEventSink sink) {
        var handle = request.handle();
        service.startTurn(
            handle,
            request.executable(),
            request.conversationId(),
            request.input(),
            request.model(),
            request.effort(),
            request.approvalPolicy(),
            request.instructions(),
            request.providerProfile(),
            new ClaudeCodeService.Listener() {
                @Override public void onModel(String model) {
                    sink.accept(new TurnEvent.ModelSelected(handle, model));
                }

                @Override public void onTextDelta(String delta) {
                    sink.accept(new TurnEvent.Delta(handle, TurnEvent.Delta.Kind.TEXT, delta));
                }

                @Override public void onThinkingDelta(String delta) {
                    sink.accept(new TurnEvent.Delta(handle, TurnEvent.Delta.Kind.THINKING, delta));
                }

                @Override public void onTool(String id, String name, com.google.gson.JsonObject input) {
                    Map<String, Object> values = GSON.fromJson(input, TOOL_INPUT_TYPE.getType());
                    sink.accept(new TurnEvent.Tool(handle, id, name, values == null ? Map.of() : values));
                }
            }
        ).whenComplete((result, error) -> {
            // 完成与取消统一转换为 TurnEvent，调用方不再理解 Claude 的 Future 语义。
            if (error == null) {
                sink.accept(new TurnEvent.Completed(
                    handle, result.sessionId(), result.model(), result.finalText()));
                return;
            }
            var cause = unwrap(error);
            sink.accept(new TurnEvent.Failed(handle, cause, cause instanceof CancellationException));
        });
        return handle;
    }

    @Override public boolean cancel(TurnHandle handle) { return service.interrupt(handle); }

    @Override
    public ProviderHealth health() {
        return new ProviderHealth(ProviderHealth.Status.READY, "Claude Code 由项目配置决定可用性");
    }

    @Override public Set<ProviderCapability> capabilities() { return Set.of(); }

    private Throwable unwrap(Throwable error) {
        return error instanceof java.util.concurrent.CompletionException && error.getCause() != null
            ? error.getCause() : error;
    }
}
