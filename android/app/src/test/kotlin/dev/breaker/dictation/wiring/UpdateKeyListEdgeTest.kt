package dev.breaker.dictation.wiring

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge cases of the key list text that the build's key check decides the same way: a byte order mark
 * before the first key, a tab around a key, a space inside a key line, and an indented number sign line.
 * Each one is decided by the same trim and the same canonical base64 test that the build uses.
 */
class UpdateKeyListEdgeTest {

    private val key: ByteArray = ByteArray(32) { 3 }
    private val keyText: String = Base64.getEncoder().encodeToString(key)
    private val byteOrderMark: String = 0xFEFF.toChar().toString()

    @Test
    fun `a byte order mark before the first key gives an empty list`() {
        assertTrue("app: a byte order mark is not a key", UpdateKeyList.parse(byteOrderMark + keyText + "\n").isEmpty())
    }

    @Test
    fun `a tab around a key is trimmed the same as a space`() {
        val keys = UpdateKeyList.parse("\t" + keyText + "\t\n")
        assertEquals("app: one key with tabs around it", 1, keys.size)
        assertArrayEquals("app: the key bytes", key, keys[0])
    }

    @Test
    fun `a space inside a key line gives an empty list`() {
        val spaced = keyText.substring(0, 10) + " " + keyText.substring(10)
        assertTrue("app: a key with a space inside gives no key", UpdateKeyList.parse(spaced + "\n").isEmpty())
    }

    @Test
    fun `an indented number sign line with a tab before it is skipped`() {
        val keys = UpdateKeyList.parse("\t# the list\n" + keyText + "\n")
        assertEquals("app: the comment with a tab before it is not a key", 1, keys.size)
    }
}
