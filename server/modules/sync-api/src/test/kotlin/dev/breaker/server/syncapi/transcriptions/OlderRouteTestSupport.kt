package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.accounts.RateLimitCounterStore
import dev.breaker.server.syncapi.settings.ServerSettingsStore
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking

// Sends GET /v1/transcriptions/older for the route tests. The bound is put in the URL exactly as given,
// and no query is sent when it is null. The Authorization header is sent exactly as given, or not at all.
internal object OlderRouteTestSupport {
    // The request with no rate limit: syncRoutes gets no counter store and no settings.
    fun getOlder(tokenStore: TokenStore, store: TranscriptionStore, authorization: String?, olderThan: String?): SyncReply =
        getOlderLimited(tokenStore, store, null, null, authorization, olderThan)

    // The same request with the given counter store and settings, installed the way the module installs them.
    fun getOlderLimited(
        tokenStore: TokenStore,
        store: TranscriptionStore,
        counterStore: RateLimitCounterStore?,
        settings: ServerSettingsStore?,
        authorization: String?,
        olderThan: String?,
    ): SyncReply {
        var reply: SyncReply? = null
        runBlocking {
            testApplication {
                application { syncRoutes(tokenStore, store, counterStore, settings) }
                val path = olderThan?.let { "/v1/transcriptions/older?older_than=$it" } ?: "/v1/transcriptions/older"
                val response = client.get(path) {
                    if (authorization != null) header("Authorization", authorization)
                }
                reply = SyncReply(response.status, response.bodyAsText())
            }
        }
        return checkNotNull(reply)
    }
}
