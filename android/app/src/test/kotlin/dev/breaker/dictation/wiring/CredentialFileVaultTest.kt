package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.AccountKeys
import dev.breaker.dictation.core.model.BoxPrivateKey
import dev.breaker.dictation.core.model.DataEncryptionKey
import java.io.File
import java.io.IOException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A seal that writes the plain bytes as they are: the control that shows the file search can find a key. */
private class CfvIdentitySeal : SessionSeal {
    override fun seal(plain: ByteArray): ByteArray = plain.copyOf()
    override fun unseal(sealed: ByteArray): ByteArray = sealed.copyOf()
}

/** A seal that always fails to seal, to show a failed save leaves the old value and no temporary file. */
private class CfvFailingSeal : SessionSeal {
    override fun seal(plain: ByteArray): ByteArray = throw IllegalStateException("seal failed")
    override fun unseal(sealed: ByteArray): ByteArray = throw IllegalStateException("not used")
}

/** The credentials file vault over a real file and a real AES seal with its own label. */
class CredentialFileVaultTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val key: SecretKey = SecretKeySpec(ByteArray(32) { 5 }, "AES")
    private val seal = AesSessionSeal(CREDENTIAL_VAULT_PURPOSE) { key }

    private fun file(): File = File(tmp.root, "credentials.vault")

    private fun vault(): CredentialFileVault = CredentialFileVault(file(), seal)

    private fun keysOf(): AccountKeys = AccountKeys(
        DataEncryptionKey(ByteArray(32) { (it + 10).toByte() }),
        BoxPrivateKey(ByteArray(32) { (it + 100).toByte() }),
    )

    private fun dekBytes(): ByteArray = ByteArray(32) { (it + 10).toByte() }

    private fun boxBytes(): ByteArray = ByteArray(32) { (it + 100).toByte() }

    /** A plain record of [total] bytes: the format byte, four version bytes, then filler. */
    private fun plainRecord(format: Int, version: ByteArray, total: Int): ByteArray {
        val plain = ByteArray(total)
        plain[0] = format.toByte()
        for (i in version.indices) plain[1 + i] = version[i]
        for (i in 5 until total) plain[i] = 7
        return plain
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        for (start in 0..haystack.size - needle.size) {
            var same = true
            for (j in needle.indices) if (haystack[start + j] != needle[j]) same = false
            if (same) return true
        }
        return false
    }

    @Test
    fun `a saved version zero comes back with both keys`() {
        vault().save(0)
        val read = vault().readKeys()
        assertNotNull("wiring: saved keys must be read back", read)
        assertEquals("wiring: the version must come back", 0, read!!.keyVersion)
        assertArrayEquals("wiring: the data key must come back", dekBytes(), read.keys.dek.bytes)
        assertArrayEquals(
            "wiring: the box key must come back",
            boxBytes(),
            read.keys.boxPrivateKey.copyBytes(),
        )
    }

    private fun CredentialFileVault.save(version: Int) = saveKeys(keysOf(), version)

    @Test
    fun `the largest version comes back`() {
        vault().save(Int.MAX_VALUE)
        assertEquals(
            "wiring: the largest version must come back",
            Int.MAX_VALUE,
            vault().readKeys()!!.keyVersion,
        )
    }

    @Test
    fun `the plain record is the format byte then the big endian version then the two keys`() {
        val seen = CredentialFileVault(file(), CfvIdentitySeal())
        seen.save(258)
        val bytes = file().readBytes()
        assertEquals("wiring: the plain record must be 69 bytes", 69, bytes.size)
        assertEquals("wiring: the format byte must be 1", 1, bytes[0].toInt())
        assertArrayEquals(
            "wiring: the version must be big endian",
            byteArrayOf(0, 0, 1, 2),
            bytes.copyOfRange(1, 5),
        )
        assertArrayEquals("wiring: the data key must follow", dekBytes(), bytes.copyOfRange(5, 37))
        assertArrayEquals("wiring: the box key must close the record", boxBytes(), bytes.copyOfRange(37, 69))
    }

    @Test
    fun `wiping the callers keys after a save does not change what is stored`() {
        val keys = keysOf()
        vault().saveKeys(keys, 3)
        keys.wipe()
        assertArrayEquals(
            "wiring: the stored data key must be a copy",
            dekBytes(),
            vault().readKeys()!!.keys.dek.bytes,
        )
    }

    @Test
    fun `each read returns a new holder and wiping one leaves the other`() {
        vault().save(1)
        val first = vault().readKeys()!!
        val second = vault().readKeys()!!
        assertNotSame("wiring: a read must give a new holder", first, second)
        first.wipe()
        assertArrayEquals("wiring: the other holder must keep its bytes", dekBytes(), second.keys.dek.bytes)
    }

    @Test
    fun `a new instance on the same file reads the keys after a restart`() {
        vault().save(9)
        val again = CredentialFileVault(file(), AesSessionSeal(CREDENTIAL_VAULT_PURPOSE) { key })
        assertEquals("wiring: the restarted vault must read the version", 9, again.readKeys()!!.keyVersion)
    }

    @Test
    fun `a tampered file reads as nothing`() {
        vault().save(1)
        val bytes = file().readBytes()
        bytes[20] = (bytes[20].toInt() xor 1).toByte()
        file().writeBytes(bytes)
        assertNull("wiring: a changed byte must read as nothing", vault().readKeys())
    }

    @Test
    fun `a truncated file reads as nothing`() {
        vault().save(1)
        val bytes = file().readBytes()
        file().writeBytes(bytes.copyOfRange(0, bytes.size - 5))
        assertNull("wiring: a cut file must read as nothing", vault().readKeys())
    }

    @Test
    fun `a hand built record of the exact shape opens`() {
        file().writeBytes(seal.seal(plainRecord(1, byteArrayOf(0, 0, 0, 4), 69)))
        assertEquals("wiring: the control record must open", 4, vault().readKeys()!!.keyVersion)
    }

    @Test
    fun `a sealed record that is one byte short reads as nothing`() {
        file().writeBytes(seal.seal(plainRecord(1, byteArrayOf(0, 0, 0, 4), 68)))
        assertNull("wiring: a short plain record must read as nothing", vault().readKeys())
    }

    @Test
    fun `a sealed record that is one byte too long reads as nothing`() {
        file().writeBytes(seal.seal(plainRecord(1, byteArrayOf(0, 0, 0, 4), 70)))
        assertNull("wiring: a long plain record must read as nothing", vault().readKeys())
    }

    @Test
    fun `a sealed record with another format byte reads as nothing`() {
        file().writeBytes(seal.seal(plainRecord(2, byteArrayOf(0, 0, 0, 4), 69)))
        assertNull("wiring: another format byte must read as nothing", vault().readKeys())
    }

    @Test
    fun `a sealed record with a negative version reads as nothing`() {
        val minusOne = byteArrayOf(-1, -1, -1, -1)
        file().writeBytes(seal.seal(plainRecord(1, minusOne, 69)))
        assertNull("wiring: a negative version must read as nothing", vault().readKeys())
    }

    @Test
    fun `a file over the size cap reads as nothing`() {
        file().writeBytes(ByteArray(65537) { 1 })
        assertNull("wiring: a file over the cap must read as nothing", vault().readKeys())
    }

    @Test
    fun `a data key that is not 32 bytes is refused and nothing is written`() {
        val short = AccountKeys(DataEncryptionKey(ByteArray(31) { 1 }), BoxPrivateKey(boxBytes()))
        assertThrows(IllegalArgumentException::class.java) { vault().saveKeys(short, 1) }
        assertFalse("wiring: a refused save must write no file", file().exists())
    }

    @Test
    fun `a negative version is refused and nothing is written`() {
        assertThrows(IllegalArgumentException::class.java) { vault().saveKeys(keysOf(), -1) }
        assertFalse("wiring: a refused save must write no file", file().exists())
    }

    @Test
    fun `wipeAll deletes the file and is fine when nothing is stored`() {
        vault().wipeAll()
        vault().save(1)
        assertTrue("wiring: the file must exist after a save", file().isFile)
        vault().wipeAll()
        assertFalse("wiring: wipeAll must delete the file", file().exists())
        assertNull("wiring: nothing may be read after wipeAll", vault().readKeys())
    }

    @Test
    fun `a failed seal keeps the old value and leaves no temporary file`() {
        vault().save(7)
        assertThrows(IllegalStateException::class.java) {
            CredentialFileVault(file(), CfvFailingSeal()).saveKeys(keysOf(), 8)
        }
        assertEquals("wiring: the old version must stay", 7, vault().readKeys()!!.keyVersion)
        assertFalse("wiring: no temporary file may stay", File(tmp.root, "credentials.vault.tmp").exists())
    }

    @Test
    fun `a failed write keeps the old value and leaves no temporary file`() {
        vault().save(7)
        assertTrue(File(tmp.root, "credentials.vault.tmp").mkdir())
        assertThrows(IOException::class.java) { vault().saveKeys(keysOf(), 8) }
        assertEquals("wiring: the old version must stay", 7, vault().readKeys()!!.keyVersion)
        assertFalse("wiring: no temporary file may stay", File(tmp.root, "credentials.vault.tmp").exists())
    }

    @Test
    fun `the file is named credentials dot vault in the files folder`() {
        assertEquals("wiring: the credentials file name", "credentials.vault", credentialsFile(tmp.root).name)
        assertEquals("wiring: the credentials file folder", tmp.root, credentialsFile(tmp.root).parentFile)
    }

    @Test
    fun `the plain keys are never found in the sealed file`() {
        vault().save(1)
        val sealed = file().readBytes()
        assertFalse("wiring: the data key must not be at rest", contains(sealed, dekBytes()))
        assertFalse("wiring: the box key must not be at rest", contains(sealed, boxBytes()))
        CredentialFileVault(file(), CfvIdentitySeal()).save(1)
        val open = file().readBytes()
        assertTrue("wiring: the search must find a key that is not sealed", contains(open, dekBytes()))
    }
}
