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
        if (waiting.isEmpty()) return null
        return waiting.removeFirst().also { next -> activeItem = next }
    }

    /** Returns and clears active + queued items in arrival order. */
    fun drain(): List<T> = buildList {
        activeItem?.let(::add)
        addAll(waiting)
        activeItem = null
        waiting.clear()
    }

    fun waitingCount(): Int = waiting.size
}
