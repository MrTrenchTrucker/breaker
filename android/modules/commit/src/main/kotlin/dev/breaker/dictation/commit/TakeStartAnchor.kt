package dev.breaker.dictation.commit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The window a take started in: an accessibility window id and the package of
 * the app that owns that window, nothing else.
 *
 * Either value may be null when the window could not be named. They are
 * identifying metadata, never screen content: no node and no text live here.
 */
data class TakeStartWindow(val windowId: Int?, val packageName: String?) {
    override fun toString(): String = "TakeStartWindow"
}

/**
 * The one process-wide holder for the window a take started in.
 *
 * It is a plain object because three modules share no constructor: this
 * module, the text-insert mechanism built as its own Gradle module that
 * writes the anchor at take start, and the app that reads it at commit. None
 * can be handed the holder through a constructor, so all of them reach it
 * here, the same way the keyboard readings reach [KeyboardProbeReadings].
 *
 * The holder stores ONE immutable [TakeStartWindow] in a state flow, and every
 * change is one compare-and-set of that value, so any caller on any thread may
 * call any member and no member waits for another.
 *
 * The stored value is a tri-state, and the difference between its answers is
 * the point:
 * - [current] returns null when no take was captured. A commit that sees null
 *   treats its check as inactive, so an anchor left over from an earlier take
 *   can never refuse a later commit.
 * - A take that started with no focused field captures a [TakeStartWindow]
 *   with both fields null. That value is NOT null, so "a take started and
 *   there was no field" stays distinguishable from "no take".
 *
 * Only an Int and a String are held: no node, no window object and no text,
 * ever. A window handle is released the moment these two values are read out
 * of it.
 *
 * The object, [capture], [current] and [clear] are public because Kotlin
 * `internal` does not cross a Gradle module boundary, for the same reason the
 * focused-field holder is public. [reset] is internal, for tests.
 */
object TakeStartAnchor {

    /** A take started and no field was focused: not null, both fields null. */
    private val noFocusedField: TakeStartWindow = TakeStartWindow(windowId = null, packageName = null)

    /** The whole holder value. It starts at null: no take has been captured yet. */
    private val state: MutableStateFlow<TakeStartWindow?> = MutableStateFlow(null)

    /**
     * Store [window] as the window the current take started in. A null
     * [window] stores [noFocusedField] rather than null, so a take that began
     * with nothing focused is still a captured take.
     */
    fun capture(window: TakeStartWindow?) {
        state.update { window ?: noFocusedField }
    }

    /** The window the current take started in, or null when no take was captured. */
    fun current(): TakeStartWindow? = state.value

    /** Drop the anchor, so the next commit sees an inactive check until a new take captures one. */
    fun clear() {
        state.update { null }
    }

    /** Drop the anchor. Internal, for tests, so one test leaks nothing into another. */
    internal fun reset() {
        clear()
    }
}
