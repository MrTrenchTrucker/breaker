package dev.breaker.dictation.wiring

import dev.breaker.dictation.gates.AppSourceFiles
import dev.breaker.dictation.stt.ondevice.KEYWORD_MODEL_ID
import dev.breaker.dictation.stt.ondevice.KeywordChooser
import dev.breaker.dictation.stt.ondevice.KeywordLoad
import dev.breaker.dictation.stt.ondevice.KeywordModel
import dev.breaker.dictation.stt.ondevice.KeywordSource
import dev.breaker.dictation.stt.ondevice.LocalModelStore
import dev.breaker.dictation.stt.ondevice.ModelLoader
import dev.breaker.dictation.stt.ondevice.UserKeywordLoad
import dev.breaker.dictation.stt.ondevice.UserKeywordReason
import dev.breaker.dictation.stt.ondevice.UserKeywordStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The keyword load the app builds over the user keyword store and the shipped store. An absent or ignored user
 * folder leaves the answer to the shipped model, and the source text pins the folder name, the chooser and the
 * signatures. A ready user model cannot be built in the app test source set, because no archive writer is on its
 * classpath; the ready branch is held by the chooser test here and by the text pin on the user loader.
 */
class KeywordLoadWiringTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keywordSource: String = "kotlin/dev/breaker/dictation/wiring/KeywordPhraseSource.kt"

    private fun shippedStore(): LocalModelStore = LocalModelStore(File(tmp.root, "models"))

    private fun userStore(): UserKeywordStore = UserKeywordStore(File(tmp.root, "user-keywords"))

    private fun squeezed(code: String): String = code.replace(Regex("\\s+"), "")

    @Test
    fun `with no user folder and no shipped model the keyword load is a refusal`() {
        val load: KeywordLoad = keywordLoad(shippedStore(), userStore())()
        assertTrue("app: the keyword load with no models was not a refusal", load is KeywordLoad.Refused)
    }

    @Test
    fun `an unstamped user folder is ignored and gives a refusal when no shipped model is installed`() {
        File(tmp.root, "user-keywords/current").mkdirs()
        val load: KeywordLoad = keywordLoad(shippedStore(), userStore())()
        assertTrue("app: an unstamped user folder must not answer a ready model", load is KeywordLoad.Refused)
    }

    @Test
    fun `a user folder with no install stamp gives the source no stream`() {
        val files: File = tmp.newFolder("files")
        File(files, "user-keywords/current").mkdirs()
        val source: PhraseStreamSource = keywordPhraseSource(shippedStore(), files)
        assertNull("app: an unstamped user folder reached the take", source.newStream())
    }

    @Test
    fun `the chooser gives a ready user model and does not ask the shipped model`() {
        var shippedAsked = 0
        val model = KeywordModel(KEYWORD_MODEL_ID, File("user-model"), "digest", KeywordSource.USER)
        val chooser = KeywordChooser(
            user = { UserKeywordLoad.Ready(model) },
            default = {
                shippedAsked += 1
                KeywordLoad.Refused(ModelLoader.Refusal.NOT_INSTALLED, "refused")
            },
        )
        val choice = chooser.choose()
        assertTrue("app: the ready user model was not the chosen load", choice.load is KeywordLoad.Ready)
        assertSame("app: the chosen model is not the ready user model", model, (choice.load as KeywordLoad.Ready).model)
        assertEquals("app: the shipped model was asked while a user model was ready", 0, shippedAsked)
        assertNull("app: a ready user model reported an ignored reason", choice.userIgnored)
    }

    @Test
    fun `a user model that the chooser ignores keeps its reason and the shipped model answers`() {
        val chooser = KeywordChooser(
            user = { UserKeywordLoad.Ignored(UserKeywordReason.STAMP_MISMATCH) },
            default = { KeywordLoad.Refused(ModelLoader.Refusal.NOT_INSTALLED, "refused") },
        )
        val choice = chooser.choose()
        assertEquals("app: the ignored reason was not kept", UserKeywordReason.STAMP_MISMATCH, choice.userIgnored)
        assertTrue("app: an ignored user model was used", choice.load is KeywordLoad.Refused)
    }

    @Test
    fun `the source names the user folder and keeps it apart from the shipped store`() {
        val stripped = AppSourceFiles.strip(AppSourceFiles.mainFile(keywordSource))
        assertTrue("app: the user keyword folder name is not user-keywords", stripped.literals.contains("user-keywords"))
        assertTrue(
            "app: the user keyword folder constant is not declared",
            squeezed(stripped.code).contains("internalconstvalUSER_KEYWORDS_FOLDER:String="),
        )
        assertTrue(
            "app: the user keyword store is not built under the user folder constant",
            squeezed(stripped.code).contains("UserKeywordStore(File(filesDir,USER_KEYWORDS_FOLDER))"),
        )
        assertFalse("app: the keyword source names the shipped store folder", stripped.literals.contains("models"))
    }

    @Test
    fun `the keyword load is the chooser over the user loader and the shipped loader`() {
        val code = squeezed(AppSourceFiles.strip(AppSourceFiles.mainFile(keywordSource)).code)
        assertTrue("app: the chooser does not ask the user loader", code.contains("user={UserKeywordLoader(userKeywords).load()},"))
        assertTrue("app: the chooser does not ask the shipped loader", code.contains("default={KeywordModelLoader(store).load()},"))
        assertTrue("app: the keyword load does not answer the chooser choice", code.contains("return{chooser.choose().load}"))
        assertTrue("app: the keyword source does not take the keyword load", code.contains("load=keywordLoad(store,userKeywords),"))
    }

    @Test
    fun `the keyword source and keyword load keep their signatures`() {
        val sourceSignature: (LocalModelStore, File) -> PhraseStreamSource = ::keywordPhraseSource
        val loadSignature: (LocalModelStore, UserKeywordStore) -> () -> KeywordLoad = ::keywordLoad
        assertNotNull("app: the keyword source reference is missing", sourceSignature)
        assertNotNull("app: the keyword load reference is missing", loadSignature)
    }
}
