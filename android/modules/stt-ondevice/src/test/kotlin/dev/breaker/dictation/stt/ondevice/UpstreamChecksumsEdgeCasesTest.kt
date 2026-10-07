package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Edge cases of [UpstreamChecksums]: whitespace on either side of a line, the message for a
 * line with too many fields, a hex field longer than 64 characters, a
 * [UpstreamChecksums.Checksums] built directly with upper-case text, and names
 * that differ only in letter case.
 */
class UpstreamChecksumsEdgeCasesTest {

    private val pin = Fixtures.VALID_PIN
    private val otherPin = Fixtures.OTHER_PIN

    /** Parses [text] and returns the refusal message; fails the test when nothing is refused. */
    private fun refusalMessage(text: String): String {
        try {
            UpstreamChecksums.parse(text)
        } catch (e: UpstreamChecksums.ChecksumFormatException) {
            return e.message ?: ""
        }
        fail("expected the checksum text to be refused: '$text'")
        return ""
    }

    /** Parses [text]; a refusal becomes a failed assertion that carries [why]. */
    private fun parseAccepted(text: String, why: String): UpstreamChecksums.Checksums =
        try {
            UpstreamChecksums.parse(text)
        } catch (e: UpstreamChecksums.ChecksumFormatException) {
            throw AssertionError("$why, but it was refused: ${e.message}")
        }

    // --- whitespace beside lines ---

    @Test
    fun `parse skips a line made only of spaces and tabs`() {
        val text = "model.tar.bz2\t$pin\n  \t \nother.bin\t$otherPin\n \t \r\n"
        val checksums = parseAccepted(text, "a line of only whitespace must be skipped")
        assertEquals(setOf("model.tar.bz2", "other.bin"), checksums.names)
        assertEquals(setOf(pin, otherPin), checksums.digests)
    }

    @Test
    fun `parse skips a comment line that starts with whitespace`() {
        val text = "   # note with spaces\n\t# note with a tab\nmodel.tar.bz2\t$pin"
        val checksums = parseAccepted(text, "an indented comment line must be skipped")
        assertEquals(setOf("model.tar.bz2"), checksums.names)
    }

    @Test
    fun `parse reads a data line padded with whitespace and a carriage return`() {
        val text = "  model.tar.bz2 \t $pin  \r\nother.bin\t$otherPin\r\n"
        val checksums = parseAccepted(text, "a padded data line must be read")
        assertEquals(pin, checksums["model.tar.bz2"])
        assertEquals(otherPin, checksums["other.bin"])
    }

    // --- a line with three fields ---

    @Test
    fun `parse refuses a line with three fields and says which line and how many`() {
        // Control: the same line without the extra field is accepted.
        val control = parseAccepted("model.tar.bz2\t$pin", "a two-field line must be read")
        assertEquals(pin, control["model.tar.bz2"])

        val message = refusalMessage("# header\nmodel.tar.bz2 $pin extra")
        assertEquals("checksum.txt:2: expected exactly two whitespace-separated fields, found 3", message)
    }

    @Test
    fun `parse refuses a three-field line whatever the order of its fields`() {
        val lines = listOf("$pin model.tar.bz2 extra", "extra $pin model.tar.bz2", "model.tar.bz2 extra $pin")
        for (line in lines) {
            assertEquals(
                "checksum.txt:1: expected exactly two whitespace-separated fields, found 3",
                refusalMessage(line),
            )
        }
    }

    // --- a hex field longer than 64 characters ---

    @Test
    fun `parse refuses a hex field longer than 64 characters`() {
        // Control: exactly 64 characters is accepted, in either field order.
        assertEquals(pin, parseAccepted("model.tar.bz2\t$pin", "64 hex characters must be accepted")["model.tar.bz2"])
        assertEquals(pin, parseAccepted("$pin\tmodel.tar.bz2", "64 hex characters must be accepted")["model.tar.bz2"])

        val tooLong = listOf(
            "model.tar.bz2\t${pin}a",
            "${pin}a\tmodel.tar.bz2",
            "model.tar.bz2\t$pin$otherPin",
        )
        for (line in tooLong) {
            assertEquals(
                "checksum.txt:1: neither field is a 64-character hex digest",
                refusalMessage(line),
            )
        }
    }

    // --- a Checksums built directly ---

    @Test
    fun `Checksums built directly lowercases an upper-case digest`() {
        val direct = UpstreamChecksums.Checksums(mapOf("model.tar.bz2" to pin.uppercase()))
        assertEquals(setOf(pin), direct.digests)
        assertTrue(direct.containsDigest(pin))
        assertTrue(direct.containsDigest(pin.uppercase()))

        val listed = UpstreamChecksums.Checksums(
            mapOf("model.tar.bz2" to otherPin),
            setOf(pin.uppercase(), otherPin.uppercase()),
        )
        assertEquals(setOf(pin, otherPin), listed.digests)
        assertTrue(listed.containsDigest(pin))

        // Control: lower-case input gives the same sets.
        val lower = UpstreamChecksums.Checksums(mapOf("model.tar.bz2" to pin))
        assertEquals(setOf(pin), lower.digests)
        assertFalse(lower.containsDigest(otherPin))
    }

    // --- names that differ only in letter case ---

    @Test
    fun `get looks a name up exactly as written`() {
        val checksums = parseAccepted(
            "A.bin\t$pin\na.bin\t$otherPin\nModel.Tar.BZ2\t${Fixtures.ABC_SHA256}",
            "names that differ only in case must be read",
        )
        assertEquals(pin, checksums["A.bin"])
        assertEquals(otherPin, checksums["a.bin"])
        assertEquals(Fixtures.ABC_SHA256, checksums["Model.Tar.BZ2"])
        assertNull(checksums["model.tar.bz2"])
        assertEquals(setOf("A.bin", "a.bin", "Model.Tar.BZ2"), checksums.names)
        assertTrue("Model.Tar.BZ2" in checksums)
        assertFalse("model.tar.bz2" in checksums)
    }

    @Test
    fun `get keeps the case of a name in a directly built Checksums`() {
        val checksums = Fixtures.checksumsOf("Upper.bin" to pin)
        assertEquals(pin, checksums["Upper.bin"])
        assertNull(checksums["upper.bin"])
    }
}
