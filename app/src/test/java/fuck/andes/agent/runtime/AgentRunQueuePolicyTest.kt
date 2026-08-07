package fuck.andes.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunQueuePolicyTest {
    private data class Queued(val runId: String)

    @Test
    fun newRunQueuesWheneverOlderWaiterExists() {
        assertTrue(
            AgentRunQueuePolicy.shouldQueue(
                hasActiveNonTerminalSession = false,
                queuedCount = 1,
            )
        )
    }

    @Test
    fun idleRuntimeWithoutWaitersCanStartImmediately() {
        assertFalse(
            AgentRunQueuePolicy.shouldQueue(
                hasActiveNonTerminalSession = false,
                queuedCount = 0,
            )
        )
    }

    @Test
    fun activeSessionForcesQueueing() {
        assertTrue(
            AgentRunQueuePolicy.shouldQueue(
                hasActiveNonTerminalSession = true,
                queuedCount = 0,
            )
        )
    }

    @Test
    fun duplicateRunIdIsDetectedAcrossEveryInFlightStage() {
        assertTrue(
            AgentRunQueuePolicy.isDuplicateRunId(
                runId = "same",
                activeRunId = "same",
                ingestContainsRunId = false,
                queuedContainsRunId = false,
            )
        )
        assertTrue(
            AgentRunQueuePolicy.isDuplicateRunId(
                runId = "same",
                activeRunId = "other",
                ingestContainsRunId = true,
                queuedContainsRunId = false,
            )
        )
        assertTrue(
            AgentRunQueuePolicy.isDuplicateRunId(
                runId = "same",
                activeRunId = null,
                ingestContainsRunId = false,
                queuedContainsRunId = true,
            )
        )
        assertFalse(
            AgentRunQueuePolicy.isDuplicateRunId(
                runId = "same",
                activeRunId = "other",
                ingestContainsRunId = false,
                queuedContainsRunId = false,
            )
        )
    }

    @Test
    fun cancellingQueuedRunRemovesOnlyMatchingItemAndKeepsOrder() {
        val queue = ArrayDeque(
            listOf(
                Queued("keep-1"),
                Queued("cancel-me"),
                Queued("keep-2"),
            )
        )

        val removed = AgentRunQueuePolicy.removeQueuedByRunId(
            queue = queue,
            runId = "cancel-me",
            runIdOf = Queued::runId,
        )

        assertEquals("cancel-me", removed?.runId)
        assertEquals(listOf("keep-1", "keep-2"), queue.map { it.runId })
    }

    @Test
    fun cancellingUnknownRunLeavesQueueUntouched() {
        val queue = ArrayDeque(listOf(Queued("keep-1"), Queued("keep-2")))

        val removed = AgentRunQueuePolicy.removeQueuedByRunId(
            queue = queue,
            runId = "missing",
            runIdOf = Queued::runId,
        )

        assertNull(removed)
        assertEquals(listOf("keep-1", "keep-2"), queue.map { it.runId })
    }

    @Test
    fun shutdownDrainReturnsEveryQueuedRunInOrderAndClearsQueue() {
        val queue = ArrayDeque(
            listOf(
                Queued("run-1"),
                Queued("run-2"),
                Queued("run-3"),
            )
        )

        val drained = AgentRunQueuePolicy.drain(queue)

        assertEquals(listOf("run-1", "run-2", "run-3"), drained.map { it.runId })
        assertTrue(queue.isEmpty())
    }
}
