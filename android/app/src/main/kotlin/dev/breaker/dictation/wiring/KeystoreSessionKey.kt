package dev.breaker.dictation.wiring

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The AES key the session vault seals to, held in the Android Keystore so it never
 * leaves the device. It is fetched under [ALIAS] or generated on first need, and is
 * built with GCM block mode, no padding, a 256-bit key size, encrypt+decrypt purpose
 * and randomized encryption required. A lazy singleton guarantees generation runs at most once.
 * The key is never read in an encoded form: an Android Keystore key has no usable encoded form,
 * and nothing may fetch one.
 */
internal object KeystoreSessionKey {
    const val ALIAS: String = "breaker_session_vault"

    private val cached: SecretKey by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { loadOrCreate() }

    @Throws(Exception::class)
    fun get(): SecretKey = cached

    private fun loadOrCreate(): SecretKey {
        val store: KeyStore = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        val entry: KeyStore.Entry? = store.getEntry(ALIAS, null)
        return if (entry == null) generate() else (entry as KeyStore.SecretKeyEntry).secretKey
    }

    private fun generate(): SecretKey {
        val spec: KeyGenParameterSpec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        val generator: KeyGenerator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(spec)
        return generator.generateKey()
    }
}
