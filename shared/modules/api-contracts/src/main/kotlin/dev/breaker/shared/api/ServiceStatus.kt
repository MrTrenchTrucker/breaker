package dev.breaker.shared.api

/** The body of `GET /v1/status`: a list of services, each named and up/down. */
data class ServiceStatusReply(
    val services: List<ServiceStatus>,
)

/** One service: its name and whether it is up. Names and booleans only. */
data class ServiceStatus(
    val name: String,
    val up: Boolean,
)
