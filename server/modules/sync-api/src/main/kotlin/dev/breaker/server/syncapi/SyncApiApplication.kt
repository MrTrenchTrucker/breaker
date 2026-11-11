package dev.breaker.server.syncapi

import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.admin.adminCreateAgentTokenRoute
import dev.breaker.server.syncapi.admin.adminListAgentTokensRoute
import dev.breaker.server.syncapi.admin.adminResetAccountRoute
import dev.breaker.server.syncapi.admin.adminClearStoreRoute
import dev.breaker.server.syncapi.admin.adminEndSessionRoute
import dev.breaker.server.syncapi.admin.adminHealthRoute
import dev.breaker.server.syncapi.admin.adminSetRoleRoute
import dev.breaker.server.syncapi.admin.adminRevokeAgentTokenRoute
import dev.breaker.server.syncapi.admin.adminWrongPwLimitRoute
import dev.breaker.server.syncapi.auth.authChangePasswordRoute
import dev.breaker.server.syncapi.auth.authCompleteResetRoute
import dev.breaker.server.syncapi.auth.authDevicesRoute
import dev.breaker.server.syncapi.auth.authHandoffRoutes
import dev.breaker.server.syncapi.auth.authKeysRoute
import dev.breaker.server.syncapi.auth.authLoginRoute
import dev.breaker.server.syncapi.auth.authLogoutRoute
import dev.breaker.server.syncapi.auth.authRegisterRoute
import dev.breaker.server.syncapi.auth.authSaltRoute
import dev.breaker.server.syncapi.db.PendingSealedStore
import dev.breaker.server.syncapi.handoff.HandoffStore
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.pendingSealedRoute
import dev.breaker.server.syncapi.transcriptions.TranscriptionStore
import dev.breaker.server.syncapi.transcriptions.syncRoutes
import dev.breaker.server.syncapi.updates.updatesRoute
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
    handoffStore: HandoffStore,
    manifestFile: String = "",
    gitTag: String = "",
) {
    val pendingSealedStore = PendingSealedStore(accountStore.db)
    routing {
        // The health route must stay free of authentication and of any data access:
        // a monitor must be able to call it when the database or the login is broken.
        get("/health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }
        updatesRoute(manifestFile)
        authRegisterRoute(accountStore)
        authSaltRoute(accountStore)
        authLoginRoute(accountStore, tokenStore)
        authKeysRoute(accountStore, tokenStore)
        authChangePasswordRoute(accountStore, tokenStore)
        adminResetAccountRoute(tokenStore, accountStore)
        adminClearStoreRoute(tokenStore, accountStore, transcriptionStore)
        authCompleteResetRoute(accountStore, tokenStore)
        authHandoffRoutes(handoffStore, tokenStore)
        authLogoutRoute(tokenStore)
        authDevicesRoute(tokenStore)
        adminRevokeAgentTokenRoute(tokenStore)
        adminCreateAgentTokenRoute(tokenStore)
        adminListAgentTokensRoute(tokenStore)
        adminEndSessionRoute(tokenStore)
        adminSetRoleRoute(tokenStore, accountStore)
        adminWrongPwLimitRoute(tokenStore, accountStore.settings)
        adminHealthRoute(tokenStore, gitTag)
        syncRoutes(tokenStore, transcriptionStore)
        pendingSealedRoute(tokenStore, pendingSealedStore)
    }
}
