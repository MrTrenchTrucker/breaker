package dev.breaker.dictation.sync

import dev.breaker.shared.api.TranscriptionRecord
import dev.breaker.shared.api.sync.SyncBody
import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.shared.api.sync.SyncBodyException

/** Thrown when a pulled row's body cannot be read. The message never carries record or body text. */
internal class PulledRowException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Reads the body of one live pulled record at key_version 0. */
internal object PulledRowBody {

    /** Reads [record] and returns its shared body, or throws [PulledRowException]. */
    fun read(record: TranscriptionRecord): SyncBody {
        if (record.deleted) {
            throw PulledRowException("pulled body refused: delete record has no body")
        }
        val keyVersion = record.keyVersion
        if (keyVersion == null) {
            throw PulledRowException("pulled body refused: missing key version")
        }
        if (keyVersion != 0) {
            throw PulledRowException("pulled body refused: key version " + keyVersion + " is not read yet")
        }
        val encrypted = record.encrypted
        if (encrypted == null) {
            throw PulledRowException("pulled body refused: missing encrypted body")
        }
        return try {
            SyncBodyCodec.decodePlain(encrypted)
        } catch (failure: SyncBodyException) {
            val codecMessage = failure.message
            val message = when {
                codecMessage != null -> "pulled body refused: " + codecMessage
                else -> "pulled body refused: "
            }
            throw PulledRowException(message, failure)
        }
    }
}
