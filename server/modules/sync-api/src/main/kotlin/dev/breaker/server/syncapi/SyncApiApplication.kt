package dev.breaker.server.syncapi

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

internal fun Application.syncApiModule() {
    routing {
        // The health route must stay free of authentication and of any data access:
        // a monitor must be able to call it when the database or the login is broken.
        get("/health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }
    }
}
