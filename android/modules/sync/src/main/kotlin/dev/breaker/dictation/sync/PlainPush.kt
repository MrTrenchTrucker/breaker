package dev.breaker.dictation.sync

import dev.breaker.shared.api.SyncPush
import dev.breaker.shared.api.sync.SyncBody
import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.shared.api.sync.SyncBodyException
import dev.breaker.shared.api.sync.SyncSource

/**
 * The push for [entry], or null when the entry must be skipped. A skipped
 * entry is not sent, not removed and not counted here.
 */
internal fun buildPush(entry: QueueEntry): SyncPush? {
    val source = SyncSource.fromWire(entry.source) ?: return null
    if (!SyncBodyCodec.isClientId(entry.clientId)) return null
    val body = SyncBody(entry.text, source, entry.model, entry.durationMs, entry.createdAtMs)
    return try {
        SyncBodyCodec.plainPush(entry.clientId, body)
    } catch (e: SyncBodyException) {
        null
    }
}
