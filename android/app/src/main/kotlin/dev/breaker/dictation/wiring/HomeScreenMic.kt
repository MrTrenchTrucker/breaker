package dev.breaker.dictation.wiring

import android.content.Context
import android.view.View
import dev.breaker.dictation.overlay.ScreenMic
import dev.breaker.dictation.overlay.TileState
import dev.breaker.shared.tokens.ThemeMode

/**
 * The narrow surface the Home take wiring needs of the Home's mic: the face it pushes and the level
 * it feeds. [HomeScreenMic] implements it; a JVM test stands in a fake, because the real mic wraps an
 * Android [View]. [HomeTakeWiring.screenMic] is typed against this and not against the view-bearing
 * class, so the production type stays honest about what the wiring actually calls.
 */
internal interface HomeMicPort {
    /** Push the [state] to the mic; the mic never changes it by itself. */
    fun setState(state: TileState)

    /** Push the audio [level] from 0 to 1. */
    fun setLevel(level: Float)
}

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
) : HomeMicPort {
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
    override fun setState(state: TileState) = mic.setState(state)

    /** Push the audio [level] from 0 to 1. */
    override fun setLevel(level: Float) = mic.setLevel(level)

    /** Push the [mode] to the mic. */
    fun setTheme(mode: ThemeMode) = mic.setTheme(mode)
}
