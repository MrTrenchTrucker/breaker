package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A re-install whose removal of the earlier unpacked files fails PART WAY: the remove function
 * deletes one file and then answers false, which is what `File.deleteRecursively` does when one
 * deletion fails. The archive is restored from the backup, but the files are NOT: `files/` is left
 * holding three of the four names, `isExtracted` is false and the loader answers `NOT_INSTALLED`
 * until a re-install heals it.
 *
 * This is the limit the card states in the same sentence as the claim (pairs E1 and E4): a failed
 * removal restores the previous ARCHIVE, not necessarily the previous FILES. Restoring an archive
 * does not restore files that were already deleted.
 *
 * `ModelInstallerRemoveRollbackTest` pins a removal refused outright (`remove = { false }`, nothing
 * deleted), where the earlier model does stay complete. This file pins the part-way case that one
 * cannot reach. The healing path itself is pinned by the protected base test
 * `ModelInstallerUnpackReinstallTest`, "a model installed without files is healed by installing again".
 */
class ModelInstallerPartwayRemovalTest : ModelInstallerUnpackFixture() {

    private object IdleRecognizer : SherpaRecognizer {
        override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript = SherpaTranscript("", emptyList(), "en")

        override fun release() {}
    }

    private val second = TarFixtures.tinyArchive { name -> TarFixtures.contentOf("v2-$name", 48) }

    private val stagedPath = ".staging/model.archive"

    private val backupPath = ".staging/tiny" + LocalModelStore.BACKUP_SUFFIX

    /**
     * A remove function that deletes one unpacked name and then fails, once. Later calls remove the
     * whole directory, so a healing re-install can succeed. [archiveAtRemoval] records the bytes of
     * the archive at the moment the removal runs, which is what separates the new order from the old.
     */
    private fun partwayRemoval(
        probe: LocalModelStore,
        archiveAtRemoval: MutableList<ByteArray>,
        deleted: MutableList<String>,
    ): (File) -> Boolean {
        var calls = 0
        return { dir ->
            calls++
            if (calls == 1) {
                archiveAtRemoval.add(probe.archiveFile("tiny").readBytes())
                val victim = TarFixtures.TINY_FILES.first { File(dir, it).isFile }
                deleted.add(victim)
                assertTrue("fixture: the part-way removal deletes one file", File(dir, victim).delete())
                false
            } else {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun `a removal that deletes one file and then fails leaves the archive restored and the files partial`() {
        val root = tmp.newFolder()
        val stagedFile = File(root, stagedPath)
        val discarded = ArrayList<File>()
        val archiveAtRemoval = ArrayList<ByteArray>()
        val deleted = ArrayList<String>()
        val probe = LocalModelStore(root)
        val store = LocalModelStore(
            root,
            remove = partwayRemoval(probe, archiveAtRemoval, deleted),
            discard = { file ->
                discarded.add(file)
                file.delete()
            },
        )
        installedOf(run(tinyBytes, store))
        val archiveBefore = store.archiveFile("tiny").readBytes()
        assertTrue("fixture: the earlier install unpacked", store.isExtracted("tiny"))
        assertTrue("fixture: a first install asks for no removal", archiveAtRemoval.isEmpty())

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

        // The archive IS restored, byte for byte. Without the restore the archive path would be
        // empty: the new archive has gone back to the staging path and the backup is still beside it.
        assertTrue("the earlier archive is back in place", store.archiveFile("tiny").isFile)
        assertArrayEquals(
            "the earlier archive is byte for byte unchanged",
            archiveBefore,
            store.archiveFile("tiny").readBytes(),
        )

        // The files are NOT restored: one name is gone and stays gone.
        assertEquals("exactly one name was deleted", 1, deleted.size)
        val expected = (TarFixtures.TINY_FILES - deleted.single()) + UnpackedStamp.FILE_NAME
        assertEquals(
            "files/ holds the three surviving names and the stamp, not all four",
            expected.sorted(),
            store.extractedDirectory("tiny").list()!!.sorted(),
        )
        assertFalse("a partly removed files/ is not extracted", store.isExtracted("tiny"))

        // The documented aftermath: the loader answers not-installed and keeps the archive.
        val created = ArrayList<SherpaModel>()
        val factory = SherpaRecognizerFactory { model ->
            created.add(model)
            IdleRecognizer
        }
        val loader = ModelLoader(store, factory, { if (it == "tiny") entry() else null })
        assertTrue("the archive still looks installed", store.isInstalled("tiny"))
        val before = loader.load("tiny")
        assertTrue("before: $before", before is ModelLoader.LoadResult.Refused)
        assertEquals(ModelLoader.Refusal.NOT_INSTALLED, (before as ModelLoader.LoadResult.Refused).refusal)
        assertTrue("no recognizer was created", created.isEmpty())

        assertEquals(
            "the new archive went back to the staging path and was discarded",
            listOf(stagedFile),
            discarded,
        )
        assertFalse("the staged path is empty", stagedFile.exists())
        assertFalse("no backup is left behind", File(root, backupPath).exists())

        // Installing again heals it, exactly as the protected base test pins for a model with no files.
        installedOf(run(tinyBytes, store))
        val after = loader.load("tiny")
        assertTrue("after: $after", after is ModelLoader.LoadResult.Ready)
        assertEquals(store.extractedDirectory("tiny"), (after as ModelLoader.LoadResult.Ready).model.directory)
        assertEquals(1, created.size)
    }

    @Test
    fun `the part-way removal runs while the new archive is in place`() {
        val root = tmp.newFolder()
        val archiveAtRemoval = ArrayList<ByteArray>()
        val deleted = ArrayList<String>()
        val probe = LocalModelStore(root)
        val store = LocalModelStore(root, remove = partwayRemoval(probe, archiveAtRemoval, deleted))
        installedOf(run(tinyBytes, store))

        refusedOf(run(second, store))

        // RED on the unfixed base: there the removal ran BEFORE the staged move, so the archive at
        // that moment was still the earlier one. Only the new order puts the new archive in place first.
        assertEquals("the removal ran exactly once", 1, archiveAtRemoval.size)
        assertArrayEquals(
            "the archive in place during the removal is the NEW one",
            second,
            archiveAtRemoval.single(),
        )
        assertFalse("and it is not the earlier one", archiveAtRemoval.single().contentEquals(tinyBytes))
    }
}
