package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.PhraseKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A kill leaves leftovers and no current model; the next install sweeps them. Also remove, and that the store stays under its root. */
class UserKeywordStoreSweepTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val good = UserKeywordFixtures.archive("first")
    private val goodDigest = Fixtures.independentSha256(good)

    /** The state after a kill between the old model moving aside and the new one moving in: no current folder. */
    private fun killedBetweenAsideAndMoveIn(root: File) {
        UserKeywordFixtures.fill(File(root, UserKeywordStore.ASIDE), UserKeywordFixtures.FILES, "old model")
        UserKeywordFixtures.fill(File(root, UserKeywordStore.NEXT), listOf(UserKeywordFixtures.FILES[0]), "partial")
        File(root, UserKeywordStore.INCOMING).writeBytes(good)
    }

    @Test
    fun `a kill between the old move aside and the new move in leaves no model and the verdict is absent`() {
        val root = tmp.newFolder()
        killedBetweenAsideAndMoveIn(root)
        val store = UserKeywordFixtures.store(root)
        assertFalse("there is no installed folder", store.directory().exists())
        assertTrue("the verdict is absent, so the default model is used", UserKeywordCheck.verdict(store.directory()) is UserKeywordVerdict.Absent)
        assertEquals(null, store.installedDigest())
    }

    @Test
    fun `the next install sweeps the leftovers and succeeds`() {
        val root = tmp.newFolder()
        killedBetweenAsideAndMoveIn(root)
        val store = UserKeywordFixtures.store(root)
        assertEquals(UserInstall.Installed(goodDigest, false), store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, good)))
        for (leftover in listOf(UserKeywordStore.ASIDE, UserKeywordStore.NEXT, UserKeywordStore.WORK, UserKeywordStore.INCOMING)) {
            assertFalse("$leftover was swept", File(root, leftover).exists())
        }
        assertTrue("the verdict is ready", UserKeywordCheck.verdict(store.directory()) is UserKeywordVerdict.Ready)
    }

    @Test
    fun `remove deletes the installed model and reports true`() {
        val store = UserKeywordFixtures.store(tmp.newFolder())
        store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, good))
        assertTrue(store.remove())
        assertFalse("the installed folder is gone", store.directory().exists())
    }

    @Test
    fun `a second remove reports true and the verdict is absent`() {
        val store = UserKeywordFixtures.store(tmp.newFolder())
        store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, good))
        assertTrue(store.remove())
        assertTrue("nothing left to remove is still a success", store.remove())
        assertTrue("the verdict is absent", UserKeywordCheck.verdict(store.directory()) is UserKeywordVerdict.Absent)
    }

    @Test
    fun `remove sweeps the leftovers as well`() {
        val root = tmp.newFolder()
        val store = UserKeywordFixtures.store(root)
        store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, good))
        killedBetweenAsideAndMoveIn(root)
        UserKeywordFixtures.fill(File(root, UserKeywordStore.WORK), listOf("junk.bin"), "work")
        assertTrue(store.remove())
        for (name in listOf(UserKeywordStore.CURRENT, UserKeywordStore.ASIDE, UserKeywordStore.NEXT, UserKeywordStore.WORK, UserKeywordStore.INCOMING)) {
            assertFalse("$name was removed", File(root, name).exists())
        }
    }

    @Test
    fun `remove without an installed model still sweeps every leftover`() {
        val root = tmp.newFolder()
        UserKeywordFixtures.fill(File(root, UserKeywordStore.ASIDE), UserKeywordFixtures.FILES, "old model")
        UserKeywordFixtures.fill(File(root, UserKeywordStore.NEXT), listOf(UserKeywordFixtures.FILES[0]), "partial")
        UserKeywordFixtures.fill(File(root, UserKeywordStore.WORK), listOf("junk.bin"), "work")
        File(root, UserKeywordStore.INCOMING).writeBytes(good)
        val store = UserKeywordFixtures.store(root)
        assertTrue("the result is true when every leftover is gone", store.remove())
        for (leftover in listOf(UserKeywordStore.ASIDE, UserKeywordStore.NEXT, UserKeywordStore.WORK, UserKeywordStore.INCOMING)) {
            assertFalse("$leftover was removed", File(root, leftover).exists())
        }
        assertFalse("there is still no installed model", store.directory().exists())
    }

    @Test
    fun `remove reports false when a folder cannot be deleted`() {
        val root = tmp.newFolder()
        UserKeywordFixtures.store(root).install(UserKeywordFixtures.trained(PhraseKind.WAKE, good))
        val stuck = UserKeywordFixtures.store(root, files = UserKeywordFixtures.FailingDeletes(RealUserStoreFiles, setOf(UserKeywordStore.CURRENT)))
        assertFalse("the delete of the installed folder failed", stuck.remove())
        assertTrue("the installed folder is still there", File(root, UserKeywordStore.CURRENT).isDirectory)
    }

    @Test
    fun `the store writes only under its own root and leaves a sibling folder untouched`() {
        val parent = tmp.newFolder("parent")
        val root = File(parent, "user-keywords")
        val sibling = File(parent, "shipped")
        UserKeywordFixtures.fill(sibling, listOf("marker.txt"), "shipped store")
        val siblingBefore = UserKeywordFixtures.snapshot(sibling)
        val store = UserKeywordFixtures.store(root)
        store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, good))
        store.install(UserKeywordFixtures.trained(PhraseKind.WAKE, UserKeywordFixtures.archive("second")))
        store.remove()
        assertEquals("the sibling folder is unchanged", siblingBefore, UserKeywordFixtures.snapshot(sibling))
        assertEquals("the parent holds only the root and the sibling", listOf("shipped", "user-keywords"), parent.list()?.sorted())
    }
}
