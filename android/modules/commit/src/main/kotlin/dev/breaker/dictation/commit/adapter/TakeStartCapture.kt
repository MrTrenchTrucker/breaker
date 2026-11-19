package dev.breaker.dictation.commit.adapter

import dev.breaker.dictation.commit.FocusedFieldSource
import dev.breaker.dictation.commit.MainThread
import dev.breaker.dictation.commit.TakeStartAnchor
import dev.breaker.dictation.commit.TakeStartWindow

/**
 * Captures the window a take started in, on the main thread the commit uses.
 *
 * The app calls [capture] once at take start and owns no hopper of its own:
 * the hop is the one [CommitService] already makes. The read is live — the
 * focused field is asked where it is right now — and only the id and the
 * package are kept, never a node and never text.
 *
 * It fails closed. A hop that throws (the main thread was not reached in time,
 * refused the post, or the service is gone), a source with no field, a field
 * with no window, any Throwable at all: every one of them stores the no-field
 * value, a [TakeStartWindow] with both fields null, through
 * [TakeStartAnchor.capture]. The store happens BEFORE an Error is rethrown, so
 * an Error can never leave the anchor at its old value. An Error is never
 * swallowed. The anchor is never left inactive, because inactive means only
 * "no capture was attempted for this take"; a capture that ran and found
 * nothing is a real answer, and a commit must see it as one.
 *
 * [capture] returns normally on every path except an Error, which it rethrows after storing,
 * and never waits indefinitely: the
 * deadline belongs to the hopper it was given, exactly as it does for the
 * commit. The no-field value is stored FIRST, so an Error from the hop still fails closed;
 * an interrupted wait is then put back on the caller's thread (the threading rule in the
 * root card) and an Error is rethrown.
 */
class TakeStartCapture internal constructor(
    private val focus: FocusedFieldSource,
    private val mainThread: MainThread,
) {
    fun capture() {
        val window: TakeStartWindow? = try {
            mainThread.call { focus.current()?.focusedWindow() }
        } catch (t: Throwable) {
            // Fail closed FIRST. An Error (OOM, NoClassDefFoundError, StackOverflowError) is not an
            // Exception: catching only Exception let it skip the store below, so the anchor kept its
            // old value and a later send committed with no window check at all.
            TakeStartAnchor.capture(null)
            // Re-armed: the interrupt belongs to the caller's thread, and the
            // catch would otherwise swallow it (the threading rule in the root card).
            if (t is InterruptedException) Thread.currentThread().interrupt()
            // Never swallow an Error: the platform and the caller's own failure path both need it.
            if (t is Error) throw t
            null
        }
        // Unconditional: one compare-and-set of an immutable value cannot throw,
        // so no path ends without an anchor.
        TakeStartAnchor.capture(window)
    }
}
