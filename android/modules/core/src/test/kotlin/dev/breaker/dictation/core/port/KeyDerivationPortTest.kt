package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.AuthVerifier
import dev.breaker.dictation.core.model.DerivedKeys
import dev.breaker.dictation.core.model.KdfParams
import dev.breaker.dictation.core.model.KdfRefusal
import dev.breaker.dictation.core.model.KdfRefused
import dev.breaker.dictation.core.model.KeyEncryptionKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Method

/**
 * The port that turns a password into a key pair.
 *
 * An adapter must be writable against the port using only the domain types,
 * and a refusal must travel through the port as a typed exception. The shape
 * is pinned by reflection: one call, returning both keys together, so no
 * function exists that hands back one key alone. The existing crypto port is
 * checked to be unchanged, so key derivation stays a separate port.
 */
class KeyDerivationPortTest {
    private val params = KdfParams(
        memoryKib = 65536,
        iterations = 3,
        parallelism = 4,
        outputLength = 32,
        version = 19,
    )

    private fun realMethods(type: Class<*>): List<Method> = type.declaredMethods.filter { !it.isSynthetic }

    @Test
    fun `an adapter can be written against the key derivation port`() {
        val adapter: KeyDerivation = object : KeyDerivation {
            override fun deriveKeys(password: String, salt: ByteArray, kdfParams: KdfParams): DerivedKeys =
                DerivedKeys(KeyEncryptionKey(ByteArray(32) { 1 }), AuthVerifier(ByteArray(32) { 2 }))
        }

        val keys = adapter.deriveKeys("password", ByteArray(16), params)

        assertArrayEquals("kek bytes are not the adapter's", ByteArray(32) { 1 }, keys.kek.copyBytes())
        assertArrayEquals("auth verifier bytes are not the adapter's", ByteArray(32) { 2 }, keys.authVerifier.copyBytes())
    }

    @Test
    fun `a refusing adapter is caught as a typed refusal through the port`() {
        val adapter: KeyDerivation = object : KeyDerivation {
            override fun deriveKeys(password: String, salt: ByteArray, kdfParams: KdfParams): DerivedKeys =
                throw KdfRefused(KdfRefusal.MEMORY_ABOVE_CEILING)
        }

        try {
            adapter.deriveKeys("password", ByteArray(16), params)
        } catch (refused: KdfRefused) {
            assertEquals(KdfRefusal.MEMORY_ABOVE_CEILING, refused.reason)
            return
        }
        fail("the refusal did not come out of the call through the port")
    }

    @Test
    fun `the port has one call that returns both keys together`() {
        val type = KeyDerivation::class.java
        assertTrue("KeyDerivation must be an interface", type.isInterface)

        val methods = realMethods(type)
        assertEquals("KeyDerivation must declare exactly one method", listOf("deriveKeys"), methods.map { it.name })

        val method = methods.single()
        assertEquals("deriveKeys must return both keys together", DerivedKeys::class.java, method.returnType)
        assertEquals(
            "deriveKeys must take the password, the salt and the parameters",
            listOf<Class<*>>(String::class.java, ByteArray::class.java, KdfParams::class.java),
            method.parameterTypes.toList(),
        )
    }

    @Test
    fun `the crypto service port is unchanged and does not derive keys`() {
        val names = realMethods(CryptoService::class.java).map { it.name }.toSet()

        assertEquals(
            "CryptoService must keep exactly its four methods",
            setOf("unwrapDek", "encrypt", "decrypt", "toEncryptedText"),
            names,
        )
        assertFalse("CryptoService must not gain deriveKeys", names.contains("deriveKeys"))
    }
}
