package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.PhraseKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The install of a verified keyword model: the good path, the refusals before any write, and the equal-digest rules. */
class UserKeywordStoreInstallTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val good = UserKeywordFixtures.archive("first")
    private val goodDigest = Fixtures.independentSha256(good)

    @Test
    fun `a good keyword archive installs and reports its digest`() {
        val store = UserKeywordFixtures.store(tmp.newFolder())
        val result = store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, good))
        assertEquals(UserInstall.Installed(goodDigest, false), result)
        assertEquals(goodDigest, store.installedDigest())
        assertTrue("the verdict of the installed folder is ready", UserKeywordCheck.verdict(store.directory()) is UserKeywordVerdict.Ready)
    }

    @Test
    fun `the installed folder holds the four keyword files and the stamp`() {
        val store = UserKeywordFixtures.store(tmp.newFolder())
        store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, good))
        val names = store.directory().list()?.sorted()
        assertEquals((UserKeywordFixtures.FILES + UnpackedStamp.FILE_NAME).sorted(), names)
        assertArrayEquals(
            "the tokens file holds the archive's bytes",
            UserKeywordFixtures.tokensBytes(),
            File(store.directory(), "tokens.txt").readBytes(),
        )
    }

    @Test
    fun `a tampered archive is refused as a digest mismatch and nothing is written`() {
        val root = tmp.newFolder()
        val store = UserKeywordFixtures.store(root)
        val flipped = good.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x01).toByte() }
        val result = store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, flipped, goodDigest))
        assertEquals(UserInstall.Refused(UserKeywordReason.DIGEST_MISMATCH), result)
        assertTrue("the root holds nothing", root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `an archive exactly at the size cap is not refused for its size`() {
        val store = UserKeywordFixtures.store(tmp.newFolder(), sizeCap = good.size.toLong())
        val result = store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, good))
        assertEquals(UserInstall.Installed(goodDigest, false), result)
    }

    @Test
    fun `an archive one byte over the size cap is refused as too large and nothing is written`() {
        val root = tmp.newFolder()
        val store = UserKeywordFixtures.store(root, sizeCap = good.size.toLong() - 1)
        val result = store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, good))
        assertEquals(UserInstall.Refused(UserKeywordReason.TOO_LARGE), result)
        assertTrue("the root holds nothing", root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `the kind of the trained model changes nothing about an equal install`() {
        val store = UserKeywordFixtures.store(tmp.newFolder())
        assertEquals(UserInstall.Installed(goodDigest, false), store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, good)))
        assertEquals(UserInstall.Installed(goodDigest, true), store.install(UserKeywordFixtures.trained(PhraseKind.SEND, good)))
    }

    @Test
    fun `an equal digest with a checking stamp is an unchanged install that rewrites nothing`() {
        val root = tmp.newFolder()
        val opened = mutableListOf<File>()
        val extractor = ModelExtractor(open = { file -> opened.add(file); file.inputStream() })
        val store = UserKeywordFixtures.store(root, extractor = extractor)
        store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, good))
        val before = UserKeywordFixtures.snapshot(store.directory())
        opened.clear()
        val again = store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, good))
        assertEquals(UserInstall.Installed(goodDigest, true), again)
        assertTrue("no archive was opened, so nothing was extracted", opened.isEmpty())
        assertEquals("the installed folder is byte for byte the same", before, UserKeywordFixtures.snapshot(store.directory()))
        assertEquals("only the installed folder is under the root", listOf(UserKeywordStore.CURRENT), root.list()?.toList())
    }

    @Test
    fun `an equal digest whose modified time changed reinstalls the model`() {
        val store = UserKeywordFixtures.store(tmp.newFolder())
        store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, good))
        val tokens = File(store.directory(), "tokens.txt")
        assertTrue("fixture: the modified time moves", tokens.setLastModified(tokens.lastModified() - 5_000L))
        assertTrue("the stamp no longer checks", UserKeywordCheck.verdict(store.directory()) is UserKeywordVerdict.Bad)
        assertEquals("an equal digest reinstalls instead of a no-op", UserInstall.Installed(goodDigest, false), store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, good)))
        assertTrue("afterwards the verdict is ready", UserKeywordCheck.verdict(store.directory()) is UserKeywordVerdict.Ready)
    }

    @Test
    fun `an equal digest whose stamp no longer matches reinstalls the model`() {
        val store = UserKeywordFixtures.store(tmp.newFolder())
        store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, good))
        File(store.directory(), "tokens.txt").writeText("changed on disk, another size")
        val again = store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, good))
        assertEquals(UserInstall.Installed(goodDigest, false), again)
        assertArrayEquals(
            "the tokens file is the archive's bytes again",
            UserKeywordFixtures.tokensBytes(),
            File(store.directory(), "tokens.txt").readBytes(),
        )
    }

    @Test
    fun `a digest mismatch keeps an installed model`() {
        val store = UserKeywordFixtures.store(tmp.newFolder())
        store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, good))
        val before = UserKeywordFixtures.snapshot(store.directory())
        val flipped = good.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x01).toByte() }
        assertEquals(UserInstall.Refused(UserKeywordReason.DIGEST_MISMATCH), store.install(UserKeywordFixtures.trained(PhraseKind.SEND, flipped, goodDigest)))
        assertEquals("the installed model is byte for byte the same", before, UserKeywordFixtures.snapshot(store.directory()))
        assertTrue("the installed model is still ready", UserKeywordCheck.verdict(store.directory()) is UserKeywordVerdict.Ready)
        assertEquals(goodDigest, store.installedDigest())
    }

    @Test
    fun `an archive over the size cap keeps an installed model`() {
        val root = tmp.newFolder()
        UserKeywordFixtures.store(root).install(UserKeywordFixtures.trained(PhraseKind.WAKE, good))
        val before = UserKeywordFixtures.snapshot(File(root, UserKeywordStore.CURRENT))
        val capped = UserKeywordFixtures.store(root, sizeCap = 1L)
        assertEquals(UserInstall.Refused(UserKeywordReason.TOO_LARGE), capped.install(UserKeywordFixtures.trained(PhraseKind.WAKE, good)))
        assertEquals("the installed model is byte for byte the same", before, UserKeywordFixtures.snapshot(capped.directory()))
        assertTrue("the installed model is still ready", UserKeywordCheck.verdict(capped.directory()) is UserKeywordVerdict.Ready)
    }

    @Test
    fun `an equal digest whose stored tokens lack a phrase is refused and is not an unchanged install`() {
        val root = tmp.newFolder()
        val store = UserKeywordFixtures.store(root)
        assertEquals("setup: the good model installs", UserInstall.Installed(goodDigest, false), store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, good)))
        val before = UserKeywordFixtures.snapshot(store.directory())
        val missingPhrase = KeywordLine(listOf("${KeywordsTestTokens.MARK}ZZ"))
        val stricter = UserKeywordFixtures.store(root, phrases = FixedPhrases.all() + missingPhrase)
        assertEquals(
            "the same digest with a phrase the tokens lack is refused, not an unchanged install",
            UserInstall.Refused(UserKeywordReason.TOKENS_MISSING),
            stricter.install(UserKeywordFixtures.trained(PhraseKind.WAKE, good)),
        )
        assertEquals("the installed model is byte for byte the same", before, UserKeywordFixtures.snapshot(store.directory()))
        assertEquals("the installed digest is still the first one", goodDigest, stricter.installedDigest())
        assertEquals("only the installed folder is under the root", listOf(UserKeywordStore.CURRENT), root.list()?.toList())
    }
}
