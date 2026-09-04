package com.codexgui.conversation;

import com.codexgui.service.CodexAppServerService;
import com.codexgui.settings.CodexSettingsState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.util.concurrency.AppExecutorUtil;

import javax.swing.Timer;
import java.awt.Dialog;
import java.awt.Window;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class ApprovalCoordinator {
    private final Project project;
    private final CodexAppServerService service;
    private final Predicate<TurnHandle> currentTurn;
    private final PendingInteractionRegistry pending = new PendingInteractionRegistry();

    public ApprovalCoordinator(Project project, CodexAppServerService service, Predicate<TurnHandle> currentTurn) {
        this.project = project;
        this.service = service;
        this.currentTurn = currentTurn;
    }

    public void handle(
        long requestId,
        String method,
        JsonObject params,
        TurnHandle handle,
        String approvalPolicy,
        int timeoutSeconds,
        Consumer<JsonArray> questionSink
    ) {
        var type = typeFor(method);
        if (handle == null || type == null) {
            decline(requestId);
            return;
        }
        // 全自动策略仅处理审批，结构化问题始终保留给用户作答。
        if (Objects.equals(approvalPolicy, "never") && autoApprove(requestId, type, params)) return;
        pending.clearExpired(Instant.now());
        pending.register(Long.toString(requestId), handle, type,
            Instant.now().plusSeconds(Math.max(30, timeoutSeconds)));
        switch (type) {
            case COMMAND_APPROVAL -> showDecisionDialog(
                requestId,
                "Codex 请求执行：\n\n" + string(params, "command", "未知命令"),
                "命令执行审批",
                new String[]{"允许一次", "本会话允许", "拒绝", "拒绝并停止"}
            );
            case FILE_APPROVAL -> showDecisionDialog(
                requestId,
                string(params, "reason", "Codex 请求修改工作区文件"),
                "文件修改审批",
                new String[]{"允许一次", "本会话允许", "拒绝", "拒绝并停止"}
            );
            case USER_INPUT -> publishQuestions(requestId, params, questionSink);
            case PERMISSIONS_APPROVAL -> showPermissionsDialog(requestId, params);
        }
    }

    public void answer(
        String requestId,
        SessionId sessionId,
        String turnId,
        long generation,
        JsonObject answers,
        boolean cancelled,
        boolean legacy
    ) {
        var numericId = requestIdAsLong(requestId);
        if (numericId < 0) return;
        var interaction = pending.take(
            requestId, sessionId, turnId, generation, PendingInteractionRegistry.Type.USER_INPUT, legacy);
        if (interaction == null || !currentTurn.test(interaction.turnHandle())) return;
        var result = new JsonObject();
        result.add("answers", cancelled || answers == null ? new JsonObject() : answers);
        service.respondToServerRequest(numericId, result);
    }

    public void decline(long requestId) {
        var result = new JsonObject();
        result.addProperty("decision", "decline");
        service.respondToServerRequest(requestId, result);
    }

    public void clearTurn(TurnHandle handle) { pending.clearTurn(handle); }
    public void clearSession(SessionId sessionId) { pending.clearSession(sessionId); }
    public void clear() { pending.clear(); }

    static PendingInteractionRegistry.Type typeFor(String method) {
        return switch (Objects.requireNonNullElse(method, "")) {
            case "item/commandExecution/requestApproval" -> PendingInteractionRegistry.Type.COMMAND_APPROVAL;
            case "item/fileChange/requestApproval" -> PendingInteractionRegistry.Type.FILE_APPROVAL;
            case "item/tool/requestUserInput" -> PendingInteractionRegistry.Type.USER_INPUT;
            case "item/permissions/requestApproval" -> PendingInteractionRegistry.Type.PERMISSIONS_APPROVAL;
            default -> null;
        };
    }

    private boolean autoApprove(long requestId, PendingInteractionRegistry.Type type, JsonObject params) {
        if (type == PendingInteractionRegistry.Type.COMMAND_APPROVAL
            || type == PendingInteractionRegistry.Type.FILE_APPROVAL) {
            var result = new JsonObject();
            result.addProperty("decision", "accept");
            service.respondToServerRequest(requestId, result);
            return true;
        }
        if (type == PendingInteractionRegistry.Type.PERMISSIONS_APPROVAL) {
            var result = new JsonObject();
            result.add("permissions", object(params, "permissions"));
            result.addProperty("scope", "turn");
            service.respondToServerRequest(requestId, result);
            return true;
        }
        return false;
    }

    private void publishQuestions(long requestId, JsonObject params, Consumer<JsonArray> questionSink) {
        var questions = array(params, "questions");
        if (!questions.isEmpty()) {
            questionSink.accept(questions);
            return;
        }
        pending.take(Long.toString(requestId));
        var result = new JsonObject();
        result.add("answers", new JsonObject());
        service.respondToServerRequest(requestId, result);
    }

    private void showDecisionDialog(long requestId, String message, String title, String[] options) {
        CompletableFuture.supplyAsync(
            () -> showTimedDialog(message, title, options), AppExecutorUtil.getAppExecutorService())
            .thenAccept(choice -> ApplicationManager.getApplication().invokeLater(
                () -> respondDecision(requestId, choice)));
    }

    private void showPermissionsDialog(long requestId, JsonObject params) {
        CompletableFuture.supplyAsync(() -> showTimedDialog(
            string(params, "reason", "Codex 请求临时提升权限"),
            "权限审批",
            new String[]{"允许", "拒绝"}
        ), AppExecutorUtil.getAppExecutorService()).thenAccept(choice -> ApplicationManager.getApplication().invokeLater(() -> {
            var interaction = pending.take(Long.toString(requestId));
            if (interaction == null) return;
            var result = new JsonObject();
            var accepted = choice == Messages.YES && currentTurn.test(interaction.turnHandle());
            result.add("permissions", accepted ? object(params, "permissions") : new JsonObject());
            result.addProperty("scope", "turn");
            service.respondToServerRequest(requestId, result);
        }));
    }

    private int showTimedDialog(String message, String title, String[] options) {
        var projectFrame = WindowManager.getInstance().getFrame(project);
        var timeoutMillis = Math.max(30, CodexSettingsState.getInstance().getState().permissionDialogTimeoutSeconds) * 1000;
        // Swing 模态框仍会处理 Timer 事件，因此超时只关闭当前项目的同名审批窗口。
        var timer = new Timer(timeoutMillis, event -> {
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
        var interaction = pending.take(Long.toString(requestId));
        if (interaction == null) return;
        if (!currentTurn.test(interaction.turnHandle())) choice = -1;
        var result = new JsonObject();
        result.addProperty("decision", switch (choice) {
            case 0 -> "accept";
            case 1 -> "acceptForSession";
            case 3 -> "cancel";
            default -> "decline";
        });
        service.respondToServerRequest(requestId, result);
    }

    private static JsonArray array(JsonObject source, String key) {
        if (source == null || !source.has(key) || !source.get(key).isJsonArray()) return new JsonArray();
        return source.getAsJsonArray(key);
    }

    private static JsonObject object(JsonObject source, String key) {
        if (source == null || !source.has(key) || !source.get(key).isJsonObject()) return new JsonObject();
        return source.getAsJsonObject(key);
    }

    private static String string(JsonObject source, String key, String fallback) {
        if (source == null || !source.has(key) || source.get(key).isJsonNull()) return fallback;
        try {
            return source.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long requestIdAsLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
