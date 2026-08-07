from pathlib import Path

terminal_path = Path('app/src/main/kotlin/fuck/andes/agent/terminal/RootShellTerminalController.kt')
text = terminal_path.read_text()
old_const = """        const val MAX_OUTPUT_CHARS = 16_000
        const val MAX_READ_BYTES = 256 * 1024
"""
new_const = """        const val MAX_OUTPUT_CHARS = 16_000
        const val MAX_STRUCTURED_OUTPUT_CHARS = 512 * 1024
        const val MAX_READ_BYTES = 256 * 1024
"""
if old_const not in text:
    raise SystemExit('terminal constant anchor not found')
text = text.replace(old_const, new_const, 1)

anchor = """    fun terminalAction(
"""
addition = """    internal data class StructuredCommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        val stdoutTruncated: Boolean,
        val stderrTruncated: Boolean,
    ) {
        val complete: Boolean
            get() = !stdoutTruncated && !stderrTruncated
    }

    /**
     * Internal bounded channel for subsystems such as Git review that need substantially more
     * output than the model-facing terminal JSON contract. It never returns unbounded process
     * output and does not change the public terminal tool limits.
     */
    internal fun runStructuredCommand(
        command: String,
        cwd: String?,
        timeoutMs: Int,
        environment: String = TerminalEnvironment.LINUX.wireName,
    ): StructuredCommandResult {
        val trimmed = command.trim()
        require(trimmed.isNotBlank()) { "command 不能为空" }
        require(trimmed.length <= MAX_COMMAND_CHARS) { "command 过长：${trimmed.length}" }
        val normalizedEnvironment = normalizeEnvironment(environment)
        val identity = "root"
        environmentPreflight(identity, normalizedEnvironment)?.let { errorPayload ->
            val json = runCatching { JSONObject(errorPayload) }.getOrNull()
            return StructuredCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = json?.optString("message").orEmpty().ifBlank { "environment unavailable" },
                timedOut = false,
                stdoutTruncated = false,
                stderrTruncated = false,
            )
        }
        val safeCwd = normalizeCwd(cwd)
        val timeoutSeconds = ((timeoutMs.coerceIn(1, MAX_TIMEOUT_SECONDS * 1000) + 999) / 1000)
            .coerceIn(1, MAX_TIMEOUT_SECONDS)
        val setup = if (safeCwd == DEFAULT_CWD) "mkdir -p ${shellQuote(DEFAULT_CWD)} && " else ""
        val fullCommand = "${setup}cd ${shellQuote(safeCwd)} && export TERM=dumb NO_COLOR=1 && $trimmed"
        val result = runText(
            identity = identity,
            command = fullCommand,
            timeoutSeconds = timeoutSeconds.toLong(),
            environment = normalizedEnvironment,
        )
        val stdoutTruncated = result.output.length > MAX_STRUCTURED_OUTPUT_CHARS
        val stderrTruncated = result.stderr.length > MAX_STRUCTURED_OUTPUT_CHARS
        return StructuredCommandResult(
            exitCode = result.exitCode,
            stdout = result.output.take(MAX_STRUCTURED_OUTPUT_CHARS),
            stderr = result.stderr.take(MAX_STRUCTURED_OUTPUT_CHARS),
            timedOut = result.exitCode == -2,
            stdoutTruncated = stdoutTruncated,
            stderrTruncated = stderrTruncated,
        )
    }

"""
if anchor not in text:
    raise SystemExit('terminal action anchor not found')
text = text.replace(anchor, addition + anchor, 1)
terminal_path.write_text(text)

git_path = Path('app/src/main/kotlin/fuck/andes/agent/workspace/RootShellGitExecutor.kt')
git_path.write_text("""package fuck.andes.agent.workspace

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
""")
