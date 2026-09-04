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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service(Service.Level.PROJECT)
public final class ClaudeCodeService implements Disposable {
    private static final Logger LOG = Logger.getInstance(ClaudeCodeService.class);
    private static final Gson GSON = new Gson();
    private static final String MINIMUM_VERSION = "2.1.210";
    private static final Pattern VERSION_PATTERN = Pattern.compile("(?<!\\d)(\\d+)\\.(\\d+)\\.(\\d+)(?!\\d)");

    public interface Listener {
        default void onModel(String model) {}
        default void onTextDelta(String delta) {}
        default void onThinkingDelta(String delta) {}
        default void onTool(String id, String name, JsonObject input) {}
    }

    public record TurnResult(String sessionId, String model, String finalText) {}

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
        ClaudeHookRelayService.HookRegistration hookRegistration = null;
        try {
            if (disposed || cancelledTurns.contains(turnHandle)) throw new CancellationException("Claude Code 回合已停止");
            ensureSupportedVersion(executable);
            if (disposed || cancelledTurns.contains(turnHandle)) throw new CancellationException("Claude Code 回合已停止");
            hookRegistration = hookRelay.register(turnHandle);
            registeredHookTurns.add(turnHandle);
            if (disposed || cancelledTurns.contains(turnHandle)) throw new CancellationException("Claude Code 回合已停止");
            var arguments = arguments(
                sessionId, conversationId, model, effort, approvalPolicy, instructions, hookRegistration.settingsJson());
            var builder = new ProcessBuilder(command(executable, arguments.toArray(String[]::new)))
                .directory(workingDirectory == null ? null : workingDirectory.toFile())
                .redirectErrorStream(false);
            applyProvider(builder, provider);
            var process = builder.start();
            activeProcesses.put(turnHandle, process);
            // 停止请求可能发生在进程创建和登记之间，登记后再次检查才能避免漏停。
            if (cancelledTurns.contains(turnHandle)) {
                terminate(process);
                throw new CancellationException("Claude Code 回合已停止");
            }
            writePrompt(process, prompt);

            // 错误流独立排空，避免 Claude Code 输出较多诊断信息时阻塞。
            var errorOutput = new StringBuilder();
            AppExecutorUtil.getAppExecutorService().execute(() -> readErrors(process, errorOutput));
            var result = readOutput(process, sessionId, listener);
            var exitCode = process.waitFor();
            if (cancelledTurns.contains(turnHandle)) throw new CancellationException("Claude Code 回合已停止");
            if (exitCode != 0) {
                String detail;
                synchronized (errorOutput) { detail = errorOutput.toString().trim(); }
                if (detail.isBlank()) detail = "Claude Code 退出码：" + exitCode;
                throw new IllegalStateException(detail);
            }
            return result;
        } catch (Exception error) {
            throw executionFailure(executable, error);
        } finally {
            if (hookRegistration != null) hookRelay.unregister(turnHandle);
            registeredHookTurns.remove(turnHandle);
            activeProcesses.remove(turnHandle);
            cancelledTurns.remove(turnHandle);
        }
    }

    private List<String> arguments(
        String sessionId,
        String conversationId,
        String model,
        String effort,
        String approvalPolicy,
        String instructions,
        String hookSettings
    ) {
        var arguments = new ArrayList<String>();
        arguments.add("-p");
        arguments.add("--verbose");
        arguments.add("--output-format");
        arguments.add("stream-json");
        arguments.add("--include-partial-messages");
        arguments.add("--include-hook-events");
        arguments.add("--settings");
        arguments.add(hookSettings);
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

    private TurnResult readOutput(Process process, String fallbackSessionId, Listener listener) throws IOException {
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
                    LOG.debug("忽略无法解析的 Claude Code 输出：" + line, error);
                    continue;
                }
                var type = string(event, "type");
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

    private void readErrors(Process process, StringBuilder output) {
        try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (output) {
                    if (!output.isEmpty()) output.append('\n');
                    output.append(line);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void applyProvider(ProcessBuilder builder, CodexSettingsState.ProviderProfileSnapshot provider) {
        if (provider == null || provider.builtIn()) return;
        var apiKey = ProviderCredentialStore.get(provider.id());
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("当前 Claude 供应商尚未配置 API 密钥");
        builder.environment().put("ANTHROPIC_BASE_URL", provider.baseUrl());
        // 网关通常使用认证令牌，官方兼容端点也可以选择标准 API Key。
        if (Objects.equals(provider.claudeAuthType(), "api-key")) {
            builder.environment().put("ANTHROPIC_API_KEY", apiKey);
            builder.environment().remove("ANTHROPIC_AUTH_TOKEN");
        } else {
            builder.environment().put("ANTHROPIC_AUTH_TOKEN", apiKey);
            builder.environment().remove("ANTHROPIC_API_KEY");
        }
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

    private JsonObject object(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) return new JsonObject();
        return parent.getAsJsonObject(key);
    }

    private Iterable<JsonElement> array(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonArray()) return List.of();
        return parent.getAsJsonArray(key);
    }

    private String string(JsonObject object, String key) {
        return value(object, key, "");
    }

    private String value(JsonObject object, String key, String fallback) {
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
