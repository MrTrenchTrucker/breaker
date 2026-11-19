package dev.breaker.dictation.commit

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The atomic generation token bundled with the held window in TakeStartAnchor.
 *
 * Every capture mints a generation greater than zero that differs from every
 * earlier one in this process, and clearIfGeneration clears only a matching
 * generation in one atomic state.update pass. Two captures of the same
 * TakeStartWindow (a data class with value equality) get different
 * generations: the token is what keeps a late clear from wiping a freshly
 * re-captured identical window.
 *
 * The holder is one process-wide object, so every test resets it before and
 * after, and no test depends on what another test left behind. reset() clears
 * the anchor but never the counter, so generations only ever climb across
 * tests; every assertion here is relative, never an absolute number.
 */
internal class TakeStartAnchorGenerationTest {

    @Before
    fun startFromNoAnchor() {
        TakeStartAnchor.reset()
    }

    @After
    fun endWithNoAnchor() {
        TakeStartAnchor.reset()
    }

    @Test
    fun `generation advances on every capture`() {
        TakeStartAnchor.capture(TakeStartWindow(windowId = 1, packageName = "com.first.app"))
        val first = TakeStartAnchor.generation()
        TakeStartAnchor.capture(TakeStartWindow(windowId = 2, packageName = "com.second.app"))
        val second = TakeStartAnchor.generation()
        assertTrue("commit: every capture bumps the generation", first < second)
        assertTrue("commit: a held anchor carries a generation greater than zero", first > 0L)
    }

    @Test
    fun `two captures of the same equal window get different generations the data class trap`() {
        val window = TakeStartWindow(windowId = 7, packageName = "com.same.app")
        TakeStartAnchor.capture(window)
        val first = TakeStartAnchor.generation()
        TakeStartAnchor.capture(window)
        val second = TakeStartAnchor.generation()
        assertNotEquals("commit: equal windows are not equal generations", first, second)
        assertTrue("commit: both generations are greater than zero", first > 0L && second > 0L)
    }

    @Test
    fun `clearIfGeneration with an older generation leaves the newer anchor`() {
        TakeStartAnchor.capture(TakeStartWindow(windowId = 1, packageName = "com.first.app"))
        val old = TakeStartAnchor.generation()
        TakeStartAnchor.capture(TakeStartWindow(windowId = 2, packageName = "com.second.app"))
        val currentGen = TakeStartAnchor.generation()
        TakeStartAnchor.clearIfGeneration(old)
        val still = TakeStartAnchor.current()
        assertNotNull("commit: clearing an old generation leaves the newer anchor", still)
        assertEquals("commit: the newer window is still current", 2, still?.windowId)
        assertEquals("commit: the current generation is unchanged", currentGen, TakeStartAnchor.generation())
    }

    @Test
    fun `clearIfGeneration with the current generation clears`() {
        TakeStartAnchor.capture(TakeStartWindow(windowId = 3, packageName = "com.current.app"))
        val gen = TakeStartAnchor.generation()
        TakeStartAnchor.clearIfGeneration(gen)
        assertNull("commit: clearing the current generation drops the anchor", TakeStartAnchor.current())
        assertEquals("commit: after a successful clearIfGeneration the generation is 0L", 0L, TakeStartAnchor.generation())
    }

    @Test
    fun `clearIfGeneration with 0L clears nothing`() {
        TakeStartAnchor.capture(TakeStartWindow(windowId = 5, packageName = "com.zero.app"))
        TakeStartAnchor.clearIfGeneration(0L)
        assertNotNull("commit: clearIfGeneration(0L) is a no-op", TakeStartAnchor.current())
    }

    @Test
    fun `clearIfGeneration on an empty anchor clears nothing`() {
        TakeStartAnchor.reset()
        val before = TakeStartAnchor.generation()
        TakeStartAnchor.clearIfGeneration(12345L)
        assertNull("commit: an empty anchor stays empty", TakeStartAnchor.current())
        assertEquals("commit: clearIfGeneration on empty does not mint a generation", before, TakeStartAnchor.generation())
    }

    @Test
    fun `clear stays unconditional`() {
        TakeStartAnchor.capture(TakeStartWindow(windowId = 9, packageName = "com.clear.app"))
        TakeStartAnchor.clear()
        assertNull("commit: clear drops the anchor unconditionally", TakeStartAnchor.current())
    }

    @Test
    fun `capture(null) holds the no field value with a generation greater than zero`() {
        TakeStartAnchor.capture(null)
        val current = TakeStartAnchor.current()
        val gen = TakeStartAnchor.generation()
        assertNotNull("commit: capture(null) stores a non-null no-field anchor", current)
        assertNull("commit: the no-field value carries no window id", current?.windowId)
        assertNull("commit: the no-field value carries no package", current?.packageName)
        assertTrue("commit: capture(null) holds a generation greater than zero", gen > 0L)
    }

    @Test
    fun `a clear then a capture still mints a fresh generation`() {
        TakeStartAnchor.capture(TakeStartWindow(windowId = 1, packageName = "com.first.app"))
        val first = TakeStartAnchor.generation()
        TakeStartAnchor.clear()
        TakeStartAnchor.capture(TakeStartWindow(windowId = 1, packageName = "com.first.app"))
        val second = TakeStartAnchor.generation()
        assertNotEquals("commit: a capture after a clear mints a fresh generation", first, second)
        assertTrue("commit: generations only climb, reset never rewinds the counter", first < second)
    }
}
