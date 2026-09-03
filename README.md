# Codex GUI for JetBrains

> 开发中，暂未发布

面向 JetBrains IDE 的 Codex / Claude Code 图形界面插件。Codex 通过 `codex app-server --stdio` 使用原生 JSON-RPC 协议，Claude Code 通过 `claude -p --output-format stream-json` 使用结构化流式输出；插件不注入第三方隐藏提示词。

当前开发版本：`0.4.4`。项目尚未发布到 JetBrains Marketplace，目前只能从源码运行或自行构建插件包。

## 当前能力

- 原生 Codex 会话创建、恢复、历史和流式响应，以及 Claude Code CLI 会话创建、续接、流式响应和工具状态展示
- 输入区设置支持 Claude / GPT 渠道切换；设置页提供按渠道分组的完整供应商管理，可添加、编辑、启用和删除自定义 API 服务
- 每个渠道保留一个使用本机 CLI 登录状态的内置配置；自定义供应商可配置名称、API 地址、默认模型和认证方式，API 密钥保存在 JetBrains PasswordSafe 中
- AI 响应进行中时在聊天底部显示旋转加载图标、状态和已用时间
- AI 回复进行中仍可发送消息，后续消息会按当前会话顺序排队处理
- 模型、推理强度、沙箱与审批策略
- 全中文自绘选择器、全自动审批与流式传输开关
- 亮色主题使用独立的高对比度颜色体系；暗色主题中的供应商操作与提示文字保持清晰可读，覆盖聊天区、菜单、设置、Skills、MCP、弹窗及禁用状态
- 审批模式展开菜单采用适合 JetBrains 工具窗的紧凑双行布局
- 命令执行、文件修改审批和中断
- 无超时的 Codex 结构化提问窗口，支持方案说明、“其它”输入和多问题导航
- 文本、文件引用与图片输入，支持编辑器选区上下文、项目树拖拽文件引用与图片附件；输入 `@` 可稳定搜索项目文件并插入文件标签，搜索过程不会打断聊天框文字选择；文件标签按输入位置发送给 Codex，并统一传递为 `@` 加完整绝对路径，也可直接拖动排序、选择、复制或剪切；从已发送消息复制文件路径或包含文件路径的混合内容后，粘贴到其它 IDE 窗口的输入框仍会按原位置恢复文件标签
- 用户/项目 Skills 导入、启停和 Codex 自动发现；Skills 页面按路径区分个人与官方来源，默认只显示个人 Skill，并可切换查看 Codex 自带 Skill
- 会话内命令、计划、MCP 与文件修改事件展示；任务悬浮框会汇总真实的计划和工具执行记录，命令以单行省略形式显示，并用红绿圆点标识执行结果，连续批量命令会收纳到可滚动的批量框中
- AI 回复中的本地文件路径链接支持直接跳转到 JetBrains 编辑器对应的文件、行和列，并兼容 Windows 盘符路径
- 修改捕获栏支持 Codex app-server 文件 diff 与 Claude Code 回合前后工作区快照，两种来源统一进入同一套累计、接受和撤销管线；Claude 快照在临时目录保存小文件基线，内存只保留路径与哈希，固定排除版本库、IDE 和构建产物目录；停止 Claude 回合会等待捕获安全收尾，不会在基线完成后重新启动已停止的任务
- 修改捕获按会话页签隔离；关闭页签或在当前页签开启新对话后，该会话的修改视为已确认，不再参与后续会话状态
- 会话收藏、搜索、重命名和 Markdown 导出
- CC-Gui 式单列界面：顶端多会话页签、会话时间线、页内历史/设置、修改捕获栏与底部输入卡
- 顶部新增“开启新对话”按钮；当前会话已有消息时会先弹出确认窗口，再在当前页签清空并开始新的对话
- 页签右键菜单支持关闭当前/全部/其它页签、前后切换、页签列表、重命名、导出、会话搜索和左右移动，并提供常用快捷键
- AI 回合中的工具消息标题不会覆盖会话页签名称
- 亮色主题下页签菜单保持明亮配色；新建页签自动使用递增序号，空白页签也支持重命名
- 多个页签可同时运行独立 AI 回合，后台页签的流式响应、完成状态和提问不会串到当前页签
- 输入聊天框内容超过自动高度上限时支持纵向滚动，并在输入时跟随光标保持可视
- 中文输入法组合输入期间支持正常退格，不会误关闭输入法；修改列表点击和状态浮层交互保持稳定
- 文件引用标签前后保留可见光标位置，输入框最前方退格不会误删标签
- 输入框支持按 `Shift+Enter` 单次换行；也可在基础设置中切换为 `Ctrl+Enter` 发送、`Enter` 换行
- 长流式回复采用增量事件传输，降低 IDE JVM 堆内存峰值
- CC-Gui 式设置侧栏：基础设置、供应商、全局/共享提示词、Agent 身份、Skills 与 Codex MCP；各设置页面保持一致的导航宽度和完整文字标签
- 输入区 Agent 身份标签支持直接切换身份；紧凑聊天设置浮层包含供应商、流式传输与思考过程开关
- 聊天输入框、状态标签和相关悬浮菜单采用紧凑字号，减少输入区占用空间
- 修改捕获列表的单文件接受与撤销按钮使用统一的无原生灰底样式，悬停时提供清晰反馈

全局提示词、共享提示词和 Agent 身份均由用户主动保存：Codex 通过原生 `developerInstructions` 传给新会话，Claude Code 通过 CLI 的附加系统提示传入新会话。插件不会恢复或复用 CC-Gui 的隐藏增强提示词。

## 使用前提

由于项目尚未发布，运行插件需要准备：

- JDK 21
- JetBrains IDE `2024.3` 或更高版本，并使用支持 JCEF 的 JetBrains Runtime
- 至少安装 OpenAI Codex CLI 或 Anthropic Claude Code CLI；插件始终通过对应 CLI 执行会话，自定义供应商只替换其模型 API 与认证配置
- 在终端执行 `codex` 可以正常启动 CLI，或已在 **设置 → 工具 → Codex GUI** 中填写 `codex`、`codex.cmd` 或 CLI 的绝对路径
- 使用 Claude Code 时，在终端执行 `claude` 可以正常启动 CLI，或在 **设置 → 工具 → Codex GUI** 中填写 `claude`、`claude.cmd` 或 CLI 的绝对路径

插件本身不包含 Codex / Claude Code CLI、模型服务或账号认证。供应商设置页中的“本地配置”会继续遵循 Codex 的 `CODEX_HOME/config.toml` 或 Claude Code 的本机登录与项目配置；自定义 GPT 供应商通过 Codex `model_providers` 启动覆盖加载，自定义 Claude 供应商通过 `ANTHROPIC_BASE_URL` 与所选认证变量加载。Windows 下插件会额外搜索两者的用户级 CLI 安装位置。
自定义供应商的 API 密钥仅保存到 JetBrains PasswordSafe，供应商列表和插件 XML 不保存明文密钥。切换或修改供应商后请开启新对话；旧会话会保留原供应商版本并阻止继续发送，避免把上下文误发到另一个接口。
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

- 自定义 GPT 供应商需要兼容 OpenAI Responses API 或 Chat Completions API；自定义 Claude 供应商需要兼容 Claude Code 使用的 Anthropic API 环境变量约定。具体模型能力与工具兼容性由供应商决定。
- 同一渠道当前只启用一个供应商配置；切换配置会重启 GPT 渠道的 Codex app-server，运行中的任务完成前不能切换。
- Codex 历史、Skills、MCP、账户用量、结构化审批与回溯仍是 app-server 专属能力；Claude Code 目前由 CLI 管理历史、MCP 和权限，不在插件设置中重复维护。
- 如果当前 JetBrains Runtime 不提供 JCEF，工具窗会显示不可用提示，需要更换支持 JCEF 的运行时。
- 全局提示词、共享提示词和 Agent 身份只对新建会话生效；插件不会注入或恢复 CC-Gui 的隐藏提示词。
- Codex 文件修改使用 app-server 提供的结构化 diff，不扫描整个工作区；Claude Code 缺少等价 diff 事件，因此在每个回合前后比较工作区快照。回合外或其它工具直接修改的文件不会自动进入列表。
- 尚未提供 Marketplace 安装包和稳定版发布渠道。

## 界面参考

界面层级、尺寸和样式令牌记录在 [`docs/cc-gui-visual-spec.md`](docs/cc-gui-visual-spec.md)。该规格结合 CC-Gui 的公开界面与开源实现整理，本项目未直接打包 CC-Gui 的品牌资源。

## 开源与版权

本项目采用 [MIT License](LICENSE) 开源，原创部分版权归 `MengXingTong` 所有。

部分界面设计与实现基于 MIT 许可的 [CC GUI](https://github.com/zhukunpenglinyutong/jetbrains-cc-gui)，原始版权为 `Copyright (c) 2026 zhukunpenglinyutong（朱昆鹏）`。完整许可与版权声明见 [LICENSE](LICENSE)，同一声明也会随插件发行包提供。本项目与 CC GUI 及其作者不存在隶属或官方合作关系。
