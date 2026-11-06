package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.AccountKeys
import dev.breaker.dictation.core.model.BoxPrivateKey
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.model.StoredAccountKeys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * The contract the key port states, run on one in-test fake that copies in and out as the port
 * says. The port has no main logic, so these tests prove the contract of the fake and that the
 * port can be written against the domain, and they are compile-only for mutant purposes.
 *
 * Every size in these tests is a literal number.
 */
class CredentialVaultPortTest {
    /** Holds one copy of the keys and the version, and hands out new copies. */
    private class FakeCredentialVault : CredentialVault {
        private var held: StoredAccountKeys? = null

        override fun saveKeys(keys: AccountKeys, keyVersion: Int) {
            val copy = StoredAccountKeys(keys, keyVersion)
            held?.wipe()
            held = copy
        }

        override fun readKeys(): StoredAccountKeys? = held?.let { StoredAccountKeys(it.keys, it.keyVersion) }

        override fun wipeAll() {
            held?.wipe()
            held = null
        }
    }

    private fun filled(size: Int, value: Int): ByteArray = ByteArray(size) { value.toByte() }

    private fun aKeySet(dekValue: Int, boxValue: Int): AccountKeys =
        AccountKeys(DataEncryptionKey(filled(32, dekValue)), BoxPrivateKey(filled(32, boxValue)))

    private fun read(vault: CredentialVault): StoredAccountKeys =
        vault.readKeys() ?: throw AssertionError("the vault returned nothing but keys were saved")

    @Test
    fun `nothing stored gives null`() {
        assertNull(FakeCredentialVault().readKeys())
    }

    @Test
    fun `saving copies so wiping the caller keys afterwards leaves the stored keys intact`() {
        val vault: CredentialVault = FakeCredentialVault()
        val keys = aKeySet(0x11, 0x22)
        vault.saveKeys(keys, 4)
        keys.wipe()

        val stored = read(vault)
        assertArrayEquals("the stored dek followed the caller", filled(32, 0x11), stored.keys.dek.bytes)
        assertArrayEquals(
            "the stored box key followed the caller",
            filled(32, 0x22),
            stored.keys.boxPrivateKey.copyBytes(),
        )
    }

    @Test
    fun `reading returns a new holder each call and wiping it does not change what is stored`() {
        val vault: CredentialVault = FakeCredentialVault()
        vault.saveKeys(aKeySet(0x33, 0x44), 2)

        val first = read(vault)
        val second = read(vault)
        assertNotSame("the vault returned the same holder twice", first, second)
        first.wipe()

        val third = read(vault)
        assertArrayEquals(
            "wiping a returned holder changed the stored dek",
            filled(32, 0x33),
            third.keys.dek.bytes,
        )
        assertArrayEquals(
            "wiping a returned holder changed the stored box key",
            filled(32, 0x44),
            third.keys.boxPrivateKey.copyBytes(),
        )
        assertArrayEquals("the other returned holder was changed", filled(32, 0x33), second.keys.dek.bytes)
    }

    @Test
    fun `the version survives a save and read for zero and for a large version`() {
        val vault: CredentialVault = FakeCredentialVault()
        vault.saveKeys(aKeySet(0x11, 0x22), 0)
        assertEquals(0, read(vault).keyVersion)

        vault.saveKeys(aKeySet(0x11, 0x22), Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, read(vault).keyVersion)
    }

    @Test
    fun `a second save replaces the first`() {
        val vault: CredentialVault = FakeCredentialVault()
        vault.saveKeys(aKeySet(0x11, 0x22), 1)
        vault.saveKeys(aKeySet(0x55, 0x66), 8)

        val stored = read(vault)
        assertEquals(8, stored.keyVersion)
        assertArrayEquals("the first dek is still stored", filled(32, 0x55), stored.keys.dek.bytes)
        assertArrayEquals(
            "the first box key is still stored",
            filled(32, 0x66),
            stored.keys.boxPrivateKey.copyBytes(),
        )
    }

    @Test
    fun `wiping all empties the store and is safe on an empty store`() {
        val vault: CredentialVault = FakeCredentialVault()
        vault.wipeAll()
        assertNull(vault.readKeys())

        vault.saveKeys(aKeySet(0x11, 0x22), 3)
        assertNotNull(vault.readKeys())
        vault.wipeAll()
        assertNull(vault.readKeys())
        vault.wipeAll()
        assertNull(vault.readKeys())
    }

    @Test
    fun `saving with a negative version is refused by the holder type and stores nothing`() {
        val vault: CredentialVault = FakeCredentialVault()
        try {
            vault.saveKeys(aKeySet(0x11, 0x22), -1)
            fail("a negative key version was accepted but must be refused with IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertNull("a refused save stored keys", vault.readKeys())
        }
    }
}
