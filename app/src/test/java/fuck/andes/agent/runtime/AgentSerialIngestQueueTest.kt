package fuck.andes.agent.runtime

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
