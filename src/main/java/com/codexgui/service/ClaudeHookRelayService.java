package com.codexgui.service;

import com.codexgui.conversation.SessionId;
import com.codexgui.conversation.TurnHandle;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service(Service.Level.PROJECT)
public final class ClaudeHookRelayService implements Disposable {
    private static final Logger LOG = Logger.getInstance(ClaudeHookRelayService.class);
    private static final Gson GSON = new Gson();
    private static final String ENDPOINT_PREFIX = "/claude-hook/";
    private static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
    private static final Set<String> FILE_TOOLS = Set.of("Write", "Edit", "NotebookEdit");

    public record HookRegistration(TurnHandle turnHandle, URI endpoint, String command, String settingsJson) {}

    private record RelayResponse(int status, String message) {
        private static RelayResponse allowed() { return new RelayResponse(204, ""); }
        private static RelayResponse denied(int status, String message) { return new RelayResponse(status, message); }
    }

    private final ConversationChangeTracker changeTracker;
    private final Map<String, HookRegistration> registrations = new ConcurrentHashMap<>();
    private final Map<SessionId, String> currentTokens = new ConcurrentHashMap<>();
    private HttpServer server;
    private ExecutorService relayExecutor;
    private boolean disposed;

    public ClaudeHookRelayService(Project project) {
        this(ConversationChangeTracker.getInstance(project));
    }

    ClaudeHookRelayService(ConversationChangeTracker changeTracker) {
        this.changeTracker = changeTracker;
    }

    public static ClaudeHookRelayService getInstance(@NotNull Project project) {
        return project.getService(ClaudeHookRelayService.class);
    }

    public synchronized HookRegistration register(TurnHandle turnHandle) {
        Objects.requireNonNull(turnHandle, "turnHandle");
        ensureServer();

        // 同一 Session 的新 generation 会立即废弃旧 token，迟到 Hook 不得建立 membership。
        var token = UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
        var previousToken = currentTokens.put(turnHandle.sessionId(), token);
        if (previousToken != null) registrations.remove(previousToken);

        var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + ENDPOINT_PREFIX + token);
        var command = relayCommand(endpoint);
        var registration = new HookRegistration(turnHandle, endpoint, command, hookSettings(command));
        registrations.put(token, registration);
        return registration;
    }

    public synchronized void unregister(TurnHandle turnHandle) {
        if (turnHandle == null) return;
        var token = currentTokens.get(turnHandle.sessionId());
        if (token == null) return;
        var registration = registrations.get(token);
        if (registration == null || !registration.turnHandle().equals(turnHandle)) return;
        currentTokens.remove(turnHandle.sessionId(), token);
        registrations.remove(token);
    }

    private synchronized void ensureServer() {
        if (disposed) throw new IllegalStateException("Claude Hook relay 已停止");
        if (server != null) return;
        try {
            var created = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            var executor = Executors.newSingleThreadExecutor(task -> {
                var thread = new Thread(task, "CodeDeck-Claude-Hook-Relay");
                thread.setDaemon(true);
                return thread;
            });
            created.createContext(ENDPOINT_PREFIX, this::handleRequest);
            created.setExecutor(executor);
            created.start();
            server = created;
            relayExecutor = executor;
        } catch (IOException error) {
            throw new IllegalStateException("无法启动 Claude 文件修改 Hook relay", error);
        }
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        RelayResponse response;
        try {
            // Relay 只接受本机 POST，请求体设置上限以隔离异常 Hook 载荷。
            var remoteAddress = exchange.getRemoteAddress().getAddress();
            if (remoteAddress == null || !remoteAddress.isLoopbackAddress()) {
                response = RelayResponse.denied(403, "Claude 文件写入已阻止：Hook 请求不是来自本机。\n");
            } else if (!Objects.equals(exchange.getRequestMethod(), "POST")) {
                response = RelayResponse.denied(405, "Claude 文件写入已阻止：Hook 请求方法无效。\n");
            } else {
                var body = exchange.getRequestBody().readNBytes(MAX_PAYLOAD_BYTES + 1);
                response = body.length > MAX_PAYLOAD_BYTES
                    ? RelayResponse.denied(413, "Claude 文件写入已阻止：Hook 载荷过大。\n")
                    : evaluate(token(exchange.getRequestURI()), new String(body, StandardCharsets.UTF_8));
            }
        } catch (RuntimeException error) {
            LOG.warn("Claude Hook relay 处理失败", error);
            response = RelayResponse.denied(500, "Claude 文件写入已阻止：无法建立安全修改基线。\n");
        }
        writeResponse(exchange, response);
    }

    private RelayResponse evaluate(String token, String body) {
        JsonObject event;
        try {
            var parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) return RelayResponse.denied(422, "Claude 文件写入已阻止：Hook 载荷格式无效。\n");
            event = parsed.getAsJsonObject();
        } catch (RuntimeException error) {
            return RelayResponse.denied(422, "Claude 文件写入已阻止：Hook 载荷格式无效。\n");
        }

        // 只允许目标 PreToolUse 文件工具声明路径，其他事件不能污染 ChangeSet。
        if (!Objects.equals(string(event, "hook_event_name"), "PreToolUse")) {
            return RelayResponse.denied(422, "Claude 文件写入已阻止：Hook 事件类型无效。\n");
        }
        var toolName = string(event, "tool_name");
        if (!FILE_TOOLS.contains(toolName)) {
            return RelayResponse.denied(422, "Claude 文件写入已阻止：文件工具类型无效。\n");
        }
        var toolInput = object(event, "tool_input");
        var reportedPath = string(toolInput, "file_path");
        if (reportedPath.isBlank() && Objects.equals(toolName, "NotebookEdit")) {
            reportedPath = string(toolInput, "notebook_path");
        }
        if (reportedPath.isBlank()) {
            return RelayResponse.denied(422, "Claude 文件写入已阻止：文件工具没有提供有效路径。\n");
        }
        return captureBaseline(token, reportedPath);
    }

    private synchronized RelayResponse captureBaseline(String token, String reportedPath) {
        var registration = registrations.get(token);
        if (registration == null
            || !Objects.equals(currentTokens.get(registration.turnHandle().sessionId()), token)) {
            return RelayResponse.denied(409, "Claude 文件写入已阻止：当前回合已经失效。\n");
        }

        // 路径通过工作区策略后同步保存首次基线；已跟踪路径继续沿用原始基线。
        var target = changeTracker.resolveWorkspacePath(reportedPath);
        if (target == null) {
            return RelayResponse.denied(422, "Claude 文件写入已阻止：目标路径不在当前项目中。\n");
        }
        var sessionId = registration.turnHandle().sessionId().value();
        var tracked = changeTracker.trackBeforeWrite(sessionId, reportedPath)
            || changeTracker.isTracked(sessionId, target);
        if (!tracked) {
            return RelayResponse.denied(500, "Claude 文件写入已阻止：无法保存文件修改基线。\n");
        }
        return RelayResponse.allowed();
    }

    private String relayCommand(URI endpoint) {
        if (com.intellij.openapi.util.SystemInfo.isWindows) {
            return "powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command \""
                + "$body=[Console]::In.ReadToEnd(); try { "
                + "Invoke-WebRequest -UseBasicParsing -Uri '" + endpoint + "' -Method Post "
                + "-ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) "
                + "-ErrorAction Stop | Out-Null; exit 0 } catch { "
                + "[Console]::Error.WriteLine('Claude 文件写入已阻止：无法建立安全修改基线。'); exit 2 }\"";
        }
        return "curl --silent --show-error --fail --header 'Content-Type: application/json' "
            + "--data-binary @- '" + endpoint + "' >/dev/null || { "
            + "printf '%s\\n' 'Claude 文件写入已阻止：无法建立安全修改基线。' >&2; exit 2; }";
    }

    private String hookSettings(String command) {
        var commandHook = new JsonObject();
        commandHook.addProperty("type", "command");
        commandHook.addProperty("command", command);
        commandHook.addProperty("timeout", 20);

        var commandHooks = new com.google.gson.JsonArray();
        commandHooks.add(commandHook);
        var matcher = new JsonObject();
        matcher.addProperty("matcher", "Write|Edit|NotebookEdit");
        matcher.add("hooks", commandHooks);

        var matchers = new com.google.gson.JsonArray();
        matchers.add(matcher);
        var hooks = new JsonObject();
        hooks.add("PreToolUse", matchers);
        var settings = new JsonObject();
        settings.add("hooks", hooks);
        return GSON.toJson(settings);
    }

    private String token(URI uri) {
        var path = uri == null ? "" : Objects.requireNonNullElse(uri.getPath(), "");
        if (!path.startsWith(ENDPOINT_PREFIX)) return "";
        var token = path.substring(ENDPOINT_PREFIX.length());
        return token.contains("/") ? "" : token;
    }

    private JsonObject object(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) return new JsonObject();
        return parent.getAsJsonObject(key);
    }

    private String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        try {
            return object.get(key).getAsString().trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private void writeResponse(HttpExchange exchange, RelayResponse response) throws IOException {
        var content = response.message().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(response.status(), response.status() == 204 ? -1 : content.length);
        if (response.status() != 204) exchange.getResponseBody().write(content);
        exchange.close();
    }

    @Override
    public synchronized void dispose() {
        disposed = true;
        registrations.clear();
        currentTokens.clear();
        if (server != null) server.stop(0);
        server = null;
        if (relayExecutor != null) relayExecutor.shutdownNow();
        relayExecutor = null;
    }
}
