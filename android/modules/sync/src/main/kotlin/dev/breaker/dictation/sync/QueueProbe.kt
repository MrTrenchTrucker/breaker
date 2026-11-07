package dev.breaker.dictation.sync

/** The two reads of the queue storage that the pull applier needs. */
internal interface QueueProbe {

    /** True when a row with [clientId] is in the queue table. Parked rows count. */
    fun isQueued(clientId: String): Boolean

    /** The client ids of every queue row, parked or not, oldest first (by seq). */
    fun queuedIds(): List<String>
}
