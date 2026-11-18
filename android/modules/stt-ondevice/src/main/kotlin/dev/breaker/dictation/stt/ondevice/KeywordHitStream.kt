package dev.breaker.dictation.stt.ondevice

/**
 * One keyword the spotter reported: the keyword text, the sub-word pieces of the hit and the start of each
 * piece in seconds from the start of the stream. [tokens] and [timestampsSeconds] may be empty when the
 * library gives none; [HitNativeStream] then falls back to the keyword text.
 */
internal data class SpotterHit(val keyword: String, val tokens: List<String>, val timestampsSeconds: List<Float>)

/**
 * The spotter's own stream as [HitNativeStream] needs it, in plain Kotlin types, so the hit logic and its tests
 * run without the library. The one implementation that talks to the library is in KeywordSpotterBinding.kt.
 */
internal interface SpotterStream {
    /** Feeds [samples] recorded at [sampleRateHz]. */
    fun acceptWaveform(samples: FloatArray, sampleRateHz: Int)

    /** Tells the spotter that no more audio will come. */
    fun inputFinished()

    /** True while the spotter still has audio it can decode. */
    fun isReady(): Boolean

    /** Decodes the next piece of audio. */
    fun decode()

    /** The keyword the spotter reports now, or null when its keyword is empty. */
    fun hit(): SpotterHit?

    /** Asks the spotter to start looking again after a hit. */
    fun reset()

    /** Frees the native memory of this stream. */
    fun release()
}

/**
 * Turns a [SpotterStream] into the [NativeStream] the word stream drives.
 *
 * After each decode the spotter is asked for its keyword. A non-empty keyword is kept as the latest hit and the
 * spotter is reset once, so the same hit is not found again. [result] answers from the kept hit and never asks
 * the spotter, so it gives the same answer between two hits and before any hit it is empty.
 *
 * It is not known whether the library keeps or clears its keyword after a reset (UNVERIFIED, read from strings
 * only). The code is safe either way: right after the reset the keyword is read once more, and a keyword that is
 * still the same hit is remembered as stale and ignored until the spotter reports no keyword or a different one.
 * A library that clears its keyword makes an identical later hit a new hit. A library that keeps it and then
 * reports an identical hit without ever showing an empty keyword in between cannot be told apart from the stale
 * one, so that second hit is not delivered; the word stream would drop an identical update as well.
 *
 * The final update is meant to repeat the last hit: this class keeps only the last hit and the word stream's
 * final update reads it again, so a final carries only the words of the last hit (after a wake and then a send
 * it carries the send words alone). That is harmless by intent, not by a device measurement: the phrase matcher
 * reports each phrase once per utterance and clears its set of reported phrases only after a final, so the
 * repeated hit is not reported a second time. No device check of this has been run yet.
 *
 * Whether the library restarts its hit times from zero after a reset of the stream, and whether it keeps or
 * clears its result after that reset, is UNVERIFIED (read from strings only, not run on a device). This class
 * passes the times through as the library gives them. If the times do restart, a SECOND hit of one stream would
 * carry a start time that is too small and the send offset computed from it would trim too much of the
 * dictation. The stream is created per take in this build, so the send phrase is normally the FIRST hit of its
 * stream, with no reset before it, and the first hit is not affected. A device check owes the answer; no fix is
 * guessed here and none is built.
 *
 * The stale-hit guard compares the whole hit including its times, so a library that keeps the keyword but
 * rewrites the times after a reset is delivered again as a new hit. The once-per-utterance rule of the phrase
 * matcher covers that case; this has not been run on a device either.
 *
 * A hit with no pieces is built from the keyword words, each as a piece that starts
 * with the word marker, all at the first time the library gave or, when it gave none, at the position of the
 * audio fed so far, which is never earlier than the true start. That position is an approximation, not a
 * measured start.
 *
 * All calls come from the one worker of the word stream, so the state here is not locked.
 */
internal class HitNativeStream(private val spotter: SpotterStream) : NativeStream {

    private var latest: NativeResult = NativeResult("", emptyList(), emptyList())
    private var stale: SpotterHit? = null
    private var fedSamples: Long = 0L
    private var rateHz: Int = DEFAULT_RATE_HZ

    override fun acceptWaveform(samples: FloatArray, sampleRateHz: Int) {
        spotter.acceptWaveform(samples, sampleRateHz)
        fedSamples += samples.size
        if (sampleRateHz > 0) rateHz = sampleRateHz
    }

    override fun inputFinished() {
        spotter.inputFinished()
    }

    override fun isReady(): Boolean = spotter.isReady()

    override fun decode() {
        spotter.decode()
        val now = spotter.hit()?.takeIf { it.keyword.isNotBlank() }
        if (now == null) {
            stale = null
            return
        }
        if (now == stale) return
        latest = resultOf(now, fedSeconds())
        spotter.reset()
        stale = spotter.hit()?.takeIf { it.keyword.isNotBlank() }
    }

    override fun text(): String = latest.text

    override fun result(): NativeResult = latest

    override fun release() {
        spotter.release()
    }

    private fun fedSeconds(): Float = (fedSamples.toDouble() / rateHz.toDouble()).toFloat()

    private fun resultOf(hit: SpotterHit, fallbackSeconds: Float): NativeResult {
        val start = hit.timestampsSeconds.firstOrNull() ?: fallbackSeconds
        if (hit.tokens.isEmpty()) {
            val pieces = hit.keyword.split(WHITESPACE).filter { it.isNotEmpty() }.map { "$WORD_MARKER$it" }
            return NativeResult(hit.keyword, pieces, pieces.map { start })
        }
        val times = ArrayList<Float>(hit.tokens.size)
        for (i in hit.tokens.indices) {
            times.add(hit.timestampsSeconds.getOrNull(i) ?: times.lastOrNull() ?: start)
        }
        return NativeResult(hit.keyword, hit.tokens.toList(), times)
    }

    private companion object {
        /** The rate the word stream feeds at, used only until the first chunk names its own. */
        const val DEFAULT_RATE_HZ = SherpaOnnxRecognizer.SAMPLE_RATE_HZ

        val WHITESPACE = Regex("\\s+")
    }
}
