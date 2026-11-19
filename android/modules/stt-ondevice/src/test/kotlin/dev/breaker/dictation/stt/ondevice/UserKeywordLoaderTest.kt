package dev.breaker.dictation.stt.ondevice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The user keyword loader over a real store folder: a ready user model, each ignored reason, and a folder removed later. */
class UserKeywordLoaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val tag = "stt-ondevice user keyword loader"

    private fun assertIgnored(label: String, expected: UserKeywordReason, load: UserKeywordLoad) {
        assertTrue(
            "$tag $label: expected Ignored with the reason $expected but got $load",
            load is UserKeywordLoad.Ignored && load.reason == expected,
        )
    }

    @Test
    fun `no user folder loads as absent`() {
        val store = UserKeywordStore(tmp.root)
        assertTrue("$tag no folder must be Absent", UserKeywordLoader(store).load() is UserKeywordLoad.Absent)
    }

    @Test
    fun `a stamped user folder loads as a user model with the keyword id and the stamp digest`() {
        val store = UserKeywordStore(tmp.root)
        StampedFolderFixtures.stampedDir(store.directory())
        val load = UserKeywordLoader(store).load()
        assertTrue("$tag expected Ready but got $load", load is UserKeywordLoad.Ready)
        val model = (load as UserKeywordLoad.Ready).model
        assertEquals("$tag the id is the keyword id", KEYWORD_MODEL_ID, model.modelId)
        assertEquals("$tag the folder is the store folder", store.directory(), model.directory)
        assertEquals("$tag the source is the user source", KeywordSource.USER, model.source)
        assertEquals("$tag the digest is the stamp digest", Fixtures.VALID_PIN, model.digest)
    }

    @Test
    fun `a folder without a stamp is ignored as stamp missing`() {
        val store = UserKeywordStore(tmp.root)
        store.directory().mkdirs()
        for (name in StampedFolderFixtures.NAMES) File(store.directory(), name).writeText("x")
        assertIgnored("no stamp", UserKeywordReason.STAMP_MISSING, UserKeywordLoader(store).load())
    }

    @Test
    fun `a stamp over a missing file is ignored as files missing`() {
        val store = UserKeywordStore(tmp.root)
        StampedFolderFixtures.stampedDir(store.directory())
        assertTrue("$tag fixture: the tokens file is removed", File(store.directory(), StampedFolderFixtures.TOKENS).delete())
        assertIgnored("tokens file deleted", UserKeywordReason.FILES_MISSING, UserKeywordLoader(store).load())
    }

    @Test
    fun `a file that changed size is ignored as stamp mismatch`() {
        val store = UserKeywordStore(tmp.root)
        StampedFolderFixtures.stampedDir(store.directory())
        File(store.directory(), StampedFolderFixtures.DECODER).writeText("xx")
        assertIgnored("size changed", UserKeywordReason.STAMP_MISMATCH, UserKeywordLoader(store).load())
    }

    @Test
    fun `a changed modified time is ignored as stamp mismatch`() {
        val store = UserKeywordStore(tmp.root)
        StampedFolderFixtures.stampedDir(store.directory())
        val decoder = File(store.directory(), StampedFolderFixtures.DECODER)
        assertTrue("$tag fixture: the time moves", decoder.setLastModified(decoder.lastModified() - 60_000L))
        assertIgnored("time changed", UserKeywordReason.STAMP_MISMATCH, UserKeywordLoader(store).load())
    }

    @Test
    fun `a folder removed after a ready load is absent on the next load`() {
        val store = UserKeywordStore(tmp.root)
        StampedFolderFixtures.stampedDir(store.directory())
        val loader = UserKeywordLoader(store)
        assertTrue("$tag the first load is Ready", loader.load() is UserKeywordLoad.Ready)
        assertTrue("$tag fixture: the folder is removed", store.directory().deleteRecursively())
        assertTrue("$tag the next load must be Absent", loader.load() is UserKeywordLoad.Absent)
    }

    @Test
    fun `a folder whose tokens lack a phrase token is ignored as tokens missing`() {
        val store = UserKeywordStore(tmp.root)
        StampedFolderFixtures.stampedDir(store.directory(), StampedFolderFixtures.tokenTextWithout(FixedPhrases.SEND.tokens.first()))
        assertIgnored("token lacking", UserKeywordReason.TOKENS_MISSING, UserKeywordLoader(store).load())
    }

    @Test
    fun `a phrase list the folder cannot satisfy ignores the user model as tokens missing`() {
        val store = UserKeywordStore(tmp.root)
        StampedFolderFixtures.stampedDir(store.directory())
        val extra = FixedPhrases.all() + KeywordLine(listOf("EXTRA"))
        assertIgnored("phrase list asks more", UserKeywordReason.TOKENS_MISSING, UserKeywordLoader(store, extra).load())
    }
}
