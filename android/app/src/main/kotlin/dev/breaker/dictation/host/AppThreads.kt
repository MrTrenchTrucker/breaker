package dev.breaker.dictation.host

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import dev.breaker.dictation.wiring.Background
import dev.breaker.dictation.wiring.DelayedPost
import dev.breaker.dictation.wiring.MainPost
import dev.breaker.dictation.wiring.PostHandle
import java.util.concurrent.Executors

/**
 * The only file in the app that names a handler, a looper, an executor or a thread. Everything
 * else in the app is thread-free and asks for these two small ports instead, so one text rule can
 * hold the whole app to that.
 *
 * [MainLooperPost] runs a block on the main looper. A block is always queued, even when the caller
 * is already on the main thread, so a post never runs inline inside the caller. It also queues a
 * block to run after a delay, on the same handler, and takes that block back when it is cancelled.
 */
internal class MainLooperPost : MainPost, DelayedPost {

    private val handler = Handler(Looper.getMainLooper())

    override fun post(block: () -> Unit) {
        handler.post { block() }
    }

    override fun postDelayed(delayMs: Long, block: () -> Unit): PostHandle {
        val task = Runnable { block() }
        handler.postDelayed(task, delayMs)
        return object : PostHandle {
            override fun cancel() {
                handler.removeCallbacks(task)
            }
        }
    }

    /** Registers [listener] with [manager] so that its callbacks run on the main looper. */
    fun registerOnMain(manager: DisplayManager, listener: DisplayManager.DisplayListener) {
        manager.registerDisplayListener(listener, handler)
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

/**
 * [OneShotBackground] starts one new daemon thread called [threadName] for every submitted block and
 * returns at once; it never runs the block on the caller. A block that throws is dropped. A thread
 * that cannot be started is not hidden: [submit] throws, so the caller can undo what it set up.
 */
internal class OneShotBackground(private val threadName: String = "breaker-signin-exchange") : Background {

    override fun submit(block: () -> Unit) {
        val work = Runnable {
            try {
                block()
            } catch (e: Exception) {
                // The caller's own guard reports a failure; nothing else waits on this thread.
            }
        }
        val worker = Thread(work, threadName)
        worker.isDaemon = true
        worker.start()
    }
}
