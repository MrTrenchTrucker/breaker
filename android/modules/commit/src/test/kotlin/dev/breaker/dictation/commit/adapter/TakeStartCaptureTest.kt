package dev.breaker.dictation.commit.adapter

import dev.breaker.dictation.commit.FakeFocus
import dev.breaker.dictation.commit.FailingMainThread
import dev.breaker.dictation.commit.FieldCommit
import dev.breaker.dictation.commit.FocusedField
import dev.breaker.dictation.commit.HopProbe
import dev.breaker.dictation.commit.InlineMainThread
import dev.breaker.dictation.commit.LateMainThread
import dev.breaker.dictation.commit.MainThread
import dev.breaker.dictation.commit.TakeStartAnchor
import dev.breaker.dictation.commit.TakeStartWindow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The take-start capture: a live read stores the identity, and every failure
 * path still stores the no-field value, so a commit is never left without a
 * check because the hop misbehaved.
 *
 * The holder is one process-wide object, so every test resets it before and
 * after, and no test depends on what another test left behind.
 */
internal class TakeStartCaptureTest {

    /** A field whose window answer is fixed; it is never asked to type. */
    private class WindowField(private val window: TakeStartWindow?) : FocusedField {
        override fun commitText(text: String): FieldCommit = FieldCommit.ACCEPTED
        override fun focusedWindow(): TakeStartWindow? = window
    }

    /**
     * A hop that throws an [Error]. [Error] is not an [Exception], so the shared FailingMainThread
     * (which takes an Exception) cannot produce this path; this one is local to the D2 test.
     */
    private class ErrorMainThread(private val failure: Error) : MainThread {
        override fun <T> call(block: () -> T): T {
            throw failure
        }
    }

    @Before
    fun startFromNoAnchor() {
        TakeStartAnchor.reset()
    }

    @After
    fun endWithNoAnchor() {
        TakeStartAnchor.reset()
    }

    @Test
    fun `a working read stores the window identity`() {
        val probe = HopProbe()
        val window = TakeStartWindow(windowId = 4242, packageName = "com.real.notes")
        val hop = InlineMainThread(probe)

        TakeStartCapture(FakeFocus(probe, WindowField(window)), hop).capture()

        assertEquals("commit: the read must go through the hop exactly once", 1, hop.calls)
        val current: TakeStartWindow? = TakeStartAnchor.current()
        assertNotNull("commit: a working read must store an anchor", current)
        assertEquals("commit: the anchor must keep the live window id", 4242, current?.windowId)
        assertEquals("commit: the anchor must keep the live package", "com.real.notes", current?.packageName)
    }

    @Test
    fun `a null focused field stores the no field value`() {
        val probe = HopProbe()

        TakeStartCapture(FakeFocus(probe, null), InlineMainThread(probe)).capture()

        val current: TakeStartWindow? = TakeStartAnchor.current()
        assertNotNull("commit: a null field must still store the no-field value", current)
        assertNull("commit: the no-field value carries no window id", current?.windowId)
        assertNull("commit: the no-field value carries no package", current?.packageName)
    }

    @Test
    fun `a field that reports no window stores the no field value`() {
        val probe = HopProbe()

        TakeStartCapture(FakeFocus(probe, WindowField(null)), InlineMainThread(probe)).capture()

        val current: TakeStartWindow? = TakeStartAnchor.current()
        assertNotNull("commit: a field with no window must still store the no-field value", current)
        assertNull("commit: the no-field value carries no window id", current?.windowId)
        assertNull("commit: the no-field value carries no package", current?.packageName)
    }

    @Test
    fun `a main thread that was never reached stores the no field value and returns`() {
        val probe = HopProbe()
        val hop = LateMainThread(probe)

        TakeStartCapture(FakeFocus(probe, WindowField(TakeStartWindow(1, "com.real.notes"))), hop).capture()

        assertEquals("commit: the refused block must be kept, not run", 1, hop.pending.size)
        val current: TakeStartWindow? = TakeStartAnchor.current()
        assertNotNull("commit: a refused hop must store the no-field value, never leave the anchor null", current)
        assertNull("commit: the no-field value carries no window id", current?.windowId)
        assertNull("commit: the no-field value carries no package", current?.packageName)
    }

    @Test
    fun `a throwing hop stores the no field value and returns`() {
        val probe = HopProbe()

        TakeStartCapture(FakeFocus(probe, WindowField(TakeStartWindow(1, "com.real.notes"))), FailingMainThread(IllegalStateException("test: the hop failed"))).capture()

        val current: TakeStartWindow? = TakeStartAnchor.current()
        assertNotNull("commit: a throwing hop must store the no-field value, never leave the anchor null", current)
        assertNull("commit: the no-field value carries no window id", current?.windowId)
        assertNull("commit: the no-field value carries no package", current?.packageName)
    }

    @Test
    fun `an interrupted hop stores the no field value, returns and re arms the interrupt`() {
        val probe = HopProbe()

        TakeStartCapture(
            FakeFocus(probe, WindowField(TakeStartWindow(1, "com.real.notes"))),
            FailingMainThread(InterruptedException("test: the hop was interrupted")),
        ).capture()

        val current: TakeStartWindow? = TakeStartAnchor.current()
        assertNotNull("commit: an interrupted hop must store the no-field value, never leave the anchor null", current)
        assertNull("commit: the no-field value carries no window id", current?.windowId)
        try {
            assertTrue(
                "commit: an interrupted hop must put the interrupt back on the caller's thread",
                Thread.currentThread().isInterrupted,
            )
        } finally {
            // Read and clear, so the flag cannot leak into another test.
            Thread.interrupted()
        }
    }

    @Test
    fun `a failed capture replaces an earlier anchor instead of leaving it`() {
        val probe = HopProbe()
        val capture = TakeStartCapture(FakeFocus(probe, WindowField(TakeStartWindow(11, "com.first.app"))), InlineMainThread(probe))

        capture.capture()
        assertEquals("commit: the first capture must be the current anchor", 11, TakeStartAnchor.current()?.windowId)

        val failing = TakeStartCapture(FakeFocus(probe, WindowField(TakeStartWindow(12, "com.second.app"))), FailingMainThread(IllegalStateException("test: the hop failed")))
        failing.capture()

        val current: TakeStartWindow? = TakeStartAnchor.current()
        assertNotNull("commit: a failed capture must overwrite the old anchor, not leave it in place", current)
        assertNull("commit: the failed capture stored the no-field value", current?.windowId)
        assertNull("commit: the failed capture carried no package", current?.packageName)
    }

    /**
     * D2: an Error from the hop must store the no-field value FIRST and then be rethrown. Catching
     * only Exception let the Error skip the store, so the anchor kept an EARLIER take's window and a
     * later send committed with no window check at all.
     */
    @Test
    fun `an Error from the hop stores the no field value and is rethrown`() {
        val probe = HopProbe()
        // An earlier take left a real window in the holder: the Error must not leave it in place.
        TakeStartAnchor.capture(TakeStartWindow(31, "com.earlier.app"))
        val boom = AssertionError("test: the hop threw an Error")
        var thrown: Throwable? = null
        try {
            TakeStartCapture(
                FakeFocus(probe, WindowField(TakeStartWindow(1, "com.real.notes"))),
                ErrorMainThread(boom),
            ).capture()
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("commit: an Error must never be swallowed", thrown === boom)
        val current: TakeStartWindow? = TakeStartAnchor.current()
        assertNotNull("commit: an Error must store the no-field value, not leave the earlier anchor", current)
        assertNull("commit: the stored value carries no window id", current?.windowId)
        assertNull("commit: the stored value carries no package", current?.packageName)
    }
}
