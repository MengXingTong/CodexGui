package com.codexgui.service;

import com.codexgui.settings.CodexSettingsState;
import com.codexgui.settings.ProviderCredentialStore;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.concurrency.AppExecutorUtil;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class ClaudeCodeService implements Disposable {
    private static final Logger LOG = Logger.getInstance(ClaudeCodeService.class);
    private static final Gson GSON = new Gson();

    public interface Listener {
        default void onModel(String model) {}
        default void onTextDelta(String delta) {}
        default void onThinkingDelta(String delta) {}
        default void onTool(String id, String name, JsonObject input) {}
    }

    public record TurnResult(String sessionId, String model, String finalText) {}

    private final Path workingDirectory;
    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();
    private final java.util.Set<String> cancelledSessions = ConcurrentHashMap.newKeySet();

    public ClaudeCodeService(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public CompletableFuture<TurnResult> startTurn(
        String localSessionId,
        String executable,
        String conversationId,
        String prompt,
        String model,
        String effort,
        String approvalPolicy,
        String instructions,
        CodexSettingsState.ProviderProfile provider,
        Listener listener
    ) {
        cancelledSessions.remove(localSessionId);
        return CompletableFuture.supplyAsync(() -> runTurn(
            localSessionId, executable, conversationId, prompt, model, effort, approvalPolicy, instructions, provider, listener
        ), AppExecutorUtil.getAppExecutorService());
    }

    public boolean isAvailable(String executable) {
        var resolved = resolveExecutable(executable);
        try {
            var path = Path.of(resolved);
            if (path.isAbsolute()) return Files.isRegularFile(path);
            var process = new ProcessBuilder(command(resolved, "--version")).redirectErrorStream(true).start();
            return process.waitFor(4, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    public String resolvedExecutable(String executable) {
        return resolveExecutable(executable);
    }

    public boolean interrupt(String localSessionId) {
        cancelledSessions.add(localSessionId);
        var process = activeProcesses.remove(localSessionId);
        if (process != null && process.isAlive()) process.destroy();
        return process != null;
    }

    private TurnResult runTurn(
        String localSessionId,
        String configuredExecutable,
        String conversationId,
        String prompt,
        String model,
        String effort,
        String approvalPolicy,
        String instructions,
        CodexSettingsState.ProviderProfile provider,
        Listener listener
    ) {
        if (cancelledSessions.contains(localSessionId)) throw new CancellationException("Claude Code 回合已停止");
        var executable = resolveExecutable(configuredExecutable);
        var sessionId = conversationId == null || conversationId.isBlank()
            ? java.util.UUID.randomUUID().toString()
            : conversationId;
        var arguments = new ArrayList<String>();
        arguments.add("-p");
        arguments.add("--verbose");
        arguments.add("--output-format");
        arguments.add("stream-json");
        arguments.add("--include-partial-messages");
        arguments.add("--permission-mode");
        arguments.add(permissionMode(approvalPolicy));
        if (Objects.equals(approvalPolicy, "never")) arguments.add("--dangerously-skip-permissions");
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

        try {
            var builder = new ProcessBuilder(command(executable, arguments.toArray(String[]::new)))
                .directory(workingDirectory == null ? null : workingDirectory.toFile())
                .redirectErrorStream(false);
            applyProvider(builder, provider);
            var process = builder.start();
            activeProcesses.put(localSessionId, process);
            // 停止请求可能发生在进程创建和登记之间，登记后再次检查才能避免漏停。
            if (cancelledSessions.contains(localSessionId)) {
                process.destroy();
                throw new CancellationException("Claude Code 回合已停止");
            }
            writePrompt(process, prompt);

            // 错误流独立排空，避免 Claude Code 输出较多诊断信息时阻塞。
            var errorOutput = new StringBuilder();
            AppExecutorUtil.getAppExecutorService().execute(() -> readErrors(process, errorOutput));
            var result = readOutput(process, sessionId, listener);
            var exitCode = process.waitFor();
            if (exitCode != 0) {
                String detail;
                synchronized (errorOutput) { detail = errorOutput.toString().trim(); }
                if (detail.isBlank()) detail = "Claude Code 退出码：" + exitCode;
                throw new IllegalStateException(detail);
            }
            return result;
        } catch (Exception error) {
            throw new IllegalStateException("无法运行 Claude Code（" + executable + "）：" + error.getMessage(), error);
        } finally {
            activeProcesses.remove(localSessionId);
            cancelledSessions.remove(localSessionId);
        }
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

    private void applyProvider(ProcessBuilder builder, CodexSettingsState.ProviderProfile provider) {
        if (provider == null || provider.builtIn) return;
        var apiKey = ProviderCredentialStore.get(provider.id);
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("当前 Claude 供应商尚未配置 API 密钥");
        builder.environment().put("ANTHROPIC_BASE_URL", provider.baseUrl);
        // 网关通常使用认证令牌，官方兼容端点也可以选择标准 API Key。
        if (Objects.equals(provider.claudeAuthType, "api-key")) {
            builder.environment().put("ANTHROPIC_API_KEY", apiKey);
            builder.environment().remove("ANTHROPIC_AUTH_TOKEN");
        } else {
            builder.environment().put("ANTHROPIC_AUTH_TOKEN", apiKey);
            builder.environment().remove("ANTHROPIC_API_KEY");
        }
    }

    private List<String> command(String executable, String... arguments) {
        var command = new ArrayList<String>();
        var normalized = executable.toLowerCase(Locale.ROOT);
        if (SystemInfo.isWindows && (normalized.endsWith(".cmd") || normalized.endsWith(".bat") || !normalized.endsWith(".exe"))) {
            command.add("cmd.exe");
            command.add("/d");
            command.add("/c");
        }
        command.add(executable);
        command.addAll(List.of(arguments));
        return command;
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

    private String permissionMode(String approvalPolicy) {
        return switch (Objects.requireNonNullElse(approvalPolicy, "on-request")) {
            case "untrusted" -> "manual";
            case "never" -> "bypassPermissions";
            default -> "acceptEdits";
        };
    }

    private String effort(String effort) {
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
        activeProcesses.values().forEach(process -> {
            if (process.isAlive()) process.destroy();
        });
        activeProcesses.clear();
        cancelledSessions.clear();
    }
}
