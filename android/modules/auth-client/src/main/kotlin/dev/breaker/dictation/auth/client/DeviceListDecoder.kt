package dev.breaker.dictation.auth.client

import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.shared.api.sync.SyncBodyException

/** Reads the answer of GET /v1/auth/devices into rows: strict, one member devices,
 * at most 50 rows, each row exactly id, name, last_seen and current. */
internal object DeviceListDecoder {
    const val MAX_ROWS = 50
    const val MAX_NAME_CODE_POINTS = 64

    fun decode(body: String): List<DeviceRow> {
        val members = membersOf(body)
        val devices = devicesOf(members)
        return devices.map { rowOf(it) }
    }

    private fun membersOf(body: String): Map<String, JsonValue> {
        val value = JsonValue.parse(body)
        return when (value) {
            is JsonValue.Obj -> value.map
            else -> throw AuthDecodeException("TYPE",
                "expected a JSON object body")
        }
    }

    private fun devicesOf(members: Map<String, JsonValue>): List<JsonValue> {
        val devices = members["devices"]
            ?: throw AuthDecodeException("MISSING_KEY", "required key 'devices' missing")
        if (devices !is JsonValue.Arr)
            throw AuthDecodeException("TYPE", "key 'devices' not an array")
        if (members.size > 1)
            throw AuthDecodeException("BAD_VALUE", "extra member in the list object")
        if (devices.items.size > MAX_ROWS)
            throw AuthDecodeException("BAD_VALUE", "more than 50 devices")
        return devices.items
    }

    private fun rowOf(item: JsonValue): DeviceRow {
        if (item !is JsonValue.Obj)
            throw AuthDecodeException("TYPE", "device row not an object")
        val map = item.map
        val id = numberOf(map, "id")
        val name = nameOf(map)
        val lastSeenMs = lastSeenOf(map)
        val current = currentOf(map)
        if (map.size > 4)
            throw AuthDecodeException("BAD_VALUE", "extra member in a device row")
        return DeviceRow(id, name, lastSeenMs, current)
    }

    private fun numberOf(map: Map<String, JsonValue>, key: String): Long {
        val v = map[key]
            ?: throw AuthDecodeException("MISSING_KEY", "required key '$key' missing")
        return if (v is JsonValue.Num) v.l
        else throw AuthDecodeException("TYPE", "key '$key' not an integer")
    }

    private fun nameOf(map: Map<String, JsonValue>): String? {
        val v = map["name"]
            ?: throw AuthDecodeException("MISSING_KEY", "required key 'name' missing")
        return when {
            v is JsonValue.Null -> null
            v is JsonValue.Str -> checkName(v.s)
            else -> throw AuthDecodeException("TYPE", "key 'name' not a string")
        }
    }

    private fun checkName(text: String): String {
        val n = text.codePointCount(0, text.length)
        if (n < 1 || n > MAX_NAME_CODE_POINTS)
            throw AuthDecodeException("BAD_VALUE", "name out of range")
        return text
    }

    private fun lastSeenOf(map: Map<String, JsonValue>): Long {
        val v = map["last_seen"]
            ?: throw AuthDecodeException("MISSING_KEY", "required key 'last_seen' missing")
        if (v !is JsonValue.Str)
            throw AuthDecodeException("TYPE", "key 'last_seen' not a string")
        return try {
            SyncBodyCodec.parseCreatedAt(v.s)
        } catch (e: SyncBodyException) {
            throw AuthDecodeException("BAD_VALUE", "last_seen is not the feed time form")
        }
    }

    private fun currentOf(map: Map<String, JsonValue>): Boolean {
        val v = map["current"]
            ?: throw AuthDecodeException("MISSING_KEY", "required key 'current' missing")
        return when (v) {
            JsonValue.True -> true
            JsonValue.False -> false
            else -> throw AuthDecodeException("TYPE", "key 'current' not a boolean")
        }
    }
}
