package dev.breaker.dictation.wiring

import dev.breaker.dictation.BreakerCompositionRoot
import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.DictationSession
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.usecase.SendUseCase
import dev.breaker.dictation.service.DictationServiceController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A dictation through the pieces the composition root builds: the real probe, the unavailable
 * engine, committer and microphone slots, the rule-based formatters and the real capture over a
 * scripted microphone.
 */
class DictationFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val history = RecordingHistoryStore()
    private val launcher = RecordingLauncher()
    private val controller = DictationServiceController(SwitchPermission(true), launcher)

    /** Runs one dictation to its end over the scripted microphone with the given mode saved first. */
    private fun dictate(mode: SttMode?): FinishResult {
        val mic = ScriptedMic()
        val root = BreakerCompositionRoot(tmp.root, history, controller, mic)
        if (mode != null) root.settingsStore.save(AppSettings().copy(mode = mode))
        controller.adopt()
        val runner = root.dictation.runner
        assertEquals("app: the capture should start over the scripted microphone", BeginResult.Recording, runner.begin())
        awaitBounded("the capture to read twice", mic.secondReadEntered)
        val result = runner.finish()
        assertFalse("app: the scripted microphone waited for a close that never came", mic.timedOut)
        assertTrue("app: a failed dictation must leave the service armed", controller.isArmed)
        assertEquals("app: a failed dictation must not halt the service", 0, launcher.halts)
        assertTrue("app: a failed dictation must not be saved", history.calls.isEmpty())
        return result
    }

    @Test
    fun `automatic mode with no server address lands on the on-device slot and fails with its sentence`() {
        assertEquals(
            "app: automatic routing with no server should end at the on-device slot",
            FinishResult.Failed(LOCAL_UNAVAILABLE_DETAIL),
            dictate(mode = null),
        )
    }

    @Test
    fun `local mode fails with the on-device sentence`() {
        assertEquals(
            "app: local mode should end at the on-device slot",
            FinishResult.Failed(LOCAL_UNAVAILABLE_DETAIL),
            dictate(mode = SttMode.LOCAL),
        )
    }

    @Test
    fun `server mode fails with the server sentence`() {
        assertEquals(
            "app: server mode should end at the server slot",
            FinishResult.Failed(SERVER_UNAVAILABLE_DETAIL),
            dictate(mode = SttMode.SERVER),
        )
    }

    @Test
    fun `with the microphone slot in place listening fails honestly and the service stays armed`() {
        val root = BreakerCompositionRoot(tmp.root, history, controller)
        controller.adopt()
        assertEquals(
            "app: the unavailable microphone should make begin fail",
            BeginResult.Failed(RunnerSentences.COULD_NOT_RECORD),
            root.dictation.runner.begin(),
        )
        assertEquals("app: a failed begin should leave the session idle", DictationState.IDLE, root.dictation.runner.sessionState)
        assertTrue("app: a failed begin must leave the service armed", controller.isArmed)
        assertEquals("app: a failed begin must not halt the service", 0, launcher.halts)
    }

    @Test
    fun `the unavailable commit slot fails a send with its sentence and the history store of the root still receives the text`() {
        val root = BreakerCompositionRoot(tmp.root, { history }, controller)
        val text = Transcription("t-1", "hand made", TranscriptionSource.LOCAL, "small", 100L, 1_000L)
        val waiting = DictationSession(DictationState.SENDING, null, text)
        val result = SendUseCase(UnavailableTextCommitter(), root.historyStore).send(waiting, text)
        assertEquals("app: the commit slot should make the send fail", CommitOutcome.FAILED, result.outcome.outcome)
        assertEquals("app: the failed send should carry the commit slot's sentence", COMMIT_UNAVAILABLE_DETAIL, result.outcome.detail)
        assertEquals("app: the history should have received the text through the root", listOf(text), history.saved)
    }
}
