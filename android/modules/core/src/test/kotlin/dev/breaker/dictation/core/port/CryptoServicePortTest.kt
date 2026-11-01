package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.AccountKeys
import dev.breaker.dictation.core.model.CipherText
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.model.EncryptedText
import dev.breaker.dictation.core.model.KeyEncryptionKey
import dev.breaker.dictation.core.model.SealedSignInPackage
import dev.breaker.dictation.core.model.SignInPrivateKey
import dev.breaker.dictation.core.model.WrappedDek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * The shape of the crypto service port, pinned by reflection.
 *
 * The port opens the account's keys from a key-encryption key the caller already
 * derived, or from a sealed sign-in package, and it binds every encrypted text to
 * additional authenticated data. The password no longer reaches it, so the old
 * password-taking form must not exist next to the new one, and the old forms of
 * encrypt and decrypt without the additional data must be gone too.
 *
 * Every expected type here is written out as a literal class, so a wrong signature in
 * the port cannot also make the tests agree with it.
 */
class CryptoServicePortTest {
    private val port = CryptoService::class.java

    private fun realMethods(type: Class<*>): List<Method> =
        type.declaredMethods.filter { !it.isSynthetic && !it.isBridge }

    /** The one method called [name]; fails with a message if there are none or several. */
    private fun only(name: String): Method {
        val found = realMethods(port).filter { it.name == name }
        assertEquals("CryptoService must have exactly one method named $name", 1, found.size)
        return found.single()
    }

    @Test
    fun `the crypto service port declares exactly its five methods`() {
        assertTrue("CryptoService must be an interface", port.isInterface)

        assertEquals(
            "CryptoService must declare exactly unwrapDek, openSignInPackage, encrypt, decrypt and toEncryptedText, once each",
            listOf("decrypt", "encrypt", "openSignInPackage", "toEncryptedText", "unwrapDek"),
            realMethods(port).map { it.name }.sorted(),
        )
    }

    @Test
    fun `unwrapDek on the crypto service port takes the wrapped key and the key encryption key`() {
        val method = only("unwrapDek")

        assertEquals(
            "CryptoService.unwrapDek must take the wrapped key and the key-encryption key, nothing else",
            listOf<Class<*>>(WrappedDek::class.java, KeyEncryptionKey::class.java),
            method.parameterTypes.toList(),
        )
        assertEquals(
            "CryptoService.unwrapDek must return the data-encryption key",
            DataEncryptionKey::class.java,
            method.returnType,
        )
    }

    @Test
    fun `the password taking unwrapDek is gone from the crypto service port`() {
        assertThrows(
            "CryptoService must not keep unwrapDek(WrappedDek, String): a password must never reach this port",
            NoSuchMethodException::class.java,
        ) {
            port.getMethod("unwrapDek", WrappedDek::class.java, String::class.java)
        }
    }

    @Test
    fun `no crypto service method takes a text except the one to encrypt`() {
        val textParameters = realMethods(port)
            .flatMap { method ->
                method.parameterTypes.mapIndexedNotNull { index, type ->
                    if (type == String::class.java) "${method.name}#$index" else null
                }
            }
            .sorted()

        assertEquals(
            "the only String parameter on CryptoService must be the first parameter of encrypt",
            listOf("encrypt#0"),
            textParameters,
        )
    }

    @Test
    fun `openSignInPackage on the crypto service port takes the sealed package and the sign in key`() {
        val method = only("openSignInPackage")

        assertEquals(
            "CryptoService.openSignInPackage must take the sealed package and the sign-in private key, nothing else",
            listOf<Class<*>>(SealedSignInPackage::class.java, SignInPrivateKey::class.java),
            method.parameterTypes.toList(),
        )
        assertEquals(
            "CryptoService.openSignInPackage must return the account keys",
            AccountKeys::class.java,
            method.returnType,
        )
    }

    @Test
    fun `encrypt and decrypt on the crypto service port take the additional data`() {
        val encrypt = only("encrypt")
        assertEquals(
            "CryptoService.encrypt must take the text, the data key and the additional data bytes",
            listOf<Class<*>>(String::class.java, DataEncryptionKey::class.java, ByteArray::class.java),
            encrypt.parameterTypes.toList(),
        )
        assertEquals("CryptoService.encrypt must return the cipher text", CipherText::class.java, encrypt.returnType)

        val decrypt = only("decrypt")
        assertEquals(
            "CryptoService.decrypt must take the cipher text, the data key and the additional data bytes",
            listOf<Class<*>>(CipherText::class.java, DataEncryptionKey::class.java, ByteArray::class.java),
            decrypt.parameterTypes.toList(),
        )
        assertEquals("CryptoService.decrypt must return the text", String::class.java, decrypt.returnType)
    }

    @Test
    fun `the two argument encrypt and decrypt are gone from the crypto service port`() {
        assertThrows(
            "CryptoService must not keep encrypt(String, DataEncryptionKey) without the additional data",
            NoSuchMethodException::class.java,
        ) {
            port.getMethod("encrypt", String::class.java, DataEncryptionKey::class.java)
        }
        assertThrows(
            "CryptoService must not keep decrypt(CipherText, DataEncryptionKey) without the additional data",
            NoSuchMethodException::class.java,
        ) {
            port.getMethod("decrypt", CipherText::class.java, DataEncryptionKey::class.java)
        }
    }

    @Test
    fun `toEncryptedText on the crypto service port is unchanged`() {
        val method = only("toEncryptedText")

        assertEquals(
            "CryptoService.toEncryptedText must still take only the cipher text",
            listOf<Class<*>>(CipherText::class.java),
            method.parameterTypes.toList(),
        )
        assertEquals(
            "CryptoService.toEncryptedText must still return the encrypted text",
            EncryptedText::class.java,
            method.returnType,
        )
    }
}
