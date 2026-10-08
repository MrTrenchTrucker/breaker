package dev.breaker.dictation.ui.render

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.breaker.dictation.ui.screen.onboarding.AccessibilityList
import dev.breaker.dictation.ui.screen.onboarding.OpenAction
import dev.breaker.dictation.ui.screen.onboarding.OpenResult
import dev.breaker.dictation.ui.screen.onboarding.PlatformStatus
import dev.breaker.dictation.ui.screen.onboarding.SetupPlatform

/*
 * The phone, as the setup screen sees it.
 *
 * Everything the screen knows about the phone comes through the two calls of
 * SetupPlatform, and this class is the only place they are answered. It asks the
 * system what is granted and opens the system pages where the user grants the
 * rest. It keeps nothing, logs nothing and runs no thread: each call is answered
 * on the main thread from the system as it is at that moment.
 */

/** The request code a permission dialog is started with; the answer is read back by asking the system again. */
private const val PERMISSION_REQUEST = 1

/**
 * Answers the setup screen's questions about the phone and opens the pages it asks for.
 *
 * Two permissions have a prompt that appears on top of the app: the microphone,
 * and notifications from Android 13. The overlay and the accessibility service are
 * switched on in the system settings, so for those the user is sent to the page.
 * A prompt needs an activity inside the context; without one it is not possible,
 * while the system pages open from any context.
 * Whether the user said yes is never learned here: the screen asks [status] again
 * when the user comes back.
 *
 * @param context the context the screen is built with, normally an activity's. It
 *   may be wrapped, and the activity inside it is found when a prompt is needed.
 * @param accessibilityServiceComponent the accessibility service's flattened
 *   component name, compared whole against the list of enabled services.
 */
internal class AndroidSetupPlatform(
    private val context: Context,
    private val accessibilityServiceComponent: String,
) : SetupPlatform {
    /**
     * What is granted right now.
     *
     * Notifications are read as "the user can see this app's notices", which also
     * covers a user who turned them off in the settings on a phone older than the
     * prompt. A phone that offers no notification service counts as not granted.
     */
    override fun status(): PlatformStatus {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val notices = context.getSystemService(NotificationManager::class.java)
        return PlatformStatus(
            overlay = Settings.canDrawOverlays(context),
            microphone = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            notifications = notices?.areNotificationsEnabled() ?: false,
            accessibility = AccessibilityList.contains(enabled, accessibilityServiceComponent),
            sdkInt = Build.VERSION.SDK_INT,
        )
    }

    /**
     * Opens the prompt or the system page for [action].
     *
     * [OpenResult.OPENED] means the prompt or page was handed to the system, not
     * that the user granted anything. A refusal by the system, or a phone with no
     * such page, is [OpenResult.NOT_POSSIBLE].
     */
    override fun open(action: OpenAction): OpenResult = when (action) {
        OpenAction.REQUEST_MICROPHONE -> request(Manifest.permission.RECORD_AUDIO)
        OpenAction.REQUEST_NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                request(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                start(notificationPage())
            }
        OpenAction.OVERLAY_PAGE -> start(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri()))
        OpenAction.NOTIFICATION_PAGE -> start(notificationPage())
        OpenAction.ACCESSIBILITY_LIST -> start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        OpenAction.APP_INFO -> start(appInfoPage())
    }

    /**
     * Shows the permission dialog for [permission].
     *
     * A prompt can only be shown by an activity. When none is found inside the
     * context there is no way to ask, and the answer is [OpenResult.NOT_POSSIBLE].
     */
    private fun request(permission: String): OpenResult {
        // ActivityNotFoundException and SecurityException stay named: the adapter check requires them, though RuntimeException covers the same ground.
        val activity = activityOf(context) ?: return OpenResult.NOT_POSSIBLE
        return try {
            activity.requestPermissions(arrayOf(permission), PERMISSION_REQUEST)
            OpenResult.OPENED
        } catch (failure: ActivityNotFoundException) {
            OpenResult.NOT_POSSIBLE
        } catch (failure: SecurityException) {
            OpenResult.NOT_POSSIBLE
        } catch (failure: RuntimeException) {
            OpenResult.NOT_POSSIBLE
        }
    }

    /**
     * Starts [intent].
     *
     * An activity can start a page as it stands. Any other context needs the page
     * to begin a task of its own, so the flag is added only when no activity is
     * found inside the context.
     */
    private fun start(intent: Intent): OpenResult {
        val activity = activityOf(context)
        if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // ActivityNotFoundException and SecurityException stay named: the adapter check requires them, though RuntimeException covers the same ground.
        return try {
            (activity ?: context).startActivity(intent)
            OpenResult.OPENED
        } catch (failure: ActivityNotFoundException) {
            OpenResult.NOT_POSSIBLE
        } catch (failure: SecurityException) {
            OpenResult.NOT_POSSIBLE
        } catch (failure: RuntimeException) {
            OpenResult.NOT_POSSIBLE
        }
    }

    /** The address of this app's own entry in the settings pages that take one. */
    private fun packageUri(): Uri = Uri.parse("package:" + context.packageName)

    /** The system page with this app's notification settings. */
    private fun notificationPage(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /** The system page with this app's info, where a permission can be granted by hand. */
    private fun appInfoPage(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())

    /**
     * The activity inside [from], or null when there is none.
     *
     * A context handed to a view is often an activity wrapped in other contexts, so
     * the wrappers are opened one at a time until an activity or the end is reached.
     */
    private fun activityOf(from: Context): Activity? {
        var current: Context? = from
        while (current != null) {
            if (current is Activity) return current
            current = if (current is ContextWrapper) current.baseContext else null
        }
        return null
    }
}
