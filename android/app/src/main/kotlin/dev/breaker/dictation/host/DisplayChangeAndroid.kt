package dev.breaker.dictation.host

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import dev.breaker.dictation.wiring.DisplayPort
import dev.breaker.dictation.wiring.DisplaySnapshot

/**
 * The [DisplayPort] over the platform's display manager.
 *
 * [start] registers one display listener through [MainLooperPost.registerOnMain], so the listener runs on the
 * main looper. The callback, the snapshot read and everything the watch does therefore run on the main thread,
 * with no lock and no second post. A second [start] while registered keeps the one registration and replaces
 * the callback. [stop] unregisters that same listener object and drops the callback; a platform refusal there
 * is swallowed, and with no callback left nothing reaches the tile.
 *
 * The application context is fine here. A display manager and a display are not visual, and no window manager
 * is used, because a window manager taken from the application context is not visual either: it can give
 * wrong metrics, and newer Android versions report that use as a strict-mode violation.
 *
 * The snapshot is the rotation of the default display and the physical size of its current mode. It is not the
 * mode id or the refresh rate, so a switch between 60 and 120 Hz at the same size is the same snapshot and moves
 * nothing. Every display id that changes is passed on; the watch decides which changes count.
 */
internal class DisplayChangeAndroid(context: Context, private val main: MainLooperPost) : DisplayPort {

    private val manager: DisplayManager? = context.getSystemService(DisplayManager::class.java)
    private var callback: ((Int) -> Unit)? = null
    private var registered: Boolean = false
    private val listener: DisplayManager.DisplayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}

        override fun onDisplayRemoved(displayId: Int) {}

        override fun onDisplayChanged(displayId: Int) {
            callback?.invoke(displayId)
        }
    }

    override fun start(onChange: (displayId: Int) -> Unit): Boolean {
        if (manager == null) {
            callback = null
            return false
        }
        callback = onChange
        if (registered) return true
        return try {
            main.registerOnMain(manager, listener)
            registered = true
            true
        } catch (e: RuntimeException) {
            callback = null
            false
        }
    }

    override fun stop() {
        callback = null
        if (!registered) return
        registered = false
        try {
            manager?.unregisterDisplayListener(listener)
        } catch (e: RuntimeException) {
            // The platform refused the removal; with no callback left, nothing reaches the tile.
        }
    }

    override fun snapshot(): DisplaySnapshot? {
        return try {
            val display = manager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
            val mode = display.mode
            DisplaySnapshot(display.rotation, mode.physicalWidth, mode.physicalHeight)
        } catch (e: RuntimeException) {
            null
        }
    }
}
