package dev.breaker.dictation.auth.client

/** JSON texts for the device list tests. A member given as null is left out of the row. */
internal object DeviceListFixtures {
    const val GOOD_TIME = "2026-11-07T12:00:05.250Z"

    fun rowText(
        id: String? = "1",
        name: String? = "\"Pixel\"",
        lastSeen: String? = "\"$GOOD_TIME\"",
        current: String? = "true",
    ): String {
        val parts = ArrayList<String>()
        if (id != null) parts.add("\"id\":" + id)
        if (name != null) parts.add("\"name\":" + name)
        if (lastSeen != null) parts.add("\"last_seen\":" + lastSeen)
        if (current != null) parts.add("\"current\":" + current)
        return "{" + parts.joinToString(",") + "}"
    }

    fun listText(rows: List<String>): String = "{\"devices\":[" + rows.joinToString(",") + "]}"

    fun quoted(text: String): String = "\"" + text + "\""
}
