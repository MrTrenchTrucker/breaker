package dev.breaker.shared.api.sync

/** Where a transcription was made: on this device, or stored by the server. */
enum class SyncSource(val wire: String) {
    LOCAL("local"),
    SERVER("server");

    companion object {
        /** Maps the wire word to a source. The match is case-sensitive; any other word gives null. */
        fun fromWire(text: String): SyncSource? = when (text) {
            LOCAL.wire -> LOCAL
            SERVER.wire -> SERVER
            else -> null
        }
    }
}
