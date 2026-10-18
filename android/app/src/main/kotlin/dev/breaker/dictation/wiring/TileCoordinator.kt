package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.overlay.TileState

/**
 * Drives the floating tile from the taps on it and from the switch: it decides what each tap does
 * and which state the tile shows.
 *
 * Every public member is called on the main thread only, and so are the blocks it hands to [main].
 * The take itself (begin, finish, send, cancel) runs on [background], one block at a time, because
 * finishing blocks while the audio is transcribed. The coordinator starts no thread of its own.
 *
 * A tile state always comes from [tileStateFor]. The coordinator keeps the facts the runner does not
 * keep: that the text of a take is on its way, that the last take failed, what the last send
 * committed, and that a begin is waiting for its answer. A port that throws never reaches the
 * caller: a take that throws shows a fixed sentence and no exception text, and a tile or
 * notification that throws is skipped.
 *
 * Switching off, then on again, makes every answer still on its way stale; a stale answer is dropped.
 */
class TileCoordinator(
    private val tile: TilePort,
    private val main: MainPost,
    private val background: Background,
    private val take: TakePort,
    private val modelReady: ModelReady,
    private val modelNotice: ModelNotice,
    private val opener: Opener,
    private val clipboard: TakenClipboard = object : TakenClipboard { override fun copy(text: String) {} },
    private val takenNotice: (String?) -> Unit = {},
    private val history: HistoryStore? = null,
) {
    private var armed: Boolean = false
    private var sendPushed: Boolean = false
    private var lastFailed: Boolean = false
    private var lastCommit: CommitOutcome? = null
    private var beginPending: Boolean = false
    private var pushed: TileState = TileState.IDLE
    private var generation: Int = 0

    /** The taken-entry controller (the MIC_BUSY face and its settle). */
    internal val yield = TakeYieldController(
        background, main, take, TakeSettle(history, clipboard), takenNotice,
        { push(it) }, { sendPushed = false }, { generation }  // the one place push happens
    )

    /** The device failure entry: the cut is transcribed, saved and copied, with one sentence and no busy face. */
    internal val deviceFailure: DeviceFailureYield = DeviceFailureYield(
        background, main, take, TakeSettle(history, clipboard), { generation }
    ) { fail(it) }

    /** The background side of a finish: the take's finish and its one send. */
    private val finisher: TakeFinisher = TakeFinisher(take)

    /** True while the last show answered SHOWN; a refused or failed show and a switch-off clear it. A begin passes while it holds or while the user-closed flag holds. */
    private var tileShown: Boolean = false

    /** Remembers the user closed the tile; cleared on launcher visible (through arm) or switch off/on. */
    internal val tileShowGuard: TileShowGuard = TileShowGuard(tile, modelNotice)

    /** The switch changed: show the tile when it is on, and take it away when it is off. */
    fun onArmedChanged(armed: Boolean) {
        if (armed) arm() else disarm()
    }

    /** The microphone on an armed tile was tapped: start a take, if the speech model is there. */
    fun onBegin() {
        if (failureBarrier()) return
        if (!armed || (!tileShown && !tileShowGuard.isClosed) || beginPending || yield.yieldRunning() ||
            (pushed != TileState.ARMED && pushed != TileState.SENT && pushed != TileState.MIC_BUSY)) return
        val wasSent: Boolean = pushed == TileState.SENT
        lastCommit = null
        if (tileShowGuard.onBegin { shown -> tileShown = (shown == TileShow.SHOWN); if (shown == TileShow.SHOWN) push() } || !tileShown) return
        if (!isModelReady()) {
            if (wasSent) push()
            guarded { modelNotice.showMissing() }
            // The notification may be switched off for the app, so the tile says it too.
            guarded { tile.showNotice(ModelSentences.NO_MODEL) }
            return
        }
        beginPending = true
        val epoch: Int = generation
        val queued: Boolean = submit {
            val result: BeginResult = try {
                take.begin()
            } catch (e: Exception) {
                BeginResult.Failed(RunnerSentences.COULD_NOT_RECORD)
            }
            main.post { if (epoch == generation) beginSettled(result) }
        }
        if (!queued) {
            beginPending = false
            fail(RunnerSentences.COULD_NOT_RECORD)
        }
    }

    /** A shake shows the tile when it is off screen or closed, and never begins a take. Main thread only. */
    fun onShake() = tileShowGuard.onShake(armed && !beginPending && !sendPushed && !yield.yieldRunning() && !deviceFailure.running() && pushed != TileState.RECORDING, tileShown) { shown ->
        tileShown = (shown == TileShow.SHOWN); if (shown == TileShow.SHOWN) push() }

    /** The user cancelled the take: drop it and show the armed tile. */
    fun onCancel() {
        if (failureBarrier()) return
        if (!armed || yield.yieldRunning()) return
        lastFailed = false
        sendPushed = false
        lastCommit = null
        guarded { tile.clearNotice() }
        // The runner is still recording until the cancel below has run, so the session is given here.
        push(DictationState.IDLE)
        submit { guarded { take.cancel() } }
    }

    /** The user ended the take: finish, transcribe and send it. A repeat before the tile shows sending is ignored. */
    fun onSend() {
        if (failureBarrier()) return
        if (!armed || sendPushed) return
        sendPushed = true
        push()
        val epoch: Int = generation
        val queued: Boolean = submit {
            val end: TakeEnd = finisher.finishTake()
            main.post { if (epoch == generation) settle(end) }
        }
        if (!queued) fail(RunnerSentences.COULD_NOT_FINISH)
    }

    /** The tile was tapped while it is off or failed: clear the failure and open the launcher. */
    fun onTap() {
        if (armed && (pushed == TileState.MIC_BUSY || pushed == TileState.SENT)) {
            onBegin()
            return
        }
        lastFailed = false
        guarded { tile.clearNotice() }
        if (armed) push()
        val route: String? = if (isModelReady()) null else ROUTE_MODEL_VALUE
        guarded { opener.openLauncher(route) }
    }

    fun onCloseRequested() = tileShowGuard.onCloseRequested()

    /** The microphone was taken by another app: cut the take and show the busy face. */
    fun onTakeMicTaken() {
        if (failureBarrier()) return
        if (!armed || sendPushed) return
        sendPushed = true
        yield.onTakeMicTaken()
    }

    /** A take ended by itself (the microphone stopped): the tile goes back to armed. */
    fun onTakeEnded() {
        if (failureBarrier()) return
        if (!armed || sendPushed) return
        push()
        yield.onTakeEndedCleared()
    }

    /**
     * The microphone ended a take by itself with a device failure: transcribe what was captured, save and
     * copy it, then show one sentence. Main thread only. False when the tile does not take the cut, and the
     * caller ends the take the plain way.
     */
    fun onTakeFailed(): Boolean {
        if (failureBarrier()) return false
        if (!armed || sendPushed || sessionNow() != DictationState.RECORDING) return false
        sendPushed = true
        push()
        if (!deviceFailure.start()) {
            sendPushed = false
            push()
            return false
        }
        return true
    }

    /** While a device failure is transcribed, the entries that start, end or change a take do nothing, but disarm and fail are not barred. */
    private fun failureBarrier(): Boolean = deviceFailure.running()

    private fun arm() {
        tileShowGuard.clear()
        // A tile that was off has no take: the one cancel queued when it went off may not have run yet.
        val session: DictationState = if (armed) sessionNow() else DictationState.IDLE
        armed = true
        lastCommit = null
        val shown: TileShow = try {
            tile.show()
        } catch (e: Exception) {
            TileShow.FAILED
        }
        tileShown = (shown == TileShow.SHOWN)
        when (shown) {
            TileShow.SHOWN -> {
                push(session)
                guarded { modelNotice.clear() }
            }
            TileShow.NO_PERMISSION, TileShow.FAILED -> guarded { modelNotice.showTileUnavailable() }
        }
    }

    private fun disarm() {
        tileShowGuard.clear()
        generation += 1
        armed = false
        sendPushed = false
        lastFailed = false
        beginPending = false
        tileShown = false
        lastCommit = null
        push()
        yield.disarmCleared()
        deviceFailure.clear()
        submit { guarded { take.cancel() } }
        guarded { tile.hide() }
    }

    private fun beginSettled(result: BeginResult) {
        beginPending = false
        when (result) {
            is BeginResult.Recording -> { yield.onBeginAccepted(); push() }
            is BeginResult.Taken -> { yield.beginRefusedAsTaken() }  // no FAILED push
            is BeginResult.Refused -> fail(result.sentence)
            is BeginResult.Failed -> { yield.onBeginFailed(); fail(result.sentence) }
        }
    }

    private fun settle(end: TakeEnd) {
        sendPushed = false
        when (end) {
            is TakeEnd.Back -> push()
            is TakeEnd.NothingHeard -> { push(); guarded { tile.showNotice(RunnerSentences.NOTHING_HEARD) } }
            is TakeEnd.Sent -> { lastCommit = end.outcome; push(); guarded { tile.clearNotice() } }
            is TakeEnd.Failure -> fail(end.sentence)
        }
    }

    private fun fail(sentence: String) {
        sendPushed = false
        lastFailed = true
        lastCommit = null
        push()
        // A notice is cleared by a state change, so it is shown after the state.
        guarded { tile.showNotice(sentence) }
    }

    private fun push(session: DictationState = sessionNow()) {
        val state: TileState = tileStateFor(
            armed, session, sendPushed, lastFailed, lastCommit, yield.micBusy.get())
        pushed = state
        guarded { tile.setState(state) }
    }

    private fun sessionNow(): DictationState =
        try {
            take.sessionState
        } catch (e: Exception) {
            DictationState.IDLE
        }

    private fun isModelReady(): Boolean =
        try {
            modelReady.isReady()
        } catch (e: Exception) {
            false
        }

    /** True when [block] was handed to the background; a background that refuses is not an exception here. */
    private fun submit(block: () -> Unit): Boolean =
        try {
            background.submit(block)
            true
        } catch (e: Exception) {
            false
        }

    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // A tile or notification that fails must not stop the take or reach the caller.
        }
    }
}
