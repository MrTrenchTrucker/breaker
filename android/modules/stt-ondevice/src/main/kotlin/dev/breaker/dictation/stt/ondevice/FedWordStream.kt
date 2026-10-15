package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.port.WordStream

/**
 * A word stream that also takes audio from its caller: the stream the factory makes.
 *
 * It runs as [WordStream] describes. [feed] is the one addition: it offers audio to the stream.
 */
interface FedWordStream : WordStream {
    /**
     * Offers [samples] (16 kHz mono float samples) to the stream. The block is copied, and the call
     * never blocks. It may be called from any thread.
     *
     * Audio fed once start() has claimed the stream is queued for the worker. Audio fed before start()
     * was called, or after stop() returned or the run ended, is ignored: it is not queued and not
     * counted. A chunk fed at the same moment as stop() may be queued, and is then discarded without
     * reaching the engine. An empty array is ignored. A chunk that finds the queue full is dropped and
     * counted, with its samples.
     */
    fun feed(samples: FloatArray)
}
