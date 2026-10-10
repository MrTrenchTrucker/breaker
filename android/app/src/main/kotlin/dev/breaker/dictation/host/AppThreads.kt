package dev.breaker.dictation.host

import android.os.Handler
import android.os.Looper
import dev.breaker.dictation.wiring.Background
import dev.breaker.dictation.wiring.MainPost
import java.util.concurrent.Executors

/**
 * The only file in the app that names a handler, a looper, an executor or a thread. Everything
 * else in the app is thread-free and asks for these two small ports instead, so one text rule can
 * hold the whole app to that.
 *
 * [MainLooperPost] runs a block on the main looper. A block is always queued, even when the caller
 * is already on the main thread, so a post never runs inline inside the caller.
 */
internal class MainLooperPost : MainPost {

    private val handler = Handler(Looper.getMainLooper())

    override fun post(block: () -> Unit) {
        handler.post { block() }
    }
}

/**
 * [SerialBackground] runs blocks one at a time, in the order they were submitted, on one daemon
 * thread called [threadName]. The thread is created by the first submit and lives as long as the
 * process. A block that throws is dropped and the next block still runs.
 */
internal class SerialBackground(threadName: String) : Background {

    private val executor = Executors.newSingleThreadExecutor { work ->
        Thread(work, threadName).apply { isDaemon = true }
    }

    override fun submit(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (e: Exception) {
                // The caller's own guard reports a failure; the worker must stay alive for the next block.
            }
        }
    }
}
