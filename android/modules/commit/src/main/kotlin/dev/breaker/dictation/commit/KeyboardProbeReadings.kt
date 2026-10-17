package dev.breaker.dictation.commit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * One read of the keyboard signals the probe is meant to measure.
 *
 * [fieldFocus] is whether the active window's input-focused node is editable
 * (the field the other app is typing in). [imeWindow] is whether the
 * accessibility window list currently carries an input-method window (the
 * keyboard). [lastEvent] is the name of the accessibility event that woke the
 * read, so a reading can say which change produced it. [time] is the
 * read's wall-clock time in milliseconds, for the debug page to show how
 * fresh the reading is.
 *
 * This is a value, not a live view: it is a snapshot of the three signals at
 * one moment, handed across a module boundary so the app can show it. It
 * names no Android type, so it is readable from a plain JVM.
 */
data class ProbeReading(
    val fieldFocus: Boolean,
    val imeWindow: Boolean,
    val lastEvent: String,
    val time: Long,
)

/**
 * The one process-wide holder for the keyboard-signal probe readings. It is
 * probe only: it is removed together with the probe's setting selector before
 * release.
 *
 * It is a plain object because three modules share no constructor: this
 * module, the accessibility module that writes the readings, and the app that
 * shows them. None can be handed the holder through a constructor, so all of
 * them reach it here, the same way the text-insert mechanism reaches
 * [FocusedFieldHolder].
 *
 * [publish] is the writer's one write: the accessibility service calls it on
 * each wake event while the probe is on. [current] and [recent] are the
 * reader's: the debug page shows the latest reading and the recent lines,
 * oldest first. [setEnabled] turns the probe on and off. Turning it off clears
 * the latest reading and the recent lines, and while it is off [publish]
 * stores nothing.
 *
 * Every change is one compare-and-set of an immutable value, so any caller may
 * call any member, and no member waits for another.
 *
 * The holder keeps no node, no window and no text of the user: a [ProbeReading]
 * is two Booleans, the name of an event and a time.
 */
object KeyboardProbeReadings {

    /** The most recent lines kept for the reader; the oldest one is dropped when one more arrives. */
    internal const val RECENT_LIMIT: Int = 40

    /** The whole holder value. It is never changed in place: each change makes a new value. */
    private data class State(
        val enabled: Boolean,
        val latest: ProbeReading?,
        val lines: List<ProbeReading>,
    )

    private val switchedOff: State = State(enabled = false, latest = null, lines = emptyList())

    private val state: MutableStateFlow<State> = MutableStateFlow(switchedOff)

    /**
     * Turn the probe on or off. Turning it off clears the latest reading and the
     * recent lines, so a later turn on starts empty.
     */
    fun setEnabled(on: Boolean) {
        if (on) {
            state.update { it.copy(enabled = true) }
        } else {
            state.update { switchedOff }
        }
    }

    /**
     * Whether the probe is on. The accessibility service checks this first, so an
     * off probe does no accessibility-tree work on a wake event.
     */
    fun isEnabled(): Boolean = state.value.enabled

    /**
     * Store [fieldFocus], [imeWindow], [lastEvent] and [time] as the latest reading
     * and append it to the recent lines, dropping the oldest line once there are
     * more than [RECENT_LIMIT]. Does nothing while the probe is off.
     */
    fun publish(fieldFocus: Boolean, imeWindow: Boolean, lastEvent: String, time: Long) {
        val reading = ProbeReading(fieldFocus, imeWindow, lastEvent, time)
        state.update { current ->
            if (current.enabled) withLine(current, reading) else current
        }
    }

    /** The latest reading, or null when nothing has been published since the probe was turned on. */
    fun current(): ProbeReading? = state.value.latest

    /** A copy of the recent lines, oldest first, at most [RECENT_LIMIT]. */
    fun recent(): List<ProbeReading> = state.value.lines.toList()

    /** Clear the latest reading and the recent lines, and leave the probe off. For tests and a fresh start. */
    internal fun reset() {
        state.update { switchedOff }
    }

    private fun withLine(current: State, reading: ProbeReading): State {
        val lines: List<ProbeReading> = current.lines + reading
        return current.copy(latest = reading, lines = lines.takeLast(RECENT_LIMIT))
    }
}
