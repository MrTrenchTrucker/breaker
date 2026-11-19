package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import dev.breaker.shared.models.ModelFiles
import dev.breaker.shared.models.ModelKind
import dev.breaker.shared.models.ModelLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The entry is the one source of the profile: ONLY the installer is given the lookup that knows the fake entry; the
 * extractor and the store keep the real registry, which has never heard of its id. The id exists nowhere in code.
 * UNCOMPILED: nothing here was compiled or run; every killer is hand traced (see the notes of the piece).
 */
class ModelInstallerOnlyLookupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val names = ModelFiles("enc.y.onnx", "dec.y.onnx", "join.y.onnx", "toks.txt")
    private val limits = ModelLimits(
        maxEntryBytes = 1_048_576L,
        maxWrittenBytes = 2_097_152L,
        maxStreamBytes = 4_194_304L,
        maxEntries = 16,
        maxDepth = 3,
    )
    private val top = "zz-only-top"

    private fun archive(): ByteArray = TarFixtures.bz2(
        TarFixtures.archiveOf(
            TarFixtures.dir(top),
            TarFixtures.file("$top/${names.tokens}", "t".toByteArray()),
            TarFixtures.file("$top/README.md", "r".toByteArray()),
            TarFixtures.file("$top/${names.joiner}", "j".toByteArray()),
            TarFixtures.file("$top/${names.decoder}", "d".toByteArray()),
            TarFixtures.file("$top/${names.encoder}", "e".toByteArray()),
        ),
    )

    private fun entry(archive: ByteArray) = ModelEntry(
        id = "zz-only-installer-model", family = ModelFamily.SHERPA_ONNX, url = "https://example.com/m",
        sha256 = Fixtures.independentSha256(archive), sizeMb = 1, licence = "test", hosted = false,
        kind = ModelKind.OFFLINE, sizeBytes = archive.size.toLong(), modelType = "nemo_transducer", files = names, limits = limits, language = "",
        offered = false,
    )

    private val allFour = listOf(names.encoder, names.decoder, names.joiner, names.tokens)

    /** The installer alone knows [fake]; the store and the extractor are the real ones with the real registry. */
    private class Rig(val store: LocalModelStore, val result: ModelInstaller.InstallResult) {
        fun unpackedNames(id: String): List<String> = store.extractedDirectory(id).list()!!.sorted()
    }

    private fun install(fake: ModelEntry, bytes: ByteArray, known: ModelEntry = fake): Rig {
        val store = LocalModelStore(tmp.newFolder())
        val installer = ModelInstaller(
            store, ArchiveFetcher(bytes), extractor = ModelExtractor(), usableSpace = { Long.MAX_VALUE },
            lookup = { id -> if (id == known.id) known else null },
        )
        return Rig(store, installer.install(fake))
    }

    @Test
    fun `an entry only the installer knows installs unpacks and is found extracted`() {
        val bytes = archive()
        val fake = entry(bytes)
        val rig = install(fake, bytes)
        // Killer of an extractor that resolves by id again: the real registry has no profile, the unpack is refused,
        // so the result is Refused(EXTRACT_REFUSED) and this equality fails (AssertionError: the test speaks).
        assertEquals(ModelInstaller.InstallResult.Installed(fake.id, fake.sha256), rig.result)
        // Killer of an allow-list that is not the entry's names: README or a wrong name would show here.
        assertEquals((allFour + UnpackedStamp.FILE_NAME).sorted(), rig.unpackedNames(fake.id))
        val profile = ExtractionProfiles.forEntry(fake)
        assertNotNull(profile)
        // Killer of a store that resolves by id inside the profile overload: it would answer false for this id.
        assertTrue(rig.store.isExtracted(fake.id, profile!!))
        // Control: the store's own by-id lookup cannot see this id, so the line above is answered by the profile.
        assertFalse(rig.store.isExtracted(fake.id))
    }

    @Test
    fun `an unpacked file of an entry only the installer knows is deleted through the profile it is given`() {
        val bytes = archive()
        val fake = entry(bytes)
        val rig = install(fake, bytes)
        val profile = ExtractionProfiles.forEntry(fake)!!
        val dir = rig.store.extractedDirectory(fake.id)
        assertTrue(File(dir, names.joiner).isFile)
        // Killer of a store that resolves by id inside the overload: it answers false and the file stays.
        assertTrue(rig.store.deleteUnpackedFile(fake.id, names.joiner, profile))
        assertFalse(File(dir, names.joiner).exists())
        val notInProfile = rig.store.deleteUnpackedFile(fake.id, names.tokens + ".x", profile)
        assertFalse("a file that is not in the profile is never deleted", notInProfile)
        assertTrue(File(dir, names.tokens).isFile)
        // Control: by id the real store has no profile for this id.
        assertFalse(rig.store.deleteUnpackedFile(fake.id, names.tokens))
        assertTrue(File(dir, names.tokens).isFile)
    }

    @Test
    fun `an entry without files or limits is resolved through the lookup the installer was given`() {
        val bytes = archive()
        val full = entry(bytes)
        val bare = full.copy(files = null, limits = null)
        val rig = install(bare, bytes, known = full)
        // Killer of a profile helper that reads only the entry: the bare entry has no profile, UNSUPPORTED_MODEL.
        assertEquals(ModelInstaller.InstallResult.Installed(full.id, full.sha256), rig.result)
        assertEquals((allFour + UnpackedStamp.FILE_NAME).sorted(), rig.unpackedNames(full.id))
    }

    @Test
    fun `an entry the installer lookup does not know and that carries no files is refused before a download`() {
        val bytes = archive()
        val bare = entry(bytes).copy(files = null, limits = null)
        val rig = install(bare, bytes, known = bare.copy(id = "zz-someone-else"))
        // Control for the test above: with no lookup answer and no own files there is no profile anywhere.
        val refused = rig.result as ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.UNSUPPORTED_MODEL, refused.refusal)
    }
}
