package dev.breaker.dictation.auth.client

/** One row of the device list answer: id, name, last-seen ms and current flag. */
data class DeviceRow(
    val id: Long,
    val name: String?,
    val lastSeenMs: Long,
    val current: Boolean,
)
