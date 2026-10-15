package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The fixed model of the stamp tests: one verified archive, four unpacked files, one pin. */
internal object StampFixture {
    const val MODEL: String = "tiny"
    val BYTES: ByteArray = "test model bytes".toByteArray()
    val DIGEST: String = Fixtures.independentSha256(BYTES)
    val FILES: List<String> = TarFixtures.TINY_FILES
    val ENTRY: ModelEntry =
        ModelEntry(MODEL, ModelFamily.SHERPA_ONNX, "https://example.com/model", DIGEST, 1, "Apache-2.0", false)
}

/** What the loader asked of its three counted seams: the archive check, the file digest and the engine creation. */
internal class StampCalls {
    var verify: Int = 0
    var digest: Int = 0
    var create: Int = 0
}

/** A recognizer that decodes nothing; the loader only hands it back. */
private object StampRecognizer : SherpaRecognizer {
    override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript = SherpaTranscript("", emptyList(), "en")

    override fun release() {}
}

/** Counts each engine creation and returns a recognizer that decodes nothing. */
internal class StampFactory(private val calls: StampCalls) : SherpaRecognizerFactory {
    override fun create(model: SherpaModel): SherpaRecognizer {
        calls.create++
        return StampRecognizer
    }
}

/** A store and a loader whose three seams are counted. */
internal class StampRig(
    root: File,
    remove: (File) -> Boolean = { it.deleteRecursively() },
    discard: (File) -> Boolean = { it.delete() },
) {
    val store = LocalModelStore(root, remove, discard)
    val calls = StampCalls()
    val debug = ArrayList<String>()
    val loader = ModelLoader(
        store = store,
        factory = StampFactory(calls),
        lookup = { requested -> if (requested == StampFixture.MODEL) StampFixture.ENTRY else null },
        debug = ModelDebugSink { debug.add(it) },
        verify = { file, pin, checksums, archiveName ->
            calls.verify++
            ModelIntegrity.verify(file, pin, checksums, archiveName)
        },
        digestOf = { file ->
            calls.digest++
            ModelIntegrity.sha256(file)
        },
    )
}

/** Writes the archive, its verified marker and the stored checksums of the fixed model. */
internal fun seedStampArchive(store: LocalModelStore, archive: ByteArray = StampFixture.BYTES) {
    Fixtures.writeBytes(store.archiveFile(StampFixture.MODEL), archive)
    store.markVerified(StampFixture.MODEL, StampFixture.DIGEST)
    store.storeChecksums(StampFixture.MODEL, "model.archive\t${StampFixture.DIGEST}")
}

internal fun unpackedFileOf(store: LocalModelStore, name: String): File =
    File(store.extractedDirectory(StampFixture.MODEL), name)

internal fun stampFileOf(store: LocalModelStore): File =
    File(store.extractedDirectory(StampFixture.MODEL), UnpackedStamp.FILE_NAME)

/** Writes the four unpacked files, each with its own content. */
internal fun seedStampUnpacked(store: LocalModelStore) {
    for (name in StampFixture.FILES) {
        Fixtures.writeBytes(unpackedFileOf(store, name), "content of $name".toByteArray())
    }
}

/** Writes the stamp measured from the files as they are now, with the real digests. */
internal fun writeStampFromFiles(store: LocalModelStore) {
    val measured = UnpackedStamp.measure(
        store.extractedDirectory(StampFixture.MODEL),
        StampFixture.FILES,
        StampFixture.DIGEST,
    ) { ModelIntegrity.sha256(it) }
    store.writeStamp(StampFixture.MODEL, checkNotNull(measured) { "fixture: the unpacked files must measure" })
}

/**
 * A stamp text written by the test itself: the digests come from [Fixtures.independentSha256]
 * and the sizes and modified times from the files. [sizeOff] adds one byte to the recorded
 * size of that one file.
 */
internal fun handWrittenStamp(store: LocalModelStore, sizeOff: String? = null): String {
    val lines = ArrayList<String>()
    lines.add("unpacked-stamp 1")
    lines.add("archive ${StampFixture.DIGEST}")
    for (name in StampFixture.FILES) {
        val file = unpackedFileOf(store, name)
        val extra = if (name == sizeOff) 1L else 0L
        val sha = Fixtures.independentSha256(file.readBytes())
        lines.add("file $name ${file.length() + extra} ${file.lastModified()} $sha")
    }
    lines.add("end")
    return lines.joinToString("\n", postfix = "\n")
}

internal fun stampRefusedOf(result: ModelLoader.LoadResult): ModelLoader.LoadResult.Refused {
    assertTrue("expected a refusal but got $result", result is ModelLoader.LoadResult.Refused)
    return result as ModelLoader.LoadResult.Refused
}

internal fun stampReadyOf(result: ModelLoader.LoadResult): ModelLoader.LoadResult.Ready {
    assertTrue("expected a loaded model but got $result", result is ModelLoader.LoadResult.Ready)
    return result as ModelLoader.LoadResult.Ready
}

class ModelLoaderStampTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a matching stamp loads with no archive hash and no file hash`() {
        val rig = StampRig(tmp.newFolder())
        seedStampArchive(rig.store)
        seedStampUnpacked(rig.store)
        writeStampFromFiles(rig.store)

        val ready = stampReadyOf(rig.loader.load(StampFixture.MODEL))

        assertEquals("the archive must not be hashed", 0, rig.calls.verify)
        assertEquals("no unpacked file may be hashed", 0, rig.calls.digest)
        assertEquals("the engine is created once", 1, rig.calls.create)
        assertEquals("the digest is the stamp's archive digest", StampFixture.DIGEST, ready.model.digest)
    }

    @Test
    fun `a shorter unpacked file is refused before the engine is opened and the file is deleted`() {
        val rig = StampRig(tmp.newFolder())
        seedStampArchive(rig.store)
        seedStampUnpacked(rig.store)
        writeStampFromFiles(rig.store)
        val joiner = unpackedFileOf(rig.store, StampFixture.FILES[2])
        joiner.writeBytes(ByteArray(1))

        val refused = stampRefusedOf(rig.loader.load(StampFixture.MODEL))

        assertEquals("the refusal", ModelLoader.Refusal.NOT_INSTALLED, refused.refusal)
        assertEquals("the sentence", ModelMessages.MODEL_NOT_INSTALLED, refused.detail)
        assertEquals("the engine must not be created", 0, rig.calls.create)
        assertEquals("the archive must not be hashed", 0, rig.calls.verify)
        assertEquals("no unpacked file may be hashed", 0, rig.calls.digest)
        assertFalse("the shorter file is deleted", joiner.exists())
        assertTrue("the archive stays", rig.store.archiveFile(StampFixture.MODEL).isFile)
    }

    @Test
    fun `a longer unpacked file is refused the same way`() {
        val rig = StampRig(tmp.newFolder())
        seedStampArchive(rig.store)
        seedStampUnpacked(rig.store)
        writeStampFromFiles(rig.store)
        val joiner = unpackedFileOf(rig.store, StampFixture.FILES[2])
        joiner.writeBytes(joiner.readBytes() + "extra".toByteArray())

        val refused = stampRefusedOf(rig.loader.load(StampFixture.MODEL))

        assertEquals("the refusal", ModelLoader.Refusal.NOT_INSTALLED, refused.refusal)
        assertEquals("the sentence", ModelMessages.MODEL_NOT_INSTALLED, refused.detail)
        assertEquals("the engine must not be created", 0, rig.calls.create)
        assertEquals("the archive must not be hashed", 0, rig.calls.verify)
        assertEquals("no unpacked file may be hashed", 0, rig.calls.digest)
        assertFalse("the longer file is deleted", joiner.exists())
    }

    @Test
    fun `a missing unpacked file is refused as not installed and nothing is hashed`() {
        val rig = StampRig(tmp.newFolder())
        seedStampArchive(rig.store)
        seedStampUnpacked(rig.store)
        writeStampFromFiles(rig.store)
        assertTrue("fixture: the joiner is removed", unpackedFileOf(rig.store, StampFixture.FILES[2]).delete())

        val refused = stampRefusedOf(rig.loader.load(StampFixture.MODEL))

        assertEquals("the refusal", ModelLoader.Refusal.NOT_INSTALLED, refused.refusal)
        assertEquals("the sentence", ModelMessages.MODEL_NOT_INSTALLED, refused.detail)
        assertEquals("the engine must not be created", 0, rig.calls.create)
        assertEquals("the archive must not be hashed", 0, rig.calls.verify)
        assertEquals("no unpacked file may be hashed", 0, rig.calls.digest)
        assertTrue("the other unpacked files stay", unpackedFileOf(rig.store, StampFixture.FILES[0]).isFile)
        assertTrue("the archive stays", rig.store.archiveFile(StampFixture.MODEL).isFile)
    }

    @Test
    fun `a changed modified time with intact content loads after hashing the four files and not the archive`() {
        val rig = StampRig(tmp.newFolder())
        seedStampArchive(rig.store)
        seedStampUnpacked(rig.store)
        writeStampFromFiles(rig.store)
        val joiner = unpackedFileOf(rig.store, StampFixture.FILES[2])
        assertTrue("fixture: the modified time moves", joiner.setLastModified(joiner.lastModified() - 60_000L))

        val ready = stampReadyOf(rig.loader.load(StampFixture.MODEL))

        assertEquals("the archive must not be hashed", 0, rig.calls.verify)
        assertEquals("the four unpacked files are hashed", 4, rig.calls.digest)
        assertEquals("the engine is created once", 1, rig.calls.create)
        assertEquals("the digest is the stamp's archive digest", StampFixture.DIGEST, ready.model.digest)
        val refreshed = checkNotNull(rig.store.readStamp(StampFixture.MODEL)) { "the stamp must still be there" }
        assertEquals(
            "the stored times are refreshed",
            UnpackedStamp.Check.Match,
            refreshed.check(rig.store.extractedDirectory(StampFixture.MODEL)),
        )
    }

    @Test
    fun `a changed modified time with a flipped byte of the same size is refused before the engine is opened`() {
        val rig = StampRig(tmp.newFolder())
        seedStampArchive(rig.store)
        seedStampUnpacked(rig.store)
        writeStampFromFiles(rig.store)
        val joiner = unpackedFileOf(rig.store, StampFixture.FILES[2])
        val original = joiner.lastModified()
        val bytes = joiner.readBytes()
        bytes[0] = (bytes[0] + 1).toByte()
        joiner.writeBytes(bytes)
        assertTrue("fixture: the modified time differs from the stamp", joiner.setLastModified(original + 60_000L))

        val refused = stampRefusedOf(rig.loader.load(StampFixture.MODEL))

        assertEquals("the refusal", ModelLoader.Refusal.NOT_INSTALLED, refused.refusal)
        assertEquals("the engine must not be created", 0, rig.calls.create)
        assertEquals("the archive must not be hashed", 0, rig.calls.verify)
        assertFalse("the changed file is deleted", joiner.exists())
        assertTrue(
            "the technical line names the changed file: ${rig.debug}",
            rig.debug.single().contains(StampFixture.FILES[2]),
        )
    }

    @Test
    fun `a stamp written by hand from independently computed values is accepted`() {
        val rig = StampRig(tmp.newFolder())
        seedStampArchive(rig.store)
        seedStampUnpacked(rig.store)
        stampFileOf(rig.store).writeText(handWrittenStamp(rig.store))

        val ready = stampReadyOf(rig.loader.load(StampFixture.MODEL))

        assertEquals("the archive must not be hashed", 0, rig.calls.verify)
        assertEquals("no unpacked file may be hashed", 0, rig.calls.digest)
        assertEquals("the engine is created once", 1, rig.calls.create)
        assertEquals("the digest is the stamp's archive digest", StampFixture.DIGEST, ready.model.digest)
    }

    @Test
    fun `a hand written stamp with one wrong size is refused before the engine is opened`() {
        val rig = StampRig(tmp.newFolder())
        seedStampArchive(rig.store)
        seedStampUnpacked(rig.store)
        stampFileOf(rig.store).writeText(handWrittenStamp(rig.store, sizeOff = StampFixture.FILES[2]))
        val joiner = unpackedFileOf(rig.store, StampFixture.FILES[2])

        val refused = stampRefusedOf(rig.loader.load(StampFixture.MODEL))

        assertEquals("the refusal", ModelLoader.Refusal.NOT_INSTALLED, refused.refusal)
        assertEquals("the engine must not be created", 0, rig.calls.create)
        assertEquals("the archive must not be hashed", 0, rig.calls.verify)
        assertFalse("the file with the wrong size is deleted", joiner.exists())
    }
}
