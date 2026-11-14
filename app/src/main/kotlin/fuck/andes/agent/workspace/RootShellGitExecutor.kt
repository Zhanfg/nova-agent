package fuck.andes.agent.workspace

import fuck.andes.agent.terminal.RootShellTerminalController

/** Executes Git inside Nova's existing Alpine/Linux terminal environment. */
internal class RootShellGitExecutor(
    private val terminal: RootShellTerminalController,
) : AgentGitExecutor {
    override fun execute(
        command: String,
        cwd: String,
        timeoutMs: Int,
    ): AgentGitExecutor.Result {
        val result = runCatching {
            terminal.runStructuredCommand(
                command = command,
                cwd = cwd,
                timeoutMs = timeoutMs,
                environment = "linux",
            )
        }.getOrElse { failure ->
            return AgentGitExecutor.Result(
                exitCode = -1,
                stdout = "",
                stderr = failure.message ?: failure.javaClass.simpleName,
            )
        }
        return AgentGitExecutor.Result(
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            timedOut = result.timedOut,
            stdoutTruncated = result.stdoutTruncated,
            stderrTruncated = result.stderrTruncated,
        )
    }
}
