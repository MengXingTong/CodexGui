package com.codexgui.settings;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    }
}
