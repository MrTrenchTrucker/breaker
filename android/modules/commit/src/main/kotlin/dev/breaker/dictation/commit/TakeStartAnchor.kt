package dev.breaker.dictation.commit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

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
 * The holder stores ONE immutable [TakeStartWindow] in a state flow, bundled
 * with a monotonic generation token, and every change is one atomic
 * compare-and-set of that same state, so any caller on any thread may call
 * any member and no member waits for another.
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

    /** Monotonic generation counter, bumped once per [capture] for the life of the process. Never reset. */
    private val counter: AtomicLong = AtomicLong(0)

    /**
     * The whole holder value: the generation of the anchor held right now plus
     * the window it holds. Starts at null: no take has been captured yet.
     *
     * Keeping generation and window in ONE state value is what makes
     * [clearIfGeneration] atomic: the compare against the generation and the
     * clear happen in the same [state.update] pass over the same capture that
     * writes it, so there is no check-then-act gap between reading the token
     * and dropping the anchor.
     */
    private val state: MutableStateFlow<Holder?> = MutableStateFlow(null)

    /** One capture plus its generation. Private: generation is exposed only through [generation]. */
    private data class Holder(val generation: Long, val window: TakeStartWindow)

    /**
     * Store [window] as the window the current take started in. A null
     * [window] stores [noFocusedField] rather than null, so a take that began
     * with nothing focused is still a captured take.
     *
     * Every capture bumps the monotonic counter, so every held anchor -
     * including the no-field fail-closed one and [capture(null)] - carries a
     * generation greater than 0 that differs from every earlier one in this
     * process. Two captures of the same [TakeStartWindow] (a data class with
     * value equality) therefore get different generations, which is what keeps
     * a late clear from wiping a freshly re-captured identical window.
     */
    fun capture(window: TakeStartWindow?) {
        state.update { Holder(counter.incrementAndGet(), window ?: noFocusedField) }
    }

    /** The generation of the anchor held right now, or 0L when nothing is held (INACTIVE). */
    fun generation(): Long = state.value?.generation ?: 0L

    /** The window the current take started in, or null when no take was captured. */
    fun current(): TakeStartWindow? = state.value?.window

    /** Drop the anchor, so the next commit sees an inactive check until a new take captures one. */
    fun clear() {
        state.update { null }
    }

    /**
     * Atomically clear the held anchor ONLY if its generation equals
     * [generation]. A no-op if another capture replaced it, if nothing is
     * held, or if [generation] is 0L. One atomic [state.update] pass compares
     * the generation and clears the same state capture writes, so a begin()
     * racing in between cannot have its anchor wiped by a late clear.
     */
    fun clearIfGeneration(generation: Long) {
        state.update {
            if (it != null && generation != 0L && it.generation == generation) null else it
        }
    }

    /** Drop the anchor. Internal, for tests, so one test leaks nothing into another. Does not reset the counter. */
    internal fun reset() {
        clear()
    }
}
