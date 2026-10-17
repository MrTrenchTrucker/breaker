package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.TilePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * What a failed save leaves behind, and when the keystore is told.
 *
 * The tests call the real write and move steps, so the only fakes are the failures they inject.
 */
class SettingsAtomicSaveTest : SettingsFileStoreTestBase() {

    /** The settings that are on disk before each failing save. */
    private fun oldSettings(): AppSettings = validSettings()

    /** The settings a save tries to write; they differ from the old ones on several keys. */
    private fun newSettings(): AppSettings =
        validSettings(TilePosition(0.1f, 0.9f)).copy(language = "fr", formattingEnabled = true)

    /** The names in the live file's folder, sorted. */
    private fun namesInFolderOf(file: File): List<String> =
        file.parentFile!!.list()!!.toList().sorted()

    /** Runs [save] and requires that it throws an [IOException]. */
    private fun saveExpectingIOException(store: SettingsFileStore, settings: AppSettings) {
        val thrown = try {
            store.save(settings)
            null
        } catch (error: IOException) {
            error
        }
        assertNotNull("save() swallowed the failure", thrown)
    }

    /** A writer that truncates its target and then fails must not cost the old settings. */
    @Test
    fun `a writer that fails after truncating the file leaves the old settings loadable`() {
        val file = newFile()
        store(file).save(oldSettings())
        val truncatingWriter: (File, Properties) -> Unit = { target, _ ->
            target.outputStream().close()
            throw IOException("writer failed after truncating the file")
        }

        saveExpectingIOException(
            SettingsFileStore(file, FakeKeystore(), truncatingWriter, AtomicFileReplace::move),
            newSettings(),
        )

        assertEquals("the old settings after a failed write", oldSettings(), store(file).load())
    }

    /** A failed move must leave the old file in place and no temp file behind. */
    @Test
    fun `a mover that fails leaves the old settings loadable and no temp file behind`() {
        val file = newFile()
        store(file).save(oldSettings())
        val failingMover: (File, File) -> Unit = { _, _ -> throw IOException("mover failed") }

        saveExpectingIOException(
            SettingsFileStore(file, FakeKeystore(), AtomicFileReplace::write, failingMover),
            newSettings(),
        )

        assertEquals("the old settings after a failed move", oldSettings(), store(file).load())
        assertEquals("the folder after a failed move", listOf(file.name), namesInFolderOf(file))
    }

    /** A writer that fails part way through must not leave a partial temp file behind. */
    @Test
    fun `a writer that fails after writing part of the file leaves no temp file behind`() {
        val file = newFile()
        store(file).save(oldSettings())
        val partialWriter: (File, Properties) -> Unit = { target, _ ->
            target.outputStream().use { it.write("mode=ser".toByteArray(StandardCharsets.UTF_8)) }
            throw IOException("writer failed part way through")
        }

        saveExpectingIOException(
            SettingsFileStore(file, FakeKeystore(), partialWriter, AtomicFileReplace::move),
            newSettings(),
        )

        assertEquals("the folder after a failed write", listOf(file.name), namesInFolderOf(file))
        assertEquals("the old settings after a failed write", oldSettings(), store(file).load())
    }

    /** A failed save must not point the keystore at the reference it failed to write. */
    @Test
    fun `a failed save does not point the keystore at the new reference`() {
        val file = newFile()
        store(file).save(oldSettings())
        val update = newSettings().copy(apiKeyRef = "new-reference")

        val writerKeystore = FakeKeystore().also { it.setActiveRef("old-reference") }
        saveExpectingIOException(
            SettingsFileStore(file, writerKeystore, { _, _ -> throw IOException("writer failed") }, AtomicFileReplace::move),
            update,
        )
        assertEquals("writer failure: the keystore reference", "old-reference", writerKeystore.activeRef())

        val moverKeystore = FakeKeystore().also { it.setActiveRef("old-reference") }
        saveExpectingIOException(
            SettingsFileStore(file, moverKeystore, AtomicFileReplace::write) { _, _ ->
                throw IOException("mover failed")
            },
            update,
        )
        assertEquals("mover failure: the keystore reference", "old-reference", moverKeystore.activeRef())
    }

    /** A successful save makes one move, from a temp file beside the live file that already holds the new settings. */
    @Test
    fun `a successful save moves the temp file over the live file in one move`() {
        val file = newFile()
        store(file).save(oldSettings())
        val moves = mutableListOf<Pair<File, File>>()
        val loadedFromSource = mutableListOf<AppSettings>()
        val recordingMover: (File, File) -> Unit = { source, destination ->
            moves += Pair(source, destination)
            loadedFromSource += store(source).load()
            AtomicFileReplace.move(source, destination)
        }

        SettingsFileStore(file, FakeKeystore(), AtomicFileReplace::write, recordingMover)
            .save(newSettings())

        assertEquals("the save should make exactly one move", 1, moves.size)
        val (source, destination) = moves.single()
        assertEquals("the move should target the live file", file.absoluteFile, destination.absoluteFile)
        assertEquals("the source should be the live file name plus .tmp", file.name + ".tmp", source.name)
        assertEquals("the source should sit in the live file's folder", file.absoluteFile.parentFile, source.absoluteFile.parentFile)
        assertEquals("the source should be read once, when the move ran", 1, loadedFromSource.size)
        assertEquals("the source's settings when the move ran", newSettings(), loadedFromSource.single())
    }

    /** After a successful save the folder holds the live file and nothing else. */
    @Test
    fun `a successful save leaves only the live file in the folder`() {
        val file = newFile()
        store(file).save(oldSettings())
        store(file).save(newSettings())

        assertEquals("the folder after a successful save", listOf(file.name), namesInFolderOf(file))
    }

    /** A temp file left by an earlier crash is never read, and the next save replaces it. */
    @Test
    fun `a stale temp file from an earlier crash is replaced by the next save and never read by load`() {
        val file = newFile()
        store(file).save(oldSettings())
        val stale = File(file.parentFile, file.name + ".tmp")
        stale.writeText("mode=ser")

        assertEquals("load should ignore the stale temp file", oldSettings(), store(file).load())

        store(file).save(newSettings())

        assertFalse("the stale temp file survived the next save", stale.exists())
        assertEquals("the live file after the next save", newSettings(), store(file).load())
    }

    /**
     * Every save runs its write while the process-wide lock is held. The check
     * reads whether the calling code holds the monitor. It starts no other
     * execution context and waits for nothing, so it gives the same answer on
     * one core and on many.
     */
    @Test
    fun `saves run while holding the process-wide lock`() {
        val file = newFile()
        val heldDuringWrite = mutableListOf<Boolean>()
        val recordingWriter: (File, Properties) -> Unit = { target, properties ->
            heldDuringWrite += Thread.holdsLock(AtomicFileReplace.lock)
            AtomicFileReplace.write(target, properties)
        }
        val mover: (File, File) -> Unit = AtomicFileReplace::move

        SettingsFileStore(file, FakeKeystore(), recordingWriter, mover).save(oldSettings())
        SettingsFileStore(file, FakeKeystore(), recordingWriter, mover).save(newSettings())

        assertEquals("both saves should reach their writer", 2, heldDuringWrite.size)
        assertTrue("the first save wrote without the process-wide lock", heldDuringWrite[0])
        assertTrue("the second save wrote without the process-wide lock", heldDuringWrite[1])
    }

    /** The keystore hears the new reference only once the new settings are in the live file, under the lock. */
    @Test
    fun `the keystore is told only after the file is in place`() {
        val file = newFile()
        store(file).save(oldSettings())
        val lockHeldAtSet = mutableListOf<Boolean>()
        val loadedAtSet = mutableListOf<AppSettings>()
        val observing = ObservingKeystore(FakeKeystore()) {
            lockHeldAtSet += Thread.holdsLock(AtomicFileReplace.lock)
            loadedAtSet += store(file).load()
        }

        SettingsFileStore(file, observing, AtomicFileReplace::write, AtomicFileReplace::move).save(newSettings())

        assertEquals("the keystore should be told once", 1, loadedAtSet.size)
        assertEquals("the live file when the keystore was told", newSettings(), loadedAtSet.single())
        assertEquals("the keystore should be told once", 1, lockHeldAtSet.size)
        assertTrue("the keystore was told without the process-wide lock", lockHeldAtSet.single())
    }
}

/** A keystore that runs [onSet] before it passes each reference on to [inner]. */
private class ObservingKeystore(
    private val inner: Keystore,
    private val onSet: () -> Unit,
) : Keystore by inner {
    override fun setActiveRef(ref: String?) {
        onSet()
        inner.setActiveRef(ref)
    }
}
