package dev.breaker.dictation.service

/**
 * Where a tap on the ongoing notification takes the user.
 *
 * The tap opens the launcher activity with [EXTRA_ROUTE] set to [ROUTE_HISTORY]; the screen that honours
 * the route is built by the ui module, so until then the launcher simply opens.
 */
object NotificationRoute {
    /** The name of the intent extra that carries the route. */
    const val EXTRA_ROUTE: String = "dev.breaker.dictation.extra.ROUTE"

    /** The route that shows the history of transcriptions. */
    const val ROUTE_HISTORY: String = "history"

    /** The full class name of the launcher activity the tap opens; it is the activity the manifest declares. */
    const val TARGET_ACTIVITY: String = "dev.breaker.dictation.SettingsLauncherActivity"
}
