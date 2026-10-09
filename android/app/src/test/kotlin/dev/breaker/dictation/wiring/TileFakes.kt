package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.DictationSession
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.CommitOutcomeResult
import dev.breaker.dictation.core.usecase.SendResult
import dev.breaker.dictation.overlay.TileState

/** What a refused begin answers with in the tile tests: equal to no sentence the app itself holds. */
internal const val REFUSED_SENTENCE: String = "refused-sentence-A"

/** What a failed begin answers with in the tile tests: equal to no sentence the app itself holds. */
internal const val FAILED_SENTENCE: String = "failed-sentence-B"

/** Upper bound on queue passes, so a block that keeps posting itself fails by name instead of hanging. */
private const val MAX_PASSES: Int = 1_000

/** Runs posted blocks only when a test drains them, in order; a block posted while draining runs in the same drain. */
internal class ManualMain : MainPost {
    private val queue: ArrayDeque<() -> Unit> = ArrayDeque()

    val pending: Int
        get() = queue.size

    override fun post(block: () -> Unit) {
        queue.addLast(block)
    }

    fun drain() {
        var steps = 0
        while (queue.isNotEmpty()) {
            steps += 1
            check(steps <= MAX_PASSES) { "app: the manual main queue did not empty after $MAX_PASSES blocks" }
            val next: () -> Unit = queue.removeFirst()
            next()
        }
    }
}

/** Runs submitted blocks only when a test says so, one at a time in submission order. */
internal class ManualBackground : Background {
    private val queue: ArrayDeque<() -> Unit> = ArrayDeque()

    /** When set, [submit] throws it, the way a refusing executor would. */
    var submitError: RuntimeException? = null

    val pending: Int
        get() = queue.size

    override fun submit(block: () -> Unit) {
        submitError?.let { throw it }
        queue.addLast(block)
    }

    fun runAll() {
        var steps = 0
        while (queue.isNotEmpty()) {
            steps += 1
            check(steps <= MAX_PASSES) { "app: the manual background queue did not empty after $MAX_PASSES blocks" }
            val next: () -> Unit = queue.removeFirst()
            next()
        }
    }
}

/** A tile that keeps a call log; with [throwing] set every call is logged and then throws. */
internal class RecordingTile : TilePort {
    val calls: MutableList<String> = ArrayList()
    val states: MutableList<TileState> = ArrayList()
    val notices: MutableList<String> = ArrayList()
    var showResult: TileShow = TileShow.SHOWN
    var throwing: Boolean = false

    override fun show(): TileShow {
        calls.add("show")
        gate()
        return showResult
    }

    override fun hide() {
        calls.add("hide")
        gate()
    }

    override fun setState(state: TileState) {
        calls.add("state:$state")
        states.add(state)
        gate()
    }

    override fun showNotice(text: String) {
        calls.add("notice")
        notices.add(text)
        gate()
    }

    override fun clearNotice() {
        calls.add("clearNotice")
        gate()
    }

    private fun gate() {
        if (throwing) throw IllegalStateException("the tile failed")
    }
}

/** A take that behaves like the runner (its session follows the calls) unless [mirrorRunner] is off. */
internal class FakeTake : TakePort {
    override var sessionState: DictationState = DictationState.IDLE
    var mirrorRunner: Boolean = true
    var beginResult: BeginResult = BeginResult.Recording
    var finishResult: FinishResult = FinishResult.ReadyToSend(tileTranscription("hello there"))
    var sendResult: SendResult? = sentResult(CommitOutcome.COMMITTED, null)
    var beginError: RuntimeException? = null
    var finishError: RuntimeException? = null
    var sendError: RuntimeException? = null
    var cancelError: RuntimeException? = null
    var beginCalls: Int = 0
        private set
    var finishCalls: Int = 0
        private set
    var sendCalls: Int = 0
        private set
    var cancelCalls: Int = 0
        private set

    override fun begin(): BeginResult {
        beginCalls += 1
        beginError?.let { throw it }
        if (mirrorRunner && beginResult == BeginResult.Recording) sessionState = DictationState.RECORDING
        return beginResult
    }

    override fun finish(): FinishResult {
        finishCalls += 1
        finishError?.let { throw it }
        if (mirrorRunner) sessionState = if (finishResult is FinishResult.ReadyToSend) DictationState.SENDING else DictationState.IDLE
        return finishResult
    }

    override fun send(): SendResult? {
        sendCalls += 1
        sendError?.let { throw it }
        if (mirrorRunner) sessionState = DictationState.IDLE
        return sendResult
    }

    override fun cancel() {
        cancelCalls += 1
        if (mirrorRunner) sessionState = DictationState.IDLE
        cancelError?.let { throw it }
    }
}

internal class FakeModelReady(var ready: Boolean = true) : ModelReady {
    var error: RuntimeException? = null

    override fun isReady(): Boolean {
        error?.let { throw it }
        return ready
    }
}

/** A notice that keeps a call log; with [throwing] set every call is logged and then throws. */
internal class RecordingModelNotice : ModelNotice {
    val calls: MutableList<String> = ArrayList()
    var throwing: Boolean = false

    override fun showMissing() {
        calls.add("missing")
        gate()
    }

    override fun showTileUnavailable() {
        calls.add("tileUnavailable")
        gate()
    }

    override fun clear() {
        calls.add("clear")
        gate()
    }

    private fun gate() {
        if (throwing) throw IllegalStateException("the notice failed")
    }
}

internal class RecordingOpener : Opener {
    val routes: MutableList<String?> = ArrayList()
    var throwing: Boolean = false

    override fun openLauncher(route: String?) {
        routes.add(route)
        if (throwing) throw IllegalStateException("the launcher failed")
    }
}

internal fun tileTranscription(text: String): Transcription =
    Transcription(id = "t-1", text = text, source = TranscriptionSource.LOCAL, model = "small", durationMs = 100L, createdAt = 1_000L)

/** What a send answers with: [outcome] and the optional [detail]. */
internal fun sentResult(outcome: CommitOutcome, detail: String?): SendResult =
    SendResult(CommitOutcomeResult(outcome, detail), DictationSession())

/** The coordinator over the fakes. It is armed when built unless [arm] is false. */
internal class TileRig(ready: Boolean = true, arm: Boolean = true) {
    val tile = RecordingTile()
    val main = ManualMain()
    val background = ManualBackground()
    val take = FakeTake()
    val model = FakeModelReady(ready)
    val notice = RecordingModelNotice()
    val opener = RecordingOpener()
    val coordinator = TileCoordinator(tile, main, background, take, model, notice, opener)

    init {
        if (arm) coordinator.onArmedChanged(true)
    }

    /** Runs the background and then the main queue until both are empty. */
    fun settle() {
        var passes = 0
        while (background.pending > 0 || main.pending > 0) {
            passes += 1
            check(passes <= MAX_PASSES) { "app: the tile rig did not settle after $MAX_PASSES passes" }
            background.runAll()
            main.drain()
        }
    }

    /** A take is recording: the microphone was tapped and the answer applied. */
    fun startRecording() {
        coordinator.onBegin()
        settle()
    }
}
