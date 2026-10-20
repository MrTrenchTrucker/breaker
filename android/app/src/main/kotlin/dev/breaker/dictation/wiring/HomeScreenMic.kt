package dev.breaker.dictation.wiring

import android.content.Context
import android.view.View
import dev.breaker.dictation.overlay.ScreenMic
import dev.breaker.dictation.overlay.TileState
import dev.breaker.shared.tokens.ThemeMode

/**
 * The Home screen's mic: a [ScreenMic] wired to the Home take.
 *
 * The app creates this through [HomeTakeWiring.createScreenMic] and hands the view to the screen;
 * the callbacks reach the [HomeTakeAdapter] through the wiring. State and level are pushed from
 * the wiring to the mic.
 */
// cancelLabel and sendLabel are the app's own labels (no defaults), passed through to ScreenMic.
internal class HomeScreenMic(
    context: Context,
    theme: ThemeMode,
    label: String,
    cancelLabel: String,
    sendLabel: String,
    onTap: () -> Unit,
    onBegin: () -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    private val mic: ScreenMic = ScreenMic.create(
        context = context,
        theme = theme,
        label = label,
        cancelLabel = cancelLabel,
        sendLabel = sendLabel,
        onTap = onTap,
        onBegin = onBegin,
        onCancel = onCancel,
        onSend = onSend,
    )

    /** The view to place on the Home screen. */
    val view: View get() = mic.asView()

    /** Push the [state] to the mic; the mic never changes it by itself. */
    fun setState(state: TileState) = mic.setState(state)

    /** Push the audio [level] from 0 to 1. */
    fun setLevel(level: Float) = mic.setLevel(level)

    /** Push the [mode] to the mic. */
    fun setTheme(mode: ThemeMode) = mic.setTheme(mode)
}
