package dev.breaker.dictation.commit

/**
 * Writes text to the system clipboard.
 *
 * Returns true when the text was written and false when the clipboard is not
 * available. It may throw.
 */
internal fun interface ClipboardWriter {
    /**
     * Copy [text]. [sensitive] marks the clip so the system hides it from
     * clipboard previews; the commit always passes true, since dictated text is
     * private.
     */
    fun copy(text: String, sensitive: Boolean): Boolean
}

/** Tells the user the text was copied. It may throw. */
internal fun interface UserNotice {
    fun showCopied()
}

/**
 * Runs work on the main thread.
 *
 * This is a plain interface, not a functional one: Kotlin does not allow a
 * generic method in a functional interface.
 */
internal interface MainThread {
    /**
     * Run [block] on the main thread and return its value.
     *
     * Throws [MainThreadUnavailable] when [block] was not run in time. A block
     * that was given up on may still run later; the caller guards against that.
     */
    fun <T> call(block: () -> T): T
}

/**
 * Hands a task to the main thread.
 *
 * Returns false when the main thread will never run the task, for example
 * because it is shutting down.
 */
internal fun interface BlockPoster {
    fun post(task: Runnable): Boolean
}

/**
 * The safety net of the hop: returns when the wait for the main thread has gone
 * on long enough. It exists so a stuck main thread cannot hold the caller.
 */
internal fun interface HopDeadline {
    suspend fun elapsed()
}

/** The main thread did not run the block in time. The message is a constant, never text. */
internal class MainThreadUnavailable(message: String) : RuntimeException(message)
