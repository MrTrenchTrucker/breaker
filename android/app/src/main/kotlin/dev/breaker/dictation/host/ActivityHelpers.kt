package dev.breaker.dictation.host

import android.content.Context
import dev.breaker.dictation.BreakerApp
import dev.breaker.dictation.ui.HomeControl
import dev.breaker.dictation.settings.ModelCatalog
import dev.breaker.dictation.ui.DownloadControl
import dev.breaker.dictation.wiring.HomeLevelFeed
import dev.breaker.dictation.wiring.HomeTakeWiring
import dev.breaker.dictation.wiring.MainPost
import dev.breaker.dictation.wiring.ModelSentences
import dev.breaker.dictation.wiring.themeModeForNight
import dev.breaker.dictation.wiring.usableHeightDp
import dev.breaker.shared.models.ModelRegistry

/**
 * Builds the Home screen's mic from the take wiring, in the theme the phone is showing.
 *
 * The mic's view is handed to the Home view, which places it; the callbacks are wired by
 * [HomeTakeWiring.createScreenMic]. The app holds no view of its own: this only makes the mic.
 */
internal fun createHomeMic(
    context: Context,
    takeWiring: HomeTakeWiring,
): dev.breaker.dictation.wiring.HomeScreenMic {
    val night = (context.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    val themeMode = themeModeForNight(night)
    // The labels come from ui's public HomeControl (one source: HomeTexts), not retyped literals.
    return takeWiring.createScreenMic(context, themeMode, HomeControl.TAP_TO_TALK, HomeControl.CANCEL, HomeControl.DONE)
}

/**
 * Adds the Home mic's level fan-out to [tileHost].
 *
 * Called when the home view is created or rebuilt. The level reaches the mic through
 * [HomeTakeWiring.screenMic], the one the wiring last made.
 *
 * Returns the [dev.breaker.dictation.wiring.LevelSink] it installs so the caller can remove it in onDestroy.
 */
internal fun addHomeLevelSink(
    takeWiring: HomeTakeWiring,
    tileHost: TileHost,
    main: MainPost,
    isRecording: () -> Boolean,
): dev.breaker.dictation.wiring.LevelSink {
    val homeFeed = HomeLevelFeed(
        onLevel = { level -> takeWiring.screenMic?.setLevel(level) },
        main = main,
        isRecording = isRecording,
    )
    tileHost.addHomeLevelSink(homeFeed)
    return homeFeed
}

/**
 * The window's height in dp after the system bars: the height the Home content gets.
 *
 * Read from the window metrics at creation: the full height less the status and
 * navigation bars. The gutter is padding inside the content, so it is not subtracted.
 */
internal fun windowHeightAfterBarsDp(context: Context): Float {
    val metrics = context.resources.displayMetrics
    val top = systemBarHeightPx(context, "status_bar_height")
    val bottom = systemBarHeightPx(context, "navigation_bar_height")
    return usableHeightDp(metrics.heightPixels, top, bottom, metrics.density)
}

private fun systemBarHeightPx(context: Context, name: String): Int {
    val id = context.resources.getIdentifier(name, "dimen", "android")
    return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
}

/** Copies [text] to the phone's primary clip, labelled with the app's name. */
internal fun copyToClip(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Breaker", text))
}

/** The download control of the screen that is showing: the first run's while it is up, the settings' otherwise. */
internal fun downloadControlOf(
    firstRunShown: Boolean,
    firstRunView: android.view.View?,
    settingsView: android.view.View?,
): DownloadControl? {
    return if (firstRunShown) {
        firstRunView as? DownloadControl
    } else {
        settingsView as? DownloadControl
    }
}

/** The selected model's size in the decimal megabytes the phone shows. */
internal fun selectedModelMb(app: BreakerApp): Int =
    ModelCatalog.byId(app.settingsStore.load().modelSize)?.sizeMb?.let(ModelSentences::mibToMb) ?: ModelSentences.mibToMb(ModelRegistry.SMALL.sizeMb)
