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

private const val READER_DEK_BYTE: Byte = 10
private const val READER_BOX_BYTE: Byte = 100

// The plain record of the card: 1 format byte, 4 version bytes (big-endian), 32 data key bytes,
// 32 box key bytes: 1 + 4 + 32 + 32 = 69 bytes.
private const val READER_RECORD_BYTES = 69

private fun readerKeys(): AccountKeys = AccountKeys(
    DataEncryptionKey(ByteArray(32) { READER_DEK_BYTE }),
    BoxPrivateKey(ByteArray(32) { READER_BOX_BYTE }),
)

/** Keeps the array the vault handed over (a reference) and a copy taken at that moment. */
private class ReaderCapturingSeal : SessionSeal {
    var reference: ByteArray? = null
    var copyAtSealTime: ByteArray? = null

    override fun seal(plain: ByteArray): ByteArray {
        reference = plain
        copyAtSealTime = plain.copyOf()
        return plain.copyOf()
    }

    override fun unseal(sealed: ByteArray): ByteArray = sealed.copyOf()
}

/** A seal that cannot seal: the vault must write nothing. */
private class ReaderBrokenSeal : SessionSeal {
    override fun seal(plain: ByteArray): ByteArray = throw IllegalStateException("cannot seal")
    override fun unseal(sealed: ByteArray): ByteArray = throw IllegalStateException("cannot unseal")
}

/** A seal whose unseal refuses every file. */
private class ReaderRefusingSeal : SessionSeal {
    override fun seal(plain: ByteArray): ByteArray = plain.copyOf()
    override fun unseal(sealed: ByteArray): ByteArray = throw IllegalArgumentException("refused")
}

/** A seal whose unseal answers the same bytes whatever file it is given. */
private class ReaderFixedAnswerSeal(private val answer: ByteArray) : SessionSeal {
    override fun seal(plain: ByteArray): ByteArray = plain.copyOf()
    override fun unseal(sealed: ByteArray): ByteArray = answer.copyOf()
}

/** Tests of CredentialFileVault written from its KDoc, the card and the 32 byte keys of ADR-006. */
class ReaderCredentialFileVaultTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val aesKey: SecretKey = SecretKeySpec(ByteArray(32) { 5 }, "AES")

    // Read on use: the folder rule creates the folder after this object is built.
    private fun vaultFile(): File = File(tmp.root, "credentials.vault")

    private fun vault(seal: SessionSeal): CredentialFileVault = CredentialFileVault(vaultFile(), seal)

    private fun aesVault(): CredentialFileVault =
        vault(AesSessionSeal(CREDENTIAL_VAULT_PURPOSE) { aesKey })

    private fun validRecord(version: Int): ByteArray {
        val record = ByteArray(READER_RECORD_BYTES)
        record[0] = 1
        record[1] = (version ushr 24).toByte()
        record[2] = (version ushr 16).toByte()
        record[3] = (version ushr 8).toByte()
        record[4] = version.toByte()
        for (i in 0 until 32) {
            record[5 + i] = READER_DEK_BYTE
            record[37 + i] = READER_BOX_BYTE
        }
        return record
    }

    @Test
    fun `a record holds the data key then the box key after five header bytes`() {
        val seal = ReaderCapturingSeal()
        vault(seal).saveKeys(readerKeys(), 0)
        val plain = seal.copyAtSealTime ?: throw AssertionError("the seal never got a record")
        assertEquals("the plain record is 69 bytes long", READER_RECORD_BYTES, plain.size)
        assertEquals("first data key byte", READER_DEK_BYTE, plain[5])
        assertEquals("last data key byte", READER_DEK_BYTE, plain[36])
        assertEquals("first box key byte", READER_BOX_BYTE, plain[37])
        assertEquals("last box key byte", READER_BOX_BYTE, plain[68])
    }

    @Test
    fun `the key version is written as four big endian bytes after the format byte`() {
        val seal = ReaderCapturingSeal()
        vault(seal).saveKeys(readerKeys(), 0x01020304)
        val plain = seal.copyAtSealTime ?: throw AssertionError("the seal never got a record")
        assertArrayEquals(
            "bytes one to four are the version, most significant first",
            byteArrayOf(1, 2, 3, 4),
            plain.copyOfRange(1, 5),
        )
    }

    @Test
    fun `the array handed to the seal is all zero once the save returns`() {
        val seal = ReaderCapturingSeal()
        vault(seal).saveKeys(readerKeys(), 3)
        val handed = seal.reference ?: throw AssertionError("the seal never got a record")
        assertTrue("the vault must zero its plain record", handed.all { it == 0.toByte() })
        val copy = seal.copyAtSealTime ?: throw AssertionError("no copy")
        assertTrue("the record was not empty when it was sealed", copy.any { it != 0.toByte() })
    }

    @Test
    fun `a seal that fails leaves the old value in place`() {
        aesVault().saveKeys(readerKeys(), 7)
        assertThrows(IllegalStateException::class.java) {
            vault(ReaderBrokenSeal()).saveKeys(readerKeys(), 8)
        }
        val back = aesVault().readKeys()
        assertNotNull("the old record must still read", back)
        assertEquals("the old version must stay", 7, back!!.keyVersion)
    }

    @Test
    fun `a move that fails leaves no temporary file behind`() {
        // A non-empty folder stands where the vault file belongs, so the final move must fail
        // after the temporary file was written.
        val blocker = vaultFile()
        assertTrue(blocker.mkdirs())
        File(blocker, "inner").writeBytes(byteArrayOf(1))
        assertThrows(IOException::class.java) {
            vault(ReaderCapturingSeal()).saveKeys(readerKeys(), 1)
        }
        assertFalse(
            "no temporary file may stay",
            File(tmp.root, "credentials.vault.tmp").exists(),
        )
    }

    @Test
    fun `a file cut short by one byte reads as nothing and the whole file reads`() {
        aesVault().saveKeys(readerKeys(), 1)
        val whole = vaultFile().readBytes()
        assertNotNull("the untouched file reads", aesVault().readKeys())
        vaultFile().writeBytes(whole.copyOf(whole.size - 1))
        assertNull("a file one byte short must not open", aesVault().readKeys())
    }

    @Test
    fun `a file over the size cap is not opened even when its content would open`() {
        val seal = ReaderFixedAnswerSeal(validRecord(1))
        vaultFile().writeBytes(ByteArray(65537) { 1 })
        assertNull("a file over 64 KiB reads as nothing", vault(seal).readKeys())
    }

    @Test
    fun `the same fixed answer opens when the file is small`() {
        val seal = ReaderFixedAnswerSeal(validRecord(1))
        vaultFile().writeBytes(ByteArray(10) { 1 })
        val back = vault(seal).readKeys()
        assertNotNull("the control must open", back)
        assertEquals("version of the fixed record", 1, back!!.keyVersion)
    }

    @Test
    fun `a missing file reads as nothing`() {
        assertNull("no file, no keys", aesVault().readKeys())
    }

    @Test
    fun `a seal that refuses to open gives nothing and does not throw`() {
        vault(ReaderRefusingSeal()).saveKeys(readerKeys(), 1)
        assertNull("a refusal must read as nothing", vault(ReaderRefusingSeal()).readKeys())
    }

    @Test
    fun `an opened record of the wrong length gives nothing`() {
        vaultFile().writeBytes(byteArrayOf(1, 2, 3))
        assertNull("68 bytes is not a record", vault(ReaderFixedAnswerSeal(ByteArray(68))).readKeys())
        assertNull("70 bytes is not a record", vault(ReaderFixedAnswerSeal(ByteArray(70))).readKeys())
        assertNotNull(
            "69 bytes with the format byte is a record",
            vault(ReaderFixedAnswerSeal(validRecord(2))).readKeys(),
        )
    }

    @Test
    fun `an opened record with another format byte or a negative version gives nothing`() {
        vaultFile().writeBytes(byteArrayOf(9))
        val otherFormat = validRecord(1)
        otherFormat[0] = 2
        assertNull("format byte 2 is not this format", vault(ReaderFixedAnswerSeal(otherFormat)).readKeys())
        val negative = validRecord(1)
        negative[1] = 0x80.toByte()
        assertNull("a negative version is refused", vault(ReaderFixedAnswerSeal(negative)).readKeys())
        assertNotNull("the untouched record opens", vault(ReaderFixedAnswerSeal(validRecord(1))).readKeys())
    }

    @Test
    fun `each read gives a new holder and wiping one leaves the other whole`() {
        aesVault().saveKeys(readerKeys(), 1)
        val first = aesVault().readKeys()!!
        val second = aesVault().readKeys()!!
        assertNotSame("two reads must not share a holder", first, second)
        first.wipe()
        assertArrayEquals(
            "the second holder keeps its data key",
            ByteArray(32) { READER_DEK_BYTE },
            second.keys.dek.bytes,
        )
    }

    @Test
    fun `wipeAll removes the file and the keys read as nothing afterwards`() {
        aesVault().saveKeys(readerKeys(), 1)
        assertTrue("the save wrote the file", vaultFile().isFile)
        aesVault().wipeAll()
        assertFalse("the file is gone", vaultFile().exists())
        assertNull("nothing to read", aesVault().readKeys())
    }

    @Test
    fun `the credentials file of the wiring is called credentials vault in the files folder`() {
        val file = credentialsFile(tmp.root)
        assertEquals("credentials.vault", file.name)
        assertEquals(tmp.root, file.parentFile)
    }
}
