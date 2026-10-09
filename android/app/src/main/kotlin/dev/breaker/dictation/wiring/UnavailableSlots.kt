package dev.breaker.dictation.wiring

import dev.breaker.dictation.audio.MicSource
import dev.breaker.dictation.audio.MicSourceException
import dev.breaker.dictation.core.model.AudioFormat
import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.CommitRequest
import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.core.port.CommitOutcomeResult
import dev.breaker.dictation.core.port.SttEngine
import dev.breaker.dictation.core.port.TextCommitter

/** What the server slot tells the user while no server engine is installed. */
const val SERVER_UNAVAILABLE_DETAIL: String = "Server transcription is not available yet."

/** What the commit slot tells the user while no text can be put into a field. */
const val COMMIT_UNAVAILABLE_DETAIL: String = "Putting text into a field is not available yet."

/** What the microphone slot says when it is asked to open. */
const val MIC_UNAVAILABLE_MESSAGE: String = "The microphone is not available yet."

/**
 * A transcription engine slot that always answers with the one failure it was given.
 *
 * It holds the place of an engine that is not built yet, so the rest of the dictation flow can be
 * wired and run end to end. It never answers with a success, never throws and never reads the
 * request, so the audio it is handed is not looked at.
 */
class UnavailableSttEngine(
    private val error: SttError,
    private val detail: String,
) : SttEngine {
    override fun transcribe(request: SttRequest): SttResult = SttResult.failure(error, detail)
}

/** A text committer slot that reports a failed commit with a plain sentence and puts no text anywhere. */
class UnavailableTextCommitter : TextCommitter {
    override fun commit(request: CommitRequest): CommitOutcomeResult =
        CommitOutcomeResult(CommitOutcome.FAILED, COMMIT_UNAVAILABLE_DETAIL)
}

/**
 * A microphone slot that cannot be opened: [open] throws [MicSourceException], [read] reports an
 * error code and [close] does nothing, so a capture over it fails at the start and never records.
 */
class UnavailableMicSource : MicSource {
    override val sampleRateHz: Int
        get() = AudioFormat.SAMPLE_RATE_HZ

    override val channelCount: Int
        get() = AudioFormat.CHANNEL_COUNT

    override fun open() {
        throw MicSourceException(MIC_UNAVAILABLE_MESSAGE)
    }

    override fun read(buffer: ShortArray, offset: Int, lengthInShorts: Int): Int = -1

    override fun close() {
        // Nothing was opened, so there is nothing to release.
    }
}
