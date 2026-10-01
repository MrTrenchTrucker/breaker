package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.SyncReport
import dev.breaker.dictation.core.model.Transcription

/**
 * Pushes transcriptions to the Local Server.
 *
 * Implemented by the sync module. Every transcription is pushed when the
 * server is reachable — including the ones the phone transcribed on its own —
 * and the push is idempotent: a retry after a failure never leaves a duplicate
 * on the server.
 */
interface SyncService {
    /**
     * Push everything still pending and report what left the phone. Safe to
     * call when the server is unreachable: the queue stays pending.
     */
    fun pushPending(): SyncReport

    /** Queue [transcription] for the next push. */
    fun enqueue(transcription: Transcription)
}
