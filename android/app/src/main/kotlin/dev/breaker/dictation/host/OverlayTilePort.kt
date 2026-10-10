package dev.breaker.dictation.host

import android.content.Context
import android.content.res.Configuration
import dev.breaker.dictation.core.model.ThemeMode
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.overlay.FloatingTile
import dev.breaker.dictation.overlay.ShowResult
import dev.breaker.dictation.overlay.TileState
import dev.breaker.dictation.wiring.TilePort
import dev.breaker.dictation.wiring.TileShow
import dev.breaker.shared.tokens.ThemeMode as TileTheme

/**
 * The [TilePort] over the floating tile of the overlay module.
 *
 * The tile is created by the first [show], on the main thread, and kept for the life of the process.
 * Until then every other call does nothing: the coordinator pushes the state again after a tile is
 * shown. A tap on the tile calls the matching function handed in here and changes nothing itself;
 * the coordinator decides what the tile shows next. A dropped position that could not be saved is
 * not reported: the tile stays where it was dropped.
 *
 * The tile draws in light or dark. A theme of [ThemeMode.SYSTEM] follows the phone's night mode as it
 * is when [show] is called.
 */
internal class OverlayTilePort(
    private val context: Context,
    private val settings: SettingsStore,
    private val onTap: () -> Unit,
    private val onBegin: () -> Unit,
    private val onCancel: () -> Unit,
    private val onSend: () -> Unit,
) : TilePort {

    private var tile: FloatingTile? = null

    override fun show(): TileShow {
        val theme = currentTheme()
        val made = tile ?: FloatingTile.create(
            context = context,
            settings = settings,
            onTap = onTap,
            theme = theme,
            onBegin = onBegin,
            onCancel = onCancel,
            onSend = onSend,
        )
        tile = made
        made.setTheme(theme)
        return when (made.show()) {
            ShowResult.SHOWN -> TileShow.SHOWN
            ShowResult.ALREADY_SHOWN -> TileShow.SHOWN
            ShowResult.PERMISSION_MISSING -> TileShow.NO_PERMISSION
            ShowResult.FAILED -> TileShow.FAILED
        }
    }

    override fun hide() {
        tile?.hide()
    }

    override fun setState(state: TileState) {
        tile?.setState(state)
    }

    override fun showNotice(text: String) {
        tile?.showNotice(text)
    }

    override fun clearNotice() {
        tile?.clearNotice()
    }

    private fun currentTheme(): TileTheme = when (settings.load().themeMode) {
        ThemeMode.LIGHT -> TileTheme.LIGHT
        ThemeMode.DARK -> TileTheme.DARK
        ThemeMode.SYSTEM -> if (phoneIsInNightMode()) TileTheme.DARK else TileTheme.LIGHT
    }

    private fun phoneIsInNightMode(): Boolean {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return night == Configuration.UI_MODE_NIGHT_YES
    }
}
