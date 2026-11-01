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
 * The value types of the website sign-in: the private key made for one sign-in, the
 * account's box private key, the sealed key package, the pair of account keys and the
 * one plain refusal.
 *
 * Each key is exactly 32 bytes and the package is any non-empty array. Each is copied on
 * the way in and on the way out so no caller can reach the internal array, can be
 * wiped in place without touching the caller's own array, and never prints its bytes.
 * The pair wipes both of its keys. The refusal carries no reason and no byte.
 *
 * Every size in these tests is a literal number, so a wrong constant in the
 * production code cannot also make the tests agree with it.
 */
class SignInKeysTest {
    /** A secret seen through the same four operations, whichever type it is. */
    private class Secret(
        val label: String,
        val bytes: () -> ByteArray,
        val wipe: () -> Unit,
        val text: () -> String,
    )

    private fun filled(size: Int, value: Int): ByteArray = ByteArray(size) { value.toByte() }

    /** The three byte holders, built from the same 32-byte caller array. */
    private fun secretsFrom(source: ByteArray): List<Secret> {
        val signIn = SignInPrivateKey(source)
        val box = BoxPrivateKey(source)
        val sealedPackage = SealedSignInPackage(source)
        return listOf(
            Secret("SignInPrivateKey", { signIn.copyBytes() }, { signIn.wipe() }, { signIn.toString() }),
            Secret("BoxPrivateKey", { box.copyBytes() }, { box.wipe() }, { box.toString() }),
            Secret("SealedSignInPackage", { sealedPackage.copyBytes() }, { sealedPackage.wipe() }, { sealedPackage.toString() }),
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

    /** The text must be exactly [expected] and must show no byte value of an array filled with 0xC7. */
    private fun assertPrintsOnly(label: String, expected: String, actual: String) {
        assertEquals("$label printed something other than its fixed text", expected, actual)
        assertFalse("$label shows an array identity", actual.contains("[B@"))
        for (shown in listOf(199.toString(), (-57).toString(), 199.toString(16), 199.toString(16).uppercase())) {
            assertFalse("$label shows the byte value $shown", actual.contains(shown))
        }
    }

    @Test
    fun `a sign in private key refuses every length except 32 bytes`() {
        for (size in listOf(0, 31, 33)) {
            val what = "a SignInPrivateKey of $size bytes"
            assertMessageHidesKeyBytes(what, refusedMessage(what) { SignInPrivateKey(filled(size, 0x5A)) })
        }
        assertEquals("a 32 byte SignInPrivateKey must be accepted", 32, SignInPrivateKey(filled(32, 0x5A)).copyBytes().size)
    }

    @Test
    fun `a box private key refuses every length except 32 bytes`() {
        for (size in listOf(0, 31, 33)) {
            val what = "a BoxPrivateKey of $size bytes"
            assertMessageHidesKeyBytes(what, refusedMessage(what) { BoxPrivateKey(filled(size, 0x5A)) })
        }
        assertEquals("a 32 byte BoxPrivateKey must be accepted", 32, BoxPrivateKey(filled(32, 0x5A)).copyBytes().size)
    }

    @Test
    fun `a sealed sign in package refuses the empty array and accepts any other length`() {
        val message = refusedMessage("an empty SealedSignInPackage") { SealedSignInPackage(ByteArray(0)) }
        assertMessageHidesKeyBytes("an empty SealedSignInPackage", message)
        assertEquals("a 1 byte SealedSignInPackage must be accepted", 1, SealedSignInPackage(filled(1, 0x5A)).copyBytes().size)
        assertEquals("an 80 byte SealedSignInPackage must be accepted", 80, SealedSignInPackage(filled(80, 0x5A)).copyBytes().size)
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
    fun `wiping a secret zeroes it and leaves the other secrets and the caller array alone`() {
        val source = filled(32, 0x33)
        val secrets = secretsFrom(source)
        assertEquals("the number of secrets under test changed", 3, secrets.size)

        secrets[0].wipe()
        assertArrayEquals("wiping the sign-in private key changed the box private key", filled(32, 0x33), secrets[1].bytes())
        assertArrayEquals("wiping the sign-in private key changed the sealed package", filled(32, 0x33), secrets[2].bytes())
        secrets[1].wipe()
        secrets[2].wipe()
        for (secret in secrets) {
            assertArrayEquals("${secret.label} is not 32 zero bytes after wipe", ByteArray(32), secret.bytes())
        }
        assertArrayEquals("wiping a secret zeroed the caller's own array", filled(32, 0x33), source)
    }

    @Test
    fun `a secret prints a fixed text with only its size and keeps it after wipe`() {
        val secrets = secretsFrom(filled(32, 0xC7))
        val expected = listOf("SignInPrivateKey(32 bytes)", "BoxPrivateKey(32 bytes)", "SealedSignInPackage(32 bytes)")
        assertEquals("the number of secrets under test changed", expected.size, secrets.size)

        secrets.forEachIndexed { index, secret -> assertPrintsOnly(secret.label, expected[index], secret.text()) }
        secrets.forEach { it.wipe() }
        secrets.forEachIndexed { index, secret -> assertPrintsOnly("${secret.label} after wipe", expected[index], secret.text()) }
    }

    @Test
    fun `a sealed sign in package prints its own size`() {
        assertPrintsOnly("an 80 byte package", "SealedSignInPackage(80 bytes)", SealedSignInPackage(filled(80, 0xC7)).toString())
        assertPrintsOnly("a 1 byte package", "SealedSignInPackage(1 bytes)", SealedSignInPackage(filled(1, 0xC7)).toString())
    }

    @Test
    fun `two secrets with the same bytes are different objects`() {
        val message = "a secret type became value-equal (for example a data class)"
        assertNotEquals(message, SignInPrivateKey(filled(32, 1)), SignInPrivateKey(filled(32, 1)))
        assertNotEquals(message, BoxPrivateKey(filled(32, 1)), BoxPrivateKey(filled(32, 1)))
        assertNotEquals(message, SealedSignInPackage(filled(32, 1)), SealedSignInPackage(filled(32, 1)))
        val dek = DataEncryptionKey(filled(32, 1))
        val box = BoxPrivateKey(filled(32, 2))
        assertNotEquals(message, AccountKeys(dek, box), AccountKeys(dek, box))
    }

    @Test
    fun `the only way to read the bytes of a secret is copyBytes`() {
        val types = listOf<Class<*>>(
            SignInPrivateKey::class.java,
            BoxPrivateKey::class.java,
            SealedSignInPackage::class.java,
        )
        for (type in types) {
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
    fun `wiping the account keys zeroes both members and the members are the ones passed in`() {
        val dek = DataEncryptionKey(filled(32, 0x44))
        val box = BoxPrivateKey(filled(32, 0x55))
        val keys = AccountKeys(dek, box)

        assertSame("dek is not the instance passed in", dek, keys.dek)
        assertSame("boxPrivateKey is not the instance passed in", box, keys.boxPrivateKey)

        keys.wipe()

        assertArrayEquals("wipe left the data-encryption key unzeroed", ByteArray(32), keys.dek.bytes)
        assertArrayEquals("wipe left the box private key unzeroed", ByteArray(32), keys.boxPrivateKey.copyBytes())
    }

    @Test
    fun `the account keys print a fixed text with no bytes and keep it after wipe`() {
        val keys = AccountKeys(DataEncryptionKey(filled(32, 0xC7)), BoxPrivateKey(filled(32, 0xC7)))
        val expected = "AccountKeys(dek=DataEncryptionKey(32 bytes), boxPrivateKey=BoxPrivateKey(32 bytes))"

        assertPrintsOnly("AccountKeys", expected, keys.toString())
        keys.wipe()
        assertPrintsOnly("AccountKeys after wipe", expected, keys.toString())
    }

    @Test
    fun `the account keys are not a data class`() {
        val names = AccountKeys::class.java.declaredMethods.map { it.name }
        val message = "AccountKeys gained generated copy or component functions that could duplicate keys"
        assertFalse(message, names.contains("copy"))
        assertFalse(message, names.any { it.startsWith("component") })
    }

    @Test
    fun `a crypto refusal is a real IllegalArgumentException with one fixed message and no digit`() {
        val caught: IllegalArgumentException = try {
            throw CryptoRefused()
        } catch (expected: IllegalArgumentException) {
            expected
        }

        assertTrue("the caught exception is not a CryptoRefused", caught is CryptoRefused)
        assertEquals("wrong message", "Cryptographic operation refused", caught.message)
        assertFalse("the message of CryptoRefused carries a digit", caught.message.orEmpty().any { it.isDigit() })
        assertTrue("CryptoRefused must carry no cause", caught.cause == null)
    }
}
