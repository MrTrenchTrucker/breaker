package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.stt.ondevice.FedWordStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The component over the shared fakes and a scripted microphone, with the phrase word stream the test gives it. */
internal fun phraseComponent(
    words: () -> FedWordStream?,
    mic: ScriptedMic,
    controller: DictationServiceController,
): DictationComponent =
    DictationComponent(
        settings = FakeSettingsStore(AppSettings(mode = SttMode.LOCAL)),
        history = RecordingHistoryStore(),
        clock = fixedClock,
        ids = SequenceIds(),
        probe = FakeProbe(),
        localEngine = FakeSttEngine(SttResult.Success("hello world")),
        serverEngine = FakeSttEngine(SttResult.Success("from the server")),
        localFormatter = CountingFormatter(),
        serverFormatter = CountingFormatter(),
        wavEncoder = FixedWavEncoder(),
        committer = FakeCommitter(),
        micSource = mic,
        controller = controller,
        phraseWords = words,
    )

/** The component's dictation and phrase wiring over the fakes, run through the real capture with bounded waits. */
internal class DictationComponentPhraseTest {

    @Test
    fun `a take feeds the held word stream with the frames it records`() {
        val stream = PhraseTestStream()
        val mic = ScriptedMic()
        val controller = DictationServiceController(SwitchPermission(true), RecordingLauncher())
        val component = phraseComponent({ stream }, mic, controller)
        controller.adopt()
        component.phrases.start { }
        assertEquals("app: the scripted take should start", BeginResult.Recording, component.runner.begin())
        awaitBounded("the held stream to be fed a frame", stream.firstFeed)
        val result = component.runner.finish()
        assertEquals("app: the phrase detector must start the held stream once", 1, stream.starts)
        assertTrue("app: the held stream must have been fed the frames of the take", stream.feeds.isNotEmpty())
        assertTrue("app: the take with a word stream must still finish with text", result is FinishResult.ReadyToSend)
        component.close()
    }

    @Test
    fun `with no word stream the take still records and finishes, and only the phrase start is refused`() {
        val mic = ScriptedMic()
        val controller = DictationServiceController(SwitchPermission(true), RecordingLauncher())
        val component = phraseComponent({ null }, mic, controller)
        controller.adopt()
        val refused = try {
            component.phrases.start { }
            false
        } catch (e: IllegalStateException) {
            true
        }
        assertTrue("app: the phrase start must be refused when no stream is ready", refused)
        assertEquals("app: the take must still begin after a refused phrase start", BeginResult.Recording, component.runner.begin())
        awaitBounded("the capture to read twice", mic.secondReadEntered)
        val result = component.runner.finish()
        assertTrue("app: the take must still finish with text after a refused phrase start", result is FinishResult.ReadyToSend)
        component.close()
    }

    @Test
    fun `close stops the phrase detector and its word stream once`() {
        val stream = PhraseTestStream()
        val mic = ScriptedMic()
        val controller = DictationServiceController(SwitchPermission(true), RecordingLauncher())
        val component = phraseComponent({ stream }, mic, controller)
        component.phrases.start { }
        assertTrue("app: the detector must be listening before close", component.phrases.isListening)
        component.close()
        assertFalse("app: close must stop the detector", component.phrases.isListening)
        assertEquals("app: close must stop the held word stream once", 1, stream.stops)
    }
}
