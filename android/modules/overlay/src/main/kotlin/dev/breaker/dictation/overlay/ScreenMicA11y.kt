package dev.breaker.dictation.overlay

/**
 * The pure decision behind the sendA11y guard in [ScreenMicView].
 *
 * With accessibility off the platform throws IllegalStateException ("Accessibility off. Did you forget
 * to check that?") from ViewRootImpl.requestSendAccessibilityEvent and that kills the app, so a
 * screen-reader event may be sent only while accessibility is enabled. The decision is pure because
 * the JVM suite cannot build a [android.view.View]: it takes the platform's enabled state as a Boolean,
 * and [ScreenMicView.sendA11y] is the only place that reads the manager and touches the platform.
 */
internal object ScreenMicA11y {

    /** Whether a screen-reader event may be sent: only while accessibility is [enabled]. */
    fun shouldSend(enabled: Boolean): Boolean = enabled
}
