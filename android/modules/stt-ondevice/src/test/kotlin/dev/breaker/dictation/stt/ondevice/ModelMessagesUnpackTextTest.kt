package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three sentences a user may read when a model cannot be unpacked, pinned by
 * their literal text and kept apart from every sentence that existed before.
 *
 * The earlier text test lists the existing sentences by name and counts them, so
 * a new constant does not disturb it; this file holds the new ones.
 */
class ModelMessagesUnpackTextTest {

    private val unpack = listOf(
        ModelMessages.UNPACK_REFUSED,
        ModelMessages.UNPACK_FAILED,
        ModelMessages.UNPACK_NO_SPACE,
    )

    @Test
    fun `the unpack sentences read exactly as written`() {
        assertEquals(
            "UNPACK_REFUSED",
            "The model file could not be unpacked safely. Download it again.",
            ModelMessages.UNPACK_REFUSED,
        )
        assertEquals("UNPACK_FAILED", "The model could not be unpacked on the phone.", ModelMessages.UNPACK_FAILED)
        assertEquals(
            "UNPACK_NO_SPACE",
            "There is not enough free space to unpack the model.",
            ModelMessages.UNPACK_NO_SPACE,
        )
    }

    @Test
    fun `an unpack sentence is different from every sentence that existed before`() {
        val before = listOf(
            ModelMessages.MODEL_UNKNOWN,
            ModelMessages.MODEL_WRONG_FAMILY,
            ModelMessages.MODEL_NOT_INSTALLED,
            ModelMessages.NOT_VERIFIED_YET,
            ModelMessages.CHECK_FAILED_DELETED,
            ModelMessages.CHECK_FAILED_NOT_DELETED,
            ModelMessages.ENGINE_COULD_NOT_START,
            ModelMessages.SAVE_FAILED,
            ModelMessages.CHECKSUMS_DOWNLOAD_FAILED,
            ModelMessages.CHECKSUMS_UNREADABLE_DOWNLOADED,
            ModelMessages.MODEL_DOWNLOAD_FAILED,
            ModelMessages.DOWNLOAD_FAILED_CHECK,
            ModelMessages.DOWNLOAD_FAILED_CHECK_NOT_DELETED,
            ModelMessages.COULD_NOT_DELETE,
        )
        assertEquals("every earlier sentence must be listed", 14, before.size)
        assertEquals("every sentence must be listed", 3, unpack.size)
        val all = before + unpack
        assertEquals("two sentences have the same text: $all", all.size, all.toSet().size)
    }

    @Test
    fun `an unpack sentence is plain text with no path and no exception name`() {
        var scanned = 0
        for (sentence in unpack + unpack.map { it + ModelMessages.COULD_NOT_DELETE }) {
            assertTrue("not ASCII: $sentence", sentence.all { it.code in 0x20..0x7e })
            assertTrue("does not end with a full stop: $sentence", sentence.endsWith("."))
            for (forbidden in listOf("/", "\\", "Exception", "Error", "null")) {
                assertFalse("'$sentence' holds '$forbidden'", sentence.contains(forbidden))
            }
            scanned++
        }
        assertEquals(6, scanned)
    }

    @Test
    fun `the refused sentence asks for a new download and the other two do not`() {
        assertTrue(ModelMessages.UNPACK_REFUSED.endsWith("Download it again."))
        assertFalse(ModelMessages.UNPACK_FAILED.contains("Download"))
        assertFalse(ModelMessages.UNPACK_NO_SPACE.contains("Download"))
        assertTrue(ModelMessages.UNPACK_NO_SPACE.contains("free space"))
    }

    @Test
    fun `a sentence with the note about a failed delete is the sentence plus the shared note`() {
        for (sentence in unpack) {
            val left = sentence + ModelMessages.COULD_NOT_DELETE
            assertTrue(left, left.startsWith(sentence))
            assertTrue(left, left.endsWith(" The file could not be deleted."))
            assertEquals(sentence.length + " The file could not be deleted.".length, left.length)
        }
    }
}
