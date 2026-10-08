package dev.breaker.dictation.ui.screen.history

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/*
 * The one queue the history model runs its store and clipboard work on.
 *
 * It is a single queue for the whole process and it runs one job at a time, so two
 * history jobs never touch the store together. It is a value, not a worker of its
 * own: the coroutines library chooses the carrier, and nothing here names one.
 */

/** The serial queue every history job runs on, shared by every history view. */
internal object HistoryDispatcher {
    val serial: CoroutineDispatcher by lazy { Dispatchers.IO.limitedParallelism(1) }
}
