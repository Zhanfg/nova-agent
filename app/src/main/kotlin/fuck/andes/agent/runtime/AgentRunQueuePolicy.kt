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

    /**
     * A runId must identify at most one in-flight request across ingest, active execution and the
     * materialized run queue. Without this invariant precise cancel/result routing becomes
     * ambiguous even if each individual queue is otherwise FIFO.
     */
    fun isDuplicateRunId(
        runId: String,
        activeRunId: String?,
        ingestContainsRunId: Boolean,
        queuedContainsRunId: Boolean,
    ): Boolean =
        runId.isNotBlank() && (
            activeRunId == runId ||
                ingestContainsRunId ||
                queuedContainsRunId
            )

    fun <T> removeQueuedByRunId(
        queue: ArrayDeque<T>,
        runId: String,
        runIdOf: (T) -> String,
    ): T? {
        val target = queue.firstOrNull { runIdOf(it) == runId } ?: return null
        queue.remove(target)
        return target
    }

    /**
     * Service shutdown is terminal for every queued request. Return each item exactly once while
     * clearing the source queue so callers can deliver an explicit failure instead of making
     * clients wait for their transport timeout.
     */
    fun <T> drain(queue: ArrayDeque<T>): List<T> = buildList(queue.size) {
        while (queue.isNotEmpty()) {
            add(queue.removeFirst())
        }
    }
}
