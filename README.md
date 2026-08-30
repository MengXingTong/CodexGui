# Codex GUI for JetBrains

> 开发中，暂未发布

面向 JetBrains IDE 的 OpenAI Codex CLI 图形界面插件。插件通过 `codex app-server --stdio` 直接使用 Codex 原生 JSON-RPC 协议，不注入第三方提示词，也不提供其它模型供应商切换。

当前开发版本：`0.4.1`。项目尚未发布到 JetBrains Marketplace，目前只能从源码运行或自行构建插件包。

## 当前能力

- 原生 Codex 会话创建、恢复、历史和流式响应
- AI 响应进行中时在聊天底部显示旋转加载图标、状态和已用时间
- AI 回复进行中仍可发送消息，后续消息会按当前会话顺序排队处理
- 模型、推理强度、沙箱与审批策略
- 全中文自绘选择器、全自动审批与流式传输开关
- 亮色主题使用独立的高对比度颜色体系，覆盖聊天区、菜单、设置、Skills、MCP、弹窗及禁用状态
- 审批模式展开菜单采用适合 JetBrains 工具窗的紧凑双行布局
- 命令执行、文件修改审批和中断
- 无超时的 Codex 结构化提问窗口，支持方案说明、“其它”输入和多问题导航
- 文本、文件引用与图片输入，支持编辑器选区上下文、项目树拖拽文件引用与图片附件；输入 `@` 可稳定搜索项目文件并插入文件标签，搜索过程不会打断聊天框文字选择；文件标签按输入位置发送给 Codex，并统一传递为 `@` 加完整绝对路径，也可直接拖动排序、选择、复制或剪切；复制已发送消息中的完整文件路径后，粘贴到输入框会恢复为文件标签
- 用户/项目 Skills 导入、启停和 Codex 自动发现
- 会话内命令、计划、MCP 与文件修改事件展示；命令以单行省略形式显示，并用红绿圆点标识执行结果，连续批量命令会收纳到可滚动的批量框中
- AI 回复中的本地文件路径链接支持直接跳转到 JetBrains 编辑器对应的文件、行和列，并兼容 Windows 盘符路径
- 基于 Codex app-server 文件修改 diff 的修改捕获栏、IDE Diff、源文件跳转、逐文件/全部接受或撤销；修改列表显示文件名并保留路径提示，不扫描整个工作区；已接受的修改在后续累计 diff 中保持确认状态，文件再次发生变化时才重新出现
- 修改捕获按会话页签隔离；关闭页签或在当前页签开启新对话后，该会话的修改视为已确认，不再参与后续会话状态
- 会话收藏、搜索、重命名和 Markdown 导出
- CC-Gui 式单列界面：顶端多会话页签、会话时间线、页内历史/设置、修改捕获栏与底部输入卡
- 顶部新增“开启新对话”按钮；当前会话已有消息时会先弹出确认窗口，再在当前页签清空并开始新的对话
- 页签右键菜单支持关闭当前/全部/其它页签、前后切换、页签列表、重命名、导出、会话搜索和左右移动，并提供常用快捷键
- AI 回合中的工具消息标题不会覆盖会话页签名称
- 亮色主题下页签菜单保持明亮配色；新建页签自动使用递增序号，空白页签也支持重命名
- 多个页签可同时运行独立 Codex 回合，后台页签的流式响应、完成状态和提问不会串到当前页签
- 输入聊天框内容超过自动高度上限时支持纵向滚动，并在输入时跟随光标保持可视
- 长流式回复采用增量事件传输，降低 IDE JVM 堆内存峰值
- CC-Gui 式设置侧栏：基础设置、全局/共享提示词、Agent 身份与 Codex MCP
- 输入区 Agent 身份标签支持直接切换身份；聊天设置浮层只保留流式传输开关
- 聊天输入框、状态标签和相关悬浮菜单采用紧凑字号，减少输入区占用空间

全局提示词、共享提示词和 Agent 身份均由用户主动保存，只通过 Codex 原生 `developerInstructions` 传给新会话。插件不会恢复或复用 CC-Gui 的隐藏增强提示词。

## 使用前提

由于项目尚未发布，运行插件需要准备：

- JDK 21
- JetBrains IDE `2024.3` 或更高版本，并使用支持 JCEF 的 JetBrains Runtime
- 已安装并完成登录的 OpenAI Codex CLI
- 在终端执行 `codex` 可以正常启动 CLI，或已在 **设置 → 工具 → Codex GUI** 中填写 `codex`、`codex.cmd` 或 CLI 的绝对路径

插件本身不包含 Codex CLI、模型服务或账号认证；插件会完整遵循 `CODEX_HOME/config.toml` 中的 provider、`base_url` 和认证设置。Windows 下插件会额外搜索 Codex 桌面应用释放的 CLI。
Windows 下插件优先使用用户级 `~/.codex` 内的本地 Codex 安装，再退回 `PATH`，避免误启动其它旧版 `codex`。

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
./gradlew test
./gradlew buildPlugin
```

Windows PowerShell 使用 `gradlew.bat` 替代 `./gradlew`。构建产物位于 `build/distributions/`。

## 已知边界

- 仅支持 Codex CLI 的原生 app-server 协议；模型 provider 和兼容 OpenAI API 的自定义后端需在 `config.toml` 中配置，插件界面不提供切换入口。
- 如果当前 JetBrains Runtime 不提供 JCEF，工具窗会显示不可用提示，需要更换支持 JCEF 的运行时。
- 全局提示词、共享提示词和 Agent 身份只对新建会话生效；插件不会注入或恢复 CC-Gui 的隐藏提示词。
- 修改列表只捕获当前 Codex 回合通过 app-server 报告的文件 diff；回合外或其它工具直接修改的文件不会自动进入列表。
- 尚未提供 Marketplace 安装包和稳定版发布渠道。

## 界面参考

界面层级、尺寸和样式令牌记录在 [`docs/cc-gui-visual-spec.md`](docs/cc-gui-visual-spec.md)。该规格结合 CC-Gui 的公开界面与开源实现整理，本项目未直接打包 CC-Gui 的品牌资源。

## 开源与版权

本项目采用 [MIT License](LICENSE) 开源，原创部分版权归 `MengXingTong` 所有。

部分界面设计与实现基于 MIT 许可的 [CC GUI](https://github.com/zhukunpenglinyutong/jetbrains-cc-gui)，原始版权为 `Copyright (c) 2026 zhukunpenglinyutong（朱昆鹏）`。完整许可与版权声明见 [LICENSE](LICENSE)，同一声明也会随插件发行包提供。本项目与 CC GUI 及其作者不存在隶属或官方合作关系。
