package dev.breaker.dictation.probe

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * The debug mic probe's shared state: the last activity the probe can reach, remembered by a provider
 * that registers one ActivityLifecycleCallbacks holder on process start. Main thread only; every field is
 * read and written on the main looper so no lock is needed.
 */
object MicProbeState {

    /** The most recently resumed activity, or null when none has ever resumed. */
    @Volatile
    private var lastResumedActivity: android.app.Activity? = null

    /** Whether [register] has already registered its lifecycle callbacks once. */
    @Volatile
    private var registered = false

    /** Remembers the activity, so the receiver can walk it for the Home mic. Main thread only. */
    fun setResumedActivity(activity: android.app.Activity?) {
        lastResumedActivity = activity
    }

    /** The last resumed activity the holder remembers, or null. */
    fun lastResumed(): android.app.Activity? = lastResumedActivity

    /** Whether the lifecycle callbacks are registered. Main thread only. */
    fun isRegistered(): Boolean = registered

    /**
     * Registers one lifecycle listener on [application], once. On resume the holder stores the activity, on
     * destroy it clears the store when the stored activity leaves; a destroyed activity is not cleared again by
     * anything else. Idempotent: a second call does nothing. Main thread only.
     */
    fun register(application: Application) {
        if (registered) return
        registered = true
        application.registerLifecycle(
            object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: android.app.Activity) {
                    lastResumedActivity = activity
                }

                override fun onActivityDestroyed(activity: android.app.Activity) {
                    if (lastResumedActivity === activity) lastResumedActivity = null
                }

                override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityPaused(activity: android.app.Activity) = Unit
                override fun onActivityStopped(activity: android.app.Activity) = Unit
                override fun onActivityStarted(activity: android.app.Activity) = Unit
                override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) = Unit
            },
        )
    }
}

/** Registers [lifecycle] with [application]. */
private fun Application.registerLifecycle(lifecycle: android.app.Application.ActivityLifecycleCallbacks) {
    registerActivityLifecycleCallbacks(lifecycle)
}
