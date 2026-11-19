package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.PhraseKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** An archive the extractor refuses is an EXTRACT_REFUSED install: the previous model stays byte for byte. */
class UserKeywordStoreRefusalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val good = UserKeywordFixtures.archive("first")
    private val goodDigest = Fixtures.independentSha256(good)
    private val limits = UserKeywordFixtures.keywordLimits()

    /** Installs the good model, then installs [bad] and demands an EXTRACT_REFUSED refusal that keeps the previous model. */
    private fun refusedKeepingPrevious(label: String, bad: ByteArray) {
        val root = tmp.newFolder()
        val store = UserKeywordFixtures.store(root)
        assertEquals("$label: setup, the good model installs", UserInstall.Installed(goodDigest, false), store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, good)))
        val before = UserKeywordFixtures.snapshot(store.directory())
        val result = store.install(UserKeywordFixtures.trained(PhraseKind.SEND, bad))
        assertEquals("$label: refused for the archive", UserInstall.Refused(UserKeywordReason.EXTRACT_REFUSED), result)
        assertEquals("$label: the previous model is byte for byte the same", before, UserKeywordFixtures.snapshot(store.directory()))
        assertEquals("$label: only the installed folder is under the root", listOf(UserKeywordStore.CURRENT), root.list()?.toList())
    }

    @Test
    fun `a path that climbs out of the top folder is refused and the previous model stays`() {
        val bad = UserKeywordFixtures.archive("second", listOf(TarFixtures.file("top/../evil.bin", ByteArray(4))))
        refusedKeepingPrevious("parent segment", bad)
    }

    @Test
    fun `an absolute entry name is refused and the previous model stays`() {
        val bad = UserKeywordFixtures.archive("second", listOf(TarFixtures.file("/top/evil.bin", ByteArray(4))))
        refusedKeepingPrevious("absolute path", bad)
    }

    @Test
    fun `an entry over the per-entry limit is refused and the previous model stays`() {
        val oversize = TarEntrySpec(name = "top/extra.bin", declaredSize = limits.maxEntryBytes + 1)
        refusedKeepingPrevious("entry too large", UserKeywordFixtures.archive("second", listOf(oversize)))
    }

    @Test
    fun `more entries than the keyword profile allows are refused and the previous model stays`() {
        val many = (1..33).map { TarFixtures.file("top/extra$it.bin", ByteArray(1)) }
        refusedKeepingPrevious("too many entries", UserKeywordFixtures.archive("second", many))
    }

    @Test
    fun `written bytes over the keyword written limit are refused and the previous model stays`() {
        val bad = TarFixtures.zerosArchive(limits.maxWrittenBytes + 1, "top/tokens.txt")
        refusedKeepingPrevious("written bytes", bad)
    }

    @Test
    fun `a token table that lacks a phrase token is refused and the installed model is kept`() {
        val root = tmp.newFolder()
        val store = UserKeywordFixtures.store(root)
        assertEquals("setup: the good model installs", UserInstall.Installed(goodDigest, false), store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, good)))
        val before = UserKeywordFixtures.snapshot(store.directory())
        val lacking = UserKeywordFixtures.archiveWithTokens("second", "${KeywordsTestTokens.MARK}B 11\n".toByteArray(Charsets.UTF_8))
        assertEquals("the token table lacks phrase tokens", UserInstall.Refused(UserKeywordReason.TOKENS_MISSING), store.install(UserKeywordFixtures.trained(PhraseKind.SEND, lacking)))
        assertEquals("the installed model is byte for byte the same", before, UserKeywordFixtures.snapshot(store.directory()))
        assertTrue("the installed model is still ready", UserKeywordCheck.verdict(store.directory()) is UserKeywordVerdict.Ready)
        assertEquals("only the installed folder is under the root", listOf(UserKeywordStore.CURRENT), root.list()?.toList())
    }
}
