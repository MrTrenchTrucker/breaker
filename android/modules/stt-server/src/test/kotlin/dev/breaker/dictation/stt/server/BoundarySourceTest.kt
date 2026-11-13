package dev.breaker.dictation.stt.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The random multipart boundary is 128 random bits written as 32 lower-case hex characters. The tests
 * name the length and the alphabet, so a shorter or a differently written boundary is a failure.
 */
class BoundarySourceTest {
    @Test
    fun `the random boundary is 32 lower case hex characters`() {
        val boundary = BoundarySource.RANDOM.next()
        assertEquals("a boundary of 128 random bits is 32 hex characters: $boundary", 32, boundary.length)
        assertTrue("only 0-9 and a-f: $boundary", boundary.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `two random boundaries differ`() {
        assertNotEquals(BoundarySource.RANDOM.next(), BoundarySource.RANDOM.next())
    }
}
