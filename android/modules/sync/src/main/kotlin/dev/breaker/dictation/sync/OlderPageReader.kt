package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.OlderBound
import dev.breaker.dictation.core.port.OlderResult
import dev.breaker.dictation.core.port.RecordKeys
import dev.breaker.shared.api.TranscriptionRecord
import dev.breaker.shared.api.sync.SyncSource

/** Sends one older-rows request and answers what came back; the app supplies it, as with [PullTransport]. */
fun interface OlderPullTransport {
    /** Send [request] and say what happened. */
    fun fetch(request: OlderPullRequest): PullTransportResult
}

/**
 * One older-rows read. It decodes the page with the pull decoder and opens each row with the pull
 * row reader, then hands back domain values only. It stores nothing and moves no cursor, so it does
 * not use the pull applier, loop, run or cycle.
 */
internal object OlderPageReader {
    /**
     * Reads the page older than [bound] over [transport]. With [keys] a key_version 1+ row is opened
     * through the port; without them it counts as unreadable. A row that cannot be read, fails the
     * [Transcription] guards, or is a delete record is left out and counted in `skipped`.
     */
    fun read(transport: OlderPullTransport, bound: OlderBound, keys: RecordKeys? = null): OlderResult {
        val answer = transport.fetch(OlderPullRequest.forBound(bound))
        if (answer !is PullTransportResult.Answered) return OlderResult.Unavailable
        return when (answer.status) {
            200 -> readPage(answer.body, keys)
            400 -> OlderResult.BadRequest
            401 -> OlderResult.SignedOut
            403 -> OlderResult.NotAllowed
            429 -> OlderResult.Busy(null)
            else -> OlderResult.Unavailable
        }
    }

    private fun readPage(body: String, keys: RecordKeys?): OlderResult {
        val page = try {
            PullPageDecoder.decode(body)
        } catch (failure: PullPageException) {
            return OlderResult.Unavailable
        }
        val next = OlderBound.parse(page.next) ?: return OlderResult.Unavailable
        val rows = mutableListOf<Transcription>()
        var skipped = 0
        for (record in page.items) {
            val row = if (record.deleted) null else rowOf(record, keys)
            if (row == null) skipped++ else rows.add(row)
        }
        if (page.items.isEmpty() && !page.more) return OlderResult.NoneOlder
        return OlderResult.Page(rows, skipped, next, page.more)
    }

    private fun rowOf(record: TranscriptionRecord, keys: RecordKeys?): Transcription? {
        val body = try {
            PulledRowBody.read(record, keys)
        } catch (failure: PulledRowException) {
            return null
        }
        return try {
            Transcription(
                id = record.clientId,
                text = body.text,
                source = sourceOf(body.source),
                model = body.model,
                durationMs = body.durationMs,
                createdAt = body.createdAtMs,
            )
        } catch (failure: IllegalArgumentException) {
            null
        }
    }

    private fun sourceOf(source: SyncSource): TranscriptionSource =
        when (source) {
            SyncSource.LOCAL -> TranscriptionSource.LOCAL
            SyncSource.SERVER -> TranscriptionSource.SERVER
        }
}
