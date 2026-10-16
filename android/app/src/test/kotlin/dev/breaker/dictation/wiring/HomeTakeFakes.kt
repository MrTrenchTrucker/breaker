package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.CommitRequest
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.CommitOutcomeResult
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.port.TextCommitter
import dev.breaker.dictation.core.usecase.SendResult

/**
 * The fakes the Home take adapter tests share: a [TakePort], a [TextCommitter], a
 * [HistoryStore], a [TakenClipboard], a [Background] and a [MainPost], each recording
 * what it was asked to do.
 *
 * They are declared once here rather than at the bottom of each adapter test, so the
 * two tests drive the same ports and neither carries a copy of the other's fakes.
 * [BeginResult] and [FinishResult] are this package's own, as are [TakePort],
 * [TakenClipboard], [Background] and [MainPost], so none of them is imported.
 */

/** A [TakePort] that records every call in the shared log and answers with set results. */
internal class FakeHomeTake(
    private val committer: TextCommitter,
    private val log: MutableList<String>,
) : TakePort {
    var beginResult: BeginResult = BeginResult.Recording
    var finishResult: FinishResult = FinishResult.ReadyToSend(SAMPLE)
    var finishThrows: Boolean = false
    var beginCount: Int = 0
        private set
    var finishCount: Int = 0
        private set
    var sendCount: Int = 0
        private set
    var cancelCount: Int = 0
        private set
    var resetCount: Int = 0
        private set
    private var session: DictationState = DictationState.IDLE

    override val sessionState: DictationState get() = session

    override fun begin(): BeginResult {
        log.add("begin")
        beginCount += 1
        if (beginResult == BeginResult.Recording) session = DictationState.RECORDING
        return beginResult
    }

    override fun finish(): FinishResult {
        log.add("finish")
        finishCount += 1
        if (finishThrows) throw IllegalStateException("finish broke")
        session = DictationState.SENDING
        return finishResult
    }

    override fun send(): SendResult? {
        log.add("send")
        sendCount += 1
        committer.commit(CommitRequest("the words"))
        return null
    }

    override fun cancel() {
        log.add("cancel")
        cancelCount += 1
    }

    override fun resetAfterTaken() {
        log.add("resetAfterTaken")
        resetCount += 1
        session = DictationState.IDLE
    }
}

/** A [TextCommitter] that counts its calls; the Home take must never reach it. */
internal class CountingCommitter : TextCommitter {
    var commits: Int = 0
        private set

    override fun commit(request: CommitRequest): CommitOutcomeResult {
        commits += 1
        return CommitOutcomeResult(CommitOutcome.COMMITTED)
    }
}

/**
 * A [HistoryStore] in memory. Each save is appended to the shared order list it is given, and the
 * store keeps its own list of saves in [calls].
 */
internal class InMemoryHistoryStore(private val order: MutableList<String> = ArrayList()) : HistoryStore {
    val calls: MutableList<String> = ArrayList()
    val saved: MutableList<Transcription> = ArrayList()

    override fun save(transcription: Transcription) {
        order.add("save")
        calls.add("save")
        saved.add(transcription)
    }

    override fun list(limit: Int): List<Transcription> = emptyList()

    override fun delete(id: String): Boolean = true
}

/** A [TakenClipboard] that counts its copies. */
internal class FakeTakenClipboard : TakenClipboard {
    var copies: Int = 0
        private set

    override fun copy(text: String) {
        copies += 1
    }
}

/** Runs submitted blocks at once, in order, on the test thread. */
internal class FakeBackground : Background {
    override fun submit(block: () -> Unit) = block()
}

/** Runs posted blocks at once, in order, on the test thread. */
internal class FakeMainPost : MainPost {
    override fun post(block: () -> Unit) = block()
}

/** The row a [FakeHomeTake] answers [FinishResult.ReadyToSend] with. */
internal val SAMPLE: Transcription = Transcription(
    id = "t-1",
    text = "hello world",
    source = TranscriptionSource.LOCAL,
    model = "test-model",
    durationMs = 1_000L,
    createdAt = 1_000L,
)

/** The same row with no text: the blank take the settle refuses to save. */
internal val BLANK: Transcription = SAMPLE.copy(text = "")
