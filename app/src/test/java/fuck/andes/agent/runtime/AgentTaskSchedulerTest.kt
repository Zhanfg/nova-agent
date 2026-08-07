package fuck.andes.agent.runtime

import fuck.andes.agent.runtime.AgentTaskScheduler.Resource
import fuck.andes.agent.runtime.AgentTaskScheduler.SubmitResult
import fuck.andes.agent.runtime.AgentTaskScheduler.TaskSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskSchedulerTest {
    @Test
    fun deviceUiTasksSerializeInFifoOrder() {
        val scheduler = AgentTaskScheduler()

        assertEquals(
            SubmitResult.Started,
            scheduler.submit(TaskSpec("run-1", setOf(Resource.DeviceUi))),
        )
        assertEquals(
            SubmitResult.Queued(1),
            scheduler.submit(TaskSpec("run-2", setOf(Resource.DeviceUi))),
        )
        assertEquals(
            SubmitResult.Queued(2),
            scheduler.submit(TaskSpec("run-3", setOf(Resource.DeviceUi))),
        )

        val firstRelease = scheduler.release("run-1")
        assertEquals(listOf("run-2"), firstRelease.newlyStarted.map { it.runId })
        assertEquals(listOf("run-3"), scheduler.queuedTasks().map { it.runId })

        val secondRelease = scheduler.release("run-2")
        assertEquals(listOf("run-3"), secondRelease.newlyStarted.map { it.runId })
    }

    @Test
    fun isolatedWorkspacesCanRunInParallel() {
        val scheduler = AgentTaskScheduler()

        assertEquals(
            SubmitResult.Started,
            scheduler.submit(TaskSpec("repo-a", setOf(Resource.WorkspaceWrite("worktree-a")))),
        )
        assertEquals(
            SubmitResult.Started,
            scheduler.submit(TaskSpec("repo-b", setOf(Resource.WorkspaceWrite("worktree-b")))),
        )
        assertEquals(setOf("repo-a", "repo-b"), scheduler.runningTasks().map { it.runId }.toSet())
    }

    @Test
    fun independentBrowserAndTerminalSessionsRunConcurrently() {
        val scheduler = AgentTaskScheduler()

        assertEquals(
            SubmitResult.Started,
            scheduler.submit(TaskSpec("browser-a", setOf(Resource.BrowserSession("browser-a")))),
        )
        assertEquals(
            SubmitResult.Started,
            scheduler.submit(TaskSpec("browser-b", setOf(Resource.BrowserSession("browser-b")))),
        )
        assertEquals(
            SubmitResult.Started,
            scheduler.submit(TaskSpec("terminal-a", setOf(Resource.TerminalSession("terminal-a")))),
        )

        assertEquals(
            setOf("browser-a", "browser-b", "terminal-a"),
            scheduler.runningTasks().map { it.runId }.toSet(),
        )
    }

    @Test
    fun sameWorkspacePreservesFifoButIndependentTaskCanBypassBlockedLane() {
        val scheduler = AgentTaskScheduler()
        scheduler.submit(TaskSpec("writer-1", setOf(Resource.WorkspaceWrite("shared"))))
        scheduler.submit(TaskSpec("writer-2", setOf(Resource.WorkspaceWrite("shared"))))
        scheduler.submit(TaskSpec("ui-1", setOf(Resource.DeviceUi)))

        // ui-1 is independent and therefore starts immediately instead of waiting behind writer-2.
        assertEquals(setOf("writer-1", "ui-1"), scheduler.runningTasks().map { it.runId }.toSet())
        assertEquals(listOf("writer-2"), scheduler.queuedTasks().map { it.runId })

        scheduler.submit(TaskSpec("writer-3", setOf(Resource.WorkspaceWrite("shared"))))
        val release = scheduler.release("writer-1")

        assertEquals(listOf("writer-2"), release.newlyStarted.map { it.runId })
        assertEquals(listOf("writer-3"), scheduler.queuedTasks().map { it.runId })
    }

    @Test
    fun olderMultiResourceWaiterReservesSharedResourceAgainstNewerTask() {
        val scheduler = AgentTaskScheduler()
        scheduler.submit(TaskSpec("ui-holder", setOf(Resource.DeviceUi)))

        assertEquals(
            SubmitResult.Queued(1),
            scheduler.submit(
                TaskSpec(
                    "older",
                    setOf(Resource.DeviceUi, Resource.WorkspaceWrite("shared")),
                )
            ),
        )
        assertEquals(
            SubmitResult.Queued(2),
            scheduler.submit(TaskSpec("newer-writer", setOf(Resource.WorkspaceWrite("shared")))),
        )
        assertEquals(
            SubmitResult.Started,
            scheduler.submit(TaskSpec("independent", setOf(Resource.TerminalSession("t1")))),
        )

        val release = scheduler.release("ui-holder")
        assertEquals(listOf("older"), release.newlyStarted.map { it.runId })
        assertEquals(listOf("newer-writer"), scheduler.queuedTasks().map { it.runId })
    }

    @Test
    fun cancellingQueuedTaskOnlyRemovesMatchingRunId() {
        val scheduler = AgentTaskScheduler()
        scheduler.submit(TaskSpec("active", setOf(Resource.DeviceUi)))
        scheduler.submit(TaskSpec("keep-1", setOf(Resource.DeviceUi)))
        scheduler.submit(TaskSpec("cancel-me", setOf(Resource.DeviceUi)))
        scheduler.submit(TaskSpec("keep-2", setOf(Resource.DeviceUi)))

        val cancelled = scheduler.cancelQueued("cancel-me")

        assertEquals("cancel-me", cancelled?.runId)
        assertEquals(listOf("keep-1", "keep-2"), scheduler.queuedTasks().map { it.runId })
        assertNull(scheduler.cancelQueued("missing"))
    }

    @Test
    fun duplicateRunIdIsRejectedAcrossRunningAndQueuedTasks() {
        val scheduler = AgentTaskScheduler()
        scheduler.submit(TaskSpec("run-1", setOf(Resource.DeviceUi)))
        scheduler.submit(TaskSpec("run-2", setOf(Resource.DeviceUi)))

        assertEquals(
            SubmitResult.DuplicateRunId,
            scheduler.submit(TaskSpec("run-1", setOf(Resource.TerminalSession("t1")))),
        )
        assertEquals(
            SubmitResult.DuplicateRunId,
            scheduler.submit(TaskSpec("run-2", setOf(Resource.TerminalSession("t2")))),
        )
        assertTrue(scheduler.runningTasks().any { it.runId == "run-1" })
        assertTrue(scheduler.queuedTasks().any { it.runId == "run-2" })
    }

    @Test
    fun rootSystemResourceIsExclusiveOnlyAgainstOtherRootTasks() {
        val scheduler = AgentTaskScheduler()

        assertEquals(
            SubmitResult.Started,
            scheduler.submit(TaskSpec("root-1", setOf(Resource.RootSystem))),
        )
        assertEquals(
            SubmitResult.Queued(1),
            scheduler.submit(TaskSpec("root-2", setOf(Resource.RootSystem))),
        )
        assertEquals(
            SubmitResult.Started,
            scheduler.submit(TaskSpec("terminal", setOf(Resource.TerminalSession("isolated")))),
        )
    }
}
