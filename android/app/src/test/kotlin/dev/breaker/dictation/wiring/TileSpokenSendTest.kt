package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.usecase.SendResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The spoken send entry of the tile coordinator. A send that lands while the tile is shown finishes the take
 * with its trim unchanged; a send that lands while the tile is hidden finishes nothing and calls the hidden
 * callback once. Every test runs on the test thread over the manual queues.
 */
internal class TileSpokenSendTest {

    /** A take that keeps the trim of each finish and counts its sends. */
    private class TrimTake : TakePort {
        val trims: MutableList<Long?> = ArrayList()
        var sends: Int = 0
        override var sessionState: DictationState = DictationState.IDLE

        override fun begin(): BeginResult {
            sessionState = DictationState.RECORDING
            return BeginResult.Recording
        }

        override fun finish(trimBeforeMs: Long?): FinishResult {
            trims.add(trimBeforeMs)
            sessionState = DictationState.SENDING
            return FinishResult.ReadyToSend(tileTranscription("hello there"))
        }

        override fun send(): SendResult? {
            sends += 1
            sessionState = DictationState.IDLE
            return sentResult(CommitOutcome.COMMITTED, null)
        }

        override fun cancel() {
            sessionState = DictationState.IDLE
        }

        override fun resetAfterTaken() {}
    }

    /** One coordinator over the fakes, armed when built, with a count of the hidden callbacks. */
    private class SpokenRig {
        val tile = RecordingTile()
        val main = ManualMain()
        val background = ManualBackground()
        val take = TrimTake()
        var hidden: Int = 0
        val coordinator = TileCoordinator(
            tile, main, background, take, FakeModelReady(true), RecordingModelNotice(), RecordingOpener(),
            RecordingClipboard(), {}, RecordingHistory(),
        )

        init {
            coordinator.onArmedChanged(true)
        }

        /** Runs the background and the main queue until both are empty, with a bound that fails by name. */
        fun settle() {
            var passes = 0
            while (background.pending > 0 || main.pending > 0) {
                passes += 1
                check(passes <= 1_000) { "app: the spoken send rig did not settle after 1000 passes" }
                background.runAll()
                main.drain()
            }
        }

        fun startRecording() {
            coordinator.onBegin()
            settle()
        }

        fun closeTile() {
            coordinator.onCloseRequested()
            settle()
        }

        fun spokenSend(trim: Long?) {
            coordinator.onSpokenSend(trim) { hidden += 1 }
        }
    }

    @Test
    fun `a spoken send while the tile is shown finishes the take once with its trim unchanged`() {
        val rig = SpokenRig()
        rig.startRecording()
        rig.spokenSend(1_500L)
        rig.settle()
        assertEquals("app: a spoken send while the tile is shown finishes with its trim", listOf<Long?>(1_500L), rig.take.trims)
        assertEquals("app: a spoken send while the tile is shown sends once", 1, rig.take.sends)
        assertEquals("app: a spoken send while the tile is shown does not call the hidden callback", 0, rig.hidden)
    }

    @Test
    fun `a spoken send while the tile is hidden does nothing and calls the hidden callback once`() {
        val rig = SpokenRig()
        rig.startRecording()
        rig.closeTile()
        rig.spokenSend(1_500L)
        rig.settle()
        assertEquals("app: a spoken send while the tile is hidden calls the hidden callback once", 1, rig.hidden)
        assertEquals("app: a spoken send while the tile is hidden finishes nothing", emptyList<Long?>(), rig.take.trims)
        assertEquals("app: a spoken send while the tile is hidden sends nothing", 0, rig.take.sends)
        assertFalse("app: the tile stays hidden after a spoken send", rig.tile.shown)
    }

    @Test
    fun `a spoken send with the tile hidden and no take running calls the hidden callback once and finishes nothing`() {
        val rig = SpokenRig()
        rig.closeTile()
        rig.spokenSend(null)
        rig.settle()
        assertEquals("app: a spoken send with no take calls the hidden callback once", 1, rig.hidden)
        assertEquals("app: a spoken send with no take finishes nothing", emptyList<Long?>(), rig.take.trims)
        assertEquals("app: a spoken send with no take sends nothing", 0, rig.take.sends)
    }

    @Test
    fun `a second spoken send before the first finish settles does not finish the take again`() {
        val rig = SpokenRig()
        rig.startRecording()
        rig.spokenSend(1_500L)
        rig.spokenSend(250L)
        rig.settle()
        assertEquals("app: the second spoken send is dropped and only the first trim finishes", listOf<Long?>(1_500L), rig.take.trims)
        assertEquals("app: the take is sent once", 1, rig.take.sends)
        assertEquals("app: no spoken send reaches the hidden callback", 0, rig.hidden)
    }

    @Test
    fun `a plain send with the tile hidden still finishes a recording take`() {
        val rig = SpokenRig()
        rig.startRecording()
        rig.closeTile()
        rig.coordinator.onSend(1_500L)
        rig.settle()
        assertEquals("app: a plain send with the tile hidden finishes the take with its trim", listOf<Long?>(1_500L), rig.take.trims)
        assertEquals("app: a plain send with the tile hidden sends the take once", 1, rig.take.sends)
    }
}
