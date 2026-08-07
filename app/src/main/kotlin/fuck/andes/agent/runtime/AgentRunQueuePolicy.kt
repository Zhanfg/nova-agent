package fuck.andes.agent.runtime

/**
 * Compatibility policy for the current single-active-session runtime.
 *
 * This policy is intentionally small. It keeps the legacy runtime correct while
 * [AgentTaskScheduler] becomes the multi-agent scheduler used by the final architecture.
 */
internal object AgentRunQueuePolicy {
    fun shouldQueue(
        hasActiveNonTerminalSession: Boolean,
        queuedCount: Int,
    ): Boolean = hasActiveNonTerminalSession || queuedCount > 0

    fun <T> removeQueuedByRunId(
        queue: ArrayDeque<T>,
        runId: String,
        runIdOf: (T) -> String,
    ): T? {
        val target = queue.firstOrNull { runIdOf(it) == runId } ?: return null
        queue.remove(target)
        return target
    }
}
