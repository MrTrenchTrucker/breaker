package dev.breaker.dictation.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The models hold their own invariants: bad values are refused at construction. */
class ModelTest {
    @Test
    fun `a transcription needs a non-blank id`() {
        try {
            Transcription(id = "  ", text = "hi", source = TranscriptionSource.LOCAL, model = "small", durationMs = 1, createdAt = 1)
            fail("expected a blank id to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("non-blank id"))
        }
    }

    @Test
    fun `a transcription cannot have a negative duration or timestamp`() {
        try {
            aTranscription(durationMs = -1, createdAt = 0)
            fail("expected a negative duration to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("durationMs"))
        }
        try {
            aTranscription(durationMs = 1, createdAt = -5)
            fail("expected a negative timestamp to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("createdAt"))
        }
    }

    @Test
    fun `silent audio is still a legal transcription`() {
        // Empty and whitespace-only text are both kept exactly as given: the
        // model neither refuses them nor tidies them.
        listOf("", "   ", "\n").forEach { silent ->
            val t = Transcription("t-1", silent, TranscriptionSource.LOCAL, "small", 1L, 0L)
            assertEquals("silent text of length ${silent.length} was altered", silent, t.text)
        }
    }

    @Test
    fun `a tile position outside the screen is refused`() {
        try {
            TilePosition(x = 1.4f, y = 0.5f)
            fail("expected an out-of-range tile position to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("screen fraction"))
        }
    }

    @Test
    fun `settings refuse a blank model or language`() {
        try {
            AppSettings(modelSize = "")
            fail("expected a blank model size to be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("modelSize"))
        }
        try {
            AppSettings(language = "")
            fail("expected a blank language to be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("language"))
        }
    }

    @Test
    fun `settings report whether a server address is configured`() {
        assertFalse(AppSettings(serverUrl = "").hasServerUrl)
        assertTrue(AppSettings(serverUrl = "https://box.local:8443").hasServerUrl)
    }

    @Test
    fun `settings default to server-primary with a blank server address`() {
        val defaults = AppSettings()
        assertEquals(SttMode.AUTO, defaults.mode)
        assertEquals("", defaults.serverUrl)
        assertNull(defaults.apiKeyRef)
        assertTrue(defaults.preloadModel)
    }

    @Test
    fun `a transcription request refuses empty audio or a blank model`() {
        try {
            SttRequest(pcm = FloatArray(0), wavBytes = byteArrayOf(1), model = "small", language = "en")
            fail("expected empty audio to be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("audio"))
        }
        try {
            SttRequest(pcm = floatArrayOf(0.1f), wavBytes = byteArrayOf(1), model = " ", language = "en")
            fail("expected a blank model to be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("model"))
        }
    }

    @Test
    fun `a request never prints its audio`() {
        val request = SttRequest(
            pcm = FloatArray(AudioFormat.SAMPLE_RATE_HZ) { 0.25f },
            wavBytes = ByteArray(44),
            model = "small",
            language = "en",
        )
        val printed = request.toString()
        assertFalse("a request must not print sample values", printed.contains("0.25"))
        assertTrue(printed.contains("model=small"))
    }

    @Test
    fun `a request reports its own duration in milliseconds`() {
        val oneSecond = SttRequest(
            pcm = FloatArray(AudioFormat.SAMPLE_RATE_HZ),
            wavBytes = ByteArray(44),
            model = "small",
            language = "en",
        )
        assertEquals(1_000L, oneSecond.durationMs)
    }

    @Test
    fun `a segment cannot end before it starts`() {
        try {
            SttSegment(startMs = 900, endMs = 100, text = "x")
            fail("expected an inverted segment to be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("precedes"))
        }
    }

    @Test
    fun `an stt result reports whether it succeeded`() {
        assertTrue(SttResult.Success(text = "hi").isSuccess)
        assertFalse(SttResult.failure(SttError.TIMEOUT).isSuccess)
    }

    @Test
    fun `a cipher text needs a nonce and a tag`() {
        try {
            CipherText(ciphertext = byteArrayOf(1), nonce = ByteArray(0), tag = byteArrayOf(3))
            fail("expected a missing nonce to be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("nonce"))
        }
    }

    @Test
    fun `secrets never print their bytes`() {
        assertFalse(WrappedDek(byteArrayOf(7, 7), byteArrayOf(1)).toString().contains("7, 7"))
        assertFalse(DataEncryptionKey(ByteArray(32) { 9 }).toString().contains("9"))
        assertFalse(CipherText(byteArrayOf(1, 2), byteArrayOf(3), byteArrayOf(4)).toString().contains("1, 2"))
    }

    @Test
    fun `a data encryption key can be wiped`() {
        val key = DataEncryptionKey(ByteArray(4) { 1 })
        key.wipe()
        assertTrue(key.bytes.all { it == 0.toByte() })
    }

    @Test
    fun `a session never prints its token`() {
        val session = AuthSession(
            userId = "u-1",
            username = "driver",
            token = "secret-token-value",
            role = UserRole.ADMIN,
            scopes = setOf(TokenScope.TRANSCRIBE),
        )
        assertFalse(session.toString().contains("secret-token-value"))
        assertTrue(session.toString().contains("driver"))
        assertTrue(session.isAdmin)
    }

    @Test
    fun `a commit request refuses empty text`() {
        try {
            CommitRequest(text = "")
            fail("expected empty text to be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("empty text"))
        }
    }

    @Test
    fun `a release needs a version, a url, a digest and a signature`() {
        try {
            ReleaseInfo(version = "1.2.0", apkUrl = "https://x/apk", sha256 = "", signature = "sig")
            fail("expected a missing digest to be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("digest"))
        }
    }

    @Test
    fun `a sync report refuses negative counts`() {
        try {
            SyncReport(pushed = 1, failed = -1)
            fail("expected a negative failure count to be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("failed"))
        }
    }

    private fun aTranscription(durationMs: Long, createdAt: Long = 0L) =
        Transcription("t", "hi", TranscriptionSource.LOCAL, "small", durationMs, createdAt)
}
