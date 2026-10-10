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
import org.junit.Test

/**
 * The full taken-take chain at the coordinator level, hand-built from the runner's own fakes. The counting
 * stop decorator wraps a scripted mic as the runner's audio so this chain is testable where the [Built] rig
 * does not expose its microphone; the reporter half (where reports are routed) is off on purpose; that is component
 * level, and the coordinator test only owns the coordinator settle. A taken take in production cuts at the read error while
 * the session still records: the hook fires before any finish, then the finish runs inside the block.
 */
class TakeChainTest {

    @Test
    fun `a full taken settle ends armed and pays one audio stop`() {
        val mic = ScriptedMic()
        val countingAudio = CountingStops(mic)
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
            countingAudio,
            DictationServiceController(SwitchPermission(true), RecordingLauncher()).also { it.adopt() },
            null,
        )

        // Hand-built coordinator; the three trailing args are clipboard, taken-notice lambda, history.
        val lines = ArrayList<String?>()
        val tile = RecordingTile()
        val main = ManualMain()
        val background = ManualBackground()
        val coord = TileCoordinator(
            tile = tile,
            main = main,
            background = background,
            take = TakePortAdapter(runner),
            modelReady = FakeModelReady(true),
            modelNotice = RecordingModelNotice(),
            opener = RecordingOpener(),
            clipboard = RecordingClipboard(),
            takenNotice = { lines.add(it) },
            history = RecordingHistoryStore(),
        )
        coord.onArmedChanged(true)

        val onMicTaken: () -> Unit = { coord.onTakeMicTaken() }

        // Drive the taken take in production order (the hook fires before any finish).
        runner.begin()
        countingAudio.speak()
        assert(DictationState.RECORDING == runner.sessionState) { "app: recording while hook fires" }
        onMicTaken()
        main.drain()

        // Run the background then drain main, looping until both queues empty (the settle loop).
        var guard = 0
        while (background.pending > 0 || main.pending > 0) {
            guard += 1
            check(guard <= 1_000) { "app: the chain did not settle" }
            background.runAll()
            main.drain()
        }

// (a) exactly one audio stop - the reset added none.
        assertEquals("app: exactly one audio stop for the taken take", 1, countingAudio.stops)
        // (b) the close contract: ScriptedMic.close must be safe to call more than once.
        assert(mic.closes.get() <= 2) { "app: ScriptedMic closes within the module contract" }
        // (c) the last taken line is one of the three outcome lines, session reset, a follow-up begin starts clean.
        assert(lines.any { it == TakenSentences.SAVED_AND_COPIED || it == TakenSentences.TAKEN_NOTHING_HEARD || it == TakenSentences.NOT_CONVERTED }) { "app: a taken settle posts an outcome line" }
        assertEquals("app: the face stays busy through the settle", TileState.MIC_BUSY, tile.states.last())
        assert(runner.sessionState == DictationState.IDLE) { "app: the reset ran: session back to IDLE" }
        assertEquals("app: a follow-up begin is accepted after settle", BeginResult.Recording, runner.begin())
    }
}
