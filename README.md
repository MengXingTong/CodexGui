# CodeDeck for JetBrains

CodeDeck 是一个给 JetBrains IDE 使用的 Codex / Claude Code 图形界面插件。它把终端里的 AI 编程助手放进 IDE 工具窗里，保留会话、流式回复、工具执行、审批、文件修改查看和多页签工作流。

当前版本：`1.0`。项目尚未发布到 JetBrains Marketplace，需要通过源码运行或安装本地构建产物。Claude Code 渠道仍缺少充分测试，建议优先在非关键项目中验证后再使用。

## 它适合做什么

- 在 IntelliJ IDEA、Rider 等 JetBrains IDE 内直接使用 Codex 或 Claude Code，不来回切终端。
- 让 AI 修改文件后，在 IDE 内查看 Diff、接受或撤销变更。
- 同时维护多个独立会话，后台会话可以继续运行，前台输入不会被打断。
- 通过自定义供应商接入兼容 OpenAI 或 Anthropic 接口的模型服务。
- 用 Skills、MCP、提示词和 Agent 身份组织更稳定的项目级工作流。

CodeDeck 不会注入第三方隐藏提示词。全局提示词、共享提示词和 Agent 身份都需要用户主动保存，并且只对新建会话生效。

## 核心能力

### 会话与输入

- 支持 Codex 会话创建、恢复、历史浏览、流式响应和中断。
- 支持 Claude Code CLI 会话创建、续接、流式响应和工具状态展示。
- GPT 与 Claude 渠道按页签隔离；切换渠道时会在当前页签开启对应的新会话。
- 多个页签可以同时运行独立 AI 回合，后台流式输出不会污染当前页签。
- 输入框支持文本、图片、编辑器选区、项目树拖拽文件和 `@` 文件搜索。
- 文件引用以稳定标签插入输入框，可选择、删除、拖动排序、撤销重做和跨 IDE 窗口复制粘贴。
- AI 回复进行中仍可继续发送消息；普通发送会排队，GPT 渠道还支持对当前回合立即引导。

### 供应商与模型

- 内置 Codex 与 Claude Code 的本机 CLI 配置。
- 支持按 GPT / Claude 渠道添加、编辑、启用和删除自定义供应商。
- GPT 自定义供应商支持 Responses API 或 Chat Completions API。
- Claude 自定义供应商通过 `ANTHROPIC_BASE_URL` 与 `ANTHROPIC_AUTH_TOKEN` 接入。
- 启用自定义供应商前会读取模型目录；模型选择器支持添加接口未返回的自定义模型 ID。
- API 密钥和认证令牌保存到 JetBrains PasswordSafe，不写入插件 XML 或网页层状态。

### 修改捕获与 Diff

- Codex 使用 app-server 报告的结构化文件事件，不扫描整个工作区。
- Claude Code 对 `Write`、`Edit` 和 `NotebookEdit` 文件工具建立首次修改基线。
- 修改列表按会话页签隔离，显示新增、修改、删除和行数变化。
- 可以逐个文件接受或撤销修改；新文件撤销时会删除，超过 5 MiB 的文件会标记为不可撤销。
- 从修改列表打开 Diff 时，右侧绑定当前真实源文件；重复打开同一文件会定位到已有 Diff 页签。
- 本地文件路径链接支持跳转到 JetBrains 编辑器，也可右键在系统文件管理器中定位。

### Codex 专属能力

- 通过 `codex app-server --stdio` 使用 Codex 原生 JSON-RPC 协议。
- 支持模型、推理强度、沙箱、审批策略、命令执行审批和结构化提问。
- 支持 Codex Skills、MCP、账户用量和 app-server 专属审批能力。
- Codex app-server 具备显式生命周期管理；断线、重启和项目关闭会清理未完成请求。

### Claude Code 渠道

- 通过 `claude -p --output-format stream-json` 使用 Claude Code 的结构化流式输出。
- Claude 历史读取当前项目的 Claude Code JSONL 文件，与 Codex 历史分开显示。
- Claude MCP、权限和项目配置仍由 Claude Code CLI 自己管理。
- Claude Code 当前成熟度低于 Codex 渠道，不建议用于不可恢复的关键修改。

## 使用前提

运行插件前需要准备：

- JDK 21
- JetBrains IDE `2024.3` 或更高版本
- 支持 JCEF 的 JetBrains Runtime
- OpenAI Codex CLI，或 Anthropic Claude Code CLI
- Claude Code `2.1.210` 或更高版本，若需要使用 Claude 渠道

插件本身不包含 Codex / Claude Code CLI、模型服务或账号认证。请先确保在终端执行 `codex` 或 `claude` 可以正常启动。也可以在 **设置 -> 工具 -> CodeDeck** 中填写 `codex`、`codex.cmd`、`claude`、`claude.cmd` 或 CLI 的绝对路径。

Windows 下，CodeDeck 会优先搜索用户级 `~/.codex` 内的本地 Codex 安装，再退回 `PATH`，避免误启动旧版本 CLI。

## 快速开始

1. 准备 JDK 21 和支持 JCEF 的 JetBrains IDE。
2. 安装并登录 Codex CLI 或 Claude Code CLI。
3. 从源码运行插件，或安装本地构建出的 zip 包。
4. 打开 IDE 侧边栏中的 CodeDeck 工具窗。
5. 在输入区选择 GPT 或 Claude 渠道，必要时到设置页添加自定义供应商。
6. 发起对话，查看 AI 回复、工具执行和文件修改。

## 从源码运行

在项目根目录执行：

```shell
# Linux/macOS
./gradlew runIde

# Windows PowerShell
./gradlew.bat runIde
```

运行测试并构建插件包：

```shell
./gradlew clean check buildPlugin verifyPlugin
```

Windows 也可以使用一键打包脚本：

```powershell
.\tools\package.ps1
```

脚本会执行清理、测试、插件构建和兼容性验证，成功后输出发行包路径、文件大小和 SHA-256。构建产物位于 `build/distributions/CodeDeck-<version>.zip`。

Gradle 会下载项目锁定的 Node.js，并自动执行 TypeScript 类型检查、Vitest、Playwright Chromium 回归和 esbuild。首次运行浏览器测试时，Playwright 会下载测试用 Chromium。

## 配置说明

### 本地 CLI 配置

“本地配置”会继续使用 Codex 的 `CODEX_HOME/config.toml`，或 Claude Code 的本机登录与项目配置。CodeDeck 只负责启动 CLI 并展示会话，不替用户保存模型服务账号。

### 自定义 GPT 供应商

自定义 GPT 供应商会通过 Codex `model_providers` 启动覆盖加载，并从 API 基地址下的 `/models` 读取模型。可选择 Responses API 或 Chat Completions API。

### 自定义 Claude 供应商

自定义 Claude 供应商会通过 `ANTHROPIC_BASE_URL` 和 `ANTHROPIC_AUTH_TOKEN` 启动 Claude Code，并使用 Bearer 认证从 `/v1/models` 读取模型。

### 提示词与 Agent 身份

全局提示词、共享提示词和 Agent 身份均由用户主动保存。Codex 通过原生 `developerInstructions` 传给新会话，Claude Code 通过 CLI 的附加系统提示传给新会话。

## 已知边界

- Claude Code 渠道仍缺少充分测试，稳定性和兼容性不如 Codex 渠道。
- 自定义供应商的工具调用、模型能力和上下文行为取决于供应商自身兼容性。
- 同一渠道当前只启用一个供应商配置；切换 GPT 供应商会重启 Codex app-server。
- 修改供应商配置后，旧会话不能继续发送，避免把上下文误发到另一个接口。
- Codex 的 Skills、MCP、账户用量、结构化审批与回溯能力来自 app-server；Claude 渠道不具备完全相同的能力面。
- Claude 只对带明确路径的文件工具建立修改基线；纯 Bash/PowerShell 首次写入不会自动进入修改列表。
- 如果 JetBrains Runtime 不提供 JCEF，工具窗会显示不可用提示，需要更换运行时。
- 当前没有 Marketplace 安装包和稳定版发布渠道。

## 开发与验证

仓库在每次 push 和 Pull Request 上执行插件验证流程，包括：

- `clean check`
- `buildPlugin`
- `verifyPlugin`
- Java 单元测试
- TypeScript 类型检查
- Vitest 测试
- Playwright Chromium 输入框回归
- 协议 fixture 与插件兼容性检查

前端使用 TypeScript、esbuild 和锁定依赖构建。页面通过版本化 Bridge v1 envelope 与 Java 侧通信，未知版本、未知类型或缺失身份字段会返回结构化 `protocol.error`。

## 界面参考

界面层级、尺寸和样式令牌记录在 [`docs/cc-gui-visual-spec.md`](docs/cc-gui-visual-spec.md)。该规格结合 CC GUI 的公开界面与 MIT 开源实现整理，CodeDeck 不直接打包 CC GUI 的品牌资源。

## 开源与版权

本项目采用 [MIT License](LICENSE) 开源，原创部分版权归 `MengXingTong` 所有。

部分界面设计与实现基于 MIT 许可的 [CC GUI](https://github.com/zhukunpenglinyutong/jetbrains-cc-gui)，原始版权为 `Copyright (c) 2026 zhukunpenglinyutong（朱昆鹏）`。完整许可与版权声明见 [LICENSE](LICENSE)，同一声明也会随插件发行包提供。本项目与 CC GUI 及其作者不存在隶属或官方合作关系。
