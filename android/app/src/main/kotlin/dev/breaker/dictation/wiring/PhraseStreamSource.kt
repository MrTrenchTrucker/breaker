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

/**
 * A source that never answers with a stream. It is only the default argument of phraseHandlerFor, used when
 * no source is given. The app does not use it: the tile host passes the keyword source. Not built: nothing
 * downloads the keyword model, so that source answers no stream until a model is installed by other means,
 * and waking by voice while idle is not wired.
 */
internal object NoPhraseStream : PhraseStreamSource {
    override fun newStream(): FedWordStream? = null
}
