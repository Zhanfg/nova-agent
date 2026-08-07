# Nova Mobile Codex Parity Contract

> Nova 的目标不是“像 Codex 的聊天界面”，而是在 Android 上提供与桌面 Codex 等价的工程工作流，并叠加手机系统级能力。
>
> 本文是功能验收合同。任何标为 **Required** 的桌面 Codex 能力，在 Nova 宣称达到 Mobile Codex 目标前都必须具备可运行实现、自动化测试和至少一轮真机验证。

## 0. 对齐基线

- 基线日期：**2026-08-07**。
- 对齐对象：OpenAI 当前公开的 Codex 桌面/远程产品能力，而不是早期仅 CLI 的能力集合。
- 当前公开能力基线包括：多 Agent 并行、隔离 worktree、Skills、Automations、长任务/后台任务、Goal mode、浏览器与精确 annotations、diff review/comment、远程继续/手机 Remote、Voice 协调以及可扩展 Plugins/Apps。
- 每次准备发布 Nova 的大版本前必须重新核对 Codex 官方能力；若桌面 Codex 新增工程能力，本表新增 Required 项，不能以“本文旧版本没写”为理由跳过。

参考基线：

- OpenAI — Introducing the Codex app
- OpenAI — Codex product page
- OpenAI Help — ChatGPT release notes / Codex updates
- OpenAI — Work with Codex from anywhere
- OpenAI Help — ChatGPT Work and Codex
- OpenAI Help — Plugins in Codex

## 1. 核心原则

1. **功能等价优先于 UI 仿制**：界面可以按 Android/M3DE 重新组织，但用户能完成的工程任务不能缩水。
2. **多 Agent 是一等能力**：不得长期依赖单一 `activeSession + global FIFO` 作为最终架构。
3. **隔离后并行**：同一仓库的写任务依靠 workspace/worktree 隔离并行；只有真正争用同一独占资源的任务才排队。
4. **可恢复**：长任务在 UI 切换、Activity 重建和进程重启后必须能恢复状态；可恢复信息要持久化，不能只存在内存。
5. **可审查**：代码修改必须可查看 diff、逐文件/逐 hunk 审查、评论、回滚，然后再提交。
6. **可控执行**：暂停、继续、追加指令、精确取消、权限批准、敏感操作确认都必须按 `runId`/task 精确作用。
7. **Android 是超集**：Accessibility、屏幕、通知、系统设置、Root/Xposed 等手机能力是桌面 Codex 之上的扩展，不得拿这些能力替代缺失的工程能力。

## 2. 桌面 Codex 对齐矩阵

| 能力域 | Required | Nova 当前基线 | 验收标准 |
| --- | --- | --- | --- |
| 项目/本地文件夹 | Yes | Partial | 可打开多个本地项目，保存项目元数据与最近列表 |
| 文件读取/搜索/修改 | Yes | Present | Agent 可读、搜、创建、修改、删除，并产生可审查变更集 |
| 本地终端 | Yes | Present | 长命令、交互式进程、取消、超时、日志、工作目录均可控 |
| Linux/dev 环境 | Yes | Present | Alpine/本地 shell 可供 Agent 与用户复用 |
| Git 状态与 diff | Yes | Missing/Partial | status/diff/log/branch 可视化，Agent 修改形成统一 change set |
| stage/commit/branch | Yes | Missing/Partial | 可选择文件/hunk，创建分支与提交，失败可回滚 |
| Worktree/隔离工作区 | Yes | Missing | 同仓库多任务可在隔离工作区并行，合并/丢弃安全 |
| 多 Agent 并行 | Yes | Missing | 至少多个独立 task/session 并行运行，独立上下文、日志、取消与资源占用 |
| 长任务/后台执行 | Yes | Partial | App 退后台后任务继续；状态持久化；系统回收后可恢复或明确失败 |
| 任务队列/精确取消 | Yes | Present (single-runtime baseline); multi-agent pending | 同资源严格 FIFO；取消一个 `runId` 不影响其他任务；不同资源可并行 |
| Pause/Resume/Steer | Yes | Present | 对指定 task 暂停、继续、追加指令，不串任务 |
| Goal mode | Yes | Missing | 用户定义 outcome + success criteria；Agent 持续验证直至成功、阻塞或预算终止 |
| Skills | Yes | Present | 安装、更新、禁用、资源读取、冲突恢复、项目/用户作用域 |
| AGENTS.md/项目指令 | Yes | Missing | 按目录层级加载项目说明并在子目录正确覆盖/继承 |
| Automations | Yes | Missing | 一次性、周期、条件触发；后台执行；结果通知；可暂停/删除 |
| Browser | Yes | Present | 多 tab/session、DOM/截图、JS 上下文、导航与 Agent 工具 |
| Browser annotations | Yes | Missing/Partial | 用户可在页面元素/区域上做精确批注并作为 Agent 上下文 |
| 图片/视觉上下文 | Yes | Present | 附件、截图、App 当前画面与视觉模型路由均可用 |
| Diff review/inline comments | Yes | Missing | 在 diff 上逐行/逐 hunk 评论，评论可直接 steer Agent |
| 编辑器/外部打开 | Yes | Mobile equivalent required | Android 上提供文件/代码查看器，并能调用已安装编辑器或导出工作区 |
| Cloud/remote environment | Yes | Missing | 可将任务交给远程环境并同步状态、结果和 diff |
| Remote continuation | Yes | Missing | 手机可查看/继续其他端正在运行的 Codex/Nova task；Nova task 也可被其他端接管 |
| Voice coordination | Yes | Partial | 语音可创建任务、询问进度、steer、批准/拒绝；不是单纯语音转文字 |
| Plugins/apps/connectors | Yes | Missing/Partial | 可安装工作流能力，并以显式权限访问外部服务/数据 |
| Model/reasoning selection | Yes | Present | 每 task 可配置模型、reasoning、provider，并持久化 |
| Permission/sandbox policy | Yes | Partial | workspace、network、root、device UI、敏感数据分别授权；最小权限 |
| Progress/trace/logs | Yes | Present/Partial | task 级事件、工具调用、日志、耗时和资源状态可追踪 |
| Usage/cost visibility | Yes | Partial | task/session 级 token/模型使用统计，可设预算与终止条件 |
| History/recovery | Yes | Present/Partial | 项目与 task 历史可恢复，崩溃后不产生幽灵运行状态 |

## 3. Nova 特有超集能力

以下不是 Codex 桌面 parity 的替代项，而是 Nova 的 Android 超集：

- Accessibility GUI 操作与结构化 UI 树。
- 当前屏幕截图、视觉定位、手势与输入。
- 前台 App/窗口上下文。
- 通知历史、设备状态、位置等明确授权的系统上下文。
- Android Root shell 与系统控制。
- Xposed/系统助手入口（Breeno / XiaoAI / Google 等）。
- 系统级悬浮任务状态、暂停、继续、追加指令。

## 4. 多 Agent 调度模型

最终调度器采用 **resource-aware scheduler**，而不是全局 FIFO。

### 资源通道

- `DEVICE_UI`：全局独占。会操作当前手机前台 UI 的 task 同时只能有一个。
- `WORKSPACE_WRITE:<workspaceId>`：同一未隔离工作区的写任务互斥；不同 worktree/workspace 可并行。
- `BROWSER:<sessionId>`：同一 browser session 串行；不同 session 可并行。
- `TERMINAL:<sessionId>`：同一交互终端串行；独立终端可并行。
- `ROOT_SYSTEM`：高风险系统变更独占，并要求更严格批准。

### 公平性

- 同一资源的等待 task 保持 FIFO。
- 被某资源阻塞的旧 task 不应阻止完全无资源冲突的新 task 启动。
- 后来的 task 不得越过更早、且与其争用同一资源的 task。
- cancel 必须精确到 `runId`；取消排队 task 不清空队列。
- task 完成/取消/崩溃后必须释放其所有资源租约。

## 5. Workspace / Worktree 合同

每个工程 task 必须绑定 `workspaceId`。Git 仓库任务还要记录：

- repository root
- base ref/SHA
- branch
- worktree path（若隔离）
- dirty state 基线
- task 产生的 change set

并行写同一仓库时默认创建独立 worktree；只有显式选择“共享当前工作区”时才允许串行写原工作区。

## 6. Goal mode 合同

Goal task 至少包含：

- `goal`：最终结果。
- `successCriteria[]`：可验证完成条件。
- `constraints[]`：禁止或必须遵守的约束。
- `verificationPlan`：测试、构建、截图、diff、命令或人工确认。
- `budget`：时间、step、token/费用或其他上限。

Agent 不能因为“已经写了代码”就自行判定完成；必须执行验证并将证据关联到 criteria。

## 7. Automations 合同

Automations 至少支持：

- one-shot schedule
- recurring schedule
- condition watch
- enable/disable/delete
- task template + workspace binding
- 后台运行与通知
- 失败重试与重复执行防护
- 权限在创建时冻结/显式升级，不能后台静默获得更高权限

## 8. Review 合同

代码 task 完成前必须能生成统一 review surface：

- changed files
- unified/side-by-side diff
- hunk 选择
- inline comment/annotation
- accept/revert file or hunk
- run tests/lint/build from review
- commit only accepted changes

用户评论必须可以直接转为同一 task 的 steer 指令，而不是创建失去上下文的新会话。

## 9. 远程与跨设备合同

Remote task 需要稳定 task identity，不把 UI connection 当作任务本身：

- `taskId/runId` 全局唯一。
- 本机、远程 runner、其他客户端均可订阅事件流。
- 断线后可从 sequence/checkpoint 恢复。
- 远程结果携带 workspace/base SHA/diff provenance。
- 手机端能够 approve/reject/steer/cancel 正在其他设备执行的 task。

## 10. 完成定义

Nova 只有同时满足以下条件才能宣称达到“Mobile Codex”目标：

1. 本文所有 **Required** 项不再是 Missing。
2. 每个能力域存在自动测试；涉及 Android 系统交互的能力另有真机用例。
3. 多 Agent 并行不会破坏工作区，也不会因全局队列互相阻塞。
4. 代码变更有 diff/review/revert/commit 完整闭环。
5. 长任务、后台任务和 Automation 具备持久化恢复。
6. Root、设备 UI、网络、外部服务等权限边界经过安全审查。
7. 至少完成三轮 code review：局部实现、跨模块一致性、端到端运行与恢复。
