package com.codexgui.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BridgeSchemaContractTest {
    @Test
    void schemaJavaAndTypeScriptExposeTheSameDiscriminators() throws IOException {
        var schema = JsonParser.parseString(resource("protocol/bridge-v1.schema.json")).getAsJsonObject();
        var schemaCommands = strings(schema, "x-commandTypes");
        var schemaEvents = strings(schema, "x-eventTypes");
        var javaCommands = new LinkedHashSet<String>();
        var javaEvents = new LinkedHashSet<String>();
        for (var type : BridgeCommand.Type.values()) javaCommands.add(type.wireName());
        for (var type : BridgeEvent.Type.values()) javaEvents.add(type.wireName());

        assertEquals(javaCommands, schemaCommands);
        assertEquals(javaEvents, schemaEvents);

        var typeScript = Files.readString(Path.of("src/main/ts/protocol.ts"), StandardCharsets.UTF_8);
        assertEquals(schemaCommands, typeScriptTypes(typeScript, "BRIDGE_COMMAND_TYPES"));
        assertEquals(schemaEvents, typeScriptTypes(typeScript, "BRIDGE_EVENT_TYPES"));
        assertTrue(typeScript.contains("[T in BridgeCommandType]: BridgeEnvelope<T"));
        assertTrue(typeScript.contains("[T in BridgeEventType]: BridgeEnvelope<T"));

        var javaScript = Files.readString(Path.of("build/generated-resources/web/app.js"), StandardCharsets.UTF_8);
        assertEquals(schemaEvents, javaScriptEventTypes(javaScript));
        assertTrue(Pattern.compile(
            "JSON\\.stringify\\(\\{\\s*v:\\s*BRIDGE_VERSION,\\s*type,\\s*requestId,\\s*sessionId,\\s*turnId:",
            Pattern.DOTALL
        ).matcher(javaScript).find());
        assertEquals(1, javaScript.split("window\\.CodexGui\\s*=", -1).length - 1);
    }

    @Test
    void fixturesAndPreviewUseTheV1Envelope() throws IOException {
        var schema = JsonParser.parseString(resource("protocol/bridge-v1.schema.json")).getAsJsonObject();
        validateFixture(schema, "protocol/bridge/v1-command.json");
        validateFixture(schema, "protocol/bridge/v1-event.json");

        var preview = Files.readString(Path.of("tools/ui-preview.html"), StandardCharsets.UTF_8);
        assertTrue(preview.contains("const previewEnvelope ="));
        assertTrue(preview.contains("{v:1,type,requestId:"));
        assertTrue(preview.contains("envelope?.v === 1"));
    }

    private void validateFixture(JsonObject schema, String resourcePath) throws IOException {
        var fixture = JsonParser.parseString(resource(resourcePath)).getAsJsonObject();
        for (var required : schema.getAsJsonArray("required")) assertTrue(fixture.has(required.getAsString()));
        assertEquals(1, fixture.get("v").getAsInt());
        assertTrue(fixture.get("requestId").isJsonPrimitive());
        assertTrue(fixture.get("sessionId").isJsonPrimitive());
        assertTrue(fixture.get("turnId").isJsonPrimitive());
        assertTrue(fixture.get("generation").getAsLong() >= 0);
        assertTrue(fixture.get("payload").isJsonObject());
        var allowed = strings(schema.getAsJsonObject("properties").getAsJsonObject("type"), "enum");
        assertTrue(allowed.contains(fixture.get("type").getAsString()));
    }

    private Set<String> typeScriptTypes(String source, String constant) {
        var block = Pattern.compile(constant + "\\s*=\\s*\\[(.*?)]\\s*as const", Pattern.DOTALL).matcher(source);
        assertTrue(block.find(), "TypeScript 中缺少 " + constant);
        var values = new LinkedHashSet<String>();
        var quoted = Pattern.compile("'([^']+)'").matcher(block.group(1));
        while (quoted.find()) values.add(quoted.group(1));
        return values;
    }

    private Set<String> javaScriptEventTypes(String source) {
        var block = Pattern.compile("BRIDGE_EVENT_TYPES\\s*=\\s*\\[(.*?)]", Pattern.DOTALL).matcher(source);
        assertTrue(block.find(), "JavaScript 中缺少 BRIDGE_EVENT_TYPES");
        var values = new LinkedHashSet<String>();
        var quoted = Pattern.compile("[\\\"']([^\\\"']+)[\\\"']").matcher(block.group(1));
        while (quoted.find()) values.add(quoted.group(1));
        return values;
    }

    private LinkedHashSet<String> strings(JsonObject object, String key) {
        var values = new LinkedHashSet<String>();
        for (var value : object.getAsJsonArray(key)) values.add(value.getAsString());
        return values;
    }

    private static String resource(String path) throws IOException {
        try (var input = BridgeSchemaContractTest.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IOException("缺少协议资源：" + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
