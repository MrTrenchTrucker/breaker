package dev.breaker.dictation.commit.adapter

import android.os.Handler
import android.os.Looper
import dev.breaker.dictation.commit.BlockPoster
import dev.breaker.dictation.commit.HopDeadline
import dev.breaker.dictation.commit.MainThread
import dev.breaker.dictation.commit.PostedMainThread
import kotlinx.coroutines.delay

/**
 * Runs a block on the thread of [looper], which is the main thread in the app.
 *
 * All the waiting logic lives in the pure [PostedMainThread]; this class only
 * supplies the looper pieces: the poster, the check for "already on the main
 * thread", and a deadline of [timeoutMillis].
 *
 * The timeout is a generous safety net for a main thread that is stuck. It is
 * never the thing being measured, so it is far longer than any normal hop.
 */
internal class HandlerMainThread(
    looper: Looper,
    timeoutMillis: Long,
) : MainThread by PostedMainThread(
    HandlerPoster(looper),
    { Looper.myLooper() === looper },
    HopDeadline { delay(timeoutMillis) },
) {

    companion object {
        /** Safety net only: how long a worker waits for the main thread before giving up. */
        const val HOP_TIMEOUT_MILLIS: Long = 5_000L
    }
}

/** Posts a task to the looper. A looper that has stopped refuses it, and the post reports false. */
private class HandlerPoster(looper: Looper) : BlockPoster {

    private val handler: Handler = Handler(looper)

    override fun post(task: Runnable): Boolean = handler.post(task)
}
