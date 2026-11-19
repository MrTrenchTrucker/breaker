package dev.breaker.dictation.stt.ondevice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chooser over scripted loaders: which model it answers, when the default is asked, and the typed ignored reason. */
class KeywordChooserTest {

    private val tag = "stt-ondevice keyword chooser"
    private val userModel = KeywordModel(KEYWORD_MODEL_ID, File("user-directory"), "user-digest", KeywordSource.USER)
    private val defaultModel = KeywordModel(KEYWORD_MODEL_ID, File("default-directory"), "default-digest")
    private val defaultReady: KeywordLoad = KeywordLoad.Ready(defaultModel)
    private val defaultRefused: KeywordLoad = KeywordLoad.Refused(ModelLoader.Refusal.NOT_INSTALLED, "refused")

    /** A chooser whose user lambda answers [user] and whose default lambda answers [default]; [calls] counts both. */
    private fun chooser(user: UserKeywordLoad, default: KeywordLoad, calls: IntArray = IntArray(2)): KeywordChooser =
        KeywordChooser(
            {
                calls[0]++
                user
            },
            {
                calls[1]++
                default
            },
        )

    @Test
    fun `a ready user model is chosen and the default is not asked`() {
        val calls = IntArray(2)
        val choice = chooser(UserKeywordLoad.Ready(userModel), defaultRefused, calls).choose()
        assertTrue("$tag a ready user model must be a Ready load but got ${choice.load}", choice.load is KeywordLoad.Ready)
        assertSame("$tag the chosen model is the user model", userModel, (choice.load as KeywordLoad.Ready).model)
        assertNull("$tag no reason is given when the user model is used", choice.userIgnored)
        assertEquals("$tag the default lambda is not asked", 0, calls[1])
    }

    @Test
    fun `an absent user model gives the default and no reason`() {
        val calls = IntArray(2)
        val choice = chooser(UserKeywordLoad.Absent, defaultReady, calls).choose()
        assertSame("$tag the default load is the answer", defaultReady, choice.load)
        assertNull("$tag an absent user model gives no reason", choice.userIgnored)
        assertEquals("$tag the default lambda is asked once", 1, calls[1])
    }

    @Test
    fun `an ignored user model gives the default with its reason and never the user model`() {
        for (reason in UserKeywordReason.values()) {
            val choice = chooser(UserKeywordLoad.Ignored(reason), defaultReady).choose()
            assertSame("$tag $reason: the default load is the answer", defaultReady, choice.load)
            assertEquals("$tag $reason: the typed reason is reported", reason, choice.userIgnored)
            assertTrue("$tag $reason: the user model is never half-used", !(choice.load is KeywordLoad.Ready && (choice.load as KeywordLoad.Ready).model === userModel))
        }
    }

    @Test
    fun `an absent user model and a refused default give a refusal with no stream`() {
        val choice = chooser(UserKeywordLoad.Absent, defaultRefused).choose()
        assertSame("$tag the refused default is the answer", defaultRefused, choice.load)
        assertNull("$tag no reason is given for an absent user model", choice.userIgnored)
        assertTrue("$tag a refused default is not a Ready load", choice.load is KeywordLoad.Refused)
    }

    @Test
    fun `a ready user model wins even when the default is refused`() {
        val choice = chooser(UserKeywordLoad.Ready(userModel), defaultRefused).choose()
        assertSame("$tag the user model is chosen over a refused default", userModel, (choice.load as KeywordLoad.Ready).model)
        assertNull("$tag no reason is given when the user model is used", choice.userIgnored)
    }

    @Test
    fun `an ignored user model and a refused default give the refusal with the reason`() {
        val choice = chooser(UserKeywordLoad.Ignored(UserKeywordReason.STAMP_MISMATCH), defaultRefused).choose()
        assertSame("$tag the refused default is the answer", defaultRefused, choice.load)
        assertEquals("$tag the typed reason is still reported", UserKeywordReason.STAMP_MISMATCH, choice.userIgnored)
    }
}
