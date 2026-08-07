# Nova Test Lifecycle Notes

Nova 的单元测试必须验证目标逻辑，而不是意外启动与测试无关的长期后台工作。

## AgentAppState

`AgentAppState` 生产环境默认启动模型能力 Flow 观察与 Runtime 结果恢复。针对纯状态/持久化逻辑的 JVM/Robolectric 测试应使用 `startBackgroundWork = false`，避免为了验证本地状态变换而创建无限 Flow collector 或 Runtime IPC 恢复任务。

纯状态模式下 `probePlatformState` 默认跟随 `startBackgroundWork` 关闭，因此不会在构造阶段同步探测 Root、无障碍、通知、Usage Access、定位等设备状态。生产默认仍为开启；如果某个测试就是为了验证平台状态，可显式设置 `probePlatformState = true`。

纯状态测试如果并不验证 Room 初始恢复，还应通过 `initialConversationSnapshot` 注入确定性的 Snapshot；生产默认值仍为 `null`，因此正常 App 启动仍会从 `AgentConversationStore` 恢复历史。这样可以避免状态测试在构造 `AgentAppState` 时隐式打开第二轮 Room 数据库。

如果测试显式创建 `CoroutineScope`，应先断言纯状态路径没有遗留 child job，再取消测试拥有的 root Job。不要用与目标逻辑无关的无限等待掩盖生命周期问题。Room 测试同时调用 `FuckAndesDatabase.closeForTests()` 并清理测试数据库。

## Runtime 队列不变量

当前单 active-session Runtime 虽然只是未来多 Agent 调度器的兼容层，但必须保持以下运输层不变量：

- 图片/附件 ingest 的 active + waiting 请求严格 FIFO，取消只移除对应 `runId`。
- 同一个 `runId` 在 ingest、active session 和 materialized run queue 中最多只能存在一次；重复请求必须在入口失败，避免取消、结果投递与持久化产生歧义。
- Service shutdown 必须对 ingest waiting、materialized run queue 与 active session 全部给出终止语义；不得让 queued 客户端只能依赖 30 分钟 transport timeout 才返回。
- stale ingest worker 不能推进已被取消后提升的新请求。
- 已有 waiter 时新请求不能插队，即使当前 active session 已进入 terminal 状态。

这些规则分别由 `AgentSerialIngestQueueTest`、`AgentRunQueuePolicyTest` 与 Runtime 测试域保护。未来迁移到 `AgentTaskScheduler` 多 session 架构时，不能以并行为理由破坏同资源 FIFO 和唯一 run identity。

## CI 分片

`Nova Verification` 将模型、Runtime、数据/Skills、平台、浏览器、图片、UI Room 持久化、UI 状态、UI 策略、终端与 Lint 分开执行。分片的目的不是规避失败，而是让资源泄漏、超时和测试失败能够定位到具体能力域。

不得通过单纯提高 timeout、忽略失败或删除验证断言来解决挂起测试；应先修复协程、数据库、进程、文件描述符或其他资源的生命周期。
