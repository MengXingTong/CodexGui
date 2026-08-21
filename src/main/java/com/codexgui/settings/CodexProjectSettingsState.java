package com.codexgui.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

@State(name = "CodexGuiProjectSettings", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class CodexProjectSettingsState implements PersistentStateComponent<CodexProjectSettingsState.StateData> {
    public static final class StateData {
        public String projectInstructions = "";
    }

    private StateData state = new StateData();

    public static CodexProjectSettingsState getInstance(Project project) {
        return project.getService(CodexProjectSettingsState.class);
    }

    @Override
    public @NotNull StateData getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull StateData state) {
        this.state = state;
    }
}
