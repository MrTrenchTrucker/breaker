package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.PhraseEvent
import dev.breaker.dictation.core.port.PhraseTrigger
import dev.breaker.dictation.core.usecase.SendResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A spoken send from the phrase detector to the path that holds the take: the send handler routes it, and a
 * take the tile holds reaches the tile's spoken send entry. The detector is a test-local fake that keeps its
 * callback; the events are fired by the test on the test thread.
 */
internal class TileSpokenSendRouteTest {

    /** A detector that keeps the callback it is given. */
    private class FakeTrigger : PhraseTrigger {
        val callbacks: MutableList<(PhraseEvent) -> Unit> = ArrayList()

        override val isListening: Boolean
            get() = callbacks.isNotEmpty()

        override fun start(onPhrase: (PhraseEvent) -> Unit) {
            callbacks.add(onPhrase)
        }

        override fun stop() {}
    }

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

    private val trigger = FakeTrigger()
    private val owner = TakeOwner()
    private val main = ManualMain()
    private val background = ManualBackground()
    private val take = TrimTake()
    private val tile = RecordingTile()
    private val home: MutableList<Long?> = ArrayList()
    private var hidden: Int = 0

    private val coordinator = TileCoordinator(
        tile, main, background, OwnedTakePort(take, owner, TakeOwnerKind.TILE),
        FakeModelReady(true), RecordingModelNotice(), RecordingOpener(),
        RecordingClipboard(), {}, RecordingHistory(),
    )

    private val handler = PhraseSendHandler(
        trigger = trigger,
        owner = owner,
        main = main,
        homeSend = { trim -> home.add(trim) },
        tileSend = { trim -> coordinator.onSpokenSend(trim) { hidden += 1 } },
        onUnavailable = {},
    )

    init {
        coordinator.onArmedChanged(true)
    }

    /** Runs the background and the main queue until both are empty, with a bound that fails by name. */
    private fun settle() {
        var passes = 0
        while (background.pending > 0 || main.pending > 0) {
            passes += 1
            check(passes <= 1_000) { "app: the spoken send route did not settle after 1000 passes" }
            background.runAll()
            main.drain()
        }
    }

    /** The detector fires one send event on its callback, as the detection thread would. */
    private fun firePhraseSend(trim: Long?) {
        trigger.callbacks[0](PhraseEvent.Send(trim))
        settle()
    }

    @Test
    fun `a spoken send for a tile take reaches the hidden callback once when the tile is hidden`() {
        coordinator.onBegin()
        settle()
        handler.start()
        coordinator.onCloseRequested()
        settle()
        firePhraseSend(1_500L)
        assertEquals("app: a spoken send for a hidden tile take calls the hidden callback once", 1, hidden)
        assertEquals("app: a spoken send for a hidden tile take finishes nothing", emptyList<Long?>(), take.trims)
    }

    @Test
    fun `a spoken send for a tile take finishes it with its trim when the tile is shown`() {
        coordinator.onBegin()
        settle()
        handler.start()
        firePhraseSend(1_500L)
        assertEquals("app: a spoken send for a shown tile take finishes it with its trim", listOf<Long?>(1_500L), take.trims)
        assertEquals("app: a spoken send for a shown tile take does not call the hidden callback", 0, hidden)
    }

    @Test
    fun `a spoken send for a home take keeps its trim with the tile hidden and never calls the hidden callback`() {
        owner.begun(TakeOwnerKind.HOME)
        coordinator.onCloseRequested()
        settle()
        handler.start()
        firePhraseSend(1_500L)
        assertEquals("app: a spoken send for a home take keeps its trim", listOf<Long?>(1_500L), home)
        assertEquals("app: a spoken send for a home take never calls the tile hidden callback", 0, hidden)
        assertEquals("app: a spoken send for a home take finishes nothing on the tile", emptyList<Long?>(), take.trims)
    }
}
