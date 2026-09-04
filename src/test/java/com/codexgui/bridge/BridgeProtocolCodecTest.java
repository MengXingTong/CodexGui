package com.codexgui.bridge;

import com.codexgui.conversation.SessionId;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BridgeProtocolCodecTest {
    private final BridgeProtocolCodec codec = new BridgeProtocolCodec();

    @Test
    void decodesV1CommandAndPreservesUnicodeIdentity() throws IOException {
        var decoded = assertInstanceOf(
            BridgeProtocolCodec.Decoded.class,
            codec.decodeCommand(resource("protocol/bridge/v1-command.json"))
        );
        var command = decoded.command();

        assertEquals(BridgeCommand.Type.SEND, command.type());
        assertEquals("web-7", command.requestId());
        assertEquals("会话-A", command.sessionId().value());
        assertEquals("turn-3", command.turnId());
        assertEquals(3, command.generation());
        assertTrue(command.payload().get("text").getAsString().contains("角色.cpp"));
        assertFalse(command.legacy());
    }

    @Test
    void encodesEveryRequiredEventIdentityField() {
        var payload = new JsonObject();
        payload.addProperty("delta", "你好");
        var encoded = codec.encodeEvent(new BridgeEvent.V1(
            BridgeEvent.Type.APPEND_MESSAGE,
            "event-1",
            SessionId.of("session-1"),
            "turn-1",
            8,
            payload
        ));
        var envelope = JsonParser.parseString(encoded).getAsJsonObject();

        assertEquals(1, envelope.get("v").getAsInt());
        assertEquals("appendMessage", envelope.get("type").getAsString());
        assertEquals("event-1", envelope.get("requestId").getAsString());
        assertEquals("session-1", envelope.get("sessionId").getAsString());
        assertEquals("turn-1", envelope.get("turnId").getAsString());
        assertEquals(8, envelope.get("generation").getAsLong());
        assertEquals("你好", envelope.getAsJsonObject("payload").get("delta").getAsString());
    }

    @Test
    void rejectsUnknownVersionTypeAndMissingIdentityWithProtocolError() {
        assertProtocolError("unsupported_version", """
            {"v":2,"type":"send","requestId":"1","sessionId":"s","turnId":"","generation":0,"payload":{}}
            """);
        assertProtocolError("unknown_type", """
            {"v":1,"type":"unknown.command","requestId":"2","sessionId":"s","turnId":"","generation":0,"payload":{}}
            """);
        assertProtocolError("missing_identity", """
            {"v":1,"type":"send","requestId":"3","sessionId":"s","generation":0,"payload":{}}
            """);
        assertProtocolError("malformed_json", "not-json");
    }

    @Test
    void legacyCommandOnlyEntersThroughCompatibilityAdapter() {
        var decoded = assertInstanceOf(BridgeProtocolCodec.Decoded.class, codec.decodeCommand(
            "{\"action\":\"send\",\"sessionId\":\"legacy-session\",\"text\":\"hello\"}"));

        assertEquals(BridgeCommand.Type.SEND, decoded.command().type());
        assertEquals("legacy-session", decoded.command().sessionId().value());
        assertEquals("hello", decoded.command().payload().get("text").getAsString());
        assertTrue(decoded.command().legacy());
        assertProtocolError("missing_version", "{\"type\":\"send\",\"payload\":{}}");
    }

    private void assertProtocolError(String code, String json) {
        var rejected = assertInstanceOf(BridgeProtocolCodec.Rejected.class, codec.decodeCommand(json));
        assertEquals(BridgeEvent.Type.PROTOCOL_ERROR, rejected.error().type());
        assertEquals(code, rejected.error().payload().get("code").getAsString());
        assertFalse(rejected.error().requestId().isBlank());
        assertFalse(rejected.error().sessionId().value().isBlank());
    }

    private static String resource(String path) throws IOException {
        try (var input = BridgeProtocolCodecTest.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IOException("缺少协议 fixture：" + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
