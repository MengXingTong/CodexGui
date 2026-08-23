# Codex GUI for JetBrains

一个只面向 OpenAI Codex CLI 的 JetBrains 插件。界面按照 CC-Gui 的单列工具窗层级与排版进行 clean-room 重建，但实现完全独立：插件通过 `codex app-server --stdio` 使用 Codex 原生 JSON-RPC 协议，不添加增强提示词，也不提供供应商切换。

## 当前能力

- 原生 Codex 会话创建、恢复、历史和流式响应
- 模型、推理强度、沙箱与审批策略
- 全中文自绘选择器、全自动审批、流式传输与思考过程开关
- 命令执行、文件修改审批和中断
- 无超时的 Codex 结构化提问窗口，支持方案说明、“其它”输入和多问题导航
- 文本、文件引用与图片输入，支持编辑器选区上下文、项目树拖拽文件引用与图片附件
- 用户/项目 Skills 导入、启停和 Codex 自动发现
- 会话内命令、推理、计划、MCP 与文件修改事件展示
- 基于回合前快照的修改捕获，覆盖补丁与 Shell 写文件
- 修改列表、IDE Diff、逐文件/全部保留或撤销
- 会话收藏、搜索、重命名和 Markdown 导出
- CC-Gui 式单列界面：会话时间线、页内历史/设置、底部输入卡与可展开修改状态面板
- CC-Gui 式设置侧栏：基础设置、全局/项目提示词、Agent 身份与 Codex MCP

全局提示词、项目提示词和 Agent 身份都属于用户主动保存的配置，只通过 Codex 原生 `developerInstructions` 传给新会话。插件不会恢复或复用 CC-Gui 的隐藏增强提示词。

界面层级、尺寸和样式令牌记录在 [`docs/cc-gui-visual-spec.md`](docs/cc-gui-visual-spec.md)。该规格来自公开界面的观察结果；项目不包含 CC-Gui 源码或资源。

## 开发

要求 JDK 21。

```shell
./gradlew runIde
./gradlew test buildPlugin
```

插件默认从 `PATH` 查找 `codex`；Windows 下也会发现 Codex 桌面应用释放到用户目录中的可执行 CLI，并跳过无法由普通进程启动的 WindowsApps 副本。可在 **设置 → 工具 → Codex GUI** 中指定可执行文件。
