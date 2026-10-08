package dev.breaker.dictation.audio

import dev.breaker.dictation.core.port.AudioListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue

/**
 * Records what a take hands out and when it is told the take ended, in one
 * ordered list, so a test can see that every frame came before the end call.
 *
 * Frames and the end call are appended from the dispatch thread, so the list is
 * a copy-on-write list and the order in it is the order they happened in.
 * [throwOnEnd] makes the end call throw after it has recorded itself, which is
 * how the containment tests play a misbehaving caller.
 */
internal class TakeEndRecorder(private val throwOnEnd: Throwable? = null) {

    /** One entry per frame (its tag) and one [END_EVENT] per end call, in order. */
    val events = CopyOnWriteArrayList<String>()

    /** The failure each end call was handed, in call order. */
    val endFailures = CopyOnWriteArrayList<Throwable?>()

    /** The name of the thread each end call ran on, in call order. */
    val endThreadNames = CopyOnWriteArrayList<String>()

    private val ends = Semaphore(0)

    /** The callback to give [MicCapture]. */
    val onTakeEnded: (Throwable?) -> Unit = { failure ->
        endThreadNames.add(Thread.currentThread().name)
        endFailures.add(failure)
        events.add(END_EVENT)
        ends.release()
        if (throwOnEnd != null) throw throwOnEnd
    }

    /** How many times the end call has run. */
    val endCount: Int get() = endFailures.size

    /** A frame listener that records each frame under [tag]. */
    fun frames(tag: String = FRAME_EVENT): AudioListener = AudioListener { events.add(tag) }

    /** [events] with runs of the same entry collapsed to one, so a count of frames does not matter. */
    fun runs(): List<String> =
        events.toList().fold(emptyList<String>()) { acc, e -> if (acc.lastOrNull() == e) acc else acc + e }

    /** Waits, bounded, for the next end call, and fails by name if it never comes. */
    fun awaitEnd(what: String) {
        assertTrue(
            "audio: no end call arrived within ${WAIT_SECONDS}s for $what, so the take was never reported over",
            ends.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS),
        )
    }

    companion object {
        const val END_EVENT = "END"
        const val FRAME_EVENT = "frame"
    }
}

/** Waits, bounded, until no capture or dispatch thread of any take is left, and fails by name if one stays. */
internal fun awaitNoSessionThreads(what: String) {
    val deadline = System.currentTimeMillis() + WAIT_SECONDS * 1000
    while (liveSessionThreads().isNotEmpty() && System.currentTimeMillis() < deadline) {
        Thread.sleep(5)
    }
    assertTrue(
        "audio: session threads were still alive ${WAIT_SECONDS}s after $what: " +
            liveSessionThreads().map { it.name },
        liveSessionThreads().isEmpty(),
    )
}
