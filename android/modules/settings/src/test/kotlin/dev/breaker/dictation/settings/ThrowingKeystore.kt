package dev.breaker.dictation.settings

/**
 * TEST-ONLY [Keystore] that can be made to fail. Not shipped: this file lives in `src/test`,
 * and no production class implements [Keystore].
 *
 * Each call to [setActiveRef] takes the next failure queued by [failNextSet], in order. A
 * failure queued with `changeFirst = true` stores the new reference and then throws, as a port
 * that changed its state and then failed would. A failure queued with `changeFirst = false`
 * throws and leaves the reference as it was. With nothing queued, the call succeeds.
 *
 * [activeRefFailsOnce] fails only the next read, so a later read answers again.
 *
 * [callLog] holds every call in order. [lockHeldAtSet] holds, for each [setActiveRef] call,
 * whether the process-wide save lock was held at that moment.
 */
class ThrowingKeystore(initialRef: String? = null) : Keystore {
    private var active: String? = initialRef
    private val queuedFailures = mutableListOf<Pair<Throwable, Boolean>>()

    /** Every call made on the port, in order, as `setActiveRef(<ref>)` or `activeRef()`. */
    val callLog: MutableList<String> = mutableListOf()

    /** For each [setActiveRef] call, whether the process-wide save lock was held. */
    val lockHeldAtSet: MutableList<Boolean> = mutableListOf()

    /** When set, [activeRef] logs the call and throws this instead of answering. */
    var activeRefFailure: Throwable? = null

    /** When set, the next [activeRef] call logs itself, clears this and throws it; later reads answer. */
    var activeRefFailsOnce: Throwable? = null

    /** Queues one failure for a later [setActiveRef] call to take. */
    fun failNextSet(failure: Throwable, changeFirst: Boolean) {
        queuedFailures += Pair(failure, changeFirst)
    }

    override fun setActiveRef(ref: String?) {
        callLog += "setActiveRef($ref)"
        lockHeldAtSet += Thread.holdsLock(AtomicFileReplace.lock)
        if (queuedFailures.isEmpty()) {
            active = ref
            return
        }
        val (failure, changeFirst) = queuedFailures.removeAt(0)
        if (changeFirst) active = ref
        throw failure
    }

    override fun activeRef(): String? {
        callLog += "activeRef()"
        activeRefFailsOnce?.let {
            activeRefFailsOnce = null
            throw it
        }
        activeRefFailure?.let { throw it }
        return active
    }
}
