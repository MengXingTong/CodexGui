package com.codexgui.bridge;

import com.codexgui.conversation.SessionId;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Objects;
import java.util.UUID;

public final class BridgeProtocolCodec {
    public static final int VERSION = 1;

    public sealed interface DecodeResult permits Decoded, Rejected {}
    public record Decoded(BridgeCommand command) implements DecodeResult {}
    public record Rejected(BridgeEvent error) implements DecodeResult {}

    private final Gson gson = new Gson();
    private final LegacyBridgeAdapter legacyAdapter = new LegacyBridgeAdapter();

    public DecodeResult decodeCommand(String json) {
        JsonObject envelope;
        try {
            var parsed = JsonParser.parseString(Objects.requireNonNullElse(json, ""));
            if (!parsed.isJsonObject()) return rejected("malformed_json", "Bridge 消息必须是 JSON 对象", null);
            envelope = parsed.getAsJsonObject();
        } catch (RuntimeException error) {
            return rejected("malformed_json", "Bridge 消息不是有效 JSON", null);
        }

        // 旧协议只能从此入口适配，进入业务分发前统一转换为 v1 command。
        if (!envelope.has("v")) {
            var adapted = legacyAdapter.adapt(envelope);
            return adapted == null
                ? rejected("missing_version", "Bridge 消息缺少协议版本", envelope)
                : new Decoded(adapted);
        }
        return decodeV1(envelope);
    }

    public String encodeEvent(BridgeEvent event) {
        var envelope = new JsonObject();
        envelope.addProperty("v", event.version());
        envelope.addProperty("type", event.type().wireName());
        envelope.addProperty("requestId", event.requestId());
        envelope.addProperty("sessionId", event.sessionId().value());
        envelope.addProperty("turnId", event.turnId());
        envelope.addProperty("generation", event.generation());
        envelope.add("payload", event.payload().deepCopy());
        return gson.toJson(envelope);
    }

    private DecodeResult decodeV1(JsonObject envelope) {
        if (!number(envelope, "v") || integer(envelope, "v", -1) != VERSION) {
            return rejected("unsupported_version", "不支持的 Bridge 协议版本", envelope);
        }
        if (!stringField(envelope, "type") || !stringField(envelope, "requestId")
            || !stringField(envelope, "sessionId") || !stringFieldAllowEmpty(envelope, "turnId")
            || !wholeNumber(envelope, "generation") || !objectField(envelope, "payload")) {
            return rejected("missing_identity", "Bridge envelope 缺少有效身份字段或 payload", envelope);
        }

        var type = BridgeCommand.Type.fromWireName(envelope.get("type").getAsString());
        if (type == null) return rejected("unknown_type", "未知 Bridge command 类型", envelope);
        var generation = envelope.get("generation").getAsLong();
        if (generation < 0) return rejected("missing_identity", "Bridge generation 不能为负数", envelope);
        return new Decoded(new BridgeCommand.V1(
            type,
            envelope.get("requestId").getAsString(),
            SessionId.of(envelope.get("sessionId").getAsString()),
            envelope.get("turnId").getAsString(),
            generation,
            envelope.getAsJsonObject("payload").deepCopy(),
            false
        ));
    }

    private Rejected rejected(String code, String message, JsonObject source) {
        var requestId = validString(source, "requestId", "protocol-" + UUID.randomUUID());
        var sessionId = SessionId.of(validString(source, "sessionId", "default"));
        var turnId = validStringAllowEmpty(source, "turnId", "");
        var generation = wholeNumber(source, "generation") ? Math.max(0, source.get("generation").getAsLong()) : 0;
        var payload = new JsonObject();
        payload.addProperty("code", code);
        payload.addProperty("message", message);
        if (source != null && source.has("type") && source.get("type").isJsonPrimitive()) {
            payload.addProperty("receivedType", source.get("type").getAsString());
        }
        return new Rejected(new BridgeEvent.V1(
            BridgeEvent.Type.PROTOCOL_ERROR, requestId, sessionId, turnId, generation, payload));
    }

    private boolean stringField(JsonObject object, String key) {
        return stringFieldAllowEmpty(object, key) && !object.get(key).getAsString().isBlank();
    }

    private boolean stringFieldAllowEmpty(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
            && object.getAsJsonPrimitive(key).isString();
    }

    private boolean number(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
            && object.getAsJsonPrimitive(key).isNumber();
    }

    private boolean wholeNumber(JsonObject object, String key) {
        if (!number(object, key)) return false;
        try {
            return object.getAsJsonPrimitive(key).getAsBigDecimal().stripTrailingZeros().scale() <= 0;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private boolean objectField(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject();
    }

    private long integer(JsonObject object, String key, long fallback) {
        if (!wholeNumber(object, key)) return fallback;
        try {
            return object.get(key).getAsLong();
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private String validString(JsonObject object, String key, String fallback) {
        if (!stringField(object, key)) return fallback;
        return object.get(key).getAsString();
    }

    private String validStringAllowEmpty(JsonObject object, String key, String fallback) {
        if (!stringFieldAllowEmpty(object, key)) return fallback;
        return object.get(key).getAsString();
    }
}
