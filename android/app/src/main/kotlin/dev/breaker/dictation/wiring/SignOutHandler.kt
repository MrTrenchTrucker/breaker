package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.port.AuthService
import dev.breaker.dictation.host.MainLooperPost
import dev.breaker.dictation.host.OneShotBackground

/**
 * The answer to the sign-out confirm of the Home drawer.
 *
 * The logout runs on [background], never on the main thread. The local sign-out has already happened by the
 * time the logout call returns or throws, so the main post that follows runs either way: it sets the live flag
 * to false first and then redraws Home, which shows the signed-out notice.
 */
internal class SignOutHandler(
    private val authService: AuthService,
    private val sessionLive: SessionLiveState,
    private val background: Background,
    private val main: MainPost,
    private val refresh: () -> Unit,
    private val clearAll: () -> Unit,
    private val pushForSignout: () -> Unit,
) {
    /** The action the launcher passes to Home as its sign-out answer. Each use signs out once. */
    fun action(): () -> Unit = {
        background.submit {
            // Best-effort push before logout: once the addressed session is gone the queue cannot
            // drain, so flush pending deletes first. Bounded ~5 s and never blocking sign-out.
            try {
                pushForSignout()
            } catch (e: Exception) {
                // A failed or timed-out push never blocks sign-out.
            }
            try {
                authService.logout()
            } catch (e: Exception) {
                // The local sign-out already happened; a failed logout call never reaches the user.
            }
            try {
                // Clear the sync queue and the history tombstones alongside the vault: one call
                // site, both cleared (they live in different databases).
                clearAll()
            } catch (e: Exception) {
                // A failed bulk-clear never reaches the user; it is retried on the next sign-out.
            }
            main.post {
                sessionLive.setLive(false)
                refresh()
            }
        }
    }
}

/**
 * The sign-out answer for the Home drawer: a [SignOutHandler] over its own background thread and the main
 * looper, returned as the action Home calls once the user confirms.
 */
internal fun signOutAction(
    authService: AuthService,
    sessionLive: SessionLiveState,
    clearAll: () -> Unit,
    refresh: () -> Unit,
    pushForSignout: () -> Unit,
): () -> Unit =
    SignOutHandler(authService, sessionLive, OneShotBackground("breaker-signout"), MainLooperPost(), clearAll, refresh, pushForSignout).action()
