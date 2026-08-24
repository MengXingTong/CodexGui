# Codex GUI for JetBrains

一个只面向 OpenAI Codex CLI 的 JetBrains 插件。部分界面结构、样式与交互实现参考并改写自 [CC GUI](https://github.com/zhukunpenglinyutong/jetbrains-cc-gui)；插件后端通过 `codex app-server --stdio` 使用 Codex 原生 JSON-RPC 协议，不添加增强提示词，也不提供供应商切换。

当前开发版本：`0.3.3`

## 开发状态

本项目尚未正式发布，仍在持续开发中。

## 当前能力

- 原生 Codex 会话创建、恢复、历史和流式响应
- 模型、推理强度、沙箱与审批策略
- 全中文自绘选择器、全自动审批、流式传输与思考过程开关
- 亮色主题使用独立的高对比度颜色体系，覆盖聊天区、菜单、设置、Skills、MCP、弹窗及禁用状态
- 审批模式展开菜单采用适合 JetBrains 工具窗的紧凑双行布局
- 命令执行、文件修改审批和中断
- 无超时的 Codex 结构化提问窗口，支持方案说明、“其它”输入和多问题导航
- 文本、文件引用与图片输入，支持编辑器选区上下文、项目树拖拽文件引用与图片附件；文件标签按输入位置发送给 Codex，并携带完整绝对路径；标签可直接拖动排序，也可选择、复制或剪切，支持右键剪切，并按拖放位置或光标位置插入，删除或剪切后再次插入不会错位
- 用户/项目 Skills 导入、启停和 Codex 自动发现
- 会话内命令、推理、计划、MCP 与文件修改事件展示
- 基于回合前快照的修改捕获，覆盖补丁与 Shell 写文件
- 修改列表、IDE Diff、逐文件/全部保留或撤销
- 会话收藏、搜索、重命名和 Markdown 导出
- CC-Gui 式单列界面：顶端多会话页签、会话时间线、页内历史/设置、底部输入卡与可展开修改状态面板
- 页签右键菜单支持关闭当前/全部/其它页签、前后切换、页签列表、重命名、导出、会话搜索和左右移动，并提供常用快捷键
- 亮色主题下页签菜单和任务/修改状态面板保持明亮配色；新建页签自动使用递增序号，空白页签也支持重命名
- 多个页签可同时运行独立 Codex 回合，后台页签的流式响应、完成状态和提问不会串到当前页签
- 输入聊天框内容超过自动高度上限时支持纵向滚动
- 长流式回复采用增量事件传输，降低 IDE JVM 堆内存峰值
- CC-Gui 式设置侧栏：基础设置、全局/项目提示词、Agent 身份与 Codex MCP
- 输入区 Agent 身份标签支持直接切换身份；聊天设置浮层只保留流式传输与思考过程开关

全局提示词、项目提示词和 Agent 身份都属于用户主动保存的配置，只通过 Codex 原生 `developerInstructions` 传给新会话。插件不会恢复或复用 CC-Gui 的隐藏增强提示词。

界面层级、尺寸和样式令牌记录在 [`docs/cc-gui-visual-spec.md`](docs/cc-gui-visual-spec.md)。该规格结合 CC-Gui 的公开界面与开源实现整理，本项目未直接打包 CC-Gui 的品牌资源。

## 使用前提

- JetBrains IDE `2024.3` 或更高版本，并使用支持 JCEF 的 JetBrains Runtime
- 已安装并完成登录的 OpenAI Codex CLI
- 在终端执行 `codex` 可以正常启动 CLI，或已在 **设置 → 工具 → Codex GUI** 中填写 `codex`、`codex.cmd` 或 CLI 的绝对路径

插件本身不包含 Codex CLI、模型服务或账号认证。Windows 下插件会额外搜索 Codex 桌面应用释放的 CLI。

## 开发与试用

本地开发要求使用 JDK 21。仓库协作约定见 [`AGENTS.md`](AGENTS.md)；修改源码或文档时，需要同步维护对应版本的 [`CHANGELOG.md`](CHANGELOG.md)。

```bash
./gradlew runIde
./gradlew test
./gradlew buildPlugin
```

`runIde` 会启动用于调试插件的沙箱 IDE，`buildPlugin` 的构建产物位于 `build/distributions/`。Windows PowerShell 可使用 `gradlew.bat` 替代 `./gradlew`。

## 已知边界

- 仅支持 Codex CLI 的原生 app-server 协议，不能切换到其它模型供应商或兼容 OpenAI API 的自定义后端。
- 若当前 JetBrains Runtime 不提供 JCEF，工具窗会显示不可用提示，需更换支持 JCEF 的运行时。
- 全局提示词、项目提示词和 Agent 身份只对新建会话生效；插件不会注入或恢复 CC-Gui 的隐藏提示词。

## 开源与版权

本项目采用 [MIT License](LICENSE) 开源，原创部分版权归 `MengXingTong` 所有。

部分界面设计与实现基于 MIT 许可的 [CC GUI](https://github.com/zhukunpenglinyutong/jetbrains-cc-gui)，原始版权为 `Copyright (c) 2026 zhukunpenglinyutong（朱昆鹏）`。完整许可与版权声明见 [LICENSE](LICENSE)，同一声明也会随插件发行包提供。本项目与 CC GUI 及其作者不存在隶属或官方合作关系。
