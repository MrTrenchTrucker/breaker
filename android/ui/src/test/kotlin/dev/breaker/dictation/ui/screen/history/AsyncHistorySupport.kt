package dev.breaker.dictation.ui.screen.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.FakeClipboardSink
import dev.breaker.dictation.ui.testing.FakeHistoryStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/** Holds the store and clipboard jobs until the test runs them, oldest first. */
internal class QueueDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.addLast(block)
    }

    /** Runs the oldest job only. */
    fun runOne() {
        tasks.removeFirst().run()
    }

    /** Runs until the queue is empty, including jobs added while it runs. */
    fun runAll() {
        while (tasks.isNotEmpty()) runOne()
    }
}

/** Holds the draws posted to the main side until the test runs them, in order. */
internal class MainQueue {
    private val posted = mutableListOf<() -> Unit>()

    fun post(task: () -> Unit) {
        posted.add(task)
    }

    /** Runs the posted draws in order, including any posted while they run. */
    fun runAll() {
        while (posted.isNotEmpty()) posted.removeAt(0).invoke()
    }
}

/** A store that records every read and delete into [events], then calls the held store. */
internal class RecordingStore(
    private val inner: FakeHistoryStore,
    private val events: MutableList<String>,
) : HistoryStore {
    override fun save(transcription: Transcription) {
        inner.save(transcription)
    }

    override fun list(limit: Int): List<Transcription> {
        events.add("list:$limit")
        return inner.list(limit)
    }

    override fun delete(id: String): Boolean {
        events.add("delete:$id")
        return inner.delete(id)
    }
}

internal class Scheduled(val delayMs: Long, val work: () -> Unit) {
    var cancelled: Boolean = false
    var ran: Boolean = false
}

/** Holds scheduled work until a test fires it. Nothing here waits on a clock or a thread. */
internal class FakeDelayedWork : DelayedWork {
    val windows: MutableList<Scheduled> = mutableListOf()

    override fun schedule(delayMs: Long, work: () -> Unit): Cancellation {
        val entry = Scheduled(delayMs, work)
        windows.add(entry)
        return Cancellation { entry.cancelled = true }
    }

    /** The windows that are neither cancelled nor run yet. */
    fun live(): List<Scheduled> = windows.filter { !it.cancelled && !it.ran }

    /** Runs [entry] as the scheduler would when its delay is up. A cancelled one does not run. */
    fun fire(entry: Scheduled) {
        if (entry.cancelled || entry.ran) return
        entry.ran = true
        entry.work()
    }
}

/** One store, one queue and one main side, with the first view built on them. */
internal class Rig(rows: List<Transcription>) {
    val queue = QueueDispatcher()
    val events: MutableList<String> = mutableListOf()
    val store = FakeHistoryStore(rows.toMutableList())
    val clipboard = FakeClipboardSink()
    val work = FakeDelayedWork()
    val main = MainQueue()
    val drawn: MutableList<Screen> = mutableListOf()
    private val recording = RecordingStore(store, events)
    val view: AsyncHistory = newView(drawn)

    /** A view over the same store and queue, whose draws go to [into]. */
    fun newView(into: MutableList<Screen>): AsyncHistory = AsyncHistory(
        recording, clipboard, stamp, work, HistoryScreen(), queue, { main.post(it) }, { into.add(it) },
    )

    /** Reads the first page so the model holds the rows, then forgets what that read recorded. */
    fun prime() {
        view.load(); queue.runAll(); main.runAll(); events.clear(); drawn.clear()
    }
}

internal val stamp = TimestampFormat { "at $it" }

internal fun transcription(
    id: String,
    text: String = "words $id",
    source: TranscriptionSource = TranscriptionSource.LOCAL,
) = Transcription(id = id, text = text, source = source, model = "tiny", durationMs = 1000L, createdAt = 1000L)

internal fun intent(action: String, id: String = "") = ScreenIntent.History(action, id)

internal fun Screen.node(id: String): Node? = nodes.flatMap { it.flatten() }.firstOrNull { it.id == id }

internal fun Screen.notice(): String? = (node("history.notice") as? Label)?.text

/** The ids of the row blocks drawn at the top level, in order. */
internal fun Screen.rowIds(): List<String> = nodes.map { it.id }.filter { it.startsWith("history.row.") }
