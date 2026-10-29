package dev.breaker.dictation.wiring

import dev.breaker.dictation.stt.ondevice.FedWordStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the word stream for the model the settings select. A build runs on the background it is given,
 * never on the caller's thread. [prepare] may be called from any thread: it submits one build at a time,
 * and only when no stream is held for the current model id. When the model id changes, the held stream is
 * dropped at the next prepare. A failed build holds nothing, so the next prepare builds again.
 *
 * [ready] reads the held stream and answers null when none is held for the current model id. It never
 * waits and never starts a build.
 */
internal class PhraseStreamProvider(
    private val background: Background,
    private val modelId: () -> String,
    private val build: (String) -> FedWordStream?,
) {
    /** A held stream and the model id it was built for. */
    private class Held(val id: String, val stream: FedWordStream)

    private val held: AtomicReference<Held?> = AtomicReference(null)
    private val building: AtomicBoolean = AtomicBoolean(false)

    /** Submits one build for the current model id when no stream is held for it and no build is in flight. */
    fun prepare() {
        val id: String = modelId()
        val current: Held? = held.get()
        if (current != null && current.id == id) return
        if (current != null) held.compareAndSet(current, null)
        if (!building.compareAndSet(false, true)) return
        try {
            background.submit { buildFor(id) }
        } catch (e: Exception) {
            building.set(false)
        }
    }

    /** The stream held for the current model id, or null. */
    fun ready(): FedWordStream? {
        val current: Held = held.get() ?: return null
        return if (current.id == modelId()) current.stream else null
    }

    /** Runs on the background: makes the stream for [id] and holds it, or holds nothing when the build fails. */
    private fun buildFor(id: String) {
        try {
            val stream: FedWordStream? = try {
                build(id)
            } catch (e: Exception) {
                null
            }
            if (stream != null) held.set(Held(id, stream))
        } finally {
            building.set(false)
        }
    }
}
