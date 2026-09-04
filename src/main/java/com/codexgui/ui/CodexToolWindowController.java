package com.codexgui.ui;

import com.codexgui.bridge.BridgeCommand;
import com.codexgui.bridge.BridgeEvent;
import com.codexgui.bridge.BridgeProtocolCodec;
import com.codexgui.conversation.ConversationSession;
import com.codexgui.conversation.ApprovalCoordinator;
import com.codexgui.conversation.QueuedInput;
import com.codexgui.conversation.SessionCoordinator;
import com.codexgui.conversation.SessionId;
import com.codexgui.conversation.SessionRegistry;
import com.codexgui.conversation.TurnHandle;
import com.codexgui.model.Attachment;
import com.codexgui.model.ConversationEntry;
import com.codexgui.model.EditorFileContext;
import com.codexgui.model.FileReference;
import com.codexgui.provider.ClaudeConversationProvider;
import com.codexgui.provider.CodexConversationProvider;
import com.codexgui.provider.TurnEvent;
import com.codexgui.provider.TurnRequest;
import com.codexgui.service.CodexAppServerService;
import com.codexgui.service.CodexEventListener;
import com.codexgui.service.ClaudeCodeService;
import com.codexgui.service.AttentionService;
import com.codexgui.service.ConversationChangeTracker;
import com.codexgui.service.NotificationSoundPlayer;
import com.codexgui.service.ProjectFileSearch;
import com.codexgui.service.ProviderModelService;
import com.codexgui.service.Utf8IO;
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
import com.intellij.ide.dnd.DnDEvent;
import com.intellij.ide.dnd.DnDSupport;
import com.intellij.ide.dnd.FileCopyPasteUtil;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
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
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.concurrency.AppExecutorUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class CodexToolWindowController implements Disposable, CodexEventListener {
    private static final Logger LOG = Logger.getInstance(CodexToolWindowController.class);
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final long PROJECT_FILE_CACHE_MILLIS = 2_000L;

    private final Project project;
    private final JPanel owner;
    private final CodexAppServerService codex;
    private final ClaudeCodeService claude;
    private final ClaudeConversationProvider claudeProvider;
    private final CodexConversationProvider codexProvider;
    private final ConversationChangeTracker changeService;
    private final BridgeProtocolCodec bridgeCodec = new BridgeProtocolCodec();
    private final SessionRegistry sessionRegistry = new SessionRegistry();
    private final SessionCoordinator sessionCoordinator = new SessionCoordinator(sessionRegistry);
    private final ApprovalCoordinator approvalCoordinator;
    private final AttentionService attentionService;
    private final ProviderModelService providerModelService = new ProviderModelService();
    private final Consumer<ConversationChangeTracker.ChangeUpdate> changeListener;
    private final JBCefBrowser browser;

    private boolean pageReady;
    private final Map<String, JsonArray> providerModelOptions = new ConcurrentHashMap<>();
    private final Map<String, String> activatingProviderByChannel = new ConcurrentHashMap<>();
    private final Map<String, StringBuilder> pendingCommandDeltas = new ConcurrentHashMap<>();
    private final java.util.Set<String> scheduledCommandDeltas = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> completedCommandItems = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> confirmedSessionIds = ConcurrentHashMap.newKeySet();
    private volatile List<Path> pendingDraggedPaths = List.of();
    private volatile ComposerDropRegion composerDropRegion;
    private volatile boolean nativeDragActive;
    private volatile List<ProjectFileSearch.Candidate> projectFileCatalog = List.of();
    private volatile long projectFileCatalogLoadedAt;
    CodexToolWindowController(Project project, JPanel owner, JBCefBrowser browser) {
        this.project = project;
        this.owner = owner;
        this.browser = browser;
        this.codex = CodexAppServerService.getInstance(project);
        this.changeService = ConversationChangeTracker.getInstance(project);
        this.claude = ClaudeCodeService.getInstance(project);
        this.claudeProvider = new ClaudeConversationProvider(claude);
        this.codexProvider = new CodexConversationProvider(codex);
        this.approvalCoordinator = new ApprovalCoordinator(project, codex, sessionCoordinator::isCurrent);
        this.attentionService = new AttentionService(project);
        var initialSession = sessionRegistry.active();
        var settingsService = CodexSettingsState.getInstance();
        initialSession.provider(provider(settingsService.getState().activeProvider));
        var initialProvider = settingsService.activeProvider(initialSession.provider());
        initialSession.providerProfileId(initialProvider.id);
        initialSession.providerRevision(initialProvider.revision);
        this.changeListener = update -> publishChanges(update.sessionId(), update.changes());

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

    void handleBridgeMessage(String payload) {
        var decoded = bridgeCodec.decodeCommand(payload);
        if (decoded instanceof BridgeProtocolCodec.Rejected rejected) {
            sendProtocolEvent(rejected.error(), true);
            return;
        }
        var command = ((BridgeProtocolCodec.Decoded) decoded).command();
        var request = command.payload();
        ApplicationManager.getApplication().invokeLater(() -> {
            // 关闭会话只确认目标事务，不切换活动会话，避免批量关闭后活动指针落在已关闭页签。
            if (command.type() != BridgeCommand.Type.CLOSE_SESSION) activateSession(command.sessionId().value());
            switch (command.type()) {
                case READY -> bootstrap();
                case RECONNECT -> codex.restart().thenRun(this::loadModels).exceptionally(error -> {
                    asyncError("重新连接 Codex CLI 失败", error);
                    return null;
                });
                case SEND -> sendInput(string(request, "text", ""));
                case STOP -> interruptCurrentTurn();
                case NEW -> newConversation(
                    string(request, "title", ""),
                    bool(request, "skipConfirmation", false),
                    string(request, "provider", activeSession().provider())
                );
                case CLOSE_SESSION -> closeSession(command.sessionId().value());
                case ACTIVATE_SESSION -> publishCurrentSession();
                case HISTORY -> loadHistory(string(request, "search", ""));
                case OPEN_THREAD -> openThread(string(request, "id", ""));
                case RENAME -> renameCurrentThread();
                case EXPORT -> exportConversation();
                case PICK_FILE -> chooseAttachment(false);
                case PICK_IMAGE -> chooseAttachment(true);
                case DROP_FILES -> addDroppedInputs();
                case CANCEL_DROP -> {
                    pendingDraggedPaths = List.of();
                    publishNativeDragState(false);
                }
                case COMPOSER_BOUNDS -> updateComposerDropRegion(request);
                case LIST_PROJECT_FILES -> publishProjectFiles(
                    string(request, "query", ""), requestIdAsLong(command.requestId())
                );
                case REMOVE_ATTACHMENT -> removeAttachment(integer(request, "index"));
                case REMOVE_FILE_REFERENCE -> removeFileReference(string(request, "id", ""));
                case REMOVE_FILE_REFERENCES -> removeFileReferences(request);
                case ADD_FILE_REFERENCES -> addFileReferences(request);
                case REORDER_FILE_REFERENCES -> reorderFileReferences(request);
                case ACCEPT_CHANGE -> acceptChange(integer(request, "index"));
                case REVERT_CHANGE -> revertChange(integer(request, "index"));
                case ACCEPT_ALL -> acceptAllChanges();
                case REVERT_ALL -> revertAllChanges();
                case OPEN_CHANGE -> openChange(integer(request, "index"));
                case COMPACT -> compactCurrentThread();
                case REVIEW -> reviewCurrentChanges();
                case REWIND -> rollbackLastTurn();
                case MCP -> showMcpServers();
                case USAGE -> showUsage();
                case SETTING -> updateSetting(string(request, "key", ""), string(request, "value", ""));
                case SELECT_PROVIDER -> selectProvider(
                    string(request, "provider", "codex"),
                    string(request, "title", "")
                );
                case ACTIVATE_PROVIDER_PROFILE -> activateProviderProfile(string(request, "id", ""));
                case SAVE_PROVIDER_PROFILE -> saveProviderProfile(request);
                case DELETE_PROVIDER_PROFILE -> deleteProviderProfile(string(request, "id", ""));
                case CHECK_PROVIDERS -> publishProviderStatus();
                case BEHAVIOR_SETTING -> updateBehaviorSetting(request);
                case BROWSE_NOTIFICATION_SOUND -> browseNotificationSound();
                case TEST_NOTIFICATION_SOUND -> playConfiguredSound(true);
                case TOGGLE_STREAMING -> toggleStreaming();
                case TOGGLE_THINKING -> toggleThinking();
                case SAVE_INSTRUCTIONS -> saveInstructions(request);
                case SAVE_PROMPT -> savePrompt(request);
                case DELETE_PROMPT -> deletePrompt(string(request, "id", ""));
                case SELECT_PROMPT -> selectPrompt(string(request, "id", ""));
                case SAVE_AGENT -> saveAgent(request);
                case DELETE_AGENT -> deleteAgent(string(request, "id", ""));
                case SELECT_AGENT -> selectAgent(string(request, "id", ""));
                case LOAD_MCP -> publishMcpServers(false);
                case RELOAD_MCP -> publishMcpServers(true);
                case LOAD_SKILLS -> publishSkills(false);
                case RELOAD_SKILLS -> publishSkills(true);
                case SET_SKILL_ENABLED -> setSkillEnabled(request);
                case IMPORT_SKILL -> importSkill(string(request, "scope", "repo"));
                case OPEN_SKILL -> openSkill(request);
                case OPEN_MCP_CONFIG -> openMcpConfig();
                case LOGIN_MCP -> loginMcpServer(request);
                case SAVE_MCP -> saveMcpServer(request);
                case DELETE_MCP -> deleteMcpServer(request);
                case SET_MCP_ENABLED -> setMcpServerEnabled(request);
                case COPY_TEXT -> copyText(request);
                case ANSWER_QUESTIONS -> answerQuestions(command, false);
                case CANCEL_QUESTIONS -> answerQuestions(command, true);
                case CONVERSATION_SEARCH -> searchConversation();
                case OPEN_FILE -> openFileLocation(request);
                case OPEN_URL -> BrowserUtil.browse(string(request, "url", ""));
                case OPEN_SETTINGS -> ShowSettingsUtil.getInstance().showSettingsDialog(project, "CodeDeck");
            }
        });
    }

    private long requestIdAsLong(String requestId) {
        try {
            return Long.parseLong(requestId);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private void bootstrap() {
        var session = activeSession();
        pageReady = true;
        var settings = CodexSettingsState.getInstance().getState();
        var instructions = sharedInstructions(settings);
        var state = new JsonObject();
        state.addProperty("connected", codex.isConnected());
        state.addProperty("sessionId", session.id().value());
        state.addProperty("busy", session.busy());
        state.addProperty("queuedCount", session.queuedInputs().size());
        state.addProperty("title", session.title());
        if (session.threadId() != null) state.addProperty("threadId", session.threadId());
        state.addProperty("model", activeModel(settings));
        state.add("models", providerModels(settings));
        state.addProperty("provider", session.provider());
        state.addProperty("providerProfileId", session.providerProfileId());
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
        state.add("attachments", attachmentsJson(session));
        state.add("fileReferences", fileReferencesJson(session));
        var event = event(BridgeEvent.Type.BOOTSTRAP);
        event.add("state", state);
        sendEvent(event);
        session.transcript().forEach(entry -> publishEntry(session, entry));
        publishChanges(session.id().value(), changeService.listSummaries(session.id().value()));
        publishSkills(false);
        publishProviderStatus();
        // 页面可能晚于 CLI 连接完成，准备完成后重新请求模型，避免模型事件丢失。
        loadModels();
    }

    private String activeSessionId() { return sessionRegistry.activeSessionId().value(); }

    private ConversationSession activeSession() { return sessionRegistry.active(); }

    private ConversationSession session(String sessionId) {
        return sessionRegistry.find(SessionId.of(sessionId));
    }

    private void activateSession(String sessionId) {
        var id = SessionId.of(sessionId);
        var existing = sessionRegistry.find(id);
        var session = sessionRegistry.activate(id);
        if (existing == null) {
            var settingsService = CodexSettingsState.getInstance();
            session.provider(provider(settingsService.getState().activeProvider));
            var activeProvider = settingsService.activeProvider(session.provider());
            session.providerProfileId(activeProvider.id);
            session.providerRevision(activeProvider.revision);
        }
    }

    private void publishCurrentSession() {
        var session = activeSession();
        var event = event(BridgeEvent.Type.CLEAR, session.id());
        event.addProperty("title", session.title());
        event.addProperty("provider", session.provider());
        if (session.threadId() != null) event.addProperty("threadId", session.threadId());
        sendEvent(event);
        session.transcript().forEach(entry -> publishEntry(session, entry));
        publishAttachments(session);
        publishFileReferences(session);
        publishChanges(session.id().value(), changeService.listSummaries(session.id().value()));
        publishThread(session);
        var usage = event(BridgeEvent.Type.USAGE, session.id());
        usage.addProperty("usedTokens", session.usageUsedTokens());
        usage.addProperty("maxTokens", session.usageMaxTokens());
        usage.addProperty("percentage", session.usageMaxTokens() > 0
            ? Math.min(100.0, session.usageUsedTokens() * 100.0 / session.usageMaxTokens()) : 0.0);
        sendEvent(usage);
        setBusy(session, session.busy());
    }

    private void sendInput(String text) {
        var session = activeSession();
        text = text.trim();
        if (text.isBlank() && session.attachments().isEmpty() && session.fileReferences().isEmpty()) return;
        if (session.busy()) {
            enqueueInput(text);
            return;
        }
        var settingsService = CodexSettingsState.getInstance();
        var activeProvider = settingsService.activeProvider(session.provider());
        if (!Objects.equals(session.providerProfileId(), activeProvider.id)
            || session.providerRevision() != activeProvider.revision) {
            toast("当前会话使用的供应商配置已变化，请开启新对话后继续");
            return;
        }
        // 自定义供应商必须先由模型目录确定具体选择，禁止以空模型启动请求。
        if (!activeProvider.builtIn && activeProvider.model.isBlank()) {
            loadProviderModels(activeProvider);
            toast("当前供应商尚未加载到可用模型，请等待模型目录加载完成后重试");
            return;
        }
        if (Objects.equals(session.provider(), "codex") && !codex.isConnected()) {
            var reconnectText = text;
            var sessionId = session.id();
            codex.start().thenRun(() -> ApplicationManager.getApplication().invokeLater(() -> {
                if (sessionRegistry.find(sessionId) == session) sendInput(session, reconnectText);
            })).exceptionally(error -> {
                ApplicationManager.getApplication().invokeLater(() -> asyncError(session, "Codex CLI 未连接", error));
                return null;
            });
            return;
        }
        sendInput(session, text);
    }

    private void sendInput(ConversationSession session, String text) {
        if (session.attachments().isEmpty() && session.fileReferences().isEmpty() && handleNativeCommand(text)) return;

        var input = prepareInput(session, text);
        session.attachments().clear();
        session.fileReferences().clear();
        publishAttachments(session);
        publishFileReferences(session);
        dispatchInput(session, input, true);
    }

    private QueuedInput prepareInput(ConversationSession session, String text) {
        var settings = CodexSettingsState.getInstance().getState();
        var editorContext = settings.sendOpenedFilePath ? currentEditorContext() : null;
        var inputText = editorContext == null ? text : editorContext.appendTo(text);
        var sentAttachments = List.copyOf(session.attachments());
        var sentFileReferences = List.copyOf(session.fileReferences());
        var display = new StringBuilder(embedFileReferencePaths(text, sentFileReferences));
        if (editorContext != null) display.append("\n\n[当前编辑器上下文] ").append(editorContext.displayLabel());
        sentAttachments.forEach(attachment -> display.append("\n").append(switch (attachment.kind()) {
            case IMAGE -> "[图片] ";
            case FILE -> "@";
        }).append(attachment.kind() == Attachment.Kind.FILE ? absolutePath(attachment.path()) : attachment.name()));
        return new QueuedInput(inputText, display.toString().trim(), sentAttachments, sentFileReferences);
    }

    private void enqueueInput(String text) {
        var session = activeSession();
        if (session.attachments().isEmpty() && session.fileReferences().isEmpty() && handleNativeCommand(text)) return;
        var input = prepareInput(session, text);
        session.queuedInputs().add(input);
        addEntry(session, new ConversationEntry(
            ConversationEntry.Kind.USER,
            "你",
            input.display(),
            null,
            input.fileReferences().stream().map(reference -> absolutePath(reference.path())).toList()
        ));
        session.pendingUserMessageCount(session.pendingUserMessageCount() + 1);
        session.attachments().clear();
        session.fileReferences().clear();
        publishAttachments(session);
        publishFileReferences(session);
        publishQueueState(session);
    }

    private void dispatchInput(ConversationSession session, QueuedInput input, boolean publishUser) {
        var sessionId = session.id().value();
        var settingsService = CodexSettingsState.getInstance();
        var settings = settingsService.snapshot(session.provider());
        var providerProfile = settings.provider();
        var model = providerProfile.builtIn()
            ? (Objects.equals(session.provider(), "claude") ? settings.claudeModel() : settings.model())
            : providerProfile.model();
        if (publishUser) {
            session.pendingUserBody(input.display());
            addEntry(session, new ConversationEntry(
                ConversationEntry.Kind.USER,
                "你",
                input.display(),
                null,
                input.fileReferences().stream().map(reference -> absolutePath(reference.path())).toList()
            ));
            session.pendingUserMessageCount(session.pendingUserMessageCount() + 1);
        }
        var turnHandle = sessionCoordinator.beginTurn(session.id());
        publishBusy(session);

        // Claude Code 使用独立 CLI 会话，不能把会话 ID 或输入误发给 Codex app-server。
        if (Objects.equals(session.provider(), "claude")) {
            dispatchClaudeInput(session, turnHandle, input, settings);
            return;
        }

        var request = new TurnRequest(
            turnHandle,
            Objects.requireNonNullElse(session.threadId(), ""),
            input.inputText(),
            input.attachments(),
            input.fileReferences(),
            model,
            settings.reasoningEffort().value(),
            settings.serviceTier().value(),
            settings.approvalPolicy().value(),
            settings.sandboxMode().value(),
            developerInstructions(),
            "",
            providerProfile
        );
        codexProvider.startTurn(request, event -> ApplicationManager.getApplication().invokeLater(
            () -> handleCodexTurnEvent(event)));
    }

    private void handleCodexTurnEvent(TurnEvent event) {
        var turnHandle = event.handle();
        if (!sessionCoordinator.isCurrent(turnHandle)) return;
        var session = sessionRegistry.find(turnHandle.sessionId());
        if (session == null) return;
        if (event instanceof TurnEvent.Started started) {
            sessionCoordinator.apply(turnHandle, current -> {
                if (!started.conversationId().isBlank()) current.threadId(started.conversationId());
                if (!started.providerTurnId().isBlank()) current.bindProviderTurn(started.providerTurnId());
                publishThread(current);
                publishBusy(current);
            });
            return;
        }
        if (event instanceof TurnEvent.Delta delta) {
            appendProviderDelta(session, turnHandle, delta);
            return;
        }
        if (event instanceof TurnEvent.Item item) {
            handleCodexItem(session, item);
            return;
        }
        if (event instanceof TurnEvent.Change change) {
            changeService.trackProviderDiff(session.id().value(), change.unifiedDiff());
            return;
        }
        if (event instanceof TurnEvent.Usage usage) {
            session.usageUsedTokens(usage.usedTokens());
            session.usageMaxTokens(usage.maxTokens());
            publishUsage(session);
            return;
        }
        if (event instanceof TurnEvent.Completed) {
            approvalCoordinator.clearTurn(turnHandle);
            codexProvider.complete(turnHandle);
            sessionCoordinator.complete(turnHandle, current -> {
                current.pendingUserBody(null);
                changeService.refreshSession(current.id().value());
                publishBusy(current);
                var currentSettings = CodexSettingsState.getInstance().snapshot(CodexSettingsState.CODEX_CHANNEL);
                notifyAttention("Codex 任务已完成", current.title(), currentSettings.taskCompletionNotificationEnabled(), currentSettings.taskCompletionSoundEnabled());
                startNextQueuedInput(current);
            });
            loadHistory("");
            return;
        }
        if (event instanceof TurnEvent.Failed failed) {
            approvalCoordinator.clearTurn(turnHandle);
            codexProvider.complete(turnHandle);
            sessionCoordinator.complete(turnHandle, current -> {
                if (current.pendingUserMessageCount() > 0) current.pendingUserMessageCount(current.pendingUserMessageCount() - 1);
                current.pendingUserBody(null);
                changeService.refreshSession(current.id().value());
                publishBusy(current);
                if (!failed.cancelled()) asyncError(current, "无法发送消息", failed.error());
                startNextQueuedInput(current);
            });
        }
    }

    private void appendProviderDelta(ConversationSession session, TurnHandle turnHandle, TurnEvent.Delta delta) {
        if (!CodexSettingsState.getInstance().snapshot(CodexSettingsState.CODEX_CHANNEL).streamResponses() || delta.text().isEmpty()) return;
        var kind = switch (delta.kind()) {
            case TEXT -> ConversationEntry.Kind.ASSISTANT;
            case THINKING -> ConversationEntry.Kind.REASONING;
            case PLAN -> ConversationEntry.Kind.PLAN;
            case COMMAND -> ConversationEntry.Kind.COMMAND;
        };
        var title = switch (delta.kind()) {
            case TEXT -> "Codex";
            case THINKING -> "思考";
            case PLAN -> "计划";
            case COMMAND -> "命令";
        };
        var itemId = delta.itemId().isBlank() ? fallbackId(session, kind) : delta.itemId();
        if (delta.kind() != TurnEvent.Delta.Kind.COMMAND) {
            appendEntry(session, itemId, kind, title, delta.text());
            return;
        }
        // 命令输出高频到达时继续使用既有短批处理，避免阻塞 IDE 事件线程。
        var commandKey = commandKey(session, itemId);
        var buffer = pendingCommandDeltas.computeIfAbsent(commandKey, ignored -> new StringBuilder());
        synchronized (buffer) { buffer.append(delta.text()); }
        if (scheduledCommandDeltas.add(commandKey)) {
            CompletableFuture.delayedExecutor(40, TimeUnit.MILLISECONDS).execute(
                () -> flushCommandDelta(session, turnHandle, itemId, title));
        }
    }

    private void handleCodexItem(ConversationSession session, TurnEvent.Item item) {
        var source = GSON.toJsonTree(item.data()).getAsJsonObject();
        // Adapter 已完成协议归一化，渲染层只消费普通数据而不接触 provider 通知 envelope。
        if (item.phase() == TurnEvent.Item.Phase.UPDATED) {
            updateFileChangeDiffs(session.id().value(), source);
        } else if (item.phase() == TurnEvent.Item.Phase.STARTED) {
            renderStartedItem(session, source);
        } else {
            renderCompletedItem(session, source);
        }
    }

    private void publishUsage(ConversationSession session) {
        if (session.usageMaxTokens() <= 0) return;
        var event = event(BridgeEvent.Type.USAGE, session.id());
        event.addProperty("usedTokens", session.usageUsedTokens());
        event.addProperty("maxTokens", session.usageMaxTokens());
        event.addProperty("percentage", Math.min(100.0,
            session.usageUsedTokens() * 100.0 / session.usageMaxTokens()));
        sendEvent(event);
    }

    private void dispatchClaudeInput(
        ConversationSession session,
        TurnHandle turnHandle,
        QueuedInput input,
        CodexSettingsState.SettingsSnapshot settings
    ) {
        var sessionId = session.id().value();
        var providerProfile = settings.provider();
        var conversationId = session.threadId();
        var finalItemId = "claude:" + UUID.randomUUID();
        var streamedText = new StringBuilder();
        var toolIds = ConcurrentHashMap.<String>newKeySet();
        var prompt = claudePrompt(input);

        var request = new TurnRequest(
            turnHandle,
            conversationId,
            prompt,
            input.attachments(),
            input.fileReferences(),
            providerProfile.builtIn() ? "" : providerProfile.model(),
            settings.reasoningEffort().value(),
            settings.serviceTier().value(),
            settings.approvalPolicy().value(),
            settings.sandboxMode().value(),
            developerInstructions(),
            settings.claudeExecutable(),
            providerProfile
        );
        claudeProvider.startTurn(request, event -> handleClaudeTurnEvent(
            sessionId, turnHandle, finalItemId, streamedText, toolIds, settings, event));
    }

    private void handleClaudeTurnEvent(
        String sessionId,
        TurnHandle turnHandle,
        String finalItemId,
        StringBuilder streamedText,
        java.util.Set<String> toolIds,
        CodexSettingsState.SettingsSnapshot settings,
        TurnEvent event
    ) {
        if (!turnHandle.equals(event.handle())) return;
        if (event instanceof TurnEvent.ModelSelected selected) {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!sessionCoordinator.isCurrent(turnHandle)) return;
                CodexSettingsState.getInstance().setClaudeModel(selected.model());
                if (Objects.equals(activeSessionId(), sessionId)) publishSettings();
            });
            return;
        }
        if (event instanceof TurnEvent.Delta delta) {
            if (delta.text().isEmpty()) return;
            if (delta.kind() == TurnEvent.Delta.Kind.TEXT) {
                synchronized (streamedText) { streamedText.append(delta.text()); }
                if (settings.streamResponses()) ApplicationManager.getApplication().invokeLater(() ->
                    sessionCoordinator.apply(turnHandle, current -> appendEntry(
                        current, delta.itemId(), ConversationEntry.Kind.ASSISTANT, "Claude", delta.text())));
            } else if (delta.kind() == TurnEvent.Delta.Kind.THINKING && settings.streamResponses()) {
                ApplicationManager.getApplication().invokeLater(() -> sessionCoordinator.apply(
                    turnHandle, current -> appendEntry(
                        current, delta.itemId(), ConversationEntry.Kind.REASONING, "思考", delta.text())));
            }
            return;
        }
        if (event instanceof TurnEvent.Tool tool) {
            toolIds.add(tool.id());
            ApplicationManager.getApplication().invokeLater(() -> sessionCoordinator.apply(
                turnHandle, current -> replaceEntry(
                    current, tool.id(), ConversationEntry.Kind.COMMAND, "Claude 工具",
                    tool.name() + "\n\n" + GSON.toJson(tool.input()))));
            return;
        }
        if (event instanceof TurnEvent.Completed completed) {
            finishClaudeTurn(turnHandle, finalItemId, streamedText, toolIds, settings, completed, null);
            return;
        }
        if (event instanceof TurnEvent.Failed failed) {
            finishClaudeTurn(turnHandle, finalItemId, streamedText, toolIds, settings, null, failed);
        }
    }

    private void finishClaudeTurn(
        TurnHandle turnHandle,
        String itemId,
        StringBuilder streamedText,
        java.util.Set<String> toolIds,
        CodexSettingsState.SettingsSnapshot settings,
        TurnEvent.Completed completed,
        TurnEvent.Failed failed
    ) {
        ApplicationManager.getApplication().invokeLater(() -> {
            approvalCoordinator.clearTurn(turnHandle);
            sessionCoordinator.complete(turnHandle, current -> {
                // 正常完成时发布最终回复；失败时保留已收到的流式内容并明确提示。
                if (completed != null) {
                    current.threadId(completed.conversationId());
                    String received;
                    synchronized (streamedText) { received = streamedText.toString(); }
                    if (!settings.streamResponses() || received.isBlank()) {
                        replaceEntry(current, itemId, ConversationEntry.Kind.ASSISTANT, "Claude", completed.finalText());
                    }
                    if (!completed.model().isBlank()) CodexSettingsState.getInstance().setClaudeModel(completed.model());
                    toolIds.forEach(id -> appendEntry(
                        current, id, ConversationEntry.Kind.COMMAND, "Claude 工具", "\n\n执行状态：completed"));
                    publishThread(current);
                    notifyAttention("Claude 任务已完成", current.title(), settings.taskCompletionNotificationEnabled(), settings.taskCompletionSoundEnabled());
                } else if (failed != null && !failed.cancelled()) {
                    asyncError(current, "无法发送 Claude Code 消息", failed.error());
                }
                changeService.refreshSession(current.id().value());
                if (current.pendingUserMessageCount() > 0) {
                    current.pendingUserMessageCount(current.pendingUserMessageCount() - 1);
                }
                current.pendingUserBody(null);
                publishBusy(current);
                startNextQueuedInput(current);
            });
        });
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
        var session = session(sessionId);
        if (session != null) startNextQueuedInput(session);
    }

    private void startNextQueuedInput(ConversationSession session) {
        if (session.busy() || session.queuedInputs().isEmpty()) return;
        var next = session.queuedInputs().removeFirst();
        publishQueueState(session);
        dispatchInput(session, next, false);
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
        loadModels(activeSession().provider());
    }

    private void loadModels(String channel) {
        var profile = CodexSettingsState.getInstance().activeProvider(channel);
        if (!profile.builtIn) {
            loadProviderModels(profile);
            return;
        }
        // Claude 本地配置由 CLI 在首个回合回填真实模型，不预设静态模型目录。
        if (Objects.equals(channel, CodexSettingsState.CLAUDE_CHANNEL)) return;
        // Codex CLI 尚未完成 initialize 时不能发送请求，等待连接事件重新加载。
        if (!codex.isConnected()) return;
        var profileId = profile.id;
        var profileRevision = profile.revision;
        codex.listModels().thenAccept(result -> {
            var modelIds = new ArrayList<String>();
            String defaultModel = null;
            var modelItems = array(result, "data");
            if (modelItems.isEmpty()) modelItems = array(result, "models");
            for (var element : modelItems) {
                var model = element.getAsJsonObject();
                if (model.has("hidden") && model.get("hidden").getAsBoolean()) continue;
                var id = string(model, "model", "");
                if (id.isBlank()) id = string(model, "id", string(model, "slug", ""));
                if (!id.isBlank()) modelIds.add(id);
                if (model.has("isDefault") && model.get("isDefault").getAsBoolean()) defaultModel = id;
            }
            var resolvedDefault = defaultModel;
            ApplicationManager.getApplication().invokeLater(
                () -> applyModelOptions(profileId, profileRevision, modelIds, resolvedDefault));
        }).exceptionally(error -> {
            ApplicationManager.getApplication().invokeLater(() -> asyncError("无法读取 Codex 模型列表", error));
            return null;
        });
    }

    private void loadProviderModels(CodexSettingsState.ProviderProfile profile) {
        var apiKey = ProviderCredentialStore.get(profile.id);
        if (apiKey == null || apiKey.isBlank()) return;
        var profileId = profile.id;
        var profileRevision = profile.revision;
        providerModelService.listModels(profile.snapshot(), apiKey).thenAccept(modelIds ->
            ApplicationManager.getApplication().invokeLater(
                () -> applyModelOptions(profileId, profileRevision, modelIds, null)
            )
        ).exceptionally(error -> {
            ApplicationManager.getApplication().invokeLater(() -> asyncError("无法读取供应商模型列表", error));
            return null;
        });
    }

    private void applyModelOptions(String profileId, int profileRevision, List<String> modelIds, String defaultModel) {
        var settingsService = CodexSettingsState.getInstance();
        var profile = settingsService.provider(profileId);
        if (profile == null || profile.revision != profileRevision || modelIds.isEmpty()) return;

        // 接口模型目录是最终依据；上次选择失效时切到接口建议项或首项。
        var settings = settingsService.getState();
        var currentModel = profile.builtIn ? settings.model : profile.model;
        var nextModel = selectProviderModel(modelIds, currentModel, defaultModel);
        if (profile.builtIn) settings.model = nextModel;
        else profile.model = nextModel;
        var models = new JsonArray();
        modelIds.stream().distinct().forEach(models::add);
        providerModelOptions.put(profile.id, models);

        var activeProfile = settingsService.activeProvider(profile.channel);
        if (!Objects.equals(activeSession().provider(), profile.channel)
            || !Objects.equals(activeProfile.id, profile.id)) return;
        if (!Objects.equals(currentModel, nextModel) && !currentModel.isBlank()) {
            toast("当前供应商不支持模型 " + currentModel + "，已切换到 " + nextModel);
        }
        publishModelOptions();
    }

    static String selectProviderModel(List<String> modelIds, String currentModel, String suggestedModel) {
        if (modelIds.isEmpty()) return "";
        if (modelIds.contains(currentModel)) return currentModel;
        if (modelIds.contains(suggestedModel)) return suggestedModel;
        return modelIds.getFirst();
    }

    private void publishModelOptions() {
        var settings = CodexSettingsState.getInstance().getState();
        var state = new JsonObject();
        state.add("models", providerModels(settings));
        state.addProperty("model", activeModel(settings));
        var event = event(BridgeEvent.Type.BOOTSTRAP);
        event.add("state", state);
        sendEvent(event);
    }

    private void selectProvider(String requestedProvider, String requestedTitle) {
        var session = activeSession();
        var selected = provider(requestedProvider);
        var changesChannel = !Objects.equals(selected, session.provider());
        if (changesChannel && session.busy()) {
            toast("任务运行期间不能切换供应商");
            return;
        }
        // 跨渠道切换时复用当前页签，并让目标渠道从空白会话开始。
        if (changesChannel) {
            newConversation(requestedTitle, true, selected);
            toast(Objects.equals(selected, "claude") ? "已切换到 Claude 渠道" : "已切换到 GPT 渠道");
            return;
        }

        // 同渠道选择只刷新当前绑定，不清空已有会话。
        session.provider(selected);
        var settingsService = CodexSettingsState.getInstance();
        var settings = settingsService.getState();
        settings.activeProvider = selected;
        var profile = settingsService.activeProvider(selected);
        session.providerProfileId(profile.id);
        session.providerRevision(profile.revision);
        if (session.threadId() == null && session.transcript().isEmpty() && !requestedTitle.isBlank()) {
            session.title(requestedTitle.trim());
        }
        publishSettings();
        publishProviderStatus();
        loadModels(selected);
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
            var event = event(BridgeEvent.Type.PROVIDERS);
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
        var configured = profile.builtIn || (!profile.baseUrl.isBlank() && hasApiKey);
        json.addProperty("id", profile.id);
        json.addProperty("channel", profile.channel);
        json.addProperty("name", profile.name);
        json.addProperty("baseUrl", profile.baseUrl);
        json.addProperty("wireApi", profile.wireApi);
        json.addProperty("builtIn", profile.builtIn);
        json.addProperty("hasApiKey", hasApiKey);
        json.addProperty("available", profile.builtIn ? localAvailable : configured);
        json.addProperty("active", Objects.equals(settingsService.activeProviderId(profile.channel), profile.id));
        json.addProperty("activating", Objects.equals(activatingProviderByChannel.get(profile.channel), profile.id));
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
        if (sessionRegistry.sessions().stream().anyMatch(ConversationSession::busy)) {
            toast("任务运行期间不能切换供应商配置");
            return;
        }
        if (!profile.builtIn && (!validProviderUrl(profile.baseUrl) || !ProviderCredentialStore.has(profile.id))) {
            toast("请先补全接口地址和认证凭据");
            return;
        }
        if (Objects.equals(settingsService.activeProviderId(profile.channel), profile.id)) return;

        // 内置配置由本机 CLI 提供模型；自定义配置必须先验证远端模型目录。
        if (profile.builtIn) {
            activatingProviderByChannel.remove(profile.channel);
            completeProviderActivation(profile);
            return;
        }
        var profileChannel = profile.channel;
        var pendingProviderId = activatingProviderByChannel.put(profileChannel, profile.id);
        if (Objects.equals(pendingProviderId, profile.id)) return;

        var profileId = profile.id;
        var profileRevision = profile.revision;
        var apiKey = ProviderCredentialStore.get(profile.id);
        // 凭据在检测开始前被外部清除时终止本次启用，避免留下永久检测状态。
        if (apiKey == null || apiKey.isBlank()) {
            activatingProviderByChannel.remove(profileChannel, profileId);
            publishProviderStatus();
            toast("请先补全接口地址和认证凭据");
            return;
        }
        publishProviderStatus();
        toast("正在读取供应商模型目录...");
        providerModelService.listModels(profile.snapshot(), apiKey).thenAccept(modelIds ->
            ApplicationManager.getApplication().invokeLater(
                () -> completeCustomProviderActivation(profileChannel, profileId, profileRevision, modelIds)
            )
        ).exceptionally(error -> {
            ApplicationManager.getApplication().invokeLater(
                () -> failProviderActivation(profileChannel, profileId, profileRevision, error)
            );
            return null;
        });
    }

    private void completeCustomProviderActivation(
        String channel,
        String profileId,
        int profileRevision,
        List<String> modelIds
    ) {
        // 同一渠道只接受最后一次启用请求的结果，避免慢请求覆盖后续选择。
        if (!activatingProviderByChannel.remove(channel, profileId)) return;
        var settingsService = CodexSettingsState.getInstance();
        var profile = settingsService.provider(profileId);
        // 请求期间配置被编辑或删除时丢弃旧结果，避免启用过期连接信息。
        if (profile == null || profile.revision != profileRevision) {
            if (profile != null) toast("供应商配置已变化，请重新启用");
            publishProviderStatus();
            return;
        }
        // 模型目录返回期间可能启动了任务，此时仍保持原供应商。
        if (sessionRegistry.sessions().stream().anyMatch(ConversationSession::busy)) {
            toast("任务运行期间不能切换供应商配置");
            publishProviderStatus();
            return;
        }

        var selectedModel = selectProviderModel(modelIds, profile.model, "");
        if (selectedModel.isBlank()) {
            toast("无法启用供应商：模型目录未返回可用模型");
            publishProviderStatus();
            return;
        }
        profile.model = selectedModel;
        var models = new JsonArray();
        modelIds.stream().distinct().forEach(models::add);
        providerModelOptions.put(profile.id, models);
        completeProviderActivation(profile);
    }

    private void failProviderActivation(String channel, String profileId, int profileRevision, Throwable error) {
        // 用户已经选择其它供应商时忽略旧请求的失败结果。
        if (!activatingProviderByChannel.remove(channel, profileId)) return;
        var profile = CodexSettingsState.getInstance().provider(profileId);
        // 只报告当前版本的失败，旧请求不能覆盖用户随后完成的编辑。
        if (profile != null && profile.revision == profileRevision) {
            toast("无法启用供应商：" + errorMessage(error));
        }
        publishProviderStatus();
    }

    private void completeProviderActivation(CodexSettingsState.ProviderProfile profile) {
        var settingsService = CodexSettingsState.getInstance();

        // 配置按渠道全局启用；空白会话可直接跟随，已有会话保留旧版本并阻止误发。
        settingsService.setActiveProvider(profile.channel, profile.id);
        var session = activeSession();
        if (Objects.equals(session.provider(), profile.channel)
            && session.threadId() == null && session.transcript().isEmpty()) {
            session.providerProfileId(profile.id);
            session.providerRevision(profile.revision);
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
        if (existing != null && existing.builtIn) {
            providerSaveResult(false, "内置供应商不能编辑");
            return;
        }
        var channel = provider(string(request, "channel", CodexSettingsState.CODEX_CHANNEL));
        var name = string(request, "name", "").trim();
        var baseUrl = string(request, "baseUrl", "").trim();
        var apiKey = string(request, "apiKey", "").trim();
        if (name.isBlank() || baseUrl.isBlank()) {
            providerSaveResult(false, "供应商名称和接口地址不能为空");
            return;
        }
        if (!validProviderUrl(baseUrl)) {
            providerSaveResult(false, "接口地址必须是有效的 http 或 https 地址");
            return;
        }
        if (existing == null && apiKey.isBlank()) {
            providerSaveResult(false, "新增供应商时必须填写认证凭据");
            return;
        }
        var duplicate = settings.providers.stream().anyMatch(item -> !Objects.equals(item.id, requestedId)
            && Objects.equals(item.channel, channel) && item.name.equalsIgnoreCase(name));
        if (duplicate) {
            providerSaveResult(false, "同一渠道下不能使用重复的供应商名称");
            return;
        }
        var activeEdit = existing != null && Objects.equals(settingsService.activeProviderId(existing.channel), existing.id);
        if (activeEdit && sessionRegistry.sessions().stream().anyMatch(ConversationSession::busy)) {
            providerSaveResult(false, "任务运行期间不能修改正在使用的供应商");
            return;
        }

        // 先写入凭据，失败时不修改供应商资料，前端可保留草稿继续重试。
        var profile = existing == null ? new CodexSettingsState.ProviderProfile() : existing;
        if (existing == null) profile.id = "provider-" + UUID.randomUUID();
        try {
            if (!apiKey.isBlank()) ProviderCredentialStore.set(profile.id, apiKey);
            else if (bool(request, "clearApiKey", false)) ProviderCredentialStore.remove(profile.id);
        } catch (RuntimeException error) {
            LOG.warn("保存供应商认证凭据失败", error);
            providerSaveResult(false, "无法安全保存认证凭据");
            return;
        }

        // 供应商表单只保存连接信息，模型由启用后的模型选择器独立维护。
        profile.channel = channel;
        profile.name = name;
        profile.baseUrl = baseUrl;
        profile.wireApi = Objects.equals(string(request, "wireApi", "responses"), "chat") ? "chat" : "responses";
        if (existing != null) profile.revision++;
        if (existing == null) settings.providers.add(profile);
        providerModelOptions.remove(profile.id);

        if (activeEdit) applyProviderRuntimeChange(profile.channel);
        publishSettings();
        publishProviderStatus();
        providerSaveResult(true, existing == null ? "供应商已添加" : "供应商配置已保存");
    }

    private void providerSaveResult(boolean success, String message) {
        var event = event(BridgeEvent.Type.TOAST);
        event.addProperty("message", message);
        event.addProperty("providerSaveSuccess", success);
        sendEvent(event);
    }

    private void deleteProviderProfile(String id) {
        var settingsService = CodexSettingsState.getInstance();
        var settings = settingsService.getState();
        var profile = settingsService.provider(id);
        if (profile == null || profile.builtIn) return;
        var active = Objects.equals(settingsService.activeProviderId(profile.channel), profile.id);
        if (active && sessionRegistry.sessions().stream().anyMatch(ConversationSession::busy)) {
            toast("任务运行期间不能删除正在使用的供应商");
            return;
        }

        settings.providers.remove(profile);
        providerModelOptions.remove(profile.id);
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
        if (!Objects.equals(channel, CodexSettingsState.CODEX_CHANNEL)) {
            loadModels(channel);
            return;
        }
        codex.restart().thenRun(() -> loadModels(channel)).exceptionally(error -> {
            asyncError("切换 GPT 供应商失败", error);
            return null;
        });
    }

    private String provider(String value) {
        return Objects.equals(value, "claude") ? "claude" : "codex";
    }

    private String activeModel(CodexSettingsState.StateData settings) {
        var provider = activeSession().provider();
        var profile = CodexSettingsState.getInstance().activeProvider(provider);
        if (!profile.builtIn) return profile.model;
        return Objects.equals(provider, "claude") ? settings.claudeModel : settings.model;
    }

    private JsonArray providerModels(CodexSettingsState.StateData settings) {
        var provider = activeSession().provider();
        var profile = CodexSettingsState.getInstance().activeProvider(provider);
        var loaded = providerModelOptions.get(profile.id);
        if (loaded != null) return loaded.deepCopy();
        var models = new JsonArray();
        var model = profile.builtIn
            ? (Objects.equals(provider, "claude") ? settings.claudeModel : settings.model)
            : profile.model;
        if (!model.isBlank()) models.add(model);
        return models;
    }

    private void loadHistory(String search) {
        var session = activeSession();
        var historyProvider = session.provider();
        // Claude 历史来自当前项目的 JSONL 文件，不经过 Codex app-server。
        if (Objects.equals(historyProvider, CodexSettingsState.CLAUDE_CHANNEL)) {
            claude.listHistory(search).thenAccept(history -> {
                var items = new JsonArray();
                for (var entry : history) {
                    var item = new JsonObject();
                    item.addProperty("id", entry.id());
                    item.addProperty("title", entry.title());
                    item.addProperty("favorite", false);
                    item.addProperty("time", Instant.ofEpochMilli(entry.updatedAtEpochMs())
                        .atZone(ZoneId.systemDefault()).format(HISTORY_TIME));
                    items.add(item);
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    var current = sessionRegistry.find(session.id());
                    if (current == session && Objects.equals(current.provider(), historyProvider)) {
                        publishHistory(session, historyProvider, items);
                    }
                });
            }).exceptionally(error -> {
                ApplicationManager.getApplication().invokeLater(() -> asyncError(session, "无法读取 Claude 历史", error));
                return null;
            });
            return;
        }
        // Codex 历史只从当前 app-server 的线程目录加载。
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
            ApplicationManager.getApplication().invokeLater(() -> {
                var current = sessionRegistry.find(session.id());
                if (current == session && Objects.equals(current.provider(), historyProvider)) {
                    publishHistory(session, historyProvider, items);
                }
            });
        }).exceptionally(error -> null);
    }

    private void publishHistory(ConversationSession session, String provider, JsonArray items) {
        var event = event(BridgeEvent.Type.HISTORY, session.id());
        event.addProperty("provider", provider);
        event.add("items", items);
        sendEvent(event);
    }

    private void openThread(String threadId) {
        var session = activeSession();
        if (session.busy() || threadId.isBlank() || Objects.equals(session.threadId(), threadId)) return;
        // Claude 会话直接解析项目历史，并绑定原 session ID 供后续 --resume 使用。
        if (Objects.equals(session.provider(), CodexSettingsState.CLAUDE_CHANNEL)) {
            claude.readHistory(threadId).thenAccept(history -> ApplicationManager.getApplication().invokeLater(() -> {
                if (sessionRegistry.find(session.id()) != session
                    || !Objects.equals(session.provider(), CodexSettingsState.CLAUDE_CHANNEL)) return;
                approvalCoordinator.clearSession(session.id());
                session.clearConversation();
                session.threadId(history.id());
                session.title(history.title());
                publishClear(session);
                for (var historyEntry : history.entries()) {
                    var kind = ConversationEntry.Kind.valueOf(historyEntry.kind().name());
                    var title = switch (kind) {
                        // 用户消息沿用聊天区的用户标签。
                        case USER -> "你";
                        // 助手消息明确标记为 Claude。
                        case ASSISTANT -> "Claude";
                        // 思考内容使用统一的推理标签。
                        case REASONING -> "思考过程";
                        // 工具调用在历史中按命令条目展示。
                        case COMMAND -> "工具调用";
                        default -> "Claude";
                    };
                    addEntry(session, new ConversationEntry(
                        kind, title, historyEntry.body(), historyEntry.itemId(), List.of(), historyEntry.createdAtEpochMs()));
                }
                publishAttachments(session);
                publishFileReferences(session);
                publishThread(session);
            })).exceptionally(error -> {
                ApplicationManager.getApplication().invokeLater(() -> asyncError(session, "无法打开 Claude 历史会话", error));
                return null;
            });
            return;
        }
        // Codex 会话先恢复元数据，再通过分页接口加载完整 turns。
        codex.resumeThread(threadId).thenAccept(result -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (sessionRegistry.find(session.id()) == session) renderThread(session, result);
            });
        }).exceptionally(error -> {
            ApplicationManager.getApplication().invokeLater(() -> asyncError(session, "无法打开历史会话", error));
            return null;
        });
    }

    private void renderThread(ConversationSession session, JsonObject result) {
        var thread = result.getAsJsonObject("thread");
        if (thread == null) return;
        approvalCoordinator.clearSession(session.id());
        session.clearConversation();
        session.threadId(string(thread, "id", null));
        session.title(string(thread, "name", ""));
        if (session.title().isBlank()) session.title(string(thread, "preview", "Codex 会话"));
        publishClear(session);
        for (var turnElement : array(thread, "turns")) {
            for (var itemElement : array(turnElement.getAsJsonObject(), "items")) {
                renderCompletedItem(session, itemElement.getAsJsonObject());
            }
        }
        publishAttachments(session);
        publishFileReferences(session);
        publishThread(session);
    }

    private void newConversation() {
        newConversation("", false, activeSession().provider());
    }

    private void newConversation(String requestedTitle, boolean skipConfirmation, String requestedProvider) {
        var session = activeSession();
        if (session.busy()) return;
        var settingsService = CodexSettingsState.getInstance();
        var settings = settingsService.getState();
        // 命令入口未完成前端确认时，使用原生确认框保护已有会话。
        if (!skipConfirmation && settings.newSessionConfirmEnabled && !session.transcript().isEmpty()
            && Messages.showYesNoDialog(project, "当前会话已有消息，确定要新建会话吗？", "新建会话", "新建", "取消", Messages.getQuestionIcon()) != Messages.YES) {
            return;
        }
        // 复用页签开启新对话时，旧回合的修改视为用户已确认，不再参与后续冲突判断。
        var sessionId = session.id().value();
        confirmedSessionIds.remove(sessionId);
        changeService.clearSession(sessionId);
        approvalCoordinator.clearSession(session.id());
        // 新建会话时重置线程和输入上下文，避免旧附件或草稿带入新对话。
        session.clearConversation();
        var selectedProvider = provider(requestedProvider);
        var changesChannel = !Objects.equals(session.provider(), selectedProvider);
        session.provider(selectedProvider);
        settings.activeProvider = selectedProvider;
        var activeProvider = settingsService.activeProvider(session.provider());
        session.providerProfileId(activeProvider.id);
        session.providerRevision(activeProvider.revision);
        session.title(requestedTitle == null || requestedTitle.isBlank() ? "新会话" : requestedTitle.trim());
        publishClear(session);
        publishAttachments(session);
        publishFileReferences(session);
        publishThread(session);
        // 渠道变化后立即刷新供应商状态和模型，确保当前页签只使用目标渠道配置。
        if (changesChannel) {
            publishSettings();
            publishProviderStatus();
            loadModels(selectedProvider);
        }
    }

    private void closeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        var session = session(sessionId);
        if (session != null) {
            // 关闭页签时同时停止进程并使旧回调失效，防止会话被迟到事件复活。
            if (Objects.equals(session.provider(), CodexSettingsState.CLAUDE_CHANNEL)) {
                claudeProvider.cancel(session.handle());
            } else {
                codexProvider.cancel(session.handle());
            }
            approvalCoordinator.clearSession(session.id());
            sessionCoordinator.close(session.id());
        }
        // 页签关闭代表用户确认保留该会话的工作区修改，清理其待处理事务。
        confirmedSessionIds.add(sessionId);
        changeService.clearSession(sessionId);
    }

    private void publishClear(ConversationSession session) {
        var event = event(BridgeEvent.Type.CLEAR, session.id());
        event.addProperty("title", session.title());
        event.addProperty("provider", session.provider());
        if (session.threadId() != null) event.addProperty("threadId", session.threadId());
        sendEvent(event);
    }

    private void renameCurrentThread() {
        var session = activeSession();
        var name = Messages.showInputDialog(project, "输入新的会话名称：", "重命名会话", Messages.getQuestionIcon(), session.title(), null);
        if (name == null || name.isBlank()) return;
        session.title(name.trim());
        // 空白页签和 Claude 会话名称由插件本地维护，Codex 会话再同步到 app-server。
        if (session.threadId() == null || Objects.equals(session.provider(), "claude")) {
            publishThread(session);
            return;
        }
        codex.setThreadName(session.threadId(), session.title()).thenRun(() -> {
            publishThread(session);
            loadHistory("");
        }).exceptionally(error -> {
            ApplicationManager.getApplication().invokeLater(() -> asyncError(session, "无法重命名会话", error));
            return null;
        });
    }

    private void exportConversation() {
        var session = activeSession();
        if (session.transcript().isEmpty()) return;
        var chooser = new JFileChooser(changeService.getRoot().toFile());
        chooser.setDialogTitle("导出 Codex 会话");
        chooser.setSelectedFile(new java.io.File("codex-conversation.md"));
        if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        var markdown = new StringBuilder("# ").append(session.title()).append("\n\n");
        session.transcript().forEach(entry -> markdown.append("## ").append(entry.title()).append("\n\n").append(entry.body()).append("\n\n"));
        try {
            Utf8IO.write(chooser.getSelectedFile().toPath(), markdown);
            toast("会话已导出");
        } catch (IOException error) {
            Messages.showErrorDialog(project, error.getMessage(), "导出失败");
        }
    }

    private void chooseAttachment(boolean image) {
        var session = activeSession();
        var chooser = new JFileChooser(changeService.getRoot().toFile());
        chooser.setMultiSelectionEnabled(true);
        chooser.setDialogTitle(image ? "选择图片" : "选择引用文件");
        if (image) chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("图片", "png", "jpg", "jpeg", "gif", "webp"));
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        for (var file : chooser.getSelectedFiles()) {
            var path = file.toPath();
            var kind = image || isImageAttachment(path) ? Attachment.Kind.IMAGE : Attachment.Kind.FILE;
            session.attachments().add(new Attachment(kind, file.getName(), path));
        }
        publishAttachments(session);
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
        var event = event(BridgeEvent.Type.NATIVE_DRAG);
        event.addProperty("active", active);
        sendEvent(event);
    }

    private void publishNativeDropPosition(DnDEvent dragEvent) {
        var component = browser.getComponent();
        if (component.getWidth() <= 0 || component.getHeight() <= 0) return;
        var point = dragEvent.getPointOn(component);
        var event = event(BridgeEvent.Type.NATIVE_DROP);
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
        var session = activeSession();
        publishNativeDragState(false);
        var addedReferences = 0;
        var addedImages = 0;
        // 图片保留附件行为，普通文件和目录改为可移除的 Codex 文件引用。
        for (var path : droppedPaths) {
            var normalized = path.toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized) && !Files.isDirectory(normalized)) continue;
            // 图片继续作为附件插入，供发送前预览和移除。
            if (Files.isRegularFile(normalized) && isImageAttachment(normalized)) {
                if (session.attachments().stream().anyMatch(item -> item.path().toAbsolutePath().normalize().equals(normalized))) continue;
                session.attachments().add(new Attachment(Attachment.Kind.IMAGE, normalized.getFileName().toString(), normalized));
                addedImages++;
                continue;
            }
            // 普通文件或目录作为引用标签插入，重复路径保持幂等。
            if (session.fileReferences().stream().anyMatch(item -> item.path().equals(normalized))) continue;
            session.fileReferences().add(FileReference.fromPath(normalized));
            addedReferences++;
        }
        // 拖拽结果已经通过输入框标签或附件列表展示，不再弹出遮挡输入框的结果提示。
        if (addedReferences == 0 && addedImages == 0) {
            return;
        }
        if (addedReferences > 0) publishFileReferences(session);
        if (addedImages > 0) publishAttachments(session);
    }

    private void publishProjectFiles(String query, long requestId) {
        CompletableFuture.supplyAsync(
            () -> ProjectFileSearch.filter(projectFileCatalog(), query, 60),
            AppExecutorUtil.getAppExecutorService()
        ).thenAccept(files -> {
            var event = event(BridgeEvent.Type.PROJECT_FILES).requestId(Long.toString(requestId));
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
        projectFileCatalog = ProjectFileSearch.list(project);
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
            var event = event(BridgeEvent.Type.SKILLS);
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
            var event = event(BridgeEvent.Type.SKILL_ENABLED);
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
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;

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
        var session = activeSession();
        if (index < 0 || index >= session.attachments().size()) return;
        session.attachments().remove(index);
        publishAttachments(session);
    }

    private void removeFileReference(String id) {
        var session = activeSession();
        if (id.isBlank()) return;
        if (!session.fileReferences().removeIf(reference -> reference.id().equals(id))) return;
        publishFileReferences(session);
    }

    private void removeFileReferences(JsonObject request) {
        var session = activeSession();
        var ids = request.getAsJsonArray("ids");
        if (ids == null || ids.isEmpty()) return;
        var removed = false;
        for (var value : ids) {
            removed |= session.fileReferences().removeIf(reference -> reference.id().equals(value.getAsString()));
        }
        if (removed) publishFileReferences(session);
    }

    private void addFileReferences(JsonObject request) {
        var session = activeSession();
        var paths = request.getAsJsonArray("paths");
        if (paths == null) return;
        var added = 0;
        for (var value : paths) {
            var path = droppedPath(value.getAsString());
            if (path == null || (!Files.isRegularFile(path) && !Files.isDirectory(path))) continue;
            session.fileReferences().add(FileReference.fromPath(path.toAbsolutePath().normalize()));
            added++;
        }
        if (added > 0) publishFileReferences(session);
    }

    private void reorderFileReferences(JsonObject request) {
        var session = activeSession();
        var ids = request.getAsJsonArray("ids");
        if (ids == null || ids.size() != session.fileReferences().size()) return;
        var remaining = new ArrayList<>(session.fileReferences());
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
        session.fileReferences().clear();
        session.fileReferences().addAll(ordered);
    }

    private JsonArray attachmentsJson(ConversationSession session) {
        var items = new JsonArray();
        for (var attachment : session.attachments()) {
            var item = new JsonObject();
            item.addProperty("kind", attachment.kind().name());
            item.addProperty("name", attachment.name());
            item.addProperty("path", attachment.path().toString());
            items.add(item);
        }
        return items;
    }

    private void publishAttachments(ConversationSession session) {
        var event = event(BridgeEvent.Type.ATTACHMENTS, session.id());
        event.add("items", attachmentsJson(session));
        sendEvent(event);
    }

    private JsonArray fileReferencesJson(ConversationSession session) {
        var items = new JsonArray();
        for (var reference : session.fileReferences()) {
            var item = new JsonObject();
            item.addProperty("id", reference.id());
            item.addProperty("name", reference.name());
            item.addProperty("path", reference.path().toString());
            item.addProperty("directory", reference.directory());
            items.add(item);
        }
        return items;
    }

    private void publishFileReferences(ConversationSession session) {
        var event = event(BridgeEvent.Type.FILE_REFERENCES, session.id());
        event.add("items", fileReferencesJson(session));
        sendEvent(event);
    }

    private void publishChanges(List<ConversationChangeTracker.ChangeSummary> changes) {
        publishChanges(activeSessionId(), changes);
    }

    private void publishChanges(String sessionId, List<ConversationChangeTracker.ChangeSummary> changes) {
        var items = new JsonArray();
        for (var change : changes) {
            var item = new JsonObject();
            item.addProperty("path", change.displayName(changeService.getRoot()).replace('\\', '/'));
            item.addProperty("kind", change.kind().name());
            item.addProperty("reversible", change.reversible());
            item.addProperty("addedLines", change.addedLines());
            item.addProperty("deletedLines", change.deletedLines());
            items.add(item);
        }
        var event = event(BridgeEvent.Type.CHANGES, SessionId.of(sessionId));
        event.add("items", items);
        sendEvent(event);
    }

    private ConversationChangeTracker.ChangeSummary changeAt(int index) {
        var changes = changeService.listSummaries(activeSessionId());
        return index >= 0 && index < changes.size() ? changes.get(index) : null;
    }

    private void acceptAllChanges() {
        // 空列表直接结束，避免把无意义的全量扫描提交到界面线程。
        if (changeService.listSummaries(activeSessionId()).isEmpty()) return;
        changeService.acceptAll(activeSessionId());
    }

    private void acceptChange(int index) {
        var change = changeAt(index);
        if (change != null) changeService.accept(activeSessionId(), change.path());
    }

    private void revertChange(int index) {
        var change = changeAt(index);
        if (change == null) return;
        if (Messages.showYesNoDialog(project, "撤销 Codex 对该文件的全部修改？\n\n" + change.path(), "撤销文件修改", "撤销", "取消", Messages.getWarningIcon()) != Messages.YES) return;
        try {
            changeService.revert(activeSessionId(), change.path());
        } catch (IOException error) {
            Messages.showErrorDialog(project, error.getMessage(), "无法撤销修改");
        }
    }

    private void revertAllChanges() {
        if (changeService.listSummaries(activeSessionId()).isEmpty()) return;
        if (Messages.showYesNoDialog(project, "撤销当前回合捕获的全部文件修改？", "撤销全部修改", "全部撤销", "取消", Messages.getWarningIcon()) != Messages.YES) return;
        try {
            changeService.revertAll(activeSessionId());
        } catch (IOException error) {
            Messages.showErrorDialog(project, error.getMessage(), "部分文件无法撤销");
        }
    }

    private void openChange(int index) {
        var change = changeAt(index);
        if (change == null) return;
        var details = changeService.readDetails(activeSessionId(), change.path());
        if (details == null) return;
        var before = details.beforeContent() == null ? "" : new String(details.beforeContent(), StandardCharsets.UTF_8);
        var factory = DiffContentFactory.getInstance();
        // 刷新并定位源文件，让 Diff 读取用户当前看到的最新内容。
        var sourceFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(change.path());
        var beforeContent = sourceFile == null ? factory.create(project, before) : factory.create(project, before, sourceFile);
        var sourceDocument = sourceFile == null ? null : FileDocumentManager.getInstance().getDocument(sourceFile);
        // 文本源文件绑定真实文档，Diff 中的编辑会直接落到当前文件。
        var afterContent = sourceDocument == null
            ? factory.create(project, details.afterContent() == null ? "" : new String(details.afterContent(), StandardCharsets.UTF_8))
            : factory.create(project, sourceDocument, sourceFile);
        var request = new SimpleDiffRequest(
            (Objects.equals(activeSession().provider(), "claude") ? "Claude Code 修改 · " : "Codex 修改 · ")
                + details.displayName(changeService.getRoot()),
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
        var count = activeSession().transcript().stream()
            .filter(entry -> entry.body().toLowerCase().contains(query.toLowerCase())).count();
        toast("找到 " + count + " 条匹配消息");
    }

    private void updateSetting(String key, String value) {
        var settingsService = CodexSettingsState.getInstance();
        var settings = settingsService.getState();
        // 模型值按供应商分别保存，避免切换后把另一套模型名称带入 CLI。
        switch (key) {
            case "model" -> {
                var provider = activeSession().provider();
                var profile = settingsService.activeProvider(provider);
                if (!profile.builtIn) profile.model = value;
                else if (Objects.equals(provider, "claude")) settings.claudeModel = value;
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
        var event = event(BridgeEvent.Type.FILE_CONTEXT);
        addFileContext(event, settings.sendOpenedFilePath ? currentEditorContext() : null);
        sendEvent(event);
    }

    private void addFileContext(BridgeEvent.Builder target, EditorFileContext context) {
        target.addProperty("path", context == null ? "" : context.path());
        target.addProperty("startLine", context == null ? 0 : context.startLine());
        target.addProperty("endLine", context == null ? 0 : context.endLine());
    }

    private void browseNotificationSound() {
        var chooser = new JFileChooser(changeService.getRoot().toFile());
        chooser.setDialogTitle("选择自定义提示音");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("音频文件（WAV、MP3、AIFF）", "wav", "mp3", "aif", "aiff"));
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;
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
        attentionService.playConfigured(settings).exceptionally(error -> {
            if (reportError) ApplicationManager.getApplication().invokeLater(() -> toast("提示音播放失败：" + errorMessage(error)));
            return null;
        });
    }

    private void notifyAttention(String title, String content, boolean notificationEnabled, boolean soundEnabled) {
        attentionService.notify(title, content, notificationEnabled, soundEnabled);
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
        var session = activeSession();
        var settings = CodexSettingsState.getInstance().getState();
        var instructions = sharedInstructions(settings);
        var state = new JsonObject();
        state.addProperty("provider", session.provider());
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
        var event = event(BridgeEvent.Type.BOOTSTRAP, session.id());
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
        var session = activeSession();
        if (session.threadId() == null || session.busy()) return;
        if (Objects.equals(session.provider(), "claude")) {
            toast("Claude Code 由 CLI 自动管理上下文，无需手动压缩");
            return;
        }
        codex.compactThread(session.threadId()).thenRun(() -> {
            toast(session, "上下文压缩完成");
        }).exceptionally(error -> {
            ApplicationManager.getApplication().invokeLater(() -> asyncError(session, "无法压缩上下文", error));
            return null;
        });
    }

    private void reviewCurrentChanges() {
        var session = activeSession();
        if (session.threadId() == null || session.busy()) {
            toast("请先创建或打开一个会话");
            return;
        }
        if (Objects.equals(session.provider(), "claude")) {
            sendInput("请审查当前工作区中尚未提交的修改，并优先报告具体问题和风险。");
            return;
        }
        var turnHandle = sessionCoordinator.beginTurn(session.id());
        publishBusy(session);
        codex.reviewUncommittedChanges(session.threadId()).thenCompose(result -> onEdt(() -> {
            var turn = result.getAsJsonObject("turn");
            if (turn != null) sessionCoordinator.apply(
                turnHandle, current -> current.bindProviderTurn(string(turn, "id", null)));
            return null;
        })).exceptionally(error -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                approvalCoordinator.clearTurn(turnHandle);
                sessionCoordinator.complete(turnHandle, current -> {
                    publishBusy(current);
                    asyncError(current, "无法启动代码审查", error);
                });
            });
            return null;
        });
    }

    private void rollbackLastTurn() {
        var session = activeSession();
        if (session.threadId() == null || session.busy()) return;
        if (Objects.equals(session.provider(), "claude")) {
            toast("Claude Code CLI 暂不支持从此界面回溯上一回合");
            return;
        }
        if (Messages.showYesNoDialog(project, "回退上一轮会话？工作区文件仍由修改面板单独管理。", "回退上一轮", "回退", "取消", Messages.getWarningIcon()) != Messages.YES) return;
        codex.rollbackThread(session.threadId()).thenAccept(result -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (sessionRegistry.find(session.id()) == session) renderThread(session, result);
            });
        }).exceptionally(error -> {
            ApplicationManager.getApplication().invokeLater(() -> asyncError(session, "无法回退会话", error));
            return null;
        });
    }

    private void interruptCurrentTurn() {
        var session = activeSession();
        if (!session.busy()) return;
        if (Objects.equals(session.provider(), "claude")) {
            // 先让待启动和运行中的回合同时失效，迟到回调会被 Coordinator 丢弃。
            var turnHandle = session.handle();
            approvalCoordinator.clearTurn(turnHandle);
            sessionCoordinator.cancel(session.id());
            claudeProvider.cancel(turnHandle);
            publishBusy(session);
            startNextQueuedInput(session);
            return;
        }
        if (session.threadId() == null || session.providerTurnId() == null) return;
        var turnHandle = session.handle();
        approvalCoordinator.clearTurn(turnHandle);
        sessionCoordinator.cancel(session.id());
        publishBusy(session);
        codexProvider.cancel(turnHandle);
        startNextQueuedInput(session);
    }

    private void showMcpServers() {
        var session = activeSession();
        if (Objects.equals(session.provider(), "claude")) {
            toast("Claude Code 的 MCP 配置由 Claude CLI 管理");
            return;
        }
        codex.listMcpServers(session.threadId()).thenAccept(result -> ApplicationManager.getApplication().invokeLater(() -> {
            var text = new StringBuilder();
            for (var element : array(result, "data")) text.append("• ").append(string(element.getAsJsonObject(), "name", "未命名服务器")).append('\n');
            Messages.showInfoMessage(project, text.isEmpty() ? "当前没有已配置的 MCP 服务器。" : text.toString(), "Codex MCP 服务器");
        })).exceptionally(error -> {
            asyncError("无法读取 MCP 服务器", error);
            return null;
        });
    }

    private void publishMcpServers(boolean reload) {
        var session = activeSession();
        var operation = reload ? codex.reloadMcpServers() : CompletableFuture.completedFuture(new JsonObject());
        operation.thenCompose(ignored -> {
            var statuses = codex.listMcpServers(session.threadId());
            var config = codex.readConfig(project.getBasePath()).exceptionally(error -> new JsonObject());
            return statuses.thenCombine(config, this::mergeMcpServers);
        }).thenAccept(items -> {
            var event = event(BridgeEvent.Type.MCP_SERVERS, session.id());
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
        var session = activeSession();
        var name = string(request, "name", "").trim();
        if (name.isBlank()) return;
        codex.loginMcpServer(name, session.threadId()).thenAccept(result -> ApplicationManager.getApplication().invokeLater(() -> {
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
        setBusy(activeSession(), value);
    }

    private void setBusy(ConversationSession session, boolean value) {
        session.busy(value);
        publishBusy(session);
    }

    private void publishBusy(ConversationSession session) {
        var event = event(BridgeEvent.Type.BUSY, session.id());
        event.addProperty("busy", session.busy());
        event.addProperty("queuedCount", session.queuedInputs().size());
        sendEvent(event);
    }

    private void publishQueueState(ConversationSession session) {
        var event = event(BridgeEvent.Type.QUEUE, session.id());
        event.addProperty("queuedCount", session.queuedInputs().size());
        sendEvent(event);
    }

    private void addEntry(ConversationEntry entry) {
        addEntry(activeSession(), entry);
    }

    private void addEntry(ConversationSession session, ConversationEntry entry) {
        session.transcript().add(entry);
        publishEntry(session, entry);
    }

    private void publishEntry(ConversationEntry entry) {
        publishEntry(activeSession(), entry);
    }

    private void publishEntry(ConversationSession session, ConversationEntry entry) {
        var event = event(BridgeEvent.Type.MESSAGE, session.id());
        event.add("entry", entryJson(entry));
        sendEvent(event);
    }

    private void replaceEntry(String itemId, ConversationEntry.Kind kind, String title, String body) {
        replaceEntry(activeSession(), itemId, kind, title, body);
    }

    private void replaceEntry(ConversationSession session, String itemId, ConversationEntry.Kind kind, String title, String body) {
        for (int index = 0; index < session.transcript().size(); index++) {
            var current = session.transcript().get(index);
            if (!Objects.equals(itemId, current.itemId())) continue;
            var replacement = new ConversationEntry(
                kind, title, body, itemId, current.fileReferencePaths(), current.createdAtEpochMs());
            session.transcript().set(index, replacement);
            var event = event(BridgeEvent.Type.REPLACE_MESSAGE, session.id());
            event.add("entry", entryJson(replacement));
            sendEvent(event);
            return;
        }
        var replacement = new ConversationEntry(kind, title, body, itemId);
        session.transcript().add(replacement);
        publishEntry(session, replacement);
    }

    private void appendEntry(String itemId, ConversationEntry.Kind kind, String title, String delta) {
        appendEntry(activeSession(), itemId, kind, title, delta);
    }

    private void appendEntry(ConversationSession session, String itemId, ConversationEntry.Kind kind, String title, String delta) {
        for (var index = 0; index < session.transcript().size(); index++) {
            var current = session.transcript().get(index);
            if (!Objects.equals(itemId, current.itemId())) continue;
            session.transcript().set(index, new ConversationEntry(
                kind, title, current.body() + delta, itemId,
                current.fileReferencePaths(), current.createdAtEpochMs()));
            var event = event(BridgeEvent.Type.APPEND_MESSAGE, session.id());
            event.addProperty("itemId", itemId);
            event.addProperty("kind", kind.name().toLowerCase());
            event.addProperty("title", title);
            event.addProperty("delta", delta);
            sendEvent(event);
            return;
        }
        var entry = new ConversationEntry(kind, title, delta, itemId);
        session.transcript().add(entry);
        publishEntry(session, entry);
    }

    private JsonObject entryJson(ConversationEntry entry) {
        var json = new JsonObject();
        json.addProperty("kind", entry.kind().name().toLowerCase());
        json.addProperty("title", entry.title());
        json.addProperty("body", entry.body());
        json.addProperty("createdAtEpochMs", entry.createdAtEpochMs());
        if (!entry.fileReferencePaths().isEmpty()) {
            var references = new JsonArray();
            entry.fileReferencePaths().forEach(references::add);
            json.add("fileReferencePaths", references);
        }
        if (entry.itemId() != null) json.addProperty("itemId", entry.itemId());
        return json;
    }

    private void publishThread(ConversationSession session) {
        var event = event(BridgeEvent.Type.THREAD, session.id());
        if (session.threadId() != null) event.addProperty("id", session.threadId());
        event.addProperty("title", session.title());
        event.addProperty("provider", session.provider());
        sendEvent(event);
    }

    private void toast(String message) {
        toast(activeSession(), message);
    }

    private void toast(ConversationSession session, String message) {
        var event = event(BridgeEvent.Type.TOAST, session.id());
        event.addProperty("message", message);
        sendEvent(event);
    }

    private void addBehaviorSettings(JsonObject state, CodexSettingsState.StateData settings) {
        var session = activeSession();
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
        state.addProperty("usageUsedTokens", session.usageUsedTokens());
        state.addProperty("usageMaxTokens", session.usageMaxTokens());
        state.addProperty("usagePercentage", session.usageMaxTokens() > 0
            ? Math.min(100.0, session.usageUsedTokens() * 100.0 / session.usageMaxTokens())
            : 0.0);
    }

    private void mcpLog(String level, String serverName, String message) {
        var event = event(BridgeEvent.Type.MCP_LOG);
        event.addProperty("level", level);
        event.addProperty("serverName", serverName);
        event.addProperty("message", message);
        sendEvent(event);
    }

    private String errorMessage(Throwable throwable) {
        var error = unwrap(throwable);
        return Objects.toString(error.getMessage(), error.toString());
    }

    private Throwable unwrap(Throwable throwable) {
        return throwable instanceof CompletionException && throwable.getCause() != null ? throwable.getCause() : throwable;
    }

    private BridgeEvent.Builder event(BridgeEvent.Type type) {
        return event(type, sessionRegistry.activeSessionId());
    }

    private BridgeEvent.Builder event(BridgeEvent.Type type, SessionId sessionId) {
        var session = sessionRegistry.find(sessionId);
        var handle = session == null ? null : session.handle();
        return new BridgeEvent.Builder(
            type,
            sessionId,
            handle == null ? "" : handle.turnId().value(),
            session == null ? 0 : session.generation()
        );
    }

    private void sendEvent(BridgeEvent.Builder event) {
        sendProtocolEvent(event.build(), false);
    }

    private void sendProtocolEvent(BridgeEvent event, boolean allowBeforeReady) {
        if ((!pageReady && !allowBeforeReady) || browser == null || browser.isDisposed()) return;
        var json = bridgeCodec.encodeEvent(event);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!browser.isDisposed()) browser.getCefBrowser().executeJavaScript("window.CodexGui && window.CodexGui.receive(" + json + ");", "http://codex-gui.local/", 0);
        });
    }

    @Override
    public void onConnectionChanged(boolean connected, String detail) {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (var session : sessionRegistry.sessions()) {
                if (!Objects.equals(session.provider(), CodexSettingsState.CODEX_CHANNEL)) continue;
                var event = event(BridgeEvent.Type.CONNECTION, session.id());
                event.addProperty("connected", connected);
                event.addProperty("detail", detail);
                sendEvent(event);
                // 连接代际变化会使旧回合失效，后续通知不能再写入该会话。
                if (session.busy()) {
                    approvalCoordinator.clearTurn(session.handle());
                    sessionCoordinator.cancel(session.id());
                    publishBusy(session);
                }
                if (connected) startNextQueuedInput(session);
            }
            publishProviderStatus();
            if (!connected) return;
            // 连接建立后补发启动阶段被跳过的基础数据请求。
            loadModels();
            publishSkills(false);
            loadHistory("");
        });
    }

    private <T> CompletableFuture<T> onEdt(Supplier<T> operation) {
        var result = new CompletableFuture<T>();
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                result.complete(operation.get());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    @Override
    public void onNotification(String method, JsonObject params) {
        ApplicationManager.getApplication().invokeLater(() -> handleNotification(method, params));
    }

    private void handleNotification(String method, JsonObject params) {
        var notificationThreadId = string(params, "threadId", "");
        if (notificationThreadId.isBlank() && params != null) {
            var turn = params.has("turn") && params.get("turn").isJsonObject() ? params.getAsJsonObject("turn") : null;
            notificationThreadId = string(turn, "threadId", "");
            if (notificationThreadId.isBlank() && params.has("item") && params.get("item").isJsonObject()) {
                notificationThreadId = string(params.getAsJsonObject("item"), "threadId", "");
            }
        }
        var session = sessionForThread(notificationThreadId);
        // 缺少线程标识的通知只能归入当前会话；无法匹配线程的事件不得误投到当前页签。
        if (session == null && notificationThreadId.isBlank()) session = activeSession();
        if (session == null) return;
        var turnHandle = session.handle();
        var turnEvent = method.startsWith("turn/") || method.startsWith("item/");
        if (turnEvent && turnHandle == null) return;
        var notificationTurnId = notificationTurnId(params);
        if (turnEvent && session.providerTurnId() != null && !notificationTurnId.isBlank()
            && !Objects.equals(session.providerTurnId(), notificationTurnId)) return;
        // 回合事件由 CodexConversationProvider 归一化后进入统一 TurnEvent 通道。
        if (turnEvent || Objects.equals(method, "thread/tokenUsage/updated")
            || Objects.equals(method, "turn/diff/updated")) return;
        if (Objects.equals(method, "error") && (!notificationThreadId.isBlank() || !notificationTurnId.isBlank())) return;
        switch (method) {
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
            case "error" -> addEntry(session, new ConversationEntry(ConversationEntry.Kind.ERROR, "Codex 错误", params.toString(), null));
            case "warning", "configWarning", "deprecationNotice" -> addEntry(session, new ConversationEntry(
                ConversationEntry.Kind.NOTICE, "提示", string(params, "message", params.toString()), null));
            default -> {
            }
        }
    }

    private ConversationSession sessionForThread(String threadId) {
        if (threadId == null || threadId.isBlank()) return null;
        var session = sessionRegistry.findByThreadId(threadId);
        return session == null || confirmedSessionIds.contains(session.id().value()) ? null : session;
    }

    private String notificationTurnId(JsonObject params) {
        var direct = string(params, "turnId", "");
        if (!direct.isBlank()) return direct;
        if (params != null && params.has("turn") && params.get("turn").isJsonObject()) {
            return string(params.getAsJsonObject("turn"), "id", "");
        }
        if (params != null && params.has("item") && params.get("item").isJsonObject()) {
            return string(params.getAsJsonObject("item"), "turnId", "");
        }
        return "";
    }

    private void flushCommandDelta(ConversationSession session, TurnHandle turnHandle, String itemId, String title) {
        var commandKey = commandKey(session, itemId);
        scheduledCommandDeltas.remove(commandKey);
        var buffer = pendingCommandDeltas.remove(commandKey);
        if (buffer == null) return;
        String delta;
        synchronized (buffer) { delta = buffer.toString(); }
        if (!delta.isEmpty() && !completedCommandItems.contains(commandKey)) ApplicationManager.getApplication().invokeLater(
            () -> sessionCoordinator.apply(turnHandle, current -> {
                if (!completedCommandItems.contains(commandKey)) {
                    appendEntry(current, itemId, ConversationEntry.Kind.COMMAND, title, delta);
                }
            }));
    }

    private String commandKey(ConversationSession session, String itemId) {
        return session.id().value() + "\u0000" + itemId;
    }

    private String fallbackId(ConversationSession session, ConversationEntry.Kind kind) {
        return (session.providerTurnId() == null ? "turn" : session.providerTurnId()) + ":" + kind;
    }

    private void renderStartedItem(ConversationSession session, JsonObject item) {
        if (item == null) return;
        var id = string(item, "id", fallbackId(session, ConversationEntry.Kind.NOTICE));
        var type = string(item, "type", "");
        if (!CodexSettingsState.getInstance().getState().streamResponses
            && !Objects.equals(type, "fileChange")) return;
        switch (type) {
            case "agentMessage" -> replaceEntry(session, id, ConversationEntry.Kind.ASSISTANT, "Codex", "");
            case "plan" -> replaceEntry(session, id, ConversationEntry.Kind.PLAN, "计划", "");
            case "commandExecution" -> {
                completedCommandItems.remove(commandKey(session, id));
                replaceEntry(session, id, ConversationEntry.Kind.COMMAND, "命令", "$ " + string(item, "command", "") + "\n\n");
            }
            case "mcpToolCall" -> replaceEntry(session, id, ConversationEntry.Kind.MCP, "MCP 工具", string(item, "server", "") + " / " + string(item, "tool", ""));
            case "fileChange" -> updateFileChangeDiffs(session.id().value(), item);
            default -> {
            }
        }
    }

    private void renderCompletedItem(ConversationSession session, JsonObject item) {
        if (item == null) return;
        var id = string(item, "id", fallbackId(session, ConversationEntry.Kind.NOTICE));
        switch (string(item, "type", "")) {
            case "userMessage" -> {
                if (session.pendingUserMessageCount() > 0) {
                    session.pendingUserMessageCount(session.pendingUserMessageCount() - 1);
                    session.pendingUserBody(null);
                } else {
                    var content = array(item, "content");
                    addEntry(session, new ConversationEntry(
                        ConversationEntry.Kind.USER,
                        "你",
                        userMessageText(content),
                        id,
                        userMessageReferencePaths(content)
                    ));
                }
            }
            case "agentMessage" -> replaceEntry(session, id, ConversationEntry.Kind.ASSISTANT, "Codex", string(item, "text", ""));
            case "plan" -> replaceEntry(session, id, ConversationEntry.Kind.PLAN, "计划", string(item, "text", ""));
            case "commandExecution" -> {
                var commandKey = commandKey(session, id);
                completedCommandItems.add(commandKey);
                scheduledCommandDeltas.remove(commandKey);
                pendingCommandDeltas.remove(commandKey);
                var body = new StringBuilder("$ ").append(string(item, "command", ""));
                var output = string(item, "aggregatedOutput", "");
                if (!output.isBlank()) body.append("\n\n").append(output);
                var hasExitCode = item.has("exitCode") && !item.get("exitCode").isJsonNull();
                var exitCode = hasExitCode ? item.get("exitCode").getAsInt() : 0;
                if (hasExitCode) body.append("\n\n退出码：").append(exitCode);
                var status = string(item, "status", "");
                if (status.isBlank()) status = !hasExitCode || exitCode == 0 ? "completed" : "failed";
                body.append("\n\n执行状态：").append(status);
                replaceEntry(session, id, ConversationEntry.Kind.COMMAND, "命令", body.toString());
            }
            case "mcpToolCall" -> replaceEntry(session, id, ConversationEntry.Kind.MCP, "MCP 工具", string(item, "server", "") + " / " + string(item, "tool", "") + "\n状态：" + string(item, "status", ""));
            case "fileChange" -> updateFileChangeDiffs(session.id().value(), item);
            case "contextCompaction" -> addEntry(session, new ConversationEntry(ConversationEntry.Kind.NOTICE, "上下文整理", "Codex 已压缩当前会话上下文。", id));
            default -> {
            }
        }
    }

    @Override
    public void onServerRequest(long requestId, String method, JsonObject params) {
        ApplicationManager.getApplication().invokeLater(() -> {
            var threadId = string(params, "threadId", "");
            var session = sessionForThread(threadId);
            if (session == null && threadId.isBlank()) session = activeSession();
            if (session == null) {
                approvalCoordinator.decline(requestId);
                return;
            }
            var turnHandle = session.handle();
            var settings = CodexSettingsState.getInstance().getState();
            var targetSession = session;
            approvalCoordinator.handle(
                requestId,
                method,
                params,
                turnHandle,
                settings.approvalPolicy,
                settings.permissionDialogTimeoutSeconds,
                questions -> publishUserQuestions(targetSession, requestId, questions)
            );
        });
    }

    private void publishUserQuestions(ConversationSession session, long requestId, JsonArray questions) {
        var event = event(BridgeEvent.Type.QUESTION, session.id()).requestId(Long.toString(requestId));
        event.add("questions", questions);
        sendEvent(event);
        var settings = CodexSettingsState.getInstance().getState();
        notifyAttention("Codex 有一些问题想问你", session.title(), settings.askUserQuestionNotificationEnabled, settings.askUserQuestionSoundEnabled);
    }

    private void answerQuestions(BridgeCommand command, boolean cancelled) {
        var request = command.payload();
        approvalCoordinator.answer(
            command.requestId(),
            command.sessionId(),
            command.turnId(),
            command.generation(),
            cancelled || !request.has("answers") || !request.get("answers").isJsonObject()
                ? null
                : request.getAsJsonObject("answers"),
            cancelled,
            command.legacy()
        );
    }

    @Override
    public void onProtocolError(String message, Throwable error) {
        setBusy(false);
        addEntry(new ConversationEntry(ConversationEntry.Kind.ERROR, "连接错误", message, null));
    }

    private void asyncError(String title, Throwable throwable) {
        asyncError(activeSession(), title, throwable);
    }

    private void asyncError(ConversationSession session, String title, Throwable throwable) {
        var error = unwrap(throwable);
        addEntry(session, new ConversationEntry(
            ConversationEntry.Kind.ERROR, title, Objects.toString(error.getMessage(), error.toString()), null));
    }

    private void updateFileChangeDiffs(String sessionId, JsonObject object) {
        // 结构化补丁事件只包含本次文件修改，逐项转交给修改捕获服务。
        for (var element : array(object, "changes")) {
            if (!element.isJsonObject()) continue;
            var change = element.getAsJsonObject();
            changeService.trackProviderFile(
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
        approvalCoordinator.clear();
        codex.removeListener(this);
        codexProvider.close();
        changeService.removeListener(changeListener);
        attentionService.close();
    }

}
