package dev.breaker.dictation.stt.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * How one watchdog call ended.
 *
 * [Finished] carries the work's value, [Failed] the exception the work threw. [Expired] carries
 * nothing: once the deadline has won, no value the abandoned work produces later is ever returned.
 */
internal sealed interface WatchdogOutcome<out T> {
    /** The work returned [value] before the deadline. */
    class Finished<T>(val value: T) : WatchdogOutcome<T>

    /** The work threw [error] before the deadline. */
    class Failed(val error: Throwable) : WatchdogOutcome<Nothing>

    /** The deadline passed, or the budget was already empty. The exchange has been aborted. */
    object Expired : WatchdogOutcome<Nothing>
}

/**
 * Runs one blocking call under a deadline and gives control back at the deadline.
 *
 * The work runs on [io]. The caller waits for whichever comes first: the work's result or the
 * deadline from [timer]. At the deadline the exchange is aborted, the work is cancelled and the
 * caller gets [WatchdogOutcome.Expired], even if the work never returns. The class keeps no
 * mutable state, so one instance can serve any number of calls.
 */
internal class CallWatchdog(
    private val timer: DeadlineTimer,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Runs [work] for at most [budgetMs] milliseconds. A budget of zero or less aborts [abort]
     * and returns [WatchdogOutcome.Expired] without starting the work.
     */
    suspend fun <T> run(budgetMs: Long, abort: AbortHandle, work: () -> T): WatchdogOutcome<T> {
        if (budgetMs <= 0L) {
            abort.abort()
            return WatchdogOutcome.Expired
        }
        // REASON: a blocking socket call cannot be cancelled, so the work runs in a scope this class
        // owns and is left behind at the deadline; the caller must get control back at the deadline.
        val owned = CoroutineScope(SupervisorJob() + io)
        return try {
            val worker = owned.async { work() }
            val deadline = owned.launch { timer.await(budgetMs) }
            select<WatchdogOutcome<T>> {
                worker.onAwait { value -> WatchdogOutcome.Finished(value) }
                deadline.onJoin {
                    abort.abort()
                    worker.cancel()
                    WatchdogOutcome.Expired
                }
            }
        } catch (e: CancellationException) {
            abort.abort()
            throw e
        } catch (e: Exception) {
            WatchdogOutcome.Failed(e)
        } finally {
            owned.cancel()
        }
    }
}
