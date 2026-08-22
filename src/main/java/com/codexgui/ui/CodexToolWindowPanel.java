package com.codexgui.ui;

import com.codexgui.model.Attachment;
import com.codexgui.model.ChangeEntry;
import com.codexgui.model.ConversationEntry;
import com.codexgui.service.CodexAppServerService;
import com.codexgui.service.CodexEventListener;
import com.codexgui.service.NotificationSoundPlayer;
import com.codexgui.service.WorkspaceChangeService;
import com.codexgui.settings.CodexSettingsState;
import com.codexgui.settings.CodexProjectSettingsState;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.ide.BrowserUtil;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
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

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

final class CodexToolWindowPanel extends JPanel implements Disposable, CodexEventListener {
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final Project project;
    private final CodexAppServerService codex;
    private final WorkspaceChangeService changeService;
    private final NotificationSoundPlayer notificationSoundPlayer = new NotificationSoundPlayer();
    private final List<Attachment> attachments = new ArrayList<>();
    private final List<ConversationEntry> transcript = new ArrayList<>();
    private final Consumer<List<ChangeEntry>> changeListener;
    private final JBCefBrowser browser;
    private final JBCefJSQuery bridge;

    private String currentThreadId;
    private String currentTurnId;
    private String currentTitle = "新会话";
    private String pendingUserBody;
    private boolean busy;
    private boolean pageReady;
    private long usageUsedTokens;
    private long usageMaxTokens;

    CodexToolWindowPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        this.codex = CodexAppServerService.getInstance(project);
        this.changeService = WorkspaceChangeService.getInstance(project);
        this.changeListener = this::publishChanges;

        if (!JBCefApp.isSupported()) {
            browser = null;
            bridge = null;
            add(new JLabel("当前 JetBrains Runtime 不支持 JCEF，无法显示 Codex GUI。", SwingConstants.CENTER));
            return;
        }

        browser = new JBCefBrowser();
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
            switch (action) {
                case "ready" -> bootstrap();
                case "reconnect" -> codex.restart().thenRun(this::loadModels).exceptionally(error -> {
                    asyncError("重新连接 Codex CLI 失败", error);
                    return null;
                });
                case "send" -> sendInput(string(request, "text", ""));
                case "stop" -> interruptCurrentTurn();
                case "new" -> newConversation();
                case "history" -> loadHistory(string(request, "search", ""));
                case "openThread" -> openThread(string(request, "id", ""));
                case "rename" -> renameCurrentThread();
                case "export" -> exportConversation();
                case "pickFile" -> chooseAttachment(false);
                case "pickImage" -> chooseAttachment(true);
                case "removeAttachment" -> removeAttachment(integer(request, "index"));
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
                case "behaviorSetting" -> updateBehaviorSetting(request);
                case "browseNotificationSound" -> browseNotificationSound();
                case "testNotificationSound" -> playConfiguredSound(true);
                case "toggleReasoning" -> toggleReasoning();
                case "toggleStreaming" -> toggleStreaming();
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
        var state = new JsonObject();
        state.addProperty("connected", codex.isConnected());
        state.addProperty("busy", busy);
        state.addProperty("title", currentTitle);
        if (currentThreadId != null) state.addProperty("threadId", currentThreadId);
        state.addProperty("model", settings.model);
        state.addProperty("effort", settings.reasoningEffort);
        state.addProperty("serviceTier", settings.serviceTier);
        state.addProperty("approval", settings.approvalPolicy);
        state.addProperty("sandbox", settings.sandboxMode);
        state.addProperty("streamResponses", settings.streamResponses);
        state.addProperty("showReasoning", settings.showReasoning);
        addBehaviorSettings(state, settings);
        state.addProperty("globalInstructions", settings.globalInstructions);
        state.addProperty("activePromptId", settings.activePromptId);
        state.add("prompts", promptsJson(settings.prompts));
        state.addProperty("projectInstructions", CodexProjectSettingsState.getInstance(project).getState().projectInstructions);
        state.addProperty("activeAgentId", settings.activeAgentId);
        state.add("agents", agentsJson(settings.agents));
        state.add("attachments", attachmentsJson());
        var event = event("bootstrap");
        event.add("state", state);
        sendEvent(event);
        transcript.forEach(this::publishEntry);
        publishChanges(changeService.getChanges());
        publishSkills(false);
        // 页面可能晚于 CLI 连接完成，准备完成后重新请求模型，避免模型事件丢失。
        loadModels();
    }

    private void sendInput(String text) {
        text = text.trim();
        if (busy || text.isBlank() && attachments.isEmpty()) return;
        if (!codex.isConnected()) {
            var reconnectText = text;
            codex.start().thenRun(() -> sendInput(reconnectText)).exceptionally(error -> {
                asyncError("Codex CLI 未连接", error);
                return null;
            });
            return;
        }
        if (attachments.isEmpty() && handleNativeCommand(text)) return;

        var settings = CodexSettingsState.getInstance().getState();
        var openedFile = settings.sendOpenedFilePath ? openedFileContext() : "";
        var inputText = openedFile.isBlank() ? text : text + "\n\n[当前打开文件路径] " + openedFile;
        var sentAttachments = List.copyOf(attachments);
        var display = new StringBuilder(inputText);
        sentAttachments.forEach(attachment -> display.append("\n").append(switch (attachment.kind()) {
            case IMAGE -> "[图片] ";
            case FILE -> "@";
        }).append(attachment.name()));
        pendingUserBody = display.toString().trim();
        addEntry(new ConversationEntry(ConversationEntry.Kind.USER, "你", pendingUserBody, null));
        attachments.clear();
        publishAttachments();
        setBusy(true);

        var capture = changeService.beginCaptureAsync().thenCompose(ignored -> currentThreadId == null
            ? codex.startThread(
                settings.model,
                settings.reasoningEffort,
                settings.serviceTier,
                settings.approvalPolicy,
                settings.sandboxMode,
                developerInstructions()
            )
                .thenApply(result -> {
                    currentThreadId = result.getAsJsonObject("thread").get("id").getAsString();
                    publishThread();
                    return currentThreadId;
                })
            : CompletableFuture.completedFuture(currentThreadId));
        capture.thenCompose(threadId -> codex.startTurn(
            threadId, inputText, sentAttachments, settings.model, settings.reasoningEffort,
            settings.serviceTier,
            settings.approvalPolicy, settings.sandboxMode
        )).thenAccept(result -> {
            var turn = result.getAsJsonObject("turn");
            if (turn != null && turn.has("id")) currentTurnId = turn.get("id").getAsString();
        }).exceptionally(error -> {
            changeService.finishCaptureAsync();
            setBusy(false);
            asyncError("无法发送消息", error);
            return null;
        });
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
            var event = event("bootstrap");
            var state = new JsonObject();
            state.add("models", models);
            state.addProperty("model", settings.model);
            event.add("state", state);
            sendEvent(event);
        }).exceptionally(error -> {
            asyncError("无法读取 Codex 模型列表", error);
            return null;
        });
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
        codex.resumeThread(threadId).thenAccept(this::renderThread).exceptionally(error -> {
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
        if (busy) return;
        var settings = CodexSettingsState.getInstance().getState();
        if (settings.newSessionConfirmEnabled && !transcript.isEmpty()
            && Messages.showYesNoDialog(project, "当前会话已有消息，确定要新建会话吗？", "新建会话", "新建", "取消", Messages.getQuestionIcon()) != Messages.YES) {
            return;
        }
        currentThreadId = null;
        currentTurnId = null;
        currentTitle = "新会话";
        clearConversation();
        publishThread();
    }

    private void clearConversation() {
        transcript.clear();
        usageUsedTokens = 0;
        usageMaxTokens = 0;
        var event = event("clear");
        event.addProperty("title", currentTitle);
        if (currentThreadId != null) event.addProperty("threadId", currentThreadId);
        sendEvent(event);
    }

    private void renameCurrentThread() {
        if (currentThreadId == null) return;
        var name = Messages.showInputDialog(project, "输入新的会话名称：", "重命名会话", Messages.getQuestionIcon(), currentTitle, null);
        if (name == null || name.isBlank()) return;
        currentTitle = name.trim();
        codex.setThreadName(currentThreadId, currentTitle).thenRun(() -> {
            publishThread();
            loadHistory("");
        }).exceptionally(error -> {
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

    private void publishSkills(boolean forceReload) {
        publishSkills(forceReload, forceReload);
    }

    private void publishSkills(boolean forceReload, boolean notify) {
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

    private void removeAttachment(int index) {
        if (index < 0 || index >= attachments.size()) return;
        attachments.remove(index);
        publishAttachments();
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

    private void publishChanges(List<ChangeEntry> changes) {
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
        event.add("items", items);
        sendEvent(event);
    }

    private ChangeEntry changeAt(int index) {
        var changes = changeService.getChanges();
        return index >= 0 && index < changes.size() ? changes.get(index) : null;
    }

    private void acceptAllChanges() {
        // 空列表直接结束，避免把无意义的全量扫描提交到界面线程。
        if (changeService.getChanges().isEmpty()) return;
        changeService.acceptAll();
    }

    private void acceptChange(int index) {
        var change = changeAt(index);
        if (change != null) changeService.accept(change);
    }

    private void revertChange(int index) {
        var change = changeAt(index);
        if (change == null) return;
        if (Messages.showYesNoDialog(project, "撤销 Codex 对该文件的全部修改？\n\n" + change.path(), "撤销文件修改", "撤销", "取消", Messages.getWarningIcon()) != Messages.YES) return;
        try {
            changeService.revert(change);
        } catch (IOException error) {
            Messages.showErrorDialog(project, error.getMessage(), "无法撤销修改");
        }
    }

    private void revertAllChanges() {
        if (changeService.getChanges().isEmpty()) return;
        if (Messages.showYesNoDialog(project, "撤销当前回合捕获的全部文件修改？", "撤销全部修改", "全部撤销", "取消", Messages.getWarningIcon()) != Messages.YES) return;
        try {
            changeService.revertAll();
        } catch (IOException error) {
            Messages.showErrorDialog(project, error.getMessage(), "部分文件无法撤销");
        }
    }

    private void openChange(int index) {
        var change = changeAt(index);
        if (change == null) return;
        var before = change.beforeContent() == null ? "" : new String(change.beforeContent(), StandardCharsets.UTF_8);
        var after = change.afterContent() == null ? "" : new String(change.afterContent(), StandardCharsets.UTF_8);
        var factory = DiffContentFactory.getInstance();
        DiffManager.getInstance().showDiff(project, new SimpleDiffRequest("Codex 修改 · " + change.displayName(changeService.getRoot()), factory.create(project, before), factory.create(project, after), "回合开始前", "Codex 修改后"));
    }

    private void searchConversation() {
        var query = Messages.showInputDialog(project, "输入要在当前会话中查找的文字：", "搜索会话", Messages.getQuestionIcon());
        if (query == null || query.isBlank()) return;
        var count = transcript.stream().filter(entry -> entry.body().toLowerCase().contains(query.toLowerCase())).count();
        toast("找到 " + count + " 条匹配消息");
    }

    private void updateSetting(String key, String value) {
        var settings = CodexSettingsState.getInstance().getState();
        // 只更新 Codex 原生会话参数，标准档位省略字段以使用服务默认值。
        switch (key) {
            case "model" -> settings.model = value;
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
            case "sendOpenedFilePath" -> settings.sendOpenedFilePath = bool(request, "value", true);
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

    private String openedFileContext() {
        var selectedFiles = FileEditorManager.getInstance(project).getSelectedFiles();
        if (selectedFiles.length == 0) return "";
        try {
            var file = Path.of(selectedFiles[0].getPath()).toAbsolutePath().normalize();
            var root = changeService.getRoot().toAbsolutePath().normalize();
            return file.startsWith(root) ? root.relativize(file).toString() : file.toString();
        } catch (RuntimeException ignored) {
            return selectedFiles[0].getPath();
        }
    }

    private void publishFileContext() {
        var settings = CodexSettingsState.getInstance().getState();
        var event = event("fileContext");
        event.addProperty("path", settings.sendOpenedFilePath ? openedFileContext() : "");
        sendEvent(event);
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

    private void toggleReasoning() {
        var settings = CodexSettingsState.getInstance().getState();
        settings.showReasoning = !settings.showReasoning;
        var state = new JsonObject();
        state.addProperty("showReasoning", settings.showReasoning);
        var event = event("bootstrap");
        event.add("state", state);
        sendEvent(event);
    }

    private void toggleStreaming() {
        var settings = CodexSettingsState.getInstance().getState();
        settings.streamResponses = !settings.streamResponses;
        publishSettings();
    }

    private void saveInstructions(JsonObject request) {
        var settings = CodexSettingsState.getInstance().getState();
        settings.globalInstructions = string(request, "global", "").trim();
        CodexProjectSettingsState.getInstance(project).getState().projectInstructions = string(request, "project", "").trim();
        publishSettings();
        toast("指令已保存，将从下一个新会话开始生效");
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
        var projectInstructions = CodexProjectSettingsState.getInstance(project).getState().projectInstructions;
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
        var state = new JsonObject();
        state.addProperty("model", settings.model);
        state.addProperty("effort", settings.reasoningEffort);
        state.addProperty("serviceTier", settings.serviceTier);
        state.addProperty("approval", settings.approvalPolicy);
        state.addProperty("sandbox", settings.sandboxMode);
        state.addProperty("streamResponses", settings.streamResponses);
        state.addProperty("showReasoning", settings.showReasoning);
        addBehaviorSettings(state, settings);
        state.addProperty("globalInstructions", settings.globalInstructions);
        state.addProperty("activePromptId", settings.activePromptId);
        state.add("prompts", promptsJson(settings.prompts));
        state.addProperty("projectInstructions", CodexProjectSettingsState.getInstance(project).getState().projectInstructions);
        state.addProperty("activeAgentId", settings.activeAgentId);
        state.add("agents", agentsJson(settings.agents));
        var event = event("bootstrap");
        event.add("state", state);
        sendEvent(event);
    }

    private void compactCurrentThread() {
        if (currentThreadId == null || busy) return;
        codex.compactThread(currentThreadId).thenRun(() -> toast("上下文压缩完成")).exceptionally(error -> {
            asyncError("无法压缩上下文", error);
            return null;
        });
    }

    private void reviewCurrentChanges() {
        if (currentThreadId == null || busy) {
            toast("请先创建或打开一个会话");
            return;
        }
        setBusy(true);
        codex.reviewUncommittedChanges(currentThreadId).thenAccept(result -> {
            var turn = result.getAsJsonObject("turn");
            if (turn != null) currentTurnId = string(turn, "id", null);
        }).exceptionally(error -> {
            setBusy(false);
            asyncError("无法启动代码审查", error);
            return null;
        });
    }

    private void rollbackLastTurn() {
        if (currentThreadId == null || busy) return;
        if (Messages.showYesNoDialog(project, "回退上一轮会话？工作区文件仍由修改面板单独管理。", "回退上一轮", "回退", "取消", Messages.getWarningIcon()) != Messages.YES) return;
        codex.rollbackThread(currentThreadId).thenAccept(this::renderThread).exceptionally(error -> {
            asyncError("无法回退会话", error);
            return null;
        });
    }

    private void interruptCurrentTurn() {
        if (currentThreadId == null || currentTurnId == null) return;
        codex.interruptTurn(currentThreadId, currentTurnId).exceptionally(error -> {
            asyncError("无法停止当前回合", error);
            return null;
        });
    }

    private void showMcpServers() {
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
        var event = event("busy");
        event.addProperty("busy", value);
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
        for (var current : List.copyOf(transcript)) {
            if (!Objects.equals(itemId, current.itemId())) continue;
            replaceEntry(itemId, kind, title, current.body() + delta);
            return;
        }
        replaceEntry(itemId, kind, title, delta);
    }

    private JsonObject entryJson(ConversationEntry entry) {
        var json = new JsonObject();
        json.addProperty("kind", entry.kind().name().toLowerCase());
        json.addProperty("title", entry.title());
        json.addProperty("body", entry.body());
        if (entry.itemId() != null) json.addProperty("itemId", entry.itemId());
        return json;
    }

    private void publishThread() {
        var event = event("thread");
        if (currentThreadId != null) event.addProperty("id", currentThreadId);
        event.addProperty("title", currentTitle);
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
        state.addProperty("activeFile", settings.sendOpenedFilePath ? openedFileContext() : "");
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
        var event = event("connection");
        event.addProperty("connected", connected);
        event.addProperty("detail", detail);
        sendEvent(event);
        if (connected) setBusy(false);
    }

    @Override
    public void onNotification(String method, JsonObject params) {
        switch (method) {
            case "turn/started" -> {
                var turn = params.getAsJsonObject("turn");
                if (turn != null) currentTurnId = string(turn, "id", currentTurnId);
                setBusy(true);
            }
            case "item/started" -> renderStartedItem(params.getAsJsonObject("item"));
            case "item/completed" -> renderCompletedItem(params.getAsJsonObject("item"));
            case "item/agentMessage/delta" -> appendDelta(params, ConversationEntry.Kind.ASSISTANT, "Codex");
            case "item/reasoning/summaryTextDelta", "item/reasoning/textDelta" -> {
                if (CodexSettingsState.getInstance().getState().showReasoning) appendDelta(params, ConversationEntry.Kind.REASONING, "推理");
            }
            case "item/plan/delta" -> appendDelta(params, ConversationEntry.Kind.PLAN, "计划");
            case "item/commandExecution/outputDelta" -> appendDelta(params, ConversationEntry.Kind.COMMAND, "命令");
            case "item/fileChange/outputDelta", "item/fileChange/patchUpdated" -> changeService.rescanAsync();
            case "turn/diff/updated" -> changeService.updateServerDiff(string(params, "diff", ""));
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
                changeService.finishCaptureAsync();
                currentTurnId = null;
                pendingUserBody = null;
                setBusy(false);
                loadHistory("");
                if (completedActiveTurn) {
                    var settings = CodexSettingsState.getInstance().getState();
                    notifyAttention("Codex 任务已完成", currentTitle, settings.taskCompletionNotificationEnabled, settings.taskCompletionSoundEnabled);
                }
            }
            case "error" -> addEntry(new ConversationEntry(ConversationEntry.Kind.ERROR, "Codex 错误", params.toString(), null));
            case "warning", "configWarning", "deprecationNotice" -> addEntry(new ConversationEntry(ConversationEntry.Kind.NOTICE, "提示", string(params, "message", params.toString()), null));
            default -> {
            }
        }
    }

    private void appendDelta(JsonObject params, ConversationEntry.Kind kind, String title) {
        if (!CodexSettingsState.getInstance().getState().streamResponses) return;
        var delta = string(params, "delta", "");
        if (!delta.isEmpty()) appendEntry(string(params, "itemId", fallbackId(kind)), kind, title, delta);
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
            case "reasoning" -> {
                if (CodexSettingsState.getInstance().getState().showReasoning) replaceEntry(id, ConversationEntry.Kind.REASONING, "推理", "");
            }
            case "plan" -> replaceEntry(id, ConversationEntry.Kind.PLAN, "计划", "");
            case "commandExecution" -> replaceEntry(id, ConversationEntry.Kind.COMMAND, "命令", "$ " + string(item, "command", ""));
            case "mcpToolCall" -> replaceEntry(id, ConversationEntry.Kind.MCP, "MCP 工具", string(item, "server", "") + " / " + string(item, "tool", ""));
            case "fileChange" -> changeService.rescanAsync();
            default -> {
            }
        }
    }

    private void renderCompletedItem(JsonObject item) {
        if (item == null) return;
        var id = string(item, "id", fallbackId(ConversationEntry.Kind.NOTICE));
        switch (string(item, "type", "")) {
            case "userMessage" -> {
                if (pendingUserBody == null) addEntry(new ConversationEntry(ConversationEntry.Kind.USER, "你", userMessageText(array(item, "content")), id));
            }
            case "agentMessage" -> replaceEntry(id, ConversationEntry.Kind.ASSISTANT, "Codex", string(item, "text", ""));
            case "reasoning" -> {
                if (CodexSettingsState.getInstance().getState().showReasoning) replaceEntry(id, ConversationEntry.Kind.REASONING, "推理", joinStrings(array(item, "summary")));
            }
            case "plan" -> replaceEntry(id, ConversationEntry.Kind.PLAN, "计划", string(item, "text", ""));
            case "commandExecution" -> {
                var body = new StringBuilder("$ ").append(string(item, "command", ""));
                var output = string(item, "aggregatedOutput", "");
                if (!output.isBlank()) body.append("\n\n").append(output);
                if (item.has("exitCode") && !item.get("exitCode").isJsonNull()) body.append("\n\n退出码：").append(item.get("exitCode").getAsInt());
                replaceEntry(id, ConversationEntry.Kind.COMMAND, "命令", body.toString());
            }
            case "mcpToolCall" -> replaceEntry(id, ConversationEntry.Kind.MCP, "MCP 工具", string(item, "server", "") + " / " + string(item, "tool", "") + "\n状态：" + string(item, "status", ""));
            case "fileChange" -> changeService.rescanAsync();
            case "contextCompaction" -> addEntry(new ConversationEntry(ConversationEntry.Kind.NOTICE, "上下文整理", "Codex 已压缩当前会话上下文。", id));
            default -> {
            }
        }
    }

    @Override
    public void onServerRequest(long requestId, String method, JsonObject params) {
        ApplicationManager.getApplication().invokeLater(() -> {
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
        var choice = showTimedDialog("Codex 请求执行：\n\n" + string(params, "command", "未知命令"), "命令执行审批", new String[]{"允许一次", "本会话允许", "拒绝", "拒绝并停止"});
        respondDecision(requestId, choice);
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
        var choice = showTimedDialog(string(params, "reason", "Codex 请求修改工作区文件"), "文件修改审批", new String[]{"允许一次", "本会话允许", "拒绝", "拒绝并停止"});
        respondDecision(requestId, choice);
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
        var approved = showTimedDialog(string(params, "reason", "Codex 请求临时提升权限"), "权限审批", new String[]{"允许", "拒绝"}) == Messages.YES;
        var result = new JsonObject();
        result.add("permissions", approved && params.has("permissions") ? params.getAsJsonObject("permissions") : new JsonObject());
        result.addProperty("scope", "turn");
        codex.respondToServerRequest(requestId, result);
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
                case "mention" -> "@" + string(input, "name", string(input, "path", "文件"));
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

    @Override
    public void dispose() {
        codex.removeListener(this);
        changeService.removeListener(changeListener);
        notificationSoundPlayer.close();
        if (bridge != null) bridge.dispose();
        if (browser != null) browser.dispose();
    }

}
