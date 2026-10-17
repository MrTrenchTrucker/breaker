package dev.breaker.dictation.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule that the inset probe reports a change: a repeated pair is refused, every other pair is taken. */
class InsetChangeFilterTest {

    /** A failure here means the first pair the probe sees is not reported at all. */
    @Test
    fun `the first pair is accepted`() {
        val filter = InsetChangeFilter()

        assertTrue("overlay: the first pair must be accepted", filter.accept(0, false))
    }

    /** A failure here means the same pair is reported again, so the probe would report every dispatch. */
    @Test
    fun `the same pair twice is accepted once`() {
        val filter = InsetChangeFilter()

        assertTrue("overlay: the first pair must be accepted", filter.accept(640, true))
        assertFalse("overlay: the same pair again must not be accepted", filter.accept(640, true))
    }

    /** A failure here means a new bottom inset is not reported. */
    @Test
    fun `a changed bottom is accepted`() {
        val filter = InsetChangeFilter()
        filter.accept(640, true)

        assertTrue("overlay: a changed bottom must be accepted", filter.accept(900, true))
    }

    /** A failure here means a keyboard that is shown or hidden without a change of height is not reported. */
    @Test
    fun `a changed visibility is accepted`() {
        val filter = InsetChangeFilter()
        filter.accept(640, true)

        assertTrue("overlay: a changed visibility must be accepted", filter.accept(640, false))
    }

    /** A failure here means forget does not make the next pair count as the first one. */
    @Test
    fun `forgetting makes the next pair count as first`() {
        val filter = InsetChangeFilter()
        filter.accept(640, true)
        filter.forget()

        assertTrue("overlay: after forget the same pair must be accepted as the first", filter.accept(640, true))
    }
}
