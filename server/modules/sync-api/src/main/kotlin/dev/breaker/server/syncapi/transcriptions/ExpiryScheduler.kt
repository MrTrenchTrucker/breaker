package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.accounts.AccountStore
import java.time.Clock
import java.time.Duration
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

// The daily expiry scheduler for transcriptions: live rows only, by stored_at_ms, never raises
// purged_through, new change numbers via markDeleted, PrevKeyRule once per batch. One batch is one
// transaction through TranscriptionStore.expireBatch, so a long run never holds the database for a
// whole account. A failing account is caught and logged by class name, and never stops the schedule.
internal object ExpiryScheduler {

    // Start after the server has settled; once a day thereafter.
    internal val START_DELAY: Duration = Duration.ofHours(1)
    internal val INTERVAL: Duration = Duration.ofHours(24)

    // At most this many rows per batch.
    internal const val BATCH_CAP: Int = 500

    // At most this many batches per account per run. With BATCH_CAP = 500 this bounds one account
    // to 5_000 expired rows per run; the next day's run finishes the rest.
    internal const val MAX_BATCHES_PER_ACCOUNT_PER_RUN: Int = 10

    private val logger: Logger = Logger.getLogger("dev.breaker.server.syncapi.expiry")

    /**
     * Waits [startDelay], then runs for ever: one run, then [pause] for [interval]. [clock] and [pause]
     * are seams for tests: nowMs is read from the clock at each batch, and pause replaces a real delay
     * so no test waits a real day. [batchCap] and [maxBatches] default to the constants. A
     * CancellationException propagates, so a cancelled sweep ends the loop.
     */
    internal suspend fun runExpiryScheduler(
        store: TranscriptionStore,
        accounts: AccountStore,
        clock: Clock = Clock.systemUTC(),
        interval: Duration = INTERVAL,
        startDelay: Duration = START_DELAY,
        pause: suspend (Duration) -> Unit = { delay(it.toMillis()) },
        batchCap: Int = BATCH_CAP,
        maxBatches: Int = MAX_BATCHES_PER_ACCOUNT_PER_RUN,
    ) {
        pause(startDelay)
        while (true) {
            runOnce(store, accounts, clock, batchCap, maxBatches)
            pause(interval)
        }
    }

    // One run over every account. A failure to list the accounts ends this run only; a failure on
    // one account ends that account's turn only. The log line holds the class name, never the message:
    // a store failure message can carry a path or a row value.
    internal suspend fun runOnce(
        store: TranscriptionStore,
        accounts: AccountStore,
        clock: Clock,
        batchCap: Int,
        maxBatches: Int,
    ) {
        val accountIds = try {
            accounts.accountIds()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            logFailure(failure)
            return
        }
        for (accountId in accountIds) {
            try {
                expireOneAccount(store, accountId, clock, batchCap, maxBatches)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                logFailure(failure)
            }
        }
    }

    private fun logFailure(failure: Exception) {
        val name = failure::class.simpleName ?: "Exception"
        logger.warning("expiry scheduler: $name")
    }

    // Batches for one account: repeat while a batch comes back full, stop on a short one or at the bound.
    private suspend fun expireOneAccount(
        store: TranscriptionStore,
        accountId: Long,
        clock: Clock,
        batchCap: Int,
        maxBatches: Int,
    ) {
        var batches = 0
        while (batches < maxBatches) {
            val marked = store.expireBatch(
                accountId = accountId,
                ttlMs = TranscriptionPurge.RETENTION_TTL_MS,
                nowMs = clock.millis(),
                batchCap = batchCap,
            )
            batches++
            if (marked < batchCap) break
        }
    }
}
