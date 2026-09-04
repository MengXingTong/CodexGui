package com.codexgui.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@State(name = "CodexGuiSettings", storages = @Storage("codex-gui.xml"))
public final class CodexSettingsState implements PersistentStateComponent<CodexSettingsState.StateData> {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String CODEX_CHANNEL = "codex";
    public static final String CLAUDE_CHANNEL = "claude";
    public static final String CODEX_LOCAL_PROVIDER_ID = "codex-local";
    public static final String CLAUDE_LOCAL_PROVIDER_ID = "claude-local";

    public enum ProviderChannel {
        CODEX(CODEX_CHANNEL), CLAUDE(CLAUDE_CHANNEL);

        private final String value;
        ProviderChannel(String value) { this.value = value; }
        public String value() { return value; }
        public static ProviderChannel from(String value) { return CLAUDE_CHANNEL.equals(value) ? CLAUDE : CODEX; }
    }

    public enum ReasoningEffort {
        MINIMAL("minimal"), LOW("low"), MEDIUM("medium"), HIGH("high"), XHIGH("xhigh"), ULTRA("ultra");

        private final String value;
        ReasoningEffort(String value) { this.value = value; }
        public String value() { return value; }
        public static ReasoningEffort from(String value) {
            for (var item : values()) if (item.value.equals(value)) return item;
            return HIGH;
        }
    }

    public enum ServiceTier {
        STANDARD("standard"), FAST("fast");

        private final String value;
        ServiceTier(String value) { this.value = value; }
        public String value() { return value; }
        public static ServiceTier from(String value) { return FAST.value.equals(value) ? FAST : STANDARD; }
    }

    public enum ApprovalPolicy {
        UNTRUSTED("untrusted"), ON_REQUEST("on-request"), NEVER("never");

        private final String value;
        ApprovalPolicy(String value) { this.value = value; }
        public String value() { return value; }
        public static ApprovalPolicy from(String value) {
            for (var item : values()) if (item.value.equals(value)) return item;
            return ON_REQUEST;
        }
    }

    public enum SandboxMode {
        READ_ONLY("read-only"), WORKSPACE_WRITE("workspace-write"), DANGER_FULL_ACCESS("danger-full-access");

        private final String value;
        SandboxMode(String value) { this.value = value; }
        public String value() { return value; }
        public static SandboxMode from(String value) {
            for (var item : values()) if (item.value.equals(value)) return item;
            return WORKSPACE_WRITE;
        }
    }

    public enum SendShortcut {
        ENTER("enter"), COMMAND_ENTER("cmdEnter");

        private final String value;
        SendShortcut(String value) { this.value = value; }
        public String value() { return value; }
        public static SendShortcut from(String value) { return COMMAND_ENTER.value.equals(value) ? COMMAND_ENTER : ENTER; }
    }

    public static final class StateData {
        public int schemaVersion;
        public String activeProvider = "codex";
        public String activeCodexProviderId = CODEX_LOCAL_PROVIDER_ID;
        public String activeClaudeProviderId = CLAUDE_LOCAL_PROVIDER_ID;
        public String codexExecutable = "codex";
        public String claudeExecutable = "claude";
        public String model = "";
        public String claudeModel = "";
        public String reasoningEffort = "high";
        public String serviceTier = "standard";
        public String approvalPolicy = "on-request";
        public String sandboxMode = "workspace-write";
        public boolean streamResponses = true;
        public boolean showThinking = true;
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
        public String globalInstructions = "";
        public String projectInstructions = "";
        public String activePromptId = "";
        public String activeAgentId = "";
        public List<PromptPreset> prompts = new ArrayList<>(List.of(
            new PromptPreset("program", "程序", "负责把方案转化为可直接使用、可编译、可维护的代码。", "将方案转化为可直接使用、可编译、可维护的代码。优先遵循现有项目结构，避免过度封装和无意义拆分。"),
            new PromptPreset("architect", "架构师", "从工程结构、模块边界和长期维护成本审视方案。", "从工程结构、模块边界、数据流和长期维护成本审视任务。先识别约束与风险，再给出可以逐步落地的实现。"),
            new PromptPreset("planner", "策划", "从用户体验、规则和完整工作流角度完善需求。", "从用户体验、功能规则、边界状态和完整工作流角度分析需求，确保方案明确、可验证并覆盖关键异常情况。")
        ));
        public List<AgentProfile> agents = new ArrayList<>();
        public List<ProviderProfile> providers = defaultProviders();
        public List<String> favoriteThreads = new ArrayList<>();
    }

    public static final class ProviderProfile {
        public String id = "";
        public String channel = CODEX_CHANNEL;
        public String name = "";
        public String baseUrl = "";
        public String model = "";
        public String wireApi = "responses";
        public String claudeAuthType = "auth-token";
        public boolean builtIn;
        public int revision = 1;

        public ProviderProfile() {
        }

        public ProviderProfile(String id, String channel, String name, boolean builtIn) {
            this.id = id;
            this.channel = channel;
            this.name = name;
            this.builtIn = builtIn;
        }

        public ProviderProfileSnapshot snapshot() {
            return new ProviderProfileSnapshot(
                id, ProviderChannel.from(channel), name, baseUrl, model,
                "chat".equals(wireApi) ? "chat" : "responses",
                "api-key".equals(claudeAuthType) ? "api-key" : "auth-token",
                builtIn, Math.max(1, revision)
            );
        }
    }

    public record ProviderProfileSnapshot(
        String id,
        ProviderChannel channel,
        String name,
        String baseUrl,
        String model,
        String wireApi,
        String claudeAuthType,
        boolean builtIn,
        int revision
    ) {
        public ProviderProfileSnapshot {
            id = Objects.requireNonNullElse(id, "");
            channel = Objects.requireNonNullElse(channel, ProviderChannel.CODEX);
            name = Objects.requireNonNullElse(name, "");
            baseUrl = Objects.requireNonNullElse(baseUrl, "");
            model = Objects.requireNonNullElse(model, "");
            wireApi = Objects.requireNonNullElse(wireApi, "responses");
            claudeAuthType = Objects.requireNonNullElse(claudeAuthType, "auth-token");
            revision = Math.max(1, revision);
        }
    }

    public record SettingsSnapshot(
        ProviderChannel activeProvider,
        String codexExecutable,
        String claudeExecutable,
        String model,
        String claudeModel,
        ReasoningEffort reasoningEffort,
        ServiceTier serviceTier,
        ApprovalPolicy approvalPolicy,
        SandboxMode sandboxMode,
        boolean streamResponses,
        boolean taskCompletionNotificationEnabled,
        boolean taskCompletionSoundEnabled,
        ProviderProfileSnapshot provider
    ) {}

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

    public CodexSettingsState() {
        migrateState(state);
    }

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
        migrateState(state);
    }

    public synchronized SettingsSnapshot snapshot(String channel) {
        var provider = activeProvider(channel).snapshot();
        return new SettingsSnapshot(
            ProviderChannel.from(state.activeProvider),
            Objects.requireNonNullElse(state.codexExecutable, "codex"),
            Objects.requireNonNullElse(state.claudeExecutable, "claude"),
            Objects.requireNonNullElse(state.model, ""),
            Objects.requireNonNullElse(state.claudeModel, ""),
            ReasoningEffort.from(state.reasoningEffort),
            ServiceTier.from(state.serviceTier),
            ApprovalPolicy.from(state.approvalPolicy),
            SandboxMode.from(state.sandboxMode),
            state.streamResponses,
            state.taskCompletionNotificationEnabled,
            state.taskCompletionSoundEnabled,
            provider
        );
    }

    public synchronized void setClaudeModel(String model) {
        state.claudeModel = Objects.requireNonNullElse(model, "");
    }

    public ProviderProfile activeProvider(String channel) {
        normalizeProviders();
        var activeId = CLAUDE_CHANNEL.equals(channel) ? state.activeClaudeProviderId : state.activeCodexProviderId;
        return state.providers.stream()
            .filter(item -> channel.equals(item.channel) && activeId.equals(item.id))
            .findFirst()
            .orElseGet(() -> state.providers.stream()
                .filter(item -> channel.equals(item.channel) && item.builtIn)
                .findFirst()
                .orElseThrow());
    }

    public ProviderProfile provider(String id) {
        normalizeProviders();
        return state.providers.stream().filter(item -> item.id.equals(id)).findFirst().orElse(null);
    }

    public String activeProviderId(String channel) {
        return activeProvider(channel).id;
    }

    public void setActiveProvider(String channel, String id) {
        if (CLAUDE_CHANNEL.equals(channel)) {
            state.activeClaudeProviderId = id;
        } else {
            state.activeCodexProviderId = id;
        }
    }

    private void normalizeProviders() {
        if (state.providers == null) state.providers = new ArrayList<>();
        state.providers.removeIf(item -> item == null || item.id == null || item.id.isBlank());
        for (var item : state.providers) {
            item.channel = CLAUDE_CHANNEL.equals(item.channel) ? CLAUDE_CHANNEL : CODEX_CHANNEL;
            if (item.name == null) item.name = "";
            if (item.baseUrl == null) item.baseUrl = "";
            if (item.model == null) item.model = "";
            if (!"chat".equals(item.wireApi)) item.wireApi = "responses";
            if (!"api-key".equals(item.claudeAuthType)) item.claudeAuthType = "auth-token";
            if (item.revision < 1) item.revision = 1;
        }
        addBuiltInProvider(CODEX_LOCAL_PROVIDER_ID, CODEX_CHANNEL, "Codex 本地配置");
        addBuiltInProvider(CLAUDE_LOCAL_PROVIDER_ID, CLAUDE_CHANNEL, "Claude 本地配置");

        // 旧版本没有活动供应商 ID，迁移时回退到对应渠道的本地配置。
        if (providerInChannel(state.activeCodexProviderId, CODEX_CHANNEL) == null) state.activeCodexProviderId = CODEX_LOCAL_PROVIDER_ID;
        if (providerInChannel(state.activeClaudeProviderId, CLAUDE_CHANNEL) == null) state.activeClaudeProviderId = CLAUDE_LOCAL_PROVIDER_ID;
    }

    private void migrateState(StateData target) {
        if (target.schemaVersion >= CURRENT_SCHEMA_VERSION) return;
        // v1 将历史自由字符串归一化，并补齐双渠道内置 Provider；迁移只在加载旧 schema 时执行一次。
        target.activeProvider = ProviderChannel.from(target.activeProvider).value();
        target.reasoningEffort = ReasoningEffort.from(target.reasoningEffort).value();
        target.serviceTier = ServiceTier.from(target.serviceTier).value();
        target.approvalPolicy = ApprovalPolicy.from(target.approvalPolicy).value();
        target.sandboxMode = SandboxMode.from(target.sandboxMode).value();
        target.sendShortcut = SendShortcut.from(target.sendShortcut).value();
        normalizeProviders();
        target.schemaVersion = CURRENT_SCHEMA_VERSION;
    }

    private ProviderProfile providerInChannel(String id, String channel) {
        if (id == null) return null;
        return state.providers.stream()
            .filter(item -> id.equals(item.id) && channel.equals(item.channel))
            .findFirst()
            .orElse(null);
    }

    private void addBuiltInProvider(String id, String channel, String name) {
        var existing = state.providers.stream().filter(item -> id.equals(item.id)).findFirst().orElse(null);
        if (existing == null) {
            state.providers.add(new ProviderProfile(id, channel, name, true));
            return;
        }
        existing.channel = channel;
        existing.name = name;
        existing.builtIn = true;
        if (existing.revision < 1) existing.revision = 1;
    }

    private static List<ProviderProfile> defaultProviders() {
        return new ArrayList<>(List.of(
            new ProviderProfile(CODEX_LOCAL_PROVIDER_ID, CODEX_CHANNEL, "Codex 本地配置", true),
            new ProviderProfile(CLAUDE_LOCAL_PROVIDER_ID, CLAUDE_CHANNEL, "Claude 本地配置", true)
        ));
    }

    public boolean isFavorite(String threadId) {
        return state.favoriteThreads.contains(threadId);
    }

    public void setFavorite(String threadId, boolean favorite) {
        if (favorite && !state.favoriteThreads.contains(threadId)) state.favoriteThreads.add(threadId);
        if (!favorite) state.favoriteThreads.remove(threadId);
    }
}
