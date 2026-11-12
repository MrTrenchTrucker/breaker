package dev.breaker.shared.api.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RecordFrameTest {

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("not a hex digit")
    }

    private fun hexBytes(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((hexDigit(s[2 * i]) shl 4) or hexDigit(s[2 * i + 1])).toByte()
        }
        return out
    }

    private fun hexText(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v shr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    private fun refusalOf(name: String, block: () -> Unit): SyncBodyException {
        try {
            block()
        } catch (e: SyncBodyException) {
            return e
        }
        fail("no refusal of " + name)
        throw IllegalStateException("unreachable")
    }

    private fun assertRefusedWith(name: String, family: String, block: () -> Unit): SyncBodyException {
        val e = refusalOf(name, block)
        assertEquals("class of " + name, SyncBodyException::class.java, e.javaClass)
        assertEquals("vector " + name, "sync body refused: " + family, e.message)
        return e
    }

    private fun frameVector(name: String): RecordFrameVectorData.FrameVector =
        RecordFrameVectorData.frames.first { it.name == name }

    private fun aadHex(name: String): String = RecordFrameVectorData.aads.first { it.name == name }.hex

    @Test
    fun `join gives the encrypted text of every frame vector`() {
        for (v in RecordFrameVectorData.frames) {
            val text = RecordFrame.join(hexBytes(v.nonceHex), hexBytes(v.ciphertextHex), hexBytes(v.tagHex))
            assertEquals("frame vector " + v.name, v.encrypted, text)
        }
    }

    @Test
    fun `split gives the three parts of every frame vector byte for byte`() {
        for (v in RecordFrameVectorData.frames) {
            val parts = RecordFrame.split(v.encrypted)
            assertEquals("nonce of " + v.name, v.nonceHex, hexText(parts.nonce))
            assertEquals("ciphertext of " + v.name, v.ciphertextHex, hexText(parts.ciphertext))
            assertEquals("tag of " + v.name, v.tagHex, hexText(parts.tag))
        }
    }

    @Test
    fun `join of split gives back the input text of every frame vector`() {
        for (v in RecordFrameVectorData.frames) {
            val parts = RecordFrame.split(v.encrypted)
            val again = RecordFrame.join(parts.nonce, parts.ciphertext, parts.tag)
            assertEquals("re-encoded text of " + v.name, v.encrypted, again)
        }
    }

    @Test
    fun `the frame vectors cover an empty ciphertext and a frame of exactly 28 bytes`() {
        val empty = RecordFrame.split(frameVector("empty-ciphertext").encrypted)
        assertEquals(0, empty.ciphertext.size)
        assertEquals(RecordFrame.MIN_FRAME_BYTES, empty.nonce.size + empty.ciphertext.size + empty.tag.size)
    }

    @Test
    fun `refuses every join refusal vector with exactly its family`() {
        for (v in RecordFrameVectorData.joinRefusals) {
            assertRefusedWith(v.name, v.family) {
                RecordFrame.join(hexBytes(v.nonceHex), hexBytes(v.ciphertextHex), hexBytes(v.tagHex))
            }
        }
    }

    @Test
    fun `refuses every split refusal vector with exactly its family`() {
        for (v in RecordFrameVectorData.splitRefusals) {
            assertRefusedWith(v.name, v.family) { RecordFrame.split(v.input) }
        }
    }

    @Test
    fun `refuses text that is not the canonical encoding of its bytes`() {
        for (name in listOf("non-canonical-28-bytes", "non-canonical-29-bytes")) {
            val v = RecordFrameVectorData.splitRefusals.first { it.name == name }
            assertRefusedWith(v.name, "base64") { RecordFrame.split(v.input) }
        }
    }

    @Test
    fun `refusal messages carry no text of the input`() {
        for (v in RecordFrameVectorData.splitRefusals) {
            val message = assertRefusedWith(v.name, v.family) { RecordFrame.split(v.input) }.message ?: ""
            if (v.input.length > 3 && v.input != v.family) {
                assertFalse("message of " + v.name + " holds the input", message.contains(v.input))
            }
        }
        for (v in RecordFrameVectorData.aadRefusals) {
            val message = assertRefusedWith(v.name, v.family) { RecordFrame.aad(v.clientId, v.keyVersion) }
                .message ?: ""
            if (v.clientId.length > 3) {
                assertFalse("message of " + v.name + " holds the client id", message.contains(v.clientId))
            }
        }
    }

    @Test
    fun `builds the associated data of every aad vector byte for byte`() {
        for (v in RecordFrameVectorData.aads) {
            assertEquals("aad vector " + v.name, v.hex, hexText(RecordFrame.aad(v.clientId, v.keyVersion)))
        }
    }

    @Test
    fun `key version 10 differs from key version 1 and has two digits`() {
        val clientId = RecordFrameVectorData.aads.first { it.name == "client-a-version-1" }.clientId
        val one = RecordFrame.aad(clientId, 1)
        val ten = RecordFrame.aad(clientId, 10)
        assertNotEquals(hexText(one), hexText(ten))
        assertEquals(one.size + 1, ten.size)
        assertEquals('1'.code, ten[ten.size - 2].toInt())
        assertEquals('0'.code, ten[ten.size - 1].toInt())
        assertEquals(0x0A, ten[clientId.length].toInt())
        assertTrue(aadHex("client-a-version-10").endsWith("0a3130"))
    }

    @Test
    fun `refuses every aad refusal vector with exactly its family`() {
        for (v in RecordFrameVectorData.aadRefusals) {
            assertRefusedWith(v.name, v.family) { RecordFrame.aad(v.clientId, v.keyVersion) }
        }
    }

    @Test
    fun `checks the client id before the key version`() {
        assertRefusedWith("both wrong", "client_id") { RecordFrame.aad("", 0) }
    }

    @Test
    fun `the parts hold copies of the arrays they were given`() {
        val nonce = hexBytes("000102030405060708090a0b")
        val ciphertext = hexBytes("4142")
        val tag = hexBytes("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf")
        val parts = RecordFrameParts(nonce, ciphertext, tag)
        nonce[0] = 0x7F
        ciphertext[0] = 0x7F
        tag[0] = 0x7F
        assertEquals("000102030405060708090a0b", hexText(parts.nonce))
        assertEquals("4142", hexText(parts.ciphertext))
        assertEquals("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf", hexText(parts.tag))
    }

    @Test
    fun `the properties return the stored arrays`() {
        val parts = RecordFrame.split(frameVector("one-byte-ciphertext").encrypted)
        assertSame(parts.nonce, parts.nonce)
        assertSame(parts.ciphertext, parts.ciphertext)
        assertSame(parts.tag, parts.tag)
    }

    @Test
    fun `editing the arrays of one split does not change another split of the same text`() {
        val text = frameVector("one-byte-ciphertext").encrypted
        val first = RecordFrame.split(text)
        val second = RecordFrame.split(text)
        first.nonce[0] = 0x7F
        first.ciphertext[0] = 0x7F
        first.tag[0] = 0x7F
        assertEquals("000102030405060708090a0b", hexText(second.nonce))
        assertEquals("41", hexText(second.ciphertext))
        assertEquals("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf", hexText(second.tag))
    }

    @Test
    fun `editing the arrays handed to join afterwards does not change the text it gave`() {
        val v = frameVector("one-byte-ciphertext")
        val nonce = hexBytes(v.nonceHex)
        val ciphertext = hexBytes(v.ciphertextHex)
        val tag = hexBytes(v.tagHex)
        val text = RecordFrame.join(nonce, ciphertext, tag)
        nonce[0] = 0x7F
        ciphertext[0] = 0x7F
        tag[0] = 0x7F
        assertEquals(v.encrypted, text)
        val again = RecordFrame.join(hexBytes(v.nonceHex), hexBytes(v.ciphertextHex), hexBytes(v.tagHex))
        assertEquals(v.encrypted, again)
    }

    @Test
    fun `the text form of the parts shows sizes and no bytes`() {
        val parts = RecordFrame.split(frameVector("one-byte-ciphertext").encrypted)
        assertEquals("RecordFrameParts(nonce=12, ciphertext=1, tag=16)", parts.toString())
    }

    @Test
    fun `the sizes are the literal numbers of the frame`() {
        assertEquals(12, RecordFrame.NONCE_BYTES)
        assertEquals(16, RecordFrame.TAG_BYTES)
        assertEquals(28, RecordFrame.MIN_FRAME_BYTES)
    }

    @Test
    fun `the two new families are the literal words`() {
        assertEquals("frame", SyncBodyFamily.FRAME)
        assertEquals("key_version", SyncBodyFamily.KEY_VERSION)
    }

    @Test
    fun `split of join of a frame with a ciphertext of 100 bytes keeps every part`() {
        val nonce = ByteArray(RecordFrame.NONCE_BYTES) { (it + 1).toByte() }
        val ciphertext = ByteArray(100) { (it + 50).toByte() }
        val tag = ByteArray(RecordFrame.TAG_BYTES) { (it + 200).toByte() }
        val parts = RecordFrame.split(RecordFrame.join(nonce, ciphertext, tag))
        assertArrayEquals(nonce, parts.nonce)
        assertArrayEquals(ciphertext, parts.ciphertext)
        assertArrayEquals(tag, parts.tag)
    }
}
