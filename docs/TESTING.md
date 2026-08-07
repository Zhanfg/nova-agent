# Nova Test Lifecycle Notes

Nova 的单元测试必须验证目标逻辑，而不是意外启动与测试无关的长期后台工作。

## AgentAppState

`AgentAppState` 生产环境默认启动模型能力 Flow 观察与 Runtime 结果恢复。针对纯状态/持久化逻辑的 JVM/Robolectric 测试应使用 `startBackgroundWork = false`，避免为了验证本地状态变换而创建无限 Flow collector 或 Runtime IPC 恢复任务。

如果测试显式创建 `CoroutineScope`，结束时必须等待该 scope 的根 `Job` 完成取消，而不只是发出异步 `cancel()` 信号。Room 测试同时调用 `FuckAndesDatabase.closeForTests()` 并清理测试数据库。

## CI 分片

`Nova Verification` 将模型、Runtime、数据/Skills、平台、浏览器、图片、UI 状态、UI 策略、终端与 Lint 分开执行。分片的目的不是规避失败，而是让资源泄漏、超时和测试失败能够定位到具体能力域。

不得通过单纯提高 timeout、忽略失败或删除验证断言来解决挂起测试；应先修复协程、数据库、进程、文件描述符或其他资源的生命周期。
