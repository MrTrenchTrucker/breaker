package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Edge cases of [LocalModelStore] that the basic tests leave open: ids whose
 * only fault is a character outside the allowed set, ids with capital letters,
 * a subdirectory inside a model directory, an empty marker file, and the order
 * of the installed id list.
 */
class LocalModelStoreEdgeCasesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun rootNames(): List<String> =
        tmp.root.listFiles()!!.map { it.name }.sorted()

    private fun installDirectly(dirName: String) {
        val dir = File(tmp.root, dirName)
        dir.mkdirs()
        File(dir, LocalModelStore.ARCHIVE_NAME).writeBytes("data".toByteArray())
    }

    // --- ids whose only fault is a disallowed character ---

    @Test
    fun `isSafeName refuses an id whose only fault is a disallowed character`() {
        val ids = listOf(
            "a b", "ab ", " ab", "a:b", "a*b", "a?b", "a;b", "a|b", "a<b", "a>b",
            "a%b", "a+b", "a@b", "a#b", "a(b", "a\"b", "a\nb", "a\tb", "a\u0000b",
        )
        for ((index, id) in ids.withIndex()) {
            assertFalse("id number $index has a disallowed character and must be refused", LocalModelStore.isSafeName(id))
        }
    }

    @Test
    fun `isSafeName accepts letters digits dot dash and underscore in any mix`() {
        assertTrue(LocalModelStore.isSafeName("Ab-9_x.y"))
        assertTrue(LocalModelStore.isSafeName("Model1"))
        assertTrue(LocalModelStore.isSafeName("a_b"))
    }

    @Test
    fun `directoryFor throws for an id with a disallowed character`() {
        val store = LocalModelStore(tmp.root)
        for (id in listOf("a b", "a:b", "a;b")) {
            try {
                store.directoryFor(id)
                fail("Expected IllegalArgumentException for id: '$id'")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `writing calls create nothing for an id with a disallowed character`() {
        val store = LocalModelStore(tmp.root)
        store.markVerified("a b", Fixtures.VALID_PIN)
        store.storeChecksums("a;b", "text")
        assertEquals("nothing may be created for an unsafe id", emptyList<String>(), rootNames())
    }

    @Test
    fun `question calls answer safely for an existing directory with a disallowed character`() {
        val store = LocalModelStore(tmp.root)
        // Control: the same layout under a safe name is seen as installed.
        installDirectly("a_b")
        File(tmp.root, "a_b/${LocalModelStore.VERIFIED_FILE}").writeText(Fixtures.VALID_PIN)
        assertTrue(store.isInstalled("a_b"))
        assertEquals(Fixtures.VALID_PIN, store.lastVerifiedDigest("a_b"))

        installDirectly("a b")
        File(tmp.root, "a b/${LocalModelStore.VERIFIED_FILE}").writeText(Fixtures.VALID_PIN)
        assertTrue("fixture: the directory must exist", File(tmp.root, "a b").isDirectory)

        assertFalse(store.isInstalled("a b"))
        assertTrue(store.modelFiles("a b").isEmpty())
        assertNull(store.lastVerifiedDigest("a b"))
        assertFalse(store.delete("a b"))
        assertTrue("the directory must still be there", File(tmp.root, "a b").isDirectory)
    }

    // --- letter case of the id ---

    @Test
    fun `directoryFor and archiveFile keep the letter case of the id`() {
        val store = LocalModelStore(tmp.root)
        assertEquals(File(tmp.root, "Model1"), store.directoryFor("Model1"))
        assertEquals("Mixed_Case-1.0", store.directoryFor("Mixed_Case-1.0").name)
        assertEquals(File(tmp.root, "Model1/model.archive"), store.archiveFile("Model1"))
        // Control: an all-lowercase id is unchanged.
        assertEquals(File(tmp.root, "model1"), store.directoryFor("model1"))
    }

    @Test
    fun `markVerified creates the directory named exactly as the id`() {
        val store = LocalModelStore(tmp.root)
        store.markVerified("Alpha_1", Fixtures.VALID_PIN)
        assertEquals(listOf("Alpha_1"), rootNames())
        assertEquals(Fixtures.VALID_PIN, store.lastVerifiedDigest("Alpha_1"))
    }

    @Test
    fun `installedModelIds returns an id with capital letters as written`() {
        val store = LocalModelStore(tmp.root)
        installDirectly("gamma")
        installDirectly("Beta")
        assertEquals(listOf("Beta", "gamma"), store.installedModelIds())
    }

    // --- a subdirectory is not a model file ---

    @Test
    fun `modelFiles does not list a subdirectory`() {
        val store = LocalModelStore(tmp.root)
        installDirectly("model1")
        File(tmp.root, "model1/extra.txt").writeText("extra")
        val nested = File(tmp.root, "model1/nested")
        nested.mkdirs()
        File(nested, "inner.txt").writeText("inner")
        assertTrue("fixture: the subdirectory must exist", nested.isDirectory)

        val names = store.modelFiles("model1").keys
        assertEquals(setOf(LocalModelStore.ARCHIVE_NAME, "extra.txt"), names)
    }

    // --- an empty marker file ---

    @Test
    fun `lastVerifiedDigest returns null for an empty marker file`() {
        val store = LocalModelStore(tmp.root)
        val dir = store.directoryFor("model1")
        dir.mkdirs()
        val marker = File(dir, LocalModelStore.VERIFIED_FILE)

        marker.writeText(Fixtures.VALID_PIN)
        assertEquals("control: a marker with a digest is read", Fixtures.VALID_PIN, store.lastVerifiedDigest("model1"))

        marker.writeText("")
        assertTrue("fixture: the marker must exist", marker.isFile)
        assertNull(store.lastVerifiedDigest("model1"))
    }

    @Test
    fun `lastVerifiedDigest returns null for a marker holding only whitespace`() {
        val store = LocalModelStore(tmp.root)
        val dir = store.directoryFor("model1")
        dir.mkdirs()
        File(dir, LocalModelStore.VERIFIED_FILE).writeText("  \n\t \n")
        assertNull(store.lastVerifiedDigest("model1"))
    }

    // --- order of the installed id list ---

    @Test
    fun `installedModelIds is sorted for many ids created in a scrambled order`() {
        val store = LocalModelStore(tmp.root)
        val created = listOf("m", "c", "x", "a", "q", "f", "z", "h", "b", "t", "e", "k")
        for (id in created) installDirectly(id)
        assertEquals(
            listOf("a", "b", "c", "e", "f", "h", "k", "m", "q", "t", "x", "z"),
            store.installedModelIds(),
        )
    }
}
