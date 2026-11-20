package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A re-install whose removal of the earlier unpacked files fails AFTER the staged archive has been
 * moved into place. The staged move must not leave the phone holding the new archive with the old
 * files: the new archive goes back to the staging path, the backup is renamed back over the archive
 * path, the staged file is discarded, and the install is refused with MOVE_FAILED. The earlier model
 * stays complete here because the removal deletes nothing before failing; a removal that deletes one
 * file and then fails is a different case, pinned by ModelInstallerPartwayRemovalTest.
 *
 * The order is what makes this reachable: when the removal happens before the staged move, a failed
 * removal never has to undo anything.
 */
class ModelInstallerRemoveRollbackTest : ModelInstallerUnpackFixture() {

    private val second = TarFixtures.tinyArchive { name -> TarFixtures.contentOf("v2-$name", 48) }

    private val stagedPath = ".staging/model.archive"

    private val backupPath = ".staging/tiny" + LocalModelStore.BACKUP_SUFFIX

    @Test
    fun `a failed removal after the swap restores the earlier archive and discards the new one`() {
        val root = tmp.newFolder()
        val stagedFile = File(root, stagedPath)
        val discarded = ArrayList<File>()
        var discardedBytes: ByteArray? = null
        val store = LocalModelStore(
            root,
            remove = { false },
            discard = { file ->
                discarded.add(file)
                discardedBytes = file.readBytes()
                file.delete()
            },
        )
        installedOf(run(tinyBytes, store))
        val archiveBefore = store.archiveFile("tiny").readBytes()
        assertTrue("fixture: the earlier install unpacked", store.isExtracted("tiny"))
        assertTrue("fixture: nothing was discarded by the first install", discarded.isEmpty())

        val sink = ArrayList<String>()
        val refused = refusedOf(run(second, store, sink = sink))

        assertEquals(ModelInstaller.Refusal.MOVE_FAILED, refused.refusal)
        assertEquals(ModelMessages.SAVE_FAILED, refused.detail)
        assertFalse("the new archive was discarded, so nothing is left on disk", refused.leftOnDisk)
        assertEquals("one debug line", 1, sink.size)
        assertTrue(
            "the sink names the files that could not be removed: $sink",
            sink.single().contains("cannot remove the earlier unpacked files"),
        )
        assertTrue("the earlier archive is still in place", store.archiveFile("tiny").isFile)
        assertArrayEquals(
            "the earlier archive is byte for byte unchanged",
            archiveBefore,
            store.archiveFile("tiny").readBytes(),
        )
        assertFilesHold(store, TarFixtures.TINY_FILES, withStamp = true) { TarFixtures.contentOf(it, 48) }
        assertEquals(
            "the new archive went back to the staging path and was discarded",
            listOf(stagedFile),
            discarded,
        )
        assertArrayEquals(
            "what was discarded is the new archive",
            second,
            checkNotNull(discardedBytes) { "fixture: one file must have been discarded" },
        )
        assertFalse("the staged path is empty", stagedFile.exists())
        assertFalse("no backup is left behind", File(root, backupPath).exists())
    }

    @Test
    fun `a failed removal that also cannot discard is reported as left on disk`() {
        val root = tmp.newFolder()
        val store = LocalModelStore(root, remove = { false }, discard = { false })
        installedOf(run(tinyBytes, store))
        val archiveBefore = store.archiveFile("tiny").readBytes()
        assertTrue("fixture: the earlier install unpacked", store.isExtracted("tiny"))

        val refused = refusedOf(run(second, store))

        assertEquals(ModelInstaller.Refusal.MOVE_FAILED, refused.refusal)
        assertTrue("the new archive could not be discarded", refused.leftOnDisk)
        assertEquals(ModelMessages.SAVE_FAILED + ModelMessages.COULD_NOT_DELETE, refused.detail)
        assertTrue("the earlier archive is still in place", store.archiveFile("tiny").isFile)
        assertArrayEquals(
            "the earlier archive is byte for byte unchanged",
            archiveBefore,
            store.archiveFile("tiny").readBytes(),
        )
        assertFilesHold(store, TarFixtures.TINY_FILES, withStamp = true) { TarFixtures.contentOf(it, 48) }
        assertArrayEquals(
            "the new archive is still in the staging path",
            second,
            File(root, stagedPath).readBytes(),
        )
        assertFalse("no backup is left behind", File(root, backupPath).exists())
    }
}