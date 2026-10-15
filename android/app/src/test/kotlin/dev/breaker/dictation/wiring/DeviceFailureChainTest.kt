package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.core.usecase.DictateUseCase
import dev.breaker.dictation.core.usecase.SendUseCase
import dev.breaker.dictation.overlay.TileState
import dev.breaker.dictation.service.DictationServiceController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The device failure chain: the real dictation runner over a counting audio source, the real take
 * settle and coordinator. The hook is the device failure hook as the component builds it: the tile
 * takes the cut, and when it does not, the fallback runs the old end (the owed stop and the take end).
 */
internal class DeviceFailureChainTest {

    /** One coordinator over the real runner, with every part the chain touches. */
    private class Chain {
        val audio = CountingStops(ScriptedMic())
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
        val runner = DictationRunner(
            dictate,
            SendUseCase(FakeCommitter(), RecordingHistoryStore()),
            audio,
            DictationServiceController(SwitchPermission(true), RecordingLauncher()).also { it.adopt() },
            null,
        )
        val lines = ArrayList<String?>()
        val tile = RecordingTile()
        val main = ManualMain()
        val background = ManualBackground()
        val clipboard = RecordingClipboard()
        val history = RecordingHistory()
        val coordinator = TileCoordinator(
            tile,
            main,
            background,
            TakePortAdapter(runner),
            FakeModelReady(true),
            RecordingModelNotice(),
            RecordingOpener(),
            clipboard,
            { lines.add(it) },
            history,
        )

        init {
            coordinator.onArmedChanged(true)
        }

        /** The fallback the component runs when the tile does not take the cut: the owed stop, then the take end. */
        val fallback: () -> Unit = {
            runner.onCaptureEnded()
            coordinator.onTakeEnded()
        }

        /** The device failure hook: the tile takes the cut on main, or the fallback runs. */
        fun deviceFailure() {
            main.post { if (!coordinator.onTakeFailed()) fallback() }
        }

        /** Runs the background and then the main queue until both are empty. */
        fun settle() {
            while (background.pending > 0 || main.pending > 0) {
                background.runAll()
                main.drain()
            }
        }
    }

    @Test
    fun `a device failure with audio saves and copies the text and pays one audio stop`() {
        val chain = Chain()
        assertEquals("chain: the dictation begins", BeginResult.Recording, chain.runner.begin())
        chain.audio.speak()
        chain.deviceFailure()
        chain.main.drain()
        chain.settle()
        assertEquals("chain: the text is stored once", 1, chain.history.saved.size)
        assertEquals("chain: the stored text is the captured text", "hello world", chain.history.saved.first().text)
        assertEquals("chain: the text is copied once", listOf("hello world"), chain.clipboard.copies)
        assertEquals("chain: exactly one audio stop is paid", 1, chain.audio.stops)
        assertEquals("chain: the session is idle afterwards", DictationState.IDLE, chain.runner.sessionState)
    }

    @Test
    fun `a device failure with audio shows the saved and copied sentence on the failed tile`() {
        val chain = Chain()
        chain.runner.begin()
        chain.audio.speak()
        chain.deviceFailure()
        chain.main.drain()
        chain.settle()
        assertEquals("chain: the tile shows the saved and copied sentence", listOf(DeviceFailureSentences.SAVED_AND_COPIED), chain.tile.notices)
        assertEquals("chain: the tile ends failed", TileState.FAILED, chain.tile.states.last())
        assertEquals("chain: no taken line is posted", emptyList<String?>(), chain.lines)
    }

    @Test
    fun `a device failure with no audio gives the lead sentence only`() {
        val chain = Chain()
        chain.runner.begin()
        chain.deviceFailure()
        chain.main.drain()
        chain.settle()
        assertEquals("chain: the tile shows the lead sentence only", listOf(DeviceFailureSentences.LEAD), chain.tile.notices)
        assertEquals("chain: nothing is stored", 0, chain.history.saved.size)
        assertEquals("chain: nothing is copied", emptyList<String>(), chain.clipboard.copies)
        assertEquals("chain: one audio stop is paid", 1, chain.audio.stops)
    }

    @Test
    fun `a refused background leaves the session recording and the fallback owes the stop until the next cancel`() {
        val chain = Chain()
        chain.runner.begin()
        chain.audio.speak()
        chain.background.submitError = RuntimeException("the background refused the work")
        assertFalse("chain: a refused start is not taken by the tile", chain.coordinator.onTakeFailed())
        assertEquals("chain: a refused start leaves the session recording", DictationState.RECORDING, chain.runner.sessionState)
        chain.fallback()
        assertEquals("chain: the fallback idles the session", DictationState.IDLE, chain.runner.sessionState)
        assertEquals("chain: the fallback does not stop the audio itself", 0, chain.audio.stops)
        chain.runner.cancel()
        assertEquals("chain: the next cancel pays the owed stop", 1, chain.audio.stops)
    }
}
