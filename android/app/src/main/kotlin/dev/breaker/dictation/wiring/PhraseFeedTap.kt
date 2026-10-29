package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.port.AudioListener
import dev.breaker.dictation.core.port.AudioSource
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An [AudioSource] that passes everything through and hands each frame to [feed] on the way.
 *
 * The listener runs on the dispatch thread, and [feed] runs there too, inside the same call;
 * this wrapper starts nothing of its own. Each frame goes to the listener first, as the same
 * array, and only then to [feed]. The wrapper keeps no reference to a frame after the call.
 *
 * An [Exception] or a [LinkageError] from [feed] is caught. After the first one, [feed] is not
 * called again until the next [start]; the take carries on, and every frame still reaches the
 * listener. An [Error] such as an out-of-memory error is not caught and propagates.
 */
internal class PhraseFeedTap(
    private val inner: AudioSource,
    private val feed: (FloatArray) -> Unit,
) : AudioSource {

    private val feedOpen: AtomicBoolean = AtomicBoolean(true)

    override fun start(listener: AudioListener) {
        feedOpen.set(true)
        inner.start(
            AudioListener { frame ->
                listener.onFrame(frame)
                if (feedOpen.get()) {
                    try {
                        feed(frame)
                    } catch (e: Exception) {
                        // A feed that fails stops for this take; the frame has already gone on to the listener.
                        feedOpen.set(false)
                    } catch (e: LinkageError) {
                        feedOpen.set(false)
                    }
                }
            },
        )
    }

    override fun stop() {
        inner.stop()
    }
}
