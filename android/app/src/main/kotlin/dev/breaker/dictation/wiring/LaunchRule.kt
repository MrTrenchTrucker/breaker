package dev.breaker.dictation.wiring

/** The platform's name for the intent action that starts an app's main screen. */
private const val ACTION_MAIN: String = "android.intent.action.MAIN"

/** The platform's name for the intent category the launcher icon puts on its start intent. */
private const val CATEGORY_LAUNCHER: String = "android.intent.category.LAUNCHER"

/**
 * True when the launcher activity was opened by the user touching the app's launcher icon.
 *
 * The icon starts the activity with the main action, the launcher category and no route. Everything
 * else is not the icon: the tile's intent has no action, and the notification taps carry a route
 * (or, for the tile notice, no main action). Only an icon launch switches dictation on again; any
 * other start respects an off the user chose. A blank [route] counts as no route.
 */
fun isIconLaunch(action: String?, categories: Set<String>, route: String?): Boolean =
    action == ACTION_MAIN && CATEGORY_LAUNCHER in categories && route.isNullOrBlank()
