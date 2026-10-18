package dev.breaker.dictation.wiring

/** The default display in portrait, 1080 by 2400 pixels. */
internal val DISPLAY_BASE: DisplaySnapshot = DisplaySnapshot(rotation = 0, physicalWidthPx = 1080, physicalHeightPx = 2400)

/** The same display turned to its second rotation, the same size. */
internal val DISPLAY_TURNED: DisplaySnapshot = DisplaySnapshot(rotation = 1, physicalWidthPx = 1080, physicalHeightPx = 2400)

/**
 * A display port that counts its calls and can refuse or throw. It keeps the callback it was given, even
 * after a stop, so a test can call it afterwards and see that the watch ignores it. Snapshot answers come
 * from [script]: each call takes the first entry while more than one is left, and the last entry stays.
 */
internal class FakeDisplayPort : DisplayPort {
    var startCalls: Int = 0
        private set
    var stopCalls: Int = 0
        private set
    var snapshotCalls: Int = 0
        private set
    var allowStart: Boolean = true
    var startError: RuntimeException? = null
    var stopError: RuntimeException? = null
    var snapshotError: RuntimeException? = null

    /** Runs inside start, after the callback is kept; a test uses it to change the display during registration. */
    var onRegister: (() -> Unit)? = null
    val script: MutableList<DisplaySnapshot?> = ArrayList()
    private var callback: ((Int) -> Unit)? = null

    override fun start(onChange: (displayId: Int) -> Unit): Boolean {
        startCalls += 1
        startError?.let { throw it }
        callback = onChange
        onRegister?.invoke()
        return allowStart
    }

    override fun stop() {
        stopCalls += 1
        stopError?.let { throw it }
    }

    override fun snapshot(): DisplaySnapshot? {
        snapshotCalls += 1
        snapshotError?.let { throw it }
        return if (script.size > 1) script.removeAt(0) else script.firstOrNull()
    }

    /** Makes every answer from now on [snapshot], replacing the script. */
    fun answer(snapshot: DisplaySnapshot?) {
        script.clear()
        script.add(snapshot)
    }

    /** The callback the watch registered, or null when it never registered one. */
    fun registered(): ((Int) -> Unit)? = callback

    /** Calls the registered callback as the platform would; does nothing when none was registered. */
    fun fire(displayId: Int) {
        callback?.invoke(displayId)
    }
}
