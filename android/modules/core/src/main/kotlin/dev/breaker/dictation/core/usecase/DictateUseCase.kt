package dev.breaker.dictation.core.usecase

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.AudioFormat
import dev.breaker.dictation.core.model.DictationResult
import dev.breaker.dictation.core.model.DictationSession
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.AudioListener
import dev.breaker.dictation.core.port.AudioSource
import dev.breaker.dictation.core.port.Clock
import dev.breaker.dictation.core.port.ConnectivityProbe
import dev.breaker.dictation.core.port.Formatter
import dev.breaker.dictation.core.port.IdSource
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.core.port.SttEngine
import dev.breaker.dictation.core.port.WavEncoder
import kotlinx.coroutines.channels.Channel

// Shown when a step throws; a thrown exception's class name or message is never shown.
private const val SPEECH_NOT_CONVERTED = "The speech could not be converted."

/**
 * Runs one dictation from audio to text.
 *
 * The use case owns the state machine ([DictationSession]) and the routing
 * decision. Everything it touches is a port, so the whole flow runs in a plain
 * unit test with no device, no network and no clock.
 *
 * ### Routing
 *
 * The engine is chosen from the user's mode:
 * - `AUTO` — ask [ConnectivityProbe]. Server when it answers, on-device when it
 *   does not. This is the default.
 * - `LOCAL` — always on-device, without probing.
 * - `SERVER` — always the server, without probing, and it fails loudly rather
 *   than quietly dropping to the phone.
 *
 * ### The rule that matters
 *
 * When the on-device engine reports [SttError.LOCAL_MODEL_MISSING], the attempt
 * ends there. The use case does **not** try the server, does not retry, and
 * does not reach for any other path — a phone that was told to work locally has
 * no model, and the honest answer is "no model is installed". A dictation that
 * quietly leaves the device is a privacy failure, so the failure is surfaced
 * instead. A server failure is surfaced the same way rather than retried: the
 * fallback decision belongs to the probe, made once, before the attempt.
 *
 * ### Formatting
 *
 * The transcript is formatted after it is transcribed, and which formatter
 * sees it follows the route the dictation actually took ([LocalModeEgress]):
 * - server route, formatting on: [serverFormatter], which may be a cloud service.
 * - on-device route, formatting on: [localFormatter], which must stay on the
 *   phone. The [serverFormatter] is never called for a phone transcript,
 *   including when automatic routing fell back to the phone and including when
 *   formatting was switched on before the user moved to the phone.
 * - either route, formatting off: the text as dictated.
 *
 * Both formatters are required, with no default, so a caller has to decide what
 * runs on the phone; a pass-through leaves the text as dictated.
 *
 * Deciding this adds no probe and no settings read: it uses the route and the
 * settings already loaded for the attempt.
 *
 * Not thread-safe: drive one dictation from one thread. The audio callback and
 * the thread that drains it meet at the capture channel, which is the only
 * shared thing between the two.
 */
class DictateUseCase(
    private val settings: SettingsStore,
    private val probe: ConnectivityProbe,
    private val localEngine: SttEngine,
    private val serverEngine: SttEngine,
    private val serverFormatter: Formatter,
    private val wavEncoder: WavEncoder,
    private val clock: Clock,
    private val ids: IdSource,
    private val localFormatter: Formatter,
) {
    /**
     * The audio a capture collects, as a queue of frames. The capture thread
     * appends; the draining thread takes the lot out in one go. Unbounded, so an
     * append never blocks the capture thread and never fails while the channel is
     * open — the same way the list it replaces grew. It is drained to empty by a
     * stop, a cancel or a fresh start, so it holds one capture's worth of audio at
     * a time.
     */
    private val captured = Channel<FloatArray>(Channel.UNLIMITED)

    private val captureListener = AudioListener { samples ->
        // The capture thread hands a frame in and moves on; it never waits on the
        // drain and never loses a frame while the channel is open.
        captured.trySend(samples.copyOf())
    }

    /**
     * Start capturing audio for a dictation. [session] must already be in the
     * RECORDING state and comes back unchanged; [audioSource] starts delivering
     * audio into this use case, and any audio an earlier capture left buffered
     * is discarded.
     */
    fun startCapture(session: DictationSession, audioSource: AudioSource): DictationSession {
        check(session.state == DictationState.RECORDING) {
            "Recording can only start from the RECORDING state, not ${session.state.name}"
        }
        // A fresh capture starts empty: whatever an earlier capture left in the
        // channel is drained away, so its audio cannot leak into this one.
        drainCaptured()
        audioSource.start(captureListener)
        return session
    }

    /**
     * End the dictation: the buffered audio is transcribed, formatted and
     * returned as a [Transcription], and the session moves to sending.
     *
     * [trimBeforeMs] is where the send phrase began. Everything from that point
     * on is the phrase, so it is dropped and the speech before it is kept — that
     * is what keeps the phrase out of the transcript. A null value keeps
     * everything. A negative value is a caller bug: it is refused with
     * [IllegalArgumentException] before the source is stopped or any audio is
     * discarded, so the caller can try again with a good offset.
     *
     * A session the state machine refuses to move (not RECORDING) is a wiring
     * bug and throws, with no side effects: the source keeps running and the
     * capture is kept, so the caller can try again with the right session.
     */
    fun stopCapture(
        session: DictationSession,
        audioSource: AudioSource,
        trimBeforeMs: Long? = null,
    ): DictationResult {
        require(trimBeforeMs == null || trimBeforeMs >= 0) {
            "trimBeforeMs cannot be negative: $trimBeforeMs"
        }
        // Validate the move BEFORE stopping the source or draining the
        // buffer: a stop the state machine refuses is a wiring bug, and — like
        // a refused startCapture — it must leave the source running and the
        // capture intact, so the caller can try again with the right session.
        val transcribing = session.transitionTo(DictationState.TRANSCRIBING)
        audioSource.stop()
        // The empty-audio case is reported from the TRANSCRIBING state, which
        // is the only state an error may be raised from. Failing on the raw
        // RECORDING session would throw instead of reporting the failure the
        // caller asked about.
        return runFromTranscribing(transcribing, takeCapturedAudio(trimBeforeMs))
    }

    /**
     * Stop the source and throw the buffered audio away. The returned session is
     * IDLE, with no error and no transcription; arm it again to record again.
     *
     * A cancel the state machine refuses is a wiring bug and throws, with no
     * side effects: the source is not stopped a second time and the buffer is
     * not cleared, so a stale cancel cannot kill a live capture.
     */
    fun cancel(session: DictationSession, audioSource: AudioSource): DictationSession {
        // Validate BEFORE stopping the source or clearing the buffer: a
        // cancel the state machine refuses is a wiring bug, and — like a
        // refused startCapture or stopCapture — it must have no side effects,
        // so a stale cancel handed a fresh session cannot kill a live capture.
        val idle = session.cancel()
        audioSource.stop()
        // A cancel drops the audio: whatever is left in the channel is drained
        // away, so a cancelled capture leaves nothing for the next one.
        drainCaptured()
        return idle
    }

    /**
     * Transcribe and format [audio], advancing [session].
     *
     * Exposed on its own so a caller that captured audio elsewhere — a replay,
     * a test — can run the same path.
     *
     * A move the state machine refuses is a wiring bug and throws. Anything an
     * adapter throws after that point — the settings store, the probe, the WAV
     * encoder, an engine, a formatter, the id source, the clock, checked exceptions
     * included — is reported as a [DictationResult.Failure] with [SttError.OTHER]
     * instead of escaping, and the detail is one fixed sentence, never the exception's
     * class or message (which can carry the dictated text). An interruption also leaves the
     * thread's interrupt flag set. An [Error] is not an adapter failure and propagates.
     */
    fun dictate(session: DictationSession, audio: FloatArray): DictationResult {
        val transcribing = session.transitionTo(DictationState.TRANSCRIBING)
        return runFromTranscribing(transcribing, audio)
    }

    /** The body of [dictate] and [stopCapture], from a TRANSCRIBING session. */
    private fun runFromTranscribing(transcribing: DictationSession, audio: FloatArray): DictationResult {
        if (audio.isEmpty()) {
            return fail(transcribing, SttError.OTHER, "No audio was captured")
        }
        return try {
            transcribe(transcribing, audio)
        } catch (e: Exception) {
            // Re-armed, not folded: an interrupted adapter is the thread's, not just a
            // Failure(OTHER) with the fixed sentence (the threading rule in the root AGENTS.md).
            if (e is InterruptedException) Thread.currentThread().interrupt()
            fail(transcribing, SttError.OTHER, SPEECH_NOT_CONVERTED)
        }
    }

    /** The part of [dictate] that talks to adapters. */
    private fun transcribe(transcribing: DictationSession, audio: FloatArray): DictationResult {
        val config = settings.load()
        val route = route(config.mode)
        val request = SttRequest(
            pcm = audio,
            wavBytes = wavEncoder.encode(audio),
            model = config.modelSize,
            language = config.language,
        )

        val result = try {
            route.engine.transcribe(request)
        } catch (e: RuntimeException) {
            // An adapter that throws instead of returning a failure would take
            // the whole flow down. Report it the same way as any other error
            // and let the caller carry on.
            SttResult.failure(SttError.OTHER, SPEECH_NOT_CONVERTED)
        }

        return when (result) {
            is SttResult.Failure -> fail(transcribing, result.error, result.detail)
            is SttResult.Success -> {
                val text = formatted(result.text, route, config)
                val transcription = Transcription(
                    id = ids.newId(),
                    text = text,
                    source = route.source,
                    model = config.modelSize,
                    durationMs = request.durationMs,
                    createdAt = clock.nowEpochMillis(),
                )
                DictationResult.Success(transcribing.withTranscription(transcription), transcription)
            }
        }
    }

    /**
     * Pick the engine for [mode]. In [SttMode.AUTO] the probe decides; in the
     * other two modes the choice is the user's and no probe is made.
     */
    private fun route(mode: SttMode): Route =
        when (mode) {
            SttMode.LOCAL -> Route(localEngine, TranscriptionSource.LOCAL)
            SttMode.SERVER -> Route(serverEngine, TranscriptionSource.SERVER)
            SttMode.AUTO ->
                if (probe.isServerReachable()) {
                    Route(serverEngine, TranscriptionSource.SERVER)
                } else {
                    Route(localEngine, TranscriptionSource.LOCAL)
                }
        }

    /**
     * [rawText] after formatting. The [serverFormatter] is reached only on the
     * server route; an on-device transcript goes to [localFormatter], and nothing
     * is formatted when the user has switched formatting off.
     */
    private fun formatted(rawText: String, route: Route, config: AppSettings): String {
        val onDevice = route.source == TranscriptionSource.LOCAL
        return when {
            LocalModeEgress.mayCleanUpTranscript(onDevice, config.formattingEnabled) -> serverFormatter.format(rawText)
            config.formattingEnabled -> localFormatter.format(rawText)
            else -> rawText
        }
    }

    private fun fail(
        session: DictationSession,
        error: SttError,
        detail: String?,
    ): DictationResult = DictationResult.Failure(session.withError(error), error, detail)

    /**
     * The buffered audio, flattened and trimmed. Empties the buffer.
     *
     * [trimBeforeMs] is where the send phrase began: the speech before that
     * point is kept and the phrase from that point on is dropped. The parameter
     * is the start of the phrase, not the start of the speech, so a trim point
     * past the end of the capture keeps the whole capture — there was no phrase
     * in it to remove. That holds for any offset, however large: the offset is
     * compared with the capture's length before it is multiplied by the sample
     * rate, so it cannot wrap around.
     */
    private fun takeCapturedAudio(trimBeforeMs: Long?): FloatArray {
        // The snapshot-and-clear: drain the channel to empty, taking whatever is
        // in it now. A frame the capture thread appends after the drain has seen
        // the channel empty stays in the channel for the next capture, exactly as
        // it would have stayed in the cleared list. The drain is one loop shared
        // with the start and cancel paths, so the cut is defined in one place.
        val frames = drainCaptured()
        val total = frames.sumOf { it.size }
        val flat = FloatArray(total)
        var offset = 0
        frames.forEach { frame ->
            frame.copyInto(flat, offset)
            offset += frame.size
        }
        val keepSamples = trimBeforeMs?.let { keptSamples(it, total) } ?: total
        return flat.copyOfRange(0, keepSamples)
    }

    /**
     * How many of [total] samples come before an offset of [trimBeforeMs], which is
     * not negative. An offset that reaches [total] samples or more keeps them all;
     * anything smaller is at most [total] samples and cannot overflow the multiplication.
     */
    private fun keptSamples(trimBeforeMs: Long, total: Int): Int {
        val firstOffsetPastTheEndMs = total.toLong() * 1000L / AudioFormat.SAMPLE_RATE_HZ + 1
        return if (trimBeforeMs >= firstOffsetPastTheEndMs) {
            total
        } else {
            (trimBeforeMs * AudioFormat.SAMPLE_RATE_HZ / 1000L).coerceIn(0L, total.toLong()).toInt()
        }
    }

    /**
     * Takes every frame currently in the capture channel, in order, leaving it
     * empty. The loop stops at the first empty read, which is the cut: a frame
     * the capture thread appends after that read stays in the channel for the
     * next capture. Shared by the stop, the cancel and the fresh start, so the
     * cut is defined in one place.
     */
    private fun drainCaptured(): List<FloatArray> {
        val frames = mutableListOf<FloatArray>()
        while (true) {
            // tryReceive() hands back a ChannelResult, not the element: an empty
            // read is a result, so the element is taken with getOrNull(), which is
            // null exactly when there is nothing to take. That null is the cut.
            val frame = captured.tryReceive().getOrNull()
            if (frame == null) break
            frames += frame
        }
        return frames
    }

    /** An engine plus the source its transcripts are recorded as. */
    private data class Route(val engine: SttEngine, val source: TranscriptionSource)
}
