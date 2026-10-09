package dev.breaker.dictation.wiring

import dev.breaker.dictation.audio.MicSource
import dev.breaker.dictation.audio.MicSourceException
import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.core.port.SttEngine
import dev.breaker.dictation.service.DictationServiceController
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * A microphone source for the real capture: the first read returns one block of sound, the second
 * read announces itself and waits until the source is closed, so a test can stop the capture at a
 * known point. [openError] and [readError] make it fail; the failure is a [MicSourceException].
 * [readCode], when set, is returned by every read instead (a negative code is a device failure).
 * [firstReadThread] completes with the thread that made the first read, the capture thread.
 */
internal class ScriptedMic : MicSource {
    var openError: MicSourceException? = null
    var readError: MicSourceException? = null
    var readCode: Int? = null
    private val readCount = AtomicInteger()
    private val timedOutFlag = AtomicBoolean(false)
    val firstReadThread = CompletableDeferred<Thread>()
    val secondReadEntered = CompletableDeferred<Unit>()
    val closed = CompletableDeferred<Unit>()

    /** True when a read waited for a close that never came. */
    val timedOut: Boolean
        get() = timedOutFlag.get()

    override val sampleRateHz: Int
        get() = 16_000

    override val channelCount: Int
        get() = 1

    override fun open() {
        openError?.let { throw it }
    }

    override fun read(buffer: ShortArray, offset: Int, lengthInShorts: Int): Int {
        val number = readCount.incrementAndGet()
        firstReadThread.complete(Thread.currentThread())
        readError?.let { throw it }
        readCode?.let { return it }
        if (number == 1) {
            for (i in 0 until lengthInShorts) {
                buffer[offset + i] = 1_000
            }
            return lengthInShorts
        }
        secondReadEntered.complete(Unit)
        try {
            runBlocking { withTimeout(5_000L) { closed.await() } }
        } catch (e: TimeoutCancellationException) {
            timedOutFlag.set(true)
            throw MicSourceException("the test microphone was never closed")
        }
        return 0
    }

    override fun close() {
        closed.complete(Unit)
    }
}

/** The component over fakes and a scripted microphone, run through the real capture. */
internal class Built(
    val mic: ScriptedMic = ScriptedMic(),
    mode: SttMode = SttMode.LOCAL,
    localEngine: SttEngine = FakeSttEngine(SttResult.Success("hello world")),
    serverEngine: SttEngine = FakeSttEngine(SttResult.Success("from the server")),
    armed: Boolean = true,
    onTakeEnded: () -> Unit = {},
) {
    val launcher = RecordingLauncher()
    val controller = DictationServiceController(SwitchPermission(true), launcher)
    val localFormatter = CountingFormatter()
    val serverFormatter = CountingFormatter()
    val history = RecordingHistoryStore()
    val component = DictationComponent(
        settings = FakeSettingsStore(AppSettings(mode = mode)),
        history = history,
        clock = fixedClock,
        ids = SequenceIds(),
        probe = FakeProbe(),
        localEngine = localEngine,
        serverEngine = serverEngine,
        localFormatter = localFormatter,
        serverFormatter = serverFormatter,
        wavEncoder = FixedWavEncoder(),
        committer = FakeCommitter(),
        micSource = mic,
        controller = controller,
        onTakeEnded = onTakeEnded,
    )
    val runner = component.runner

    init {
        if (armed) controller.adopt()
    }

    /** Starts listening and waits until the microphone has handed over its first block of sound. */
    fun listen() {
        assertEquals("app: the scripted capture should start", BeginResult.Recording, runner.begin())
        awaitBounded("the capture to read twice", mic.secondReadEntered)
    }

    /** Waits until the capture thread has ended by itself, so every report it made has been made. */
    fun awaitCaptureThreadEnd() {
        val thread = awaitBounded("the capture thread to read", mic.firstReadThread)
        thread.join(5_000L)
        if (thread.isAlive) throw AssertionError("app: timed out waiting for the capture thread to end")
    }

    /** Checks the state after the capture ended by itself, then pays the owed stop with a cancel. */
    fun assertEndedBySelfThenPayStop() {
        awaitCaptureThreadEnd()
        assertEquals("app: a failed microphone should leave the session idle", DictationState.IDLE, runner.sessionState)
        assertTrue("app: a failed microphone must leave the service armed", controller.isArmed)
        assertEquals("app: a failed microphone must not halt the service", 0, launcher.halts)
        assertTrue("app: a failed microphone should be released by its own report before the owed stop is paid", mic.closed.isCompleted)
        runner.cancel()
        awaitBounded("the owed stop to close the microphone", mic.closed)
        assertEquals("app: paying the owed stop must not halt the service", 0, launcher.halts)
        assertMicWasStopped()
        component.close()
    }

    fun assertMicWasStopped() = assertFalse("app: the scripted microphone waited for a close that never came", mic.timedOut)
}
