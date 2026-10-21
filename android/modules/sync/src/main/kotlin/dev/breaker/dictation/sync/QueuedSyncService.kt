package dev.breaker.dictation.sync

import android.content.Context
import dev.breaker.dictation.core.model.SyncReport
import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.ConnectivityProbe
import dev.breaker.dictation.core.port.SyncService
import dev.breaker.shared.api.SyncPush
import dev.breaker.shared.api.sync.SyncBody
import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.shared.api.sync.SyncBodyException
import dev.breaker.shared.api.sync.SyncSource
import kotlinx.coroutines.sync.Mutex

/**
 * The offline-first queue behind [SyncService] (F13, N12).
 *
 * Every transcription is queued on the phone first and sent later, when the
 * server is reachable. Three guarantees:
 *
 * 1. **Nothing is lost.** [enqueue] returns only after the row has been
 *    committed. A row is removed only after the uploader answered
 *    [UploadOutcome.CONFIRMED]; every other path leaves it queued.
 *
 * 2. **Every push is built the same way.** Each push is built by
 *    [SyncBodyCodec.plainPush] from the stored row, at key_version 0. The body is
 *    the JSON object of the body format (keys v, text, source, model, duration_ms,
 *    created_at), written as padded base64 with no line breaks. Key version 0 has
 *    no cryptography, so the server can read the text. This is the development
 *    form of the sync body (ADR-006).
 *
 * 3. **A retry never duplicates.** A retry sends the same client id (the
 *    transcription id) at the same key version, 0. The server dedupes on the pair
 *    (client id, key version) (ADR-006), so a resend of a row whose first
 *    confirmation was lost is the same push.
 *
 * The queue drains oldest first, in the order it was filled, not by creation
 * time, which can go backwards. A row that cannot be pushed is skipped: it stays
 * queued, the drain goes on to the next row, and each drain that reaches the row
 * counts it in [SyncReport.failed]. A skipped row is never removed. Only one drain
 * runs at a time: a call that finds one running returns at once with an empty
 * report.
 *
 * A drain walks only the rows that were queued when it started. The queue reads
 * the highest seq once, after the server answers, and a row queued later waits for
 * the next drain. An uploader that enqueues therefore cannot keep one drain running.
 *
 * The storage seam is internal, so the class is built only through [create].
 */
class QueuedSyncService internal constructor(
    private val database: SyncQueueDatabase,
    private val uploader: SyncUploader,
    private val probe: ConnectivityProbe,
) : SyncService {

    private val drain = Mutex()

    /**
     * Queue [transcription] for the next push and return once it is committed.
     *
     * The source is stored as its wire text, "local" or "server". A second call
     * with the same transcription id adds nothing: the first copy stays and the new
     * one is dropped. Blank text is queued like any other text. A storage exception
     * propagates unchanged and leaves no partial row.
     */
    override fun enqueue(transcription: Transcription) {
        val source = when (transcription.source) {
            TranscriptionSource.LOCAL -> SyncSource.LOCAL.wire
            TranscriptionSource.SERVER -> SyncSource.SERVER.wire
        }
        database.transaction {
            database.insertIfAbsent(
                clientId = transcription.id,
                text = transcription.text,
                source = source,
                model = transcription.model,
                durationMs = transcription.durationMs,
                createdAtMs = transcription.createdAt,
            )
        }
    }

    /**
     * Send what is queued, oldest first, and report what left the phone.
     *
     * [SyncReport.pushed] counts the rows the uploader confirmed and the queue
     * removed in this call. [SyncReport.failed] counts the rows skipped in this
     * call, plus one when the call stopped on [UploadOutcome.FAILED] or on an
     * uploader that threw. An unreachable server, an empty queue and a busy call
     * report 0 failed. A drain that finds the server unreachable does not walk the
     * rows, so it counts no skipped row either.
     *
     * A row that cannot be pushed is skipped. Its stored source is not local or
     * server, its client id is not a lowercase canonical UUID, or the codec refuses
     * its body. A skipped row is never removed and never ends the drain. Each drain
     * that reaches it counts it again, until a permanent-refusal path exists.
     *
     * Known gap: [UploadOutcome.FAILED] stops the drain and keeps the row at the head
     * of the queue. A row the server refuses for good therefore blocks every row
     * behind it until a permanent-refusal path exists.
     *
     * The drain walks only the rows queued when it started. The highest seq is read
     * once, after the probe answers and before the first row. A row queued during
     * the drain, including one queued from inside the uploader, has a greater seq,
     * is neither sent nor counted, and waits for the next drain. An exception from
     * the probe or from the database propagates, no report is returned, and the drain lock is
     * released. An exception from the uploader counts as [UploadOutcome.FAILED].
     */
    override fun pushPending(): SyncReport {
        if (!drain.tryLock()) return SyncReport(0, 0)
        try {
            if (!probe.isServerReachable()) return SyncReport(0, 0)
            val bound = database.tailSeq()
            var pushed = 0
            var skipped = 0
            var next: QueueEntry? = database.head()
            while (next != null) {
                val entry: QueueEntry = next
                if (entry.seq > bound) break
                val push = buildPush(entry)
                if (push == null) {
                    skipped++
                } else {
                    val outcome = try {
                        uploader.upload(push)
                    } catch (e: Exception) {
                        UploadOutcome.FAILED
                    }
                    when (outcome) {
                        UploadOutcome.CONFIRMED -> {
                            database.transaction { database.remove(entry.clientId) }
                            pushed++
                        }
                        UploadOutcome.UNREACHABLE -> return SyncReport(pushed, skipped)
                        UploadOutcome.FAILED -> return SyncReport(pushed, skipped + 1)
                    }
                }
                next = database.nextAfter(entry.seq)
            }
            return SyncReport(pushed, skipped)
        } finally {
            drain.unlock()
        }
    }

    /**
     * The push for [entry], or null when the entry must be skipped. A skipped
     * entry is not sent, not removed and not counted here.
     */
    private fun buildPush(entry: QueueEntry): SyncPush? {
        val source = SyncSource.fromWire(entry.source) ?: return null
        if (!SyncBodyCodec.isClientId(entry.clientId)) return null
        val body = SyncBody(entry.text, source, entry.model, entry.durationMs, entry.createdAtMs)
        return try {
            SyncBodyCodec.plainPush(entry.clientId, body)
        } catch (e: SyncBodyException) {
            null
        }
    }

    /** How many rows are still queued. */
    fun pendingCount(): Int = database.count()

    /** Release the queue's database handle. */
    fun close() {
        database.close()
    }

    companion object {
        /**
         * The one way to build a [QueuedSyncService] outside this module. It opens
         * the queue's own database file, which is private to the app.
         *
         * Create it once per process and share it: each call opens its own handle on
         * the same file.
         */
        fun create(
            context: Context,
            uploader: SyncUploader,
            probe: ConnectivityProbe,
        ): QueuedSyncService = QueuedSyncService(SqliteSyncQueueDatabase(context), uploader, probe)
    }
}
