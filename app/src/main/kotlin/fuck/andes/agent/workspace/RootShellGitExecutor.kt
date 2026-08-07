package fuck.andes.agent.workspace

import fuck.andes.agent.terminal.RootShellTerminalController
import org.json.JSONObject

/** Executes Git inside Nova's existing Alpine/Linux terminal environment. */
internal class RootShellGitExecutor(
    private val terminal: RootShellTerminalController,
) : AgentGitExecutor {
    override fun execute(
        command: String,
        cwd: String,
        timeoutMs: Int,
    ): AgentGitExecutor.Result {
        val payload = terminal.terminalOpenAndExec(
            command = command,
            cwd = cwd,
            timeoutMs = timeoutMs,
            identity = "root",
            mergeStderr = false,
            environment = "linux",
        )
        return runCatching {
            val json = JSONObject(payload)
            AgentGitExecutor.Result(
                exitCode = json.optInt("exit_code", if (json.optBoolean("ok", false)) 0 else -1),
                stdout = json.optString("stdout"),
                stderr = json.optString("stderr").ifBlank {
                    json.optString("message")
                },
                timedOut = json.optBoolean("timed_out", false),
            )
        }.getOrElse { failure ->
            AgentGitExecutor.Result(
                exitCode = -1,
                stdout = "",
                stderr = "无法解析 Git terminal 结果：${failure.message ?: failure.javaClass.simpleName}",
            )
        }
    }
}
