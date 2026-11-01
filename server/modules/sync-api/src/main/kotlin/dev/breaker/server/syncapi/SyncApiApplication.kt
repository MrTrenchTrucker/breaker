package dev.breaker.server.syncapi

import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.admin.adminRevokeAgentTokenRoute
import dev.breaker.server.syncapi.auth.authLoginRoute
import dev.breaker.server.syncapi.auth.authLogoutRoute
import dev.breaker.server.syncapi.auth.authRegisterRoute
import dev.breaker.server.syncapi.auth.authSaltRoute
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.TranscriptionStore
import dev.breaker.server.syncapi.transcriptions.syncRoutes
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

internal fun Application.syncApiModule(
    accountStore: AccountStore,
    tokenStore: TokenStore,
    transcriptionStore: TranscriptionStore,
) {
    routing {
        // The health route must stay free of authentication and of any data access:
        // a monitor must be able to call it when the database or the login is broken.
        get("/health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }
        authRegisterRoute(accountStore)
        authSaltRoute(accountStore)
        authLoginRoute(accountStore, tokenStore)
        authLogoutRoute(tokenStore)
        adminRevokeAgentTokenRoute(tokenStore)
        syncRoutes(tokenStore, transcriptionStore)
    }
}
