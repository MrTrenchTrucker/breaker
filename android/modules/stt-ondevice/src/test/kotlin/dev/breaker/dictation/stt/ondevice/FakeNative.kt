package dev.breaker.dictation.stt.ondevice

/*
 * A scripted native engine for the adapter tests. Calls arrive one at a time; the log is
 * read after the call returned. It reads no clock, and loads no library. It writes every
 * call to one ordered log, can be told to throw on the Nth call of any method, and can be
 * told to never stop asking for more decode steps (then it fails by name after a poll
 * limit instead of hanging).
 */

/** One block of audio handed to a stream: its length, the sum of its samples and its rate. */
internal class FedChunk(val length: Int, val sum: Double, val rate: Int)

/**
 * A native recognizer and its streams in one object. A stream is ready for
 * [decodesNeeded] decode calls (each decode call uses one up), or always when
 * [neverReady] is set. After [pollGuard] polls the fake throws an AssertionError that
 * names the missing bound, so an adapter with no step bound fails instead of looping.
 */
internal class ScriptedNative(
    private val decodesNeeded: Int = 0,
    private val text: String = "hello",
    private val neverReady: Boolean = false,
    private val pollGuard: Int = 10_000,
) : NativeStreamingRecognizer {

    private val lines = ArrayList<String>()
    private val counts = HashMap<String, Int>()
    private val failures = HashMap<Pair<String, Int>, Throwable>()
    private val chunks = ArrayList<FedChunk>()

    /** Every call in order, as "name" or "name detail". */
    val log: List<String> get() = lines

    /** Every block of audio fed to any stream, in order. */
    val fed: List<FedChunk> get() = chunks

    /** How many times the method [call] was called, whether or not it threw. */
    fun count(call: String): Int = counts[call] ?: 0

    /** Makes the [nth] call (1-based) of the method [call] throw [error]. The call is logged first. */
    fun failOn(call: String, error: Throwable, nth: Int = 1) {
        failures[call to nth] = error
    }

    private fun hit(call: String, detail: String = "") {
        val n = count(call) + 1
        counts[call] = n
        lines.add(if (detail.isEmpty()) call else "$call $detail")
        val error = failures[call to n]
        if (error != null) throw error
    }

    override fun createStream(): NativeStream {
        hit("createStream")
        return ScriptedNativeStream(this)
    }

    override fun release() {
        hit("release")
    }

    internal fun accept(samples: FloatArray, rate: Int) {
        hit("acceptWaveform", "${samples.size} @$rate")
        chunks.add(FedChunk(samples.size, samples.sumOf { it.toDouble() }, rate))
    }

    internal fun finished() = hit("inputFinished")

    internal fun ready(left: Int): Boolean {
        hit("isReady")
        if (count("isReady") > pollGuard) {
            throw AssertionError("isReady was polled more than $pollGuard times: the adapter has no working step bound")
        }
        return neverReady || left > 0
    }

    internal fun decoded() = hit("decode")

    internal fun textOf(): String {
        hit("text")
        return text
    }

    internal fun streamReleased() = hit("streamRelease")

    internal fun stepsPerStream(): Int = decodesNeeded
}

/** A stream of a [ScriptedNative]; it only forwards to the owner, which logs and scripts. */
internal class ScriptedNativeStream(private val owner: ScriptedNative) : NativeStream {
    private var left = owner.stepsPerStream()

    override fun acceptWaveform(samples: FloatArray, sampleRateHz: Int) = owner.accept(samples, sampleRateHz)

    override fun inputFinished() = owner.finished()

    override fun isReady(): Boolean = owner.ready(left)

    override fun decode() {
        owner.decoded()
        left--
    }

    override fun text(): String = owner.textOf()

    override fun release() = owner.streamReleased()
}

/** A clip of [samples] samples that all read 0.5, so the sum of a fed block tells its length. */
internal fun halfClip(samples: Int): FloatArray = FloatArray(samples) { 0.5f }

/** Runs [block] and returns what it threw (any Throwable); fails by name when it returned. */
internal fun failureOf(label: String, block: () -> Unit): Throwable {
    try {
        block()
    } catch (t: Throwable) {
        return t
    }
    throw AssertionError("$label: expected a failure but the call returned")
}

/** Decodes [pcm] and returns the SherpaTranscriptionException; any other outcome fails by name. */
internal fun decodeFailure(
    label: String,
    recognizer: SherpaRecognizer,
    pcm: FloatArray = halfClip(1_600),
    rate: Int = 16_000,
): SherpaTranscriptionException {
    val thrown = failureOf(label) { recognizer.decode(pcm, rate) }
    if (thrown is SherpaTranscriptionException) return thrown
    throw AssertionError("$label: expected a SherpaTranscriptionException but got $thrown", thrown)
}

/** Decodes [pcm] and returns the transcript; a failure of any kind becomes an AssertionError that names [label]. */
internal fun decodeOk(
    label: String,
    recognizer: SherpaRecognizer,
    pcm: FloatArray = halfClip(1_600),
    rate: Int = 16_000,
): SherpaTranscript {
    try {
        return recognizer.decode(pcm, rate)
    } catch (t: Throwable) {
        throw AssertionError("$label: the decode must succeed but threw $t", t)
    }
}

/** Runs [block]; a failure of any kind becomes an AssertionError that names [label]. */
internal fun mustNotThrow(label: String, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        throw AssertionError("$label: expected no failure but got $t", t)
    }
}
