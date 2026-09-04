# CodeDeck for JetBrains

> 开发中，暂未发布

CodeDeck 是面向 JetBrains IDE 的 Codex / Claude Code 图形界面插件。Codex 通过 `codex app-server --stdio` 使用原生 JSON-RPC 协议，Claude Code 通过 `claude -p --output-format stream-json` 使用结构化流式输出；插件不注入第三方隐藏提示词。

当前开发版本：`0.5.1`。项目尚未发布到 JetBrains Marketplace，目前只能从源码运行或自行构建插件包。

## 当前能力

- 原生 Codex 会话创建、恢复、历史和流式响应，以及 Claude Code CLI 会话创建、恢复、续接、流式响应和工具状态展示；Claude 与 Codex 会话按渠道隔离，切换渠道时会在当前页签自动开启对应的新会话
- Codex app-server 使用显式生命周期状态与进程 generation，初始化、普通 RPC 和 OAuth 分别设有 20 秒、60 秒和 5 分钟超时；断线、重启和项目关闭会清理未完成请求，停止进程最多等待 2 秒后强制结束
- 输入区设置支持 Claude / GPT 渠道切换；设置页提供按渠道分组的完整供应商管理，可添加、编辑、启用和删除自定义 API 服务
- 每个渠道保留一个使用本机 CLI 登录状态的内置配置；自定义供应商只配置名称、API 地址与凭据，GPT 可选择 Responses 或 Chat Completions 协议，Claude 中转渠道统一使用认证令牌且不显示认证类型选项，凭据保存在 JetBrains PasswordSafe 中
- 自定义供应商启用前必须成功读取非空模型目录；插件优先恢复该供应商上次选择且仍有效的模型，否则选择接口首项。读取失败时保持原供应商，不进入可发送状态
- 添加或编辑供应商只在后端确认保存成功后关闭弹窗；校验或凭据保存失败时保留名称、接口地址、认证凭据和协议，便于直接修改后重试
- AI 响应进行中时在聊天底部显示旋转加载图标、状态和已用时间
- 用户消息与 AI 回复会显示首次发送时间并提供整条消息复制按钮；AI 回复将时间与复制操作收纳在同一紧凑行，并缩短分段回复的上下留白，流式回复只记录开始时间，不会随增量内容刷新
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
- 修改捕获栏由 Conversation ChangeSet 驱动：只有 Provider 明确报告或 Claude 文件 Hook 预先声明的路径才会进入列表；首次触碰保存存在/缺失基线，后续用户编辑从 JetBrains Document/VFS 或磁盘实时读取，并通过批量 VFS 事件只刷新已跟踪路径
- 接受修改会清除该文件当前基线，再次被 Provider 触碰时重新建立基线；撤销会恢复首次基线，新文件会删除，超过 5 MiB 的文件仍显示变化但标记为不可撤销
- 修改捕获按会话页签隔离；关闭页签或在当前页签开启新对话后，该会话的修改视为已确认，不再参与后续会话状态
- 会话收藏、搜索、重命名和 Markdown 导出
- CC-Gui 式单列界面：顶端多会话页签、会话时间线、页内历史/设置、修改捕获栏与底部输入卡
- 顶部新增“开启新对话”按钮；当前会话已有消息时会先弹出确认窗口，再在当前页签清空并开始新的对话
- 页签右键菜单支持关闭当前/全部/其它页签、前后切换、页签列表、重命名、导出、会话搜索和左右移动，并提供常用快捷键
- AI 回合中的工具消息标题不会覆盖会话页签名称
- 亮色主题下页签菜单保持明亮配色；新建页签自动使用递增序号，空白页签也支持重命名
- 多个页签可同时运行独立 AI 回合，后台页签的流式响应、完成状态和提问不会串到当前页签
- Claude 流式回复沿用 Provider 事件的独立条目 ID，并在每次工具调用后开启新的文本段；文字与批量工具卡片按实际发生顺序交错显示，与 Codex 时间线行为一致
- 后台页签持续输出时不会重建当前聊天输入框；各页签输入草稿保持隔离，当前光标位置和输入法组合状态不受后台事件影响
- 会话状态由独立 Session Kernel 持有；活动页签只决定当前显示内容，取消、关闭或被新 generation 替代的回合事件会被丢弃，关闭页签同时释放队列与运行状态
- JCEF Bridge 使用版本化 v1 envelope（`v/type/requestId/sessionId/turnId/generation/payload`）；Java 与 TypeScript 共享固定 command/event 判别类型，未知版本、类型或缺失身份字段会返回结构化 `protocol.error`
- 多个审批与结构化提问由 `PendingInteractionRegistry` 按 request、Session、Turn、generation、类型和截止时间隔离；旧扁平 Bridge 消息只通过 Legacy adapter 进入兼容路径
- 前端使用 TypeScript、esbuild 和锁定依赖构建；页面只注册一个 Bridge receiver，并由 store/reducer 按 Session 路由状态，流式回复与运行中工具输出通过 `requestAnimationFrame` 合并后原地刷新目标消息节点，持续输出时仍可稳定悬停和展开详情
- 长会话每批加载 100 条消息；Markdown 由 `marked` 解析并经 DOMPurify 清洗，避免回复内容注入可执行脚本
- Claude Code 作为项目级服务运行，每个 CLI 进程绑定完整 `TurnHandle`；用户取消与 CLI 执行失败分别处理，Codex 重连不会中断 Claude 回合
- Claude Code 启动、首个响应事件、退出、取消和失败会写入 Rider `idea.log`；标准错误会脱敏、限长并实时记录。启动后 90 秒仍未收到流式、助手或结果事件时，插件会终止 CLI 并提示检查供应商接口、模型名称和网络代理，CLI 本地初始化不会提前解除监视，已开始响应的长任务不受该超时限制
- Codex 与 Claude 均通过最小 `ConversationProvider` 接口启动和取消回合；模型、流式文本、工具、文件变化、用量、完成与失败统一转换为携带完整 `TurnHandle` 的 `TurnEvent`，Provider 原始 JSON 不进入会话控制层
- 工具窗面板只负责 JCEF 创建、依赖组装、页面资源加载和释放；会话、Provider、审批、Settings、Workspace/IDE、Codex 能力与注意力通知按粗粒度职责组织，审批身份校验和通知/声音策略不再散落在面板代码中
- Claude `Write`、`Edit` 与 `NotebookEdit` 在写入前通过仅监听 `127.0.0.1` 的 PreToolUse Hook relay 校验路径并同步保存首次基线；Hook 设置通过逐回合临时 JSON 文件传给 CLI 并在结束后清理，回合结束时主动刷新已跟踪文件并发布修改列表，不依赖 IDE 是否即时收到外部文件 VFS 通知；缺失、畸形、越界或旧 generation 请求会阻止对应文件工具
- 输入聊天框内容超过自动高度上限时支持纵向滚动，并在输入时跟随光标保持可视
- 中文输入法组合输入期间支持正常退格，不会误关闭输入法；修改列表点击和状态浮层交互保持稳定
- 文件引用标签前后保留可见光标位置，输入框最前方退格不会误删标签
- 输入框支持按 `Shift+Enter` 单次换行；也可在基础设置中切换为 `Ctrl+Enter` 发送、`Enter` 换行
- 长流式回复采用增量事件传输，降低 IDE JVM 堆内存峰值
- CC-Gui 式设置侧栏：基础设置、供应商、全局/共享提示词、Agent 身份、Skills 与 Codex MCP；各设置页面保持一致的导航宽度和完整文字标签
- 输入区 Agent 身份标签支持直接切换身份；默认身份以中性色表示未附加身份指令，自定义身份才使用强调色；切换菜单不会触发消息输入框高亮
- 聊天输入框、状态标签和相关悬浮菜单采用紧凑字号，减少输入区占用空间
- 修改捕获列表的单文件接受与撤销按钮使用统一的无原生灰底样式，悬停时提供清晰反馈
- 设置在异步任务启动前固化为不可变快照，受限选项使用枚举并通过一次性 schema migration 兼容旧配置；项目文件搜索使用 JetBrains `ProjectFileIndex`，所有工作区路径统一经过同一边界策略校验

全局提示词、共享提示词和 Agent 身份均由用户主动保存：Codex 通过原生 `developerInstructions` 传给新会话，Claude Code 通过 CLI 的附加系统提示传入新会话。插件不会恢复或复用 CC-Gui 的隐藏增强提示词。

## 使用前提

由于项目尚未发布，运行插件需要准备：

- JDK 21
- JetBrains IDE `2024.3` 或更高版本，并使用支持 JCEF 的 JetBrains Runtime
- 至少安装 OpenAI Codex CLI 或 Anthropic Claude Code CLI；插件始终通过对应 CLI 执行会话，自定义供应商只替换其模型 API 与认证配置
- 在终端执行 `codex` 可以正常启动 CLI，或已在 **设置 → 工具 → CodeDeck** 中填写 `codex`、`codex.cmd` 或 CLI 的绝对路径
- 使用 Claude Code `2.1.210` 或更高版本，并确保在终端执行 `claude` 可以正常启动 CLI；也可在 **设置 → 工具 → CodeDeck** 中填写 `claude`、`claude.cmd` 或 CLI 的绝对路径

插件本身不包含 Codex / Claude Code CLI、模型服务或账号认证。供应商设置页中的“本地配置”会继续遵循 Codex 的 `CODEX_HOME/config.toml` 或 Claude Code 的本机登录与项目配置；自定义 GPT 供应商通过 Codex `model_providers` 启动覆盖加载，并从其 API 基地址下的 `/models` 读取模型；自定义 Claude 供应商通过 `ANTHROPIC_BASE_URL` 与 `ANTHROPIC_AUTH_TOKEN` 加载，并使用 `Authorization: Bearer` 从 `/v1/models` 读取模型。Windows 下插件会额外搜索两者的用户级 CLI 安装位置。
自定义供应商的认证凭据仅保存到 JetBrains PasswordSafe，供应商列表和插件 XML 不保存明文凭据。切换 Claude / GPT 渠道时会在当前页签清空原对话并开启目标渠道的新会话；历史页按当前会话渠道筛选，Claude 历史读取当前项目的 Claude Code JSONL，Codex 历史只展示 app-server 来源，两个渠道不会相互混入。修改同一渠道的供应商配置后，旧会话仍会阻止使用新配置继续发送，避免把上下文误发到另一个接口。
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
./gradlew clean check buildPlugin verifyPlugin
```

Windows PowerShell 使用 `gradlew.bat` 替代 `./gradlew`。Gradle 会下载项目锁定的 Node.js 并自动执行 TypeScript 类型检查、前端单测和 esbuild，无需预装 Node.js；构建产物为 `build/distributions/CodeDeck-<version>.zip`。

仓库在每次 push 和 Pull Request 上执行 `clean check`、`buildPlugin` 与 `verifyPlugin`，协议 fixture、单元测试和插件兼容性检查全部通过后才视为验证完成。
Gradle Wrapper JAR 直接存入 Git，不经过 Git LFS，确保 GitHub Actions 能在依赖下载前完成官方校验值验证。

## 已知边界

- 自定义 GPT 供应商需要兼容 OpenAI Responses API 或 Chat Completions API；自定义 Claude 供应商需要兼容 Claude Code 使用的 Anthropic API 环境变量约定。具体模型能力与工具兼容性由供应商决定。
- 同一渠道当前只启用一个供应商配置；切换配置会重启 GPT 渠道的 Codex app-server，运行中的任务完成前不能切换。
- Codex 的 Skills、MCP、账户用量、结构化审批与回溯仍是 app-server 专属能力；Claude Code 历史由插件读取当前项目的 CLI JSONL 文件，MCP 和权限仍由 Claude Code CLI 管理。
- 如果当前 JetBrains Runtime 不提供 JCEF，工具窗会显示不可用提示，需要更换支持 JCEF 的运行时。
- 全局提示词、共享提示词和 Agent 身份只对新建会话生效；插件不会注入或恢复 CC-Gui 的隐藏提示词。
- Codex 文件修改使用 app-server 提供的结构化事件，不扫描整个工作区；Claude 只对带明确路径的文件工具建立首次 membership，纯 Bash/PowerShell 首次写入不会自动进入列表，但已跟踪文件的后续外部修改会实时刷新。
- 尚未提供 Marketplace 安装包和稳定版发布渠道。

## 界面参考

界面层级、尺寸和样式令牌记录在 [`docs/cc-gui-visual-spec.md`](docs/cc-gui-visual-spec.md)。该规格结合 CC-Gui 的公开界面与开源实现整理，本项目未直接打包 CC-Gui 的品牌资源。

## 开源与版权

本项目采用 [MIT License](LICENSE) 开源，原创部分版权归 `MengXingTong` 所有。

部分界面设计与实现基于 MIT 许可的 [CC GUI](https://github.com/zhukunpenglinyutong/jetbrains-cc-gui)，原始版权为 `Copyright (c) 2026 zhukunpenglinyutong（朱昆鹏）`。完整许可与版权声明见 [LICENSE](LICENSE)，同一声明也会随插件发行包提供。本项目与 CC GUI 及其作者不存在隶属或官方合作关系。
