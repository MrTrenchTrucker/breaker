package dev.breaker.dictation.commit

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The take-start anchor: one value, replaced by a newer one, dropped by clear,
 * and never confused with "no take".
 *
 * The holder is one process-wide object, so every test resets it before and
 * after, and no test depends on what another test left behind.
 */
internal class TakeStartAnchorTest {

    @Before
    fun startFromNoAnchor() {
        TakeStartAnchor.reset()
    }

    @After
    fun endWithNoAnchor() {
        TakeStartAnchor.reset()
    }

    @Test
    fun `current is null before any capture`() {
        assertNull("commit: a fresh holder must hold no anchor", TakeStartAnchor.current())
    }

    @Test
    fun `a captured window is current with its id and package`() {
        val window = TakeStartWindow(windowId = 4242, packageName = "com.real.notes")

        TakeStartAnchor.capture(window)

        val current: TakeStartWindow? = TakeStartAnchor.current()
        assertNotNull("commit: a capture must store an anchor", current)
        assertEquals("commit: the anchor must keep the captured window id", 4242, current?.windowId)
        assertEquals("commit: the anchor must keep the captured package", "com.real.notes", current?.packageName)
    }

    @Test
    fun `capturing null stores the no field value, not null`() {
        TakeStartAnchor.capture(null)

        val current: TakeStartWindow? = TakeStartAnchor.current()
        assertNotNull("commit: capture(null) must store a non-null no-field value, not null", current)
        assertNull("commit: the no-field value carries no window id", current?.windowId)
        assertNull("commit: the no-field value carries no package", current?.packageName)
    }

    @Test
    fun `a clear after a capture leaves no anchor`() {
        TakeStartAnchor.capture(TakeStartWindow(windowId = 7, packageName = "com.real.notes"))

        TakeStartAnchor.clear()

        assertNull("commit: a clear must drop the anchor", TakeStartAnchor.current())
    }

    @Test
    fun `a second capture replaces the first`() {
        TakeStartAnchor.capture(TakeStartWindow(windowId = 1, packageName = "com.first.app"))
        TakeStartAnchor.capture(TakeStartWindow(windowId = 2, packageName = "com.second.app"))

        val current: TakeStartWindow? = TakeStartAnchor.current()
        assertEquals("commit: a newer take must overwrite the older window id", 2, current?.windowId)
        assertEquals(
            "commit: a newer take must overwrite the older package",
            "com.second.app",
            current?.packageName,
        )
    }

    @Test
    fun `a no field capture after a clear is still a captured take`() {
        TakeStartAnchor.capture(TakeStartWindow(windowId = 9, packageName = "com.real.notes"))
        TakeStartAnchor.clear()

        TakeStartAnchor.capture(null)

        val current: TakeStartWindow? = TakeStartAnchor.current()
        assertNotNull("commit: capture(null) after a clear must store the no-field value", current)
        assertNull("commit: the no-field value after a clear carries no window id", current?.windowId)
        assertNull("commit: the no-field value after a clear carries no package", current?.packageName)
    }

    @Test
    fun `the window prints the fixed text and neither the id nor the package`() {
        val window = TakeStartWindow(windowId = 51713, packageName = "com.spoof.notes")

        val printed: String = window.toString()

        assertEquals("commit: a take window must print the fixed text", "TakeStartWindow", printed)
        assertFalse("commit: the printed form must not carry the window id", printed.contains(51713.toString()))
        assertFalse("commit: the printed form must not carry the package", printed.contains("com.spoof.notes"))
    }

    @Test
    fun `a reset behaves as a clear`() {
        TakeStartAnchor.capture(TakeStartWindow(windowId = 3, packageName = "com.real.notes"))

        TakeStartAnchor.reset()
        assertNull("commit: a reset must drop the anchor like a clear", TakeStartAnchor.current())

        TakeStartAnchor.capture(null)
        TakeStartAnchor.reset()
        assertNull("commit: a reset must drop even the no-field value", TakeStartAnchor.current())
    }

    @Test
    fun `many writes in one thread leave the latest write current`() {
        // One thread, so this is a sequence check, not the multi-thread proof:
        // the module's test scan allows a thread constructor in one named hop file only.
        for (id in 1..200) {
            TakeStartAnchor.capture(TakeStartWindow(windowId = id, packageName = "com.loop.app"))
            TakeStartAnchor.clear()
        }

        TakeStartAnchor.capture(null)

        val current: TakeStartWindow? = TakeStartAnchor.current()
        assertNotNull("commit: the last write must stay current after many writes", current)
        assertNull("commit: the last write was the no-field value", current?.windowId)
        assertNull("commit: the last write carried no package", current?.packageName)
    }
}
