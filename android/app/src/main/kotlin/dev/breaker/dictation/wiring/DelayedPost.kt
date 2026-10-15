package dev.breaker.dictation.wiring

/** Runs a block on the main thread after a delay, and takes a queued block back. Main thread only. */
interface DelayedPost {
    /** Queues [block] to run [delayMs] milliseconds from now, and returns the handle that takes it back. */
    fun postDelayed(delayMs: Long, block: () -> Unit): PostHandle
}

/** A block that was queued by [DelayedPost.postDelayed]. */
interface PostHandle {
    /** Takes the block back, so it does not run when its time comes. */
    fun cancel()
}
