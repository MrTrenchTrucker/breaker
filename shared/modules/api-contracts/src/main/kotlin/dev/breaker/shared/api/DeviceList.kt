package dev.breaker.shared.api

/**
 * The device list answer of `/v1/auth/devices`, at most 50 rows oldest first.
 *
 * Mirrors the `DeviceList` schema in the OpenAPI spec.
 */
data class DeviceList(
    val devices: List<DeviceRow>,
)
