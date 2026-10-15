package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * Names the download no-space type and sentence. This is the one test file of the
 * change that refers to them, so it compiles only once the change is in place.
 */
class ModelNoSpaceSymbolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a low-space model fetch throws the no-space type which is an IOException`() {
        val staging = tmp.newFolder("model")
        val opener = FakeOpener(serve(reply(body = SizedStream(1000), contentLength = 1000)))
        val thrown = attemptModel(fetcherOf(opener, usableSpace = { 999L }), testEntry(), staging).thrown
        assertTrue("the exception is a NoSpaceException: $thrown", thrown is NoSpaceException)
        assertTrue("the exception is an IOException: $thrown", thrown is IOException)
        assertEquals("not enough space", thrown?.message)
    }

    @Test
    fun `a low-space checksum fetch throws the no-space type with the fixed message`() {
        val staging = tmp.newFolder("checksums")
        val opener = FakeOpener(serve(reply(body = SizedStream(40), contentLength = 40)))
        val thrown = attemptChecksums(fetcherOf(opener, usableSpace = { 0L }), staging).thrown
        assertTrue("the exception is a NoSpaceException: $thrown", thrown is NoSpaceException)
        assertEquals("not enough space", thrown?.message)
    }

    @Test
    fun `the download no-space sentence reads exactly as written`() {
        assertEquals(
            "There is not enough free space on the phone to download the model.",
            ModelMessages.DOWNLOAD_NO_SPACE,
        )
    }

    @Test
    fun `the download no-space sentence is plain ASCII text with no path and no exception name`() {
        val sentence = ModelMessages.DOWNLOAD_NO_SPACE
        assertTrue("not ASCII: $sentence", sentence.all { it.code in 0x20..0x7e })
        assertTrue("does not end with a full stop: $sentence", sentence.endsWith("."))
        for (forbidden in listOf("/", "\\", "Exception", "Error", "null")) {
            assertFalse("'$sentence' holds '$forbidden'", sentence.contains(forbidden))
        }
        assertTrue("does not say free space: $sentence", sentence.contains("free space"))
        assertTrue("does not say download: $sentence", sentence.contains("download"))
    }

    @Test
    fun `the download no-space sentence differs from every other sentence`() {
        val others = listOf(
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
            ModelMessages.UNPACK_REFUSED,
            ModelMessages.UNPACK_FAILED,
            ModelMessages.UNPACK_NO_SPACE,
        )
        assertEquals("every earlier sentence is listed", 17, others.size)
        val all = others + ModelMessages.DOWNLOAD_NO_SPACE
        assertEquals("two sentences have the same text: $all", all.size, all.toSet().size)
    }

    @Test
    fun `the download no-space sentence with the failed-delete note starts with the sentence`() {
        val sentence = ModelMessages.DOWNLOAD_NO_SPACE
        val left = sentence + ModelMessages.COULD_NOT_DELETE
        assertTrue(left, left.startsWith(sentence))
        assertTrue(left, left.endsWith(" The file could not be deleted."))
        assertEquals(sentence.length + " The file could not be deleted.".length, left.length)
    }
}
