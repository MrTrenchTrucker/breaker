package dev.breaker.shared.api

/**
 * One row of the device list answer from `/v1/auth/devices`.
 *
 * Mirrors the `DeviceRow` schema in the OpenAPI spec. The name is nullable
 * (a session issued without a device name), which is why the spec carries it
 * as a property outside the required list.
 */
data class DeviceRow(
    val id: Long,
    val name: String?,
    val lastSeen: String,
    val current: Boolean,
)
