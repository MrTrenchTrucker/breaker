package dev.breaker.dictation.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * The value types that carry a password-derived key pair.
 *
 * Each secret is exactly 32 bytes, is copied on the way in and on the way out
 * so no caller can reach the internal array, can be wiped in place without
 * touching the caller's own array, and never prints its bytes. The parameter
 * carrier does no judging of its own: any number is stored as given, because
 * deciding what is acceptable belongs to the code that derives keys. The
 * refusal types give that code one named reason per way of saying no.
 *
 * Every size in these tests is a literal number, so a wrong constant in the
 * production code cannot also make the tests agree with it.
 */
class KdfModelsTest {
    /** A secret seen through the same four operations, whichever type it is. */
    private class Secret(
        val label: String,
        val bytes: () -> ByteArray,
        val wipe: () -> Unit,
        val text: () -> String,
    )

    private fun filled(size: Int, value: Int): ByteArray = ByteArray(size) { value.toByte() }

    /** Both secret types, built from the same caller array. */
    private fun secretsFrom(source: ByteArray): List<Secret> {
        val kek = KeyEncryptionKey(source)
        val verifier = AuthVerifier(source)
        return listOf(
            Secret("KeyEncryptionKey", { kek.copyBytes() }, { kek.wipe() }, { kek.toString() }),
            Secret("AuthVerifier", { verifier.copyBytes() }, { verifier.wipe() }, { verifier.toString() }),
        )
    }

    private fun refusedMessage(what: String, block: () -> Unit): String {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return expected.message.orEmpty()
        }
        fail("$what was accepted but must be refused with IllegalArgumentException")
        return ""
    }

    private fun assertMessageHidesKeyBytes(what: String, message: String) {
        assertFalse("$what: the message shows a key byte in decimal", message.contains("90"))
        assertFalse("$what: the message shows a key byte in upper-case hex", message.contains("5A"))
        assertFalse("$what: the message shows a key byte in lower-case hex", message.contains("5a"))
    }

    @Test
    fun `a key encryption key refuses every length except 32 bytes`() {
        for (size in listOf(0, 31, 33)) {
            val what = "a KeyEncryptionKey of $size bytes"
            assertMessageHidesKeyBytes(what, refusedMessage(what) { KeyEncryptionKey(filled(size, 0x5A)) })
        }
        assertEquals("a 32 byte KeyEncryptionKey must be accepted", 32, KeyEncryptionKey(filled(32, 0x5A)).copyBytes().size)
    }

    @Test
    fun `an auth verifier refuses every length except 32 bytes`() {
        for (size in listOf(0, 31, 33)) {
            val what = "an AuthVerifier of $size bytes"
            assertMessageHidesKeyBytes(what, refusedMessage(what) { AuthVerifier(filled(size, 0x5A)) })
        }
        assertEquals("a 32 byte AuthVerifier must be accepted", 32, AuthVerifier(filled(32, 0x5A)).copyBytes().size)
    }

    @Test
    fun `a secret copies the caller array when it is built`() {
        val source = filled(32, 0x11)
        val secrets = secretsFrom(source)
        source.fill(0x22.toByte())
        for (secret in secrets) {
            assertArrayEquals("${secret.label} changed when the caller overwrote its source array", filled(32, 0x11), secret.bytes())
        }
    }

    @Test
    fun `a secret hands out a new copy on every read`() {
        for (secret in secretsFrom(filled(32, 0x11))) {
            val first = secret.bytes()
            val second = secret.bytes()
            assertNotSame("${secret.label} returned the same array twice", first, second)
            first[0] = 0x7F
            assertArrayEquals("${secret.label} changed when a caller edited an array it read", filled(32, 0x11), secret.bytes())
        }
    }

    @Test
    fun `wiping a secret zeroes it and leaves the caller array alone`() {
        val source = filled(32, 0x33)
        val secrets = secretsFrom(source)
        secrets[0].wipe()
        assertArrayEquals("wiping the key encryption key changed the auth verifier", filled(32, 0x33), secrets[1].bytes())
        secrets[1].wipe()
        for (secret in secrets) {
            assertArrayEquals("${secret.label} is not 32 zero bytes after wipe", ByteArray(32), secret.bytes())
        }
        assertArrayEquals("wiping a secret zeroed the caller's own array", filled(32, 0x33), source)
    }

    @Test
    fun `a secret and the derived pair print a fixed text with no bytes and keep it after wipe`() {
        val kek = KeyEncryptionKey(filled(32, 0xAB))
        val verifier = AuthVerifier(filled(32, 0xAB))
        val keys = DerivedKeys(kek, verifier)
        val expected = listOf(
            "KeyEncryptionKey(32 bytes)" to { kek.toString() },
            "AuthVerifier(32 bytes)" to { verifier.toString() },
            "DerivedKeys(kek=KeyEncryptionKey(32 bytes), authVerifier=AuthVerifier(32 bytes))" to { keys.toString() },
        )
        for ((text, actual) in expected) {
            assertEquals("the printed form is not the fixed text", text, actual())
            assertFalse("the printed form shows an array identity", actual().contains("[B@"))
        }
        keys.wipe()
        for ((text, actual) in expected) {
            assertEquals("the printed form changed after wipe", text, actual())
        }
    }

    @Test
    fun `wiping the derived pair zeroes both members and the members are the ones passed in`() {
        val kek = KeyEncryptionKey(filled(32, 0x44))
        val verifier = AuthVerifier(filled(32, 0x55))
        val keys = DerivedKeys(kek, verifier)

        assertSame("kek is not the instance passed in", kek, keys.kek)
        assertSame("authVerifier is not the instance passed in", verifier, keys.authVerifier)

        keys.wipe()

        assertArrayEquals("wipe left the kek unzeroed", ByteArray(32), keys.kek.copyBytes())
        assertArrayEquals("wipe left the auth verifier unzeroed", ByteArray(32), keys.authVerifier.copyBytes())
    }

    @Test
    fun `kdf params carry any number in any position without judging it`() {
        val names = listOf("memoryKib", "iterations", "parallelism", "outputLength", "version")
        val normal = intArrayOf(65536, 3, 4, 32, 19)
        for (position in names.indices) {
            for (value in listOf(0, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
                val numbers = normal.copyOf()
                numbers[position] = value
                val params = KdfParams(numbers[0], numbers[1], numbers[2], numbers[3], numbers[4])
                val read = intArrayOf(params.memoryKib, params.iterations, params.parallelism, params.outputLength, params.version)
                assertArrayEquals("${names[position]} = $value was not carried back unchanged", numbers, read)
            }
        }
    }

    @Test
    fun `kdf params compare by value and copy changes only the named field`() {
        val params = KdfParams(1, 2, 3, 4, 5)
        val same = KdfParams(1, 2, 3, 4, 5)
        assertEquals("two params with the same numbers are not equal", params, same)
        assertEquals("equal params have different hash codes", params.hashCode(), same.hashCode())

        val changed = listOf(
            params.copy(memoryKib = 99) to KdfParams(99, 2, 3, 4, 5),
            params.copy(iterations = 99) to KdfParams(1, 99, 3, 4, 5),
            params.copy(parallelism = 99) to KdfParams(1, 2, 99, 4, 5),
            params.copy(outputLength = 99) to KdfParams(1, 2, 3, 99, 5),
            params.copy(version = 99) to KdfParams(1, 2, 3, 4, 99),
        )
        for ((copy, expected) in changed) {
            assertEquals("copy changed more or less than the one named field", expected, copy)
            assertNotEquals("copy with a new number is still equal to the original", params, copy)
        }
    }

    @Test
    fun `the refusal reasons are exactly the nine names in order`() {
        val expected = listOf(
            "MEMORY_BELOW_FLOOR",
            "MEMORY_ABOVE_CEILING",
            "ITERATIONS_BELOW_FLOOR",
            "ITERATIONS_ABOVE_CEILING",
            "PARALLELISM_BELOW_FLOOR",
            "PARALLELISM_ABOVE_CEILING",
            "OUTPUT_LENGTH_NOT_32",
            "BAD_SALT_LENGTH",
            "UNSUPPORTED_VERSION",
        )
        assertEquals("the refusal reasons are not the nine expected names in order", expected, KdfRefusal.values().map { it.name })
    }

    @Test
    fun `a refusal is a real IllegalArgumentException that names its reason`() {
        for (value in KdfRefusal.values()) {
            val caught: IllegalArgumentException = try {
                throw KdfRefused(value)
            } catch (expected: IllegalArgumentException) {
                expected
            }
            assertTrue("$value: the caught exception is not a KdfRefused", caught is KdfRefused)
            assertEquals("$value: wrong reason", value, (caught as KdfRefused).reason)
            assertEquals("$value: wrong message", "Key derivation refused: " + value.name, caught.message)
        }
    }

    @Test
    fun `two keys with the same bytes are different objects`() {
        val message = "a key type became value-equal (for example a data class)"
        assertNotEquals(message, KeyEncryptionKey(filled(32, 1)), KeyEncryptionKey(filled(32, 1)))
        assertNotEquals(message, AuthVerifier(filled(32, 1)), AuthVerifier(filled(32, 1)))
        val kek = KeyEncryptionKey(filled(32, 1))
        val verifier = AuthVerifier(filled(32, 2))
        assertNotEquals(message, DerivedKeys(kek, verifier), DerivedKeys(kek, verifier))
    }

    @Test
    fun `the only way to read the bytes is copyBytes`() {
        for (type in listOf<Class<*>>(KeyEncryptionKey::class.java, AuthVerifier::class.java)) {
            val message = "${type.simpleName}: a public accessor or field hands out the internal array"
            val readers = type.methods
                .filter { !it.isSynthetic && Modifier.isPublic(it.modifiers) && it.returnType == ByteArray::class.java }
                .map { it.name }
            assertEquals(message, listOf("copyBytes"), readers)
            for (field in type.declaredFields.filter { it.type == ByteArray::class.java }) {
                assertTrue("$message (field ${field.name})", Modifier.isPrivate(field.modifiers))
            }
        }
    }

    @Test
    fun `the derived pair is not a data class`() {
        val names = DerivedKeys::class.java.declaredMethods.map { it.name }
        val message = "DerivedKeys gained generated copy or component functions that could duplicate keys"
        assertFalse(message, names.contains("copy"))
        assertFalse(message, names.any { it.startsWith("component") })
    }
}
