package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The installer writes the unpacked-file stamp last, and only for an install that completed. */
class ModelInstallerStampTest : ModelInstallerUnpackFixture() {

    /** The four file names the profile of "tiny" opens, in role order. */
    private val tinyNames = ExtractionProfiles.forModel("tiny")!!.files

    /**
     * One install of [archive] into [store] with the real extractor. [listedDigest] is the digest the
     * checksum list gives for the archive; [digestOf] is the hash the stamp is measured with.
     */
    private fun install(
        store: LocalModelStore,
        archive: ByteArray = tinyBytes,
        listedDigest: String = Fixtures.independentSha256(archive),
        digestOf: (File) -> String? = ModelIntegrity::sha256,
    ): ModelInstaller.InstallResult {
        val fetcher = ArchiveFetcher(archive, "model.archive\t$listedDigest")
        return ModelInstaller(store, fetcher, NoDebugSink, Seams().extractor(), digestOf)
            .install(entry("tiny", Fixtures.independentSha256(archive)))
    }

    @Test
    fun `a good install writes a stamp that matches the unpacked files`() {
        val store = newStore()
        installedOf(install(store))
        val stamp = store.readStamp("tiny")
        assertNotNull("a stamp was written", stamp)
        assertEquals(tinyNames, stamp!!.files.map { it.name })
        val dir = store.extractedDirectory("tiny")
        for (file in stamp.files) {
            val onDisk = File(dir, file.name)
            assertEquals("size of ${file.name}", onDisk.length(), file.size)
            assertEquals("modified time of ${file.name}", onDisk.lastModified(), file.modifiedMillis)
            assertEquals("sha256 of ${file.name}", Fixtures.independentSha256(onDisk.readBytes()), file.sha256)
        }
    }

    @Test
    fun `the stamp carries the verified archive digest and is for the pin`() {
        val store = newStore()
        installedOf(install(store))
        val stamp = store.readStamp("tiny")
        assertNotNull("a stamp was written", stamp)
        assertEquals(tinyPin, stamp!!.archiveDigest)
        assertTrue("the stamp is for the pin", stamp.isFor(tinyPin, tinyNames))
        assertFalse("the stamp is not for another pin", stamp.isFor(Fixtures.OTHER_PIN, tinyNames))
    }

    @Test
    fun `the stamp is written after the unpack and not before`() {
        val store = newStore()
        val seen = ArrayList<String>()
        val spy: (File) -> String? = { file ->
            val stampThere = File(store.extractedDirectory("tiny"), UnpackedStamp.FILE_NAME).exists()
            seen.add("exists=${file.isFile} extracted=${store.isExtracted("tiny")} stamp=$stampThere")
            Fixtures.independentSha256(file.readBytes())
        }
        installedOf(install(store, digestOf = spy))
        assertEquals("the four unpacked files are measured, once each", 4, seen.size)
        assertEquals(List(4) { "exists=true extracted=true stamp=false" }, seen)
    }

    @Test
    fun `a download that fails the check writes no stamp and no unpacked folder`() {
        val control = newStore()
        installedOf(install(control))
        assertNotNull("control: the true digest writes a stamp", control.readStamp("tiny"))

        val store = newStore()
        val refused = refusedOf(install(store, listedDigest = "0".repeat(64)))
        assertEquals(ModelInstaller.Refusal.DIGEST_REFUSED, refused.refusal)
        assertNull("no stamp", store.readStamp("tiny"))
        assertFalse("no unpacked folder", store.extractedDirectory("tiny").exists())
    }

    @Test
    fun `an archive that cannot be unpacked writes no stamp`() {
        val store = newStore()
        val measured = ArrayList<String>()
        val refused = refusedOf(install(store, archive = TarFixtures.notBzip2(), digestOf = { measured.add(it.name); null }))
        assertEquals(ModelInstaller.Refusal.EXTRACT_REFUSED, refused.refusal)
        assertEquals("no unpacked file is measured", emptyList<String>(), measured)
        assertNull("no stamp", store.readStamp("tiny"))
        assertFalse("no unpacked folder", store.extractedDirectory("tiny").exists())
    }

    @Test
    fun `a stamp that cannot be measured deletes the model and refuses with the save sentence`() {
        val store = newStore()
        val unmeasurable: (File) -> String? = { file ->
            if (file.name == tinyNames[2]) null else Fixtures.independentSha256(file.readBytes())
        }
        val refused = refusedOf(install(store, digestOf = unmeasurable))
        assertEquals(ModelInstaller.Refusal.STAGING_FAILED, refused.refusal)
        assertEquals(ModelMessages.SAVE_FAILED, refused.detail)
        assertFalse("the delete worked, so nothing is left on disk", refused.leftOnDisk)
        assertFalse("the archive is gone", store.isInstalled("tiny"))
        assertFalse("the model folder is gone", store.directoryFor("tiny").exists())
    }

    @Test
    fun `a reinstall replaces the stamp`() {
        val store = newStore()
        installedOf(install(store))
        val first = store.readStamp("tiny")
        assertNotNull("the first install wrote a stamp", first)
        assertEquals(List(tinyNames.size) { 48L }, first!!.files.map { it.size })

        val second = TarFixtures.tinyArchive { TarFixtures.contentOf(it, 96) }
        installedOf(install(store, archive = second))
        val stamp = store.readStamp("tiny")
        assertNotNull("the reinstall wrote a stamp", stamp)
        assertEquals(tinyNames, stamp!!.files.map { it.name })
        assertEquals(List(tinyNames.size) { 96L }, stamp.files.map { it.size })
        val dir = store.extractedDirectory("tiny")
        assertEquals(
            "no stale file is left beside the new stamp",
            (tinyNames + UnpackedStamp.FILE_NAME).sorted(),
            dir.list()!!.sorted(),
        )
    }

    @Test
    fun `the install result is unchanged by the stamp`() {
        val store = newStore()
        assertEquals(ModelInstaller.InstallResult.Installed("tiny", tinyPin), install(store))
        assertNotNull("the stamp is written beside the result", store.readStamp("tiny"))
    }
}
