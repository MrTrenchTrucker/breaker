package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SyncReport
import dev.breaker.dictation.core.port.RecordKeys
import dev.breaker.shared.api.SyncPush
import dev.breaker.shared.api.sync.SyncBody
import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.shared.api.sync.SyncBodyException
import dev.breaker.shared.api.sync.SyncSource

/*
 * The push side of the RecordKeys port, kept out of QueuedSyncService so that file stays small.
 * Nothing here imports a crypto type: the port hides the key, the nonce and the associated data.
 */

/**
 * The report of a drain that stops because the port holds no keys (the key version is below 1, or
 * the port could not seal). The rows stay queued, nothing is pushed in plain form, and the drain
 * counts ONE failure on top of the [skipped] rows. This is the one place a later ruling would
 * change. It calls no uploader.
 */
internal fun stopBecausePortHasNoKeys(
    pushed: Int,
    skipped: Int,
    parked: Int,
    retryAfterSeconds: Int?,
): SyncReport = SyncReport(pushed, skipped + 1, parked, retryAfterSeconds)

/**
 * The report of a drain that stops because the server refused the key version the port still
 * reports (a 409 earlier). Same shape as [stopBecausePortHasNoKeys]; no seal and no upload.
 */
internal fun stopBecauseKeyVersionRefused(
    pushed: Int,
    skipped: Int,
    parked: Int,
    retryAfterSeconds: Int?,
): SyncReport = SyncReport(pushed, skipped + 1, parked, retryAfterSeconds)

/**
 * The key version the server refused with a 409, kept in memory for one service instance. The
 * service touches it only while it holds the drain lock, so it needs no lock of its own.
 */
internal class RefusedKeyVersion {
    private var refused: Int? = null

    /** Remember that the server refused a push made at [version]. */
    fun remember(version: Int) {
        refused = version
    }

    /** Forget the refusal, after a confirmed push. */
    fun clear() {
        refused = null
    }

    /**
     * True while the port still reports the refused version. When it reports another version
     * the memory is cleared and the answer is false.
     */
    fun refuses(current: Int): Boolean {
        val memory = refused ?: return false
        if (memory == current) return true
        refused = null
        return false
    }
}

/** What building one port push gave: a row to skip, a port without keys, or the push itself. */
internal sealed class PortPush {
    /** The row cannot be pushed (bad source, bad client id or a body the codec refuses). */
    object Skip : PortPush()

    /** The port could not seal the body. */
    object NoKeys : PortPush()

    /** The push, sealed by the port. */
    class Ready(val push: SyncPush) : PortPush()
}

/**
 * Builds the push of [entry] through [keys]. The body bytes are the R1 body of the row, sealed by
 * the port and zeroed right after the call. The wire text comes from the port, not from here.
 */
internal fun buildPortPush(keys: RecordKeys, entry: QueueEntry): PortPush {
    val source = SyncSource.fromWire(entry.source) ?: return PortPush.Skip
    if (!SyncBodyCodec.isClientId(entry.clientId)) return PortPush.Skip
    val body = SyncBody(entry.text, source, entry.model, entry.durationMs, entry.createdAtMs)
    val bytes = try {
        SyncBodyCodec.encodeBody(body)
    } catch (e: SyncBodyException) {
        return PortPush.Skip
    }
    val sealed = try {
        keys.seal(entry.clientId, bytes)
    } finally {
        bytes.fill(0)
    }
    if (sealed == null) return PortPush.NoKeys
    return PortPush.Ready(SyncPush(entry.clientId, sealed.keyVersion, sealed.encrypted, null))
}
