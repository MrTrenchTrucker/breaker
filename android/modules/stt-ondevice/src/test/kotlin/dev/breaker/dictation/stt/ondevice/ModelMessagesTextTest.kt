package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentences a user may see about a model, pinned by their literal text.
 *
 * The flow tests compare a refusal's detail to the constant itself, which
 * cannot notice a reworded sentence. These tests hold the wording, so a change
 * to what the user reads is a visible, deliberate edit of this file too.
 */
class ModelMessagesTextTest {

    @Test
    fun `the sentences of a model that cannot be loaded read exactly as written`() {
        assertEquals("MODEL_UNKNOWN", "That model is not available.", ModelMessages.MODEL_UNKNOWN)
        assertEquals("MODEL_WRONG_FAMILY", "The on-device engine cannot run that model.", ModelMessages.MODEL_WRONG_FAMILY)
        assertEquals("MODEL_NOT_INSTALLED", "The model is not installed. Download it first.", ModelMessages.MODEL_NOT_INSTALLED)
        assertEquals("NOT_VERIFIED_YET", "The model could not be checked right now.", ModelMessages.NOT_VERIFIED_YET)
        assertEquals(
            "CHECK_FAILED_DELETED",
            "The model file failed its check and was deleted. Download it again.",
            ModelMessages.CHECK_FAILED_DELETED,
        )
        assertEquals(
            "CHECK_FAILED_NOT_DELETED",
            "The model file failed its check but could not be deleted.",
            ModelMessages.CHECK_FAILED_NOT_DELETED,
        )
        assertEquals(
            "ENGINE_COULD_NOT_START",
            "The on-device engine could not start with this model.",
            ModelMessages.ENGINE_COULD_NOT_START,
        )
    }

    @Test
    fun `the sentences of a model that cannot be installed read exactly as written`() {
        assertEquals("SAVE_FAILED", "The model could not be saved on the phone.", ModelMessages.SAVE_FAILED)
        assertEquals(
            "CHECKSUMS_DOWNLOAD_FAILED",
            "The checksum list could not be downloaded.",
            ModelMessages.CHECKSUMS_DOWNLOAD_FAILED,
        )
        assertEquals(
            "CHECKSUMS_UNREADABLE_DOWNLOADED",
            "The downloaded checksum list could not be read.",
            ModelMessages.CHECKSUMS_UNREADABLE_DOWNLOADED,
        )
        assertEquals("MODEL_DOWNLOAD_FAILED", "The model could not be downloaded.", ModelMessages.MODEL_DOWNLOAD_FAILED)
        assertEquals(
            "DOWNLOAD_FAILED_CHECK",
            "The downloaded file failed its check and was discarded. Try again.",
            ModelMessages.DOWNLOAD_FAILED_CHECK,
        )
        assertEquals(
            "DOWNLOAD_FAILED_CHECK_NOT_DELETED",
            "The downloaded file failed its check but could not be discarded.",
            ModelMessages.DOWNLOAD_FAILED_CHECK_NOT_DELETED,
        )
        assertEquals("COULD_NOT_DELETE", " The file could not be deleted.", ModelMessages.COULD_NOT_DELETE)
    }

    @Test
    fun `a file that was removed and a file that could not be removed get different sentences`() {
        assertNotEquals(ModelMessages.CHECK_FAILED_DELETED, ModelMessages.CHECK_FAILED_NOT_DELETED)
        assertNotEquals(ModelMessages.DOWNLOAD_FAILED_CHECK, ModelMessages.DOWNLOAD_FAILED_CHECK_NOT_DELETED)
        // The sentence for a removed file says so; the one for a file left behind says it could not be removed.
        assertTrue(ModelMessages.CHECK_FAILED_DELETED.contains("and was deleted"))
        assertFalse(ModelMessages.CHECK_FAILED_NOT_DELETED.contains("and was deleted"))
        assertTrue(ModelMessages.CHECK_FAILED_NOT_DELETED.contains("could not be deleted"))
        assertFalse(ModelMessages.CHECK_FAILED_DELETED.contains("could not be deleted"))
    }

    @Test
    fun `every sentence a user may see is different from every other`() {
        val all = listOf(
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
        assertEquals("every sentence must be listed", 14, all.size)
        assertEquals("two sentences have the same text: $all", all.size, all.toSet().size)
    }
}
