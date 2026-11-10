package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.PullSink
import dev.breaker.shared.api.TranscriptionRecord
import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.shared.api.sync.SyncBodyException
import dev.breaker.shared.api.sync.SyncSource

/** Applies one pull result to the PullSink port: deletes always, live rows unless queued,
 * and one prune after a finished full list. PullCycle calls it. The incremental pull
 * that follows a prune is made by PullCycle. */
internal class PullApplier(
    private val sink: PullSink,
    private val queue: QueueProbe,
) {

    /** Applies [result] and returns the counts of what happened. */
    internal fun apply(result: PullRunResult): ApplyReport {
        var stored = 0
        var refusedByTombstone = 0
        var skippedQueued = 0
        var deleted = 0
        var unreadable = 0
        var removedByPrune = 0
        var pruned = false
        val keep = mutableListOf<String>()
        if (result.outcome !is PullOutcome.Ok) {
            return ApplyReport(
                stored, refusedByTombstone, skippedQueued, deleted,
                unreadable, removedByPrune, pruned,
            )
        }
        for (record in result.outcome.records) {
            if (record.deleted) {
                val deletedAtMs = try {
                    SyncBodyCodec.parseCreatedAt(record.storedAt)
                } catch (failure: SyncBodyException) {
                    unreadable++
                    continue
                }
                deleted++
                sink.applyRemoteDelete(record.clientId, deletedAtMs)
            } else {
                keep.add(record.clientId)
                if (queue.isQueued(record.clientId)) {
                    skippedQueued++
                    continue
                }
                val body = try {
                    PulledRowBody.read(record)
                } catch (failure: PulledRowException) {
                    unreadable++
                    continue
                }
                val transcription = try {
                    Transcription(
                        id = record.clientId,
                        text = body.text,
                        source = sourceOf(body.source),
                        model = body.model,
                        durationMs = body.durationMs,
                        createdAt = body.createdAtMs,
                    )
                } catch (failure: IllegalArgumentException) {
                    unreadable++
                    continue
                }
                if (sink.applyRemoteRow(transcription)) {
                    stored++
                } else {
                    refusedByTombstone++
                }
            }
        }
        if (result.fullList) {
            val allKeep = (keep + queue.queuedIds()).toSet()
            removedByPrune = sink.retainOnly(allKeep)
            pruned = true
        }
        return ApplyReport(
            stored, refusedByTombstone, skippedQueued, deleted,
            unreadable, removedByPrune, pruned,
        )
    }

    private fun sourceOf(source: SyncSource): TranscriptionSource =
        when (source) {
            SyncSource.LOCAL -> TranscriptionSource.LOCAL
            SyncSource.SERVER -> TranscriptionSource.SERVER
        }
}
