package fuck.andes.agent.workspace

/** Small seam around Git execution so workspace logic is deterministic and unit-testable. */
internal fun interface AgentGitExecutor {
    fun execute(command: String, cwd: String, timeoutMs: Int): Result

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false,
    ) {
        val ok: Boolean
            get() = exitCode == 0 && !timedOut
    }
}

internal object AgentShellQuote {
    fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}
