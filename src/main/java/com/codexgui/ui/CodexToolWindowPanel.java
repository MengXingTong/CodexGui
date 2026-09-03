package com.codexgui.ui;

import com.codexgui.model.Attachment;
import com.codexgui.model.ChangeEntry;
import com.codexgui.model.ConversationEntry;
import com.codexgui.model.EditorFileContext;
import com.codexgui.model.FileReference;
import com.codexgui.service.CodexAppServerService;
import com.codexgui.service.CodexEventListener;
import com.codexgui.service.ClaudeCodeService;
import com.codexgui.service.NotificationSoundPlayer;
import com.codexgui.service.ProjectFileSearch;
import com.codexgui.service.WorkspaceChangeService;
import com.codexgui.settings.CodexSettingsState;
import com.codexgui.settings.CodexProjectSettingsState;
import com.codexgui.settings.ProviderCredentialStore;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.ide.BrowserUtil;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.ide.dnd.DnDEvent;
import com.intellij.ide.dnd.DnDSupport;
import com.intellij.ide.dnd.FileCopyPasteUtil;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.SelectionEvent;
import com.intellij.openapi.editor.event.SelectionListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.intellij.util.concurrency.AppExecutorUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class CodexToolWindowPanel extends JPanel implements Disposable, CodexEventListener {
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final long PROJECT_FILE_CACHE_MILLIS = 2_000L;

    private final Project project;
    private final CodexAppServerService codex;
    private final ClaudeCodeService claude;
    private final WorkspaceChangeService changeService;
    private final NotificationSoundPlayer notificationSoundPlayer = new NotificationSoundPlayer();
    private List<Attachment> attachments = new ArrayList<>();
    private List<FileReference> fileReferences = new ArrayList<>();
    private List<ConversationEntry> transcript = new ArrayList<>();
    private final Consumer<WorkspaceChangeService.ChangeUpdate> changeListener;
    private final JBCefBrowser browser;
    private final JBCefJSQuery bridge;

    private String currentThreadId;
    private String currentTurnId;
    private String currentProvider = "codex";
    private String currentProviderProfileId = CodexSettingsState.CODEX_LOCAL_PROVIDER_ID;
    private int currentProviderRevision = 1;
    private String currentTitle = "新会话";
    private String pendingUserBody;
    private int pendingUserMessageCount;
    private boolean busy;
    private boolean pageReady;
    private long usageUsedTokens;
    private long usageMaxTokens;
    private JsonArray codexModels = new JsonArray();
    private final Map<String, StringBuilder> pendingCommandDeltas = new ConcurrentHashMap<>();
    private final java.util.Set<String> scheduledCommandDeltas = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> completedCommandItems = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> confirmedSessionIds = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> claudeTurnGenerations = new ConcurrentHashMap<>();
    private static final class SessionState {
        private String threadId;
        private String turnId;
        private String provider = "codex";
        private String providerProfileId = CodexSettingsState.CODEX_LOCAL_PROVIDER_ID;
        private int providerRevision = 1;
        private String title = "新会话";
        private String pendingUserBody;
        private int pendingUserMessageCount;
        private boolean busy;
        private long usageUsedTokens;
        private long usageMaxTokens;
        private List<Attachment> attachments = new ArrayList<>();
        private List<FileReference> fileReferences = new ArrayList<>();
        private List<ConversationEntry> transcript = new ArrayList<>();
        private List<QueuedInput> queuedInputs = new ArrayList<>();

        private SessionState(String ignored) {}
    }

    private record QueuedInput(String inputText, String display, List<Attachment> attachments, List<FileReference> fileReferences) {}

    private final Map<String, SessionState> sessions = new LinkedHashMap<>();
    private String activeSessionId = "default";
    private volatile List<Path> pendingDraggedPaths = List.of();
    private volatile ComposerDropRegion composerDropRegion;
    private volatile boolean nativeDragActive;
    private volatile List<ProjectFileSearch.Candidate> projectFileCatalog = List.of();
    private volatile long projectFileCatalogLoadedAt;
    private List<QueuedInput> queuedInputs = new ArrayList<>();

    CodexToolWindowPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        this.codex = CodexAppServerService.getInstance(project);
        this.changeService = WorkspaceChangeService.getInstance(project);
        this.claude = new ClaudeCodeService(project.getBasePath() == null ? null : Path.of(project.getBasePath()));
        var initialSession = new SessionState(activeSessionId);
        var settingsService = CodexSettingsState.getInstance();
        initialSession.provider = provider(settingsService.getState().activeProvider);
        var initialProvider = settingsService.activeProvider(initialSession.provider);
        initialSession.providerProfileId = initialProvider.id;
        initialSession.providerRevision = initialProvider.revision;
        currentProviderProfileId = initialProvider.id;
        currentProviderRevision = initialProvider.revision;
        sessions.put(activeSessionId, initialSession);
        this.changeListener = update -> publishChanges(update.sessionId(), update.changes());

        if (!JBCefApp.isSupported()) {
            browser = null;
            bridge = null;
            add(new JLabel("当前 JetBrains Runtime 不支持 JCEF，无法显示 Codex GUI。", SwingConstants.CENTER));
            return;
        }

        browser = new JBCefBrowser();
        // CEF 提供本地绝对路径，页面 drop 事件再确认文件确实落在输入卡内。
        browser.getJBCefClient().addDragHandler((cefBrowser, dragData, mask) -> {
            var fileNames = new Vector<String>();
            var paths = dragData.isFile() && dragData.getFileNames(fileNames)
                ? fileNames.stream().map(this::droppedPath).filter(Objects::nonNull).toList()
                : List.<Path>of();
            if (paths.isEmpty() && dragData.isFragment()) paths = droppedTextPaths(dragData.getFragmentText());
            pendingDraggedPaths = paths;
            publishNativeDragState(!paths.isEmpty());
            return false;
        }, browser.getCefBrowser());
        installProjectViewDropTarget();
        bridge = JBCefJSQuery.create((JBCefBrowserBase) browser);
        bridge.addHandler(payload -> {
            handleBridgeMessage(payload);
            return new JBCefJSQuery.Response(null);
        });
        add(browser.getComponent(), BorderLayout.CENTER);
        browser.setPageBackgroundColor("#1e1e1e");
        browser.loadHTML(buildWebApp(), "http://codex-gui.local/");

        codex.addListener(this);
        changeService.addListener(changeListener);
        project.getMessageBus().connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            new FileEditorManagerListener() {
                @Override
                public void selectionChanged(FileEditorManagerEvent event) {
                    publishFileContext();
                }
            }
        );
        EditorFactory.getInstance().getEventMulticaster().addSelectionListener(new SelectionListener() {
            @Override
            public void selectionChanged(SelectionEvent event) {
                if (event.getEditor().getProject() == project) publishFileContext();
            }
        }, this);
        codex.start().thenRun(() -> {
            loadModels();
            loadHistory("");
        }).exceptionally(error -> {
            asyncError("连接 Codex CLI 失败", error);
            return null;
        });
    }

    private String buildWebApp() {
        var template = resource("/web/index.html");
        var bridgeScript = "window.codexHost = function(payload) {" + bridge.inject("payload") + ";};";
        return template
            .replace("/*__CODEX_GUI_STYLE__*/", resource("/web/app.css"))
            .replace("/*__CODEX_GUI_BRIDGE__*/", bridgeScript)
            .replace("/*__CODEX_GUI_SCRIPT__*/", resource("/web/app.js"));
    }

    private String resource(String name) {
        try (InputStream input = getClass().getResourceAsStream(name)) {
            if (input == null) throw new IllegalStateException("缺少界面资源：" + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("无法读取界面资源：" + name, error);
        }
    }

    private void handleBridgeMessage(String payload) {
        JsonObject request;
        try {
            request = GSON.fromJson(payload, JsonObject.class);
        } catch (RuntimeException error) {
            asyncError("界面消息无效", error);
            return;
        }
        var action = string(request, "action", "");
        ApplicationManager.getApplication().invokeLater(() -> {
            // 关闭会话只确认目标事务，不切换活动会话，避免批量关闭后活动指针落在已关闭页签。
            if (!Objects.equals(action, "closeSession")) activateSession(string(request, "sessionId", activeSessionId));
            switch (action) {
                case "ready" -> bootstrap();
                case "reconnect" -> codex.restart().thenRun(this::loadModels).exceptionally(error -> {
                    asyncError("重新连接 Codex CLI 失败", error);
                    return null;
                });
                case "send" -> sendInput(string(request, "text", ""));
                case "stop" -> interruptCurrentTurn();
                case "new" -> newConversation(string(request, "title", ""), bool(request, "skipConfirmation", false));
                case "closeSession" -> closeSession(string(request, "sessionId", ""));
                case "activateSession" -> publishCurrentSession();
                case "history" -> loadHistory(string(request, "search", ""));
                case "openThread" -> openThread(string(request, "id", ""));
                case "rename" -> renameCurrentThread();
                case "export" -> exportConversation();
                case "pickFile" -> chooseAttachment(false);
                case "pickImage" -> chooseAttachment(true);
                case "dropFiles" -> addDroppedInputs();
                case "cancelDrop" -> {
                    pendingDraggedPaths = List.of();
                    publishNativeDragState(false);
                }
                case "composerBounds" -> updateComposerDropRegion(request);
                case "listProjectFiles" -> publishProjectFiles(
                    string(request, "query", ""), longValue(request, "requestId")
                );
                case "removeAttachment" -> removeAttachment(integer(request, "index"));
                case "removeFileReference" -> removeFileReference(string(request, "id", ""));
                case "removeFileReferences" -> removeFileReferences(request);
                case "addFileReferences" -> addFileReferences(request);
                case "reorderFileReferences" -> reorderFileReferences(request);
                case "acceptChange" -> acceptChange(integer(request, "index"));
                case "revertChange" -> revertChange(integer(request, "index"));
                case "acceptAll" -> acceptAllChanges();
                case "revertAll" -> revertAllChanges();
                case "openChange" -> openChange(integer(request, "index"));
                case "compact" -> compactCurrentThread();
                case "review" -> reviewCurrentChanges();
                case "rewind" -> rollbackLastTurn();
                case "mcp" -> showMcpServers();
                case "usage" -> showUsage();
                case "setting" -> updateSetting(string(request, "key", ""), string(request, "value", ""));
                case "selectProvider" -> selectProvider(string(request, "provider", "codex"));
                case "activateProviderProfile" -> activateProviderProfile(string(request, "id", ""));
                case "saveProviderProfile" -> saveProviderProfile(request);
                case "deleteProviderProfile" -> deleteProviderProfile(string(request, "id", ""));
                case "checkProviders" -> publishProviderStatus();
                case "behaviorSetting" -> updateBehaviorSetting(request);
                case "browseNotificationSound" -> browseNotificationSound();
                case "testNotificationSound" -> playConfiguredSound(true);
                case "toggleStreaming" -> toggleStreaming();
                case "toggleThinking" -> toggleThinking();
                case "saveInstructions" -> saveInstructions(request);
                case "savePrompt" -> savePrompt(request);
                case "deletePrompt" -> deletePrompt(string(request, "id", ""));
                case "selectPrompt" -> selectPrompt(string(request, "id", ""));
                case "saveAgent" -> saveAgent(request);
                case "deleteAgent" -> deleteAgent(string(request, "id", ""));
                case "selectAgent" -> selectAgent(string(request, "id", ""));
                case "loadMcp" -> publishMcpServers(false);
                case "reloadMcp" -> publishMcpServers(true);
                case "loadSkills" -> publishSkills(false);
                case "reloadSkills" -> publishSkills(true);
                case "setSkillEnabled" -> setSkillEnabled(request);
                case "importSkill" -> importSkill(string(request, "scope", "repo"));
                case "openSkill" -> openSkill(request);
                case "openMcpConfig" -> openMcpConfig();
                case "loginMcp" -> loginMcpServer(request);
                case "saveMcp" -> saveMcpServer(request);
                case "deleteMcp" -> deleteMcpServer(request);
                case "setMcpEnabled" -> setMcpServerEnabled(request);
                case "copyText" -> copyText(request);
                case "answerQuestions" -> answerQuestions(request, false);
                case "cancelQuestions" -> answerQuestions(request, true);
                case "conversationSearch" -> searchConversation();
                case "openFile" -> openFileLocation(request);
                case "openUrl" -> BrowserUtil.browse(string(request, "url", ""));
                case "openSettings" -> ShowSettingsUtil.getInstance().showSettingsDialog(project, "Codex GUI");
                default -> {
                }
            }
        });
    }

    private void bootstrap() {
        pageReady = true;
        var settings = CodexSettingsState.getInstance().getState();
        var instructions = sharedInstructions(settings);
        var state = new JsonObject();
        state.addProperty("connected", codex.isConnected());
        state.addProperty("sessionId", activeSessionId);
        state.addProperty("busy", busy);
        state.addProperty("queuedCount", queuedInputs.size());
        state.addProperty("title", currentTitle);
        if (currentThreadId != null) state.addProperty("threadId", currentThreadId);
        state.addProperty("model", activeModel(settings));
        state.add("models", providerModels(settings));
        state.addProperty("provider", currentProvider);
        state.addProperty("providerProfileId", currentProviderProfileId);
        state.addProperty("showThinking", settings.showThinking);
        state.addProperty("effort", settings.reasoningEffort);
        state.addProperty("serviceTier", settings.serviceTier);
        state.addProperty("approval", settings.approvalPolicy);
        state.addProperty("sandbox", settings.sandboxMode);
        state.addProperty("streamResponses", settings.streamResponses);
        addBehaviorSettings(state, settings);
        state.addProperty("globalInstructions", settings.globalInstructions);
        state.addProperty("projectInstructions", instructions);
        state.addProperty("activePromptId", settings.activePromptId);
        state.add("prompts", promptsJson(settings.prompts));
        state.addProperty("activeAgentId", settings.activeAgentId);
        state.add("agents", agentsJson(settings.agents));
        state.add("attachments", attachmentsJson());
        state.add("fileReferences", fileReferencesJson());
        var event = event("bootstrap");
        event.add("state", state);
        sendEvent(event);
        transcript.forEach(this::publishEntry);
        publishChanges(activeSessionId, changeService.getChanges(activeSessionId));
        publishSkills(false);
        publishProviderStatus();
        // 页面可能晚于 CLI 连接完成，准备完成后重新请求模型，避免模型事件丢失。
        loadModels();
    }

    private SessionState activeSession() { return sessions.get(activeSessionId); }

    private void saveActiveSession() {
        var session = activeSession();
        if (session == null) return;
        session.threadId = currentThreadId;
        session.turnId = currentTurnId;
        session.provider = currentProvider;
        session.providerProfileId = currentProviderProfileId;
        session.providerRevision = currentProviderRevision;
        session.title = currentTitle;
        session.pendingUserBody = pendingUserBody;
        session.pendingUserMessageCount = pendingUserMessageCount;
        session.busy = busy;
        session.usageUsedTokens = usageUsedTokens;
        session.usageMaxTokens = usageMaxTokens;
        session.attachments = attachments;
        session.fileReferences = fileReferences;
        session.transcript = transcript;
        session.queuedInputs = queuedInputs;
    }

    private void activateSession(String sessionId) {
        var id = sessionId == null || sessionId.isBlank() ? "default" : sessionId;
        if (Objects.equals(activeSessionId, id) && activeSession() != null) return;
        saveActiveSession();
        var session = sessions.computeIfAbsent(id, key -> {
            var created = new SessionState(key);
            var settingsService = CodexSettingsState.getInstance();
            created.provider = provider(settingsService.getState().activeProvider);
            var activeProvider = settingsService.activeProvider(created.provider);
            created.providerProfileId = activeProvider.id;
            created.providerRevision = activeProvider.revision;
            return created;
        });
        activeSessionId = id;
        currentThreadId = session.threadId;
        currentTurnId = session.turnId;
        currentProvider = provider(session.provider);
        currentProviderProfileId = session.providerProfileId;
        currentProviderRevision = session.providerRevision;
        currentTitle = session.title;
        pendingUserBody = session.pendingUserBody;
        pendingUserMessageCount = session.pendingUserMessageCount;
        busy = session.busy;
        usageUsedTokens = session.usageUsedTokens;
        usageMaxTokens = session.usageMaxTokens;
        attachments = session.attachments;
        fileReferences = session.fileReferences;
        transcript = session.transcript;
        queuedInputs = session.queuedInputs;
    }

    private void publishCurrentSession() {
        var event = event("clear");
        event.addProperty("title", currentTitle);
        event.addProperty("provider", currentProvider);
        if (currentThreadId != null) event.addProperty("threadId", currentThreadId);
        sendEvent(event);
        transcript.forEach(this::publishEntry);
        publishAttachments();
        publishFileReferences();
        publishChanges(activeSessionId, changeService.getChanges(activeSessionId));
        publishThread();
        var usage = event("usage");
        usage.addProperty("usedTokens", usageUsedTokens);
        usage.addProperty("maxTokens", usageMaxTokens);
        usage.addProperty("percentage", usageMaxTokens > 0 ? Math.min(100.0, usageUsedTokens * 100.0 / usageMaxTokens) : 0.0);
        sendEvent(usage);
        setBusy(busy);
    }

    private void sendInput(String text) {
        text = text.trim();
        if (text.isBlank() && attachments.isEmpty() && fileReferences.isEmpty()) return;
        if (busy) {
            enqueueInput(text);
            return;
        }
        var settingsService = CodexSettingsState.getInstance();
        var activeProvider = settingsService.activeProvider(currentProvider);
        if (!Objects.equals(currentProviderProfileId, activeProvider.id) || currentProviderRevision != activeProvider.revision) {
            toast("当前会话使用的供应商配置已变化，请开启新对话后继续");
            return;
        }
        if (Objects.equals(currentProvider, "codex") && !codex.isConnected()) {
            var reconnectText = text;
            codex.start().thenRun(() -> sendInput(reconnectText)).exceptionally(error -> {
                asyncError("Codex CLI 未连接", error);
                return null;
            });
            return;
        }
        if (attachments.isEmpty() && fileReferences.isEmpty() && handleNativeCommand(text)) return;

        var input = prepareInput(text);
        attachments.clear();
        fileReferences.clear();
        publishAttachments();
        publishFileReferences();
        dispatchInput(input, true);
    }

    private QueuedInput prepareInput(String text) {
        var settings = CodexSettingsState.getInstance().getState();
        var editorContext = settings.sendOpenedFilePath ? currentEditorContext() : null;
        var inputText = editorContext == null ? text : editorContext.appendTo(text);
        var sentAttachments = List.copyOf(attachments);
        var sentFileReferences = List.copyOf(fileReferences);
        var display = new StringBuilder(embedFileReferencePaths(text, sentFileReferences));
        if (editorContext != null) display.append("\n\n[当前编辑器上下文] ").append(editorContext.displayLabel());
        sentAttachments.forEach(attachment -> display.append("\n").append(switch (attachment.kind()) {
            case IMAGE -> "[图片] ";
            case FILE -> "@";
        }).append(attachment.kind() == Attachment.Kind.FILE ? absolutePath(attachment.path()) : attachment.name()));
        return new QueuedInput(inputText, display.toString().trim(), sentAttachments, sentFileReferences);
    }

    private void enqueueInput(String text) {
        if (attachments.isEmpty() && fileReferences.isEmpty() && handleNativeCommand(text)) return;
        var input = prepareInput(text);
        queuedInputs.add(input);
        addEntry(new ConversationEntry(
            ConversationEntry.Kind.USER,
            "你",
            input.display(),
            null,
            input.fileReferences().stream().map(reference -> absolutePath(reference.path())).toList()
        ));
        pendingUserMessageCount++;
        attachments.clear();
        fileReferences.clear();
        publishAttachments();
        publishFileReferences();
        saveActiveSession();
        publishQueueState();
    }

    private void dispatchInput(QueuedInput input, boolean publishUser) {
        var sessionId = activeSessionId;

        var settingsService = CodexSettingsState.getInstance();
        var settings = settingsService.getState();
        var providerProfile = settingsService.activeProvider(currentProvider);
        var model = providerProfile.builtIn
            ? (Objects.equals(currentProvider, "claude") ? settings.claudeModel : settings.model)
            : providerProfile.model;
        if (publishUser) {
            pendingUserBody = input.display();
            addEntry(new ConversationEntry(
                ConversationEntry.Kind.USER,
                "你",
                input.display(),
                null,
                input.fileReferences().stream().map(reference -> absolutePath(reference.path())).toList()
            ));
            pendingUserMessageCount++;
        }
        setBusy(true);

        // Claude Code 使用独立 CLI 会话，不能把会话 ID 或输入误发给 Codex app-server。
        if (Objects.equals(currentProvider, "claude")) {
            dispatchClaudeInput(sessionId, input);
            return;
        }

        var capture = changeService.beginCaptureAsync(sessionId).thenCompose(ignored -> currentThreadId == null
            ? codex.startThread(
                model,
                settings.reasoningEffort,
                settings.serviceTier,
                settings.approvalPolicy,
                settings.sandboxMode,
                developerInstructions()
            )
                .thenApply(result -> {
                    activateSession(sessionId);
                    currentThreadId = result.getAsJsonObject("thread").get("id").getAsString();
                    saveActiveSession();
                    publishThread();
                    return currentThreadId;
                })
            : CompletableFuture.completedFuture(currentThreadId));
        capture.thenCompose(threadId -> codex.startTurn(
            threadId, input.inputText(), input.attachments(), input.fileReferences(), model, settings.reasoningEffort,
            settings.serviceTier,
            settings.approvalPolicy, settings.sandboxMode
        )).thenAccept(result -> {
            activateSession(sessionId);
            var turn = result.getAsJsonObject("turn");
            if (turn != null && turn.has("id")) currentTurnId = turn.get("id").getAsString();
            saveActiveSession();
        }).exceptionally(error -> {
            changeService.finishCaptureAsync(sessionId);
            activateSession(sessionId);
            if (pendingUserMessageCount > 0) pendingUserMessageCount--;
            pendingUserBody = null;
            setBusy(false);
            asyncError("无法发送消息", error);
            startNextQueuedInput(sessionId);
            return null;
        });
    }

    private void dispatchClaudeInput(String sessionId, QueuedInput input) {
        var settingsService = CodexSettingsState.getInstance();
        var settings = settingsService.getState();
        var providerProfile = settingsService.activeProvider(CodexSettingsState.CLAUDE_CHANNEL);
        var conversationId = currentThreadId;
        var turnGeneration = claudeTurnGenerations.merge(sessionId, 1L, Long::sum);
        var itemId = "claude:" + UUID.randomUUID();
        var thinkingId = itemId + ":thinking";
        var streamedText = new StringBuilder();
        var toolIds = ConcurrentHashMap.<String>newKeySet();
        var prompt = claudePrompt(input);

        var turn = changeService.beginWorkspaceCaptureAsync(sessionId).thenCompose(ignored -> {
            // 用户可能在基线快照期间停止任务，过期回合不能继续启动 CLI。
            if (!isCurrentClaudeTurn(sessionId, turnGeneration)) {
                return CompletableFuture.failedFuture(new CancellationException("Claude Code 回合已停止"));
            }
            return claude.startTurn(
                sessionId,
                settings.claudeExecutable,
                conversationId,
                prompt,
                providerProfile.builtIn ? "" : providerProfile.model,
                settings.reasoningEffort,
                settings.approvalPolicy,
                developerInstructions(),
                providerProfile,
                new ClaudeCodeService.Listener() {
                    @Override
                    public void onModel(String model) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (!isCurrentClaudeTurn(sessionId, turnGeneration)) return;
                            activateSession(sessionId);
                            settings.claudeModel = model;
                            publishSettings();
                        });
                    }

                    @Override
                    public void onTextDelta(String delta) {
                        if (delta.isEmpty()) return;
                        synchronized (streamedText) { streamedText.append(delta); }
                        if (settings.streamResponses) ApplicationManager.getApplication().invokeLater(() -> {
                            if (!isCurrentClaudeTurn(sessionId, turnGeneration)) return;
                            activateSession(sessionId);
                            appendEntry(itemId, ConversationEntry.Kind.ASSISTANT, "Claude", delta);
                        });
                    }

                    @Override
                    public void onThinkingDelta(String delta) {
                        if (delta.isEmpty() || !settings.streamResponses) return;
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (!isCurrentClaudeTurn(sessionId, turnGeneration)) return;
                            activateSession(sessionId);
                            appendEntry(thinkingId, ConversationEntry.Kind.REASONING, "思考", delta);
                        });
                    }

                    @Override
                    public void onTool(String id, String name, JsonObject toolInput) {
                        toolIds.add(id);
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (!isCurrentClaudeTurn(sessionId, turnGeneration)) return;
                            activateSession(sessionId);
                            replaceEntry(id, ConversationEntry.Kind.COMMAND, "Claude 工具", name + "\n\n" + GSON.toJson(toolInput));
                        });
                    }
                }
            );
        });
        turn.whenComplete((result, turnError) -> changeService.finishCaptureAsync(sessionId)
            .whenComplete((ignored, captureError) -> ApplicationManager.getApplication().invokeLater(() -> {
                activateSession(sessionId);
                var cancelled = !isCurrentClaudeTurn(sessionId, turnGeneration);
                var error = turnError == null ? captureError : turnError;
                // 正常完成时发布最终回复；停止或失败则只收尾当前回合。
                if (!cancelled && error == null) {
                    currentThreadId = result.sessionId();
                    currentTurnId = null;
                    String received;
                    synchronized (streamedText) { received = streamedText.toString(); }
                    if (!settings.streamResponses || received.isBlank()) {
                        replaceEntry(itemId, ConversationEntry.Kind.ASSISTANT, "Claude", result.finalText());
                    }
                    if (!result.model().isBlank()) settings.claudeModel = result.model();
                    toolIds.forEach(id -> appendEntry(id, ConversationEntry.Kind.COMMAND, "Claude 工具", "\n\n执行状态：completed"));
                    publishThread();
                    notifyAttention("Claude 任务已完成", currentTitle, settings.taskCompletionNotificationEnabled, settings.taskCompletionSoundEnabled);
                } else if (!cancelled) {
                    asyncError("无法发送 Claude Code 消息", error);
                }
                if (pendingUserMessageCount > 0) pendingUserMessageCount--;
                pendingUserBody = null;
                setBusy(false);
                startNextQueuedInput(sessionId);
            })));
    }

    private boolean isCurrentClaudeTurn(String sessionId, long generation) {
        return Objects.equals(claudeTurnGenerations.get(sessionId), generation);
    }

    private String claudePrompt(QueuedInput input) {
        var result = new StringBuilder(input.inputText());
        // Claude Code 通过工作区工具读取附件，因此显式提供绝对路径并保留原始用户文本。
        for (var reference : input.fileReferences()) {
            result.append("\n\n[引用文件] ").append(absolutePath(reference.path()));
        }
        for (var attachment : input.attachments()) {
            result.append("\n\n[")
                .append(attachment.kind() == Attachment.Kind.IMAGE ? "图片" : "附件")
                .append("] ")
                .append(absolutePath(attachment.path()));
        }
        return result.toString();
    }

    private void startNextQueuedInput(String sessionId) {
        activateSession(sessionId);
        if (busy || queuedInputs.isEmpty()) return;
        var next = queuedInputs.remove(0);
        publishQueueState();
        dispatchInput(next, false);
    }

    private String embedFileReferencePaths(String text, List<FileReference> references) {
        var parts = text.split("\uFFFC", -1);
        var embeddedCount = Math.min(references.size(), Math.max(0, parts.length - 1));
        var result = new StringBuilder();
        for (var index = 0; index < parts.length; index++) {
            result.append(parts[index]);
            if (index < embeddedCount) result.append('@').append(absolutePath(references.get(index).path()));
        }
        for (var index = embeddedCount; index < references.size(); index++) {
            if (!result.isEmpty()) result.append('\n');
            result.append('@').append(absolutePath(references.get(index).path()));
        }
        return result.toString();
    }

    private String absolutePath(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private boolean handleNativeCommand(String text) {
        switch (text) {
            case "/new" -> newConversation();
            case "/compact" -> compactCurrentThread();
            case "/review" -> reviewCurrentChanges();
            case "/rewind" -> rollbackLastTurn();
            case "/mcp" -> showMcpServers();
            case "/usage" -> showUsage();
            case "/help" -> addEntry(new ConversationEntry(ConversationEntry.Kind.NOTICE, "Codex 原生命令", "/new · /compact · /review · /rewind · /mcp · /usage", null));
            default -> {
                return false;
            }
        }
        return true;
    }

    private void loadModels() {
        // CLI 尚未完成 initialize 时不能发送请求，等待连接事件重新加载。
        if (!codex.isConnected()) return;
        codex.listModels().thenAccept(result -> {
            var models = new JsonArray();
            String defaultModel = null;
            var modelItems = array(result, "data");
            if (modelItems.isEmpty()) modelItems = array(result, "models");
            for (var element : modelItems) {
                var model = element.getAsJsonObject();
                if (model.has("hidden") && model.get("hidden").getAsBoolean()) continue;
                var id = string(model, "model", "");
                if (id.isBlank()) id = string(model, "id", string(model, "slug", ""));
                if (!id.isBlank()) models.add(id);
                if (model.has("isDefault") && model.get("isDefault").getAsBoolean()) defaultModel = id;
            }
            var settings = CodexSettingsState.getInstance().getState();
            if (settings.model.isBlank() && defaultModel != null) settings.model = defaultModel;
            codexModels = models.deepCopy();
            if (!Objects.equals(currentProvider, "codex")) return;
            var providerProfile = CodexSettingsState.getInstance().activeProvider(CodexSettingsState.CODEX_CHANNEL);
            var event = event("bootstrap");
            var state = new JsonObject();
            if (providerProfile.builtIn) {
                state.add("models", models);
                state.addProperty("model", settings.model);
            } else {
                var providerModels = new JsonArray();
                providerModels.add(providerProfile.model);
                state.add("models", providerModels);
                state.addProperty("model", providerProfile.model);
            }
            event.add("state", state);
            sendEvent(event);
        }).exceptionally(error -> {
            asyncError("无法读取 Codex 模型列表", error);
            return null;
        });
    }

    private void selectProvider(String requestedProvider) {
        var selected = provider(requestedProvider);
        if (Objects.equals(selected, currentProvider)) return;
        if (busy) {
            toast("任务运行期间不能切换供应商");
            return;
        }
        if (currentThreadId != null || !transcript.isEmpty()) {
            toast("当前会话已绑定供应商，请在新页签或新会话中切换");
            return;
        }

        // 供应商只在空白会话中切换，避免跨协议复用不兼容的会话 ID。
        currentProvider = selected;
        var settingsService = CodexSettingsState.getInstance();
        var settings = settingsService.getState();
        settings.activeProvider = selected;
        var profile = settingsService.activeProvider(selected);
        currentProviderProfileId = profile.id;
        currentProviderRevision = profile.revision;
        saveActiveSession();
        publishSettings();
        publishProviderStatus();
        toast(Objects.equals(selected, "claude") ? "已切换到 Claude 渠道" : "已切换到 GPT 渠道");
    }

    private void publishProviderStatus() {
        var settingsService = CodexSettingsState.getInstance();
        var settings = settingsService.getState();
        var profiles = List.copyOf(settings.providers);
        CompletableFuture.supplyAsync(
            () -> claude.isAvailable(settings.claudeExecutable),
            AppExecutorUtil.getAppExecutorService()
        ).thenAccept(claudeAvailable -> {
            var providers = new JsonArray();
            for (var profile : profiles) {
                var localAvailable = Objects.equals(profile.channel, CodexSettingsState.CLAUDE_CHANNEL)
                    ? claudeAvailable
                    : codex.isConnected();
                providers.add(providerJson(profile, localAvailable, settingsService));
            }
            var event = event("providers");
            event.add("items", providers);
            sendEvent(event);
        });
    }

    private JsonObject providerJson(
        CodexSettingsState.ProviderProfile profile,
        boolean localAvailable,
        CodexSettingsState settingsService
    ) {
        var json = new JsonObject();
        var hasApiKey = profile.builtIn || ProviderCredentialStore.has(profile.id);
        var configured = profile.builtIn || (!profile.baseUrl.isBlank() && !profile.model.isBlank() && hasApiKey);
        json.addProperty("id", profile.id);
        json.addProperty("channel", profile.channel);
        json.addProperty("name", profile.name);
        json.addProperty("baseUrl", profile.baseUrl);
        json.addProperty("model", profile.model);
        json.addProperty("wireApi", profile.wireApi);
        json.addProperty("claudeAuthType", profile.claudeAuthType);
        json.addProperty("builtIn", profile.builtIn);
        json.addProperty("hasApiKey", hasApiKey);
        json.addProperty("available", profile.builtIn ? localAvailable : configured);
        json.addProperty("active", Objects.equals(settingsService.activeProviderId(profile.channel), profile.id));
        if (profile.builtIn) {
            var executable = Objects.equals(profile.channel, CodexSettingsState.CLAUDE_CHANNEL)
                ? claude.resolvedExecutable(settingsService.getState().claudeExecutable)
                : settingsService.getState().codexExecutable;
            json.addProperty("executable", executable);
        }
        return json;
    }

    private void activateProviderProfile(String id) {
        var settingsService = CodexSettingsState.getInstance();
        var profile = settingsService.provider(id);
        if (profile == null) return;
        if (sessions.values().stream().anyMatch(item -> item.busy)) {
            toast("任务运行期间不能切换供应商配置");
            return;
        }
        if (!profile.builtIn && (profile.baseUrl.isBlank() || profile.model.isBlank() || !ProviderCredentialStore.has(profile.id))) {
            toast("请先补全接口地址、API 密钥和模型");
            return;
        }
        if (Objects.equals(settingsService.activeProviderId(profile.channel), profile.id)) return;

        // 配置按渠道全局启用；空白会话可直接跟随，已有会话保留旧版本并阻止误发。
        settingsService.setActiveProvider(profile.channel, profile.id);
        if (Objects.equals(currentProvider, profile.channel) && currentThreadId == null && transcript.isEmpty()) {
            currentProviderProfileId = profile.id;
            currentProviderRevision = profile.revision;
            saveActiveSession();
        }
        applyProviderRuntimeChange(profile.channel);
        publishSettings();
        publishProviderStatus();
        toast("已启用供应商：" + profile.name);
    }

    private void saveProviderProfile(JsonObject request) {
        var settingsService = CodexSettingsState.getInstance();
        var settings = settingsService.getState();
        var requestedId = string(request, "id", "");
        var existing = requestedId.isBlank() ? null : settingsService.provider(requestedId);
        if (existing != null && existing.builtIn) return;
        var channel = provider(string(request, "channel", CodexSettingsState.CODEX_CHANNEL));
        var name = string(request, "name", "").trim();
        var baseUrl = string(request, "baseUrl", "").trim();
        var model = string(request, "model", "").trim();
        var apiKey = string(request, "apiKey", "").trim();
        if (name.isBlank() || baseUrl.isBlank() || model.isBlank()) {
            toast("供应商名称、接口地址和模型不能为空");
            return;
        }
        if (!validProviderUrl(baseUrl)) {
            toast("接口地址必须是有效的 http 或 https 地址");
            return;
        }
        if (existing == null && apiKey.isBlank()) {
            toast("新增供应商时必须填写 API 密钥");
            return;
        }
        var duplicate = settings.providers.stream().anyMatch(item -> !Objects.equals(item.id, requestedId)
            && Objects.equals(item.channel, channel) && item.name.equalsIgnoreCase(name));
        if (duplicate) {
            toast("同一渠道下不能使用重复的供应商名称");
            return;
        }
        var activeEdit = existing != null && Objects.equals(settingsService.activeProviderId(existing.channel), existing.id);
        if (activeEdit && sessions.values().stream().anyMatch(item -> item.busy)) {
            toast("任务运行期间不能修改正在使用的供应商");
            return;
        }

        var profile = existing == null ? new CodexSettingsState.ProviderProfile() : existing;
        if (existing == null) profile.id = "provider-" + UUID.randomUUID();
        profile.channel = channel;
        profile.name = name;
        profile.baseUrl = baseUrl;
        profile.model = model;
        profile.wireApi = Objects.equals(string(request, "wireApi", "responses"), "chat") ? "chat" : "responses";
        profile.claudeAuthType = Objects.equals(string(request, "claudeAuthType", "auth-token"), "api-key") ? "api-key" : "auth-token";
        if (existing != null) profile.revision++;
        if (existing == null) settings.providers.add(profile);
        if (!apiKey.isBlank()) ProviderCredentialStore.set(profile.id, apiKey);
        if (bool(request, "clearApiKey", false)) ProviderCredentialStore.remove(profile.id);

        if (activeEdit) applyProviderRuntimeChange(profile.channel);
        publishSettings();
        publishProviderStatus();
        toast(existing == null ? "供应商已添加" : "供应商配置已保存");
    }

    private void deleteProviderProfile(String id) {
        var settingsService = CodexSettingsState.getInstance();
        var settings = settingsService.getState();
        var profile = settingsService.provider(id);
        if (profile == null || profile.builtIn) return;
        var active = Objects.equals(settingsService.activeProviderId(profile.channel), profile.id);
        if (active && sessions.values().stream().anyMatch(item -> item.busy)) {
            toast("任务运行期间不能删除正在使用的供应商");
            return;
        }

        settings.providers.remove(profile);
        ProviderCredentialStore.remove(profile.id);
        if (active) {
            var fallbackId = Objects.equals(profile.channel, CodexSettingsState.CLAUDE_CHANNEL)
                ? CodexSettingsState.CLAUDE_LOCAL_PROVIDER_ID
                : CodexSettingsState.CODEX_LOCAL_PROVIDER_ID;
            settingsService.setActiveProvider(profile.channel, fallbackId);
            applyProviderRuntimeChange(profile.channel);
        }
        publishSettings();
        publishProviderStatus();
        toast("供应商已删除");
    }

    private boolean validProviderUrl(String value) {
        try {
            var uri = URI.create(value);
            return uri.getHost() != null && (Objects.equals(uri.getScheme(), "http") || Objects.equals(uri.getScheme(), "https"));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void applyProviderRuntimeChange(String channel) {
        if (!Objects.equals(channel, CodexSettingsState.CODEX_CHANNEL)) return;
        codex.restart().thenRun(this::loadModels).exceptionally(error -> {
            asyncError("切换 GPT 供应商失败", error);
            return null;
        });
    }

    private String provider(String value) {
        return Objects.equals(value, "claude") ? "claude" : "codex";
    }

    private String activeModel(CodexSettingsState.StateData settings) {
        var profile = CodexSettingsState.getInstance().activeProvider(currentProvider);
        if (!profile.builtIn) return profile.model;
        return Objects.equals(currentProvider, "claude") ? settings.claudeModel : settings.model;
    }

    private JsonArray providerModels(CodexSettingsState.StateData settings) {
        var profile = CodexSettingsState.getInstance().activeProvider(currentProvider);
        if (!profile.builtIn) {
            var models = new JsonArray();
            if (!profile.model.isBlank()) models.add(profile.model);
            return models;
        }
        if (Objects.equals(currentProvider, "codex")) return codexModels.deepCopy();
        var models = new JsonArray();
        if (!settings.claudeModel.isBlank()) models.add(settings.claudeModel);
        return models;
    }

    private void loadHistory(String search) {
        if (!codex.isConnected()) return;
        codex.listThreads(search).thenAccept(result -> {
            var items = new JsonArray();
            for (var element : array(result, "data")) {
                var thread = element.getAsJsonObject();
                var id = string(thread, "id", "");
                var title = string(thread, "name", "");
                if (title.isBlank()) title = string(thread, "preview", "未命名会话");
                var item = new JsonObject();
                item.addProperty("id", id);
                item.addProperty("title", title);
                item.addProperty("favorite", CodexSettingsState.getInstance().isFavorite(id));
                if (thread.has("updatedAt")) item.addProperty("time", Instant.ofEpochSecond(thread.get("updatedAt").getAsLong()).atZone(ZoneId.systemDefault()).format(HISTORY_TIME));
                items.add(item);
            }
            var event = event("history");
            event.add("items", items);
            sendEvent(event);
        }).exceptionally(error -> null);
    }

    private void openThread(String threadId) {
        if (busy || threadId.isBlank() || Objects.equals(currentThreadId, threadId)) return;
        var sessionId = activeSessionId;
        codex.resumeThread(threadId).thenAccept(result -> {
            activateSession(sessionId);
            renderThread(result);
        }).exceptionally(error -> {
            activateSession(sessionId);
            asyncError("无法打开历史会话", error);
            return null;
        });
    }

    private void renderThread(JsonObject result) {
        var thread = result.getAsJsonObject("thread");
        if (thread == null) return;
        currentThreadId = string(thread, "id", null);
        currentTurnId = null;
        currentTitle = string(thread, "name", "");
        if (currentTitle.isBlank()) currentTitle = string(thread, "preview", "Codex 会话");
        clearConversation();
        for (var turnElement : array(thread, "turns")) {
            for (var itemElement : array(turnElement.getAsJsonObject(), "items")) renderCompletedItem(itemElement.getAsJsonObject());
        }
        publishThread();
    }

    private void newConversation() {
        newConversation("", false);
    }

    private void newConversation(String requestedTitle, boolean skipConfirmation) {
        if (busy) return;
        var settings = CodexSettingsState.getInstance().getState();
        // 命令入口未完成前端确认时，使用原生确认框保护已有会话。
        if (!skipConfirmation && settings.newSessionConfirmEnabled && !transcript.isEmpty()
            && Messages.showYesNoDialog(project, "当前会话已有消息，确定要新建会话吗？", "新建会话", "新建", "取消", Messages.getQuestionIcon()) != Messages.YES) {
            return;
        }
        // 复用页签开启新对话时，旧回合的修改视为用户已确认，不再参与后续冲突判断。
        confirmedSessionIds.remove(activeSessionId);
        changeService.confirmSession(activeSessionId);
        // 新建会话时重置线程和输入上下文，避免旧附件或草稿带入新对话。
        currentThreadId = null;
        currentTurnId = null;
        currentProvider = provider(settings.activeProvider);
        var activeProvider = CodexSettingsState.getInstance().activeProvider(currentProvider);
        currentProviderProfileId = activeProvider.id;
        currentProviderRevision = activeProvider.revision;
        currentTitle = requestedTitle == null || requestedTitle.isBlank() ? "新会话" : requestedTitle.trim();
        pendingUserBody = null;
        pendingUserMessageCount = 0;
        queuedInputs = new ArrayList<>();
        attachments = new ArrayList<>();
        fileReferences = new ArrayList<>();
        clearConversation();
        publishAttachments();
        publishFileReferences();
        publishThread();
    }

    private void closeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        // 页签关闭代表用户确认保留该会话的工作区修改，清理其待处理事务。
        confirmedSessionIds.add(sessionId);
        changeService.confirmSession(sessionId);
    }

    private void clearConversation() {
        transcript.clear();
        pendingUserBody = null;
        pendingUserMessageCount = 0;
        usageUsedTokens = 0;
        usageMaxTokens = 0;
        var event = event("clear");
        event.addProperty("title", currentTitle);
        if (currentThreadId != null) event.addProperty("threadId", currentThreadId);
        sendEvent(event);
    }

    private void renameCurrentThread() {
        var name = Messages.showInputDialog(project, "输入新的会话名称：", "重命名会话", Messages.getQuestionIcon(), currentTitle, null);
        if (name == null || name.isBlank()) return;
        var sessionId = activeSessionId;
        currentTitle = name.trim();
        // 空白页签和 Claude 会话名称由插件本地维护，Codex 会话再同步到 app-server。
        if (currentThreadId == null || Objects.equals(currentProvider, "claude")) {
            publishThread();
            return;
        }
        codex.setThreadName(currentThreadId, currentTitle).thenRun(() -> {
            activateSession(sessionId);
            publishThread();
            loadHistory("");
        }).exceptionally(error -> {
            activateSession(sessionId);
            asyncError("无法重命名会话", error);
            return null;
        });
    }

    private void exportConversation() {
        if (transcript.isEmpty()) return;
        var chooser = new JFileChooser(changeService.getRoot().toFile());
        chooser.setDialogTitle("导出 Codex 会话");
        chooser.setSelectedFile(new java.io.File("codex-conversation.md"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        var markdown = new StringBuilder("# ").append(currentTitle).append("\n\n");
        transcript.forEach(entry -> markdown.append("## ").append(entry.title()).append("\n\n").append(entry.body()).append("\n\n"));
        try {
            Files.writeString(chooser.getSelectedFile().toPath(), markdown, StandardCharsets.UTF_8);
            toast("会话已导出");
        } catch (IOException error) {
            Messages.showErrorDialog(project, error.getMessage(), "导出失败");
        }
    }

    private void chooseAttachment(boolean image) {
        var chooser = new JFileChooser(changeService.getRoot().toFile());
        chooser.setMultiSelectionEnabled(true);
        chooser.setDialogTitle(image ? "选择图片" : "选择引用文件");
        if (image) chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("图片", "png", "jpg", "jpeg", "gif", "webp"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        for (var file : chooser.getSelectedFiles()) {
            var path = file.toPath();
            var kind = image || isImageAttachment(path) ? Attachment.Kind.IMAGE : Attachment.Kind.FILE;
            attachments.add(new Attachment(kind, file.getName(), path));
        }
        publishAttachments();
    }

    private boolean isImageAttachment(Path path) {
        var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
            || name.endsWith(".gif") || name.endsWith(".webp");
    }

    private Path droppedPath(String rawPath) {
        try {
            var value = Objects.requireNonNullElse(rawPath, "").trim();
            if (value.startsWith("@")) value = value.substring(1).trim();
            var path = value.startsWith("file:") ? Path.of(URI.create(value)) : Path.of(value);
            if (path.isAbsolute()) return path.normalize();
            var root = changeService.getRoot();
            return root == null ? path.toAbsolutePath().normalize() : root.resolve(path).normalize();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private List<Path> droppedTextPaths(String text) {
        if (text == null || text.isBlank()) return List.of();
        return text.lines().map(this::droppedPath).filter(Objects::nonNull).toList();
    }

    private void installProjectViewDropTarget() {
        // Project View 使用 IDE 内部 DnD 对象，通常不会进入 Chromium 的 Files 列表。
        DnDSupport.createBuilder(browser.getComponent())
            .disableAsSource()
            .setTargetChecker(event -> {
                var canDrop = isInsideComposer(event) && !nativeDroppedFiles(event).isEmpty();
                event.setDropPossible(canDrop, canDrop ? "添加文件引用" : "请拖到聊天输入框");
                publishNativeDragState(canDrop);
                return true;
            })
            .setDropHandler(event -> {
                // 原生项目树拖放不经过浏览器 drop 事件，先把释放坐标交给输入框定位逻辑。
                publishNativeDropPosition(event);
                publishNativeDragState(false);
                addDroppedInputs(nativeDroppedFiles(event));
            })
            .setCleanUpOnLeaveCallback(() -> publishNativeDragState(false))
            .setDisposableParent(this)
            .install();
    }

    private List<Path> nativeDroppedFiles(DnDEvent event) {
        var paths = new LinkedHashSet<Path>();
        for (var file : FileCopyPasteUtil.getFileListFromAttachedObject(event.getAttachedObject())) {
            paths.add(file.toPath());
        }
        var transferred = FileCopyPasteUtil.getFiles(event);
        if (transferred != null) paths.addAll(transferred);
        return paths.stream()
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .filter(path -> Files.isRegularFile(path) || Files.isDirectory(path))
            .toList();
    }

    private boolean isInsideComposer(DnDEvent event) {
        var region = composerDropRegion;
        var component = browser.getComponent();
        if (region == null || component.getWidth() <= 0 || component.getHeight() <= 0) return false;
        var point = event.getPointOn(component);
        var x = point.getX() / component.getWidth();
        var y = point.getY() / component.getHeight();
        return region.contains(x, y);
    }

    private void updateComposerDropRegion(JsonObject request) {
        var left = doubleValue(request, "left");
        var top = doubleValue(request, "top");
        var right = doubleValue(request, "right");
        var bottom = doubleValue(request, "bottom");
        composerDropRegion = left >= 0 && top >= 0 && right > left && bottom > top
            ? new ComposerDropRegion(left, top, right, bottom)
            : null;
    }

    private void publishNativeDragState(boolean active) {
        if (nativeDragActive == active) return;
        nativeDragActive = active;
        var event = event("nativeDrag");
        event.addProperty("active", active);
        sendEvent(event);
    }

    private void publishNativeDropPosition(DnDEvent dragEvent) {
        var component = browser.getComponent();
        if (component.getWidth() <= 0 || component.getHeight() <= 0) return;
        var point = dragEvent.getPointOn(component);
        var event = event("nativeDrop");
        event.addProperty("x", point.getX() / component.getWidth());
        event.addProperty("y", point.getY() / component.getHeight());
        sendEvent(event);
    }

    private void addDroppedInputs() {
        var droppedPaths = pendingDraggedPaths;
        pendingDraggedPaths = List.of();
        addDroppedInputs(droppedPaths);
    }

    private void addDroppedInputs(List<Path> droppedPaths) {
        publishNativeDragState(false);
        var addedReferences = 0;
        var addedImages = 0;
        // 图片保留附件行为，普通文件和目录改为可移除的 Codex 文件引用。
        for (var path : droppedPaths) {
            var normalized = path.toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized) && !Files.isDirectory(normalized)) continue;
            // 图片继续作为附件插入，供发送前预览和移除。
            if (Files.isRegularFile(normalized) && isImageAttachment(normalized)) {
                if (attachments.stream().anyMatch(item -> item.path().toAbsolutePath().normalize().equals(normalized))) continue;
                attachments.add(new Attachment(Attachment.Kind.IMAGE, normalized.getFileName().toString(), normalized));
                addedImages++;
                continue;
            }
            // 普通文件或目录作为引用标签插入，重复路径保持幂等。
            if (fileReferences.stream().anyMatch(item -> item.path().equals(normalized))) continue;
            fileReferences.add(FileReference.fromPath(normalized));
            addedReferences++;
        }
        // 拖拽结果已经通过输入框标签或附件列表展示，不再弹出遮挡输入框的结果提示。
        if (addedReferences == 0 && addedImages == 0) {
            return;
        }
        if (addedReferences > 0) publishFileReferences();
        if (addedImages > 0) publishAttachments();
    }

    private void publishProjectFiles(String query, long requestId) {
        CompletableFuture.supplyAsync(
            () -> ProjectFileSearch.filter(projectFileCatalog(), query, 60),
            AppExecutorUtil.getAppExecutorService()
        ).thenAccept(files -> {
            var event = event("projectFiles");
            event.addProperty("requestId", requestId);
            var items = new JsonArray();
            for (var file : files) {
                var item = new JsonObject();
                item.addProperty("path", file.path());
                item.addProperty("name", file.name());
                items.add(item);
            }
            event.add("items", items);
            sendEvent(event);
        });
    }

    private synchronized List<ProjectFileSearch.Candidate> projectFileCatalog() {
        var now = System.currentTimeMillis();
        if (now - projectFileCatalogLoadedAt < PROJECT_FILE_CACHE_MILLIS) return projectFileCatalog;
        projectFileCatalog = ProjectFileSearch.list(changeService.getRoot());
        projectFileCatalogLoadedAt = now;
        return projectFileCatalog;
    }

    private void publishSkills(boolean forceReload) {
        publishSkills(forceReload, forceReload);
    }

    private void publishSkills(boolean forceReload, boolean notify) {
        // CLI 尚未完成 initialize 时不能发送请求，等待连接事件重新加载。
        if (!codex.isConnected()) return;
        // 读取当前工作区的原生 Skills，并同步到设置页供浏览和启停。
        codex.listSkills(forceReload).thenAccept(result -> ApplicationManager.getApplication().invokeLater(() -> {
            var items = new JsonArray();
            var errors = new JsonArray();
            for (var entry : array(result, "data")) {
                var group = entry.getAsJsonObject();
                for (var error : array(group, "errors")) errors.add(error.deepCopy());
                for (var skill : array(group, "skills")) {
                    var object = skill.getAsJsonObject();
                    var item = new JsonObject();
                    item.addProperty("name", string(object, "name", "Skill"));
                    item.addProperty("path", string(object, "path", ""));
                    item.addProperty("description", string(object, "description", string(object, "shortDescription", "")));
                    item.addProperty("enabled", !object.has("enabled") || object.get("enabled").getAsBoolean());
                    item.addProperty("scope", string(object, "scope", "repo"));
                    if (object.has("interface") && object.get("interface").isJsonObject()) item.add("interface", object.getAsJsonObject("interface").deepCopy());
                    if (object.has("dependencies") && object.get("dependencies").isJsonObject()) item.add("dependencies", object.getAsJsonObject("dependencies").deepCopy());
                    items.add(item);
                }
            }
            var event = event("skills");
            event.add("items", items);
            event.add("errors", errors);
            sendEvent(event);
            if (notify) toast(items.size() == 0 ? "当前工作区没有可用的 Skill" : "Skills 已重新加载");
        })).exceptionally(error -> {
            asyncError("无法读取 Skills", error);
            return null;
        });
    }

    private void setSkillEnabled(JsonObject request) {
        var path = string(request, "path", "");
        var enabled = request.has("enabled") && request.get("enabled").getAsBoolean();
        if (path.isBlank()) {
            toast("该 Skill 缺少有效路径");
            return;
        }
        codex.setSkillEnabled(path, enabled).thenAccept(result -> ApplicationManager.getApplication().invokeLater(() -> {
            var effectiveEnabled = result.has("effectiveEnabled") && result.get("effectiveEnabled").getAsBoolean();
            var event = event("skillEnabled");
            event.addProperty("path", path);
            event.addProperty("enabled", effectiveEnabled);
            sendEvent(event);
            toast(effectiveEnabled ? "Skill 已启用，Codex 可自动调用" : "Skill 已停用");
            publishSkills(true, false);
        })).exceptionally(error -> {
            publishSkills(true, false);
            asyncError(enabled ? "无法启用 Skill" : "无法停用 Skill", error);
            return null;
        });
    }

    private void importSkill(String scope) {
        var chooser = new JFileChooser(changeService.getRoot().toFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle(Objects.equals(scope, "user") ? "选择要导入的用户 Skill 目录" : "选择要导入的项目 Skill 目录");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        var source = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        if (!Files.isRegularFile(source.resolve("SKILL.md"))) {
            Messages.showErrorDialog(project, "所选目录中没有 SKILL.md。", "导入 Skill 失败");
            return;
        }

        var parent = Objects.equals(scope, "user")
            ? userSkillsDirectory()
            : changeService.getRoot().resolve(".codex").resolve("skills");
        parent = parent.toAbsolutePath().normalize();
        var target = parent.resolve(source.getFileName()).normalize();
        if (!target.startsWith(parent) || Objects.equals(source, target)) {
            toast(Objects.equals(source, target) ? "该 Skill 已位于目标目录" : "Skill 目标路径无效");
            return;
        }
        if (Files.exists(target)) {
            Messages.showErrorDialog(project, "目标目录已存在：\n" + target, "导入 Skill 失败");
            return;
        }

        try {
            Files.createDirectories(parent);
            copySkillDirectory(source, target);
            LocalFileSystem.getInstance().refreshNioFiles(List.of(target), true, true, null);
            toast(Objects.equals(scope, "user") ? "用户 Skill 已导入" : "项目 Skill 已导入");
            publishSkills(true, false);
        } catch (IOException error) {
            deleteImportedDirectory(target);
            Messages.showErrorDialog(project, error.getMessage(), "导入 Skill 失败");
        }
    }

    private Path userSkillsDirectory() {
        var configuredHome = System.getenv("CODEX_HOME");
        var codexHome = configuredHome == null || configuredHome.isBlank()
            ? Path.of(System.getProperty("user.home"), ".codex")
            : Path.of(configuredHome);
        return codexHome.resolve("skills");
    }

    private void copySkillDirectory(Path source, Path target) throws IOException {
        try (var entries = Files.walk(source)) {
            for (var iterator = entries.iterator(); iterator.hasNext(); ) {
                var path = iterator.next();
                var destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
    }

    private void deleteImportedDirectory(Path target) {
        if (!Files.exists(target)) return;
        try (var entries = Files.walk(target)) {
            for (var path : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理对象只可能是本次导入创建的新目录，失败时保留现场供用户检查。
        }
    }

    private void openSkill(JsonObject request) {
        var rawPath = string(request, "path", "").trim();
        if (rawPath.isBlank()) {
            toast("该 Skill 缺少有效路径");
            return;
        }
        try {
            var path = Path.of(rawPath);
            var target = Files.isDirectory(path) ? path.resolve("SKILL.md") : path;
            var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target);
            if (file == null) {
                toast("无法打开 Skill 源文件");
                return;
            }
            new OpenFileDescriptor(project, file).navigate(true);
        } catch (RuntimeException error) {
            Messages.showErrorDialog(project, error.getMessage(), "打开 Skill 失败");
        }
    }

    private void openFileLocation(JsonObject request) {
        var rawPath = string(request, "path", "").trim();
        if (rawPath.isBlank()) return;
        var line = Math.max(1, integer(request, "line"));
        var column = Math.max(1, integer(request, "column"));
        try {
            // 相对路径以当前项目为基准，绝对路径则直接使用 Codex 返回的位置。
            var path = Path.of(normalizeReportedFilePath(rawPath));
            if (!path.isAbsolute() && project.getBasePath() != null) path = Path.of(project.getBasePath()).resolve(path);
            // 先刷新本地文件，再按用户可见的行列位置打开编辑器。
            var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path.normalize());
            if (file == null) {
                toast("无法打开文件：" + rawPath);
                return;
            }
            new OpenFileDescriptor(project, file, line - 1, column - 1).navigate(true);
        } catch (RuntimeException error) {
            // 路径格式无效时给出提示，避免点击链接导致界面线程异常。
            toast("无法打开文件：" + rawPath);
        }
    }

    static String normalizeReportedFilePath(String rawPath) {
        var path = rawPath == null ? "" : rawPath.trim();
        // 浏览器 URL 形式可能给 Windows 盘符补一个前导斜杠，例如 /E:/src/Main.cpp。
        if (path.matches("^/[A-Za-z]:[\\\\/].*")) path = path.substring(1);
        var result = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            var current = path.charAt(i);
            if (current == '\\' && i + 1 < path.length() && isMarkdownEscape(path.charAt(i + 1))) {
                result.append(path.charAt(++i));
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static boolean isMarkdownEscape(char value) {
        return "`*_{}[]()#+.!|>~:-".indexOf(value) >= 0;
    }

    private void removeAttachment(int index) {
        if (index < 0 || index >= attachments.size()) return;
        attachments.remove(index);
        publishAttachments();
    }

    private void removeFileReference(String id) {
        if (id.isBlank()) return;
        if (!fileReferences.removeIf(reference -> reference.id().equals(id))) return;
        publishFileReferences();
    }

    private void removeFileReferences(JsonObject request) {
        var ids = request.getAsJsonArray("ids");
        if (ids == null || ids.isEmpty()) return;
        var removed = false;
        for (var value : ids) removed |= fileReferences.removeIf(reference -> reference.id().equals(value.getAsString()));
        if (removed) publishFileReferences();
    }

    private void addFileReferences(JsonObject request) {
        var paths = request.getAsJsonArray("paths");
        if (paths == null) return;
        var added = 0;
        for (var value : paths) {
            var path = droppedPath(value.getAsString());
            if (path == null || (!Files.isRegularFile(path) && !Files.isDirectory(path))) continue;
            fileReferences.add(FileReference.fromPath(path.toAbsolutePath().normalize()));
            added++;
        }
        if (added > 0) publishFileReferences();
    }

    private void reorderFileReferences(JsonObject request) {
        var ids = request.getAsJsonArray("ids");
        if (ids == null || ids.size() != fileReferences.size()) return;
        var remaining = new ArrayList<>(fileReferences);
        var ordered = new ArrayList<FileReference>();
        for (var value : ids) {
            var id = value.getAsString();
            var index = -1;
            for (var i = 0; i < remaining.size(); i++) {
                if (remaining.get(i).id().equals(id)) {
                    index = i;
                    break;
                }
            }
            if (index < 0) return;
            ordered.add(remaining.remove(index));
        }
        fileReferences.clear();
        fileReferences.addAll(ordered);
    }

    private JsonArray attachmentsJson() {
        var items = new JsonArray();
        for (var attachment : attachments) {
            var item = new JsonObject();
            item.addProperty("kind", attachment.kind().name());
            item.addProperty("name", attachment.name());
            item.addProperty("path", attachment.path().toString());
            items.add(item);
        }
        return items;
    }

    private void publishAttachments() {
        var event = event("attachments");
        event.add("items", attachmentsJson());
        sendEvent(event);
    }

    private JsonArray fileReferencesJson() {
        var items = new JsonArray();
        for (var reference : fileReferences) {
            var item = new JsonObject();
            item.addProperty("id", reference.id());
            item.addProperty("name", reference.name());
            item.addProperty("path", reference.path().toString());
            item.addProperty("directory", reference.directory());
            items.add(item);
        }
        return items;
    }

    private void publishFileReferences() {
        var event = event("fileReferences");
        event.add("items", fileReferencesJson());
        sendEvent(event);
    }

    private void publishChanges(List<ChangeEntry> changes) {
        publishChanges(activeSessionId, changes);
    }

    private void publishChanges(String sessionId, List<ChangeEntry> changes) {
        var items = new JsonArray();
        for (var change : changes) {
            var item = new JsonObject();
            item.addProperty("path", change.displayName(changeService.getRoot()).replace('\\', '/'));
            item.addProperty("kind", change.kind().name());
            item.addProperty("reversible", change.reversible());
            item.addProperty("diff", change.unifiedDiff());
            items.add(item);
        }
        var event = event("changes");
        event.addProperty("sessionId", sessionId);
        event.add("items", items);
        sendEvent(event);
    }

    private ChangeEntry changeAt(int index) {
        var changes = changeService.getChanges(activeSessionId);
        return index >= 0 && index < changes.size() ? changes.get(index) : null;
    }

    private void acceptAllChanges() {
        // 空列表直接结束，避免把无意义的全量扫描提交到界面线程。
        if (changeService.getChanges(activeSessionId).isEmpty()) return;
        changeService.acceptAll(activeSessionId);
    }

    private void acceptChange(int index) {
        var change = changeAt(index);
        if (change != null) changeService.accept(activeSessionId, change);
    }

    private void revertChange(int index) {
        var change = changeAt(index);
        if (change == null) return;
        if (Messages.showYesNoDialog(project, "撤销 Codex 对该文件的全部修改？\n\n" + change.path(), "撤销文件修改", "撤销", "取消", Messages.getWarningIcon()) != Messages.YES) return;
        try {
            changeService.revert(activeSessionId, change);
        } catch (IOException error) {
            Messages.showErrorDialog(project, error.getMessage(), "无法撤销修改");
        }
    }

    private void revertAllChanges() {
        if (changeService.getChanges(activeSessionId).isEmpty()) return;
        if (Messages.showYesNoDialog(project, "撤销当前回合捕获的全部文件修改？", "撤销全部修改", "全部撤销", "取消", Messages.getWarningIcon()) != Messages.YES) return;
        try {
            changeService.revertAll(activeSessionId);
        } catch (IOException error) {
            Messages.showErrorDialog(project, error.getMessage(), "部分文件无法撤销");
        }
    }

    private void openChange(int index) {
        var change = changeAt(index);
        if (change == null) return;
        var before = change.beforeContent() == null ? "" : new String(change.beforeContent(), StandardCharsets.UTF_8);
        var factory = DiffContentFactory.getInstance();
        // 刷新并定位源文件，让 Diff 读取用户当前看到的最新内容。
        var sourceFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(change.path());
        var beforeContent = sourceFile == null ? factory.create(project, before) : factory.create(project, before, sourceFile);
        var sourceDocument = sourceFile == null ? null : FileDocumentManager.getInstance().getDocument(sourceFile);
        // 文本源文件绑定真实文档，Diff 中的编辑会直接落到当前文件。
        var afterContent = sourceDocument == null
            ? factory.create(project, change.afterContent() == null ? "" : new String(change.afterContent(), StandardCharsets.UTF_8))
            : factory.create(project, sourceDocument, sourceFile);
        var request = new SimpleDiffRequest(
            (Objects.equals(currentProvider, "claude") ? "Claude Code 修改 · " : "Codex 修改 · ")
                + change.displayName(changeService.getRoot()),
            beforeContent,
            afterContent,
            "AI 修改前（只读）",
            "当前文件（可编辑）"
        );
        // 只读保护 AI 修改前快照，允许右侧真实文件文档接收编辑。
        request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS, new boolean[]{true, false});
        DiffManager.getInstance().showDiff(project, request);
    }

    private void searchConversation() {
        var query = Messages.showInputDialog(project, "输入要在当前会话中查找的文字：", "搜索会话", Messages.getQuestionIcon());
        if (query == null || query.isBlank()) return;
        var count = transcript.stream().filter(entry -> entry.body().toLowerCase().contains(query.toLowerCase())).count();
        toast("找到 " + count + " 条匹配消息");
    }

    private void updateSetting(String key, String value) {
        var settings = CodexSettingsState.getInstance().getState();
        // 模型值按供应商分别保存，避免切换后把另一套模型名称带入 CLI。
        switch (key) {
            case "model" -> {
                if (Objects.equals(currentProvider, "claude")) settings.claudeModel = value;
                else settings.model = value;
            }
            case "effort" -> settings.reasoningEffort = value;
            case "serviceTier" -> settings.serviceTier = Objects.equals(value, "fast") ? "fast" : "standard";
            case "approval" -> settings.approvalPolicy = value;
            case "sandbox" -> settings.sandboxMode = value;
            default -> {
            }
        }
        publishSettings();
    }

    private void updateBehaviorSetting(JsonObject request) {
        var settings = CodexSettingsState.getInstance().getState();
        var key = string(request, "key", "");
        switch (key) {
            case "sendShortcut" -> settings.sendShortcut = Objects.equals(string(request, "value", "enter"), "cmdEnter") ? "cmdEnter" : "enter";
            case "permissionDialogTimeoutSeconds" -> settings.permissionDialogTimeoutSeconds = Math.max(30, Math.min(3600, integer(request, "value")));
            case "streamResponses" -> settings.streamResponses = bool(request, "value", true);
            case "showThinking" -> settings.showThinking = bool(request, "value", true);
            case "sendOpenedFilePath" -> settings.sendOpenedFilePath = bool(request, "value", false);
            case "diffExpandedByDefault" -> settings.diffExpandedByDefault = bool(request, "value", false);
            case "newSessionConfirmEnabled" -> settings.newSessionConfirmEnabled = bool(request, "value", true);
            case "askUserQuestionNotificationEnabled" -> settings.askUserQuestionNotificationEnabled = bool(request, "value", false);
            case "askUserQuestionSoundEnabled" -> settings.askUserQuestionSoundEnabled = bool(request, "value", false);
            case "taskCompletionNotificationEnabled" -> settings.taskCompletionNotificationEnabled = bool(request, "value", false);
            case "taskCompletionSoundEnabled" -> settings.taskCompletionSoundEnabled = bool(request, "value", false);
            case "systemNotificationOnlyWhenUnfocused" -> settings.systemNotificationOnlyWhenUnfocused = bool(request, "value", false);
            case "soundOnlyWhenUnfocused" -> settings.soundOnlyWhenUnfocused = bool(request, "value", false);
            case "notificationSound" -> {
                var sound = string(request, "value", "default");
                settings.notificationSound = NotificationSoundPlayer.BUILT_IN_SOUND_IDS.contains(sound) || Objects.equals(sound, "custom") ? sound : "default";
            }
            case "customSoundPath" -> settings.customSoundPath = string(request, "value", "").trim();
            default -> {
                return;
            }
        }
        publishSettings();
    }

    private EditorFileContext currentEditorContext() {
        var selectedFiles = FileEditorManager.getInstance(project).getSelectedFiles();
        if (selectedFiles.length == 0) return null;
        var selectedFile = selectedFiles[0];
        String displayPath;
        try {
            var file = Path.of(selectedFile.getPath()).toAbsolutePath().normalize();
            var root = changeService.getRoot().toAbsolutePath().normalize();
            displayPath = file.startsWith(root) ? root.relativize(file).toString() : file.toString();
        } catch (RuntimeException ignored) {
            displayPath = selectedFile.getPath();
        }

        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null || FileDocumentManager.getInstance().getDocument(selectedFile) != editor.getDocument()) {
            return new EditorFileContext(displayPath, 0, 0, "");
        }
        var selection = editor.getSelectionModel();
        var selectedText = selection.getSelectedText();
        if (selectedText == null || selectedText.isEmpty()) return new EditorFileContext(displayPath, 0, 0, "");
        var document = editor.getDocument();
        var startLine = document.getLineNumber(selection.getSelectionStart()) + 1;
        var endOffset = Math.max(selection.getSelectionStart(), selection.getSelectionEnd() - 1);
        var endLine = document.getLineNumber(endOffset) + 1;
        return new EditorFileContext(displayPath, startLine, endLine, selectedText);
    }

    private void publishFileContext() {
        var settings = CodexSettingsState.getInstance().getState();
        var event = event("fileContext");
        addFileContext(event, settings.sendOpenedFilePath ? currentEditorContext() : null);
        sendEvent(event);
    }

    private void addFileContext(JsonObject target, EditorFileContext context) {
        target.addProperty("path", context == null ? "" : context.path());
        target.addProperty("startLine", context == null ? 0 : context.startLine());
        target.addProperty("endLine", context == null ? 0 : context.endLine());
    }

    private void browseNotificationSound() {
        var chooser = new JFileChooser(changeService.getRoot().toFile());
        chooser.setDialogTitle("选择自定义提示音");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("音频文件（WAV、MP3、AIFF）", "wav", "mp3", "aif", "aiff"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        var settings = CodexSettingsState.getInstance().getState();
        settings.notificationSound = "custom";
        settings.customSoundPath = chooser.getSelectedFile().getAbsolutePath();
        publishSettings();
    }

    private void playConfiguredSound(boolean reportError) {
        var settings = CodexSettingsState.getInstance().getState();
        if (Objects.equals(settings.notificationSound, "custom") && settings.customSoundPath.isBlank()) {
            if (reportError) toast("请先选择自定义音频文件");
            return;
        }
        notificationSoundPlayer.play(settings.notificationSound, settings.customSoundPath).exceptionally(error -> {
            if (reportError) ApplicationManager.getApplication().invokeLater(() -> toast("提示音播放失败：" + errorMessage(error)));
            return null;
        });
    }

    private boolean isIdeFocused() {
        var frame = WindowManager.getInstance().getFrame(project);
        return frame != null && frame.isFocused();
    }

    private void notifyAttention(String title, String content, boolean notificationEnabled, boolean soundEnabled) {
        var settings = CodexSettingsState.getInstance().getState();
        var focused = isIdeFocused();
        if (notificationEnabled && (!settings.systemNotificationOnlyWhenUnfocused || !focused)) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Codex GUI Notifications")
                .createNotification(title, content, NotificationType.INFORMATION)
                .notify(project);
        }
        if (soundEnabled && (!settings.soundOnlyWhenUnfocused || !focused)) playConfiguredSound(false);
    }

    private void toggleStreaming() {
        var settings = CodexSettingsState.getInstance().getState();
        settings.streamResponses = !settings.streamResponses;
        publishSettings();
    }

    private void toggleThinking() {
        var settings = CodexSettingsState.getInstance().getState();
        settings.showThinking = !settings.showThinking;
        publishSettings();
    }

    private void saveInstructions(JsonObject request) {
        var settings = CodexSettingsState.getInstance().getState();
        settings.globalInstructions = string(request, "global", "").trim();
        settings.projectInstructions = string(request, "project", "").trim();
        CodexProjectSettingsState.getInstance(project).getState().projectInstructions = "";
        publishSettings();
        toast("指令已保存到用户级配置，将从下一个新会话开始生效");
    }

    private void savePrompt(JsonObject request) {
        // 同一入口处理新建和编辑，保持提示词库结构简单。
        var settings = CodexSettingsState.getInstance().getState();
        var id = string(request, "id", "").trim();
        var name = string(request, "name", "").trim();
        var description = string(request, "description", "").trim();
        var instructions = string(request, "instructions", "").trim();
        if (name.isBlank() || instructions.isBlank()) {
            toast("提示词名称和内容不能为空");
            return;
        }
        CodexSettingsState.PromptPreset target = null;
        for (var prompt : settings.prompts) if (Objects.equals(prompt.id, id)) target = prompt;
        if (target == null) {
            target = new CodexSettingsState.PromptPreset(UUID.randomUUID().toString(), name, description, instructions);
            settings.prompts.add(target);
        } else {
            target.name = name;
            target.description = description;
            target.instructions = instructions;
        }
        settings.activePromptId = target.id;
        publishSettings();
        toast("提示词已保存");
    }

    private void deletePrompt(String id) {
        // 删除后清理当前启用项，避免新会话继续引用已移除的提示词。
        var settings = CodexSettingsState.getInstance().getState();
        settings.prompts.removeIf(prompt -> Objects.equals(prompt.id, id));
        if (Objects.equals(settings.activePromptId, id)) settings.activePromptId = "";
        publishSettings();
    }

    private void selectPrompt(String id) {
        // 仅保存启用项，实际指令在创建新会话时合并。
        var settings = CodexSettingsState.getInstance().getState();
        settings.activePromptId = settings.prompts.stream().anyMatch(prompt -> Objects.equals(prompt.id, id)) ? id : "";
        publishSettings();
        toast(settings.activePromptId.isBlank() ? "已停用提示词" : "提示词将在下一个新会话生效");
    }

    private void saveAgent(JsonObject request) {
        var settings = CodexSettingsState.getInstance().getState();
        var id = string(request, "id", "").trim();
        var name = string(request, "name", "").trim();
        var instructions = string(request, "instructions", "").trim();
        if (name.isBlank() || instructions.isBlank()) {
            toast("Agent 名称和身份指令不能为空");
            return;
        }

        // 同一入口同时处理新建与编辑，保持 Agent 配置列表简单可维护。
        CodexSettingsState.AgentProfile target = null;
        for (var agent : settings.agents) {
            if (Objects.equals(agent.id, id)) target = agent;
        }
        if (target == null) {
            target = new CodexSettingsState.AgentProfile(UUID.randomUUID().toString(), name, instructions);
            settings.agents.add(target);
        } else {
            target.name = name;
            target.instructions = instructions;
        }
        settings.activeAgentId = target.id;
        publishSettings();
        toast("Agent 身份已保存");
    }

    private void deleteAgent(String id) {
        var settings = CodexSettingsState.getInstance().getState();
        settings.agents.removeIf(agent -> Objects.equals(agent.id, id));
        if (Objects.equals(settings.activeAgentId, id)) settings.activeAgentId = "";
        publishSettings();
    }

    private void selectAgent(String id) {
        var settings = CodexSettingsState.getInstance().getState();
        settings.activeAgentId = settings.agents.stream().anyMatch(agent -> Objects.equals(agent.id, id)) ? id : "";
        publishSettings();
        toast(settings.activeAgentId.isBlank() ? "已停用 Agent 身份" : "Agent 身份将在下一个新会话生效");
    }

    private JsonArray agentsJson(List<CodexSettingsState.AgentProfile> agents) {
        var result = new JsonArray();
        for (var agent : agents) {
            var item = new JsonObject();
            item.addProperty("id", agent.id);
            item.addProperty("name", agent.name);
            item.addProperty("instructions", agent.instructions);
            result.add(item);
        }
        return result;
    }

    private JsonArray promptsJson(List<CodexSettingsState.PromptPreset> prompts) {
        var result = new JsonArray();
        for (var prompt : prompts) {
            var item = new JsonObject();
            item.addProperty("id", prompt.id);
            item.addProperty("name", prompt.name);
            item.addProperty("description", prompt.description);
            item.addProperty("instructions", prompt.instructions);
            result.add(item);
        }
        return result;
    }

    private String developerInstructions() {
        var settings = CodexSettingsState.getInstance().getState();
        var instructions = new ArrayList<String>();
        if (!settings.globalInstructions.isBlank()) instructions.add(settings.globalInstructions.trim());
        var projectInstructions = sharedInstructions(settings);
        if (!projectInstructions.isBlank()) instructions.add(projectInstructions.trim());
        settings.agents.stream()
            .filter(agent -> Objects.equals(agent.id, settings.activeAgentId))
            .findFirst()
            .map(agent -> agent.instructions)
            .filter(value -> !value.isBlank())
            .ifPresent(value -> instructions.add(value.trim()));
        return String.join("\n\n", instructions);
    }

    private void publishSettings() {
        var settings = CodexSettingsState.getInstance().getState();
        var instructions = sharedInstructions(settings);
        var state = new JsonObject();
        state.addProperty("provider", currentProvider);
        state.addProperty("model", activeModel(settings));
        state.add("models", providerModels(settings));
        state.addProperty("effort", settings.reasoningEffort);
        state.addProperty("serviceTier", settings.serviceTier);
        state.addProperty("approval", settings.approvalPolicy);
        state.addProperty("sandbox", settings.sandboxMode);
        state.addProperty("streamResponses", settings.streamResponses);
        state.addProperty("showThinking", settings.showThinking);
        addBehaviorSettings(state, settings);
        state.addProperty("globalInstructions", settings.globalInstructions);
        state.addProperty("projectInstructions", instructions);
        state.addProperty("activePromptId", settings.activePromptId);
        state.add("prompts", promptsJson(settings.prompts));
        state.addProperty("activeAgentId", settings.activeAgentId);
        state.add("agents", agentsJson(settings.agents));
        var event = event("bootstrap");
        event.add("state", state);
        sendEvent(event);
    }

    private String sharedInstructions(CodexSettingsState.StateData settings) {
        if (!settings.projectInstructions.isBlank()) return settings.projectInstructions;

        var projectInstructions = CodexProjectSettingsState.getInstance(project).getState().projectInstructions;
        if (projectInstructions.isBlank()) return "";

        settings.projectInstructions = projectInstructions.trim();
        CodexProjectSettingsState.getInstance(project).getState().projectInstructions = "";
        return settings.projectInstructions;
    }

    private void compactCurrentThread() {
        if (currentThreadId == null || busy) return;
        if (Objects.equals(currentProvider, "claude")) {
            toast("Claude Code 由 CLI 自动管理上下文，无需手动压缩");
            return;
        }
        var sessionId = activeSessionId;
        codex.compactThread(currentThreadId).thenRun(() -> {
            activateSession(sessionId);
            toast("上下文压缩完成");
        }).exceptionally(error -> {
            activateSession(sessionId);
            asyncError("无法压缩上下文", error);
            return null;
        });
    }

    private void reviewCurrentChanges() {
        if (currentThreadId == null || busy) {
            toast("请先创建或打开一个会话");
            return;
        }
        if (Objects.equals(currentProvider, "claude")) {
            sendInput("请审查当前工作区中尚未提交的修改，并优先报告具体问题和风险。");
            return;
        }
        var sessionId = activeSessionId;
        setBusy(true);
        codex.reviewUncommittedChanges(currentThreadId).thenAccept(result -> {
            activateSession(sessionId);
            var turn = result.getAsJsonObject("turn");
            if (turn != null) currentTurnId = string(turn, "id", null);
            saveActiveSession();
        }).exceptionally(error -> {
            activateSession(sessionId);
            setBusy(false);
            asyncError("无法启动代码审查", error);
            return null;
        });
    }

    private void rollbackLastTurn() {
        if (currentThreadId == null || busy) return;
        if (Objects.equals(currentProvider, "claude")) {
            toast("Claude Code CLI 暂不支持从此界面回溯上一回合");
            return;
        }
        if (Messages.showYesNoDialog(project, "回退上一轮会话？工作区文件仍由修改面板单独管理。", "回退上一轮", "回退", "取消", Messages.getWarningIcon()) != Messages.YES) return;
        var sessionId = activeSessionId;
        codex.rollbackThread(currentThreadId).thenAccept(result -> {
            activateSession(sessionId);
            renderThread(result);
        }).exceptionally(error -> {
            activateSession(sessionId);
            asyncError("无法回退会话", error);
            return null;
        });
    }

    private void interruptCurrentTurn() {
        if (Objects.equals(currentProvider, "claude")) {
            if (!busy) return;
            // 先让待启动和运行中的回合同时失效，完成链会统一比较快照并启动下一条队列消息。
            claudeTurnGenerations.merge(activeSessionId, 1L, Long::sum);
            var processStarted = claude.interrupt(activeSessionId);
            // CLI 尚未启动时不存在 AI 修改，丢弃基线可避免把等待期间的用户编辑误记到修改栏。
            if (!processStarted) changeService.discardCapture(activeSessionId);
            return;
        }
        if (currentThreadId == null || currentTurnId == null) return;
        var sessionId = activeSessionId;
        codex.interruptTurn(currentThreadId, currentTurnId).exceptionally(error -> {
            activateSession(sessionId);
            asyncError("无法停止当前回合", error);
            return null;
        });
    }

    private void showMcpServers() {
        if (Objects.equals(currentProvider, "claude")) {
            toast("Claude Code 的 MCP 配置由 Claude CLI 管理");
            return;
        }
        codex.listMcpServers(currentThreadId).thenAccept(result -> ApplicationManager.getApplication().invokeLater(() -> {
            var text = new StringBuilder();
            for (var element : array(result, "data")) text.append("• ").append(string(element.getAsJsonObject(), "name", "未命名服务器")).append('\n');
            Messages.showInfoMessage(project, text.isEmpty() ? "当前没有已配置的 MCP 服务器。" : text.toString(), "Codex MCP 服务器");
        })).exceptionally(error -> {
            asyncError("无法读取 MCP 服务器", error);
            return null;
        });
    }

    private void publishMcpServers(boolean reload) {
        var operation = reload ? codex.reloadMcpServers() : CompletableFuture.completedFuture(new JsonObject());
        operation.thenCompose(ignored -> {
            var statuses = codex.listMcpServers(currentThreadId);
            var config = codex.readConfig(project.getBasePath()).exceptionally(error -> new JsonObject());
            return statuses.thenCombine(config, this::mergeMcpServers);
        }).thenAccept(items -> {
            var event = event("mcpServers");
            event.add("items", items);
            sendEvent(event);
            if (reload) toast("MCP 配置已重新加载");
        }).exceptionally(error -> {
            mcpLog("error", "", "读取 MCP 服务器失败：" + errorMessage(error));
            asyncError("无法读取 MCP 服务器", error);
            return null;
        });
    }

    private JsonArray mergeMcpServers(JsonObject statusResult, JsonObject configResult) {
        Map<String, JsonObject> merged = new LinkedHashMap<>();
        var configRoot = configResult.has("config") && configResult.get("config").isJsonObject()
            ? configResult.getAsJsonObject("config")
            : new JsonObject();
        var configured = configRoot.has("mcp_servers") && configRoot.get("mcp_servers").isJsonObject()
            ? configRoot.getAsJsonObject("mcp_servers")
            : new JsonObject();

        for (var entry : configured.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            var item = new JsonObject();
            var serverConfig = entry.getValue().getAsJsonObject().deepCopy();
            item.addProperty("name", entry.getKey());
            item.add("config", serverConfig);
            item.addProperty("enabled", !serverConfig.has("enabled") || serverConfig.get("enabled").getAsBoolean());
            item.addProperty("connectionStatus", "failed");
            item.add("tools", new JsonObject());
            item.add("resources", new JsonArray());
            item.add("resourceTemplates", new JsonArray());
            merged.put(entry.getKey(), item);
        }

        for (var element : array(statusResult, "data")) {
            if (!element.isJsonObject()) continue;
            var status = element.getAsJsonObject();
            var name = string(status, "name", "");
            if (name.isBlank()) continue;
            var item = merged.getOrDefault(name, new JsonObject());
            for (var entry : status.entrySet()) item.add(entry.getKey(), entry.getValue().deepCopy());
            item.addProperty("name", name);
            if (!item.has("config")) {
                item.add("config", new JsonObject());
                item.addProperty("managed", true);
            }
            if (!item.has("enabled")) item.addProperty("enabled", true);
            item.addProperty("connectionStatus", status.has("serverInfo") && status.get("serverInfo").isJsonObject() ? "connected" : "failed");
            merged.put(name, item);
        }

        var result = new JsonArray();
        merged.values().forEach(result::add);
        return result;
    }

    private void saveMcpServer(JsonObject request) {
        var name = string(request, "name", "").trim();
        var originalName = string(request, "originalName", "").trim();
        if (!validMcpName(name) || !request.has("config") || !request.get("config").isJsonObject()) {
            toast("MCP 配置无效：名称仅支持字母、数字、下划线和连字符");
            return;
        }
        var config = request.getAsJsonObject("config").deepCopy();
        if (string(config, "command", "").isBlank() && string(config, "url", "").isBlank()) {
            toast("MCP 配置必须包含 command 或 url");
            return;
        }
        var operation = codex.writeConfigValue("mcp_servers." + name, config);
        if (!originalName.isBlank() && !originalName.equals(name) && validMcpName(originalName)) {
            operation = operation.thenCompose(ignored -> codex.writeConfigValue("mcp_servers." + originalName, JsonNull.INSTANCE));
        }
        operation.thenCompose(ignored -> codex.reloadMcpServers()).thenRun(() -> {
            toast(originalName.isBlank() ? "MCP 服务器已添加" : "MCP 服务器配置已保存");
            publishMcpServers(false);
        }).exceptionally(error -> {
            asyncError("无法保存 MCP 服务器", error);
            return null;
        });
    }

    private void deleteMcpServer(JsonObject request) {
        var name = string(request, "name", "").trim();
        if (!validMcpName(name)) return;
        codex.writeConfigValue("mcp_servers." + name, JsonNull.INSTANCE)
            .thenCompose(ignored -> codex.reloadMcpServers())
            .thenRun(() -> {
                toast("MCP 服务器已删除");
                publishMcpServers(false);
            }).exceptionally(error -> {
                asyncError("无法删除 MCP 服务器", error);
                return null;
            });
    }

    private void setMcpServerEnabled(JsonObject request) {
        var name = string(request, "name", "").trim();
        if (!validMcpName(name) || !request.has("enabled")) return;
        var enabled = request.get("enabled").getAsBoolean();
        codex.writeConfigValue("mcp_servers." + name + ".enabled", new com.google.gson.JsonPrimitive(enabled))
            .thenCompose(ignored -> codex.reloadMcpServers())
            .thenRun(() -> {
                toast(enabled ? "MCP 服务器已启用" : "MCP 服务器已停用");
                publishMcpServers(false);
            }).exceptionally(error -> {
                asyncError("无法切换 MCP 服务器", error);
                return null;
            });
    }

    private boolean validMcpName(String name) {
        return name.matches("[A-Za-z0-9_-]{1,64}");
    }

    private void copyText(JsonObject request) {
        var text = string(request, "text", "");
        if (text.isEmpty() || text.length() > 1_000_000) return;
        CopyPasteManager.getInstance().setContents(new StringSelection(text));
    }

    private void loginMcpServer(JsonObject request) {
        var name = string(request, "name", "").trim();
        if (name.isBlank()) return;
        codex.loginMcpServer(name, currentThreadId).thenAccept(result -> ApplicationManager.getApplication().invokeLater(() -> {
            var authorizationUrl = string(result, "authorizationUrl", "");
            if (authorizationUrl.isBlank()) {
                toast("MCP 服务器未返回登录地址");
                return;
            }
            BrowserUtil.browse(authorizationUrl);
            toast("已在浏览器中打开 MCP 登录页面");
        })).exceptionally(error -> {
            asyncError("无法登录 MCP 服务器", error);
            return null;
        });
    }

    private void openMcpConfig() {
        var path = Path.of(System.getProperty("user.home"), ".codex", "config.toml");
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                Files.createFile(path);
            }
            var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            if (file == null) {
                toast("无法打开 Codex 配置文件");
                return;
            }
            new OpenFileDescriptor(project, file).navigate(true);
        } catch (IOException error) {
            Messages.showErrorDialog(project, error.getMessage(), "打开 MCP 配置失败");
        }
    }

    private void showUsage() {
        var account = codex.readAccount();
        var usage = codex.readUsage();
        var limits = codex.readRateLimits();
        CompletableFuture.allOf(account, usage, limits).thenRun(() -> ApplicationManager.getApplication().invokeLater(() -> {
            var accountData = account.join().getAsJsonObject("account");
            var summary = usage.join().getAsJsonObject("summary");
            var rateLimits = limits.join().getAsJsonObject("rateLimits");
            var primary = rateLimits == null ? null : rateLimits.getAsJsonObject("primary");
            var text = new StringBuilder();
            if (accountData != null) text.append("账户：").append(string(accountData, "email", string(accountData, "type", "OpenAI"))).append('\n');
            if (summary != null) text.append("累计 Token：").append(string(summary, "lifetimeTokens", "暂无")).append('\n');
            if (primary != null) text.append("当前窗口已使用：").append(string(primary, "usedPercent", "0")).append('%');
            Messages.showInfoMessage(project, text.isEmpty() ? "暂无用量数据" : text.toString(), "Codex 用量");
        })).exceptionally(error -> {
            asyncError("无法读取 Codex 用量", error);
            return null;
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        saveActiveSession();
        var event = event("busy");
        event.addProperty("busy", value);
        event.addProperty("queuedCount", queuedInputs.size());
        sendEvent(event);
    }

    private void publishQueueState() {
        var event = event("queue");
        event.addProperty("queuedCount", queuedInputs.size());
        sendEvent(event);
    }

    private void addEntry(ConversationEntry entry) {
        transcript.add(entry);
        publishEntry(entry);
    }

    private void publishEntry(ConversationEntry entry) {
        var event = event("message");
        event.add("entry", entryJson(entry));
        sendEvent(event);
    }

    private void replaceEntry(String itemId, ConversationEntry.Kind kind, String title, String body) {
        var replacement = new ConversationEntry(kind, title, body, itemId);
        for (int index = 0; index < transcript.size(); index++) {
            if (!Objects.equals(itemId, transcript.get(index).itemId())) continue;
            transcript.set(index, replacement);
            var event = event("replaceMessage");
            event.add("entry", entryJson(replacement));
            sendEvent(event);
            return;
        }
        transcript.add(replacement);
        publishEntry(replacement);
    }

    private void appendEntry(String itemId, ConversationEntry.Kind kind, String title, String delta) {
        for (var index = 0; index < transcript.size(); index++) {
            var current = transcript.get(index);
            if (!Objects.equals(itemId, current.itemId())) continue;
            transcript.set(index, new ConversationEntry(kind, title, current.body() + delta, itemId));
            var event = event("appendMessage");
            event.addProperty("itemId", itemId);
            event.addProperty("kind", kind.name().toLowerCase());
            event.addProperty("title", title);
            event.addProperty("delta", delta);
            sendEvent(event);
            return;
        }
        var entry = new ConversationEntry(kind, title, delta, itemId);
        transcript.add(entry);
        publishEntry(entry);
    }

    private JsonObject entryJson(ConversationEntry entry) {
        var json = new JsonObject();
        json.addProperty("kind", entry.kind().name().toLowerCase());
        json.addProperty("title", entry.title());
        json.addProperty("body", entry.body());
        if (!entry.fileReferencePaths().isEmpty()) {
            var references = new JsonArray();
            entry.fileReferencePaths().forEach(references::add);
            json.add("fileReferencePaths", references);
        }
        if (entry.itemId() != null) json.addProperty("itemId", entry.itemId());
        return json;
    }

    private void publishThread() {
        saveActiveSession();
        var event = event("thread");
        if (currentThreadId != null) event.addProperty("id", currentThreadId);
        event.addProperty("title", currentTitle);
        event.addProperty("provider", currentProvider);
        sendEvent(event);
    }

    private void toast(String message) {
        var event = event("toast");
        event.addProperty("message", message);
        sendEvent(event);
    }

    private void addBehaviorSettings(JsonObject state, CodexSettingsState.StateData settings) {
        state.addProperty("sendShortcut", settings.sendShortcut);
        state.addProperty("permissionDialogTimeoutSeconds", settings.permissionDialogTimeoutSeconds);
        state.addProperty("sendOpenedFilePath", settings.sendOpenedFilePath);
        state.addProperty("diffExpandedByDefault", settings.diffExpandedByDefault);
        state.addProperty("newSessionConfirmEnabled", settings.newSessionConfirmEnabled);
        state.addProperty("askUserQuestionNotificationEnabled", settings.askUserQuestionNotificationEnabled);
        state.addProperty("askUserQuestionSoundEnabled", settings.askUserQuestionSoundEnabled);
        state.addProperty("taskCompletionNotificationEnabled", settings.taskCompletionNotificationEnabled);
        state.addProperty("taskCompletionSoundEnabled", settings.taskCompletionSoundEnabled);
        state.addProperty("systemNotificationOnlyWhenUnfocused", settings.systemNotificationOnlyWhenUnfocused);
        state.addProperty("soundOnlyWhenUnfocused", settings.soundOnlyWhenUnfocused);
        state.addProperty("notificationSound", settings.notificationSound);
        state.addProperty("customSoundPath", settings.customSoundPath);
        var context = settings.sendOpenedFilePath ? currentEditorContext() : null;
        state.addProperty("activeFile", context == null ? "" : context.path());
        state.addProperty("activeFileStartLine", context == null ? 0 : context.startLine());
        state.addProperty("activeFileEndLine", context == null ? 0 : context.endLine());
        state.addProperty("usageUsedTokens", usageUsedTokens);
        state.addProperty("usageMaxTokens", usageMaxTokens);
        state.addProperty("usagePercentage", usageMaxTokens > 0
            ? Math.min(100.0, usageUsedTokens * 100.0 / usageMaxTokens)
            : 0.0);
    }

    private void mcpLog(String level, String serverName, String message) {
        var event = event("mcpLog");
        event.addProperty("level", level);
        event.addProperty("serverName", serverName);
        event.addProperty("message", message);
        sendEvent(event);
    }

    private String errorMessage(Throwable throwable) {
        var error = throwable instanceof CompletionException && throwable.getCause() != null ? throwable.getCause() : throwable;
        return Objects.toString(error.getMessage(), error.toString());
    }

    private JsonObject event(String type) {
        var event = new JsonObject();
        event.addProperty("type", type);
        event.addProperty("sessionId", activeSessionId);
        return event;
    }

    private void sendEvent(JsonObject event) {
        if (!pageReady || browser == null || browser.isDisposed()) return;
        var json = GSON.toJson(event);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!browser.isDisposed()) browser.getCefBrowser().executeJavaScript("window.CodexGui && window.CodexGui.receive(" + json + ");", "http://codex-gui.local/", 0);
        });
    }

    @Override
    public void onConnectionChanged(boolean connected, String detail) {
        if (Objects.equals(currentProvider, "codex")) {
            var event = event("connection");
            event.addProperty("connected", connected);
            event.addProperty("detail", detail);
            sendEvent(event);
        }
        publishProviderStatus();
        if (connected) {
            // 连接建立后补发启动阶段被跳过的基础数据请求。
            loadModels();
            publishSkills(false);
            loadHistory("");
            var previousSessionId = activeSessionId;
            for (var sessionId : List.copyOf(sessions.keySet())) {
                activateSession(sessionId);
                currentTurnId = null;
                setBusy(false);
                startNextQueuedInput(sessionId);
            }
            activateSession(previousSessionId);
        }
    }

    @Override
    public void onNotification(String method, JsonObject params) {
        var notificationThreadId = string(params, "threadId", "");
        if (notificationThreadId.isBlank() && params != null) {
            var turn = params.has("turn") && params.get("turn").isJsonObject() ? params.getAsJsonObject("turn") : null;
            notificationThreadId = string(turn, "threadId", "");
            if (notificationThreadId.isBlank() && params.has("item") && params.get("item").isJsonObject()) {
                notificationThreadId = string(params.getAsJsonObject("item"), "threadId", "");
            }
        }
        var routedSessionId = activateSessionForThread(notificationThreadId);
        // 缺少线程标识的通知只能归入当前会话；无法匹配线程的事件不得误投到当前页签。
        var eventSessionId = routedSessionId == null && notificationThreadId.isBlank() ? activeSessionId : routedSessionId;
        if (eventSessionId == null) return;
        switch (method) {
            case "turn/started" -> {
                var turn = params.getAsJsonObject("turn");
                if (turn != null) currentTurnId = string(turn, "id", currentTurnId);
                setBusy(true);
            }
            case "item/started" -> renderStartedItem(params.getAsJsonObject("item"));
            case "item/completed" -> renderCompletedItem(params.getAsJsonObject("item"));
            case "item/agentMessage/delta" -> appendDelta(params, ConversationEntry.Kind.ASSISTANT, "Codex");
            case "item/plan/delta" -> appendDelta(params, ConversationEntry.Kind.PLAN, "计划");
            case "item/commandExecution/outputDelta" -> appendDelta(params, ConversationEntry.Kind.COMMAND, "命令");
            case "item/fileChange/outputDelta" -> {
                // 旧版文本输出事件不携带结构化文件 diff，忽略它避免重复捕获。
            }
            case "item/fileChange/patchUpdated" -> updateFileChangeDiffs(eventSessionId, params);
            case "turn/diff/updated" -> changeService.updateServerDiff(eventSessionId, string(params, "diff", ""));
            case "thread/tokenUsage/updated" -> publishTokenUsage(params);
            case "skills/changed" -> publishSkills(false);
            case "mcpServer/startupStatus/updated" -> {
                var name = string(params, "name", string(params, "serverName", ""));
                mcpLog("info", name, "服务器启动状态已更新，正在重新读取连接状态");
                publishMcpServers(false);
            }
            case "mcpServer/oauthLogin/completed" -> {
                var name = string(params, "name", string(params, "serverName", ""));
                mcpLog("success", name, "OAuth 登录已完成，正在重新读取连接状态");
                publishMcpServers(false);
            }
            case "turn/completed" -> {
                var completedActiveTurn = busy;
                changeService.finishCaptureAsync(eventSessionId);
                currentTurnId = null;
                pendingUserBody = null;
                setBusy(false);
                loadHistory("");
                if (completedActiveTurn) {
                    var settings = CodexSettingsState.getInstance().getState();
                    notifyAttention("Codex 任务已完成", currentTitle, settings.taskCompletionNotificationEnabled, settings.taskCompletionSoundEnabled);
                }
                startNextQueuedInput(eventSessionId);
            }
            case "error" -> addEntry(new ConversationEntry(ConversationEntry.Kind.ERROR, "Codex 错误", params.toString(), null));
            case "warning", "configWarning", "deprecationNotice" -> addEntry(new ConversationEntry(ConversationEntry.Kind.NOTICE, "提示", string(params, "message", params.toString()), null));
            default -> {
            }
        }
    }

    private String activateSessionForThread(String threadId) {
        if (threadId == null || threadId.isBlank()) return null;
        for (var entry : sessions.entrySet()) {
            if (confirmedSessionIds.contains(entry.getKey())) continue;
            if (Objects.equals(entry.getValue().threadId, threadId)) {
                activateSession(entry.getKey());
                return entry.getKey();
            }
        }
        return null;
    }

    private void appendDelta(JsonObject params, ConversationEntry.Kind kind, String title) {
        if (!CodexSettingsState.getInstance().getState().streamResponses) return;
        var delta = string(params, "delta", "");
        if (delta.isEmpty()) return;
        var itemId = string(params, "itemId", fallbackId(kind));
        if (kind != ConversationEntry.Kind.COMMAND) {
            appendEntry(itemId, kind, title, delta);
            return;
        }
        // 命令输出可能高频到达，短暂合并后再刷新页面，避免连续重绘占满界面线程。
        var buffer = pendingCommandDeltas.computeIfAbsent(itemId, ignored -> new StringBuilder());
        synchronized (buffer) { buffer.append(delta); }
        if (scheduledCommandDeltas.add(itemId)) {
            CompletableFuture.delayedExecutor(40, TimeUnit.MILLISECONDS).execute(() -> flushCommandDelta(itemId, title));
        }
    }

    private void flushCommandDelta(String itemId, String title) {
        scheduledCommandDeltas.remove(itemId);
        var buffer = pendingCommandDeltas.remove(itemId);
        if (buffer == null) return;
        String delta;
        synchronized (buffer) { delta = buffer.toString(); }
        if (!delta.isEmpty() && !completedCommandItems.contains(itemId)) ApplicationManager.getApplication().invokeLater(
            () -> { if (!completedCommandItems.contains(itemId)) appendEntry(itemId, ConversationEntry.Kind.COMMAND, title, delta); });
    }

    private String fallbackId(ConversationEntry.Kind kind) {
        return (currentTurnId == null ? "turn" : currentTurnId) + ":" + kind;
    }

    private void renderStartedItem(JsonObject item) {
        if (item == null) return;
        var id = string(item, "id", fallbackId(ConversationEntry.Kind.NOTICE));
        var type = string(item, "type", "");
        if (!CodexSettingsState.getInstance().getState().streamResponses
            && !Objects.equals(type, "fileChange")) return;
        switch (type) {
            case "agentMessage" -> replaceEntry(id, ConversationEntry.Kind.ASSISTANT, "Codex", "");
            case "plan" -> replaceEntry(id, ConversationEntry.Kind.PLAN, "计划", "");
            case "commandExecution" -> {
                completedCommandItems.remove(id);
                replaceEntry(id, ConversationEntry.Kind.COMMAND, "命令", "$ " + string(item, "command", "") + "\n\n");
            }
            case "mcpToolCall" -> replaceEntry(id, ConversationEntry.Kind.MCP, "MCP 工具", string(item, "server", "") + " / " + string(item, "tool", ""));
            case "fileChange" -> updateFileChangeDiffs(activeSessionId, item);
            default -> {
            }
        }
    }

    private void renderCompletedItem(JsonObject item) {
        if (item == null) return;
        var id = string(item, "id", fallbackId(ConversationEntry.Kind.NOTICE));
        switch (string(item, "type", "")) {
            case "userMessage" -> {
                if (pendingUserMessageCount > 0) {
                    pendingUserMessageCount--;
                    pendingUserBody = null;
                } else {
                    var content = array(item, "content");
                    addEntry(new ConversationEntry(
                        ConversationEntry.Kind.USER,
                        "你",
                        userMessageText(content),
                        id,
                        userMessageReferencePaths(content)
                    ));
                }
            }
            case "agentMessage" -> replaceEntry(id, ConversationEntry.Kind.ASSISTANT, "Codex", string(item, "text", ""));
            case "plan" -> replaceEntry(id, ConversationEntry.Kind.PLAN, "计划", string(item, "text", ""));
            case "commandExecution" -> {
                completedCommandItems.add(id);
                scheduledCommandDeltas.remove(id);
                pendingCommandDeltas.remove(id);
                var body = new StringBuilder("$ ").append(string(item, "command", ""));
                var output = string(item, "aggregatedOutput", "");
                if (!output.isBlank()) body.append("\n\n").append(output);
                var hasExitCode = item.has("exitCode") && !item.get("exitCode").isJsonNull();
                var exitCode = hasExitCode ? item.get("exitCode").getAsInt() : 0;
                if (hasExitCode) body.append("\n\n退出码：").append(exitCode);
                var status = string(item, "status", "");
                if (status.isBlank()) status = !hasExitCode || exitCode == 0 ? "completed" : "failed";
                body.append("\n\n执行状态：").append(status);
                replaceEntry(id, ConversationEntry.Kind.COMMAND, "命令", body.toString());
            }
            case "mcpToolCall" -> replaceEntry(id, ConversationEntry.Kind.MCP, "MCP 工具", string(item, "server", "") + " / " + string(item, "tool", "") + "\n状态：" + string(item, "status", ""));
            case "fileChange" -> updateFileChangeDiffs(activeSessionId, item);
            case "contextCompaction" -> addEntry(new ConversationEntry(ConversationEntry.Kind.NOTICE, "上下文整理", "Codex 已压缩当前会话上下文。", id));
            default -> {
            }
        }
    }

    @Override
    public void onServerRequest(long requestId, String method, JsonObject params) {
        ApplicationManager.getApplication().invokeLater(() -> {
            activateSessionForThread(string(params, "threadId", ""));
            // “全自动”只自动处理审批请求，Codex 主动提问仍必须交给用户选择。
            if (Objects.equals(CodexSettingsState.getInstance().getState().approvalPolicy, "never")
                && autoApprove(requestId, method, params)) return;
            switch (method) {
                case "item/commandExecution/requestApproval" -> commandApproval(requestId, params);
                case "item/fileChange/requestApproval" -> fileApproval(requestId, params);
                case "item/tool/requestUserInput" -> userInputRequest(requestId, params);
                case "item/permissions/requestApproval" -> permissionsApproval(requestId, params);
                default -> {
                    var result = new JsonObject();
                    result.addProperty("decision", "decline");
                    codex.respondToServerRequest(requestId, result);
                }
            }
        });
    }

    private boolean autoApprove(long requestId, String method, JsonObject params) {
        if (Objects.equals(method, "item/commandExecution/requestApproval")
            || Objects.equals(method, "item/fileChange/requestApproval")) {
            var result = new JsonObject();
            result.addProperty("decision", "accept");
            codex.respondToServerRequest(requestId, result);
            return true;
        }
        if (Objects.equals(method, "item/permissions/requestApproval")) {
            var result = new JsonObject();
            result.add("permissions", params.has("permissions") ? params.getAsJsonObject("permissions") : new JsonObject());
            result.addProperty("scope", "turn");
            codex.respondToServerRequest(requestId, result);
            return true;
        }
        return false;
    }

    private void commandApproval(long requestId, JsonObject params) {
        // 审批窗口放到后台等待，避免阻塞 IDE 事件线程和聊天界面响应。
        CompletableFuture.supplyAsync(() -> showTimedDialog(
            "Codex 请求执行：\n\n" + string(params, "command", "未知命令"), "命令执行审批",
            new String[]{"允许一次", "本会话允许", "拒绝", "拒绝并停止"}), AppExecutorUtil.getAppExecutorService())
            .thenAccept(choice -> ApplicationManager.getApplication().invokeLater(() -> respondDecision(requestId, choice)));
    }

    private void publishTokenUsage(JsonObject params) {
        var notificationThreadId = string(params, "threadId", "");
        if (currentThreadId != null && !notificationThreadId.isBlank()
            && !Objects.equals(currentThreadId, notificationThreadId)) return;
        var tokenUsage = params != null && params.has("tokenUsage") && params.get("tokenUsage").isJsonObject()
            ? params.getAsJsonObject("tokenUsage") : null;
        var last = tokenUsage != null && tokenUsage.has("last") && tokenUsage.get("last").isJsonObject()
            ? tokenUsage.getAsJsonObject("last") : null;
        var used = longValue(last, "totalTokens");
        var maximum = longValue(tokenUsage, "modelContextWindow");
        if (used < 0 || maximum <= 0) return;

        // Codex 的 last 是当前上下文快照；total 是会话累计值，不能用于上下文环。
        usageUsedTokens = used;
        usageMaxTokens = maximum;
        var event = event("usage");
        event.addProperty("usedTokens", used);
        event.addProperty("maxTokens", maximum);
        event.addProperty("percentage", Math.min(100.0, used * 100.0 / maximum));
        sendEvent(event);
    }

    private void fileApproval(long requestId, JsonObject params) {
        // 文件审批与命令审批使用同一异步等待方式，保持主线程可交互。
        CompletableFuture.supplyAsync(() -> showTimedDialog(
            string(params, "reason", "Codex 请求修改工作区文件"), "文件修改审批",
            new String[]{"允许一次", "本会话允许", "拒绝", "拒绝并停止"}), AppExecutorUtil.getAppExecutorService())
            .thenAccept(choice -> ApplicationManager.getApplication().invokeLater(() -> respondDecision(requestId, choice)));
    }

    private int showTimedDialog(String message, String title, String[] options) {
        var projectFrame = WindowManager.getInstance().getFrame(project);
        // Swing modal dialogs keep processing timer events, so the timeout can safely close only this project's matching approval window.
        var timer = new Timer(CodexSettingsState.getInstance().getState().permissionDialogTimeoutSeconds * 1000, event -> {
            for (var window : Window.getWindows()) {
                if (!(window instanceof Dialog dialog) || !dialog.isShowing() || !Objects.equals(dialog.getTitle(), title)) continue;
                var owner = dialog.getOwner();
                while (owner != null && owner != projectFrame) owner = owner.getOwner();
                if (projectFrame == null || owner == projectFrame) dialog.dispose();
            }
        });
        timer.setRepeats(false);
        timer.start();
        try {
            return Messages.showDialog(project, message, title, options, 0, Messages.getQuestionIcon());
        } finally {
            timer.stop();
        }
    }

    private void respondDecision(long requestId, int choice) {
        var result = new JsonObject();
        result.addProperty("decision", switch (choice) { case 0 -> "accept"; case 1 -> "acceptForSession"; case 3 -> "cancel"; default -> "decline"; });
        codex.respondToServerRequest(requestId, result);
    }

    private void userInputRequest(long requestId, JsonObject params) {
        if (array(params, "questions").isEmpty()) {
            var result = new JsonObject();
            result.add("answers", new JsonObject());
            codex.respondToServerRequest(requestId, result);
            return;
        }
        var event = event("question");
        event.addProperty("requestId", requestId);
        event.add("questions", array(params, "questions"));
        sendEvent(event);
        var settings = CodexSettingsState.getInstance().getState();
        notifyAttention("Codex 有一些问题想问你", currentTitle, settings.askUserQuestionNotificationEnabled, settings.askUserQuestionSoundEnabled);
    }

    private void answerQuestions(JsonObject request, boolean cancelled) {
        var requestId = longValue(request, "requestId");
        if (requestId < 0) return;
        var result = new JsonObject();
        result.add("answers", cancelled || !request.has("answers") || !request.get("answers").isJsonObject()
            ? new JsonObject()
            : request.getAsJsonObject("answers"));
        codex.respondToServerRequest(requestId, result);
    }

    private void permissionsApproval(long requestId, JsonObject params) {
        // 权限审批同样异步等待，完成后再回到应用线程发送结果。
        CompletableFuture.supplyAsync(() -> showTimedDialog(
            string(params, "reason", "Codex 请求临时提升权限"), "权限审批", new String[]{"允许", "拒绝"}), AppExecutorUtil.getAppExecutorService())
            .thenAccept(choice -> ApplicationManager.getApplication().invokeLater(() -> {
                var result = new JsonObject();
                result.add("permissions", choice == Messages.YES && params.has("permissions") ? params.getAsJsonObject("permissions") : new JsonObject());
                result.addProperty("scope", "turn");
                codex.respondToServerRequest(requestId, result);
            }));
    }

    @Override
    public void onProtocolError(String message, Throwable error) {
        setBusy(false);
        addEntry(new ConversationEntry(ConversationEntry.Kind.ERROR, "连接错误", message, null));
    }

    private void asyncError(String title, Throwable throwable) {
        var error = throwable instanceof CompletionException && throwable.getCause() != null ? throwable.getCause() : throwable;
        addEntry(new ConversationEntry(ConversationEntry.Kind.ERROR, title, Objects.toString(error.getMessage(), error.toString()), null));
    }

    private void updateFileChangeDiffs(String sessionId, JsonObject object) {
        // 结构化补丁事件只包含本次文件修改，逐项转交给修改捕获服务。
        for (var element : array(object, "changes")) {
            if (!element.isJsonObject()) continue;
            var change = element.getAsJsonObject();
            changeService.updateFileDiff(
                sessionId,
                string(change, "path", ""),
                string(change, "kind", "update"),
                string(change, "diff", "")
            );
        }
    }

    private JsonArray array(JsonObject object, String field) {
        if (object == null || !object.has(field) || !object.get(field).isJsonArray()) return new JsonArray();
        return object.getAsJsonArray(field);
    }

    private String string(JsonObject object, String field, String fallback) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) return fallback;
        try {
            return object.get(field).getAsString();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private int integer(JsonObject object, String field) {
        try {
            return object.get(field).getAsInt();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private boolean bool(JsonObject object, String field, boolean fallback) {
        try {
            return object.get(field).getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private long longValue(JsonObject object, String field) {
        try {
            return object.get(field).getAsLong();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private double doubleValue(JsonObject object, String field) {
        try {
            return object.get(field).getAsDouble();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private record ComposerDropRegion(double left, double top, double right, double bottom) {
        private boolean contains(double x, double y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }
    }

    private String joinStrings(JsonArray values) {
        var result = new StringBuilder();
        for (var value : values) {
            if (!value.isJsonPrimitive()) continue;
            if (!result.isEmpty()) result.append('\n');
            result.append(value.getAsString());
        }
        return result.toString();
    }

    private String userMessageText(JsonArray content) {
        var result = new StringBuilder();
        for (JsonElement element : content) {
            var input = element.getAsJsonObject();
            var part = switch (string(input, "type", "")) {
                case "text" -> string(input, "text", "");
                case "mention" -> "@" + mentionPath(string(input, "path", string(input, "name", "文件")));
                case "localImage", "image" -> "[图片] " + string(input, "path", "");
                case "skill" -> "[Skill] " + string(input, "name", "");
                default -> "";
            };
            if (part.isBlank()) continue;
            if (!result.isEmpty()) result.append('\n');
            result.append(part);
        }
        return result.toString();
    }

    private List<String> userMessageReferencePaths(JsonArray content) {
        var result = new ArrayList<String>();
        for (JsonElement element : content) {
            var input = element.getAsJsonObject();
            if (!"mention".equals(string(input, "type", ""))) continue;
            var rawPath = string(input, "path", string(input, "name", ""));
            var path = droppedPath(rawPath);
            if (path != null) result.add(absolutePath(path));
        }
        return List.copyOf(result);
    }

    private String mentionPath(String rawPath) {
        var path = droppedPath(rawPath);
        return path == null ? rawPath : absolutePath(path);
    }

    @Override
    public void dispose() {
        claude.dispose();
        codex.removeListener(this);
        changeService.removeListener(changeListener);
        notificationSoundPlayer.close();
        if (bridge != null) bridge.dispose();
        if (browser != null) browser.dispose();
    }

}
