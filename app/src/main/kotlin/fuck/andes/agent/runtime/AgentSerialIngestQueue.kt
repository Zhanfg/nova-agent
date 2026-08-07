package fuck.andes.agent.runtime

/**
 * Serializes expensive request ingestion (for example image descriptor materialization) without
 * dropping newer requests. The active item is outside the waiting-capacity budget; [maxWaiting]
 * therefore means exactly how many callers may wait behind the item currently being ingested.
 */
internal class AgentSerialIngestQueue<T : Any>(
    private val maxWaiting: Int,
) {
    init {
        require(maxWaiting >= 0) { "maxWaiting must be non-negative" }
    }

    private var activeItem: T? = null
    private val waiting = ArrayDeque<T>()

    sealed interface SubmitResult {
        data object StartNow : SubmitResult
        data class Queued(val position: Int) : SubmitResult
        data object RejectedFull : SubmitResult
    }

    sealed interface RemoveResult<out T> {
        data class RemovedActive<T>(
            val item: T,
            val next: T?,
        ) : RemoveResult<T>

        data class RemovedWaiting<T>(val item: T) : RemoveResult<T>
        data object NotFound : RemoveResult<Nothing>
    }

    fun submit(item: T): SubmitResult {
        if (activeItem == null) {
            activeItem = item
            return SubmitResult.StartNow
        }
        if (waiting.size >= maxWaiting) return SubmitResult.RejectedFull
        waiting.addLast(item)
        return SubmitResult.Queued(waiting.size)
    }

    fun active(): T? = activeItem

    /**
     * Completes only the exact active object. A stale worker cannot advance or corrupt the queue.
     * Returns the next item that should start ingestion, if any.
     */
    fun complete(item: T): T? {
        if (activeItem !== item) return null
        activeItem = null
        return promoteNext()
    }

    /**
     * Removes exactly one matching item without disturbing unrelated requests. If the active item
     * is removed, the next waiter is promoted immediately and returned to the caller so ingestion
     * can continue without a gap.
     */
    fun removeFirst(predicate: (T) -> Boolean): RemoveResult<T> {
        activeItem?.takeIf(predicate)?.let { active ->
            activeItem = null
            return RemoveResult.RemovedActive(
                item = active,
                next = promoteNext(),
            )
        }

        val queued = waiting.firstOrNull(predicate) ?: return RemoveResult.NotFound
        waiting.remove(queued)
        return RemoveResult.RemovedWaiting(queued)
    }

    /** Returns and clears active + queued items in arrival order. */
    fun drain(): List<T> = buildList {
        activeItem?.let(::add)
        addAll(waiting)
        activeItem = null
        waiting.clear()
    }

    fun waitingCount(): Int = waiting.size

    private fun promoteNext(): T? {
        if (waiting.isEmpty()) return null
        return waiting.removeFirst().also { next -> activeItem = next }
    }
}
