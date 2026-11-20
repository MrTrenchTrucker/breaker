package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A re-install whose staged move fails. The earlier archive is renamed aside to a backup first, so
 * a failed move must put it back: the earlier model stays complete - archive AND unpacked files -
 * and the install is refused with MOVE_FAILED.
 *
 * The staged file is wrapped so its [File.renameTo] answers false, the seam
 * ModelInstallerDeleteFailureTest uses. The rename-aside is a rename of the real archive file, so
 * the wrap cannot see it.
 */
class ModelInstallerMoveRollbackTest : ModelInstallerUnpackFixture() {

    private val second = TarFixtures.tinyArchive { name -> TarFixtures.contentOf("v2-$name", 48) }

    private fun backupPathOf(store: LocalModelStore): File =
        File(store.stagingDirectory(), "tiny" + LocalModelStore.BACKUP_SUFFIX)

    @Test
    fun `a staged move that fails puts the earlier archive back and leaves the earlier files`() {
        val store = newStore()
        installedOf(run(tinyBytes, store))
        val archiveBefore = store.archiveFile("tiny").readBytes()
        assertTrue("fixture: the earlier install unpacked", store.isExtracted("tiny"))

        val refused = refusedOf(
            run(
                second,
                store,
                wrap = { real ->
                    object : File(real.path) {
                        override fun renameTo(dest: File): Boolean = false
                    }
                },
            ),
        )

        assertEquals(ModelInstaller.Refusal.MOVE_FAILED, refused.refusal)
        assertEquals(ModelMessages.SAVE_FAILED, refused.detail)
        assertFalse("the staged file was discarded", refused.leftOnDisk)
        assertTrue("the earlier archive is still in place", store.archiveFile("tiny").isFile)
        assertArrayEquals(
            "the earlier archive is byte for byte unchanged",
            archiveBefore,
            store.archiveFile("tiny").readBytes(),
        )
        assertFilesHold(store, TarFixtures.TINY_FILES, withStamp = true) { TarFixtures.contentOf(it, 48) }
        assertFalse("no backup is left in staging", backupPathOf(store).exists())
        assertFalse("the staged file is gone", File(store.stagingDirectory(), "model.archive").exists())
    }

    @Test
    fun `a rename aside that fails refuses MOVE_FAILED and leaves the earlier model alone`() {
        val store = newStore()
        installedOf(run(tinyBytes, store))
        val archiveBefore = store.archiveFile("tiny").readBytes()
        // A non-empty directory where the backup goes: the rename-aside cannot replace it.
        val blocked = backupPathOf(store)
        assertTrue("fixture: the backup path can be made a directory", blocked.mkdirs())
        File(blocked, "held").writeText("x")
        assertTrue("fixture: the backup path is a non-empty directory", blocked.isDirectory)

        val refused = refusedOf(run(second, store))

        assertEquals(ModelInstaller.Refusal.MOVE_FAILED, refused.refusal)
        assertEquals(ModelMessages.SAVE_FAILED, refused.detail)
        assertFalse("the staged file was discarded", refused.leftOnDisk)
        assertTrue("the earlier archive is still in place", store.archiveFile("tiny").isFile)
        assertArrayEquals(
            "the earlier archive is byte for byte unchanged",
            archiveBefore,
            store.archiveFile("tiny").readBytes(),
        )
        assertFilesHold(store, TarFixtures.TINY_FILES, withStamp = true) { TarFixtures.contentOf(it, 48) }
        assertTrue("the blocked backup path is still there", blocked.isDirectory)
        assertFalse("the staged file is gone", File(store.stagingDirectory(), "model.archive").exists())
    }
}