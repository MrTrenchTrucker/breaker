package dev.breaker.dictation.commit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * One read of the keyboard signals that the accessibility service publishes.
 *
 * [fieldFocus] is whether the active window's input-focused node is editable
 * (the field the other app is typing in). [imeWindow] is whether the
 * accessibility window list currently carries an input-method window (the
 * keyboard). [lastEvent] is the name of the accessibility event that woke the
 * read, so a reading can say which change produced it. [time] is a monotonic clock
 * in milliseconds (System.nanoTime() / 1_000_000L), read when the service handled the
 * event; only differences between stamps are used, so a wall-clock change never moves it.
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
 * The one process-wide holder for the keyboard-signal readings. The probe's
 * own members ([publish], [recent], [isEnabled] and the recent lines) are not
 * called from release code; the production seam ([publishLatest], [readings],
 * [current]) is what the keyboard tile's show-and-hide decision reads in a
 * release build.
 *
 * It is a plain object because three modules share no constructor: this
 * module, the accessibility module that writes the readings, and the app that
 * shows them. None can be handed the holder through a constructor, so all of
 * them reach it here, the same way the text-insert mechanism reaches
 * [FocusedFieldHolder].
 *
 * [shouldProcessEvent] decides whether an event from a package is worth recording: the
 * probe's own package is skipped, so the probe never floods its own readings list.
 * [publish] is the probe's write: it stores a reading, as the latest one and as a
 * recent line, only while the probe is on. No production code calls it.
 * [publishLatest] is the production write:
 * it stores the latest reading whether or not the probe is on, so the reader
 * of [readings] always sees the keyboard state of the phone. [current] and
 * [recent] are the reader's: the latest reading and the
 * recent lines, oldest first. [setEnabled] turns the probe on and off. Turning
 * it off clears the latest reading and the recent lines; while it is off,
 * [publish] stores nothing and [publishLatest] stores the latest reading only.
 *
 * Every change is one compare-and-set of an immutable value, so any caller may
 * call any member, and no member waits for another.
 *
 * The holder keeps no node, no window and no text of the user: a [ProbeReading]
 * is two Booleans, the name of an event and a time.
 */
object KeyboardProbeReadings {

    /** The most recent lines kept for the reader; the oldest one is dropped when one more arrives. */
    internal const val RECENT_LIMIT: Int = 200

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
     * Whether the probe is on. The flag gates [publish] and the recent lines that
     * [publishLatest] appends; the latest reading is stored whether or not the probe
     * is on. No production code calls this.
     */
    fun isEnabled(): Boolean = state.value.enabled

    /**
     * Whether an event from [eventPackage] is worth recording. An event whose package is
     * [ownPackage], Breaker's own package, is Breaker's own UI reacting to itself, so it is
     * skipped; a null package, where the event names none, is kept. The service calls this
     * before it reads anything, so the probe never floods its own readings list.
     */
    fun shouldProcessEvent(eventPackage: String?, ownPackage: String): Boolean =
        eventPackage == null || eventPackage != ownPackage

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

    /**
     * The production write: it stores the latest reading whether or not the probe is on, so the
     * reader of [readings] sees the keyboard state of the phone at all times. A line for the
     * recent list is appended only while the probe is on, exactly as [publish] does.
     */
    fun publishLatest(fieldFocus: Boolean, imeWindow: Boolean, lastEvent: String, time: Long): ProbeReading {
        val reading = ProbeReading(fieldFocus, imeWindow, lastEvent, time)
        state.update { current ->
            val lines = if (current.enabled) (current.lines + reading).takeLast(RECENT_LIMIT) else current.lines
            current.copy(latest = reading, lines = lines)
        }
        return reading
    }

    /** The latest reading, or null when nothing has been published yet or a clear happened. */
    fun current(): ProbeReading? = state.value.latest

    /** A copy of the recent lines, oldest first, at most [RECENT_LIMIT]. */
    fun recent(): List<ProbeReading> = state.value.lines.toList()

    /** Clear the latest reading and the recent lines, and leave the probe off. For tests and a fresh start. */
    internal fun reset() {
        state.update { switchedOff }
    }

    /**
     * The live view of the latest reading: it re-emits whenever the latest reading changes,
     * and it carries the current value at the moment a reader starts collecting. While a
     * reading is live the value stays on it until the next change; a clear or a disable
     * hands out null again. A reader that starts collecting after a change sees that change
     * as its first value, not the one before it.
     */
    fun readings(): Flow<ProbeReading?> = state.map { it.latest }.distinctUntilChanged()

    private fun withLine(current: State, reading: ProbeReading): State {
        val lines: List<ProbeReading> = current.lines + reading
        return current.copy(latest = reading, lines = lines.takeLast(RECENT_LIMIT))
    }
}
