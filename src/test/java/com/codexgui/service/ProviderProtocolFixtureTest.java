package com.codexgui.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProviderProtocolFixtureTest {
    @Test
    void codexFixturesKeepThreadAndTurnIdentity() throws IOException {
        var events = jsonLines("protocol/codex/turn-events.jsonl");

        assertEquals("thread-1", events.getFirst().getAsJsonObject("params").get("threadId").getAsString());
        assertEquals("turn-1", events.getFirst().getAsJsonObject("params").getAsJsonObject("turn").get("id").getAsString());
        assertEquals("turn/completed", events.getLast().get("method").getAsString());
    }

    @Test
    void claudeFixturesCoverInitDeltaToolAndResult() throws IOException {
        var events = jsonLines("protocol/claude/turn-events.jsonl");

        assertEquals(List.of("system", "stream_event", "assistant", "result"),
            events.stream().map(event -> event.get("type").getAsString()).toList());
        var tool = events.get(2).getAsJsonObject("message").getAsJsonArray("content").get(0).getAsJsonObject();
        assertEquals("Edit", tool.get("name").getAsString());
        assertTrue(tool.getAsJsonObject("input").get("file_path").getAsString().endsWith("README.md"));
    }

    @Test
    void bridgeInventoryContainsUniqueCommandsAndEvents() throws IOException {
        var inventory = JsonParser.parseString(resource("protocol/bridge/legacy-inventory.json")).getAsJsonObject();
        var commands = inventory.getAsJsonArray("commands").asList();
        var events = inventory.getAsJsonArray("events").asList();

        assertFalse(commands.isEmpty());
        assertFalse(events.isEmpty());
        assertEquals(commands.size(), new HashSet<>(commands).size());
        assertEquals(events.size(), new HashSet<>(events).size());
    }

    private static List<JsonObject> jsonLines(String path) throws IOException {
        return resource(path).lines()
            .filter(line -> !line.isBlank())
            .map(line -> JsonParser.parseString(line).getAsJsonObject())
            .toList();
    }

    private static String resource(String path) throws IOException {
        try (var input = ProviderProtocolFixtureTest.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IOException("缺少协议 fixture：" + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
