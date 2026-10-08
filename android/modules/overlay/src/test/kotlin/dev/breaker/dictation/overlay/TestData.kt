package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.TilePosition
import dev.breaker.shared.tokens.ThemeMode

/**
 * Shared numbers and helpers for the overlay tests.
 *
 * The default screen has a usable area of 1080 by 2220 pixels starting at (16, 80),
 * and the default tile is 100 pixels square, so the movable range is 980 by 2120.
 * A saved fraction of (0.5, 0.5) gives pixels (506, 1140); the allowed pixel range
 * is x 16..996 and y 80..2200.
 */
internal object TestData {
    val SCREEN = PixelBounds(16, 80, 1096, 2300)
    const val TILE_PX = 100
    const val SLOP_PX = 8

    /**
     * Settings with a non-default value in every field, so a save that drops one
     * shows up. The theme here is the core preference, written with its full name
     * because the tile itself only ever uses the ui-tokens mode.
     */
    fun settings(position: TilePosition): AppSettings = AppSettings(
        mode = SttMode.LOCAL,
        modelSize = "medium",
        serverUrl = "http://example.invalid:8080",
        apiKeyRef = "ref-1",
        wakeGestureEnabled = false,
        tilePosition = position,
        language = "de",
        preloadModel = false,
        formattingEnabled = false,
        themeMode = dev.breaker.dictation.core.model.ThemeMode.DARK,
    )

    /** A controller over the given fakes, with the touch slop set to [SLOP_PX]. */
    fun controller(
        window: FakeTileWindow,
        settings: FakeSettingsStore,
        onTap: () -> Unit = {},
        theme: ThemeMode = ThemeMode.LIGHT,
        onSaveFailed: (() -> Unit)? = null,
    ): TileController = TileController(window, settings, onTap, theme, SLOP_PX, onSaveFailed)
}
