package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.CipherText
import dev.breaker.shared.api.sync.RecordFrame
import dev.breaker.shared.api.sync.SyncBodyException
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The record frame of the shared contract module and the real service work together: a record sealed
 * by [CryptoServiceImpl] is joined into one frame text, split again and opened, and the tag binds the
 * associated data that [RecordFrame.aad] builds. The vectors and helpers are in [CryptoServiceTestFixture].
 */
class RecordFrameRoundTripTest : CryptoServiceTestFixture() {

    private fun sealedThroughTheFrame(text: String, clientId: String, keyVersion: Int): CipherText {
        val service = serviceWith(CountingNonces(nonceOne))
        val sealed = service.encrypt(text, dek, RecordFrame.aad(clientId, keyVersion))
        val frame = RecordFrame.join(sealed.nonce, sealed.ciphertext, sealed.tag)
        val parts = RecordFrame.split(frame)
        return CipherText(parts.ciphertext, parts.nonce, parts.tag)
    }

    @Test
    fun `the aad of the frame is the form the fixture vectors use`() {
        assertArrayEquals(
            "crypto: RecordFrame.aad differs from the client id, newline, decimal key version of the vectors",
            aadFor(clientA, 1),
            RecordFrame.aad(clientA, 1),
        )
        assertArrayEquals(
            "crypto: RecordFrame.aad of key version 2 differs from the vectors",
            aadFor(clientA, 2),
            RecordFrame.aad(clientA, 2),
        )
    }

    @Test
    fun `a record sealed joined into a frame split and opened gives the same text`() {
        val service = serviceWith(CountingNonces(nonceOne))
        val rebuilt = sealedThroughTheFrame(r1Text, clientA, 1)

        assertEquals(
            "crypto: the record did not survive the frame",
            r1Text,
            service.decrypt(rebuilt, dek, RecordFrame.aad(clientA, 1)),
        )
    }

    @Test
    fun `a record with an empty text survives the frame as a 28 byte frame`() {
        val service = serviceWith(CountingNonces(nonceTwo))
        val sealed = service.encrypt("", dek, RecordFrame.aad(clientB, 1))
        val frame = RecordFrame.join(sealed.nonce, sealed.ciphertext, sealed.tag)

        val decoded = Base64.getDecoder().decode(frame)
        assertEquals("crypto: an empty text must give a frame of 28 bytes", 28, decoded.size)
        val parts = RecordFrame.split(frame)
        val rebuilt = CipherText(parts.ciphertext, parts.nonce, parts.tag)
        val opened = service.decrypt(rebuilt, dek, RecordFrame.aad(clientB, 1))
        assertEquals("crypto: the empty text did not survive", "", opened)
    }

    @Test
    fun `the parts after the frame are the parts before it`() {
        val service = serviceWith(CountingNonces(nonceOne))
        val sealed = service.encrypt(r1Text, dek, RecordFrame.aad(clientA, 1))
        val parts = RecordFrame.split(RecordFrame.join(sealed.nonce, sealed.ciphertext, sealed.tag))

        assertArrayEquals("crypto: nonce changed in the frame", sealed.nonce, parts.nonce)
        assertArrayEquals("crypto: ciphertext changed in the frame", sealed.ciphertext, parts.ciphertext)
        assertArrayEquals("crypto: tag changed in the frame", sealed.tag, parts.tag)
    }

    @Test
    fun `a record is refused under the aad of another client id`() {
        val service = serviceWith(CountingNonces(nonceOne))
        val rebuilt = sealedThroughTheFrame(r1Text, clientA, 1)

        refused("crypto: a framed record opened under another client id") {
            service.decrypt(rebuilt, dek, RecordFrame.aad(clientB, 1))
        }
    }

    @Test
    fun `a record is refused under the aad of another key version`() {
        val service = serviceWith(CountingNonces(nonceOne))
        val rebuilt = sealedThroughTheFrame(r1Text, clientA, 1)

        refused("crypto: a framed record opened under another key version") {
            service.decrypt(rebuilt, dek, RecordFrame.aad(clientA, 2))
        }
    }

    @Test
    fun `a frame of 27 bytes is refused by split before any decrypt`() {
        val short = Base64.getEncoder().encodeToString(patterned(0x90, 12) + patterned(0xA0, 15))

        val refusal = assertThrows(SyncBodyException::class.java) { RecordFrame.split(short) }

        assertEquals("sync body refused: frame", refusal.message)
    }
}
