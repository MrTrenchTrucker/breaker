package dev.breaker.dictation.wiring

import dev.breaker.dictation.stt.ondevice.FedWordStream

/**
 * Where a take gets the word stream its phrase detector listens to.
 *
 * [newStream] is asked once per take, on the take worker. An answer of null means there is no stream,
 * and then the spoken send stays off: the take runs exactly as it runs without phrases.
 */
interface PhraseStreamSource {
    /** A new word stream for one take, or null when there is none. */
    fun newStream(): FedWordStream?
}

/** The source in use until a speech provider exists: it never answers with a stream. */
internal object NoPhraseStream : PhraseStreamSource {
    override fun newStream(): FedWordStream? = null
}
