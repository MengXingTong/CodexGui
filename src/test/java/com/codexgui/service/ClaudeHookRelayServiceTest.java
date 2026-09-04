package com.codexgui.service;

import com.codexgui.conversation.SessionId;
import com.codexgui.conversation.TurnHandle;
import com.codexgui.conversation.TurnId;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClaudeHookRelayServiceTest {
    @TempDir
    Path root;

    private ClaudeHookRelayService relay;

    @AfterEach
    void stopRelay() {
        if (relay != null) relay.dispose();
    }

    @Test
    void capturesBaselineBeforeAllowingFileTool() throws Exception {
        var path = root.resolve("source.txt");
        Files.writeString(path, "before\n", StandardCharsets.UTF_8);
        var tracker = new ConversationChangeTracker(root);
        relay = new ClaudeHookRelayService(tracker);
        var registration = relay.register(handle("session", "turn", 1));

        var response = post(registration.endpoint(), payload("Write", "file_path", path.toString()));
        assertEquals(204, response.statusCode());
        Files.writeString(path, "after\n", StandardCharsets.UTF_8);

        var details = tracker.readDetails("session", path);
        assertArrayEquals("before\n".getBytes(StandardCharsets.UTF_8), details.beforeContent());
        assertArrayEquals("after\n".getBytes(StandardCharsets.UTF_8), details.afterContent());
    }

    @Test
    void rejectsMissingMalformedAndOutsidePathsWithoutMembership() throws Exception {
        var tracker = new ConversationChangeTracker(root);
        relay = new ClaudeHookRelayService(tracker);
        var registration = relay.register(handle("session", "turn", 1));

        assertEquals(422, post(registration.endpoint(), "{not-json").statusCode());
        assertEquals(422, post(registration.endpoint(), payload("Edit", "file_path", null)).statusCode());
        assertEquals(422, post(registration.endpoint(), payload("Write", "file_path", "bad\u0000path")).statusCode());
        assertEquals(422, post(registration.endpoint(), payload(
            "Write", "file_path", root.resolveSibling("outside.txt").toString())).statusCode());
        assertEquals(422, post(registration.endpoint(), payload("Bash", "file_path", "ignored.txt")).statusCode());

        assertTrue(tracker.listSummaries("session").isEmpty());
    }

    @Test
    void rejectsOldGenerationAndAllowsNotebookPathForCurrentTurn() throws Exception {
        var tracker = new ConversationChangeTracker(root);
        relay = new ClaudeHookRelayService(tracker);
        var oldRegistration = relay.register(handle("session", "old-turn", 1));
        var currentRegistration = relay.register(handle("session", "current-turn", 2));

        assertEquals(409, post(oldRegistration.endpoint(), payload(
            "Write", "file_path", root.resolve("stale.txt").toString())).statusCode());
        assertFalse(tracker.isTracked("session", root.resolve("stale.txt")));
        assertEquals(204, post(currentRegistration.endpoint(), payload(
            "NotebookEdit", "notebook_path", root.resolve("notes.ipynb").toString())).statusCode());
        assertTrue(tracker.isTracked("session", root.resolve("notes.ipynb")));
    }

    @Test
    void emitsClaudePreToolUseSettingsForLoopbackRelay() {
        relay = new ClaudeHookRelayService(new ConversationChangeTracker(root));
        var registration = relay.register(handle("session", "turn", 1));
        var settings = JsonParser.parseString(registration.settingsJson()).getAsJsonObject();
        var matcher = settings.getAsJsonObject("hooks").getAsJsonArray("PreToolUse").get(0).getAsJsonObject();
        var commandHook = matcher.getAsJsonArray("hooks").get(0).getAsJsonObject();

        assertEquals("127.0.0.1", registration.endpoint().getHost());
        assertEquals("Write|Edit|NotebookEdit", matcher.get("matcher").getAsString());
        assertEquals("command", commandHook.get("type").getAsString());
        assertEquals(20, commandHook.get("timeout").getAsInt());
        assertTrue(commandHook.get("command").getAsString().contains(registration.endpoint().toString()));
    }

    private HttpResponse<String> post(URI endpoint, String body) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(endpoint)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String payload(String tool, String pathKey, String path) {
        var input = new JsonObject();
        if (path != null) input.addProperty(pathKey, path);
        var payload = new JsonObject();
        payload.addProperty("hook_event_name", "PreToolUse");
        payload.addProperty("tool_name", tool);
        payload.add("tool_input", input);
        return payload.toString();
    }

    private TurnHandle handle(String sessionId, String turnId, long generation) {
        return new TurnHandle(SessionId.of(sessionId), new TurnId(turnId), generation);
    }
}
