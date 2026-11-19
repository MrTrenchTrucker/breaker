package dev.breaker.dictation.stt.ondevice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The user keyword verdict over real folders: each refusal reason, the hash path with a scripted digest, and the no-hash path. */
class UserKeywordCheckTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val tag = "stt-ondevice user keyword check"

    private fun assertBad(label: String, expected: UserKeywordReason, verdict: UserKeywordVerdict) {
        assertTrue(
            "$tag $label: expected a refusal with the reason $expected but got $verdict",
            verdict is UserKeywordVerdict.Bad && verdict.reason == expected,
        )
    }

    @Test
    fun `a folder that does not exist is absent`() {
        assertTrue("$tag the verdict must be Absent", UserKeywordCheck.verdict(File(tmp.root, "missing")) is UserKeywordVerdict.Absent)
    }

    @Test
    fun `a folder with no readable stamp is bad as stamp missing`() {
        val dir = tmp.newFolder()
        for (name in StampedFolderFixtures.NAMES) File(dir, name).writeText("x")
        assertBad("no stamp", UserKeywordReason.STAMP_MISSING, UserKeywordCheck.verdict(dir))
        File(dir, UnpackedStamp.FILE_NAME).writeText("not a stamp\n")
        assertBad("unreadable stamp", UserKeywordReason.STAMP_MISSING, UserKeywordCheck.verdict(dir))
    }

    @Test
    fun `a stamp that does not cover the four files is bad as files missing`() {
        val dir = StampedFolderFixtures.stampedDir(tmp.newFolder())
        File(dir, "other.txt").writeText("x")
        StampedFolderFixtures.writeStamp(dir, StampedFolderFixtures.NAMES + "other.txt")
        assertBad("stamp over other names", UserKeywordReason.FILES_MISSING, UserKeywordCheck.verdict(dir))
    }

    @Test
    fun `a missing or empty model file is bad as files missing`() {
        for (victim in StampedFolderFixtures.NAMES) {
            val missing = StampedFolderFixtures.stampedDir(tmp.newFolder())
            assertTrue("$tag fixture: the $victim file is removed", File(missing, victim).delete())
            assertBad("$victim missing", UserKeywordReason.FILES_MISSING, UserKeywordCheck.verdict(missing))
            val empty = StampedFolderFixtures.stampedDir(tmp.newFolder())
            File(empty, victim).writeText("")
            assertBad("$victim empty", UserKeywordReason.FILES_MISSING, UserKeywordCheck.verdict(empty))
        }
    }

    @Test
    fun `a file with another size is bad as stamp mismatch`() {
        val dir = StampedFolderFixtures.stampedDir(tmp.newFolder())
        File(dir, StampedFolderFixtures.DECODER).writeText("xx")
        assertBad("size changed", UserKeywordReason.STAMP_MISMATCH, UserKeywordCheck.verdict(dir))
    }

    @Test
    fun `a stamped folder whose files all match is ready with the stamp digest`() {
        val dir = StampedFolderFixtures.stampedDir(tmp.newFolder())
        val verdict = UserKeywordCheck.verdict(dir)
        assertTrue("$tag all files match must be Ready but got $verdict", verdict is UserKeywordVerdict.Ready)
        assertEquals("$tag the digest is the stamp archive digest", Fixtures.VALID_PIN, (verdict as UserKeywordVerdict.Ready).digest)
    }

    @Test
    fun `a changed modified time is bad as stamp mismatch with the same content`() {
        val dir = StampedFolderFixtures.stampedDir(tmp.newFolder())
        val decoder = File(dir, StampedFolderFixtures.DECODER)
        assertTrue("$tag fixture: the time moves", decoder.setLastModified(decoder.lastModified() - 60_000L))
        assertBad("time changed, bytes equal", UserKeywordReason.STAMP_MISMATCH, UserKeywordCheck.verdict(dir))
    }

    @Test
    fun `a changed modified time with other content is bad as stamp mismatch`() {
        val dir = StampedFolderFixtures.stampedDir(tmp.newFolder())
        val decoder = File(dir, StampedFolderFixtures.DECODER)
        assertTrue("$tag fixture: the time moves", decoder.setLastModified(decoder.lastModified() - 60_000L))
        decoder.writeText("y")
        assertTrue("$tag fixture: the time moves again", decoder.setLastModified(decoder.lastModified() - 60_000L))
        assertBad("time changed, bytes other", UserKeywordReason.STAMP_MISMATCH, UserKeywordCheck.verdict(dir))
    }

    @Test
    fun `a stamped folder whose tokens lack a phrase token is bad as tokens missing`() {
        val dir = StampedFolderFixtures.stampedDir(tmp.newFolder(), StampedFolderFixtures.tokenTextWithout(FixedPhrases.SEND.tokens.first()))
        assertBad("token lacking", UserKeywordReason.TOKENS_MISSING, UserKeywordCheck.verdict(dir))
    }

    @Test
    fun `a phrase list with a token the folder lacks is bad as tokens missing`() {
        val dir = StampedFolderFixtures.stampedDir(tmp.newFolder())
        val extra = FixedPhrases.all() + KeywordLine(listOf("EXTRA"))
        assertBad("phrase list asks more", UserKeywordReason.TOKENS_MISSING, UserKeywordCheck.verdict(dir, extra))
    }
}
