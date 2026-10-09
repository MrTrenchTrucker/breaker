package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.WordUpdate

/**
 * Reports the words the recogniser hears while a capture runs.
 *
 * Implemented by a recogniser adapter in another module. The consumer is the phrases
 * module. Both depend on core only, and the app wires them together.
 *
 * Start while running is a no-op. The first start keeps its callback, and a second
 * start does not start a second stream or replace the callback.
 * Stop is safe to call when stopped.
 * [WordUpdate.words] times are capture-relative, as HeardWord.startMs describes: the
 * source owns the origin and resets it when a capture starts.
 * onUpdate is called on one thread.
 * No update is delivered after stop() returns.
 */
interface WordStream {
    /** True while the stream is running. */
    val isRunning: Boolean

    /** Start the stream. [onUpdate] is called on one thread. */
    fun start(onUpdate: (WordUpdate) -> Unit)

    /** Stop the stream. Safe to call when stopped. */
    fun stop()
}
