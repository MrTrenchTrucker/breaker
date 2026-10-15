package dev.breaker.dictation.wiring

/**
 * What the default display looked like at one moment: its rotation and the physical size of its current
 * mode, in pixels. The refresh rate and the mode id are NOT part of it, so a switch between 60 and 120 Hz
 * gives the same snapshot.
 */
internal data class DisplaySnapshot(val rotation: Int, val physicalWidthPx: Int, val physicalHeightPx: Int)

/** The platform's id for the default display (the value of the platform constant, 0). */
internal const val DEFAULT_DISPLAY_ID: Int = 0

/** The platform's display listener, as the watch sees it. */
internal interface DisplayPort {
    /** Registers the listener on the MAIN looper; [onChange] gets the id of the display that changed and is always called on the main thread. Answers false when the platform refuses. */
    fun start(onChange: (displayId: Int) -> Unit): Boolean

    /** Unregisters the listener. Safe when nothing was registered. */
    fun stop()

    /** The default display now, or null when it cannot be read. Called on the main thread only. */
    fun snapshot(): DisplaySnapshot?
}

/**
 * Tells the tile when the default display really changes, and only then. The port calls back on the main
 * thread, so this class runs everything on that thread: it takes no lock, posts nothing and starts no
 * thread. Start takes the baseline snapshot first and registers after it. A callback for another display
 * is dropped before the display is read. A callback whose snapshot equals the last one is dropped. Any
 * other callback calls [onChanged] once and becomes the last snapshot; an unreadable display always counts
 * as a change. A throw from the port, from the snapshot read or from [onChanged] is caught here.
 */
internal class DisplayWatch(
    private val port: DisplayPort,
    private val onChanged: () -> Unit,
) {
    private var started: Boolean = false
    private var last: DisplaySnapshot? = null

    /** Takes the baseline and registers the listener. Does nothing when already started; a refused registration leaves the watch stopped. */
    fun start() {
        if (started) return
        last = readOrNull()
        val accepted: Boolean = registerOrRefuse()
        started = accepted
        if (!accepted) last = null
    }

    /** Unregisters the listener and drops the last snapshot. Does nothing when not started. */
    fun stop() {
        if (started) {
            started = false
            last = null
            unregister()
        }
    }

    private fun handle(displayId: Int) {
        if (!started) return
        if (displayId != DEFAULT_DISPLAY_ID) return
        val now: DisplaySnapshot? = readOrNull()
        if (now != null && now == last) return
        last = now
        forward()
    }

    private fun readOrNull(): DisplaySnapshot? = guarded<DisplaySnapshot?>(null) { port.snapshot() }

    private fun registerOrRefuse(): Boolean = guarded<Boolean>(false) { port.start { displayId -> handle(displayId) } }

    private fun unregister() { guarded<Unit>(Unit) { port.stop() } }

    private fun forward() { guarded<Unit>(Unit) { onChanged() } }

    private fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        fallback
    }
}
