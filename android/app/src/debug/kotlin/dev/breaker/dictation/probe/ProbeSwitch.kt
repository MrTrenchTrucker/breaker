package dev.breaker.dictation.probe

/**
 * Applies a setting: the probe holder on or off, and the tile's inset listener given or taken away.
 * The app passes the real functions; the tests pass fakes. Pure: no Android type.
 */
class ProbeSwitch(
    private val setHolder: (Boolean) -> Unit,
    private val setTileProbe: (((Int, Boolean) -> Unit)?) -> Unit,
    private val onInset: (Int, Boolean) -> Unit,
    private val clearLog: () -> Unit,
) {
    /**
     * OFF turns the holder off, takes the tile listener away, and clears the inset log, and does nothing
     * else. Any other value turns the holder on and gives the tile a listener that forwards each pair to
     * [onInset]; it never clears the log.
     */
    fun apply(setting: KeyboardSignalSetting) {
        if (setting == KeyboardSignalSetting.OFF) {
            setHolder(false)
            setTileProbe(null)
            clearLog()
        } else {
            setHolder(true)
            setTileProbe { bottom, visible -> onInset(bottom, visible) }
        }
    }
}

/** Whether the activity's window inset listener is attached for [setting]: never for OFF, for any other value always. */
fun activityInsetsWanted(setting: KeyboardSignalSetting): Boolean = setting != KeyboardSignalSetting.OFF

/** Whether a process start applies the full setting: never for OFF, which is the default state and needs no tile call. */
fun startNeedsFullApply(setting: KeyboardSignalSetting): Boolean = setting != KeyboardSignalSetting.OFF
