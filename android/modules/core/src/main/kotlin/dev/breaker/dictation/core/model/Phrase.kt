package dev.breaker.dictation.core.model

/**
 * Which of the two voice phrases a model or a sample is for, and what an event
 * reports. There is no cancel phrase in this version.
 */
enum class PhraseKind { WAKE, SEND }

/**
 * What a phrase detector reports: the wake phrase, or the send phrase and where
 * it began. A closed set, so a `when` over it fails to compile when a phrase is
 * added, and the code that handles the events has to be told what to do with it.
 *
 * The two events carry different data because the phrases mean different things
 * for the audio. A wake phrase starts the capture, so none of it is in the
 * capture and there is nothing to trim: [Wake] cannot carry an offset. A send
 * phrase ends the capture, and [Send] says where in it the phrase began.
 */
sealed interface PhraseEvent {
    /** The wake phrase was heard. */
    data object Wake : PhraseEvent

    /**
     * The send phrase was heard. [trimBeforeMs] is the milliseconds from the
     * start of the active capture to the moment the phrase began: hand it to
     * `DictateUseCase.stopCapture`, which keeps the audio before it and drops
     * the phrase onward. `null` means the detector cannot say where the phrase
     * began, and the whole capture is kept.
     */
    data class Send(val trimBeforeMs: Long?) : PhraseEvent

    /** The [PhraseKind] this event is about. */
    val kind: PhraseKind
        get() = when (this) {
            Wake -> PhraseKind.WAKE
            is Send -> PhraseKind.SEND
        }
}

/**
 * A trained keyword-spotting model for one phrase, as downloaded to the phone.
 *
 * [sha256] is the digest the phone checked the download against; a model whose
 * digest does not match is refused, because a model file is executable content
 * arriving from elsewhere.
 */
class PhraseModel(val kind: PhraseKind, val bytes: ByteArray, val sha256: String) {
    init {
        require(bytes.isNotEmpty()) { "A phrase model cannot be empty" }
        require(sha256.isNotBlank()) { "A phrase model needs the digest it was verified against" }
    }

    override fun toString(): String = "PhraseModel(kind=$kind, ${bytes.size} bytes)"
}

/** One recorded sample of a phrase, in the same 16 kHz shape as dictation audio. */
class PhraseSample(val kind: PhraseKind, val pcm: FloatArray) {
    init {
        require(pcm.isNotEmpty()) { "A phrase sample cannot be silent" }
    }

    override fun toString(): String = "PhraseSample(kind=$kind, ${pcm.size} samples)"
}

/** A trained model, once it has been verified and is safe to install. */
data class TrainedPhraseModel(val kind: PhraseKind, val model: PhraseModel, val samplesUsed: Int) {
    init {
        require(samplesUsed > 0) { "samplesUsed must be positive: $samplesUsed" }
    }
}
