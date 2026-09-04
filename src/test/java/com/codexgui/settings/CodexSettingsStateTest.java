package com.codexgui.settings;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexSettingsStateTest {
    @Test
    void restoresBuiltInProvidersWhenMigratingOldSettings() {
        var component = new CodexSettingsState();
        var state = new CodexSettingsState.StateData();
        state.providers = new ArrayList<>();
        state.activeCodexProviderId = "missing";
        state.activeClaudeProviderId = "missing";

        component.loadState(state);

        assertNotNull(component.provider(CodexSettingsState.CODEX_LOCAL_PROVIDER_ID));
        assertNotNull(component.provider(CodexSettingsState.CLAUDE_LOCAL_PROVIDER_ID));
        assertEquals(CodexSettingsState.CODEX_LOCAL_PROVIDER_ID, component.activeProviderId(CodexSettingsState.CODEX_CHANNEL));
        assertEquals(CodexSettingsState.CLAUDE_LOCAL_PROVIDER_ID, component.activeProviderId(CodexSettingsState.CLAUDE_CHANNEL));
        assertTrue(component.activeProvider(CodexSettingsState.CODEX_CHANNEL).builtIn);
        assertEquals(CodexSettingsState.CURRENT_SCHEMA_VERSION, state.schemaVersion);
    }

    @Test
    void migratesRestrictedValuesOnlyWhenLoadingAnOldSchema() {
        var component = new CodexSettingsState();
        var state = new CodexSettingsState.StateData();
        state.schemaVersion = 0;
        state.reasoningEffort = "unsupported";
        state.serviceTier = "turbo";
        state.approvalPolicy = "always";
        state.sandboxMode = "unknown";
        state.sendShortcut = "space";

        component.loadState(state);

        assertEquals("high", state.reasoningEffort);
        assertEquals("standard", state.serviceTier);
        assertEquals("on-request", state.approvalPolicy);
        assertEquals("workspace-write", state.sandboxMode);
        assertEquals("enter", state.sendShortcut);
        assertEquals(CodexSettingsState.CURRENT_SCHEMA_VERSION, state.schemaVersion);
    }

    @Test
    void conversationSnapshotDoesNotChangeWithThePersistentBean() {
        var component = new CodexSettingsState();
        var state = component.getState();
        var provider = component.activeProvider(CodexSettingsState.CODEX_CHANNEL);
        state.model = "model-a";
        provider.baseUrl = "https://provider-a.example";

        var snapshot = component.snapshot(CodexSettingsState.CODEX_CHANNEL);
        state.model = "model-b";
        provider.baseUrl = "https://provider-b.example";

        assertEquals("model-a", snapshot.model());
        assertEquals("https://provider-a.example", snapshot.provider().baseUrl());
        assertNotEquals(state.model, snapshot.model());
    }
}
