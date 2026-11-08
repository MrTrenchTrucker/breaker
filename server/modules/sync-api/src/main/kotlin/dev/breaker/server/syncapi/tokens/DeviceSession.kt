package dev.breaker.server.syncapi.tokens

/** One row of the device list: a live session's id, stored name and last-seen time in milliseconds. */
internal data class DeviceSession(
    val id: Long,
    val name: String?,
    val lastSeenMs: Long,
)
