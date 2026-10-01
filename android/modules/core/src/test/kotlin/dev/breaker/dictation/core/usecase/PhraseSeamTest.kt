package dev.breaker.dictation.core.usecase

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.DictationResult
import dev.breaker.dictation.core.model.DictationSession
import dev.breaker.dictation.core.model.PhraseEvent
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.port.PhraseTrigger
import dev.breaker.dictation.core.testing.FakeAudioSource
import dev.breaker.dictation.core.testing.FakeWavEncoder
import dev.breaker.dictation.core.testing.FixedClock
import dev.breaker.dictation.core.testing.InMemorySettingsStore
import dev.breaker.dictation.core.testing.PassThroughFormatter
import dev.breaker.dictation.core.testing.RecordingSttEngine
import dev.breaker.dictation.core.testing.ScriptedConnectivityProbe
import dev.breaker.dictation.core.testing.SequentialIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam between a phrase detector and the capture: the detector reports that
 * the send phrase was heard and where it began, and stopping the capture at that
 * offset must keep the speech and drop the phrase.
 *
 * The audio is built so that speech and phrase are told apart by value (0.1 for
 * speech, 0.9 for the phrase). This checks the half of the contract that lives in
 * core, given an offset that marks the start of the phrase. It cannot show that a
 * particular detector computes that offset correctly; nothing in core does.
 */
class PhraseSeamTest {
    private val speech = 0.1f
    private val phrase = 0.9f

    /** A trigger that reports what a phrase detector reports: the phrase, and where a send phrase began. */
    private class TimelineTrigger : PhraseTrigger {
        private var callback: ((PhraseEvent) -> Unit)? = null

        override var isListening: Boolean = false
            private set

        override fun start(onPhrase: (PhraseEvent) -> Unit) {
            callback = onPhrase
            isListening = true
        }

        override fun stop() {
            callback = null
            isListening = false
        }

        fun hear(event: PhraseEvent) {
            val listener = checkNotNull(callback) { "the trigger is not listening" }
            listener(event)
        }
    }

    private val engine = RecordingSttEngine.succeeding("send this")

    private val useCase = DictateUseCase(
        settings = InMemorySettingsStore(AppSettings(mode = SttMode.LOCAL)),
        probe = ScriptedConnectivityProbe(reachable = true),
        localEngine = engine,
        serverEngine = RecordingSttEngine.succeeding("unused"),
        serverFormatter = PassThroughFormatter,
        wavEncoder = FakeWavEncoder,
        clock = FixedClock(),
        ids = SequentialIds(),
        localFormatter = PassThroughFormatter,
    )

    /** Capture [frames], then let the trigger report a send phrase that began at [beganAtMs]. */
    private fun dictate(frames: List<FloatArray>, beganAtMs: Long?): DictationResult {
        val audio = FakeAudioSource()
        val trigger = TimelineTrigger()
        val session = useCase.startCapture(DictationSession().arm().startRecording(), audio)
        var result: DictationResult? = null
        trigger.start { event ->
            result = when (event) {
                is PhraseEvent.Send -> useCase.stopCapture(session, audio, trimBeforeMs = event.trimBeforeMs)
                PhraseEvent.Wake -> null
            }
        }

        frames.forEach { audio.emit(it) }
        trigger.hear(PhraseEvent.Send(beganAtMs))

        return checkNotNull(result) { "the send phrase did not stop the capture" }
    }

    @Test
    fun `the speech is kept and the send phrase is dropped`() {
        val result = dictate(
            frames = listOf(FloatArray(16_000) { speech }, FloatArray(8_000) { phrase }),
            beganAtMs = 1_000,
        )

        assertTrue("expected a success, got $result", result is DictationResult.Success)
        val heard = engine.requests.single().pcm
        assertEquals("one second of speech", 16_000, heard.size)
        assertTrue("the send phrase reached the engine", heard.all { it == speech })
    }

    @Test
    fun `an offset in the middle of a frame is honoured to the sample`() {
        // One frame that holds the speech and the start of the phrase together.
        val frame = FloatArray(24_000) { if (it < 16_800) speech else phrase }

        dictate(frames = listOf(frame), beganAtMs = 1_050)

        val heard = engine.requests.single().pcm
        assertEquals("1_050 ms at 16 kHz", 16_800, heard.size)
        assertTrue("the send phrase reached the engine", heard.all { it == speech })
    }

    @Test
    fun `a detector that cannot say where the phrase began keeps everything`() {
        dictate(
            frames = listOf(FloatArray(16_000) { speech }, FloatArray(8_000) { phrase }),
            beganAtMs = null,
        )

        assertEquals(24_000, engine.requests.single().pcm.size)
    }
}
