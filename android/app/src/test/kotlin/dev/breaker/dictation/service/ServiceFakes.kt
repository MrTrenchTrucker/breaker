package dev.breaker.dictation.service

import dev.breaker.dictation.audio.MicSource
import dev.breaker.dictation.audio.MicSourceException

/** A microphone permission that answers a set value, counts the questions, and can throw instead. */
internal class FakePermission(var granted: Boolean = true, var throwsOnAsk: Boolean = false) : MicPermission {
    var asked: Int = 0
        private set

    /** Runs on every question, after it is counted and before the answer; a test uses it to re-enter the controller. */
    var onAsk: () -> Unit = {}

    override fun isRecordAudioGranted(): Boolean {
        asked += 1
        onAsk()
        if (throwsOnAsk) throw IllegalStateException("permission check broke")
        return granted
    }
}

/** A launcher that answers a set result, keeps the order of its calls, and can throw from either call. */
internal class FakeLauncher(
    var result: LaunchResult = LaunchResult.Launched,
    var launchThrows: Boolean = false,
    var haltThrows: Boolean = false,
) : ServiceLauncher {
    val calls: MutableList<String> = ArrayList()

    /** Runs inside every launch, after it is recorded and before it answers; a test uses it to re-enter the controller. */
    var onLaunch: () -> Unit = {}

    /** Runs inside every halt, after it is recorded and before it can throw; a test uses it to look at the controller mid-halt. */
    var onHalt: () -> Unit = {}

    val launches: Int
        get() = calls.count { it == "launch" }

    val halts: Int
        get() = calls.count { it == "halt" }

    override fun launch(): LaunchResult {
        calls.add("launch")
        onLaunch()
        if (launchThrows) throw IllegalStateException("start not allowed")
        return result
    }

    override fun halt() {
        calls.add("halt")
        onHalt()
        if (haltThrows) throw IllegalStateException("stop failed")
    }
}

/** A microphone source that counts calls, answers set values, and can throw from each member. */
internal class FakeMicSource(
    override val sampleRateHz: Int = 16000,
    override val channelCount: Int = 1,
) : MicSource {
    var readResult: Int = 0
    var openError: RuntimeException? = null
    var readError: RuntimeException? = null
    var closeError: RuntimeException? = null
    var opens: Int = 0
        private set
    var reads: Int = 0
        private set
    var closes: Int = 0
        private set

    override fun open() {
        opens += 1
        openError?.let { throw it }
    }

    override fun read(buffer: ShortArray, offset: Int, lengthInShorts: Int): Int {
        reads += 1
        readError?.let { throw it }
        return readResult
    }

    override fun close() {
        closes += 1
        closeError?.let { throw it }
    }

    companion object {
        fun micFailure(): MicSourceException = MicSourceException("the microphone stopped")
    }
}
