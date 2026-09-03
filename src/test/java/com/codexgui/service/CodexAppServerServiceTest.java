package com.codexgui.service;

import com.codexgui.settings.CodexSettingsState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexAppServerServiceTest {
    @Test
    void addsCustomProviderOverridesBeforeAppServerCommand() {
        var provider = new CodexSettingsState.ProviderProfile("open-router", "codex", "Open \"Router\"", false);
        provider.baseUrl = "https://openrouter.ai/api/v1";
        provider.wireApi = "responses";

        var command = CodexAppServerService.createCommand("codex.exe", provider);

        assertTrue(command.contains("model_provider=\"codex_gui\""));
        assertTrue(command.contains("model_providers.codex_gui.name=\"Open \\\"Router\\\"\""));
        assertTrue(command.contains("model_providers.codex_gui.base_url=\"https://openrouter.ai/api/v1\""));
        assertTrue(command.contains("model_providers.codex_gui.env_key=\"CODEX_GUI_PROVIDER_KEY\""));
        assertTrue(command.contains("model_providers.codex_gui.wire_api=\"responses\""));
        assertTrue(command.indexOf("model_provider=\"codex_gui\"") < command.indexOf("app-server"));
    }

    @Test
    void keepsLocalProviderCommandFreeOfOverrides() {
        var provider = new CodexSettingsState.ProviderProfile("codex-local", "codex", "Codex 本地配置", true);

        var command = CodexAppServerService.createCommand("codex.exe", provider);

        assertFalse(command.stream().anyMatch(value -> value.startsWith("model_provider=")));
        assertTrue(command.contains("app-server"));
        assertTrue(command.contains("--stdio"));
    }
}
