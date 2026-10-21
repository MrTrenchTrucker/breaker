package dev.breaker.dictation.stt.server

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val BOUNDARY = "0123456789abcdef"
private const val CRLF = "\r\n"

/** A short byte run that has no line feed in it, so line-ending counts stay exact. */
private val TINY_WAV: ByteArray =
    "RIFF".toByteArray(Charsets.US_ASCII) + byteArrayOf(4, 0, 0, 0) + "WAVE".toByteArray(Charsets.US_ASCII)

private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

/** The body written out by hand, part by part, so the code under test is checked against an independent copy. */
private fun expectedBody(wav: ByteArray, model: ByteArray, language: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(ascii("--$BOUNDARY$CRLF"))
    out.write(ascii("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"$CRLF"))
    out.write(ascii("Content-Type: audio/wav$CRLF"))
    out.write(ascii(CRLF))
    out.write(wav)
    out.write(ascii(CRLF))
    out.write(ascii("--$BOUNDARY$CRLF"))
    out.write(ascii("Content-Disposition: form-data; name=\"model\"$CRLF"))
    out.write(ascii(CRLF))
    out.write(model)
    out.write(ascii(CRLF))
    out.write(ascii("--$BOUNDARY$CRLF"))
    out.write(ascii("Content-Disposition: form-data; name=\"language\"$CRLF"))
    out.write(ascii(CRLF))
    out.write(language)
    out.write(ascii(CRLF))
    out.write(ascii("--$BOUNDARY--$CRLF"))
    return out.toByteArray()
}

private fun endsWithBytes(bytes: ByteArray, tail: ByteArray): Boolean =
    bytes.size >= tail.size && bytes.copyOfRange(bytes.size - tail.size, bytes.size).contentEquals(tail)

private fun containsBytes(bytes: ByteArray, needle: ByteArray): Boolean {
    for (start in 0..bytes.size - needle.size) {
        if (bytes.copyOfRange(start, start + needle.size).contentEquals(needle)) return true
    }
    return false
}

private fun assertRejects(message: String, action: () -> Unit) {
    try {
        action()
    } catch (e: IllegalArgumentException) {
        return
    }
    fail("$message: no IllegalArgumentException was thrown")
}

class MultipartBodyTest {

    @Test
    fun `a tiny wav becomes exactly the expected multipart bytes`() {
        val body = MultipartBodies.create(TINY_WAV, "small", "en", BOUNDARY)
        assertArrayEquals(
            "the body bytes differ from the hand-written body",
            expectedBody(TINY_WAV, ascii("small"), ascii("en")),
            body.bytes,
        )
    }

    @Test
    fun `the content type names multipart form data with the boundary`() {
        val body = MultipartBodies.create(TINY_WAV, "small", "en", BOUNDARY)
        assertEquals("multipart/form-data; boundary=$BOUNDARY", body.contentType)
    }

    @Test
    fun `the parts come in the order file, model, language`() {
        val text = String(MultipartBodies.create(TINY_WAV, "small", "en", BOUNDARY).bytes, Charsets.ISO_8859_1)
        val file = text.indexOf("name=\"file\"")
        val model = text.indexOf("name=\"model\"")
        val language = text.indexOf("name=\"language\"")
        assertTrue("the file part is present", file >= 0)
        assertTrue("the model part is present and follows the file part", file < model)
        assertTrue("the language part is present and follows the model part", model < language)
    }

    @Test
    fun `the file part carries the filename audio dot wav and the audio wav content type`() {
        val text = String(MultipartBodies.create(TINY_WAV, "small", "en", BOUNDARY).bytes, Charsets.ISO_8859_1)
        assertTrue(
            "the file part's disposition line with its filename",
            text.contains("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"$CRLF"),
        )
        assertTrue("the file part's content type", text.contains("Content-Type: audio/wav$CRLF$CRLF"))
    }

    @Test
    fun `every line feed in the body is part of a CRLF`() {
        val text = String(MultipartBodies.create(TINY_WAV, "small", "en", BOUNDARY).bytes, Charsets.ISO_8859_1)
        val lineFeeds = text.count { it == '\n' }
        val crlfs = text.split(CRLF).size - 1
        assertEquals("every line feed must be preceded by a carriage return", crlfs, lineFeeds)
        assertTrue("the body has at least one CRLF", crlfs > 0)
    }

    @Test
    fun `the body ends with the closing delimiter and its CRLF`() {
        val body = MultipartBodies.create(TINY_WAV, "small", "en", BOUNDARY).bytes
        assertTrue("the closing delimiter", endsWithBytes(body, ascii("--$BOUNDARY--$CRLF")))
    }

    @Test
    fun `a large wav is copied into the body byte for byte`() {
        val wav = ByteArray(200_000) { (it * 31 + it / 256).toByte() }
        val body = MultipartBodies.create(wav, "small", "en", BOUNDARY)
        assertArrayEquals(
            "the large wav must come back byte for byte",
            expectedBody(wav, ascii("small"), ascii("en")),
            body.bytes,
        )
    }

    @Test
    fun `the model and language text appear in the body as UTF-8`() {
        val model = "mod\u00e8le"
        val language = "\u65e5\u672c"
        val body = MultipartBodies.create(TINY_WAV, model, language, BOUNDARY).bytes
        assertArrayEquals(
            "the model and language bytes differ from their UTF-8 encoding",
            expectedBody(TINY_WAV, model.toByteArray(Charsets.UTF_8), language.toByteArray(Charsets.UTF_8)),
            body,
        )
        assertTrue(
            "the e-grave in the model is the two UTF-8 bytes C3 A8",
            containsBytes(body, byteArrayOf(0xC3.toByte(), 0xA8.toByte())),
        )
    }

    @Test
    fun `isSafeField accepts a plain value and a value of exactly 128 characters`() {
        assertTrue("a plain value", MultipartBodies.isSafeField("small"))
        assertTrue("a value of exactly 128 characters", MultipartBodies.isSafeField("a".repeat(128)))
    }

    @Test
    fun `isSafeField refuses a blank value`() {
        assertFalse("an empty value", MultipartBodies.isSafeField(""))
        assertFalse("a value of spaces only", MultipartBodies.isSafeField("   "))
    }

    @Test
    fun `isSafeField refuses a carriage return, a line feed or a NUL`() {
        assertFalse("a carriage return", MultipartBodies.isSafeField("a\rb"))
        assertFalse("a line feed", MultipartBodies.isSafeField("a\nb"))
        assertFalse("a NUL", MultipartBodies.isSafeField("a\u0000b"))
    }

    @Test
    fun `isSafeField refuses a double quote`() {
        assertFalse("a double quote", MultipartBodies.isSafeField("a\"b"))
    }

    @Test
    fun `isSafeField refuses a value over 128 characters`() {
        assertFalse("129 characters", MultipartBodies.isSafeField("a".repeat(129)))
    }

    @Test
    fun `create refuses an unsafe model and an unsafe language`() {
        assertRejects("a double quote in the model") {
            MultipartBodies.create(TINY_WAV, "a\"b", "en", BOUNDARY)
        }
        assertRejects("a line feed in the language") {
            MultipartBodies.create(TINY_WAV, "small", "e\nn", BOUNDARY)
        }
    }

    @Test
    fun `create accepts a boundary of 16 and a boundary of 70 lowercase hex characters`() {
        val short = MultipartBodies.create(TINY_WAV, "small", "en", "0".repeat(16))
        val long = MultipartBodies.create(TINY_WAV, "small", "en", "f".repeat(70))
        assertTrue("the 16-character boundary gives a body", short.bytes.isNotEmpty())
        assertTrue("the 70-character boundary gives a body", long.bytes.isNotEmpty())
    }

    @Test
    fun `create refuses a boundary shorter than 16 or longer than 70 characters`() {
        assertRejects("a 15-character boundary") {
            MultipartBodies.create(TINY_WAV, "small", "en", "0".repeat(15))
        }
        assertRejects("a 71-character boundary") {
            MultipartBodies.create(TINY_WAV, "small", "en", "0".repeat(71))
        }
    }

    @Test
    fun `create refuses a boundary with a character outside lowercase hex`() {
        assertRejects("an upper-case letter in the boundary") {
            MultipartBodies.create(TINY_WAV, "small", "en", "0".repeat(15) + "A")
        }
        assertRejects("a non-hex letter in the boundary") {
            MultipartBodies.create(TINY_WAV, "small", "en", "0".repeat(15) + "g")
        }
        assertRejects("a dash in the boundary") {
            MultipartBodies.create(TINY_WAV, "small", "en", "0".repeat(15) + "-")
        }
    }
}
