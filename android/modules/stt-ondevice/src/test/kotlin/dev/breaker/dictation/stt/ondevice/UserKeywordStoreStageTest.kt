package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.PhraseKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A scripted failure at each stage of the swap. Every test first installs the first model, then installs the second
 * one with the failure in place, so the previous model is what each refusal must keep.
 */
class UserKeywordStoreStageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val first = UserKeywordFixtures.archive("first")
    private val second = UserKeywordFixtures.archive("second")
    private val firstDigest = Fixtures.independentSha256(first)
    private val secondDigest = Fixtures.independentSha256(second)

    /** A root holding the first model, the store that will install the second one, and the first model's bytes. */
    private class Scene(val root: File, val store: UserKeywordStore, val before: Map<String, List<Byte>>)

    /** [afterSetup] runs once the first model is installed, so a leftover it makes is not swept by the setup install. */
    private fun scene(
        stage: (UserStoreStage) -> Unit = {},
        files: UserStoreFiles = RealUserStoreFiles,
        afterSetup: (File) -> Unit = {},
    ): Scene {
        val root = tmp.newFolder()
        val setup = UserKeywordFixtures.store(root)
        assertEquals("setup: the first model installs", UserInstall.Installed(firstDigest, false), setup.install(UserKeywordFixtures.trained(PhraseKind.WAKE, first)))
        val before = UserKeywordFixtures.snapshot(setup.directory())
        afterSetup(root)
        return Scene(root, UserKeywordFixtures.store(root, files = files, stage = stage), before)
    }

    /** The first model is still the installed one, byte for byte, loads as ready, and nothing of the new install is left. */
    private fun assertPreviousKept(scene: Scene, label: String) {
        assertEquals("$label: the installed folder is byte for byte the first model", scene.before, UserKeywordFixtures.snapshot(scene.store.directory()))
        assertEquals("$label: the installed digest is the first one", firstDigest, scene.store.installedDigest())
        assertTrue("$label: the verdict is ready", UserKeywordCheck.verdict(scene.store.directory()) is UserKeywordVerdict.Ready)
        assertEquals("$label: nothing of the new install is left", listOf(UserKeywordStore.CURRENT), scene.root.list()?.toList())
    }

    @Test
    fun `a failure while staging the archive is a stage failure and the previous model stays`() {
        val s = scene(stage = UserKeywordFixtures.failAt(UserStoreStage.STAGE_WRITE))
        assertEquals(UserInstall.Failed(UserKeywordReason.STAGE_FAILED), s.store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, second)))
        assertPreviousKept(s, "stage write")
    }

    @Test
    fun `a failure during extraction is a stage failure and the previous model stays`() {
        val s = scene(stage = UserKeywordFixtures.failAt(UserStoreStage.EXTRACT))
        assertEquals(UserInstall.Failed(UserKeywordReason.STAGE_FAILED), s.store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, second)))
        assertPreviousKept(s, "extract")
    }

    @Test
    fun `a failure while writing the stamp is a stamp failure and the previous model stays`() {
        val s = scene(stage = UserKeywordFixtures.failAt(UserStoreStage.STAMP_WRITE))
        assertEquals(UserInstall.Failed(UserKeywordReason.STAMP_FAILED), s.store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, second)))
        assertPreviousKept(s, "stamp write")
    }

    @Test
    fun `a stamp that cannot be measured is a stamp failure and the previous model stays`() {
        val s = scene()
        val unmeasurable = UserKeywordFixtures.store(s.root, digestOf = { null })
        assertEquals(UserInstall.Failed(UserKeywordReason.STAMP_FAILED), unmeasurable.install(UserKeywordFixtures.trained(PhraseKind.WAKE, second)))
        assertPreviousKept(Scene(s.root, unmeasurable, s.before), "measure")
    }

    @Test
    fun `a failure before the old model moves aside is a swap failure and the previous model stays`() {
        val s = scene(stage = UserKeywordFixtures.failAt(UserStoreStage.ASIDE))
        assertEquals(UserInstall.Failed(UserKeywordReason.SWAP_FAILED), s.store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, second)))
        assertPreviousKept(s, "aside")
    }

    @Test
    fun `a failed move into place restores the previous model and reports a swap failure`() {
        val s = scene(stage = UserKeywordFixtures.failAt(UserStoreStage.MOVE_IN))
        assertEquals(UserInstall.Failed(UserKeywordReason.SWAP_FAILED), s.store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, second)))
        assertPreviousKept(s, "move in")
    }

    @Test
    fun `a failure while deleting the old model still leaves the new model current`() {
        val s = scene(stage = UserKeywordFixtures.failAt(UserStoreStage.DELETE_OLD))
        assertEquals(UserInstall.Installed(secondDigest, false), s.store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, second)))
        assertEquals(secondDigest, s.store.installedDigest())
        assertTrue("the verdict is ready for the new model", UserKeywordCheck.verdict(s.store.directory()) is UserKeywordVerdict.Ready)
        assertTrue("the old model is left aside for the next install to sweep", File(s.root, UserKeywordStore.ASIDE).isDirectory)
    }

    @Test
    fun `a failed put-back after a failed move into place leaves the previous model aside and the next install sweeps it`() {
        val s = scene(
            stage = UserKeywordFixtures.failAt(UserStoreStage.MOVE_IN),
            files = UserKeywordFixtures.FailingPutBack(RealUserStoreFiles),
        )
        assertEquals(UserInstall.Failed(UserKeywordReason.SWAP_FAILED), s.store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, second)))
        assertFalse("nothing is installed", s.store.directory().exists())
        assertTrue("the verdict is absent, so the default model is used", UserKeywordCheck.verdict(s.store.directory()) is UserKeywordVerdict.Absent)
        assertEquals(
            "the previous model's files are in the aside folder, byte for byte",
            s.before,
            UserKeywordFixtures.snapshot(File(s.root, UserKeywordStore.ASIDE)),
        )
        val next = UserKeywordFixtures.store(s.root)
        assertEquals(UserInstall.Installed(secondDigest, false), next.install(UserKeywordFixtures.trained(PhraseKind.WAKE, second)))
        assertFalse("the aside folder was swept by the next install", File(s.root, UserKeywordStore.ASIDE).exists())
        assertTrue("the new model is ready", UserKeywordCheck.verdict(next.directory()) is UserKeywordVerdict.Ready)
    }

    @Test
    fun `a failed delete of a leftover during the sweep is a stage failure and nothing new is written`() {
        val s = scene(
            files = UserKeywordFixtures.FailingDeletes(RealUserStoreFiles, setOf(UserKeywordStore.ASIDE)),
            afterSetup = { root -> UserKeywordFixtures.fill(File(root, UserKeywordStore.ASIDE), UserKeywordFixtures.FILES, "left over") },
        )
        assertEquals(UserInstall.Failed(UserKeywordReason.STAGE_FAILED), s.store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, second)))
        assertFalse("no archive was staged", File(s.root, UserKeywordStore.INCOMING).exists())
        assertFalse("no new model folder was made", File(s.root, UserKeywordStore.NEXT).exists())
        assertEquals("the installed folder is byte for byte the first model", s.before, UserKeywordFixtures.snapshot(s.store.directory()))
    }
}
