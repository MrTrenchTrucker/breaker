package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.BoxPrivateKey
import dev.breaker.dictation.core.model.CryptoRefused
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.model.KeyEncryptionKey
import dev.breaker.dictation.core.model.WrappedBoxPrivateKey
import dev.breaker.dictation.core.model.WrappedPreviousDek
import dev.breaker.dictation.core.port.AccountKeyService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Proves the three wrap forms of [AccountKeyServiceImpl] against the shared vectors (copied in
 * [CryptoVectorData]): the data key under the key-encryption key with empty associated data, the
 * box private key under the data key and the previous data key under the new data key, each with
 * its own purpose string. A blob of one slot must fail the tag in the other slot.
 */
class AccountKeyWrapTest : CryptoServiceTestFixture() {
    private val dekName = "dek-under-kek"
    private val boxName = "box-private-key-under-dek"
    private val prevName = "previous-dek-under-new-dek"
    private val saltBytes = ByteArray(16) { 7 }

    /** A service that only opens: its nonce source is never asked by an unwrap. */
    private val opener = accountService(CountingNonces(nonceOne))

    private fun accountService(nonces: NonceSource) =
        AccountKeyServiceImpl(nonces) { size -> ByteArray(size) }

    private fun wrapNamed(name: String): WrapVector = CryptoVectorData.wrap.single { it.name == name }

    private fun flipBitAt(bytes: ByteArray, index: Int): ByteArray {
        val copy = bytes.copyOf()
        copy[index] = (copy[index].toInt() xor 1).toByte()
        return copy
    }

    /** Throws the plain refusal or fails the test; returns the refusal so its message can be read. */
    private fun refusedExactly(message: String, block: () -> Unit): CryptoRefused {
        val thrown = assertThrows(message, Throwable::class.java) { block() }
        assertEquals(message, CryptoRefused::class.java, thrown.javaClass)
        return thrown as CryptoRefused
    }

    private fun openBox(blob: ByteArray, key: ByteArray): ByteArray =
        opener.unwrapBoxPrivateKey(WrappedBoxPrivateKey(blob), DataEncryptionKey(key)).copyBytes()

    private fun openPrev(blob: ByteArray, key: ByteArray): ByteArray =
        opener.unwrapPreviousDek(WrappedPreviousDek(blob), DataEncryptionKey(key)).bytes

    /** The wrapped data key opens through [CryptoServiceImpl], the existing service for that form. */
    private fun openDek(blob: ByteArray, key: ByteArray): ByteArray =
        CryptoServiceImpl().unwrapDek(wrapped(blob), KeyEncryptionKey(key)).bytes

    private fun openBy(name: String, blob: ByteArray, key: ByteArray): ByteArray =
        when (name) {
            dekName -> openDek(blob, key)
            boxName -> openBox(blob, key)
            prevName -> openPrev(blob, key)
            else -> throw IllegalArgumentException("unknown wrap vector")
        }

    private fun wrapBy(
        service: AccountKeyService,
        name: String,
        plain: ByteArray,
        key: ByteArray,
    ): ByteArray =
        when (name) {
            dekName -> service.wrapDek(DataEncryptionKey(plain), KeyEncryptionKey(key), saltBytes).bytes
            boxName -> service.wrapBoxPrivateKey(BoxPrivateKey(plain), DataEncryptionKey(key)).bytes
            prevName -> service.wrapPreviousDek(DataEncryptionKey(plain), DataEncryptionKey(key)).bytes
            else -> throw IllegalArgumentException("unknown wrap vector")
        }

    /** Offers a refusal vector to the unwrap its name targets, in the way its name says. */
    private fun offerRefusal(r: WrapRefusal) {
        val blob = hex(r.wrappedHex)
        val key = hex(r.wrapperKeyHex)
        when (r.name) {
            "box-blob-with-empty-aad", "prev-dek-blob-with-empty-aad" -> openDek(blob, key)
            "box-blob-with-prev-dek-aad" -> openPrev(blob, key)
            "prev-dek-blob-with-box-aad" -> openBox(blob, key)
            "box-blob-with-other-purpose" -> {
                val plain = hex(wrapNamed(boxName).plainHex)
                openBox(sealKey(plain, key, hex(r.aadHex), blob.copyOfRange(0, GCM_NONCE_BYTES)), key)
            }
            else -> throw IllegalArgumentException("unknown wrap refusal")
        }
    }

    private fun assertNoHex(label: String, text: String, vararg secretHex: String) {
        for (secret in secretHex) {
            assertFalse("crypto: $label holds key hex", text.contains(secret.substring(0, 16)))
        }
    }

    /** A failure means a wrap form changed: the bytes, the associated data or the nonce use. */
    @Test
    fun `each wrap vector is made byte for byte from its nonce, plain key and wrapper key`() {
        for (v in CryptoVectorData.wrap) {
            val nonces = CountingNonces(hex(v.nonceHex))
            val made = wrapBy(accountService(nonces), v.name, hex(v.plainHex), hex(v.wrapperKeyHex))
            assertArrayEquals("crypto vector ${v.name}: wrapped bytes", hex(v.wrappedHex), made)
            assertEquals("crypto vector ${v.name}: nonces asked", 1, nonces.calls)
        }
    }

    /** A failure means a shared wrapped blob does not open to its plain key. */
    @Test
    fun `each wrap vector opens with the matching unwrap to its plain key`() {
        for (v in CryptoVectorData.wrap) {
            val opened = openBy(v.name, hex(v.wrappedHex), hex(v.wrapperKeyHex))
            assertArrayEquals("crypto vector ${v.name}: plain key", hex(v.plainHex), opened)
        }
    }

    /** A failure means a refusal vector was accepted by the unwrap it targets. */
    @Test
    fun `each wrap refusal is refused by the unwrap its name targets`() {
        val names = CryptoVectorData.wrapRefusals.map { it.name }.toSet()
        val expected = setOf(
            "box-blob-with-empty-aad",
            "box-blob-with-prev-dek-aad",
            "box-blob-with-other-purpose",
            "prev-dek-blob-with-empty-aad",
            "prev-dek-blob-with-box-aad",
        )
        assertEquals("crypto: the set of wrap refusal vectors changed", expected, names)
        for (r in CryptoVectorData.wrapRefusals) {
            refusedExactly("crypto vector ${r.name}: it was accepted") { offerRefusal(r) }
        }
    }

    /** A failure means the purpose strings no longer keep the two slots apart. */
    @Test
    fun `the previous key blob is refused in the box slot and the box blob in the previous slot`() {
        val maker = accountService(CountingUpNonces(0x30))
        val boxBlob = maker.wrapBoxPrivateKey(BoxPrivateKey(patterned(0x10, 32)), dek).bytes
        val prevBlob = maker.wrapPreviousDek(DataEncryptionKey(patterned(0x20, 32)), dek).bytes

        val boxOpened = openBox(boxBlob, dekBytes)
        val prevOpened = openPrev(prevBlob, dekBytes)
        assertArrayEquals("crypto: control, box blob in its own slot", patterned(0x10, 32), boxOpened)
        assertArrayEquals("crypto: control, previous blob in its own slot", patterned(0x20, 32), prevOpened)
        refusedExactly("crypto: the previous key blob was accepted in the box slot") {
            openBox(prevBlob, dekBytes)
        }
        refusedExactly("crypto: the box blob was accepted in the previous slot") {
            openPrev(boxBlob, dekBytes)
        }
    }

    /** A failure means a blob of 59 or 61 bytes was accepted by one of the two unwraps. */
    @Test
    fun `blobs of 59 and 61 bytes are refused by both unwraps`() {
        for (name in listOf(boxName, prevName)) {
            val v = wrapNamed(name)
            val key = hex(v.wrapperKeyHex)
            val blob = hex(v.wrappedHex)
            val bad = listOf("59" to blob.copyOf(59), "61" to (blob + byteArrayOf(0)))
            for ((label, bytes) in bad) {
                refusedExactly("crypto vector ${v.name}: $label bytes in the box slot") {
                    openBox(bytes, key)
                }
                refusedExactly("crypto vector ${v.name}: $label bytes in the previous slot") {
                    openPrev(bytes, key)
                }
            }
        }
    }

    /** A failure means a damaged nonce, ciphertext or tag was accepted. */
    @Test
    fun `a flipped bit in the nonce, the ciphertext or the tag is refused for every blob`() {
        val parts = listOf("nonce" to 3, "ciphertext" to 17, "tag" to 50)
        for (v in CryptoVectorData.wrap) {
            for ((part, index) in parts) {
                val damaged = flipBitAt(hex(v.wrappedHex), index)
                refusedExactly("crypto vector ${v.name}: a flipped $part bit was accepted") {
                    openBy(v.name, damaged, hex(v.wrapperKeyHex))
                }
            }
        }
    }

    /** A failure means a wrong wrapper key was accepted. */
    @Test
    fun `a wrong wrapper key is refused for every blob`() {
        for (v in CryptoVectorData.wrap) {
            val wrongKey = flipBitAt(hex(v.wrapperKeyHex), 0)
            refusedExactly("crypto vector ${v.name}: a wrong key was accepted") {
                openBy(v.name, hex(v.wrappedHex), wrongKey)
            }
        }
    }

    /** A failure means a blob made with empty associated data opens in a purpose slot. */
    @Test
    fun `the data key blob made with empty associated data is refused by both purpose unwraps`() {
        val v = wrapNamed(dekName)
        val blob = hex(v.wrappedHex)
        val key = hex(v.wrapperKeyHex)
        val opened = openDek(blob, key)
        assertArrayEquals("crypto: control, the blob opens as a data key", hex(v.plainHex), opened)
        refusedExactly("crypto vector ${v.name}: accepted in the box slot") { openBox(blob, key) }
        refusedExactly("crypto vector ${v.name}: accepted in the previous slot") { openPrev(blob, key) }
    }

    /** A failure means the salt is not checked, or the result shares the caller's array. */
    @Test
    fun `wrapDek refuses an empty salt and keeps its own copy of the salt`() {
        val service = accountService(CountingUpNonces(0x30))
        val kek = KeyEncryptionKey(kekBytes)
        refusedExactly("crypto: an empty salt was accepted") { service.wrapDek(dek, kek, ByteArray(0)) }

        val salt = ByteArray(16) { 7 }
        val result = service.wrapDek(dek, kek, salt)
        salt.fill(0)
        val expectedSalt = ByteArray(16) { 7 }
        assertArrayEquals("crypto: the result changed with the caller's salt", expectedSalt, result.salt)
        assertNotSame("crypto: the result holds the caller's salt array", salt, result.salt)
    }

    /** A failure means a purpose string in KeyWrap.kt is not the one in the shared vectors. */
    @Test
    fun `the associated data constants equal the aad of the shared vectors`() {
        val box = aadBytes(BOX_PRIVATE_KEY_AAD_TEXT)
        val prev = aadBytes(PREVIOUS_DEK_AAD_TEXT)
        assertArrayEquals("crypto vector $boxName: aad", hex(wrapNamed(boxName).aadHex), box)
        assertArrayEquals("crypto vector $prevName: aad", hex(wrapNamed(prevName).aadHex), prev)
        assertFalse("crypto: the two purpose strings are equal", box.contentEquals(prev))
    }

    /** A failure means a returned key object or a refusal message prints key bytes. */
    @Test
    fun `no returned key object and no refusal message prints key hex`() {
        val dekV = wrapNamed(dekName)
        val boxV = wrapNamed(boxName)
        val prevV = wrapNamed(prevName)
        val maker = accountService(CountingUpNonces(0x30))
        val dekPlain = DataEncryptionKey(hex(dekV.plainHex))
        val kek = KeyEncryptionKey(hex(dekV.wrapperKeyHex))
        val boxKey = DataEncryptionKey(hex(boxV.wrapperKeyHex))
        val prevKey = DataEncryptionKey(hex(prevV.wrapperKeyHex))
        val shown = listOf(
            dekPlain,
            kek,
            boxKey,
            maker.wrapDek(dekPlain, kek, saltBytes),
            maker.wrapBoxPrivateKey(BoxPrivateKey(hex(boxV.plainHex)), boxKey),
            maker.wrapPreviousDek(DataEncryptionKey(hex(prevV.plainHex)), prevKey),
            opener.unwrapBoxPrivateKey(WrappedBoxPrivateKey(hex(boxV.wrappedHex)), boxKey),
            opener.unwrapPreviousDek(WrappedPreviousDek(hex(prevV.wrappedHex)), prevKey),
        )

        val everyHex = CryptoVectorData.wrap.flatMap { listOf(it.plainHex, it.wrapperKeyHex, it.wrappedHex) }
        for (item in shown) {
            val text = item.toString()
            val label = "toString of ${item.javaClass.simpleName}"
            assertNoHex(label, text, *everyHex.toTypedArray())
        }

        for (r in CryptoVectorData.wrapRefusals) {
            val refusal = refusedExactly("crypto vector ${r.name}: it was accepted") { offerRefusal(r) }
            val label = "the refusal message of ${r.name}"
            assertNoHex(label, refusal.message.orEmpty(), r.wrapperKeyHex, r.wrappedHex)
            assertNull("crypto vector ${r.name}: the refusal has a cause", refusal.cause)
        }
    }
}
