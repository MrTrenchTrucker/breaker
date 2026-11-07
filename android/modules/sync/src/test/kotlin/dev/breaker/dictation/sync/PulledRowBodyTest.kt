package dev.breaker.dictation.sync

import dev.breaker.shared.api.TranscriptionRecord
import dev.breaker.shared.api.sync.SyncBody
import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.shared.api.sync.SyncBodyException
import dev.breaker.shared.api.sync.SyncSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for PulledRowBody: reading the body of one pulled record at key_version 0. */
class PulledRowBodyTest {

    private val clientId = "0f9c" + "4d1e-6a2b-4c3d-9e8f-" + 1.toString().padStart(12, '0')
    private val storedAt = "2023-11-14T22:13:20.000Z"

    private fun aRecord(
        deleted: Boolean = false,
        keyVersion: Int? = 0,
        encrypted: String? = null,
    ): TranscriptionRecord =
        TranscriptionRecord(
            clientId = clientId,
            change = 1L,
            deleted = deleted,
            keyVersion = keyVersion,
            encrypted = encrypted,
            storedAt = storedAt,
        )

    /** A failure here means a live key_version 0 record does not return its body for source LOCAL. */
    @Test
    fun `read returns the body for source LOCAL`() {
        val body = SyncBody("hello world", SyncSource.LOCAL, "small.en", 1200, 1_700_000_000_000L)
        val encrypted = SyncBodyCodec.encodePlain(body)
        val record = aRecord(keyVersion = 0, encrypted = encrypted)
        val result = PulledRowBody.read(record)
        assertEquals("android_sync: read must return the body unchanged for source LOCAL", body, result)
    }

    /** A failure here means a live key_version 0 record does not return its body for source SERVER. */
    @Test
    fun `read returns the body for source SERVER`() {
        val body = SyncBody("hello world", SyncSource.SERVER, "small.en", 1200, 1_700_000_000_000L)
        val encrypted = SyncBodyCodec.encodePlain(body)
        val record = aRecord(keyVersion = 0, encrypted = encrypted)
        val result = PulledRowBody.read(record)
        assertEquals("android_sync: read must return the body unchanged for source SERVER", body, result)
    }

    /** A failure here means a non ASCII letter in the body text is not preserved. */
    @Test
    fun `read returns the body with a non ASCII letter`() {
        val body = SyncBody("caf\u00E9", SyncSource.LOCAL, "small.en", 1200, 1_700_000_000_000L)
        val encrypted = SyncBodyCodec.encodePlain(body)
        val record = aRecord(keyVersion = 0, encrypted = encrypted)
        val result = PulledRowBody.read(record)
        assertEquals(
            "android_sync: read must preserve a non ASCII letter in the body text",
            "caf\u00E9",
            result.text,
        )
    }

    /** A failure here means an emoji in the body text is not preserved. */
    @Test
    fun `read returns the body with an emoji`() {
        val body = SyncBody("\uD83D\uDE00", SyncSource.SERVER, "small.en", 1200, 1_700_000_000_000L)
        val encrypted = SyncBodyCodec.encodePlain(body)
        val record = aRecord(keyVersion = 0, encrypted = encrypted)
        val result = PulledRowBody.read(record)
        assertEquals(
            "android_sync: read must preserve an emoji in the body text",
            "\uD83D\uDE00",
            result.text,
        )
    }

    /** A failure here means an empty model is not accepted. */
    @Test
    fun `read returns the body with an empty model`() {
        val body = SyncBody("hello", SyncSource.LOCAL, "", 1200, 1_700_000_000_000L)
        val encrypted = SyncBodyCodec.encodePlain(body)
        val record = aRecord(keyVersion = 0, encrypted = encrypted)
        val result = PulledRowBody.read(record)
        assertEquals("android_sync: read must accept an empty model", "", result.model)
    }

    /** A failure here means an empty text is not accepted. */
    @Test
    fun `read returns the body with an empty text`() {
        val body = SyncBody("", SyncSource.SERVER, "small.en", 1200, 1_700_000_000_000L)
        val encrypted = SyncBodyCodec.encodePlain(body)
        val record = aRecord(keyVersion = 0, encrypted = encrypted)
        val result = PulledRowBody.read(record)
        assertEquals("android_sync: read must accept an empty text", "", result.text)
    }

    /** A failure here means the literal known body is not read back exactly. */
    @Test
    fun `read returns the literal known body`() {
        val record = aRecord(keyVersion = 0, encrypted = literalKnownBody())
        val result = PulledRowBody.read(record)
        assertEquals("android_sync: read must return the literal body text", "hello world", result.text)
        assertEquals(
            "android_sync: read must return the literal body source",
            SyncSource.LOCAL,
            result.source)
        assertEquals("android_sync: read must return the literal body model", "small.en", result.model)
        assertEquals("android_sync: read must return the literal body duration", 1200L, result.durationMs)
        assertEquals(
            "android_sync: read must return the literal body created at",
            1_700_000_000_000L,
            result.createdAtMs)
    }

    private fun literalKnownBody(): String {
        val part1 = "eyJ2IjoxLCJ0ZXh0IjoiaGVsbG8gd29ybGQiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoic21"
        val part2 = "hbGwuZW4iLCJkdXJhdGlvbl9tcyI6MTIwMCwiY3JlYXRlZF9hdCI6IjIwMjMtMTEtMTRUMjI6MT"
        val part3 = "M6MjAuMDAwWiJ9"
        return part1 + part2 + part3
    }


    /** A failure here means a delete record is read instead of refused. */
    @Test
    fun `a delete record is refused`() {
        val body = SyncBody("hello world", SyncSource.LOCAL, "small.en", 1200, 1_700_000_000_000L)
        val encrypted = SyncBodyCodec.encodePlain(body)
        val deleted = aRecord(deleted = true, keyVersion = 0, encrypted = encrypted)
        val failure = thrownBy { PulledRowBody.read(deleted) }
        assertNotNull("android_sync: a delete record must throw", failure)
        assertTrue("android_sync: the refusal must be a PulledRowException", failure is PulledRowException)
        if (failure is PulledRowException) {
            assertEquals(
                "android_sync: the delete message must name the rule",
                "pulled body refused: delete record has no body",
                failure.message)
        }
        // Accept control: the same record live reads back its body.
        val live = aRecord(deleted = false, keyVersion = 0, encrypted = encrypted)
        val result = PulledRowBody.read(live)
        assertEquals("android_sync: accept control: a live record reads its body", body, result)
    }

    /** A failure here means a null key version is read instead of refused. */
    @Test
    fun `a null key version is refused`() {
        val body = SyncBody("hello world", SyncSource.LOCAL, "small.en", 1200, 1_700_000_000_000L)
        val encrypted = SyncBodyCodec.encodePlain(body)
        val record = aRecord(keyVersion = null, encrypted = encrypted)
        val failure = thrownBy { PulledRowBody.read(record) }
        assertNotNull("android_sync: a null key version must throw", failure)
        assertTrue("android_sync: the refusal must be a PulledRowException", failure is PulledRowException)
        if (failure is PulledRowException) {
            assertEquals(
                "android_sync: the null key version message must name the rule",
                "pulled body refused: missing key version",
                failure.message)
        }
        // Accept control: the same record with a key version reads back its body.
        val withVersion = aRecord(keyVersion = 0, encrypted = encrypted)
        val result = PulledRowBody.read(withVersion)
        assertEquals("android_sync: accept control: a key version 0 record reads its body", body, result)
    }

    /** A failure here means key version 1 is read instead of refused. */
    @Test
    fun `key version 1 is refused with the number in the message`() {
        val body = SyncBody("hello world", SyncSource.LOCAL, "small.en", 1200, 1_700_000_000_000L)
        val encrypted = SyncBodyCodec.encodePlain(body)
        val record = aRecord(keyVersion = 1, encrypted = encrypted)
        val failure = thrownBy { PulledRowBody.read(record) }
        assertNotNull("android_sync: key version 1 must throw", failure)
        assertTrue("android_sync: the refusal must be a PulledRowException", failure is PulledRowException)
        if (failure is PulledRowException) {
            assertEquals(
                "android_sync: the key version 1 message must hold the number",
                "pulled body refused: key version 1 is not read yet",
                failure.message)
            assertTrue(
                "android_sync: the message must not contain the record text",
                failure.message!!.indexOf("hello world") < 0)
        }
        // Accept control: the same body at key version 0 reads back.
        val atZero = aRecord(keyVersion = 0, encrypted = encrypted)
        val result = PulledRowBody.read(atZero)
        assertEquals("android_sync: accept control: key version 0 reads the body", body, result)
    }

    /** A failure here means key version 2 is read instead of refused. */
    @Test
    fun `key version 2 is refused with the number in the message`() {
        val body = SyncBody("hello world", SyncSource.LOCAL, "small.en", 1200, 1_700_000_000_000L)
        val encrypted = SyncBodyCodec.encodePlain(body)
        val record = aRecord(keyVersion = 2, encrypted = encrypted)
        val failure = thrownBy { PulledRowBody.read(record) }
        assertNotNull("android_sync: key version 2 must throw", failure)
        assertTrue("android_sync: the refusal must be a PulledRowException", failure is PulledRowException)
        if (failure is PulledRowException) {
            assertEquals(
                "android_sync: the key version 2 message must hold the number",
                "pulled body refused: key version 2 is not read yet",
                failure.message)
            assertTrue(
                "android_sync: the message must not contain the record text",
                failure.message!!.indexOf("hello world") < 0)
        }
    }

    /** A failure here means key version 255 is read instead of refused. */
    @Test
    fun `key version 255 is refused with the number in the message`() {
        val body = SyncBody("hello world", SyncSource.LOCAL, "small.en", 1200, 1_700_000_000_000L)
        val encrypted = SyncBodyCodec.encodePlain(body)
        val record = aRecord(keyVersion = 255, encrypted = encrypted)
        val failure = thrownBy { PulledRowBody.read(record) }
        assertNotNull("android_sync: key version 255 must throw", failure)
        assertTrue("android_sync: the refusal must be a PulledRowException", failure is PulledRowException)
        if (failure is PulledRowException) {
            assertEquals(
                "android_sync: the key version 255 message must hold the number",
                "pulled body refused: key version 255 is not read yet",
                failure.message)
            assertTrue(
                "android_sync: the message must not contain the record text",
                failure.message!!.indexOf("hello world") < 0)
        }
    }

    /** A failure here means key version 0 with null encrypted is read instead of refused. */
    @Test
    fun `key version 0 with null encrypted is refused`() {
        val record = aRecord(keyVersion = 0, encrypted = null)
        val failure = thrownBy { PulledRowBody.read(record) }
        assertNotNull("android_sync: null encrypted at version 0 must throw", failure)
        assertTrue("android_sync: the refusal must be a PulledRowException", failure is PulledRowException)
        if (failure is PulledRowException) {
            assertEquals(
                "android_sync: the null encrypted message must name the rule",
                "pulled body refused: missing encrypted body",
                failure.message)
        }
        // Accept control: the same record with non-null encrypted reads back its body.
        val body = SyncBody("hello world", SyncSource.LOCAL, "small.en", 1200, 1_700_000_000_000L)
        val encrypted = SyncBodyCodec.encodePlain(body)
        val withEncrypted = aRecord(keyVersion = 0, encrypted = encrypted)
        val result = PulledRowBody.read(withEncrypted)
        assertEquals("android_sync: accept control: non-null encrypted reads the body", body, result)
    }

    /** A failure here means non base64 encrypted is not refused. */
    @Test
    fun `non base64 encrypted is refused`() {
        val record = aRecord(keyVersion = 0, encrypted = "%%%")
        val failure = thrownBy { PulledRowBody.read(record) }
        assertNotNull("android_sync: non base64 must throw", failure)
        assertTrue("android_sync: the refusal must be a PulledRowException", failure is PulledRowException)
        if (failure is PulledRowException) {
            assertEquals(
                "android_sync: the base64 refusal message must wrap the codec message",
                "pulled body refused: sync body refused: base64",
                failure.message)
            assertNotNull("android_sync: the cause must be a SyncBodyException", failure.cause)
            assertTrue(
                "android_sync: the cause must be a SyncBodyException",
                failure.cause is SyncBodyException)
        }
        // Accept control: a good base64 value reads back its body.
        val body = SyncBody("hello world", SyncSource.LOCAL, "small.en", 1200, 1_700_000_000_000L)
        val encrypted = SyncBodyCodec.encodePlain(body)
        val good = aRecord(keyVersion = 0, encrypted = encrypted)
        val result = PulledRowBody.read(good)
        assertEquals("android_sync: accept control: valid base64 reads the body", body, result)
    }

    /** A failure here means unpadded base64 is not refused. */
    @Test
    fun `unpadded base64 encrypted is refused`() {
        val record = aRecord(keyVersion = 0, encrypted = "aGVsbG8")
        val failure = thrownBy { PulledRowBody.read(record) }
        assertNotNull("android_sync: unpadded base64 must throw", failure)
        assertTrue("android_sync: the refusal must be a PulledRowException", failure is PulledRowException)
        if (failure is PulledRowException) {
            assertTrue(
                "android_sync: the message must name the base64 family",
                failure.message!!.contains("base64"))
            assertTrue(
                "android_sync: the cause must be a SyncBodyException",
                failure.cause is SyncBodyException)
        }
        // Accept control: padded base64 reads back its body.
        val body = SyncBody("hello world", SyncSource.LOCAL, "small.en", 1200, 1_700_000_000_000L)
        val encrypted = SyncBodyCodec.encodePlain(body)
        val good = aRecord(keyVersion = 0, encrypted = encrypted)
        val result = PulledRowBody.read(good)
        assertEquals("android_sync: accept control: padded base64 reads the body", body, result)
    }

    /** A failure here means base64 of non JSON text is not refused. */
    @Test
    fun `base64 of non JSON text is refused`() {
        val record = aRecord(keyVersion = 0, encrypted = "aGVsbG8=")
        val failure = thrownBy { PulledRowBody.read(record) }
        assertNotNull("android_sync: base64 of non JSON must throw", failure)
        assertTrue("android_sync: the refusal must be a PulledRowException", failure is PulledRowException)
        if (failure is PulledRowException) {
            assertTrue(
                "android_sync: the cause must be a SyncBodyException",
                failure.cause is SyncBodyException)
        }
        // Accept control: valid JSON base64 reads back its body.
        val body = SyncBody("hello world", SyncSource.LOCAL, "small.en", 1200, 1_700_000_000_000L)
        val encrypted = SyncBodyCodec.encodePlain(body)
        val good = aRecord(keyVersion = 0, encrypted = encrypted)
        val result = PulledRowBody.read(good)
        assertEquals("android_sync: accept control: valid JSON base64 reads the body", body, result)
    }

    /** A failure here means base64 of an object with a missing key is not refused. */
    @Test
    fun `base64 of an object with a missing key is refused`() {
        val record = aRecord(keyVersion = 0, encrypted = missingKeyBody())
        val failure = thrownBy { PulledRowBody.read(record) }
        assertNotNull("android_sync: missing key base64 must throw", failure)
        assertTrue("android_sync: the refusal must be a PulledRowException", failure is PulledRowException)
        if (failure is PulledRowException) {
            assertTrue(
                "android_sync: the cause must be a SyncBodyException",
                failure.cause is SyncBodyException)
        }
        // Accept control: a complete object reads back its body.
        val body = SyncBody("hello world", SyncSource.LOCAL, "small.en", 1200, 1_700_000_000_000L)
        val encrypted = SyncBodyCodec.encodePlain(body)
        val good = aRecord(keyVersion = 0, encrypted = encrypted)
        val result = PulledRowBody.read(good)
        assertEquals("android_sync: accept control: complete object reads the body", body, result)
    }

    private fun missingKeyBody(): String {
        val part1 = "eyJ2IjoxLCJ0ZXh0IjoieCIsInNvdXJjZSI6ImxvY2FsIiwibW9kZWwiOiJtIiwiZHVyYXRpb25"
        val part2 = "fbXMiOjB9"
        return part1 + part2
    }


    /** A failure here means the privacy marker leaks into the message for key version 1. */
    @Test
    fun `privacy marker not in the message for key version 1`() {
        val marker = "secret user text that must not leak"
        val body = SyncBody(marker, SyncSource.LOCAL, "small.en", 1200, 1_700_000_000_000L)
        val encrypted = SyncBodyCodec.encodePlain(body)
        val record = aRecord(keyVersion = 1, encrypted = encrypted)
        val failure = thrownBy { PulledRowBody.read(record) }
        assertNotNull("android_sync: key version 1 with marker must throw", failure)
        assertTrue("android_sync: the refusal must be a PulledRowException", failure is PulledRowException)
        if (failure is PulledRowException) {
            assertTrue(
                "android_sync: the message must not contain the marker",
                failure.message!!.indexOf(marker) < 0)
            assertTrue(
                "android_sync: the message must not contain the encrypted text",
                failure.message!!.indexOf(encrypted) < 0)
        }
    }

    /** A failure here means the privacy marker leaks into the message for a decode refusal. */
    @Test
    fun `privacy marker not in the message for a decode refusal`() {
        val marker = "secret user text that must not leak"
        val record = aRecord(keyVersion = 0, encrypted = marker)
        val failure = thrownBy { PulledRowBody.read(record) }
        assertNotNull("android_sync: marker as encrypted must throw", failure)
        assertTrue("android_sync: the refusal must be a PulledRowException", failure is PulledRowException)
        if (failure is PulledRowException) {
            assertTrue(
                "android_sync: the message must not contain the marker",
                failure.message!!.indexOf(marker) < 0)
            assertTrue(
                "android_sync: the message must not contain the record encrypted text",
                failure.message!!.indexOf(record.encrypted!!) < 0)
        }
    }

    /** What [block] threw, or null when it returned. An [Error] counts, so a guard failure shows up. */
    private fun thrownBy(block: () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (failure: Throwable) {
            failure
        }
}
