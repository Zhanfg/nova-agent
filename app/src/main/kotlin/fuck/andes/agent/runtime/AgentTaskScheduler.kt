package fuck.andes.agent.runtime

/**
 * A resource-aware scheduler for Nova's Codex-style multi-agent runtime.
 *
 * The current AgentRuntimeService still owns the execution lifecycle. This class is deliberately
 * platform-agnostic so scheduling semantics can be tested before the service migrates from one
 * global active session to multiple concurrently running sessions.
 */
internal class AgentTaskScheduler {
    private val running = linkedMapOf<String, TaskSpec>()
    private val waiting = ArrayDeque<TaskSpec>()

    data class TaskSpec(
        val runId: String,
        val resources: Set<Resource>,
    )

    sealed interface Resource {
        data object DeviceUi : Resource
        data object RootSystem : Resource
        data class WorkspaceWrite(val workspaceId: String) : Resource
        data class BrowserSession(val sessionId: String) : Resource
        data class TerminalSession(val sessionId: String) : Resource
    }

    sealed interface SubmitResult {
        data object Started : SubmitResult
        data class Queued(val position: Int) : SubmitResult
        data object DuplicateRunId : SubmitResult
    }

    data class ReleaseResult(
        val released: Boolean,
        val newlyStarted: List<TaskSpec>,
    )

    fun submit(task: TaskSpec): SubmitResult {
        if (running.containsKey(task.runId) || waiting.any { it.runId == task.runId }) {
            return SubmitResult.DuplicateRunId
        }

        if (canStartNow(task, earlierBlockedResources = emptySet())) {
            running[task.runId] = task
            return SubmitResult.Started
        }

        waiting.addLast(task)
        return SubmitResult.Queued(waiting.size)
    }

    /**
     * Remove exactly one queued task. Running tasks are cancelled by their execution owner so that
     * resource cleanup happens in the same lifecycle that acquired the resources.
     */
    fun cancelQueued(runId: String): TaskSpec? {
        val target = waiting.firstOrNull { it.runId == runId } ?: return null
        waiting.remove(target)
        return target
    }

    /**
     * Release a running task and admit as many non-conflicting waiters as possible.
     *
     * Fairness is resource-local rather than global: a blocked task reserves its resources against
     * later waiters, preventing leapfrogging on the same resource while allowing unrelated tasks to
     * make progress.
     */
    fun release(runId: String): ReleaseResult {
        val released = running.remove(runId) != null
        if (!released) return ReleaseResult(released = false, newlyStarted = emptyList())

        val newlyStarted = mutableListOf<TaskSpec>()
        val stillWaiting = ArrayDeque<TaskSpec>()
        val earlierBlockedResources = linkedSetOf<Resource>()

        while (waiting.isNotEmpty()) {
            val candidate = waiting.removeFirst()
            if (canStartNow(candidate, earlierBlockedResources)) {
                running[candidate.runId] = candidate
                newlyStarted += candidate
            } else {
                stillWaiting.addLast(candidate)
                earlierBlockedResources += candidate.resources
            }
        }
        waiting.addAll(stillWaiting)

        return ReleaseResult(released = true, newlyStarted = newlyStarted)
    }

    fun runningTasks(): List<TaskSpec> = running.values.toList()

    fun queuedTasks(): List<TaskSpec> = waiting.toList()

    private fun canStartNow(
        task: TaskSpec,
        earlierBlockedResources: Set<Resource>,
    ): Boolean {
        if (task.resources.any { it in earlierBlockedResources }) return false
        return running.values.none { runningTask -> resourcesConflict(task, runningTask) }
    }

    private fun resourcesConflict(left: TaskSpec, right: TaskSpec): Boolean =
        left.resources.any { it in right.resources }
}
