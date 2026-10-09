package dev.breaker.dictation.audio

import dev.breaker.dictation.core.model.AudioFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * A microphone the test drives: a script of samples, then end of stream.
 *
 * Stands in for [android.media.AudioRecord], which a JVM test cannot open. It
 * hands out the script in the frame sizes the capture asks for, so the pipeline
 * sees the same shape of input a real device produces.
 */
class FakeMicSource(
    script: FloatArray,
    override val sampleRateHz: Int = AudioFormat.SAMPLE_RATE_HZ,
    override val channelCount: Int = 1,
    /** Samples handed over per read. */
    private val samplesPerRead: Int = 1_600,
    /** Artificial delay per read, for testing a slow device. */
    private val readDelayMs: Long = 0,
    /**
     * How many samples the consumer has taken so far, or null for a device
     * that offers audio as fast as it is asked for it.
     *
     * A real microphone cannot outrun its consumer: it produces 16 000
     * samples a second whether or not anybody is listening. A fake that
     * free-runs is not a faster microphone, it is a device that overruns, and
     * the drops that follow say more about how the two threads were scheduled
     * on the day than about whether the capture loses audio — which is what a
     * test asserting a zero drop count is trying to establish. So when this is
     * given, [read] waits until the consumer has caught up to within
     * [PACE_HEADROOM_SAMPLES] of what the device has already handed over, and
     * then offers the next read. There is then always room for it, and a
     * drop means the buffer really could not keep the audio.
     */
    private val pacedBy: (() -> Int)? = null,
    /**
     * Whether a spent script means the device is still waiting for audio.
     *
     * Off by default, and the default is the behaviour most of these tests are
     * written against: a spent script reports nothing more, and a capture
     * concludes from that silence that the device has stopped producing audio.
     * The tests that check what happens when a device dies depend on exactly
     * that conclusion, so this cannot be the default.
     *
     * Turned on, a spent script is not a dead device but a device with nothing
     * YET: the test is the one supplying the audio, and until it stops asking
     * the device is entitled to report that this read produced nothing rather
     * than that it never will again. So [read] waits while the device is still
     * open and reports nothing the moment it is closed.
     *
     * Note what this is not. It does not invent audio — returning silence
     * would be a device making up a take nobody recorded, and every read of
     * it would advance the take by samples the test never scripted. It says
     * nothing either, which is the whole difference: a read that reports
     * nothing leaves the take exactly as long as the device's script said.
     *
     * The timing it buys is not decoration, and this is not dead surface to be
     * tidied away: a capture gives up after 500 empty reads 1 ms apart — about
     * 0.5 s — so without it a test thread that reaches its stop() later than
     * that, merely because the machine was loaded, fails the give-up assertion
     * for a reason that has nothing to do with the code under test.
     */
    private val holdsOpenWhenScriptSpent: Boolean = false,
) : MicSource {

    /** Set to make [open] throw. */
    var openFailure: MicSourceException? = null

    /** Called from [open], so a test can observe the order of events. */
    var onOpen: (() -> Unit)? = null

    /** After this many reads, [read] reports [readErrorCode]. */
    var failAfterReads: Int = Int.MAX_VALUE

    /** The driver error code reported once [failAfterReads] is reached. */
    var readErrorCode: Int = -1

    /** After this many reads, [read] throws the microphone-taken reason. */
    var takenAfterReads: Int = Int.MAX_VALUE

    /** After this many reads, [read] reports that nothing arrived. */
    var stallAfterReads: Int = Int.MAX_VALUE

    /** Set to false by [close]; a closed source returns 0. */
    @Volatile
    var open: Boolean = false
        private set

    /**
     * Counts down once the whole script has been handed over.
     *
     * The take a test scripted is over at that point and not before, and it is
     * the only signal on the device side that says so: [read] returning
     * nothing means the same thing whether the device has run out of audio or
     * is merely between reads, so a test that wants to know the take is
     * complete has to be told. It is counted down on the read that spends the
     * script, so a test that waits on it and then stops has a device that is
     * open, mid-take and holding back only what the pipeline itself is still
     * holding.
     */
    val scriptSpent = CountDownLatch(1)

    private val readCount = AtomicInteger(0)
    private val openCount = AtomicInteger(0)
    private val closeCount = AtomicInteger(0)
    private var offset = 0

    /** The samples handed out, replaceable between takes. */
    private var script: FloatArray = script

    /**
     * Installed on the next [open]. A reopened device starts a new stream, so
     * a test that needs its second take to be tellable from its first gives
     * the second take different audio rather than the same audio again.
     */
    var scriptOnOpen: FloatArray? = null

    val readCalls: Int get() = readCount.get()

    /**
     * How many times [close] has run.
     *
     * Counted rather than read off [open], because a source that is closed
     * twice looks exactly like one closed once: the flag only says the device
     * is shut, and "shut exactly once" is what a teardown has to promise.
     */
    val closeCalls: Int get() = closeCount.get()

    /** How many times [open] has run. */
    val openCalls: Int get() = openCount.get()

    override fun open() {
        openFailure?.let { throw it }
        // A reopened device starts a new stream, so the script starts again.
        // Without the rewind the second take reads past the end of the script
        // and the device looks permanently silent, which says nothing about
        // the capture under test.
        scriptOnOpen?.let { script = it }
        offset = 0
        open = true
        openCount.incrementAndGet()
        onOpen?.invoke()
    }

    override fun read(buffer: ShortArray, offsetInBuffer: Int, lengthInShorts: Int): Int {
        if (!open) return 0
        val reads = readCount.incrementAndGet()
        if (readDelayMs > 0) Thread.sleep(readDelayMs)
        if (reads > failAfterReads) return readErrorCode
        if (reads > takenAfterReads) {
            throw MicSourceException(
                "audio: another app or a call took the microphone",
                reason = MicSourceException.Reason.MICROPHONE_TAKEN,
            )
        }
        if (reads > stallAfterReads) {
            Thread.sleep(1)
            return 0
        }
        if (offset >= script.size) {
            scriptSpent.countDown()
            // A device that is still waiting reports nothing until it is
            // closed. `open` is re-read every pass so a capture that is
            // stopping is not held here, exactly as in the paced wait below.
            if (holdsOpenWhenScriptSpent) {
                while (open) Thread.sleep(PACE_POLL_MS)
            }
            return 0
        }

        val count = minOf(lengthInShorts, samplesPerRead, script.size - offset)
        // The device waits for room before it hands the read over rather than
        // racing the consumer for it. `open` is re-read every pass so a capture
        // that is stopping does not wait for a consumer that has gone home.
        pacedBy?.let { consumerHas ->
            while (open && offset - consumerHas() > PACE_HEADROOM_SAMPLES) {
                Thread.sleep(PACE_POLL_MS)
            }
        }
        for (i in 0 until count) {
            buffer[offsetInBuffer + i] = (script[offset + i] * Short.MAX_VALUE).toInt().toShort()
        }
        offset += count
        return count
    }

    override fun close() {
        open = false
        closeCount.incrementAndGet()
    }

    private companion object {
        /**
         * How far ahead of the consumer the device is willing to run.
         *
         * Comfortably inside the buffer a paced test gives the capture, so a
         * read always has room waiting for it however the two threads happen
         * to be scheduled, and a paced take is short of audio only if the
         * buffer could not hold it.
         */
        const val PACE_HEADROOM_SAMPLES = 16_000

        /** How often a waiting device looks at the consumer's progress. */
        const val PACE_POLL_MS = 1L
    }
}
