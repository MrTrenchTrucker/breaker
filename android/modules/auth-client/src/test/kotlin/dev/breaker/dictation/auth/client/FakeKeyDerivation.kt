package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AuthVerifier
import dev.breaker.dictation.core.model.DerivedKeys
import dev.breaker.dictation.core.model.KdfParams
import dev.breaker.dictation.core.model.KdfRefused
import dev.breaker.dictation.core.model.KeyEncryptionKey
import dev.breaker.dictation.core.port.KeyDerivation
import org.junit.Assert.assertTrue

/** The key-encryption key bytes the fake derivation returns. */
internal val FAKE_KEK_BYTES: ByteArray = ByteArray(32) { 0x11.toByte() }

/** The auth verifier bytes the fake derivation returns. */
internal val FAKE_VERIFIER_BYTES: ByteArray = ByteArray(32) { 0x22.toByte() }

/** The salt the fake salt source returns by default. */
internal val FAKE_SALT_BYTES: ByteArray = ByteArray(16) { 0x05.toByte() }

/**
 * Test double for the key-derivation port. It counts calls and records the password, a copy of
 * the salt and the parameters it was given. Every result is kept so a test can check the wipe.
 * When [refusal] is set, the call is counted and then throws that refusal.
 */
class FakeKeyDerivation : KeyDerivation {
    /** One call: the password text recorded as a copy, a copy of the salt and the parameters. */
    class Call(val password: String, val salt: ByteArray, val params: KdfParams)

    /** Every call, in order. */
    val calls: MutableList<Call> = mutableListOf()

    /** Every result returned, so a test can see whether its bytes were wiped. */
    val derived: MutableList<DerivedKeys> = mutableListOf()

    /** When set, each call throws this refusal after it has been counted. */
    var refusal: KdfRefused? = null

    override fun deriveKeys(password: CharArray, salt: ByteArray, kdfParams: KdfParams): DerivedKeys {
        calls += Call(String(password), salt.copyOf(), kdfParams)
        refusal?.let { throw it }
        val keys = DerivedKeys(KeyEncryptionKey(FAKE_KEK_BYTES), AuthVerifier(FAKE_VERIFIER_BYTES))
        derived += keys
        return keys
    }
}

/** Checks that every derivation result was wiped, and that at least one derivation ran. */
fun assertWiped(keys: FakeKeyDerivation) {
    assertTrue("the derivation never ran, so there is nothing to check", keys.derived.isNotEmpty())
    for (keyPair in keys.derived) {
        assertTrue("the key-encryption key was not wiped", keyPair.kek.copyBytes().all { it == 0.toByte() })
        assertTrue("the auth verifier was not wiped", keyPair.authVerifier.copyBytes().all { it == 0.toByte() })
    }
}
