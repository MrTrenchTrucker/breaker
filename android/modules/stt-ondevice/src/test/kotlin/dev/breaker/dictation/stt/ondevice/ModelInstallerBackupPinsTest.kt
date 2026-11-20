package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Three backup answers the module card states as fact, pinned so a change that breaks one fails here:
 * a successful install reports no stamp failure, a successful re-install leaves no backup behind, and
 * a re-install clears a backup an earlier crash left in the way.
 *
 * The third test also observes WHEN the stale backup is cleared. File.renameTo onto an
 * existing regular file succeeds on some file systems and fails on others, so "the install succeeded
 * and the backup is gone" does not by itself distinguish clearing the stale file first from
 * overwriting it during the rename-aside. The store's discard seam records the archive bytes at each
 * discard of the backup path: the stale clear runs while the archive still holds the earlier bytes,
 * and the success-path drop runs after the new archive is in place.
 */
class ModelInstallerBackupPinsTest : ModelInstallerUnpackFixture() {

    private val second = TarFixtures.tinyArchive { name -> TarFixtures.contentOf("v2-$name", 48) }

    @Test
    fun `a successful first install reports no stamp failure`() {
        val installed = installedOf(run(tinyBytes, newStore()))
        assertFalse(
            "the stamp was written, so the install must not report a stamp failure",
            installed.stampFailed,
        )
    }

    @Test
    fun `a successful re-install leaves no backup behind`() {
        val store = newStore()
        installedOf(run(tinyBytes, store))
        assertFalse("fixture: a first install leaves no backup", store.backupFile("tiny").exists())

        installedOf(run(second, store))

        assertFalse("the backup is dropped once the re-install succeeds", store.backupFile("tiny").exists())
        assertArrayEquals("the new archive is in place", second, store.archiveFile("tiny").readBytes())
    }

    @Test
    fun `a re-install clears a stale backup file before it moves the earlier archive aside`() {
        val stale = byteArrayOf(9, 9, 9, 9)
        // The archive bytes seen at each discard of the backup path, in call order.
        val archiveBytesAtBackupDiscard = ArrayList<ByteArray>()
        var observed: LocalModelStore? = null
        val store = LocalModelStore(
            tmp.newFolder(),
            discard = { file ->
                val seen = observed
                if (seen != null && file == seen.backupFile("tiny")) {
                    val archive = seen.archiveFile("tiny")
                    archiveBytesAtBackupDiscard.add(if (archive.isFile) archive.readBytes() else ByteArray(0))
                }
                file.delete()
            },
        )
        observed = store

        installedOf(run(tinyBytes, store))
        assertTrue(
            "fixture: a first install never discards a backup",
            archiveBytesAtBackupDiscard.isEmpty(),
        )
        Fixtures.writeBytes(store.backupFile("tiny"), stale)
        assertTrue("fixture: a stale backup file is in the way", store.backupFile("tiny").isFile)

        installedOf(run(second, store))

        assertFalse("the stale backup is gone", store.backupFile("tiny").exists())
        assertArrayEquals(
            "the new archive is in place, not the stale bytes",
            second,
            store.archiveFile("tiny").readBytes(),
        )
        assertTrue(
            "the stale backup was cleared while the earlier archive was still in place",
            archiveBytesAtBackupDiscard.any { it.contentEquals(tinyBytes) },
        )
    }
}