package dev.breaker.dictation.wiring

import dev.breaker.dictation.BreakerCompositionRoot
import dev.breaker.dictation.audio.MicSource
import dev.breaker.dictation.audio.MicSourceException
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.core.port.SttEngine
import dev.breaker.dictation.service.DictationServiceController
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The loopback address, built from bytes so that no address text is written in the source. */
internal val serverLinkLoopbackAddress: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

/** A service controller over a granted permission and a counting launcher, armed as the app arms it at start. */
internal fun serverLinkArmed(): DictationServiceController =
    DictationServiceController(SwitchPermission(true), RecordingLauncher()).also { it.adopt() }

/** Begins one take on the root's runner, waits for [awaitSecondRead] to return, then finishes the take. */
internal fun serverLinkOneTake(root: BreakerCompositionRoot, awaitSecondRead: () -> Unit): FinishResult {
    val runner = root.dictation.runner
    check(runner.begin() == BeginResult.Recording) { "app: the take did not start" }
    awaitSecondRead()
    return runner.finish()
}

/** A fake engine that counts its calls, keeps the thread of its first call and answers [result]. */
internal class ServerLinkEngine(
    private val result: SttResult = SttResult.Success("from the loopback test"),
) : SttEngine {
    val firstCallThread: CompletableDeferred<Thread> = CompletableDeferred()
    private val count = AtomicInteger()

    val calls: Int
        get() = count.get()

    override fun transcribe(request: SttRequest): SttResult {
        if (count.incrementAndGet() == 1) firstCallThread.complete(Thread.currentThread())
        return result
    }
}

/** A fake engine that reads its key through the provider on every call and keeps each value it was given. */
internal class ServerLinkKeyEngine(private val key: () -> String?) : SttEngine {
    val seen: MutableList<String?> = ArrayList()

    override fun transcribe(request: SttRequest): SttResult {
        seen.add(key())
        return SttResult.Success("seen")
    }
}

/** A main-thread stand-in that runs each block at once, on the thread that posted it, so the take's answers come back in order. */
internal class ServerLinkInlineMain : MainPost {
    override fun post(block: () -> Unit) {
        block()
    }
}

/**
 * A loopback listener that never waits. The connections a probe made stay in the accept queue; [pending]
 * takes whatever is already waiting and returns at once, so no connection is ever a wait.
 */
internal class ServerLinkListener : AutoCloseable {
    private val channel: ServerSocketChannel = ServerSocketChannel.open()

    init {
        channel.configureBlocking(false)
        channel.bind(InetSocketAddress(serverLinkLoopbackAddress, 0))
    }

    /** The port the system chose for the listener. */
    val port: Int = (channel.localAddress as InetSocketAddress).port

    /** Closes every connection already waiting and returns how many there were. */
    fun pending(): Int {
        var count = 0
        while (true) {
            val connection = channel.accept() ?: break
            connection.close()
            count += 1
        }
        return count
    }

    override fun close() {
        channel.close()
    }
}

/**
 * A microphone that serves [takes] takes in turn: each open starts the next one. In a take the first read
 * returns one block of sound; the second read completes [enteredTake] for that take and waits until close
 * completes its gate. Any later read of the same take returns the end code at once. A wait that never
 * ends fails after five seconds.
 */
internal class ServerLinkRepeatMic(takes: Int) : MicSource {
    private val entered: List<CompletableDeferred<Unit>> = List(takes) { CompletableDeferred<Unit>() }
    private val gates: List<CompletableDeferred<Unit>> = List(takes) { CompletableDeferred<Unit>() }
    private val opened = AtomicInteger()
    private val readsInTake = AtomicInteger()

    /** Completes when the second read of take [index] starts. */
    fun enteredTake(index: Int): CompletableDeferred<Unit> = entered[index]

    override val sampleRateHz: Int
        get() = 16_000

    override val channelCount: Int
        get() = 1

    override fun open() {
        opened.incrementAndGet()
        readsInTake.set(0)
    }

    override fun read(buffer: ShortArray, offset: Int, lengthInShorts: Int): Int {
        val take: Int = opened.get() - 1
        val number: Int = readsInTake.incrementAndGet()
        if (number == 1 && !gates[take].isCompleted) {
            for (i in 0 until lengthInShorts) {
                buffer[offset + i] = 1_000
            }
            return lengthInShorts
        }
        if (number != 2) return 0
        entered[take].complete(Unit)
        try {
            runBlocking { withTimeout(5_000L) { gates[take].await() } }
        } catch (e: TimeoutCancellationException) {
            throw MicSourceException("the test microphone was never closed")
        }
        return 0
    }

    override fun close() {
        gates[opened.get() - 1].complete(Unit)
    }
}
