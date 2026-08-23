package com.codexgui.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

@State(name = "CodexGuiSettings", storages = @Storage("codex-gui.xml"))
public final class CodexSettingsState implements PersistentStateComponent<CodexSettingsState.StateData> {
    public static final class StateData {
        public String codexExecutable = "codex";
        public String model = "";
        public String reasoningEffort = "high";
        public String serviceTier = "standard";
        public String approvalPolicy = "on-request";
        public String sandboxMode = "workspace-write";
        public boolean streamResponses = true;
        public boolean showReasoning = true;
        public String sendShortcut = "enter";
        public int permissionDialogTimeoutSeconds = 300;
        public boolean sendOpenedFilePath = false;
        public boolean diffExpandedByDefault = false;
        public boolean newSessionConfirmEnabled = true;
        public boolean askUserQuestionNotificationEnabled = false;
        public boolean askUserQuestionSoundEnabled = false;
        public boolean taskCompletionNotificationEnabled = false;
        public boolean taskCompletionSoundEnabled = false;
        public boolean systemNotificationOnlyWhenUnfocused = false;
        public boolean soundOnlyWhenUnfocused = false;
        public String notificationSound = "default";
        public String customSoundPath = "";
        public boolean captureIgnoredFiles = true;
        public String globalInstructions = "";
        public String activePromptId = "";
        public String activeAgentId = "";
        public List<PromptPreset> prompts = new ArrayList<>(List.of(
            new PromptPreset("program", "程序", "负责把方案转化为可直接使用、可编译、可维护的代码。", "将方案转化为可直接使用、可编译、可维护的代码。优先遵循现有项目结构，避免过度封装和无意义拆分。"),
            new PromptPreset("architect", "架构师", "从工程结构、模块边界和长期维护成本审视方案。", "从工程结构、模块边界、数据流和长期维护成本审视任务。先识别约束与风险，再给出可以逐步落地的实现。"),
            new PromptPreset("planner", "策划", "从用户体验、规则和完整工作流角度完善需求。", "从用户体验、功能规则、边界状态和完整工作流角度分析需求，确保方案明确、可验证并覆盖关键异常情况。")
        ));
        public List<AgentProfile> agents = new ArrayList<>();
        public List<String> favoriteThreads = new ArrayList<>();
    }

    public static final class PromptPreset {
        public String id = "";
        public String name = "";
        public String description = "";
        public String instructions = "";

        public PromptPreset() {
        }

        public PromptPreset(String id, String name, String description, String instructions) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.instructions = instructions;
        }
    }

    public static final class AgentProfile {
        public String id = "";
        public String name = "";
        public String instructions = "";

        public AgentProfile() {
        }

        public AgentProfile(String id, String name, String instructions) {
            this.id = id;
            this.name = name;
            this.instructions = instructions;
        }
    }

    private StateData state = new StateData();

    public static CodexSettingsState getInstance() {
        return ApplicationManager.getApplication().getService(CodexSettingsState.class);
    }

    @Override
    public @NotNull StateData getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull StateData state) {
        this.state = state;
    }

    public boolean isFavorite(String threadId) {
        return state.favoriteThreads.contains(threadId);
    }

    public void setFavorite(String threadId, boolean favorite) {
        if (favorite && !state.favoriteThreads.contains(threadId)) state.favoriteThreads.add(threadId);
        if (!favorite) state.favoriteThreads.remove(threadId);
    }
}
