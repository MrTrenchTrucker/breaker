package dev.breaker.dictation.wiring

import dev.breaker.dictation.commit.ProbeReading
import dev.breaker.dictation.commit.KeyboardProbeReadings
import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.shakeArms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Collects the keyboard-signal readings the holder publishes and feeds each real reading to the
 * coordinator on the main thread, so the tile's show/hide follows the keyboard.
 *
 * One instance owns one collection of [KeyboardProbeReadings.readings] for the lifetime it is given:
 * it starts when constructed (the host builds it together with the coordinator) and stops only when a
 * caller calls [stop]. A null reading, produced by a clear or disable of the probe, feeds the entry nothing
 * and clears the keyboard flag of the wake policy when one is given; every
 * non-null reading is posted once to [post], so the main thread sees each distinct state change exactly
 * once. This file names the holder, but no other file in the app does: it reaches the production seam
 * here and nowhere else. It never shows or hides a tile itself — it only hands readings to the
 * coordinator's own entry. The wiring lives for the process (built with the process-lifetime
 * coordinator) and is never stopped in this build.
 */
internal class KeyboardTileWiring(
    private val readEntry: (Boolean, Boolean, Long) -> Unit,
    private val post: MainPost,
    private val wake: WakePolicy? = null,
) {

    /** The collection scope; a supervisor child so a failing reader cannot cancel the wiring. Recreated per start. */
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /** The last reading observed before this collection started; used to skip the replayed-at-attach value. */
    private var baseline: ProbeReading? = null

    /** Starts the single collection on a fresh scope; stops only when [stop] is called. */
    fun start() {
        if (started) return
        started = true
        // Capture the current reading so the replayed-at-attach value can be skipped without eating any
        // genuine post-start emission: collect only readings that differ from what was already current.
        baseline = KeyboardProbeReadings.current()
        wake?.onReading(baseline?.imeWindow ?: false)
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        job = scope!!.launch {
            KeyboardProbeReadings.readings().collect { reading ->
                if (reading == null) {
                    wake?.let { policy -> post.post { policy.onSignalLost() } }
                } else if (reading != baseline) {
                    post.post { deliver(reading) }
                }
            }
        }
    }

    /** Hands one reading to the wake policy first and then to the keyboard entry; runs on the main thread. */
    private fun deliver(reading: ProbeReading) {
        wake?.onReading(reading.imeWindow)
        readEntry(reading.imeWindow, reading.fieldFocus, reading.time)
    }

    /** Stops the collection; safe to call when it was never started. */
    fun stop() {
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        started = false
    }

    private var started = false
}

/**
 * Answers whether a shake may show the tile and whether a keyboard reading may raise it. The keyboard
 * flag is the window flag of the newest raw reading, and a lost signal clears it. Each answer reads the
 * settings once, when it is asked, so a change in the settings applies to the next event.
 */
internal class WakePolicy(private val settings: () -> AppSettings) {
    private var keyboardUp: Boolean = false

    /** Sets the keyboard flag from the window flag of the newest raw keyboard reading. */
    fun onReading(imeWindow: Boolean) {
        keyboardUp = imeWindow
    }

    /** A lost keyboard signal counts as the keyboard being gone. */
    fun onSignalLost() {
        keyboardUp = false
    }

    /** Whether a shake may show the tile, given the shake mode and the keyboard flag. */
    fun shakeMayShow(): Boolean = settings().shakeWakeMode.shakeArms(keyboardUp)

    /** Whether a keyboard reading may raise the tile, as the keyboard switch says. */
    fun keyboardMayRaise(): Boolean = settings().showTileOnKeyboard

    companion object {
        /** The answers of a fresh install: a shake always shows the tile and a keyboard reading raises it. */
        fun permissive(): WakePolicy = WakePolicy { AppSettings() }
    }
}
