package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.PhraseEvent

/**
 * Reports the two voice phrases.
 *
 * Implemented by the phrases module, which listens to the on-device stream and
 * matches the wake and send phrases. Each report is a [PhraseEvent]:
 * - [PhraseEvent.Wake]: the wake phrase was heard. The capture starts after it,
 *   so there is nothing of the phrase in the capture to trim, and the event
 *   cannot carry an offset.
 * - [PhraseEvent.Send]: the send phrase was heard. Its `trimBeforeMs` is the
 *   milliseconds from the start of the active capture to the moment the phrase
 *   began. Hand it to `DictateUseCase.stopCapture`: the audio before that point
 *   is kept and the phrase onward is dropped. `null` means the detector cannot
 *   say where the phrase began, and the whole capture is kept.
 *
 * This version has no cancel phrase. Cancelling a dictation is
 * `DictateUseCase.cancel`; a cancel phrase would be a new [PhraseEvent], and
 * deciding what it does is a decision for that change to make.
 *
 * Core pins its half: given an offset the capture is cut there, and a send
 * report with no offset leaves the capture whole. How a detector works out the
 * offset is not checked in core.
 */
interface PhraseTrigger {
    /** True while the trigger is listening. */
    val isListening: Boolean

    /** Start listening. [onPhrase] is called on the detection thread. */
    fun start(onPhrase: (PhraseEvent) -> Unit)

    /** Stop listening. Safe to call when not listening. */
    fun stop()
}
