package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.core.usecase.DictateUseCase
import dev.breaker.dictation.core.usecase.SendUseCase
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DictationRunnerDisarmTest {
    @Test
    fun `disarm has dropped the capture and the session already when the service is halted`() {
        val launcher = FakeLauncher()
        val controller = DictationServiceController(SwitchPermission(true), launcher)
        val audio = FakeAudioSource()
        val dictate = DictateUseCase(
            settings = FakeSettingsStore(AppSettings(mode = SttMode.LOCAL)),
            probe = FakeProbe(),
            localEngine = FakeSttEngine(SttResult.Success("hello world")),
            serverEngine = FakeSttEngine(SttResult.Success("from the server")),
            serverFormatter = CountingFormatter(),
            wavEncoder = FixedWavEncoder(),
            clock = fixedClock,
            ids = SequenceIds(),
            localFormatter = CountingFormatter(),
        )
        val runner = DictationRunner(dictate, SendUseCase(FakeCommitter(), RecordingHistoryStore()), audio, controller, null)
        controller.adopt()
        assertEquals("app: the take should start", BeginResult.Recording, runner.begin())

        val stopsInsideHalt = ArrayList<Int>()
        val statesInsideHalt = ArrayList<DictationState>()
        launcher.onHalt = {
            stopsInsideHalt.add(audio.stops)
            statesInsideHalt.add(runner.sessionState)
        }
        runner.disarm()

        assertEquals("app: the capture must already be stopped when the service is halted", listOf(1), stopsInsideHalt)
        assertEquals("app: the session must already be idle when the service is halted", listOf(DictationState.IDLE), statesInsideHalt)
        assertEquals("app: disarm must halt the service exactly once", 1, launcher.halts)
        assertEquals("app: disarm must stop the capture exactly once", 1, audio.stops)
        assertFalse("app: disarm should leave the service unarmed", controller.isArmed)
    }
}
