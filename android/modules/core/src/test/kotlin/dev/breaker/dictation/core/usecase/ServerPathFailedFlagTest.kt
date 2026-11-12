package dev.breaker.dictation.core.usecase

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.AudioFormat
import dev.breaker.dictation.core.model.DictationResult
import dev.breaker.dictation.core.model.DictationSession
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.Transcription
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transient `serverPathFailed` fact: true only when the auto routing fell
 * back to the phone model because the probe found no server. User-chosen local
 * and server, and an auto call that reached the server, all leave it false. The
 * field never leaves the in-memory value the tile reads: the default is false
 * and only the one auto probe-down sets it.
 */
class ServerPathFailedFlagTest {
    private val clock = FixedClock()
    private val ids = SequentialIds()
    private val local = RecordingSttEngine.succeeding("local words")
    private val server = RecordingSttEngine.succeeding("server words")

    private fun useCase(
        mode: SttMode,
        reachable: Boolean = true,
    ): DictateUseCase {
        val probe = ScriptedConnectivityProbe(reachable)
        return DictateUseCase(
            settings = InMemorySettingsStore(AppSettings(mode = mode)),
            probe = probe,
            localEngine = local,
            serverEngine = server,
            serverFormatter = PassThroughFormatter,
            wavEncoder = FakeWavEncoder,
            clock = clock,
            ids = ids,
            localFormatter = PassThroughFormatter,
        )
    }

    private val oneSecond = FloatArray(AudioFormat.SAMPLE_RATE_HZ) { 0.1f }

    private fun recording() = DictationSession().arm().startRecording()

    private fun transcribe(useCase: DictateUseCase): Transcription {
        val result: DictationResult = useCase.dictate(recording(), oneSecond)
        assertTrue("expected a successful dictation, got $result", result is DictationResult.Success)
        return (result as DictationResult.Success).transcription
    }

    @Test
    fun `auto with the server unreachable sets the flag and records local`() {
        val t = transcribe(useCase(SttMode.AUTO, reachable = false))
        assertEquals("core: the auto probe-down must record a local source", TranscriptionSource.LOCAL, t.source)
        assertTrue("core: the auto probe-down must set the flag", t.serverPathFailed)
    }

    @Test
    fun `auto with the server reachable leaves the flag clear and records server`() {
        val t = transcribe(useCase(SttMode.AUTO, reachable = true))
        assertEquals("core: a reached server must record a server source", TranscriptionSource.SERVER, t.source)
        assertFalse("core: a reached server must not set the flag", t.serverPathFailed)
    }

    @Test
    fun `user-chosen local leaves the flag clear`() {
        val t = transcribe(useCase(SttMode.LOCAL))
        assertEquals("core: local mode must record a local source", TranscriptionSource.LOCAL, t.source)
        assertFalse("core: a user-chosen local must not set the flag", t.serverPathFailed)
    }

    @Test
    fun `user-chosen server leaves the flag clear`() {
        val t = transcribe(useCase(SttMode.SERVER))
        assertEquals("core: server mode must record a server source", TranscriptionSource.SERVER, t.source)
        assertFalse("core: a user-chosen server must not set the flag", t.serverPathFailed)
    }

    @Test
    fun `the default is false so an untouched value never looks like a fallback`() {
        val t = Transcription(
            id = "t-1",
            text = "hello",
            source = TranscriptionSource.LOCAL,
            model = "small",
            durationMs = 100L,
            createdAt = 1_000L,
        )
        assertFalse("core: a transcription built without the flag must default to false", t.serverPathFailed)
    }
}
