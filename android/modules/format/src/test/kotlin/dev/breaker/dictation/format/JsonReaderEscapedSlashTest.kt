package dev.breaker.dictation.format

import org.junit.Assert.assertEquals
import org.junit.Test

// JSON allows the slash to be written as backslash-slash, and some servers do it for every slash.
class JsonReaderEscapedSlashTest {

    @Test
    fun `an escaped slash in a string decodes to a slash`() {
        val json = """{"content":"a\/b and c\/d"}"""

        val tree = JsonReader.parse(json) as Map<*, *>

        assertEquals("a/b and c/d", tree["content"])
    }
}
