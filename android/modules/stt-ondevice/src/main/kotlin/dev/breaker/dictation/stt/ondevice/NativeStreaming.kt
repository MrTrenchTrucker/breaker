package dev.breaker.dictation.stt.ondevice

import java.io.File

/*
 * The seam between the on-device adapter and the native streaming engine.
 *
 * Everything the adapter needs from the engine is named here, in plain Kotlin
 * types, so the adapter and its tests never touch the native library. The one
 * file that talks to the real library implements these interfaces.
 */

/** One decode session over one clip. A stream knows how to be fed, polled, decoded and read. */
internal interface NativeStream {
    /** Feeds [samples] recorded at [sampleRateHz]. */
    fun acceptWaveform(samples: FloatArray, sampleRateHz: Int)

    /** Tells the engine that no more audio will come. */
    fun inputFinished()

    /** True while the engine still has audio it can decode. */
    fun isReady(): Boolean

    /** Decodes the next piece of audio. */
    fun decode()

    /** The text decoded so far. */
    fun text(): String

    /** The text, the sub-word pieces and their start times in seconds, decoded so far. */
    fun result(): NativeResult = NativeResult(text(), emptyList(), emptyList())

    /** Frees the native memory of this stream. */
    fun release()
}

/** A loaded native recognizer that gives out streams. */
internal interface NativeStreamingRecognizer {
    /** Starts a new, empty stream. */
    fun createStream(): NativeStream

    /** Frees the native memory of this recognizer. */
    fun release()
}

/** What a stream has decoded: the text, the pieces in order, and the start of each piece in seconds from the stream start. */
internal class NativeResult(val text: String, val tokens: List<String>, val timestampsSeconds: List<Float>)

/** The four files a streaming transducer model needs, each already checked to exist. */
internal class TransducerFiles(val encoder: File, val decoder: File, val joiner: File, val tokens: File)

/** Opens a native recognizer over [TransducerFiles]. */
internal fun interface NativeStreamingOpener {
    /** May throw anything the real engine can throw, including [LinkageError]. */
    fun open(files: TransducerFiles, numThreads: Int): NativeStreamingRecognizer
}
