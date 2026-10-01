package dev.breaker.dictation.core.model

/**
 * How a dictation picks an engine.
 *
 * `AUTO` is the server-primary default: ask whether the Local Server is
 * reachable, transcribe there when it is and on the phone when it is not.
 * `LOCAL` stays on the phone even when the server is reachable, and `SERVER`
 * stays on the server and fails loudly rather than dropping to the phone.
 */
enum class SttMode { AUTO, LOCAL, SERVER }

/**
 * The user's theme preference, as the domain sees it.
 *
 * `SYSTEM` follows the phone's dark mode; `LIGHT` and `DARK` override it.
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Where the floating tile sits, as fractions of the screen (0f..1f on each
 * axis). Fractions rather than pixels so a saved position survives a change of
 * display size or density.
 */
data class TilePosition(val x: Float, val y: Float) {
    init {
        require(x in 0f..1f) { "tile x must be a screen fraction in 0..1: $x" }
        require(y in 0f..1f) { "tile y must be a screen fraction in 0..1: $y" }
    }
}

/**
 * The user's settings, as the domain sees them.
 *
 * [apiKeyRef] is a *reference* to a credential held elsewhere (the platform
 * keeps it in its keystore), never the credential itself, so this value can be
 * logged, compared and persisted without leaking anything secret.
 *
 * [formattingEnabled] turns transcript formatting on or off, whichever engine
 * transcribed. Off means the text is committed exactly as it was dictated. On
 * does not decide where formatting runs: an on-device dictation is never
 * formatted by a cloud service, whatever this says.
 *
 * Defaults match the documented behaviour: server-primary routing, the small
 * on-device model, the model preloaded so a wake phrase can start a dictation
 * within a second, formatting on, the theme following the phone
 * ([ThemeMode.SYSTEM]), and the tile centred.
 */
data class AppSettings(
    val mode: SttMode = SttMode.AUTO,
    val modelSize: String = "small",
    val serverUrl: String = "",
    val apiKeyRef: String? = null,
    val wakeGestureEnabled: Boolean = true,
    val tilePosition: TilePosition = TilePosition(0.5f, 0.5f),
    val language: String = "en",
    val preloadModel: Boolean = true,
    val formattingEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
) {
    init {
        require(modelSize.isNotBlank()) { "modelSize cannot be blank" }
        require(language.isNotBlank()) { "language cannot be blank" }
    }

    /** True when a server address has been configured at all. */
    val hasServerUrl: Boolean
        get() = serverUrl.isNotBlank()
}
