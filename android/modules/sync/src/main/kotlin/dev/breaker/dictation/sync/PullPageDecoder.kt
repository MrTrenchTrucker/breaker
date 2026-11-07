package dev.breaker.dictation.sync

import dev.breaker.shared.api.TranscriptionPage
import dev.breaker.shared.api.TranscriptionRecord
import dev.breaker.shared.api.sync.SyncBodyCodec

/** The only exception [decode] ever throws. Messages never contain body text. */
internal class PullPageException(message: String) : Exception(message)

/** Decodes one pull page answer into the shared page type, or refuses it. */
internal object PullPageDecoder {
    private const val MAX_CHANGE_DIGITS = 19
    private const val MAX_KEY_VERSION_DIGITS = 10

    /** Decodes [body] as one pull page object. Only PullPageException leaves this function. */
    fun decode(body: String): TranscriptionPage {
        val page = PullPageJson.parse(body)
        val map = if (page is JsonValue.Obj) page.map
        else throw PullPageException("top value is not an object")

        val itemsRaw = map["items"] ?: throw PullPageException("items is missing")
        val itemsArr = if (itemsRaw is JsonValue.Arr) itemsRaw.list
        else throw PullPageException("items is not an array")

        val nextRaw = map["next"] ?: throw PullPageException("next is missing")
        val nextText = if (nextRaw is JsonValue.Str) nextRaw.s
        else throw PullPageException("next is not a string")
        if (nextText.isEmpty()) throw PullPageException("next is empty")

        val moreRaw = map["more"] ?: throw PullPageException("more is missing")
        val more = if (moreRaw is JsonValue.Bool) moreRaw.v
        else throw PullPageException("more is not a boolean")

        val records = itemsArr.mapIndexed { index, entry ->
            readEntry(index, entry)
        }

        return TranscriptionPage(items = records, next = nextText, more = more)
    }

    private fun readEntry(index: Int, entry: JsonValue): TranscriptionRecord {
        val map = if (entry is JsonValue.Obj) entry.map
        else throw PullPageException("items element $index is not an object")

        val clientIdRaw = map["client_id"] ?: throw clientMissing(index)
        val clientId = if (clientIdRaw is JsonValue.Str) clientIdRaw.s
        else throw PullPageException("items element $index: client_id is not a string")
        if (!SyncBodyCodec.isClientId(clientId)) {
            throw clientNotUuid(index)
        }

        val changeRaw = map["change"] ?: throw PullPageException("items element $index: change is missing")
        val changeToken = if (changeRaw is JsonValue.Num) changeRaw.token
        else throw PullPageException("items element $index: change is not an integer")
        val change = parseChange(changeToken, index)

        val deletedRaw = map["deleted"] ?: throw PullPageException("items element $index: deleted is missing")
        val deleted = if (deletedRaw is JsonValue.Bool) deletedRaw.v
        else throw PullPageException("items element $index: deleted is not a boolean")

        val storedAtRaw = map["stored_at"] ?: throw storedAtMissing(index)
        val storedAt = if (storedAtRaw is JsonValue.Str) storedAtRaw.s
        else throw PullPageException("items element $index: stored_at is not a string")
        if (storedAt.isEmpty()) throw PullPageException("items element $index: stored_at is empty")

        return if (deleted) {
            readDelete(index, map, clientId, change, storedAt)
        } else {
            readLive(index, map, clientId, change, storedAt)
        }
    }

    private fun clientMissing(index: Int): Nothing =
        throw PullPageException("items element $index: client_id is missing")

    private fun clientNotUuid(index: Int): Nothing =
        throw PullPageException("items element $index: client_id is not a lowercase canonical UUID")

    private fun readLive(
        index: Int,
        map: Map<String, JsonValue>,
        clientId: String,
        change: Long,
        storedAt: String,
    ): TranscriptionRecord {
        val keyVersionRaw = map["key_version"] ?: throw keyVersionMissing(index)
        val keyVersionToken = if (keyVersionRaw is JsonValue.Num) keyVersionRaw.token
        else throw PullPageException("items element $index: key_version is not an integer")
        val keyVersion = parseKeyVersion(keyVersionToken, index)

        val encryptedRaw = map["encrypted"] ?: throw encryptedMissing(index)
        val encrypted = if (encryptedRaw is JsonValue.Str) encryptedRaw.s
        else throw PullPageException("items element $index: encrypted is not a string")

        return TranscriptionRecord(
            clientId = clientId,
            change = change,
            deleted = false,
            keyVersion = keyVersion,
            encrypted = encrypted,
            storedAt = storedAt,
        )
    }

    private fun readDelete(
        index: Int,
        map: Map<String, JsonValue>,
        clientId: String,
        change: Long,
        storedAt: String,
    ): TranscriptionRecord {
        if (map.containsKey("key_version")) {
            throw PullPageException("items element $index: delete record must not carry key_version")
        }
        if (map.containsKey("encrypted")) {
            throw PullPageException("items element $index: delete record must not carry encrypted")
        }
        return TranscriptionRecord(
            clientId = clientId,
            change = change,
            deleted = true,
            keyVersion = null,
            encrypted = null,
            storedAt = storedAt,
        )
    }

    private fun isDigits(token: String): Boolean {
        if (token.isEmpty()) return false
        for (c in token) if (c !in '0'..'9') return false
        return true
    }

    private fun parseChange(token: String, index: Int): Long {
        if (!isDigits(token)) throw PullPageException("items element $index: change is not an integer")
        if (token.length > 1 && token[0] == '0') throw changeLeadingZero(index)
        if (token.length > MAX_CHANGE_DIGITS) throw changeOutOfLong(index)
        val n = token.toLongOrNull() ?: throw changeOutOfLong(index)
        if (n < 1L) throw PullPageException("items element $index: change is less than 1")
        return n
    }

    private fun changeLeadingZero(index: Int): Nothing =
        throw PullPageException("items element $index: change has a leading zero")

    private fun changeOutOfLong(index: Int): Nothing =
        throw PullPageException("items element $index: change is out of Long range")

    private fun parseKeyVersion(token: String, index: Int): Int {
        if (!isDigits(token)) throw PullPageException("items element $index: key_version is not an integer")
        if (token.length > 1 && token[0] == '0') throw keyVersionLeadingZero(index)
        if (token.length > MAX_KEY_VERSION_DIGITS) throw keyVersionOutOfInt(index)
        val n = token.toIntOrNull() ?: throw keyVersionOutOfInt(index)
        if (n < 0) throw PullPageException("items element $index: key_version is less than 0")
        return n
    }

    private fun keyVersionLeadingZero(index: Int): Nothing =
        throw PullPageException("items element $index: key_version has a leading zero")

    private fun keyVersionOutOfInt(index: Int): Nothing =
        throw PullPageException("items element $index: key_version is out of Int range")

    private fun keyVersionMissing(index: Int): Nothing =
        throw PullPageException("items element $index: key_version is missing")

    private fun encryptedMissing(index: Int): Nothing =
        throw PullPageException("items element $index: encrypted is missing")

    private fun storedAtMissing(index: Int): Nothing =
        throw PullPageException("items element $index: stored_at is missing")
}
