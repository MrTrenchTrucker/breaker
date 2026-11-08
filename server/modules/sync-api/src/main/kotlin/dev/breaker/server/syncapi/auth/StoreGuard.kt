package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.transcriptions.respondUnavailable
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.CancellationException

/** What one guarded store call came to: its value, or a failure already answered with the fixed 503. */
internal sealed class StoreCall<out T> {
    class Done<out T>(val value: T) : StoreCall<T>()
    object Failed : StoreCall<Nothing>()
}

/**
 * Runs one store call. A store failure answers the fixed 503 (no part of the failure reaches the body)
 * and gives [StoreCall.Failed]; a cancellation is rethrown, never answered.
 */
internal suspend fun <T> ApplicationCall.guardedStoreCall(block: suspend () -> T): StoreCall<T> =
    try {
        StoreCall.Done(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        respondUnavailable()
        StoreCall.Failed
    }
