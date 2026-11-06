package dev.breaker.dictation.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * The holder of stored account keys: the keys plus the version of the key set. It refuses a
 * negative version before it copies anything, holds its own copy of both keys, wipes only that
 * copy, prints a fixed text and is a plain class.
 *
 * Every size in these tests is a literal number, so a wrong constant in the production code
 * cannot also make the tests agree with it.
 */
class StoredAccountKeysTest {
    private val expectedText = "StoredAccountKeys(redacted)"

    private fun filled(size: Int, value: Int): ByteArray = ByteArray(size) { value.toByte() }

    /** The caller's side: the two arrays and the account keys built from them. */
    private class Caller(val dekBytes: ByteArray, val boxBytes: ByteArray) {
        val keys = AccountKeys(DataEncryptionKey(dekBytes), BoxPrivateKey(boxBytes))
    }

    private fun aCaller(dekValue: Int = 0x11, boxValue: Int = 0x22): Caller =
        Caller(filled(32, dekValue), filled(32, boxValue))

    private fun refusedMessage(what: String, block: () -> Unit): String {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return expected.message.orEmpty()
        }
        fail("$what was accepted but must be refused with IllegalArgumentException")
        return ""
    }

    @Test
    fun `version zero and a large version are accepted and read back as given`() {
        assertEquals(0, StoredAccountKeys(aCaller().keys, 0).keyVersion)
        assertEquals(Int.MAX_VALUE, StoredAccountKeys(aCaller().keys, Int.MAX_VALUE).keyVersion)
        assertEquals(7351, StoredAccountKeys(aCaller().keys, 7351).keyVersion)
    }

    @Test
    fun `a negative version is refused and the message holds no number`() {
        for (version in listOf(-1, Int.MIN_VALUE)) {
            val what = "a negative key version"
            val message = refusedMessage(what) { StoredAccountKeys(aCaller().keys, version) }
            assertFalse("$what: the message holds a digit", message.any { it.isDigit() })
            assertFalse("$what: the message holds a minus sign", message.contains("-"))
        }
    }

    @Test
    fun `a refused version leaves the caller arrays untouched`() {
        val caller = aCaller(0x5A, 0x6B)
        refusedMessage("a negative key version") { StoredAccountKeys(caller.keys, -1) }

        assertArrayEquals("the version check changed the caller dek array", filled(32, 0x5A), caller.dekBytes)
        assertArrayEquals("the version check changed the caller box array", filled(32, 0x6B), caller.boxBytes)
        assertArrayEquals(
            "the version check changed the caller box key",
            filled(32, 0x6B),
            caller.keys.boxPrivateKey.copyBytes(),
        )
    }

    @Test
    fun `the holder prints exactly its fixed text and no key byte or version`() {
        val holder = StoredAccountKeys(aCaller(0xC7, 0xC7).keys, 7351)

        assertEquals(
            "the holder printed something other than its fixed text",
            expectedText,
            holder.toString(),
        )
        holder.wipe()
        assertEquals("the holder printed something else after wipe", expectedText, holder.toString())
        val printed = holder.toString()
        val hex = 199.toString(16)
        for (shown in listOf("199", "-57", hex, hex.uppercase(), "7351", "[B@", "32")) {
            assertFalse("the holder shows $shown", printed.contains(shown))
        }
    }

    @Test
    fun `the constructor copies so changing the caller keys afterwards changes nothing held`() {
        val caller = aCaller(0x33, 0x44)
        val holder = StoredAccountKeys(caller.keys, 3)

        caller.dekBytes.fill(0x77.toByte())
        caller.keys.wipe()

        assertArrayEquals("the held dek followed the caller array", filled(32, 0x33), holder.keys.dek.bytes)
        assertArrayEquals(
            "the held box key followed the caller keys",
            filled(32, 0x44),
            holder.keys.boxPrivateKey.copyBytes(),
        )
    }

    @Test
    fun `the held keys carry the caller bytes`() {
        val holder = StoredAccountKeys(aCaller(0x11, 0x22).keys, 5)

        assertEquals("the held dek has the wrong length", 32, holder.keys.dek.bytes.size)
        assertArrayEquals("the held dek differs from the caller dek", filled(32, 0x11), holder.keys.dek.bytes)
        assertEquals("the held box key has the wrong length", 32, holder.keys.boxPrivateKey.copyBytes().size)
        assertArrayEquals(
            "the held box key differs from the caller box key",
            filled(32, 0x22),
            holder.keys.boxPrivateKey.copyBytes(),
        )
    }

    @Test
    fun `wiping the holder zeroes the held dek and the held box key and not the caller arrays`() {
        val caller = aCaller(0x55, 0x66)
        val holder = StoredAccountKeys(caller.keys, 9)

        holder.wipe()

        assertArrayEquals("wipe left the held dek unzeroed", ByteArray(32), holder.keys.dek.bytes)
        assertArrayEquals(
            "wipe left the held box key unzeroed",
            ByteArray(32),
            holder.keys.boxPrivateKey.copyBytes(),
        )
        assertArrayEquals("wipe zeroed the caller dek array", filled(32, 0x55), caller.dekBytes)
        assertArrayEquals(
            "wipe zeroed the caller box key",
            filled(32, 0x66),
            caller.keys.boxPrivateKey.copyBytes(),
        )
    }

    @Test
    fun `the holder is a plain class with no copy component or equals`() {
        val names = StoredAccountKeys::class.java.declaredMethods.map { it.name }
        val message = "StoredAccountKeys gained generated or value members that could duplicate keys"
        assertFalse(message, names.contains("copy"))
        assertFalse(message, names.any { it.startsWith("component") })
        assertFalse(message, names.contains("equals"))
        assertFalse(message, names.contains("hashCode"))

        val first = StoredAccountKeys(aCaller(0x11, 0x22).keys, 1)
        val second = StoredAccountKeys(aCaller(0x11, 0x22).keys, 1)
        assertNotEquals("two holders of equal bytes became value-equal", first, second)
    }
}
