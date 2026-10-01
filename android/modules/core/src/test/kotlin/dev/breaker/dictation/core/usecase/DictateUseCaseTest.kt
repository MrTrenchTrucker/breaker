package dev.breaker.dictation.core.usecase

import dev.breaker.dictation.core.model.AudioFormat
import dev.breaker.dictation.core.model.DictationResult
import dev.breaker.dictation.core.model.DictationSession
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.testing.BracketingFormatter
import dev.breaker.dictation.core.testing.FakeAudioSource
import dev.breaker.dictation.core.testing.FakeWavEncoder
import dev.breaker.dictation.core.testing.FixedClock
import dev.breaker.dictation.core.testing.InMemorySettingsStore
import dev.breaker.dictation.core.testing.PassThroughFormatter
import dev.breaker.dictation.core.testing.RecordingSttEngine
import dev.breaker.dictation.core.testing.ScriptedConnectivityProbe
import dev.breaker.dictation.core.testing.SequentialIds
import dev.breaker.dictation.core.testing.ThrowingSttEngine
import dev.breaker.dictation.core.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * DictateUseCase: the state machine, the routing, and the privacy rule that
 * local mode with no model fails instead of reaching for the network.
 */
class DictateUseCaseTest {
    private val clock = FixedClock()
    private val ids = SequentialIds()
    private val local = RecordingSttEngine.succeeding("local words")
    private val server = RecordingSttEngine.succeeding("server words")

    private fun useCase(
        mode: SttMode = SttMode.AUTO,
        reachable: Boolean = true,
        localEngine: RecordingSttEngine = local,
        serverEngine: RecordingSttEngine = server,
    ): Pair<DictateUseCase, ScriptedConnectivityProbe> {
        val probe = ScriptedConnectivityProbe(reachable)
        val useCase = DictateUseCase(
            settings = InMemorySettingsStore(AppSettings(mode = mode)),
            probe = probe,
            localEngine = localEngine,
            serverEngine = serverEngine,
            serverFormatter = PassThroughFormatter,
            wavEncoder = FakeWavEncoder,
            clock = clock,
            ids = ids,
            localFormatter = PassThroughFormatter,
        )
        return useCase to probe
    }

    private val oneSecond = FloatArray(AudioFormat.SAMPLE_RATE_HZ) { 0.1f }

    /**
     * A session in the RECORDING state, which is what a caller hands to
     * `dictate()`: the use case advances RECORDING -> TRANSCRIBING itself.
     */
    private fun recording() = DictationSession().arm().startRecording()

    // ── the privacy rule ──────────────────────────────────────────────────

    @Test
    fun `local mode with no model fails and never touches the server engine`() {
        val missing = RecordingSttEngine.failing(
            SttError.LOCAL_MODEL_MISSING,
            "No model is installed",
        )
        val (useCase, probe) = useCase(mode = SttMode.LOCAL, localEngine = missing)

        val result = useCase.dictate(recording(), oneSecond)

        assertTrue("expected a failure", result is DictationResult.Failure)
        assertEquals(SttError.LOCAL_MODEL_MISSING, (result as DictationResult.Failure).error)
        assertEquals("No model is installed", result.detail)
        assertEquals(DictationState.ERROR, result.session.state)
        // The rule: no fallthrough. The server engine must not be asked.
        assertEquals("the server engine was called from local mode", 0, server.callCount)
        assertFalse(server.wasCalled)
        assertEquals("local mode must not probe the network", 0, probe.callCount)
    }

    @Test
    fun `auto mode with no model and an unreachable server does not retry the server`() {
        val missing = RecordingSttEngine.failing(SttError.LOCAL_MODEL_MISSING)
        val (useCase, _) = useCase(mode = SttMode.AUTO, reachable = false, localEngine = missing)

        val result = useCase.dictate(recording(), oneSecond)

        assertTrue(result is DictationResult.Failure)
        assertEquals(SttError.LOCAL_MODEL_MISSING, (result as DictationResult.Failure).error)
        assertEquals(0, server.callCount)
    }

    @Test
    fun `a server failure is surfaced, not retried on the phone`() {
        val broken = RecordingSttEngine.failing(SttError.SERVER_UNREACHABLE, "no answer")
        val (useCase, _) = useCase(mode = SttMode.SERVER, serverEngine = broken)

        val result = useCase.dictate(recording(), oneSecond)

        assertEquals(SttError.SERVER_UNREACHABLE, (result as DictationResult.Failure).error)
        assertEquals("a server failure must not fall back to the phone", 0, local.callCount)
    }

    @Test
    fun `server mode does not probe and never uses the phone engine`() {
        val (useCase, probe) = useCase(mode = SttMode.SERVER, reachable = false)

        val result = useCase.dictate(recording(), oneSecond)

        assertEquals(0, probe.callCount)
        assertEquals(1, server.callCount)
        assertEquals(0, local.callCount)
        assertEquals(TranscriptionSource.SERVER, (result as DictationResult.Success).transcription.source)
    }

    // ── routing ───────────────────────────────────────────────────────────

    @Test
    fun `auto mode transcribes on the server when it is reachable`() {
        val (useCase, probe) = useCase(mode = SttMode.AUTO, reachable = true)

        val result = useCase.dictate(recording(), oneSecond) as DictationResult.Success

        assertEquals(TranscriptionSource.SERVER, result.transcription.source)
        assertEquals("server words", result.transcription.text)
        assertEquals(1, probe.callCount)
        assertEquals(0, local.callCount)
    }

    @Test
    fun `auto mode transcribes on the phone when the server is unreachable`() {
        val (useCase, _) = useCase(mode = SttMode.AUTO, reachable = false)

        val result = useCase.dictate(recording(), oneSecond) as DictationResult.Success

        assertEquals(TranscriptionSource.LOCAL, result.transcription.source)
        assertEquals("local words", result.transcription.text)
        assertEquals(0, server.callCount)
    }

    @Test
    fun `local mode ignores a reachable server`() {
        val (useCase, probe) = useCase(mode = SttMode.LOCAL, reachable = true)

        val result = useCase.dictate(recording(), oneSecond) as DictationResult.Success

        assertEquals(TranscriptionSource.LOCAL, result.transcription.source)
        assertEquals(0, server.callCount)
        assertEquals(0, probe.callCount)
    }

    // ── the result ────────────────────────────────────────────────────────

    @Test
    fun `a successful dictation records the text, source, model, duration and time`() {
        val (useCase, _) = useCase(mode = SttMode.LOCAL)

        val result = useCase.dictate(recording(), oneSecond) as DictationResult.Success

        val t = result.transcription
        assertEquals("id-1", t.id)
        assertEquals("local words", t.text)
        assertEquals(TranscriptionSource.LOCAL, t.source)
        assertEquals("small", t.model)
        assertEquals(1_000L, t.durationMs)
        assertEquals(clock.instant, t.createdAt)
        assertEquals(DictationState.SENDING, result.session.state)
        assertEquals(t, result.session.lastTranscription)
    }

    @Test
    fun `the engine receives the audio, its wav encoding, the model and the language`() {
        val (useCase, _) = useCase(mode = SttMode.LOCAL)

        useCase.dictate(recording(), oneSecond)

        val request = local.requests.single()
        assertTrue(request.pcm.contentEquals(oneSecond))
        assertEquals(FakeWavEncoder.encode(oneSecond).toList(), request.wavBytes.toList())
        assertEquals("small", request.model)
        assertEquals("en", request.language)
    }

    @Test
    fun `an engine that throws becomes a reported failure, not a crash`() {
        val useCase = DictateUseCase(
            settings = InMemorySettingsStore(AppSettings(mode = SttMode.LOCAL)),
            probe = ScriptedConnectivityProbe(),
            localEngine = ThrowingSttEngine("boom"),
            serverEngine = server,
            serverFormatter = PassThroughFormatter,
            wavEncoder = FakeWavEncoder,
            clock = clock,
            ids = ids,
            localFormatter = PassThroughFormatter,
        )

        val result = useCase.dictate(recording(), oneSecond)

        assertTrue(result is DictationResult.Failure)
        assertEquals(SttError.OTHER, (result as DictationResult.Failure).error)
        assertEquals(DictationState.ERROR, result.session.state)
    }

    @Test
    fun `empty audio fails without asking an engine to transcribe nothing`() {
        val (useCase, _) = useCase(mode = SttMode.LOCAL)

        val result = useCase.dictate(recording(), FloatArray(0))

        assertTrue(result is DictationResult.Failure)
        assertEquals(SttError.OTHER, (result as DictationResult.Failure).error)
        assertEquals(0, local.callCount)
    }

    @Test
    fun `dictating from the wrong state is refused`() {
        val (useCase, _) = useCase()
        try {
            useCase.dictate(DictationSession(), oneSecond)
            fail("expected dictating from idle to be refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("IDLE -> TRANSCRIBING"))
        }
    }

    // ── capture ───────────────────────────────────────────────────────────

    @Test
    fun `stopping a capture transcribes everything the source produced`() {
        val (useCase, _) = useCase(mode = SttMode.LOCAL)
        val audio = FakeAudioSource()
        val session = useCase.startCapture(DictationSession().arm().startRecording(), audio)
        audio.emitTone(500)
        audio.emitTone(500)

        val result = useCase.stopCapture(session, audio) as DictationResult.Success

        // Two 500 ms frames: 1_000 ms, and 1_000 ms at 16 kHz is 16_000 samples.
        assertEquals(1_000L, result.transcription.durationMs)
        assertEquals(AudioFormat.SAMPLE_RATE_HZ, local.requests.single().pcm.size)
        // The source is stopped exactly once by the stop.
        assertEquals(1, audio.stopCount)
    }

    @Test
    fun `the send phrase is trimmed out of the audio`() {
        val (useCase, _) = useCase(mode = SttMode.LOCAL)
        val audio = FakeAudioSource()
        val session = useCase.startCapture(DictationSession().arm().startRecording(), audio)
        audio.emitTone(1_000) // the speech
        audio.emitTone(500) // the send phrase

        // The phrase began at 1_000 ms, so the speech before it is what is kept.
        val result = useCase.stopCapture(session, audio, trimBeforeMs = 1_000) as DictationResult.Success

        assertEquals(1_000L, result.transcription.durationMs)
        assertEquals(AudioFormat.SAMPLE_RATE_HZ, local.requests.single().pcm.size)
    }

    @Test
    fun `a trim point past the end of the audio keeps the whole capture`() {
        val (useCase, _) = useCase(mode = SttMode.LOCAL)
        val audio = FakeAudioSource()
        val session = useCase.startCapture(DictationSession().arm().startRecording(), audio)
        audio.emitTone(100)

        // A phrase that begins after the recording ended was never in it, so
        // nothing is trimmed and the dictation still works.
        val result = useCase.stopCapture(session, audio, trimBeforeMs = 60_000) as DictationResult.Success

        assertEquals(100L, result.transcription.durationMs)
        assertEquals(AudioFormat.SAMPLE_RATE_HZ / 10, local.requests.single().pcm.size)
    }

    @Test
    fun `a trim point at the very start leaves no audio to transcribe`() {
        val (useCase, _) = useCase(mode = SttMode.LOCAL)
        val audio = FakeAudioSource()
        val session = useCase.startCapture(DictationSession().arm().startRecording(), audio)
        audio.emitTone(1_000)

        // The phrase began at 0 ms, so the whole capture is the phrase.
        val result = useCase.stopCapture(session, audio, trimBeforeMs = 0)

        assertTrue(result is DictationResult.Failure)
        assertEquals(SttError.OTHER, (result as DictationResult.Failure).error)
        assertEquals(0, local.callCount)
    }

    @Test
    fun `cancelling stops the source and returns to idle`() {
        val (useCase, _) = useCase()
        val audio = FakeAudioSource()
        val session = useCase.startCapture(DictationSession().arm().startRecording(), audio)
        audio.emitTone(100)

        val after = useCase.cancel(session, audio)

        assertEquals(DictationState.IDLE, after.state)
        assertTrue(!audio.isRunning)
        assertEquals(1, audio.stopCount)
        // Cancelled audio is never transcribed.
        assertEquals(0, local.callCount)
    }

    @Test
    fun `starting a capture twice does not leak the previous dictation's audio`() {
        val (useCase, _) = useCase(mode = SttMode.LOCAL)
        val audio = FakeAudioSource()
        val first = useCase.startCapture(DictationSession().arm().startRecording(), audio)
        audio.emitTone(1_000)
        val second = useCase.startCapture(first.cancel().arm().startRecording(), audio)
        audio.emitTone(500)

        val result = useCase.stopCapture(second, audio) as DictationResult.Success

        assertEquals(500L, result.transcription.durationMs)
    }

    @Test
    fun `starting a capture from the wrong state is refused`() {
        val (useCase, _) = useCase()
        // Every state but RECORDING is refused, and the message names the state
        // it was given. A guard that tests the wrong state cannot pass by
        // happening to mention RECORDING.
        DictationState.values().filter { it != DictationState.RECORDING }.forEach { wrong ->
            val audio = FakeAudioSource()
            try {
                useCase.startCapture(DictationSession(state = wrong), audio)
                fail("expected startCapture from $wrong to be refused")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!, e.message!!.contains("RECORDING"))
                assertTrue(e.message!!, e.message!!.contains("not ${wrong.name}"))
            }
            assertEquals("a refused start must not start the source ($wrong)", 0, audio.startCount)
        }

        // RECORDING is the one state it accepts, and the session comes back as it went in.
        val audio = FakeAudioSource()
        val recording = DictationSession(state = DictationState.RECORDING)
        assertEquals(recording, useCase.startCapture(recording, audio))
        assertEquals(1, audio.startCount)
    }

    @Test
    fun `the result of a failure carries a session that can be asked why`() {
        val missing = RecordingSttEngine.failing(SttError.LOCAL_MODEL_MISSING, "install a model")
        val (useCase, _) = useCase(mode = SttMode.LOCAL, localEngine = missing)

        val result = useCase.dictate(recording(), oneSecond) as DictationResult.Failure

        assertNotNull(result.session.lastError)
        assertEquals(SttError.LOCAL_MODEL_MISSING, result.session.lastError)
        assertEquals("install a model", result.detail)
    }

    @Test
    fun `an engine returning a success with empty text still produces a transcription`() {
        val quiet = RecordingSttEngine("quiet") { SttResult.Success(text = "") }
        val (useCase, _) = useCase(mode = SttMode.LOCAL, localEngine = quiet)

        val result = useCase.dictate(recording(), oneSecond) as DictationResult.Success

        assertEquals("", result.transcription.text)
    }
}
