package dev.breaker.dictation.sync

import dev.breaker.dictation.core.port.RecordKeys
import dev.breaker.shared.api.TranscriptionRecord
import dev.breaker.shared.api.sync.SyncBody
import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.shared.api.sync.SyncBodyException

/** Thrown when a pulled row's body cannot be read. The message never carries record or body text. */
internal class PulledRowException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads the body of one live pulled record: at key_version 0 as plain base64, and at key_version
 * 1 or higher through the RecordKeys port when the app gave one.
 */
internal object PulledRowBody {

    /**
     * Reads [record] and returns its shared body, or throws [PulledRowException]. With no [keys],
     * a key_version of 1 or higher is refused as before. With [keys], the record is opened by the
     * port and read with the lenient body reader; the opened bytes are zeroed after the read.
     */
    fun read(record: TranscriptionRecord, keys: RecordKeys? = null): SyncBody {
        if (record.deleted) {
            throw PulledRowException("pulled body refused: delete record has no body")
        }
        val keyVersion = record.keyVersion
        if (keyVersion == null) {
            throw PulledRowException("pulled body refused: missing key version")
        }
        if (keyVersion != 0 && keys == null) {
            throw PulledRowException("pulled body refused: key version " + keyVersion + " is not read yet")
        }
        val encrypted = record.encrypted
        if (encrypted == null) {
            throw PulledRowException("pulled body refused: missing encrypted body")
        }
        if (keyVersion != 0 && keys != null) {
            return readSealed(record.clientId, keyVersion, encrypted, keys)
        }
        return try {
            SyncBodyCodec.decodePlain(encrypted)
        } catch (failure: SyncBodyException) {
            throw refused(failure)
        }
    }

    private fun readSealed(clientId: String, keyVersion: Int, encrypted: String, keys: RecordKeys): SyncBody {
        val bytes = keys.open(clientId, keyVersion, encrypted)
            ?: throw PulledRowException("pulled body refused: cannot be opened")
        try {
            return SyncBodyCodec.decodeBody(bytes)
        } catch (failure: SyncBodyException) {
            throw refused(failure)
        } finally {
            bytes.fill(0)
        }
    }

    private fun refused(failure: SyncBodyException): PulledRowException {
        val codecMessage = failure.message
        val message = when {
            codecMessage != null -> "pulled body refused: " + codecMessage
            else -> "pulled body refused: "
        }
        return PulledRowException(message, failure)
    }
}
