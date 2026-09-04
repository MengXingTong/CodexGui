package com.codexgui.provider;

import com.codexgui.conversation.SessionId;
import com.codexgui.conversation.TurnHandle;
import com.codexgui.conversation.TurnId;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CodexConversationProviderTest {
    private final TurnHandle handle = new TurnHandle(new SessionId("session-a"), new TurnId("local-turn"), 3);

    @Test
    void decodesFixtureWithTheOriginalTurnHandle() throws IOException {
        var lines = resource("protocol/codex/turn-events.jsonl").lines().filter(line -> !line.isBlank()).toList();

        var started = assertInstanceOf(TurnEvent.Started.class, decode(lines.get(0)));
        assertEquals(handle, started.handle());
        assertEquals("thread-1", started.conversationId());
        assertEquals("turn-1", started.providerTurnId());

        var delta = assertInstanceOf(TurnEvent.Delta.class, decode(lines.get(1)));
        assertEquals(handle, delta.handle());
        assertEquals("message-1", delta.itemId());
        assertEquals("你好", delta.text());

        var completed = assertInstanceOf(TurnEvent.Completed.class, decode(lines.get(2)));
        assertEquals(handle, completed.handle());
    }

    @Test
    void normalizesItemPayloadAndKeepsItImmutable() {
        var params = JsonParser.parseString("""
            {"item":{"id":"cmd-1","type":"commandExecution","command":"gradlew test"}}
            """).getAsJsonObject();

        var event = assertInstanceOf(TurnEvent.Item.class,
            CodexConversationProvider.decodeNotification(handle, "item/started", params));

        assertEquals(TurnEvent.Item.Phase.STARTED, event.phase());
        assertEquals("commandExecution", event.kind());
        assertEquals("gradlew test", event.data().get("command"));
        assertThrows(UnsupportedOperationException.class, () -> event.data().put("status", "changed"));
    }

    @Test
    void readsUsageFromTheTokenUsageSnapshot() {
        var params = JsonParser.parseString("""
            {"tokenUsage":{"last":{"totalTokens":1250},"modelContextWindow":10000}}
            """).getAsJsonObject();

        var event = assertInstanceOf(TurnEvent.Usage.class,
            CodexConversationProvider.decodeNotification(handle, "thread/tokenUsage/updated", params));

        assertEquals(1250, event.usedTokens());
        assertEquals(10000, event.maxTokens());
    }

    private TurnEvent decode(String line) {
        var envelope = JsonParser.parseString(line).getAsJsonObject();
        return CodexConversationProvider.decodeNotification(
            handle, envelope.get("method").getAsString(), envelope.getAsJsonObject("params"));
    }

    private static String resource(String path) throws IOException {
        try (var input = CodexConversationProviderTest.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IOException("缺少测试资源：" + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
