CodexGui 重构审查报告
一句话结论
项目功能已相当完整，但代码结构是"两个巨型文件承载一切"：CodexToolWindowPanel.java（2870 行）是后端上帝类，app.js（737 行压缩单文件）是前端上帝类，两者之间通过约 60 个 action 和 25 个事件类型的裸字符串协议耦合，没有任何集中定义或类型保护。重构的主线应该是：先把协议类型化并集中定义，再把上帝类按职责切分，最后处理性能和卫生问题。当前所有工作区改动尚未提交，建议先提交或暂存，再开始重构。

二、核心问题诊断
1. CodexToolWindowPanel 是上帝类（最高优先级）
一个 Swing 面板类同时承担了以下全部职责（引用行号）：

职责	位置	应属于
桥接消息分发（62 个 case 的 switch）	CodexToolWindowPanel.java:239-333	协议路由层
多会话状态管理（SessionState + 15 个"current*"镜像字段来回拷贝）	:100-147, :378-427	会话管理器
Codex 回合调度 / Claude 回合调度	:514-578, :580-677	Provider 适配层
Codex 通知事件分发（onNotification 的 switch）	:2430-2489	Codex 事件处理器
审批对话框、超时定时器	:2637-2736	审批服务
供应商配置增删改、URL 校验	:781-981	供应商服务
Skills 导入（含文件复制、回滚删除）	:1373-1437	Skills 服务
MCP 状态与配置合并	:2100-2201	MCP 服务
提示词 / Agent 身份 CRUD	:1830-1936	设置服务
拖放坐标换算、项目树 DnD	:1179-1284	UI 输入层
Diff 查看器、文件跳转、路径规范化	:1459-1499, :1649-1673	IDE 集成层
通知与提示音	:1769-1807	通知服务
一整套 JSON 取值工具（array/string/integer/bool/longValue/doubleValue）	:2763-2807	公共工具
最危险的设计是"活动会话镜像"模式：currentThreadId、busy、transcript 等 15 个字段是当前会话的副本，任何异步回调都要先调 activateSession(sessionId) 把目标会话"切换到前台"再操作（例如 :552, :564, :612, :653, :2420-2425）。这意味着：

每个异步回调都依赖"先 activateSession 再操作"的隐式纪律，漏一次就会把数据写进错误的会话；
onConnectionChanged（:2418-2425）为了给所有会话复位，要遍历切换再切回；
event() 工具（:2389-2394）用 activeSessionId 打标签，所以后台会话事件的 sessionId 是否正确完全取决于调用时机。
这套机制是历次修"后台页签串台"bug 的根源，应该直接改为 每个会话一个独立对象，方法显式接收 SessionState 参数，删除全部 current* 镜像字段。

2. 前后端协议无集中定义、无类型
事件名与 action 名在 Java（:251-329, :2389）和 JS（app.js:613 的 receive switch、数十处 post('xxx')）两侧各自硬编码。改一个名字没有任何编译期保护。- bootstrap 事件用 Object.assign(state, e.state)（app.js:613）整体合并，Java 侧字段名必须与前端 state 键逐字一致，否则静默丢失。- tools/ui-preview.html:42-139 手写了第三份协议实现（模拟桥），同样靠手工同步。- 服务层对 UI 暴露的全是 CompletableFuture<JsonObject>（CodexAppServerService 通篇），UI 层再用 result.getAsJsonObject("thread").get("id").getAsString() 之类裸取值（:553, :565-566, :1038, :2249）。协议 JSON 结构知识散布在 UI 类里。
3. app.js 的演进方式已不可持续
receive 被猴补丁包装了 7 层（app.js:614-616, 634-656, 657-674, 675-681, 683, 684, 716, 732），每层 const prev = window.CodexGui.receive; window.CodexGui.receive = e => {...; prev(e)}，同一份事件被 JSON.parse 多次。这是典型的"每加一个功能就再包一层"，调试时几乎无法追踪事件去向。- 每个事件都全量 innerHTML 重绘（render() app.js:473-474），然后 bind()（app.js:569-610）重新绑定全部监听器。为了在重绘中不丢状态，render() 里手工抢救光标位置、滚动位置、焦点、selection range。流式输出时每个 token 触发一次全页重建，这是性能和复杂度的双重爆点。- 页签快照机制 tabStateKeys / snapshotTab / restoreTab（app.js:42-46）是前端版的"活动会话镜像"，与后端问题同构：新增一个页签级字段要同时改三处。- state.messages 只增不减，长会话 DOM 无限增长。
4. 两条 Provider 路径没有共同抽象
Codex（长驻 JSON-RPC 进程）与 Claude（每回合一个短命进程）在 Panel 里是两套完全平行的代码路径（dispatchInput vs dispatchClaudeInput），并且靠 Objects.equals(currentProvider, "claude") 字符串判断分叉（:461, :537, :1110, :1663, :1687, :2406 等 15+ 处）。ClaudeCodeService 甚至不是 IDE Service，由 Panel 直接 new（:154）。

可以统一的部分：回合启动、中断、可用性检测、凭据注入、流式事件回调（文本增量 / 思考增量 / 工具调用 / 完成 / 错误）。不应强行统一的部分：Codex 独有的 thread 历史、服务端审批请求、MCP、Skills、用量查询，这些应作为可选扩展接口。

5. 服务层具体缺陷（已核实）
潜在 bug：ClaudeCodeService.java:151 在 try 块内抛出的 CancellationException 会被 :167 的 catch (Exception) 捕获，重新包装成 IllegalStateException("无法运行 Claude Code…")。目前 Panel 侧靠 isCurrentClaudeTurn 世代号（:654）屏蔽了这个错误不显示给用户，但服务层语义已经错了——一旦有其他调用方或改动世代逻辑就会暴露。应在 catch 前先放行 CancellationException。- WorkspaceSnapshot.java:152 附近调用 git 子进程的 waitFor() 无超时，git 卡死会永久阻塞捕获线程。- NotificationSoundPlayer.java:49-60, 89-99 打开音频 line/clip 后异常路径不关闭，缺少 try-finally。- CodexAppServerService 的 startBlocking 对 process/writer/connected 的写入不在 lifecycleLock 内（:108-109, 128），与 stopProcess/restart 存在竞态窗口。- 重复代码：decode(byte[]) 在 UnifiedDiffBuilder:82-92 与 UnifiedDiffParser:128-138 完全相同；可执行文件探测在 CodexExecutableResolver 与 ClaudeCodeService:286-309 各一份；SKIPPED_DIRECTORIES 在 WorkspaceSnapshot:26-29 与 ProjectFileSearch:16-18 两份且内容不一致；JSON 取值辅助在 Panel 与 ClaudeCodeService:337-358 各一份。
6. 设置状态是全 public 可变字段
CodexSettingsState.StateData（:19-59）及 ProviderProfile/PromptPreset/AgentProfile 全部字段 public 可变，getState() 直接返回内部引用，Panel 里到处 settings.xxx = value 直写（:758, :797, :1687-1693, :1704-1721, :1823-1825）。normalizeProviders() 在每次读取时执行有副作用的清洗（:133, 146），读操作不幂等且非线程安全。约束（如 wireApi 只能是 responses/chat、serviceTier 只能是 fast/standard）散落在 Panel 的写入点上，而不是在设置类内部。

7. 工程卫生
build.gradle.kts:27-29 同时引入 JUnit 5 和 JUnit 4 运行时，后者多余。- CI 只有发布工作流（.github/workflows/gradle-publish.yml），没有 push/PR 上的 build + test + verifyPlugin。- 前端无构建工具、无 lint、无类型检查。- 测试覆盖空白：ClaudeCodeService 零测试、UnifiedDiffBuilder 无独立测试、CodexAppServerService 协议分发无测试、WorkspaceChangeService 的 accept/revert 无测试、Panel 中的全部业务逻辑无法测试（因为都是私有方法且依赖 JCEF）。- app.css 亮色主题不是纯令牌驱动：:root 定义了变量，但 [data-theme="light"] 段（app.css:627-785，约 160 行）大量逐组件覆盖，根因是暗色样式里用了硬编码色值（#4a90e2 :199、#252526 :786、#2d2d2d :790）。另有 .integration-row/.inventory-*/.dependency-list 等疑似死样式。
三、重构方向（按建议执行顺序）
阶段 0：立即修复的正确性问题（半天）
不动结构，先消除已知缺陷，为后续重构提供安全网：

ClaudeCodeService:167 放行 CancellationException。2. WorkspaceSnapshot git 调用加超时。3. NotificationSoundPlayer 加 try-finally。4. CodexAppServerService.startBlocking 的状态写入纳入锁。5. 移除 JUnit 4 依赖；增加 CI 构建+测试工作流。
阶段 1：协议类型化（1–2 天，收益最高）
这是后续所有拆分的地基。- Java 侧新建 com.codexgui.bridge 包：InboundAction（前端→Java）与 OutboundEvent（Java→前端）两个枚举或 sealed interface，payload 用 record。handleBridgeMessage 的 switch 改为按枚举分发。- 前端引入 TypeScript（用 esbuild 打包，产物仍注入 index.html 占位符，Java 侧集成方式不变），定义与 Java 对应的 discriminated union。- 用一份 protocol.md 或生成脚本作为单一来源，ui-preview.html 的模拟桥从同一份类型构造事件。- 服务层的 CompletableFuture<JsonObject> 改为返回领域 record（Thread、Turn、ModelInfo、SkillInfo、McpServerInfo），JSON 解析下沉到服务层，UI 不再 getAsJsonObject。

阶段 2：拆解 CodexToolWindowPanel（3–5 天）
按上面第 1 节的表格切分。关键设计决策：

会话模型：新建 ConversationSession 类，持有 threadId、turnId、provider、transcript、attachments、queuedInputs、usage 等全部状态。SessionRegistry 管理 Map<String, ConversationSession> 和活动会话 ID。所有方法显式接收 session 参数，删除全部 current* 镜像字段和 activateSession() 调用。event(type) 改为 event(type, sessionId)。

Provider 抽象：

Code

interface AiProvider {
  CompletableFuture<Void> startTurn(ConversationSession session, TurnRequest request, TurnListener listener);
  void interrupt(ConversationSession session);
  CompletableFuture<Boolean> isAvailable();
}
CodexProvider 和 ClaudeProvider 各实现一份，dispatchInput/dispatchClaudeInput 的差异全部收进实现类。Codex 独有能力放在 CodexProvider 自己的公开方法上，UI 通过 instanceof 或能力接口访问。ClaudeCodeService 注册为 IDE Service。

从 Panel 抽出的独立类（建议目录 com.codexgui.feature.*）：

TurnDispatcher：发送、排队、startNextQueuedInput、pendingUserMessage 计数。- CodexNotificationHandler：onNotification / renderStartedItem / renderCompletedItem / 命令输出合并。- ApprovalHandler：onServerRequest、autoApprove、showTimedDialog、respondDecision。- ProviderProfileManager：供应商 CRUD、校验、applyProviderRuntimeChange。- SkillsManager、McpManager、PromptAgentManager。- ComposerDropHandler：拖放坐标、项目树 DnD、droppedPath。- IdeNavigator：openFileLocation、normalizeReportedFilePath、openChange（Diff 查看器）。- AttentionNotifier：系统通知、提示音、焦点判断。- JsonValues：array/string/integer/bool/... 工具，与 ClaudeCodeService 那份合并。
拆完后 CodexToolWindowPanel 只剩：创建 JCEF、注入桥、把 action 路由到对应 manager、把 manager 的输出事件发给页面。目标 300 行以内。

阶段 3：前端重构（2–4 天）
拆模块：state、protocol（一次 JSON.parse，显式路由表 { [type]: handler } 取代 7 层猴补丁）、views/*（chat、history、settings、dialogs 各一文件）、prompt-editor（app.js:441-562 富文本那一大块）、dnd、markdown。- 渲染策略：appendMessage / replaceMessage 改为定点更新对应消息节点，不再全页重绘；页签切换和设置页可以继续全量渲染。若愿意引入依赖，Preact（约 10 KB）能一次性解决状态到 DOM 的映射和监听器管理，比手写 diff 更稳。- 长会话消息虚拟化或按窗口裁剪。- 消除页签 tabStateKeys 快照机制：与后端对齐，每个页签一个独立 state 对象。- Markdown 渲染改为受控 DOM 构建或引入 DOMPurify，不再依赖"每个分支都记得 esc()"的隐式契约。
阶段 4：设置与样式卫生（1–2 天）
CodexSettingsState：字段私有化 + 带校验的 setter，或至少把 wireApi/serviceTier/approvalPolicy/sandboxMode 等改为枚举；normalizeProviders() 改为写入时归一化；加 schema 版本号。- 协议方法名、策略字面量集中为常量/枚举。- app.css：所有组件色值改为变量引用，删掉 [data-theme="light"] 段的逐组件覆盖；跑一次覆盖率清死样式。- 合并重复的 decode、SKIPPED_DIRECTORIES、可执行文件探测。
阶段 5：补测试（穿插进行）
每拆出一个类就补一组测试。最高价值目标：ClaudeCodeService（stream-json 解析、permissionMode/effort 映射、环境变量注入）、UnifiedDiffBuilder、CodexAppServerService.handleProtocolMessage、TurnDispatcher 的排队逻辑、SessionRegistry 的会话路由（这正是历史上串台 bug 的位置）。