package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.TilePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * What a save reports when the keystore fails and the restore that follows also fails.
 *
 * The failures are injected through [ThrowingKeystore] and through a file mover that the test
 * supplies. The original failure is always the one that comes out of the save; any other failure
 * has to be attached to it, and the live file and the reference have to be put back where they can.
 */
class SettingsKeystoreRestoreFaultTest : SettingsFileStoreTestBase() {

    /** The settings on disk before each failing save, with a reference the port also holds. */
    private fun oldSettings(): AppSettings = validSettings().copy(apiKeyRef = "old-reference")

    /** The settings a save tries to write; they differ from the old ones on several keys. */
    private fun newSettings(): AppSettings =
        validSettings(TilePosition(0.1f, 0.9f)).copy(
            language = "fr",
            formattingEnabled = true,
            apiKeyRef = "new-reference",
        )

    /** The names in the live file's folder, sorted. */
    private fun namesInFolderOf(file: File): List<String> =
        file.parentFile!!.list()!!.toList().sorted()

    /** The setActiveRef entries of [keystore], in the order they were made. */
    private fun setCallsOf(keystore: ThrowingKeystore): List<String> =
        keystore.callLog.filter { it.startsWith("setActiveRef(") }

    /** Runs [save] on [target] and returns what it threw; requires that it threw something. */
    private fun saveThatFails(target: SettingsFileStore, settings: AppSettings): Throwable {
        var thrown: Throwable? = null
        try {
            target.save(settings)
        } catch (failure: Throwable) {
            thrown = failure
        }
        assertNotNull("save() did not throw", thrown)
        return thrown!!
    }

    @Test
    fun `a failed reference restore still propagates the original failure with the second one attached`() {
        val file = newFile()
        val keystore = ThrowingKeystore(initialRef = "old-reference")
        store(file, keystore).save(oldSettings())
        val bytesBefore = file.readBytes()
        val first = IOException("first")
        val second = IOException("second")
        keystore.failNextSet(first, changeFirst = true)
        keystore.failNextSet(second, changeFirst = false)

        val thrown = saveThatFails(store(file, keystore), newSettings())

        assertSame("the save must throw the first failure", first, thrown)
        assertEquals(
            "the failures attached to the original",
            listOf(second),
            thrown.suppressed.toList(),
        )
        assertTrue(
            "the live file bytes changed after a failed save",
            file.readBytes().contentEquals(bytesBefore),
        )
    }

    @Test
    fun `a failed file put-back still propagates the original failure and still restores the reference`() {
        val file = newFile()
        val keystore = ThrowingKeystore(initialRef = "old-reference")
        store(file, keystore).save(oldSettings())
        val moveBackFailure = IOException("put-back failed")
        var moveCalls = 0
        val countingMover: (File, File) -> Unit = { from, to ->
            moveCalls++
            if (moveCalls == 2) throw moveBackFailure
            AtomicFileReplace.move(from, to)
        }
        val failure = IOException("keystore failed after changing the reference")
        keystore.failNextSet(failure, changeFirst = true)

        val thrown = saveThatFails(
            SettingsFileStore(file, keystore, AtomicFileReplace::write, countingMover),
            newSettings(),
        )

        assertSame("the save must throw the keystore's own exception", failure, thrown)
        assertEquals(
            "the failures attached to the original",
            listOf(moveBackFailure),
            thrown.suppressed.toList(),
        )
        assertEquals("the reference after the failed save", "old-reference", keystore.activeRef())
    }

    @Test
    fun `the put-back uses its own temp name beside the file and leaves nothing behind`() {
        val file = newFile()
        val keystore = ThrowingKeystore(initialRef = "old-reference")
        store(file, keystore).save(oldSettings())
        val sources = mutableListOf<File>()
        val destinations = mutableListOf<File>()
        val recordingMover: (File, File) -> Unit = { from, to ->
            sources += from
            destinations += to
            AtomicFileReplace.move(from, to)
        }
        keystore.failNextSet(IOException("keystore failed after changing the reference"), changeFirst = true)

        saveThatFails(
            SettingsFileStore(file, keystore, AtomicFileReplace::write, recordingMover),
            newSettings(),
        )

        assertEquals("the number of moves", 2, sources.size)
        assertEquals(
            "the put-back destination is the live file",
            file.absoluteFile,
            destinations[1].absoluteFile,
        )
        assertEquals(
            "the put-back source sits in the live file's folder",
            file.absoluteFile.parentFile,
            sources[1].absoluteFile.parentFile,
        )
        assertFalse(
            "the put-back source has the save's temp name",
            sources[1].name == AtomicFileReplace.tempFileFor(file).name,
        )
        assertFalse(
            "the put-back source has the first move's name",
            sources[1].name == sources[0].name,
        )
        assertEquals("the folder after the failed save", listOf(file.name), namesInFolderOf(file))
    }

    @Test
    fun `a previous reference that cannot be read does not block the save and skips only the reference restore`() {
        val fileA = newFile()
        val keystoreA = ThrowingKeystore(initialRef = "old-reference")
        store(fileA, keystoreA).save(oldSettings())
        keystoreA.activeRefFailure = IOException("reference unreadable")
        keystoreA.callLog.clear()

        store(fileA, keystoreA).save(newSettings())

        assertEquals(
            "the last setActiveRef call on a successful save",
            "setActiveRef(new-reference)",
            setCallsOf(keystoreA).last(),
        )
        assertEquals(
            "the file written by a save whose previous reference could not be read",
            newSettings().copy(apiKeyRef = null),
            store(fileA).load(),
        )

        val fileB = newFile()
        val keystoreB = ThrowingKeystore(initialRef = "old-reference")
        store(fileB, keystoreB).save(oldSettings())
        val bytesBefore = fileB.readBytes()
        keystoreB.activeRefFailure = IOException("reference unreadable")
        val failure = IOException("keystore failed after changing the reference")
        keystoreB.failNextSet(failure, changeFirst = true)
        keystoreB.callLog.clear()

        val thrown = saveThatFails(store(fileB, keystoreB), newSettings())

        assertSame("the save must throw the keystore's own exception", failure, thrown)
        assertEquals(
            "the setActiveRef calls after the failed save",
            listOf("setActiveRef(new-reference)"),
            setCallsOf(keystoreB),
        )
        assertTrue(
            "the live file bytes changed after a failed save",
            fileB.readBytes().contentEquals(bytesBefore),
        )
    }

    @Test
    fun `a previous reference read that fails once is never restored as no reference`() {
        val file = newFile()
        val keystore = ThrowingKeystore(initialRef = "old-reference")
        store(file, keystore).save(oldSettings())
        val bytesBefore = file.readBytes()
        keystore.activeRefFailsOnce = IOException("reference unreadable once")
        val failure = IOException("keystore failed after changing the reference")
        keystore.failNextSet(failure, changeFirst = true)
        keystore.callLog.clear()

        val thrown = saveThatFails(store(file, keystore), newSettings())

        assertSame("the save must throw the keystore's own exception", failure, thrown)
        assertEquals("the first read is the one that failed", null, keystore.activeRefFailsOnce)
        assertEquals(
            "the setActiveRef calls after the failed save: no restore to an empty reference",
            listOf("setActiveRef(new-reference)"),
            setCallsOf(keystore),
        )
        assertEquals("the reference stays as the port left it", "new-reference", keystore.activeRef())
        assertTrue("the live file is put back byte for byte", file.readBytes().contentEquals(bytesBefore))
    }

    @Test
    fun `the put-back of the file and of the reference run while the save lock is held`() {
        val file = newFile()
        val keystore = ThrowingKeystore(initialRef = "old-reference")
        store(file, keystore).save(oldSettings())
        keystore.callLog.clear()
        keystore.lockHeldAtSet.clear()
        val moverLockHeld = mutableListOf<Boolean>()
        val recordingMover: (File, File) -> Unit = { from, to ->
            moverLockHeld += Thread.holdsLock(AtomicFileReplace.lock)
            AtomicFileReplace.move(from, to)
        }
        keystore.failNextSet(IOException("keystore failed after changing the reference"), changeFirst = true)

        saveThatFails(
            SettingsFileStore(file, keystore, AtomicFileReplace::write, recordingMover),
            newSettings(),
        )

        assertEquals("the number of file moves", 2, moverLockHeld.size)
        assertTrue("the save's own move ran without the save lock", moverLockHeld[0])
        assertTrue("the put-back move ran without the save lock", moverLockHeld[1])
        assertEquals("the number of set calls", 2, keystore.lockHeldAtSet.size)
        assertTrue("the failing set ran without the save lock", keystore.lockHeldAtSet[0])
        assertTrue("the put-back set ran without the save lock", keystore.lockHeldAtSet[1])
    }

    @Test
    fun `the previous file is back before the previous reference is restored`() {
        val file = newFile()
        val inner = ThrowingKeystore(initialRef = "old-reference")
        store(file, inner).save(oldSettings())
        inner.callLog.clear()
        val bytesBefore = file.readBytes()
        val observed = mutableListOf<ByteArray?>()
        val observing = object : Keystore {
            override fun setActiveRef(ref: String?) {
                observed += if (file.exists()) file.readBytes() else null
                inner.setActiveRef(ref)
            }

            override fun activeRef(): String? = inner.activeRef()
        }
        inner.failNextSet(IOException("keystore failed after changing the reference"), changeFirst = true)

        saveThatFails(store(file, observing), newSettings())

        assertEquals("the number of setActiveRef calls", 2, observed.size)
        assertFalse(
            "at the first call the new file was not in place",
            observed[0]!!.contentEquals(bytesBefore),
        )
        assertTrue(
            "at the restoring call the previous file was not yet back",
            observed[1]!!.contentEquals(bytesBefore),
        )
        assertEquals(
            "the setActiveRef calls, in order",
            listOf("setActiveRef(new-reference)", "setActiveRef(old-reference)"),
            setCallsOf(inner),
        )
    }
}
