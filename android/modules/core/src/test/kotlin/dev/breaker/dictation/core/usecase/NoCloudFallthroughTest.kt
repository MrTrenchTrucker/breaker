package dev.breaker.dictation.core.usecase

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.AudioFormat
import dev.breaker.dictation.core.model.DictationResult
import dev.breaker.dictation.core.model.DictationSession
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.testing.FakeWavEncoder
import dev.breaker.dictation.core.testing.FixedClock
import dev.breaker.dictation.core.testing.InMemorySettingsStore
import dev.breaker.dictation.core.testing.PassThroughFormatter
import dev.breaker.dictation.core.testing.RecordingSttEngine
import dev.breaker.dictation.core.testing.ScriptedConnectivityProbe
import dev.breaker.dictation.core.testing.SequentialIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule this project exists for: a dictation that was told to stay on the
 * phone never leaves the phone.
 *
 * The other use-case tests check routing as a side effect. This file exists so
 * that one behaviour is checked on purpose, from every angle a fallthrough
 * could hide:
 *
 * 1. The server engine is never handed the audio — not even a copy, not even
 *    after the local attempt has already failed.
 * 2. The probe is not consulted in `LOCAL` mode, so "the server is reachable"
 *    is not even learned, let alone acted on.
 * 3. A *reachable* server does not change the outcome. The temptation to fall
 *    through is strongest exactly when the server is right there, so that is the
 *    case most worth pinning.
 * 4. `AUTO` mode, having chosen the phone because the server did not answer,
 *    must not try the server afterwards.
 * 5. The failure the user sees names the real reason — no model is installed —
 *    rather than a network-shaped excuse.
 *
 * Every fake here counts its calls, so "did not happen" is asserted directly
 * instead of being inferred from the result.
 */
class NoCloudFallthroughTest {
    private val clock = FixedClock()
    private val ids = SequentialIds()
    private val oneSecond = FloatArray(AudioFormat.SAMPLE_RATE_HZ) { 0.1f }

    /**
     * A session in the RECORDING state: `dictate()` advances it to TRANSCRIBING
     * itself, so handing it one already transcribing would ask for an illegal
     * self-transition.
     */
    private fun recording() = DictationSession().arm().startRecording()

    /** A use case whose on-device engine always reports "no model installed". */
    private fun withMissingModel(
        mode: SttMode,
        serverReachable: Boolean,
    ): Triple<DictateUseCase, RecordingSttEngine, ScriptedConnectivityProbe> {
        val server = RecordingSttEngine.succeeding("text the server should never produce")
        val probe = ScriptedConnectivityProbe(serverReachable)
        val useCase = DictateUseCase(
            settings = InMemorySettingsStore(AppSettings(mode = mode)),
            probe = probe,
            localEngine = RecordingSttEngine.failing(
                SttError.LOCAL_MODEL_MISSING,
                "No model is installed",
            ),
            serverEngine = server,
            serverFormatter = PassThroughFormatter,
            wavEncoder = FakeWavEncoder,
            clock = clock,
            ids = ids,
            localFormatter = PassThroughFormatter,
        )
        return Triple(useCase, server, probe)
    }

    @Test
    fun `local mode with no model never hands the audio to the server engine`() {
        val (useCase, server, _) = withMissingModel(SttMode.LOCAL, serverReachable = true)

        val result = useCase.dictate(recording(), oneSecond)

        // The attempt must really have reached the no-model branch, otherwise
        // "the server was never asked" could hold for the wrong reason.
        assertTrue("expected the no-model failure, got $result", result is DictationResult.Failure)
        assertEquals(SttError.LOCAL_MODEL_MISSING, (result as DictationResult.Failure).error)

        // The audio is the thing that must not travel. Assert on the recorded
        // requests, not only on a counter.
        assertEquals(
            "the server engine was handed the audio in local mode",
            emptyList<Any>(),
            server.requests.toList(),
        )
        assertFalse("the server engine was called at all", server.wasCalled)
    }

    @Test
    fun `local mode with no model does not even probe the network`() {
        val (useCase, _, probe) = withMissingModel(SttMode.LOCAL, serverReachable = true)

        useCase.dictate(recording(), oneSecond)

        assertEquals(
            "local mode must not learn whether the server is reachable",
            0,
            probe.callCount,
        )
    }

    @Test
    fun `a reachable server does not change a local failure into a server dictation`() {
        val (useCase, server, _) = withMissingModel(SttMode.LOCAL, serverReachable = true)

        val result = useCase.dictate(recording(), oneSecond)

        assertTrue(
            "a reachable server must not rescue a local dictation: got $result",
            result is DictationResult.Failure,
        )
        assertEquals(0, server.callCount)
    }

    @Test
    fun `auto mode that chose the phone does not try the server after the model is missing`() {
        val (useCase, server, probe) = withMissingModel(SttMode.AUTO, serverReachable = false)

        val result = useCase.dictate(recording(), oneSecond)

        assertTrue(result is DictationResult.Failure)
        assertEquals(SttError.LOCAL_MODEL_MISSING, (result as DictationResult.Failure).error)
        assertEquals(1, probe.callCount)
        assertEquals(
            "the server was probed and answered no; it must not be asked to transcribe anyway",
            0,
            server.callCount,
        )
    }

    @Test
    fun `auto mode with a reachable server goes to the server and never asks the phone`() {
        val server = RecordingSttEngine.succeeding("server words")
        val local = RecordingSttEngine.succeeding("local words")
        val probe = ScriptedConnectivityProbe(reachable = true)
        val useCase = DictateUseCase(
            settings = InMemorySettingsStore(AppSettings(mode = SttMode.AUTO)),
            probe = probe,
            localEngine = local,
            serverEngine = server,
            serverFormatter = PassThroughFormatter,
            wavEncoder = FakeWavEncoder,
            clock = clock,
            ids = ids,
            localFormatter = PassThroughFormatter,
        )

        val result = useCase.dictate(recording(), oneSecond) as DictationResult.Success

        // AUTO is server-primary, so this is the one case where the server
        // being called is correct — and the phone must be left alone.
        assertEquals(TranscriptionSource.SERVER, result.transcription.source)
        assertEquals(1, server.callCount)
        assertEquals("the phone transcribed when the server answered", 0, local.callCount)
    }

    @Test
    fun `the failure names the missing model, not a network problem`() {
        val (useCase, _, _) = withMissingModel(SttMode.LOCAL, serverReachable = true)

        val result = useCase.dictate(recording(), oneSecond) as DictationResult.Failure

        // A fallthrough would show up here as SERVER_UNREACHABLE or OTHER: the
        // domain would be explaining a problem it does not have.
        assertEquals(SttError.LOCAL_MODEL_MISSING, result.error)
        assertEquals("No model is installed", result.detail)
    }

    @Test
    fun `a failed local dictation produces no transcription for anything to send`() {
        val (useCase, _, _) = withMissingModel(SttMode.LOCAL, serverReachable = true)

        val result = useCase.dictate(recording(), oneSecond) as DictationResult.Failure

        // If a fallthrough had succeeded, there would be text here to commit.
        assertNull(result.session.lastTranscription)
        assertEquals(DictationState.ERROR, result.session.state)
        assertEquals(SttError.LOCAL_MODEL_MISSING, result.session.lastError)
    }

    @Test
    fun `the whole capture path in local mode with no model still never reaches the server`() {
        val (useCase, server, probe) = withMissingModel(SttMode.LOCAL, serverReachable = true)
        val audio = dev.breaker.dictation.core.testing.FakeAudioSource()
        val session = useCase.startCapture(DictationSession().arm().startRecording(), audio)
        audio.emitTone(1_000)

        val result = useCase.stopCapture(session, audio)

        assertTrue(result is DictationResult.Failure)
        assertEquals(SttError.LOCAL_MODEL_MISSING, (result as DictationResult.Failure).error)
        assertEquals(
            "the server engine was handed the captured audio in local mode",
            emptyList<Any>(),
            server.requests.toList(),
        )
        assertEquals(0, probe.callCount)
    }

    @Test
    fun `a server failure is not retried on the phone either`() {
        val local = RecordingSttEngine.succeeding("local words")
        val useCase = DictateUseCase(
            settings = InMemorySettingsStore(AppSettings(mode = SttMode.SERVER)),
            probe = ScriptedConnectivityProbe(),
            localEngine = local,
            serverEngine = RecordingSttEngine.failing(SttError.SERVER_UNREACHABLE, "no answer"),
            serverFormatter = PassThroughFormatter,
            wavEncoder = FakeWavEncoder,
            clock = clock,
            ids = ids,
            localFormatter = PassThroughFormatter,
        )

        val result = useCase.dictate(recording(), oneSecond)

        // The mirror image of the no-fallthrough rule: a user who asked for the
        // server does not silently get a phone-side attempt either.
        assertEquals(SttError.SERVER_UNREACHABLE, (result as DictationResult.Failure).error)
        assertEquals("a server failure must not fall back to the phone", 0, local.callCount)
    }
}
