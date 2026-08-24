package com.codexgui.service;

import com.codexgui.model.Attachment;
import com.codexgui.model.FileReference;
import com.codexgui.settings.CodexSettingsState;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service(Service.Level.PROJECT)
public final class CodexAppServerService implements Disposable {
    private static final Logger LOG = Logger.getInstance(CodexAppServerService.class);
    private static final Gson GSON = new Gson();
    private static final String FILE_REFERENCE_MARKER = "\uFFFC";

    private final Project project;
    private final AtomicLong requestSequence = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<CodexEventListener> listeners = new CopyOnWriteArrayList<>();
    private final Object writerLock = new Object();
    private final Object lifecycleLock = new Object();

    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile boolean connected;
    private volatile CompletableFuture<Void> startFuture;

    public CodexAppServerService(Project project) {
        this.project = project;
    }

    public static CodexAppServerService getInstance(@NotNull Project project) {
        return project.getService(CodexAppServerService.class);
    }

    public void addListener(CodexEventListener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(CodexEventListener listener) {
        listeners.remove(listener);
    }

    public boolean isConnected() {
        return connected;
    }

    public CompletableFuture<Void> start() {
        synchronized (lifecycleLock) {
            if (connected) return CompletableFuture.completedFuture(null);
            if (startFuture != null && !startFuture.isDone()) return startFuture;
            startFuture = CompletableFuture.runAsync(this::startBlocking, AppExecutorUtil.getAppExecutorService());
            return startFuture;
        }
    }

    public CompletableFuture<Void> restart() {
        stopProcess("正在重启 Codex CLI");
        return start();
    }

    private void startBlocking() {
        var state = CodexSettingsState.getInstance().getState();
        var configuredExecutable = state.codexExecutable.isBlank() ? "codex" : state.codexExecutable;
        var executable = CodexExecutableResolver.resolve(configuredExecutable, SystemInfo.isWindows);
        try {
            var command = createCommand(executable);
            var builder = new ProcessBuilder(command);
            // JetBrains 进程的环境变量可能早于 Codex Desktop 启动，显式指定配置目录，确保读取同一份 config.toml/auth.json。
            var codexHome = System.getenv("CODEX_HOME");
            if (codexHome == null || codexHome.isBlank()) {
                codexHome = Path.of(System.getProperty("user.home", "."), ".codex").toString();
            }
            builder.environment().put("CODEX_HOME", codexHome);
            if (project.getBasePath() != null) builder.directory(Path.of(project.getBasePath()).toFile());
            process = builder.start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

            // 独立读取标准输出和错误输出，避免 app-server 的任一管道阻塞。
            AppExecutorUtil.getAppExecutorService().execute(this::readProtocolLoop);
            AppExecutorUtil.getAppExecutorService().execute(this::readErrorLoop);

            var capabilities = new JsonObject();
            capabilities.addProperty("experimentalApi", true);
            capabilities.addProperty("requestAttestation", false);
            var clientInfo = new JsonObject();
            clientInfo.addProperty("name", "codex-gui-jetbrains");
            clientInfo.addProperty("title", "Codex GUI for JetBrains");
            clientInfo.addProperty("version", "0.3.0");
            var params = new JsonObject();
            params.add("clientInfo", clientInfo);
            params.add("capabilities", capabilities);

            request("initialize", params).get(20, TimeUnit.SECONDS);
            notifyServer("initialized", null);
            connected = true;
            fireConnectionChanged(true, "Codex CLI 已连接");
        } catch (Exception error) {
            var message = startupFailureMessage(executable, error);
            LOG.warn(message, error);
            stopProcess("Codex CLI 启动失败");
            throw new IllegalStateException(message, error);
        }
    }

    private String startupFailureMessage(String executable, Exception error) {
        Throwable cause = error;
        while (cause.getCause() != null && (cause instanceof java.util.concurrent.ExecutionException
            || cause instanceof java.util.concurrent.CompletionException)) {
            cause = cause.getCause();
        }
        var detail = cause.getMessage();
        if (detail == null || detail.isBlank()) detail = cause.getClass().getSimpleName();
        return "无法启动 Codex CLI（" + executable + "）：" + detail
            + "。请安装 OpenAI Codex CLI，或在“设置 → 工具 → Codex GUI”中指定可执行文件。";
    }

    private List<String> createCommand(String executable) {
        var normalized = executable.toLowerCase();
        if (SystemInfo.isWindows && !normalized.endsWith(".exe")) {
            return List.of("cmd.exe", "/d", "/c", executable, "app-server", "--stdio");
        }
        return List.of(executable, "app-server", "--stdio");
    }

    public CompletableFuture<JsonObject> listThreads(String searchTerm) {
        var params = new JsonObject();
        params.addProperty("limit", 100);
        params.addProperty("sortKey", "updated_at");
        params.addProperty("sortDirection", "desc");
        if (project.getBasePath() != null) params.addProperty("cwd", project.getBasePath());
        if (searchTerm != null && !searchTerm.isBlank()) params.addProperty("searchTerm", searchTerm.trim());
        return request("thread/list", params);
    }

    public CompletableFuture<JsonObject> listModels() {
        var params = new JsonObject();
        params.addProperty("limit", 100);
        params.addProperty("includeHidden", false);
        return request("model/list", params);
    }

    public CompletableFuture<JsonObject> listSkills(boolean forceReload) {
        var params = new JsonObject();
        var roots = new JsonArray();
        if (project.getBasePath() != null) roots.add(project.getBasePath());
        params.add("cwds", roots);
        params.addProperty("forceReload", forceReload);
        return request("skills/list", params);
    }

    public CompletableFuture<JsonObject> setSkillEnabled(String path, boolean enabled) {
        var params = new JsonObject();
        params.addProperty("enabled", enabled);
        if (path != null && !path.isBlank()) params.addProperty("path", path);
        return request("skills/config/write", params);
    }

    public CompletableFuture<JsonObject> startThread(
        String model,
        String effort,
        String serviceTier,
        String approvalPolicy,
        String sandbox,
        String developerInstructions
    ) {
        var params = new JsonObject();
        if (project.getBasePath() != null) params.addProperty("cwd", project.getBasePath());
        if (model != null && !model.isBlank()) params.addProperty("model", model);
        if (serviceTier != null && !serviceTier.isBlank() && !Objects.equals(serviceTier, "standard")) params.addProperty("serviceTier", serviceTier);
        params.addProperty("approvalPolicy", approvalPolicy);
        params.addProperty("sandbox", sandbox);
        params.addProperty("ephemeral", false);
        // 只传递用户在插件设置中明确保存的指令，不注入第三方增强提示词。
        if (developerInstructions != null && !developerInstructions.isBlank()) {
            params.addProperty("developerInstructions", developerInstructions);
        }
        return request("thread/start", params);
    }

    public CompletableFuture<JsonObject> resumeThread(String threadId) {
        var params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.addProperty("excludeTurns", false);
        return request("thread/resume", params);
    }

    public CompletableFuture<JsonObject> readThread(String threadId) {
        var params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.addProperty("includeTurns", true);
        return request("thread/read", params);
    }

    public CompletableFuture<JsonObject> setThreadName(String threadId, String name) {
        var params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.addProperty("name", name);
        return request("thread/name/set", params);
    }

    public CompletableFuture<JsonObject> rollbackThread(String threadId) {
        var params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.addProperty("numTurns", 1);
        return request("thread/rollback", params);
    }

    public CompletableFuture<JsonObject> compactThread(String threadId) {
        var params = new JsonObject();
        params.addProperty("threadId", threadId);
        return request("thread/compact/start", params);
    }

    public CompletableFuture<JsonObject> reviewUncommittedChanges(String threadId) {
        var target = new JsonObject();
        target.addProperty("type", "uncommittedChanges");
        var params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.add("target", target);
        params.addProperty("delivery", "inline");
        return request("review/start", params);
    }

    public CompletableFuture<JsonObject> readAccount() {
        return request("account/read", new JsonObject());
    }

    public CompletableFuture<JsonObject> readUsage() {
        return request("account/usage/read", new JsonObject());
    }

    public CompletableFuture<JsonObject> readRateLimits() {
        return request("account/rateLimits/read", new JsonObject());
    }

    public CompletableFuture<JsonObject> listMcpServers(String threadId) {
        var params = new JsonObject();
        params.addProperty("limit", 100);
        params.addProperty("detail", "full");
        if (threadId != null) params.addProperty("threadId", threadId);
        return request("mcpServerStatus/list", params);
    }

    public CompletableFuture<JsonObject> reloadMcpServers() {
        return request("config/mcpServer/reload", new JsonObject());
    }

    public CompletableFuture<JsonObject> readConfig(String cwd) {
        var params = new JsonObject();
        if (cwd != null && !cwd.isBlank()) params.addProperty("cwd", cwd);
        params.addProperty("includeLayers", false);
        return request("config/read", params);
    }

    public CompletableFuture<JsonObject> writeConfigValue(String keyPath, JsonElement value) {
        var params = new JsonObject();
        params.addProperty("keyPath", keyPath);
        params.add("value", value == null ? com.google.gson.JsonNull.INSTANCE : value);
        params.addProperty("mergeStrategy", "replace");
        return request("config/value/write", params);
    }

    public CompletableFuture<JsonObject> loginMcpServer(String name, String threadId) {
        var params = new JsonObject();
        params.addProperty("name", name);
        if (threadId != null && !threadId.isBlank()) params.addProperty("threadId", threadId);
        return request("mcpServer/oauth/login", params);
    }

    public CompletableFuture<JsonObject> startTurn(
        String threadId,
        String text,
        List<Attachment> attachments,
        List<FileReference> fileReferences,
        String model,
        String effort,
        String serviceTier,
        String approvalPolicy,
        String sandboxMode
    ) {
        var params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.addProperty("approvalPolicy", approvalPolicy);
        if (model != null && !model.isBlank()) params.addProperty("model", model);
        if (effort != null && !effort.isBlank()) params.addProperty("effort", effort);
        if (serviceTier != null && !serviceTier.isBlank() && !Objects.equals(serviceTier, "standard")) params.addProperty("serviceTier", serviceTier);
        params.add("sandboxPolicy", sandboxPolicy(sandboxMode));
        var input = new JsonArray();
        addTextAndFileReferences(input, text, fileReferences);
        // 附件继续保持原有输入类型。
        for (var attachment : attachments) input.add(attachmentInput(attachment));
        params.add("input", input);
        return request("turn/start", params);
    }

    private void addTextAndFileReferences(JsonArray input, String text, List<FileReference> fileReferences) {
        var parts = Objects.requireNonNullElse(text, "").split(FILE_REFERENCE_MARKER, -1);
        var embeddedCount = Math.min(fileReferences.size(), Math.max(0, parts.length - 1));
        for (var index = 0; index < parts.length; index++) {
            if (!parts[index].isEmpty()) input.add(textInput(parts[index]));
            if (index < embeddedCount) input.add(fileReferenceInput(fileReferences.get(index)));
        }
        // 兼容没有占位符的旧调用，不能静默丢失文件引用。
        for (var index = embeddedCount; index < fileReferences.size(); index++) {
            input.add(fileReferenceInput(fileReferences.get(index)));
        }
    }

    private JsonObject textInput(String text) {
        var input = new JsonObject();
        input.addProperty("type", "text");
        input.addProperty("text", text);
        input.add("text_elements", new JsonArray());
        return input;
    }

    public CompletableFuture<JsonObject> interruptTurn(String threadId, String turnId) {
        var params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.addProperty("turnId", turnId);
        return request("turn/interrupt", params);
    }

    public void respondToServerRequest(long requestId, JsonObject result) {
        var response = new JsonObject();
        response.addProperty("id", requestId);
        response.add("result", result);
        writeMessage(response);
    }

    private JsonObject attachmentInput(Attachment attachment) {
        var input = new JsonObject();
        switch (attachment.kind()) {
            case IMAGE -> {
                input.addProperty("type", "localImage");
                input.addProperty("path", attachment.path().toAbsolutePath().toString());
            }
            case FILE -> {
                input.addProperty("type", "mention");
                input.addProperty("name", attachment.name());
                input.addProperty("path", attachment.path().toAbsolutePath().toString());
            }
        }
        return input;
    }

    private JsonObject fileReferenceInput(FileReference reference) {
        var input = new JsonObject();
        input.addProperty("type", "mention");
        input.addProperty("name", reference.name());
        input.addProperty("path", reference.path().toAbsolutePath().normalize().toString());
        return input;
    }

    private JsonObject sandboxPolicy(String mode) {
        var policy = new JsonObject();
        switch (Objects.requireNonNullElse(mode, "workspace-write")) {
            case "read-only" -> {
                policy.addProperty("type", "readOnly");
                policy.addProperty("networkAccess", false);
            }
            case "danger-full-access" -> policy.addProperty("type", "dangerFullAccess");
            default -> {
                policy.addProperty("type", "workspaceWrite");
                policy.add("writableRoots", new JsonArray());
                policy.addProperty("networkAccess", false);
                policy.addProperty("excludeTmpdirEnvVar", false);
                policy.addProperty("excludeSlashTmp", false);
            }
        }
        return policy;
    }

    public CompletableFuture<JsonObject> request(String method, JsonObject params) {
        var id = requestSequence.getAndIncrement();
        var request = new JsonObject();
        request.addProperty("id", id);
        request.addProperty("method", method);
        request.add("params", params == null ? new JsonObject() : params);
        var future = new CompletableFuture<JsonObject>();
        pendingRequests.put(id, future);
        try {
            writeMessage(request);
        } catch (RuntimeException error) {
            pendingRequests.remove(id);
            future.completeExceptionally(error);
        }
        return future;
    }

    private void notifyServer(String method, JsonObject params) {
        var notification = new JsonObject();
        notification.addProperty("method", method);
        if (params != null) notification.add("params", params);
        writeMessage(notification);
    }

    private void writeMessage(JsonObject message) {
        synchronized (writerLock) {
            try {
                if (writer == null) throw new IOException("Codex app-server 尚未启动");
                writer.write(GSON.toJson(message));
                writer.newLine();
                writer.flush();
            } catch (IOException error) {
                throw new IllegalStateException("无法写入 Codex app-server", error);
            }
        }
    }

    private void readProtocolLoop() {
        var activeProcess = process;
        if (activeProcess == null) return;
        try (var reader = new BufferedReader(new InputStreamReader(activeProcess.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                handleProtocolMessage(line);
            }
        } catch (Exception error) {
            if (process == activeProcess) fireProtocolError("Codex CLI 连接已断开", error);
        } finally {
            if (process == activeProcess) stopProcess("Codex CLI 已停止");
        }
    }

    private void readErrorLoop() {
        var activeProcess = process;
        if (activeProcess == null) return;
        try (var reader = new BufferedReader(new InputStreamReader(activeProcess.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) LOG.warn("codex app-server: " + line);
            }
        } catch (IOException ignored) {
        }
    }

    private void handleProtocolMessage(String line) {
        try {
            var message = GSON.fromJson(line, JsonObject.class);
            if (message.has("method")) {
                var method = message.get("method").getAsString();
                var params = message.has("params") && message.get("params").isJsonObject()
                    ? message.getAsJsonObject("params") : new JsonObject();
                if (message.has("id")) {
                    var id = message.get("id").getAsLong();
                    fireServerRequest(id, method, params);
                } else {
                    fireNotification(method, params);
                }
                return;
            }

            if (!message.has("id")) return;
            var id = message.get("id").getAsLong();
            var future = pendingRequests.remove(id);
            if (future == null) return;
            if (message.has("error")) {
                future.completeExceptionally(new IllegalStateException(message.get("error").toString()));
            } else {
                JsonElement result = message.get("result");
                future.complete(result != null && result.isJsonObject() ? result.getAsJsonObject() : new JsonObject());
            }
        } catch (Exception error) {
            fireProtocolError("无法解析 Codex app-server 消息", error);
        }
    }

    private void fireConnectionChanged(boolean state, String detail) {
        ApplicationManager.getApplication().invokeLater(() ->
            listeners.forEach(listener -> listener.onConnectionChanged(state, detail)));
    }

    private void fireNotification(String method, JsonObject params) {
        ApplicationManager.getApplication().invokeLater(() ->
            listeners.forEach(listener -> listener.onNotification(method, params)));
    }

    private void fireServerRequest(long id, String method, JsonObject params) {
        ApplicationManager.getApplication().invokeLater(() ->
            listeners.forEach(listener -> listener.onServerRequest(id, method, params)));
    }

    private void fireProtocolError(String message, Throwable error) {
        LOG.warn(message, error);
        ApplicationManager.getApplication().invokeLater(() ->
            listeners.forEach(listener -> listener.onProtocolError(message, error)));
    }

    private void stopProcess(String detail) {
        synchronized (lifecycleLock) {
            connected = false;
            var active = process;
            process = null;
            writer = null;
            if (active != null && active.isAlive()) active.destroy();
            var error = new IllegalStateException(detail);
            pendingRequests.values().forEach(future -> future.completeExceptionally(error));
            pendingRequests.clear();
            fireConnectionChanged(false, detail);
        }
    }

    @Override
    public void dispose() {
        listeners.clear();
        stopProcess("项目已关闭");
    }
}
