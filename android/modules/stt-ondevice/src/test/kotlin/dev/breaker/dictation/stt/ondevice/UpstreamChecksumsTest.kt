package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpstreamChecksumsTest {

    private val pin = Fixtures.VALID_PIN
    private val otherPin = Fixtures.OTHER_PIN

    // --- parse: accepted formats ---

    @Test
    fun `parse accepts name TAB digest format`() {
        val text = "model.tar.bz2\t$pin"
        val checksums = UpstreamChecksums.parse(text)
        assertEquals(pin, checksums["model.tar.bz2"])
    }

    @Test
    fun `parse accepts digest space name format`() {
        val text = "$pin  model.tar.bz2"
        val checksums = UpstreamChecksums.parse(text)
        assertEquals(pin, checksums["model.tar.bz2"])
    }

    @Test
    fun `parse accepts any whitespace separator`() {
        val text = "model.tar.bz2 $pin"
        val checksums = UpstreamChecksums.parse(text)
        assertEquals(pin, checksums["model.tar.bz2"])
    }

    @Test
    fun `parse skips blank lines`() {
        val text = "\n\nmodel.tar.bz2\t$pin\n\n"
        val checksums = UpstreamChecksums.parse(text)
        assertEquals(pin, checksums["model.tar.bz2"])
    }

    @Test
    fun `parse skips comment lines`() {
        val text = "# comment\nmodel.tar.bz2\t$pin\n# another"
        val checksums = UpstreamChecksums.parse(text)
        assertEquals(pin, checksums["model.tar.bz2"])
    }

    @Test
    fun `parse accepts duplicate names`() {
        val text = "model.tar.bz2\t$pin\nmodel.tar.bz2\t$otherPin"
        val checksums = UpstreamChecksums.parse(text)
        assertEquals(otherPin, checksums["model.tar.bz2"])
    }

    @Test
    fun `parse takes the first field as the digest when both fields are hex and keeps every line`() {
        val firstName = Fixtures.ABC_SHA256
        val secondName = Fixtures.EMPTY_SHA256
        val text = "$pin\t$firstName\n$otherPin\t$secondName"
        val checksums = UpstreamChecksums.parse(text)
        assertEquals(setOf(pin, otherPin), checksums.digests)
        assertEquals(setOf(firstName, secondName), checksums.names)
        assertEquals(pin, checksums[firstName])
        assertEquals(otherPin, checksums[secondName])
        assertFalse(checksums.containsDigest(firstName))
    }

    @Test
    fun `parse accepts path-shaped names`() {
        val text = "../evil.tar.bz2\t$pin"
        val checksums = UpstreamChecksums.parse(text)
        assertEquals(pin, checksums["../evil.tar.bz2"])
    }

    // --- parse: refusals ---

    @Test
    fun `parse refuses empty file`() {
        try {
            UpstreamChecksums.parse("")
            fail("Expected ChecksumFormatException")
        } catch (e: UpstreamChecksums.ChecksumFormatException) {
            assertTrue(e.message!!.contains("checksum.txt"))
        }
    }

    @Test
    fun `parse refuses file with only comments`() {
        try {
            UpstreamChecksums.parse("# comment\n# another")
            fail("Expected ChecksumFormatException")
        } catch (e: UpstreamChecksums.ChecksumFormatException) {
            assertTrue(e.message!!.contains("checksum.txt"))
        }
    }

    @Test
    fun `parse refuses line with fewer than two fields`() {
        val text = "onlyonefield"
        try {
            UpstreamChecksums.parse(text)
            fail("Expected ChecksumFormatException")
        } catch (e: UpstreamChecksums.ChecksumFormatException) {
            assertTrue(e.message!!.contains("checksum.txt:1"))
        }
    }

    @Test
    fun `parse refuses line with more than two fields`() {
        val text = "field1 field2 field3"
        try {
            UpstreamChecksums.parse(text)
            fail("Expected ChecksumFormatException")
        } catch (e: UpstreamChecksums.ChecksumFormatException) {
            assertTrue(e.message!!.contains("checksum.txt:1"))
        }
    }

    @Test
    fun `parse refuses line where neither field is 64 hex chars`() {
        val text = "nothex also not hex"
        try {
            UpstreamChecksums.parse(text)
            fail("Expected ChecksumFormatException")
        } catch (e: UpstreamChecksums.ChecksumFormatException) {
            assertTrue(e.message!!.contains("checksum.txt:1"))
        }
    }

    @Test
    fun `parse refuses line with 63-char hex field`() {
        val short = pin.dropLast(1)
        val text = "model.tar.bz2\t$short"
        try {
            UpstreamChecksums.parse(text)
            fail("Expected ChecksumFormatException")
        } catch (e: UpstreamChecksums.ChecksumFormatException) {
            assertTrue(e.message!!.contains("checksum.txt:1"))
        }
    }

    @Test
    fun `parse uses custom file name in error messages`() {
        try {
            UpstreamChecksums.parse("bad", "custom.txt")
            fail("Expected ChecksumFormatException")
        } catch (e: UpstreamChecksums.ChecksumFormatException) {
            assertTrue(e.message!!.contains("custom.txt:1"))
        }
    }

    // --- parse: every digest of a repeated name stays listed ---

    @Test
    fun `containsDigest finds a digest that a later line for the same name replaced`() {
        val single = UpstreamChecksums.parse("model.tar.bz2\t$pin")
        assertEquals(1, single.digests.size)
        assertFalse(single.containsDigest(otherPin))

        val checksums = UpstreamChecksums.parse("model.tar.bz2\t$pin\nmodel.tar.bz2\t$otherPin")
        assertTrue(checksums.containsDigest(pin))
        assertTrue(checksums.containsDigest(otherPin))
        assertEquals(otherPin, checksums["model.tar.bz2"])
        assertEquals(setOf(pin, otherPin), checksums.digests)
    }

    @Test
    fun `digests holds every digest of a name listed three times`() {
        val third = Fixtures.ABC_SHA256
        val text = "model.tar.bz2\t$pin\nmodel.tar.bz2\t$otherPin\nmodel.tar.bz2\t$third"
        val checksums = UpstreamChecksums.parse(text)
        assertEquals(3, checksums.digests.size)
        assertEquals(setOf(pin, otherPin, third), checksums.digests)
        assertEquals(third, checksums["model.tar.bz2"])
    }

    // --- Checksums membership ---

    @Test
    fun `containsDigest returns true for matching digest`() {
        val checksums = Fixtures.checksumsOf("model" to pin)
        assertTrue(checksums.containsDigest(pin))
    }

    @Test
    fun `containsDigest is case-insensitive`() {
        val checksums = Fixtures.checksumsOf("model" to pin)
        assertTrue(checksums.containsDigest(pin.uppercase()))
    }

    @Test
    fun `containsDigest returns false for unknown digest`() {
        val checksums = Fixtures.checksumsOf("model" to pin)
        assertFalse(checksums.containsDigest(otherPin))
    }

    @Test
    fun `get returns digest for known name`() {
        val checksums = Fixtures.checksumsOf("model" to pin)
        assertEquals(pin, checksums["model"])
    }

    @Test
    fun `get returns null for unknown name`() {
        val checksums = Fixtures.checksumsOf("model" to pin)
        assertEquals(null, checksums["unknown"])
    }

    @Test
    fun `contains returns true for known name`() {
        val checksums = Fixtures.checksumsOf("model" to pin)
        assertTrue("model" in checksums)
    }

    @Test
    fun `contains returns false for unknown name`() {
        val checksums = Fixtures.checksumsOf("model" to pin)
        assertFalse("unknown" in checksums)
    }

    @Test
    fun `digests returns all distinct digests`() {
        val checksums = Fixtures.checksumsOf("a" to pin, "b" to otherPin)
        assertEquals(setOf(pin, otherPin), checksums.digests)
    }

    @Test
    fun `names returns all recorded names`() {
        val checksums = Fixtures.checksumsOf("a" to pin, "b" to otherPin)
        assertEquals(setOf("a", "b"), checksums.names)
    }

    @Test
    fun `digests lowercases stored digests`() {
        val checksums = Fixtures.checksumsOf("model" to pin.uppercase())
        assertTrue(checksums.containsDigest(pin))
    }

    @Test
    fun `parse accepts uppercase digest`() {
        val text = "model.tar.bz2\t${pin.uppercase()}"
        val checksums = UpstreamChecksums.parse(text)
        assertEquals(pin, checksums["model.tar.bz2"])
    }
}
