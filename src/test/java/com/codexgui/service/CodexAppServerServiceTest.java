package com.codexgui.service;

import com.codexgui.settings.CodexSettingsState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void lifecycleRejectsOldProcessGenerationAcrossRestartAndDispose() {
        var lifecycle = new CodexAppServerService.Lifecycle();

        var firstGeneration = lifecycle.beginStart();
        assertEquals(CodexAppServerService.LifecycleState.STARTING, lifecycle.state());
        assertTrue(lifecycle.transition(firstGeneration, CodexAppServerService.LifecycleState.READY));

        lifecycle.beginStop();
        assertEquals(CodexAppServerService.LifecycleState.STOPPING, lifecycle.state());
        assertTrue(lifecycle.transition(firstGeneration, CodexAppServerService.LifecycleState.STOPPED));
        var secondGeneration = lifecycle.beginStart();

        assertFalse(lifecycle.transition(firstGeneration, CodexAppServerService.LifecycleState.READY));
        assertTrue(lifecycle.transition(secondGeneration, CodexAppServerService.LifecycleState.FAILED));
        lifecycle.dispose();
        assertFalse(lifecycle.transition(secondGeneration, CodexAppServerService.LifecycleState.READY));
        assertEquals(CodexAppServerService.LifecycleState.DISPOSED, lifecycle.state());
    }

    @Test
    void appliesDedicatedRpcTimeouts() {
        assertEquals(60, CodexAppServerService.timeoutSeconds("thread/list"));
        assertEquals(300, CodexAppServerService.timeoutSeconds("mcpServer/oauth/login"));
        assertEquals(20, CodexAppServerService.INITIALIZE_TIMEOUT_SECONDS);
        assertEquals(2, CodexAppServerService.STOP_TIMEOUT_SECONDS);
    }
}
