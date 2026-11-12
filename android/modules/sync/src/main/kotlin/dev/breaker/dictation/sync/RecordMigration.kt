package dev.breaker.dictation.sync

import dev.breaker.dictation.core.port.RecordKeys
import dev.breaker.shared.api.SyncPush

/**
 * What the migration did so far, as numbers only: never an id, never a text. [retryAfterSeconds] is
 * the Retry-After of the last 429 the migration met, or null.
 */
internal class MigrationCounts(
    val replaced: Int,
    val unconvertible: Int,
    val failed: Int,
    val retryAfterSeconds: Int?,
)

/**
 * Replaces the account's own records that are stored below the port's current key version, one
 * bounded pass after each drain. Each record goes through [RecordKeys.reseal], so the plaintext
 * never reaches this class, and the result is pushed at the same client id by the same uploader
 * the drain uses (the server swaps the record atomically).
 *
 * The listing is a full one made with [PullLoop] and the stored pull cursor is neither read nor
 * written. A pass handles at most [MAX_ATTEMPTS] records, walks them in ascending change order and
 * remembers where it stopped. A complete walk clears [pending] until the key version changes, even
 * when it met records the port could not convert or permanent refusals (400, 413), which are only
 * counted. Only a transient problem keeps it: a failed push, an exception or another status stops
 * the pass and marks the walk, so the walk starts again from the top at its end; an unreachable
 * server, a 429 and a 409 stop the pass and the next drain goes on from the same record.
 * A pass runs only inside the drain lock, so this class needs no lock of its own.
 */
internal class RecordMigration(
    private val transport: PullTransport,
    private val queue: QueueProbe,
    private val uploader: SyncUploader,
    private val keys: RecordKeys,
    private val refused: RefusedKeyVersion,
) {
    private var pending = true
    private var lastVersion = 0
    private var cursor = 0L
    private var walkProblem = false
    private var replaced = 0
    private var unconvertible = 0
    private var failed = 0
    private var retryAfterSeconds: Int? = null

    /** The numbers so far, a fresh copy on every read. */
    val counts: MigrationCounts
        get() = MigrationCounts(replaced, unconvertible, failed, retryAfterSeconds)

    /**
     * Runs one bounded pass. It never throws: anything thrown inside, a listing that fails, the
     * queue, the port or the uploader, ends the pass and leaves the state as it was at the last
     * finished record, so the finished drain keeps its report and the next drain tries again.
     */
    fun pass() {
        try {
            passOnce()
        } catch (t: Throwable) {
        }
    }

    private fun passOnce() {
        val version = keys.currentKeyVersion()
        if (version < 1) return
        if (version != lastVersion) {
            lastVersion = version
            pending = true
            cursor = 0L
            walkProblem = false
        }
        if (!pending) return
        if (refused.refuses(version)) return
        val outcome = PullLoop.run(transport, null)
        if (outcome !is PullOutcome.Ok) return
        var attempts = 0
        var done = cursor
        for (record in outcome.records.sortedBy { it.change }) {
            if (record.change <= cursor) continue
            val recordVersion = record.keyVersion
            val text = record.encrypted
            if (!record.deleted && recordVersion != null && text != null &&
                recordVersion < version && !queue.isQueued(record.clientId)
            ) {
                if (!attempt(record.clientId, recordVersion, text)) {
                    cursor = done
                    return
                }
                attempts++
            }
            done = record.change
            if (attempts == MAX_ATTEMPTS) {
                cursor = done
                return
            }
        }
        if (!walkProblem) pending = false
        cursor = 0L
        walkProblem = false
    }

    /** Re-seals and uploads one record. False means the pass stops at this record. */
    private fun attempt(clientId: String, recordVersion: Int, text: String): Boolean {
        val sealed = keys.reseal(clientId, recordVersion, text)
        if (sealed == null) {
            unconvertible++
            return true
        }
        val outcome = try {
            uploader.upload(SyncPush(clientId, sealed.keyVersion, sealed.encrypted, null))
        } catch (e: PermanentRefusalException) {
            return refusal(e, sealed.keyVersion)
        } catch (e: Exception) {
            UploadOutcome.FAILED
        }
        return when (outcome) {
            UploadOutcome.CONFIRMED -> {
                replaced++
                true
            }
            UploadOutcome.UNREACHABLE -> false
            UploadOutcome.FAILED -> stopFailed()
        }
    }

    /** What a refusal does, as in the drain. False means the pass stops. */
    private fun refusal(e: PermanentRefusalException, sentVersion: Int): Boolean {
        if (PermanentRefusalException.isPermanentRefusal(e.status)) {
            failed++
            return true
        }
        if (e.status == PermanentRefusalException.RATE_LIMITED_429) {
            retryAfterSeconds = e.retryAfterSeconds
            return false
        }
        if (e.status == CONFLICT_409) {
            refused.remember(sentVersion)
            return false
        }
        return stopFailed()
    }

    private fun stopFailed(): Boolean {
        failed++
        walkProblem = true
        return false
    }

    private companion object {
        /** The most records one pass tries, so a drain never runs long. */
        const val MAX_ATTEMPTS = 50
        const val CONFLICT_409 = 409
    }
}
