package dev.breaker.dictation.wiring

import dev.breaker.dictation.stt.ondevice.FedWordStream
import java.util.concurrent.atomic.AtomicReference

/**
 * The spoken-send slot of the app. The root is built before the take worker exists, so it holds this
 * slot, and the audio tee and the take ends reach the phrase handler through it. The tile host binds the
 * handler when it first makes it; until then the slot answers no stream and ends nothing.
 */
class PhraseSlot {
    private val bound: AtomicReference<PhraseSendHandler?> = AtomicReference(null)

    /** Points the slot at the handler the app made. */
    fun bind(handler: PhraseSendHandler) {
        bound.set(handler)
    }

    /** The word stream of the take that runs now, or null. The audio tee reads it on the capture thread. */
    fun currentStream(): FedWordStream? = bound.get()?.currentStream()

    /** A take that ended by itself or a service that ended: the handler stops its detector. Any thread. */
    fun onTakeEnded() {
        bound.get()?.onTakeEnded()
    }
}

/** The app's phrase handler. No speech provider exists yet, so it is given [NoPhraseStream] and the spoken send stays off. */
internal fun phraseHandlerFor(background: Background, main: MainPost): PhraseSendHandler =
    PhraseSendHandler(background, main, NoPhraseStream)

/** The Home take port with its spoken send; without a handler the take port is the plain one. */
internal fun homeSpokenTake(inner: TakePort, handler: PhraseSendHandler?, sendEntry: () -> Unit): TakePort =
    if (handler == null) inner else PhraseTakePort(inner, handler, sendEntry)
