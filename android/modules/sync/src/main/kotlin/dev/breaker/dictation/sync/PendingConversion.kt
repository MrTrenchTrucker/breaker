package dev.breaker.dictation.sync

import dev.breaker.dictation.core.port.RecordKeys
import dev.breaker.shared.api.SyncPush

/**
 * What the pending conversion did so far, as numbers only: never an id, never a text.
 * [skipped] counts each distinct bad row once. [retryAfterSeconds] is the Retry-After of the last
 * 429 it met, or null.
 */
internal class PendingCounts(
    val converted: Int,
    val unconvertible: Int,
    val failed: Int,
    val skipped: Int,
    val retryAfterSeconds: Int?,
)

/** The list positions of the rows skipped in the last pass that decoded a list. No text, no id. */
internal class PendingSkips(val count: Int, val indices: List<Int>)

/**
 * Converts the owner's still-sealed agent results, one bounded pass after each drain. The list comes
 * from the [PendingSealedTransport]; each row goes through [RecordKeys.convertSealed], so the box
 * key and the plaintext never reach this class, and the result is pushed as a NEW record (client id =
 * the row id, at the key version the port sealed at) by the same uploader the drain uses.
 *
 * A structurally bad list (a refusal of [PendingSealedDecoder]) counts one failure and converts
 * nothing. A row that fails its own checks is skipped, left untouched on the server, and counted
 * once: it is remembered by a fingerprint of its text, never by its id, so it can never hide a good
 * row with the same id. A row whose id waits in the queue is skipped (a local edit wins). A pass
 * tries at most [MAX_ATTEMPTS] rows. A row the port cannot convert, or whose upload is refused for
 * good (400, 413), stays on the server and is counted; its id is remembered and it is not tried
 * again until the key version changes or the process restarts, so such rows at the head of the list
 * cannot starve the rows behind them. Each remembered set holds at most [MAX_MARKED] entries: a bad
 * row met when a set is full is still skipped and counted in that pass, but not remembered, so it is
 * counted again on every later pass; this stops the memory from growing without bound. A pass runs
 * only inside the drain lock.
 */
internal class PendingConversion(
    private val transport: PendingSealedTransport,
    private val queue: QueueProbe,
    private val uploader: SyncUploader,
    private val keys: RecordKeys,
    private val refused: RefusedKeyVersion,
) {
    private var lastVersion = 0
    private val badIds = HashSet<String>()
    private val badFingerprints = HashSet<Long>()
    private var lastSkipped: List<Int> = emptyList()
    private var converted = 0
    private var unconvertible = 0
    private var failed = 0
    private var skipped = 0
    private var retryAfterSeconds: Int? = null

    /** The numbers so far, a fresh copy on every read. */
    val counts: PendingCounts
        get() = PendingCounts(converted, unconvertible, failed, skipped, retryAfterSeconds)

    /** The positions of the rows skipped in the last pass that decoded a list, a fresh copy. */
    val skips: PendingSkips
        get() = PendingSkips(lastSkipped.size, lastSkipped.toList())

    /**
     * Runs one bounded pass. It never throws: anything thrown inside, the transport, the queue, the
     * port or the uploader, ends the pass, so the finished drain keeps its report.
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
            badIds.clear()
            badFingerprints.clear()
        }
        if (refused.refuses(version)) return
        val answer = transport.fetch()
        if (answer !is PullTransportResult.Answered || answer.status != OK_200) return
        val list = try {
            PendingSealedDecoder.decode(answer.body)
        } catch (e: PendingSealedException) {
            failed++
            return
        }
        lastSkipped = list.skipped.map { it.index }
        for (bad in list.skipped) {
            if (bad.fingerprint in badFingerprints) continue
            skipped++
            remember(badFingerprints, bad.fingerprint)
        }
        var attempts = 0
        for (row in list.rows) {
            if (row.id in badIds || queue.isQueued(row.id)) continue
            if (attempts == MAX_ATTEMPTS) return
            attempts++
            if (!attempt(row)) return
        }
    }

    /** Remembers [value] in [set] unless the set already holds [MAX_MARKED] entries. */
    private fun <T> remember(set: HashSet<T>, value: T) {
        if (set.size < MAX_MARKED) set.add(value)
    }

    /** Converts and uploads one row. False means the pass stops at this row. */
    private fun attempt(row: PendingRow): Boolean {
        val bytes = row.sealedBox
        val sealed = try {
            keys.convertSealed(row.id, bytes)
        } finally {
            bytes.fill(0)
        }
        if (sealed == null) {
            unconvertible++
            remember(badIds, row.id)
            return true
        }
        val outcome = try {
            uploader.upload(SyncPush(row.id, sealed.keyVersion, sealed.encrypted, null))
        } catch (e: PermanentRefusalException) {
            return refusal(e, row.id, sealed.keyVersion)
        } catch (e: Exception) {
            UploadOutcome.FAILED
        }
        return when (outcome) {
            UploadOutcome.CONFIRMED -> {
                converted++
                true
            }
            UploadOutcome.UNREACHABLE -> false
            UploadOutcome.FAILED -> stopFailed()
        }
    }

    /** What a refusal does, as in the drain. False means the pass stops. */
    private fun refusal(e: PermanentRefusalException, id: String, sentVersion: Int): Boolean {
        if (PermanentRefusalException.isPermanentRefusal(e.status)) {
            failed++
            remember(badIds, id)
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
        return false
    }

    private companion object {
        /** The most rows one pass tries, so a drain never runs long. */
        const val MAX_ATTEMPTS = 50

        /** The most entries each remembered set holds. */
        const val MAX_MARKED = 10000
        const val OK_200 = 200
        const val CONFLICT_409 = 409
    }
}
