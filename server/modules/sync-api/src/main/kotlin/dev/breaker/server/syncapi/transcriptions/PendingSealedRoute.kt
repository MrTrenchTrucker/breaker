package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.PendingSealedRow
import dev.breaker.server.syncapi.db.PendingSealedStore
import dev.breaker.server.syncapi.escapeJsonString
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException

// GET /v1/sync/pending-sealed — the authenticated owner's still-sealed agent-token job
// results: a list of sealed boxes ([{id, sealed_box, created_at}]).
//
// User session token ONLY: no or unknown token is 401, an agent token is 403 (also an
// admin agent token — a token kind is not an account privilege). An admin user session
// sees only its OWN rows: the query is WHERE account_id = the token's account, and the
// account never comes from the request.
//
// Ordered created_at ascending then id, so the answer is deterministic. No paging and no LIMIT, on purpose: a
// LIMIT N would hide every row after the Nth until the earlier ones are converted. The table is bounded per
// account by the insert cap instead, so the answer holds at most PENDING_PER_ACCOUNT_MAX rows of at most
// MAX_SEALED_BYTES decoded bytes each (db/PendingSealedStore.kt). A converted row leaves the list at once:
// POST /v1/sync deletes it in the push's own transaction.
//
// Honest limit (T26, ADR-018): this endpoint returns whatever the pending-sealed store
// holds; it has no way to tell a sealed box whisper-server actually produced from one a
// database writer inserted directly — both are equally well-formed crypto_box_seal
// output, and the owner's client converts either kind the same way.
//
// The write path is POST /internal/v1/pending-sealed (servicecheck/PendingSealedIntakeRoute.kt); whisper-server
// does not call it yet.
internal fun Application.pendingSealedRoute(
    tokenStore: TokenStore,
    pendingSealedStore: PendingSealedStore,
    read: suspend (Long) -> List<PendingSealedRow> = pendingSealedStore::list,
) {
    routing {
        get("/v1/sync/pending-sealed") {
            servePendingSealed(call, tokenStore, read)
        }
    }
}

// The whole answer of the route. It is a top-level function so a test can call it through a test-only route
// and see what a cancelled read does: it is passed on, never turned into an answer.
internal suspend fun servePendingSealed(
    call: ApplicationCall,
    tokenStore: TokenStore,
    read: suspend (Long) -> List<PendingSealedRow>,
) {
    val resolved = call.sessionOrRefuse(tokenStore) ?: return
    val accountId = resolved.accountId
    val rows = try {
        read(accountId)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        call.respondUnavailable()
        return
    }
    val json = rows.joinToString(
        separator = ",",
        transform = { row ->
            "{\"id\":${escapeJsonString(row.id)},\"sealed_box\":${escapeJsonString(row.sealedBox)},\"created_at\":${escapeJsonString(row.createdAt)}}"
        },
        prefix = "[",
        postfix = "]",
    )
    call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
}
