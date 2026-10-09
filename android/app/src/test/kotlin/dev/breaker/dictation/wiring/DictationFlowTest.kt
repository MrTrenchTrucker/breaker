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
import dev.breaker.dictation.stt.ondevice.ErrorMapping
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A dictation through the pieces the composition root builds: the real probe, the real on-device
 * engine over the root's own model folder, the unavailable server, committer and microphone slots,
 * the rule-based formatters and the real capture over a scripted microphone.
 */
class DictationFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val history = RecordingHistoryStore()
    private val launcher = RecordingLauncher()
    private val controller = DictationServiceController(SwitchPermission(true), launcher)

    /** What the engine answers when the selected model is not on the phone. */
    private val notInstalled = FinishResult.Failed(checkNotNull(ErrorMapping.noModelInstalled(AppSettings().modelSize).detail))

    /**
     * Runs one dictation to its end over the scripted microphone with the given mode saved first;
     * [prepare] gets the root before the dictation starts.
     */
    private fun dictate(mode: SttMode?, prepare: (BreakerCompositionRoot) -> Unit = {}): FinishResult {
        val mic = ScriptedMic()
        val root = BreakerCompositionRoot(tmp.root, history, controller, mic)
        prepare(root)
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
    fun `automatic mode with no server address lands on the on-device engine and fails with its not-installed sentence`() {
        assertEquals(
            "app: automatic routing with no server should end at the on-device engine",
            notInstalled,
            dictate(mode = null),
        )
    }

    @Test
    fun `local mode fails with the on-device engine's not-installed sentence`() {
        assertEquals(
            "app: local mode should end at the on-device engine",
            notInstalled,
            dictate(mode = SttMode.LOCAL),
        )
    }

    @Test
    fun `the on-device engine reads the model folder the root exposes`() {
        val result = dictate(mode = SttMode.LOCAL) { root ->
            val archive = root.modelStore.archiveFile("small")
            archive.parentFile.mkdirs()
            archive.writeText("x")
        }
        assertEquals(
            "app: with an archive in the root's model folder the engine must get past not-installed and stop at the missing checksums",
            FinishResult.Failed(checkNotNull(ErrorMapping.checksumsUnreadable("small").detail)),
            result,
        )
    }

    @Test
    fun `the model folder is models under the files directory`() {
        val root = BreakerCompositionRoot(tmp.root, history, controller)
        assertEquals(
            "app: the model store must sit in the models folder of the files directory",
            File(File(tmp.root, "models"), "small"),
            root.modelStore.directoryFor("small"),
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
