package dev.breaker.dictation.sync

import dev.breaker.shared.api.sync.SyncBodyCodec
import java.util.Base64

/** One pending row: the client id it must be uploaded under and the sealed box bytes. */
internal class PendingRow(val id: String, val sealedBox: ByteArray)

/**
 * A row the decoder skipped. [index] is its position in the list. [id] is its id only when that is a
 * canonical lowercase UUID, else null. [fingerprint] is a 64-bit hash of the row's raw text. Nothing
 * else of the row is kept.
 */
internal class SkippedRow(val index: Int, val id: String?, val fingerprint: Long)

/** The result of [PendingSealedDecoder.decode]: the valid rows and the skipped ones, in list order. */
internal class PendingList(val rows: List<PendingRow>, val skipped: List<SkippedRow>)

/** The only exception [PendingSealedDecoder.decode] throws. The message names the family only. */
internal class PendingSealedException(val family: String) : Exception("pending list refused: " + family)

/**
 * Decodes the answer of GET /v1/sync/pending-sealed.
 *
 * The WHOLE list is refused (an exception) only for a structural fault: a body over [MAX_BODY_CHARS],
 * a top level that is not an array, text that is not JSON, or nesting deeper than 16 levels. A row
 * that fails its own checks is SKIPPED, never a refusal: it is not an object, it has a missing,
 * unknown or duplicate member, a member that is not a string, an id that is not a canonical UUID, a
 * sealed_box that is not strict padded standard base64 or is over [MAX_BOX_CHARS], or an empty
 * created_at. Every row is read completely first (see [PendingJson]), so a junk row never hides the
 * rows after it. A refusal never carries text of the body.
 */
internal object PendingSealedDecoder {
    /** The most response text the app reads and the decoder accepts. */
    const val MAX_BODY_CHARS = 8 * 1024 * 1024
    const val MAX_BOX_CHARS = 2 * 1024 * 1024
    private val ALLOWED = setOf("id", "sealed_box", "created_at")

    /** Decodes [body]. Only [PendingSealedException] leaves this function. */
    fun decode(body: String): PendingList {
        if (body.length > MAX_BODY_CHARS) throw PendingSealedException("body_size")
        val json = PendingJson(body, ALLOWED)
        val rows = ArrayList<PendingRow>()
        val skipped = ArrayList<SkippedRow>()
        for ((index, raw) in json.readList().withIndex()) {
            val row = toRow(raw)
            if (row != null) {
                rows.add(row)
            } else {
                val id = raw.id?.takeIf { SyncBodyCodec.isClientId(it) }
                skipped.add(SkippedRow(index, id, json.fingerprint(raw.start, raw.end)))
            }
        }
        return PendingList(rows, skipped)
    }

    /** The row, or null when it fails any of its own checks. */
    private fun toRow(raw: RawRow): PendingRow? {
        val members = raw.members ?: return null
        val id = members["id"]
        val box = members["sealed_box"]
        val createdAt = members["created_at"]
        if (id == null || box == null || createdAt == null) return null
        if (!SyncBodyCodec.isClientId(id) || box.length > MAX_BOX_CHARS || createdAt.isEmpty()) return null
        val bytes = decodeBox(box) ?: return null
        return PendingRow(id, bytes)
    }

    /** Strict padded standard base64: it must decode and re-encode to the same text, else null. */
    private fun decodeBox(text: String): ByteArray? {
        val bytes = try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return if (Base64.getEncoder().encodeToString(bytes) == text) bytes else null
    }
}
