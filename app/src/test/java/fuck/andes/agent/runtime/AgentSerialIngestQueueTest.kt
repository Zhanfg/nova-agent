package fuck.andes.agent.runtime

import fuck.andes.agent.runtime.AgentSerialIngestQueue.RemoveResult
import fuck.andes.agent.runtime.AgentSerialIngestQueue.SubmitResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentSerialIngestQueueTest {
    private data class Request(val runId: String)

    @Test
    fun newerRequestsWaitInsteadOfReplacingActiveIngest() {
        val queue = AgentSerialIngestQueue<Request>(maxWaiting = 3)
        val first = Request("first")
        val second = Request("second")
        val third = Request("third")

        assertEquals(SubmitResult.StartNow, queue.submit(first))
        assertEquals(SubmitResult.Queued(1), queue.submit(second))
        assertEquals(SubmitResult.Queued(2), queue.submit(third))
        assertEquals(first, queue.active())

        assertEquals(second, queue.complete(first))
        assertEquals(second, queue.active())
        assertEquals(third, queue.complete(second))
        assertEquals(third, queue.active())
        assertNull(queue.complete(third))
        assertNull(queue.active())
    }

    @Test
    fun fullQueueRejectsOnlyNewestRequestWithoutMutatingExistingOrder() {
        val queue = AgentSerialIngestQueue<Request>(maxWaiting = 2)
        val first = Request("first")
        val second = Request("second")
        val third = Request("third")
        val rejected = Request("rejected")

        queue.submit(first)
        queue.submit(second)
        queue.submit(third)

        assertEquals(SubmitResult.RejectedFull, queue.submit(rejected))
        assertEquals(second, queue.complete(first))
        assertEquals(third, queue.complete(second))
        assertNull(queue.complete(third))
    }

    @Test
    fun removingWaitingRequestLeavesOtherRequestsInOrder() {
        val queue = AgentSerialIngestQueue<Request>(maxWaiting = 3)
        val active = Request("active")
        val keepFirst = Request("keep-first")
        val cancel = Request("cancel")
        val keepLast = Request("keep-last")
        queue.submit(active)
        queue.submit(keepFirst)
        queue.submit(cancel)
        queue.submit(keepLast)

        assertEquals(
            RemoveResult.RemovedWaiting(cancel),
            queue.removeFirst { it.runId == "cancel" },
        )
        assertEquals(keepFirst, queue.complete(active))
        assertEquals(keepLast, queue.complete(keepFirst))
        assertNull(queue.complete(keepLast))
    }

    @Test
    fun removingActiveRequestPromotesNextWithoutInvalidatingRemainingQueue() {
        val queue = AgentSerialIngestQueue<Request>(maxWaiting = 2)
        val active = Request("active")
        val next = Request("next")
        val last = Request("last")
        queue.submit(active)
        queue.submit(next)
        queue.submit(last)

        assertEquals(
            RemoveResult.RemovedActive(item = active, next = next),
            queue.removeFirst { it.runId == "active" },
        )
        assertEquals(next, queue.active())
        assertEquals(last, queue.complete(next))
        assertNull(queue.complete(last))
    }

    @Test
    fun staleWorkerCannotAdvanceQueue() {
        val queue = AgentSerialIngestQueue<Request>(maxWaiting = 2)
        val active = Request("active")
        val waiting = Request("waiting")
        val stale = Request("stale")

        queue.submit(active)
        queue.submit(waiting)

        assertNull(queue.complete(stale))
        assertEquals(active, queue.active())
        assertEquals(waiting, queue.complete(active))
    }

    @Test
    fun drainReturnsEverythingInArrivalOrderAndClearsState() {
        val queue = AgentSerialIngestQueue<Request>(maxWaiting = 2)
        val first = Request("first")
        val second = Request("second")
        val third = Request("third")
        queue.submit(first)
        queue.submit(second)
        queue.submit(third)

        assertEquals(listOf(first, second, third), queue.drain())
        assertNull(queue.active())
        assertEquals(0, queue.waitingCount())
        assertEquals(SubmitResult.StartNow, queue.submit(Request("after-drain")))
    }
}
