package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.port.AudioListener
import dev.breaker.dictation.core.port.AudioSource
import dev.breaker.dictation.stt.ondevice.FedWordStream

/**
 * An [AudioSource] that hands each frame to the listener first, then offers the same array to the word
 * stream that [current] names, when there is one.
 *
 * The offer is made inside a catch of [Exception] and [LinkageError]. A failure there never reaches the
 * capture: the frame has already gone on to the listener, and after the first failure of a start the
 * stream is not offered audio again until the next start. Any other [Error] propagates.
 */
internal class PhraseFeedTap(
    private val inner: AudioSource,
    private val current: () -> FedWordStream?,
) : AudioSource {

    override fun start(listener: AudioListener) {
        val offer = FeedOffer(current)
        inner.start(
            AudioListener { frame ->
                listener.onFrame(frame)
                offer.offer(frame)
            },
        )
    }

    override fun stop() {
        inner.stop()
    }
}

/** The offers of one start. The frames of one start arrive on one thread, so [broken] needs no lock. */
private class FeedOffer(private val current: () -> FedWordStream?) {
    private var broken: Boolean = false

    fun offer(frame: FloatArray) {
        if (broken) return
        try {
            val stream: FedWordStream = current() ?: return
            stream.feed(frame)
        } catch (e: Exception) {
            broken = true
        } catch (e: LinkageError) {
            broken = true
        }
    }
}
