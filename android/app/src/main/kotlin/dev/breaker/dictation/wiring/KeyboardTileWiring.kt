package dev.breaker.dictation.wiring

import dev.breaker.dictation.commit.ProbeReading
import dev.breaker.dictation.commit.KeyboardProbeReadings
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
 * caller calls [stop]. Null readings — produced by a clear or disable of the probe — feed nothing; every
 * non-null reading is posted once to [post], so the main thread sees each distinct state change exactly
 * once. This file names the holder, but no other file in the app does: it reaches the production seam
 * here and nowhere else. It never shows or hides a tile itself — it only hands readings to the
 * coordinator's own entry. The wiring lives for the process (built with the process-lifetime
 * coordinator) and is never stopped in this build.
 */
internal class KeyboardTileWiring(
    private val readEntry: (Boolean, Boolean, Long) -> Unit,
    private val post: MainPost,
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
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        job = scope!!.launch {
            KeyboardProbeReadings.readings().collect { reading ->
                if (reading == null || reading != baseline) {
                    val timeMs: Long = reading?.time ?: return@collect
                    post.post { readEntry(reading.imeWindow, reading.fieldFocus, timeMs) }
                }
            }
        }
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
