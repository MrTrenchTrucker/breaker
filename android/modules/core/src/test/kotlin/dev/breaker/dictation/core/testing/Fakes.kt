package dev.breaker.dictation.core.testing

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.AudioFormat
import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.AudioListener
import dev.breaker.dictation.core.port.AudioSource
import dev.breaker.dictation.core.port.Clock
import dev.breaker.dictation.core.port.CommitOutcomeResult
import dev.breaker.dictation.core.port.ConnectivityProbe
import dev.breaker.dictation.core.port.Formatter
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.port.IdSource
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.core.port.SttEngine
import dev.breaker.dictation.core.port.TextCommitter
import dev.breaker.dictation.core.port.WavEncoder

/**
 * Test doubles for the core ports.
 *
 * Every fake records what it was asked to do, so a test can assert on what the
 * domain did — and, just as importantly, on what it did *not* do. The fakes
 * that matter for the privacy rule are [RecordingSttEngine] and
 * [ScriptedConnectivityProbe]: they count calls, which is how a test proves the
 * server engine was never touched.
 */

/** A probe whose answer is set by the test, and which counts its calls. */
class ScriptedConnectivityProbe(var reachable: Boolean = true) : ConnectivityProbe {
    var callCount: Int = 0
        private set

    override fun isServerReachable(): Boolean {
        callCount++
        return reachable
    }
}

/** An engine that returns a scripted result and counts its calls. */
class RecordingSttEngine(
    val name: String,
    private val respond: (SttRequest) -> SttResult,
) : SttEngine {
    val requests: MutableList<SttRequest> = mutableListOf()

    val callCount: Int get() = requests.size

    /** True when this engine was handed any audio at all. */
    val wasCalled: Boolean get() = requests.isNotEmpty()

    override fun transcribe(request: SttRequest): SttResult {
        requests += request
        return respond(request)
    }

    companion object {
        /** An engine that always returns [text]. */
        fun succeeding(text: String): RecordingSttEngine =
            RecordingSttEngine("ok") { SttResult.Success(text = text) }

        /** An engine that always fails with [error]. */
        fun failing(error: SttError, detail: String? = null): RecordingSttEngine =
            RecordingSttEngine("fail") { SttResult.failure(error, detail) }
    }
}

/** An engine that throws, to prove the use case survives a misbehaving adapter. */
class ThrowingSttEngine(private val message: String = "engine exploded") : SttEngine {
    override fun transcribe(request: SttRequest): SttResult = throw IllegalStateException(message)
}

/** A WAV encoder that returns a recognisable, valid-looking byte string. */
object FakeWavEncoder : WavEncoder {
    const val MARKER: String = "RIFF"

    override fun encode(pcm: FloatArray): ByteArray =
        MARKER.toByteArray(Charsets.US_ASCII) + pcm.size.toString().toByteArray(Charsets.US_ASCII)
}

/** A formatter that brackets its input, so formatted text is distinguishable. */
object BracketingFormatter : Formatter {
    override fun format(rawText: String): String = "[$rawText]"
}

/** A formatter that passes text through unchanged. */
object PassThroughFormatter : Formatter {
    override fun format(rawText: String): String = rawText
}

/** Settings held in memory, loaded as given. */
class InMemorySettingsStore(var settings: AppSettings = AppSettings()) : SettingsStore {
    var loadCount: Int = 0
        private set

    override fun load(): AppSettings {
        loadCount++
        return settings
    }

    override fun save(settings: AppSettings) {
        this.settings = settings
    }
}

/** An in-memory history store that records writes. */
class InMemoryHistoryStore : HistoryStore {
    val saved: MutableList<Transcription> = mutableListOf()

    /** Ids deleted, in order. */
    val deleted: MutableList<String> = mutableListOf()

    override fun save(transcription: Transcription) {
        saved.removeAll { it.id == transcription.id }
        saved += transcription
    }

    override fun list(limit: Int): List<Transcription> = saved.asReversed().take(limit)

    override fun delete(id: String): Boolean {
        deleted += id
        return saved.removeAll { it.id == id }
    }
}

/** A committer that returns a fixed outcome and records the text it was given. */
class FakeTextCommitter(
    var outcome: CommitOutcome = CommitOutcome.COMMITTED,
    private val detail: String? = null,
) : TextCommitter {
    /** Every text handed to this committer, in order. */
    val committed: MutableList<String> = mutableListOf()

    val callCount: Int get() = committed.size

    override fun commit(request: dev.breaker.dictation.core.model.CommitRequest): CommitOutcomeResult {
        committed += request.text
        return CommitOutcomeResult(outcome, detail)
    }
}

/** An audio source that hands frames to whoever is listening, on demand. */
class FakeAudioSource : AudioSource {
    private var listener: AudioListener? = null

    var isRunning: Boolean = false
        private set

    var startCount: Int = 0
        private set

    var stopCount: Int = 0
        private set

    override fun start(listener: AudioListener) {
        startCount++
        isRunning = true
        this.listener = listener
    }

    override fun stop() {
        stopCount++
        isRunning = false
    }

    /** Push one frame of 16 kHz mono audio to the listener. */
    fun emit(samples: FloatArray) {
        val target = listener ?: error("emit() before start(): nothing is listening")
        target.onFrame(samples)
    }

    /** Push [ms] milliseconds of a steady tone. */
    fun emitTone(ms: Long, amplitude: Float = 0.5f) {
        val count = (ms * AudioFormat.SAMPLE_RATE_HZ / 1000L).toInt()
        emit(FloatArray(count) { amplitude })
    }
}

/** A clock that stands still at a fixed instant. */
class FixedClock(var instant: Long = 1_700_000_000_000L) : Clock {
    override fun nowEpochMillis(): Long = instant
}

/** Ids that count up, so tests can assert on them. */
class SequentialIds(private val prefix: String = "id") : IdSource {
    private var next: Int = 1

    override fun newId(): String = "$prefix-${next++}"
}

/** A transcription with sensible defaults, for tests that do not care. */
fun aTranscription(
    id: String = "t-1",
    text: String = "hello",
    source: TranscriptionSource = TranscriptionSource.LOCAL,
    model: String = "small",
    durationMs: Long = 1_000L,
    createdAt: Long = 1_700_000_000_000L,
): Transcription = Transcription(id, text, source, model, durationMs, createdAt)
