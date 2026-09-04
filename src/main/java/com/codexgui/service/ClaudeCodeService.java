package com.codexgui.service;

import com.codexgui.conversation.TurnHandle;
import com.codexgui.settings.CodexSettingsState;
import com.codexgui.settings.ProviderCredentialStore;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Service(Service.Level.PROJECT)
public final class ClaudeCodeService implements Disposable {
    private static final Logger LOG = Logger.getInstance(ClaudeCodeService.class);
    private static final Gson GSON = new Gson();
    private static final String MINIMUM_VERSION = "2.1.210";
    static final int FIRST_RESPONSE_TIMEOUT_SECONDS = 90;
    private static final int MAX_ERROR_OUTPUT_CHARS = 16_384;
    private static final int MAX_LOG_DIAGNOSTIC_CHARS = 1_000;
    private static final Pattern VERSION_PATTERN = Pattern.compile("(?<!\\d)(\\d+)\\.(\\d+)\\.(\\d+)(?!\\d)");
    private static final Pattern BEARER_SECRET_PATTERN = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern NAMED_SECRET_PATTERN = Pattern.compile(
        "(?i)(\\\"?(?:authorization|x-api-key|api[_-]?key|anthropic_api_key|anthropic_auth_token|access[_-]?token|token)\\\"?\\s*[=:]\\s*\\\"?)[^\\s\\\",;}]+"
    );

    private enum FirstResponseState { WAITING, RECEIVED, TIMED_OUT }

    public interface Listener {
        default void onModel(String model) {}
        default void onTextDelta(String delta) {}
        default void onThinkingDelta(String delta) {}
        default void onTool(String id, String name, JsonObject input) {}
    }

    public record TurnResult(String sessionId, String model, String finalText) {}
    public record HistoryItem(String id, String title, long updatedAtEpochMs) {}
    public record HistoryEntry(HistoryEntryKind kind, String body, String itemId, long createdAtEpochMs) {}
    public record HistoryConversation(String id, String title, List<HistoryEntry> entries) {}
    public enum HistoryEntryKind { USER, ASSISTANT, REASONING, COMMAND }

    private final Path workingDirectory;
    private final ClaudeHookRelayService hookRelay;
    private final Map<TurnHandle, Process> activeProcesses = new ConcurrentHashMap<>();
    private final java.util.Set<TurnHandle> cancelledTurns = ConcurrentHashMap.newKeySet();
    private final Set<TurnHandle> registeredHookTurns = ConcurrentHashMap.newKeySet();
    private final Set<String> verifiedExecutables = ConcurrentHashMap.newKeySet();
    private volatile boolean disposed;

    public ClaudeCodeService(Project project) {
        this.workingDirectory = project.getBasePath() == null ? null : Path.of(project.getBasePath());
        this.hookRelay = ClaudeHookRelayService.getInstance(project);
    }

    public static ClaudeCodeService getInstance(@NotNull Project project) {
        return project.getService(ClaudeCodeService.class);
    }

    public CompletableFuture<TurnResult> startTurn(
        TurnHandle turnHandle,
        String executable,
        String conversationId,
        String prompt,
        String model,
        String effort,
        String approvalPolicy,
        String instructions,
        CodexSettingsState.ProviderProfileSnapshot provider,
        Listener listener
    ) {
        if (disposed) return CompletableFuture.failedFuture(new CancellationException("Claude Code 服务已释放"));
        cancelledTurns.remove(turnHandle);
        return CompletableFuture.supplyAsync(() -> runTurn(
            turnHandle, executable, conversationId, prompt, model, effort, approvalPolicy, instructions, provider, listener
        ), AppExecutorUtil.getAppExecutorService());
    }

    public boolean isAvailable(String executable) {
        var resolved = resolveExecutable(executable);
        try {
            var path = Path.of(resolved);
            if (path.isAbsolute() && !Files.isRegularFile(path)) return false;
            if (verifiedExecutables.contains(resolved)) return true;
            var version = readVersion(resolved);
            if (!supportsVersion(version)) return false;
            verifiedExecutables.add(resolved);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public String resolvedExecutable(String executable) {
        return resolveExecutable(executable);
    }

    public CompletableFuture<List<HistoryItem>> listHistory(String searchTerm) {
        var directory = historyDirectory();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return readHistoryItems(directory, searchTerm);
            } catch (IOException error) {
                throw new CompletionException(error);
            }
        }, AppExecutorUtil.getAppExecutorService());
    }

    public CompletableFuture<HistoryConversation> readHistory(String sessionId) {
        var directory = historyDirectory();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return readHistoryConversation(directory, sessionId);
            } catch (IOException error) {
                throw new CompletionException(error);
            }
        }, AppExecutorUtil.getAppExecutorService());
    }

    public boolean interrupt(TurnHandle turnHandle) {
        if (turnHandle == null) return false;
        cancelledTurns.add(turnHandle);
        hookRelay.unregister(turnHandle);
        var process = activeProcesses.remove(turnHandle);
        if (process != null) terminate(process);
        return process != null;
    }

    private TurnResult runTurn(
        TurnHandle turnHandle,
        String configuredExecutable,
        String conversationId,
        String prompt,
        String model,
        String effort,
        String approvalPolicy,
        String instructions,
        CodexSettingsState.ProviderProfileSnapshot provider,
        Listener listener
    ) {
        var executable = resolveExecutable(configuredExecutable);
        var sessionId = conversationId == null || conversationId.isBlank()
            ? java.util.UUID.randomUUID().toString()
            : conversationId;
        var startedAt = System.nanoTime();
        ClaudeHookRelayService.HookRegistration hookRegistration = null;
        Path hookSettingsFile = null;
        try {
            LOG.info("准备启动 Claude Code 回合：turn=" + turnHandle
                + ", executable=" + executable
                + ", model=" + display(model)
                + ", provider=" + providerLabel(provider));
            if (disposed || cancelledTurns.contains(turnHandle)) throw new CancellationException("Claude Code 回合已停止");
            ensureSupportedVersion(executable);
            if (disposed || cancelledTurns.contains(turnHandle)) throw new CancellationException("Claude Code 回合已停止");
            hookRegistration = hookRelay.register(turnHandle);
            registeredHookTurns.add(turnHandle);
            if (disposed || cancelledTurns.contains(turnHandle)) throw new CancellationException("Claude Code 回合已停止");
            // Windows 原生命令行会破坏内联 JSON 中的引号，改用临时文件传递 Hook 设置。
            hookSettingsFile = writeHookSettings(hookRegistration.settingsJson());
            var arguments = arguments(
                sessionId, conversationId, model, effort, approvalPolicy, instructions, hookSettingsFile.toString());
            var builder = new ProcessBuilder(command(executable, arguments.toArray(String[]::new)))
                .directory(workingDirectory == null ? null : workingDirectory.toFile())
                .redirectErrorStream(false);
            applyProvider(builder, provider);
            var process = builder.start();
            activeProcesses.put(turnHandle, process);
            LOG.info("Claude Code 进程已启动：turn=" + turnHandle + ", pid=" + process.pid());
            // 停止请求可能发生在进程创建和登记之间，登记后再次检查才能避免漏停。
            if (cancelledTurns.contains(turnHandle)) {
                terminate(process);
                throw new CancellationException("Claude Code 回合已停止");
            }
            writePrompt(process, prompt);

            // 错误流独立排空并保留脱敏尾部，既避免阻塞，也为超时提供即时诊断。
            var errorOutput = new StringBuilder();
            var errorLineCount = new AtomicInteger();
            var errorReader = CompletableFuture.runAsync(
                () -> readErrors(process, errorOutput, errorLineCount, turnHandle),
                AppExecutorUtil.getAppExecutorService());
            var firstResponseState = new AtomicReference<>(FirstResponseState.WAITING);
            ScheduledFuture<?> firstResponseTimeout = AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
                // 超时只约束首个上游响应，CLI 本地初始化不能提前解除监视。
                if (!firstResponseState.compareAndSet(FirstResponseState.WAITING, FirstResponseState.TIMED_OUT)) return;
                LOG.warn(firstResponseTimeoutMessage(process.pid(), errorOutput));
                terminate(process);
            }, FIRST_RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            TurnResult result;
            try {
                result = readOutput(process, sessionId, listener, type -> {
                    if (!firstResponseState.compareAndSet(FirstResponseState.WAITING, FirstResponseState.RECEIVED)) return;
                    LOG.info("Claude Code 收到首个响应事件：turn=" + turnHandle
                        + ", pid=" + process.pid()
                        + ", type=" + type
                        + ", elapsedMs=" + elapsedMillis(startedAt));
                });
            } catch (IOException error) {
                if (firstResponseState.get() == FirstResponseState.TIMED_OUT) {
                    throw new IllegalStateException(firstResponseTimeoutMessage(process.pid(), errorOutput), error);
                }
                throw error;
            } finally {
                firstResponseTimeout.cancel(false);
            }
            var exitCode = process.waitFor();
            errorReader.join();
            LOG.info("Claude Code 进程已结束：turn=" + turnHandle
                + ", pid=" + process.pid()
                + ", exitCode=" + exitCode
                + ", elapsedMs=" + elapsedMillis(startedAt)
                + ", stderrLines=" + errorLineCount.get());
            if (cancelledTurns.contains(turnHandle)) throw new CancellationException("Claude Code 回合已停止");
            if (firstResponseState.get() == FirstResponseState.TIMED_OUT) {
                throw new IllegalStateException(firstResponseTimeoutMessage(process.pid(), errorOutput));
            }
            if (exitCode != 0) {
                var detail = diagnosticSummary(errorOutput);
                if (detail.isBlank()) detail = "Claude Code 退出码：" + exitCode;
                throw new IllegalStateException(detail);
            }
            return result;
        } catch (Exception error) {
            if (error instanceof CancellationException) {
                LOG.info("Claude Code 回合已取消：turn=" + turnHandle + ", elapsedMs=" + elapsedMillis(startedAt));
            } else {
                LOG.warn("Claude Code 回合失败：turn=" + turnHandle
                    + ", elapsedMs=" + elapsedMillis(startedAt)
                    + ", error=" + sanitizeDiagnostic(error.getMessage()), error);
            }
            throw executionFailure(executable, error);
        } finally {
            deleteHookSettings(hookSettingsFile);
            if (hookRegistration != null) hookRelay.unregister(turnHandle);
            registeredHookTurns.remove(turnHandle);
            activeProcesses.remove(turnHandle);
            cancelledTurns.remove(turnHandle);
        }
    }

    static List<HistoryItem> readHistoryItems(Path directory, String searchTerm) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) return List.of();
        var normalizedSearch = Objects.requireNonNullElse(searchTerm, "").trim().toLowerCase(Locale.ROOT);
        try (var files = Files.list(directory)) {
            return files
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                .map(path -> historyItem(path, normalizedSearch))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(HistoryItem::updatedAtEpochMs).reversed())
                .limit(100)
                .toList();
        }
    }

    static HistoryConversation readHistoryConversation(Path directory, String sessionId) throws IOException {
        if (directory == null || sessionId == null || !sessionId.matches("[0-9a-fA-F-]{36}")) {
            throw new IOException("无效的 Claude 会话 ID");
        }
        var normalizedDirectory = directory.toAbsolutePath().normalize();
        var path = normalizedDirectory.resolve(sessionId + ".jsonl").normalize();
        if (!path.startsWith(normalizedDirectory) || !Files.isRegularFile(path)) {
            throw new IOException("找不到 Claude 历史会话");
        }

        var entries = new ArrayList<HistoryEntry>();
        var title = "";
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                var event = parseHistoryEvent(line);
                if (event == null || booleanValue(event, "isSidechain")) continue;
                var timestamp = historyTimestamp(event);
                var type = string(event, "type");
                // 用户消息只保留真实文本，跳过工具结果和内部元数据。
                if (Objects.equals(type, "user")) {
                    var body = userHistoryText(event);
                    if (body.isBlank()) continue;
                    if (title.isBlank()) title = historyTitle(body);
                    entries.add(new HistoryEntry(
                        HistoryEntryKind.USER, body, historyItemId(event, entries.size()), timestamp));
                    continue;
                }
                // 助手消息按内容块恢复，保留文本、思考和工具调用的原始顺序。
                if (Objects.equals(type, "assistant")) {
                    appendAssistantHistory(entries, event, timestamp);
                }
            }
        }
        if (title.isBlank()) title = "未命名会话";
        return new HistoryConversation(sessionId, title, List.copyOf(entries));
    }

    private Path historyDirectory() {
        if (workingDirectory == null) return null;
        var projectKey = workingDirectory.toAbsolutePath().normalize().toString().replaceAll("[^A-Za-z0-9]", "-");
        return Path.of(System.getProperty("user.home"), ".claude", "projects", projectKey);
    }

    private static HistoryItem historyItem(Path path, String normalizedSearch) {
        var fileName = path.getFileName().toString();
        var sessionId = fileName.substring(0, fileName.length() - ".jsonl".length());
        if (!sessionId.matches("[0-9a-fA-F-]{36}")) return null;
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                var event = parseHistoryEvent(line);
                if (event == null || booleanValue(event, "isSidechain")
                    || !Objects.equals(string(event, "type"), "user")) continue;
                var title = historyTitle(userHistoryText(event));
                if (title.isBlank()) continue;
                if (!normalizedSearch.isBlank() && !title.toLowerCase(Locale.ROOT).contains(normalizedSearch)) return null;
                return new HistoryItem(sessionId, title, Files.getLastModifiedTime(path).toMillis());
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private static JsonObject parseHistoryEvent(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            return GSON.fromJson(line, JsonObject.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String userHistoryText(JsonObject event) {
        if (booleanValue(event, "isMeta")) return "";
        var message = object(event, "message");
        if (message == null || !message.has("content")) return "";
        var content = message.get("content");
        if (content.isJsonPrimitive()) return content.getAsString().trim();
        if (!content.isJsonArray()) return "";
        var text = new StringBuilder();
        for (var element : content.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            var block = element.getAsJsonObject();
            if (!Objects.equals(string(block, "type"), "text")) continue;
            if (!text.isEmpty()) text.append('\n');
            text.append(string(block, "text"));
        }
        return text.toString().trim();
    }

    private static void appendAssistantHistory(List<HistoryEntry> entries, JsonObject event, long timestamp) {
        var message = object(event, "message");
        if (message == null) return;
        var blockIndex = 0;
        for (var element : array(message, "content")) {
            if (!element.isJsonObject()) continue;
            var block = element.getAsJsonObject();
            var blockType = string(block, "type");
            var kind = switch (blockType) {
                case "text" -> HistoryEntryKind.ASSISTANT;
                case "thinking" -> HistoryEntryKind.REASONING;
                case "tool_use" -> HistoryEntryKind.COMMAND;
                default -> null;
            };
            if (kind == null) continue;
            var body = switch (kind) {
                case ASSISTANT -> string(block, "text");
                case REASONING -> string(block, "thinking");
                case COMMAND -> string(block, "name") + "\n\n" + GSON.toJson(object(block, "input"));
                case USER -> "";
            };
            if (body.isBlank()) continue;
            entries.add(new HistoryEntry(
                kind, body, historyItemId(event, blockIndex++), timestamp));
        }
    }

    private static String historyItemId(JsonObject event, int index) {
        return value(event, "uuid", "claude-history") + ":" + index;
    }

    private static String historyTitle(String body) {
        var normalized = Objects.requireNonNullElse(body, "").replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }

    private static long historyTimestamp(JsonObject event) {
        try {
            return Instant.parse(string(event, "timestamp")).toEpochMilli();
        } catch (RuntimeException ignored) {
            return System.currentTimeMillis();
        }
    }

    private static boolean booleanValue(JsonObject event, String key) {
        if (event == null || !event.has(key) || event.get(key).isJsonNull()) return false;
        try {
            return event.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private List<String> arguments(
        String sessionId,
        String conversationId,
        String model,
        String effort,
        String approvalPolicy,
        String instructions,
        String hookSettingsFile
    ) {
        var arguments = new ArrayList<String>();
        arguments.add("-p");
        arguments.add("--verbose");
        arguments.add("--output-format");
        arguments.add("stream-json");
        arguments.add("--include-partial-messages");
        arguments.add("--include-hook-events");
        arguments.add("--settings");
        arguments.add(hookSettingsFile);
        arguments.add("--permission-mode");
        arguments.add(permissionMode(approvalPolicy));
        if (Objects.equals(approvalPolicy, "never")) arguments.add("--dangerously-skip-permissions");

        // 新会话写入固定 ID 和用户指令，续接会话只恢复原 Claude 上下文。
        if (conversationId == null || conversationId.isBlank()) {
            arguments.add("--session-id");
            arguments.add(sessionId);
            if (instructions != null && !instructions.isBlank()) {
                arguments.add("--append-system-prompt");
                arguments.add(instructions);
            }
        } else {
            arguments.add("--resume");
            arguments.add(sessionId);
        }
        if (model != null && !model.isBlank()) {
            arguments.add("--model");
            arguments.add(model);
        }
        var normalizedEffort = effort(effort);
        if (!normalizedEffort.isBlank()) {
            arguments.add("--effort");
            arguments.add(normalizedEffort);
        }
        return arguments;
    }

    static Path writeHookSettings(String settingsJson) throws IOException {
        var path = Files.createTempFile("codedeck-claude-settings-", ".json").toAbsolutePath();
        try {
            Files.writeString(path, settingsJson, StandardCharsets.UTF_8);
            return path;
        } catch (IOException error) {
            Files.deleteIfExists(path);
            throw error;
        }
    }

    private void deleteHookSettings(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException error) {
            LOG.warn("无法清理 Claude Hook 临时设置文件：" + path, error);
        }
    }

    private void ensureSupportedVersion(String executable) throws IOException, InterruptedException {
        if (verifiedExecutables.contains(executable)) return;
        var version = readVersion(executable);
        if (!supportsVersion(version)) {
            var displayVersion = version.lines().findFirst().orElse("无法识别").trim();
            throw new IllegalStateException(
                "需要 Claude Code " + MINIMUM_VERSION + " 或更高版本，当前版本：" + displayVersion);
        }
        verifiedExecutables.add(executable);
    }

    private String readVersion(String executable) throws IOException, InterruptedException {
        var process = new ProcessBuilder(command(executable, "--version")).redirectErrorStream(true).start();
        if (!process.waitFor(4, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("读取 Claude Code 版本超时");
        }
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) throw new IllegalStateException(
            output.isBlank() ? "无法读取 Claude Code 版本" : output);
        return output;
    }

    static boolean supportsVersion(String output) {
        var matcher = VERSION_PATTERN.matcher(Objects.requireNonNullElse(output, ""));
        if (!matcher.find()) return false;
        var version = new int[] {
            Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2)),
            Integer.parseInt(matcher.group(3))
        };
        var minimum = new int[] {2, 1, 210};
        for (var index = 0; index < version.length; index++) {
            if (version[index] > minimum[index]) return true;
            if (version[index] < minimum[index]) return false;
        }
        return true;
    }

    static RuntimeException executionFailure(String executable, Exception error) {
        if (error instanceof CancellationException cancellation) return cancellation;
        return new IllegalStateException("无法运行 Claude Code（" + executable + "）：" + error.getMessage(), error);
    }

    private void terminate(Process process) {
        if (!process.isAlive()) return;
        process.destroy();
        // 中断操作可能来自 EDT；延迟检查后强制结束，避免阻塞工具窗。
        CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS).execute(() -> {
            if (process.isAlive()) process.destroyForcibly();
        });
    }

    private TurnResult readOutput(
        Process process,
        String fallbackSessionId,
        Listener listener,
        java.util.function.Consumer<String> responseEventListener
    ) throws IOException {
        var sessionId = fallbackSessionId;
        var model = "";
        var finalText = "";
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonObject event;
                try {
                    event = GSON.fromJson(line, JsonObject.class);
                } catch (RuntimeException error) {
                    LOG.debug("忽略无法解析的 Claude Code 输出：" + sanitizeDiagnostic(line), error);
                    continue;
                }
                var type = string(event, "type");
                if (isResponseEvent(type)) responseEventListener.accept(type);
                // 初始化事件提供真实会话与模型，界面据此回填供应商状态。
                if (Objects.equals(type, "system") && Objects.equals(string(event, "subtype"), "init")) {
                    sessionId = value(event, "session_id", sessionId);
                    model = value(event, "model", model);
                    if (!model.isBlank()) listener.onModel(model);
                    continue;
                }
                if (Objects.equals(type, "stream_event")) {
                    var streamEvent = object(event, "event");
                    var delta = object(streamEvent, "delta");
                    var deltaType = string(delta, "type");
                    if (Objects.equals(deltaType, "text_delta")) listener.onTextDelta(string(delta, "text"));
                    if (Objects.equals(deltaType, "thinking_delta")) listener.onThinkingDelta(string(delta, "thinking"));
                    continue;
                }
                if (Objects.equals(type, "assistant")) {
                    var message = object(event, "message");
                    var assistantText = new StringBuilder();
                    var messageModel = string(message, "model");
                    if (!messageModel.isBlank()) {
                        model = messageModel;
                        listener.onModel(model);
                    }
                    for (var content : array(message, "content")) {
                        if (!content.isJsonObject()) continue;
                        var block = content.getAsJsonObject();
                        if (Objects.equals(string(block, "type"), "text")) assistantText.append(string(block, "text"));
                        if (Objects.equals(string(block, "type"), "tool_use")) {
                            listener.onTool(value(block, "id", "claude-tool"), string(block, "name"), object(block, "input"));
                        }
                    }
                    if (!assistantText.isEmpty()) finalText = assistantText.toString();
                    continue;
                }
                if (Objects.equals(type, "result")) {
                    sessionId = value(event, "session_id", sessionId);
                    var resultText = string(event, "result");
                    if (!resultText.isBlank()) finalText = resultText;
                    if (event.has("is_error") && event.get("is_error").getAsBoolean()) {
                        throw new IllegalStateException(finalText.isBlank() ? "Claude Code 返回错误" : finalText);
                    }
                }
            }
        }
        return new TurnResult(sessionId, model, finalText);
    }

    private void writePrompt(Process process, String prompt) throws IOException {
        try (var writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            writer.write(Objects.requireNonNullElse(prompt, ""));
            writer.newLine();
        }
    }

    private void readErrors(
        Process process,
        StringBuilder output,
        AtomicInteger lineCount,
        TurnHandle turnHandle
    ) {
        try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                var diagnostic = sanitizeDiagnostic(line);
                if (diagnostic.isBlank()) continue;
                lineCount.incrementAndGet();
                synchronized (output) {
                    if (!output.isEmpty()) output.append('\n');
                    output.append(diagnostic);
                    if (output.length() > MAX_ERROR_OUTPUT_CHARS) {
                        output.delete(0, output.length() - MAX_ERROR_OUTPUT_CHARS);
                    }
                }
                LOG.warn("Claude Code stderr：turn=" + turnHandle + ", " + diagnostic);
            }
        } catch (IOException error) {
            if (process.isAlive()) LOG.warn("读取 Claude Code stderr 失败：turn=" + turnHandle, error);
        }
    }

    static String sanitizeDiagnostic(String value) {
        var diagnostic = Objects.requireNonNullElse(value, "");
        diagnostic = BEARER_SECRET_PATTERN.matcher(diagnostic).replaceAll("$1<redacted>");
        diagnostic = NAMED_SECRET_PATTERN.matcher(diagnostic).replaceAll("$1<redacted>");
        if (diagnostic.length() <= MAX_LOG_DIAGNOSTIC_CHARS) return diagnostic;
        return diagnostic.substring(0, MAX_LOG_DIAGNOSTIC_CHARS) + "...";
    }

    static String firstResponseTimeoutMessage(long pid, StringBuilder errorOutput) {
        var message = "Claude Code 在 " + FIRST_RESPONSE_TIMEOUT_SECONDS
            + " 秒内未返回任何响应事件，已终止进程（PID " + pid
            + "）。请检查供应商接口、模型名称和网络代理。";
        var detail = diagnosticSummary(errorOutput);
        return detail.isBlank() ? message : message + " 最近错误：" + detail;
    }

    private static String diagnosticSummary(StringBuilder output) {
        synchronized (output) { return output.toString().trim(); }
    }

    static boolean isResponseEvent(String type) {
        return Objects.equals(type, "stream_event")
            || Objects.equals(type, "assistant")
            || Objects.equals(type, "result");
    }

    private static String providerLabel(CodexSettingsState.ProviderProfileSnapshot provider) {
        if (provider == null) return "unknown";
        return provider.builtIn() ? "local" : provider.name() + " (" + provider.id() + ")";
    }

    private static String display(String value) {
        return value == null || value.isBlank() ? "default" : value;
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private void applyProvider(ProcessBuilder builder, CodexSettingsState.ProviderProfileSnapshot provider) {
        if (provider == null || provider.builtIn()) return;
        var apiKey = ProviderCredentialStore.get(provider.id());
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("当前 Claude 供应商尚未配置认证令牌");
        builder.environment().put("ANTHROPIC_BASE_URL", provider.baseUrl());
        // 中转渠道统一使用 Bearer 令牌，并清除本机 API Key 避免覆盖当前认证。
        builder.environment().put("ANTHROPIC_AUTH_TOKEN", apiKey);
        builder.environment().remove("ANTHROPIC_API_KEY");
    }

    static List<String> command(String executable, String... arguments) {
        return ExecutableCommand.build(executable, List.of(arguments));
    }

    private String resolveExecutable(String configured) {
        var requested = Objects.requireNonNullElse(configured, "").trim();
        if (!requested.isBlank() && !requested.equalsIgnoreCase("claude")) return requested;
        if (!SystemInfo.isWindows) return "claude";

        var candidates = new ArrayList<Path>();
        var appData = path(System.getenv("APPDATA"));
        if (appData != null) {
            candidates.add(appData.resolve("npm/node_modules/@anthropic-ai/claude-code/bin/claude.exe"));
            candidates.add(appData.resolve("npm/claude.cmd"));
        }
        addPathCandidates(candidates, System.getenv("PATH"));
        return candidates.stream().filter(Files::isRegularFile).map(Path::toString).findFirst().orElse("claude");
    }

    private void addPathCandidates(List<Path> candidates, String pathValue) {
        if (pathValue == null || pathValue.isBlank()) return;
        for (var entry : pathValue.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            var directory = path(entry.replace("\"", "").trim());
            if (directory == null) continue;
            candidates.add(directory.resolve("claude.exe"));
            candidates.add(directory.resolve("claude.cmd"));
        }
    }

    private Path path(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Path.of(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static String permissionMode(String approvalPolicy) {
        return switch (Objects.requireNonNullElse(approvalPolicy, "on-request")) {
            case "untrusted" -> "manual";
            case "never" -> "bypassPermissions";
            default -> "acceptEdits";
        };
    }

    static String effort(String effort) {
        return switch (Objects.requireNonNullElse(effort, "high")) {
            case "minimal" -> "low";
            case "ultra" -> "max";
            case "low", "medium", "high", "xhigh", "max" -> effort;
            default -> "high";
        };
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) return new JsonObject();
        return parent.getAsJsonObject(key);
    }

    private static Iterable<JsonElement> array(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonArray()) return List.of();
        return parent.getAsJsonArray(key);
    }

    private static String string(JsonObject object, String key) {
        return value(object, key, "");
    }

    private static String value(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        registeredHookTurns.forEach(hookRelay::unregister);
        registeredHookTurns.clear();
        activeProcesses.values().forEach(this::terminate);
        activeProcesses.clear();
        cancelledTurns.clear();
        verifiedExecutables.clear();
    }
}
