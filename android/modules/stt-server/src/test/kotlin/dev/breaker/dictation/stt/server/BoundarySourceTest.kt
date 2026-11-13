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
    fun `every random boundary is 32 lower case hex characters`() {
        // A byte below 0x10 must still be written with two characters, which one boundary shows only
        // about two times in three: two hundred boundaries show it every time.
        repeat(200) { n ->
            val boundary = BoundarySource.RANDOM.next()
            assertEquals("boundary $n of 128 random bits is 32 hex characters: $boundary", 32, boundary.length)
            assertTrue("boundary $n holds only 0-9 and a-f: $boundary", boundary.all { it in '0'..'9' || it in 'a'..'f' })
        }
    }

    @Test
    fun `two random boundaries differ`() {
        assertNotEquals(BoundarySource.RANDOM.next(), BoundarySource.RANDOM.next())
    }
}
