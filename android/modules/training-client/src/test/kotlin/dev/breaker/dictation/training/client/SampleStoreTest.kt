package dev.breaker.dictation.training.client

import dev.breaker.dictation.core.model.PhraseKind
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Behaviour of the sample files: put, list, read, delete, id and path checks. Written without a compiler, not run. */
class SampleStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val encoder = CountingWavEncoder()

    private val badIds = listOf(
        "../x", "../../outside", "/abs", "a/b", "a\\b", "", "a".repeat(65), "a.b", "a b", "café", "x\ny",
    )

    private fun storeOn(root: File, vararg ids: String) = SampleStore(root, ScriptedIdSource(*ids), encoder)

    private fun bytesOf(result: StoreResult<ByteArray>): ByteArray {
        assertTrue("expected Ok but was $result", result is StoreResult.Ok)
        return (result as StoreResult.Ok<ByteArray>).value
    }

    private fun linkOrSkip(link: File, target: File) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (e: IOException) {
            assumeNoException(e)
        } catch (e: UnsupportedOperationException) {
            assumeNoException(e)
        }
    }

    @Test
    fun `put then read gives back the bytes the encoder made`() {
        val root = tmp.newFolder("root")
        val ids = ScriptedIdSource("s1")
        val store = SampleStore(root, ids, encoder)
        val pcm = floatArrayOf(0.1f, 0.2f, 0.3f)
        assertEquals(StoreResult.Ok("s1"), store.put(PhraseKind.WAKE, pcm))
        assertArrayEquals(encoder.expected(pcm), bytesOf(store.read(PhraseKind.WAKE, "s1")))
        assertArrayEquals(encoder.expected(pcm), File(root, "wake/s1.wav").readBytes())
        assertEquals(1, ids.calls)
        assertEquals(1, encoder.calls)
        assertEquals(listOf("s1.wav"), File(root, "wake").list()!!.toList())
    }

    @Test
    fun `samples go in the folder of their own kind`() {
        val root = tmp.newFolder("root")
        val store = storeOn(root, "w1", "s1")
        store.put(PhraseKind.WAKE, floatArrayOf(0.1f))
        store.put(PhraseKind.SEND, floatArrayOf(0.1f))
        assertTrue(File(root, "wake/w1.wav").isFile)
        assertTrue(File(root, "send/s1.wav").isFile)
        assertFalse(File(root, "wake/s1.wav").exists())
        assertFalse(File(root, "send/w1.wav").exists())
        assertEquals(listOf("w1"), store.list(PhraseKind.WAKE))
        assertEquals(listOf("s1"), store.list(PhraseKind.SEND))
    }

    @Test
    fun `list returns the ids sorted`() {
        val root = tmp.newFolder("root")
        val store = storeOn(root, "d", "b", "f", "a", "e", "c", "h", "g")
        repeat(8) { store.put(PhraseKind.WAKE, floatArrayOf(0.1f)) }
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g", "h"), store.list(PhraseKind.WAKE))
    }

    @Test
    fun `list ignores a leftover temp file and a file with no sample suffix`() {
        val root = tmp.newFolder("root")
        val dir = File(root, "wake")
        dir.mkdirs()
        File(dir, "x.wav.tmp").writeText("half")
        File(dir, "plain").writeText("not a sample")
        val store = storeOn(root, "real")
        store.put(PhraseKind.WAKE, floatArrayOf(0.1f))
        assertEquals(listOf("real"), store.list(PhraseKind.WAKE))
    }

    @Test
    fun `list skips names that are not valid ids and folders`() {
        val root = tmp.newFolder("root")
        val dir = File(root, "wake")
        dir.mkdirs()
        File(dir, "bad.name.wav").writeText("x")
        File(dir, "folder1.wav").mkdirs()
        File(dir, "ok.wav").writeText("x")
        assertEquals(listOf("ok"), storeOn(root).list(PhraseKind.WAKE))
    }

    @Test
    fun `a missing kind folder lists as empty`() {
        val root = File(tmp.root, "never-made")
        assertEquals(emptyList<String>(), storeOn(root).list(PhraseKind.WAKE))
        assertEquals(emptyList<String>(), storeOn(tmp.newFolder("made")).list(PhraseKind.SEND))
    }

    @Test
    fun `put with a bad id from the source writes and encodes nothing`() {
        val root = tmp.newFolder("root")
        val outside = File(tmp.root, "outside.wav")
        outside.writeText("keep")
        val store = storeOn(root, "../x", "../../outside")
        assertEquals(StoreResult.InvalidId, store.put(PhraseKind.WAKE, floatArrayOf(0.1f)))
        assertEquals(StoreResult.InvalidId, store.put(PhraseKind.WAKE, floatArrayOf(0.1f)))
        assertEquals(0, encoder.calls)
        assertEquals("keep", outside.readText())
        assertFalse(File(root, "x.wav").exists())
        assertEquals(0, root.walkTopDown().count { it.isFile })
    }

    @Test
    fun `put does not overwrite an existing id and says so`() {
        val root = tmp.newFolder("root")
        val store = storeOn(root, "same", "same")
        val first = floatArrayOf(0.1f, 0.2f)
        assertEquals(StoreResult.Ok("same"), store.put(PhraseKind.WAKE, first))
        assertEquals(StoreResult.IoFailed, store.put(PhraseKind.WAKE, floatArrayOf(0.5f)))
        assertArrayEquals(encoder.expected(first), bytesOf(store.read(PhraseKind.WAKE, "same")))
    }

    @Test
    fun `put reports a failed write and leaves no sample`() {
        val root = tmp.newFolder("root")
        val blocker = File(root, "wake/s1.wav.tmp")
        File(blocker, "inner").also { it.parentFile.mkdirs() }.writeText("x")
        val store = storeOn(root, "s1")
        assertEquals(StoreResult.IoFailed, store.put(PhraseKind.WAKE, floatArrayOf(0.1f)))
        assertFalse(File(root, "wake/s1.wav").exists())
        assertEquals(emptyList<String>(), store.list(PhraseKind.WAKE))
    }

    @Test
    fun `read refuses ids that could leave the folder`() {
        val root = tmp.newFolder("root")
        File(tmp.root, "outside.wav").writeText("keep")
        val store = storeOn(root)
        for (id in badIds) {
            assertEquals("id: $id", StoreResult.InvalidId, store.read(PhraseKind.WAKE, id))
        }
    }

    @Test
    fun `deleteOne refuses ids that could leave the folder and deletes nothing`() {
        val root = tmp.newFolder("root")
        val outside = File(tmp.root, "outside.wav")
        outside.writeText("keep")
        val inside = File(root, "x.wav")
        inside.writeText("keep")
        val store = storeOn(root)
        for (id in badIds) {
            assertEquals("id: $id", StoreResult.InvalidId, store.deleteOne(PhraseKind.WAKE, id))
        }
        assertEquals("keep", outside.readText())
        assertEquals("keep", inside.readText())
    }

    @Test
    fun `a 64 character id works from put to delete`() {
        val root = tmp.newFolder("root")
        val id = "Z-9_".repeat(16)
        assertEquals(64, id.length)
        val store = storeOn(root, id)
        assertEquals(StoreResult.Ok(id), store.put(PhraseKind.WAKE, floatArrayOf(0.1f)))
        assertEquals(listOf(id), store.list(PhraseKind.WAKE))
        assertTrue(store.read(PhraseKind.WAKE, id) is StoreResult.Ok)
        assertEquals(StoreResult.Ok(Unit), store.deleteOne(PhraseKind.WAKE, id))
    }

    @Test
    fun `deleteOne removes the sample and then it is not found`() {
        val root = tmp.newFolder("root")
        val store = storeOn(root, "s1")
        store.put(PhraseKind.SEND, floatArrayOf(0.1f))
        assertEquals(StoreResult.Ok(Unit), store.deleteOne(PhraseKind.SEND, "s1"))
        assertEquals(emptyList<String>(), store.list(PhraseKind.SEND))
        assertEquals(StoreResult.NotFound, store.read(PhraseKind.SEND, "s1"))
        assertEquals(StoreResult.NotFound, store.deleteOne(PhraseKind.SEND, "s1"))
    }

    @Test
    fun `an id nobody stored is not found`() {
        val store = storeOn(tmp.newFolder("root"))
        assertEquals(StoreResult.NotFound, store.read(PhraseKind.WAKE, "nobody"))
        assertEquals(StoreResult.NotFound, store.deleteOne(PhraseKind.WAKE, "nobody"))
    }

    @Test
    fun `a kind folder that points outside the root is not followed`() {
        val root = tmp.newFolder("root")
        val elsewhere = tmp.newFolder("elsewhere")
        val secret = File(elsewhere, "abc.wav")
        secret.writeText("secret")
        linkOrSkip(File(root, "wake"), elsewhere)
        val store = storeOn(root, "new1")
        assertEquals(StoreResult.InvalidId, store.read(PhraseKind.WAKE, "abc"))
        assertEquals(StoreResult.InvalidId, store.deleteOne(PhraseKind.WAKE, "abc"))
        assertEquals(StoreResult.InvalidId, store.put(PhraseKind.WAKE, floatArrayOf(0.1f)))
        assertEquals(emptyList<String>(), store.list(PhraseKind.WAKE))
        assertEquals(listOf("abc.wav"), elsewhere.list()!!.toList())
        assertEquals("secret", secret.readText())
    }

    @Test
    fun `deleteAll does not follow a kind folder that points outside the root`() {
        val root = tmp.newFolder("root")
        val elsewhere = tmp.newFolder("elsewhere")
        val secret = File(elsewhere, "abc.wav")
        secret.writeText("secret")
        linkOrSkip(File(root, "wake"), elsewhere)
        assertEquals(StoreResult.IoFailed, storeOn(root).deleteAll())
        assertEquals("secret", secret.readText())
    }

    @Test
    fun `a stored sample is readable by its owner only`() {
        val root = tmp.newFolder("root")
        storeOn(root, "s1").put(PhraseKind.WAKE, floatArrayOf(0.1f))
        val permissions = try {
            Files.getPosixFilePermissions(File(root, "wake/s1.wav").toPath())
        } catch (e: UnsupportedOperationException) {
            assumeNoException(e)
            return
        }
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions)
    }
}
