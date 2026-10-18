package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.port.AudioListener
import dev.breaker.dictation.core.port.AudioSource

/**
 * An [AudioSource] that passes everything through and reads a level from each frame on the way.
 *
 * Each frame goes to the listener first, as the same array and in the order it came. Only then is
 * the level worked out and handed to the sink, inside a catch of [Exception], so an exception on the
 * level path never reaches the capture or the engine; an [Error] still propagates. Each [start] gets
 * a meter of its own, so one take's level never carries into the next.
 */
internal class LevelTap(
    private val inner: AudioSource,
    private val sink: LevelSink,
) : AudioSource {

    override fun start(listener: AudioListener) {
        val meter = LevelMeter()
        inner.start(
            AudioListener { frame ->
                listener.onFrame(frame)
                try {
                    val update = meter.accept(frame)
                    if (update != null) sink.publish(update)
                } catch (e: Exception) {
                    // A level that cannot be shown is dropped; the frame has already gone on to the listener.
                }
            },
        )
    }

    override fun stop() {
        inner.stop()
    }
}
