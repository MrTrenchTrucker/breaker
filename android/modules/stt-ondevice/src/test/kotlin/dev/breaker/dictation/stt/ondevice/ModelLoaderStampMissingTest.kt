package dev.breaker.dictation.stt.ondevice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The loader when there is no usable stamp, and the stamp rules that sit beside the
 * stamp path: the stored checksums, a failed delete and the first stamp write. The
 * helpers are the ones in ModelLoaderStampTest.kt.
 */
class ModelLoaderStampMissingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a missing stamp verifies the archive exactly once and then writes the stamp`() {
        val rig = StampRig(tmp.newFolder())
        seedStampArchive(rig.store)
        seedStampUnpacked(rig.store)

        stampReadyOf(rig.loader.load(StampFixture.MODEL))

        assertEquals("the archive is verified exactly once", 1, rig.calls.verify)
        assertEquals("the engine is created once", 1, rig.calls.create)
        val stamp = checkNotNull(rig.store.readStamp(StampFixture.MODEL)) { "the stamp must be written after the load" }
        assertTrue("the stamp is for the pin and the four names", stamp.isFor(StampFixture.DIGEST, StampFixture.FILES))
        assertEquals(
            "the recorded digests are the real digests of the files",
            StampFixture.FILES.map { Fixtures.independentSha256(unpackedFileOf(rig.store, it).readBytes()) },
            stamp.files.map { it.sha256 },
        )
    }

    @Test
    fun `a stamp that does not parse is treated as a missing stamp`() {
        val rig = StampRig(tmp.newFolder())
        seedStampArchive(rig.store)
        seedStampUnpacked(rig.store)
        stampFileOf(rig.store).writeText("unpacked-stamp 1\narchive ${StampFixture.DIGEST}\nfile torn")

        stampReadyOf(rig.loader.load(StampFixture.MODEL))

        assertEquals("the archive is verified, because the stamp was not trusted", 1, rig.calls.verify)
        assertEquals("the engine is created once", 1, rig.calls.create)
    }

    @Test
    fun `a stamp for another pin is treated as a missing stamp`() {
        val rig = StampRig(tmp.newFolder())
        seedStampArchive(rig.store)
        seedStampUnpacked(rig.store)
        val other = checkNotNull(
            UnpackedStamp.measure(
                rig.store.extractedDirectory(StampFixture.MODEL),
                StampFixture.FILES,
                Fixtures.OTHER_PIN,
            ) { ModelIntegrity.sha256(it) },
        ) { "fixture: the unpacked files must measure" }
        rig.store.writeStamp(StampFixture.MODEL, other)

        stampReadyOf(rig.loader.load(StampFixture.MODEL))

        assertEquals("the archive is verified, because the stamp is for another pin", 1, rig.calls.verify)
        assertEquals("the engine is created once", 1, rig.calls.create)
        val stamp = checkNotNull(rig.store.readStamp(StampFixture.MODEL)) { "the stamp must still be there" }
        assertTrue("the other pin's stamp is replaced by one for this pin", stamp.isFor(StampFixture.DIGEST, StampFixture.FILES))
    }

    @Test
    fun `a bad archive with a missing stamp is refused and no stamp is written`() {
        val rig = StampRig(tmp.newFolder(), remove = { false })
        seedStampArchive(rig.store, archive = "tampered".toByteArray())
        seedStampUnpacked(rig.store)
        assertFalse("fixture: no stamp before the load", stampFileOf(rig.store).exists())

        val refused = stampRefusedOf(rig.loader.load(StampFixture.MODEL))

        assertEquals("the refusal", ModelLoader.Refusal.VERIFICATION_REFUSED, refused.refusal)
        assertTrue("the model could not be deleted, so it is left on disk", refused.leftOnDisk)
        assertEquals("the archive is verified once", 1, rig.calls.verify)
        assertEquals("the engine must not be created", 0, rig.calls.create)
        assertFalse("no stamp is written for an archive that did not verify", stampFileOf(rig.store).exists())
    }

    @Test
    fun `a missing stamp with no unpacked files writes no stamp`() {
        val rig = StampRig(tmp.newFolder())
        seedStampArchive(rig.store)
        assertFalse("fixture: there are no unpacked files", rig.store.extractedDirectory(StampFixture.MODEL).exists())

        val refused = stampRefusedOf(rig.loader.load(StampFixture.MODEL))

        assertEquals("the refusal", ModelLoader.Refusal.NOT_INSTALLED, refused.refusal)
        assertEquals("the sentence", ModelMessages.MODEL_NOT_INSTALLED, refused.detail)
        assertEquals("the archive is verified once", 1, rig.calls.verify)
        assertEquals("the engine must not be created", 0, rig.calls.create)
        assertFalse(
            "no stamp file is written anywhere",
            rig.store.directoryFor(StampFixture.MODEL).walkTopDown().any { it.name == UnpackedStamp.FILE_NAME },
        )
    }

    @Test
    fun `a stamp that cannot be written does not fail the load`() {
        val rig = StampRig(tmp.newFolder())
        seedStampArchive(rig.store)
        seedStampUnpacked(rig.store)
        assertTrue("fixture: a folder sits where the stamp file goes", stampFileOf(rig.store).mkdirs())

        stampReadyOf(rig.loader.load(StampFixture.MODEL))

        assertEquals("the archive is verified once", 1, rig.calls.verify)
        assertEquals("the engine is created once", 1, rig.calls.create)
        assertTrue(
            "the failed write is reported to the debug sink: ${rig.debug}",
            rig.debug.contains("could not write the unpacked-file stamp"),
        )
    }

    @Test
    fun `a failing file that cannot be deleted is reported as left on disk`() {
        val rig = StampRig(tmp.newFolder(), discard = { false })
        seedStampArchive(rig.store)
        seedStampUnpacked(rig.store)
        writeStampFromFiles(rig.store)
        val joiner = unpackedFileOf(rig.store, StampFixture.FILES[2])
        joiner.writeBytes(ByteArray(1))

        val refused = stampRefusedOf(rig.loader.load(StampFixture.MODEL))

        assertEquals("the refusal", ModelLoader.Refusal.NOT_INSTALLED, refused.refusal)
        assertTrue("the delete failed, so the refusal is reported as left on disk", refused.leftOnDisk)
        assertTrue("the changed file is still on disk", joiner.exists())
    }

    @Test
    fun `the stored checksums are still required with a good stamp`() {
        val rig = StampRig(tmp.newFolder())
        seedStampArchive(rig.store)
        seedStampUnpacked(rig.store)
        writeStampFromFiles(rig.store)
        val checksums = File(rig.store.directoryFor(StampFixture.MODEL), LocalModelStore.CHECKSUMS_FILE)
        assertTrue("fixture: the stored checksums are removed", checksums.delete())

        val refused = stampRefusedOf(rig.loader.load(StampFixture.MODEL))

        assertEquals("the refusal", ModelLoader.Refusal.CHECKSUMS_UNREADABLE, refused.refusal)
        assertEquals("the sentence", ModelMessages.NOT_VERIFIED_YET, refused.detail)
        assertEquals("the archive must not be hashed", 0, rig.calls.verify)
        assertEquals("no unpacked file may be hashed", 0, rig.calls.digest)
        assertEquals("the engine must not be created", 0, rig.calls.create)
        assertTrue("the stamp stays", stampFileOf(rig.store).isFile)
    }
}
