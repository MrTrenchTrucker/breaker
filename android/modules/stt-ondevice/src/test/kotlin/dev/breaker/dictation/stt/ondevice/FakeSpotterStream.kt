package dev.breaker.dictation.stt.ondevice

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

/*
 * A scripted spotter stream for the keyword tests. It reads no clock and loads no library. Each decode call
 * takes the next entry of the script: a hit entry becomes the keyword the spotter reports, a null entry leaves
 * what is reported as it is, as a spotter that found nothing new does. What a reset does to the reported keyword
 * is chosen by the test, because the library's own behaviour is not known.
 */

/** What the fake spotter does to the keyword it reports when it is reset. */
internal enum class AfterReset {
    /** The keyword is gone after the reset. */
    CLEARS,

    /** The keyword is still reported after the reset, until a later entry replaces it. */
    KEEPS,
}

/** The word marker as text, for building scripted pieces. */
internal val SPOT_MARK: String = "$WORD_MARKER"

/** The wake phrase as the spotter would report it: pieces and times in seconds (all exact in binary). */
internal val WAKE_HIT: SpotterHit = SpotterHit(
    keyword = "BREAKER BREAKER",
    tokens = listOf("${SPOT_MARK}B", "RE", "A", "K", "ER", "${SPOT_MARK}B", "RE", "A", "K", "ER"),
    timestampsSeconds = listOf(0.5f, 0.5625f, 0.625f, 0.6875f, 0.75f, 1.0f, 1.0625f, 1.125f, 1.1875f, 1.25f),
)

/** The send phrase with the apostrophe pieces, as the spotter would report it. */
internal val SEND_HIT: SpotterHit = SpotterHit(
    keyword = "AND I'M GONE",
    tokens = listOf("${SPOT_MARK}AND", "${SPOT_MARK}I", "'", "M", "${SPOT_MARK}GO", "NE"),
    timestampsSeconds = listOf(2.0f, 2.25f, 2.3125f, 2.375f, 2.5f, 2.5625f),
)

internal class FakeSpotterStream(
    private val script: List<SpotterHit?>,
    private val afterReset: AfterReset = AfterReset.CLEARS,
    private val failDecodeOn: Int = 0,
) : SpotterStream {

    private val log = CopyOnWriteArrayList<String>()
    private val acceptedSizes = CopyOnWriteArrayList<Int>()
    private val pending = AtomicInteger(0)
    private val decodes = AtomicInteger(0)

    @Volatile
    private var current: SpotterHit? = null

    /** One signal per decode call, sent as the call is entered, so a test can wait for the Nth decode. */
    val decoded = Channel<Unit>(Channel.UNLIMITED)

    /** Completed when the stream is released. */
    val released = CompletableDeferred<Unit>()

    /** Every call in order, by name. */
    val calls: List<String> get() = log.toList()

    /** The size of every block accepted, in order. */
    val sizes: List<Int> get() = acceptedSizes.toList()

    /** How many times the call [name] was made. */
    fun count(name: String): Int = log.count { it == name }

    override fun acceptWaveform(samples: FloatArray, sampleRateHz: Int) {
        log.add("acceptWaveform")
        acceptedSizes.add(samples.size)
        pending.incrementAndGet()
    }

    override fun inputFinished() {
        log.add("inputFinished")
    }

    override fun isReady(): Boolean = pending.get() > 0

    override fun decode() {
        log.add("decode")
        val n = decodes.incrementAndGet()
        pending.decrementAndGet()
        decoded.trySend(Unit)
        if (n == failDecodeOn) throw IllegalStateException("the fake spotter was told to fail on this decode")
        script.getOrNull(n - 1)?.let { current = it }
    }

    override fun hit(): SpotterHit? = current

    override fun reset() {
        log.add("reset")
        if (afterReset == AfterReset.CLEARS) current = null
    }

    override fun release() {
        log.add("release")
        released.complete(Unit)
    }
}
