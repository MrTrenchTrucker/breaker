package dev.breaker.dictation.audio

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Constants and the gated device that `MicCaptureStartStopRaceTest` and
 * `MicCaptureStartStopRaceGatedOpenTest` read, hoisted out of the class that
 * used to own all four race tests so neither half has to carry the other's
 * fixtures.
 *
 * Every name here is a constant, a KDoc that explains it, or the gated
 * `MicSource` the open() window tests need. Nothing in this file is a test.
 *
 * [JOIN_TIMEOUT_MS] is the 150ms race-timing budget for the losing-stop
 * window. It is deliberately NOT the same thing as the 2s stuck-thread
 * recovery bound in the indicator tests, and the two are kept in separate
 * support objects on purpose: they are different mechanisms measured by
 * different tests, and reconciling them would be a behaviour change hidden
 * inside a move.
 */
internal object RaceTestSupport {

    /**
     * A take that is a whole number of frames, so "every sample arrived" is
     * a statement about the take rather than about where the test stopped
     * reading it.
     */
    const val FULL_TAKE_SAMPLES = 4 * MicCapture.DEFAULT_FRAME_SAMPLES

    /**
     * How long after stop() returns the racing open() is released.
     *
     * Long enough that the open is still in flight when the stop comes back,
     * short enough to keep the suite quick. It is a gate a third thread
     * opens, not a condition any assertion waits on: the assertions about
     * the state at that moment all run before it.
     */
    const val GATE_RELEASE_MS = 200L

    /**
     * How long the third start is watched for reaching the device while the
     * first is still inside its own open.
     *
     * A window that ends on the SECOND open arriving, so a gate that does
     * not hold is caught at once. Long enough that a loaded machine gives
     * the third start a fair chance to be scheduled and prove it CAN get
     * there — which is what makes the absence of a second open a statement
     * about the gate rather than about the thread never being run.
     */
    const val NO_OVERLAP_WINDOW_MS = 750L

    /**
     * How long a start is allowed to wait on the device gate in the
     * overlapping-starts test.
     *
     * Sized against [NO_OVERLAP_WINDOW_MS], not against the suite: the
     * first open is held for the length of that window and released the
     * moment it ends, so the third start's wait on the gate is however long
     * the first took to release plus a scheduling hand-off. Four times the
     * window is generous for that and still short enough that a gate which
     * genuinely gave up would say so inside the test's own bounds rather
     * than being mistaken for a slow machine.
     */
    const val GATE_WAIT_MS = 4 * NO_OVERLAP_WINDOW_MS

    /** The bound a hung open() is held to. */
    const val GIVE_UP_TIMEOUT_MS = 250L

    /** Short enough that a parked dispatcher is given up on promptly. */
    const val JOIN_TIMEOUT_MS = 150L

    /**
     * How long the losing stop() is watched for an early return.
     *
     * A ceiling on the wait, not the wait itself. It MUST be shorter than
     * [JOIN_TIMEOUT_MS], and that is the whole reason it is not a larger
     * number: the winning stop() gives up on its parked dispatcher after
     * joinTimeoutMs and returns, so a window longer than that would be
     * asserting about a teardown that has provably finished — the loser
     * would come home correctly and be failed for it. The two constants
     * are a matched pair and moving one without the other breaks the test.
     */
    const val LOSER_WINDOW_MS = 100L
}
/**
 * A device whose [open] blocks until the test releases it, counting how many
 * opens are inside it at once and recording each one's entry and exit against
 * the thread that made it.
 *
 * Three things live here because they are the same property of the same seam: an
 * open takes real time (tens of milliseconds on a phone, a permission prompt on
 * a cold start), only a caller that can HOLD an open open can put a test inside
 * that window or see whether two opens overlap, and an overlap cannot be checked
 * from outside at all — two starts that both returned "successfully" and a
 * source that reports one open tell a caller nothing about whether the
 * microphone was really shared.
 *
 * The per-thread event log is what makes the order of two opens a fact rather
 * than an inference. A peak count alone says two were open at once and not which
 * one was late; the log says which thread entered when and which left when, so a
 * test can assert that one start's open finished before the next one began, and
 * can put that order in a failure message.
 *
 * The spent script holds the device open rather than reporting a dead one, so a
 * take that ends here is a take that ended because the test stopped it, and
 * [MicCapture.failure] is null for a reason that is not the fixture's.
 */
internal class GatedSource(
    script: FloatArray,
    override val sampleRateHz: Int = 16_000,
    override val channelCount: Int = 1,
) : MicSource {

    private val delegate = FakeMicSource(script = script, holdsOpenWhenScriptSpent = true)
    private val inside = AtomicInteger(0)

    /** Counted down once [open] is under way and waiting. */
    val insideOpen = CountDownLatch(1)

    /** Counted down by the test to let [open] finish. */
    val releaseOpen = CountDownLatch(1)

    val openCalls = AtomicInteger(0)
    val peakOpenConcurrency = AtomicInteger(0)

    private val events = CopyOnWriteArrayList<String>()

    /**
     * "thread entered" / "thread left" for every [open], in the order the device
     * saw them.
     *
     * Deliberately a flat list rather than a map: the claim under test is about
     * ORDER between two threads, and a map keyed by thread would lose the
     * interleaving that is the whole point.
     */
    val openEvents: List<String> get() = events.toList()

    /**
     * True while a thread is inside [open] and blocked on [releaseOpen].
     *
     * Read by a test that has to know the window was still open when it looked,
     * so a window that found nothing to complain about because the first open
     * had already returned cannot pass by saying nothing.
     */
    @Volatile
    var insideOpenInProgress: Boolean = false
        private set

    override fun open() {
        openCalls.incrementAndGet()
        peakOpenConcurrency.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
        insideOpenInProgress = true
        events.add("${Thread.currentThread().name} entered")
        insideOpen.countDown()
        try {
            releaseOpen.await(WAIT_SECONDS, TimeUnit.SECONDS)
        } finally {
            insideOpenInProgress = false
            events.add("${Thread.currentThread().name} left")
            inside.decrementAndGet()
        }
        delegate.open()
    }

    override fun read(buffer: ShortArray, offset: Int, lengthInShorts: Int): Int =
        delegate.read(buffer, offset, lengthInShorts)

    override fun close() = delegate.close()
}
