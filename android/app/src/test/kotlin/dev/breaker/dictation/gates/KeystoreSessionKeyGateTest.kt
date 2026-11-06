package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystoreSessionKeyGateTest {

    private val path: String = "kotlin/dev/breaker/dictation/wiring/KeystoreSessionKey.kt"

    private fun stripped() = AppSourceFiles.strip(AppSourceFiles.mainFile(path))

    private fun count(text: String, needle: String): Int {
        var found = 0
        var at: Int = text.indexOf(needle)
        while (at >= 0) {
            found += 1
            at = text.indexOf(needle, at + needle.length)
        }
        return found
    }

    @Test
    fun `the Keystore AES key is GCM with no padding 256-bit aliased and never exported`() {
        val code = stripped().code
        val literals = stripped().literals

        assertTrue("app: the alias is exactly breaker_session_vault", literals.contains("breaker_session_vault"))
        assertTrue("app: GCM block mode", code.contains("BLOCK_MODE_GCM"))
        assertTrue("app: no padding", code.contains("ENCRYPTION_PADDING_NONE"))
        assertTrue("app: 256-bit key", code.contains("setKeySize(256)"))
        assertTrue("app: encrypt and decrypt purpose", code.contains("PURPOSE_ENCRYPT") && code.contains("PURPOSE_DECRYPT"))
        assertTrue("app: AES over the Android Keystore", code.contains("KEY_ALGORITHM_AES") && literals.contains("AndroidKeyStore"))
        assertTrue("app: randomized encryption required", code.contains("setRandomizedEncryptionRequired(true)"))

        assertFalse("app: the key is never exported with getEncoded", code.contains("getEncoded"))
        assertFalse("app: randomized encryption is never weakened", code.contains("setRandomizedEncryptionRequired(false)"))
        assertFalse("app: the key never forces user authentication", code.contains("setUserAuthenticationRequired"))
    }

    @Test
    fun `the key is never exported through the property spelling either`() {
        val code = stripped().code
        assertFalse("app: the key is never exported with the encoded property", code.contains(".encoded"))
    }

    @Test
    fun `the key size is set exactly once`() {
        assertEquals("app: setKeySize is called once", 1, count(stripped().code, "setKeySize("))
    }

    @Test
    fun `the Android Keystore name is written exactly twice`() {
        val twice: Int = stripped().literals.count { it == "AndroidKeyStore" }
        assertEquals("app: the provider name is written for the store and for the generator", 2, twice)
    }

    @Test
    fun `the stored key is looked up once under the alias`() {
        assertEquals("app: one lookup under the alias", 1, count(stripped().code, "getEntry(ALIAS, null)"))
    }

    @Test
    fun `the lazy key holder is synchronized exactly once`() {
        val mode = "LazyThreadSafetyMode.SYNCHRONIZED"
        assertEquals("app: one synchronized lazy holder", 1, count(stripped().code, mode))
    }
}
