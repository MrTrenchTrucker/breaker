package dev.breaker.server.syncapi

import dev.breaker.server.syncapi.servicecheck.serviceCheckRoutes
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.server.application.Application
import io.ktor.server.routing.routing

// The internal listener. It carries the two token-check routes and nothing else: no health
// route, no account routes and no admin routes. It is never mounted on the public listener.
// [keysFor] is the account key lookup the live answer uses for its box_pubkey member; without it
// the answer has no such member.
internal fun Application.internalModule(
    tokenStore: TokenStore,
    serviceKey: String,
    keysFor: (suspend (Long) -> ByteArray?)? = null,
) {
    routing {
        serviceCheckRoutes(tokenStore, serviceKey, keysFor)
    }
}
