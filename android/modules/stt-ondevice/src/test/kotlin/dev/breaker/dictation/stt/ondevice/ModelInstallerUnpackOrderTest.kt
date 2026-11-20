package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The order of a re-install, the half the re-install tests do not cover: the earlier unpacked
 * files are removed only AFTER the staged archive has been moved into the archive path, and they
 * are gone before the new archive is unpacked.
 *
 * Both pins use seams that exist: the store's own `remove` function, which
 * [LocalModelStore.removeExtracted] calls, and the extractor's `open` function, which
 * [ModelExtractor] calls once at the start of an unpack.
 */
class ModelInstallerUnpackOrderTest : ModelInstallerUnpackFixture() {

    private val second = TarFixtures.tinyArchive { name -> TarFixtures.contentOf("v2-$name", 48) }

    @Test
    fun `the earlier unpacked files are removed after the staged archive is in place`() {
        val root = tmp.newFolder()
        val archivePath = File(root, "tiny/model.archive")
        val asked = ArrayList<File>()
        var archiveAtRemoval: ByteArray? = null
        val store = LocalModelStore(
            root,
            remove = { file ->
                asked.add(file)
                archiveAtRemoval = archivePath.readBytes()
                file.deleteRecursively()
            },
        )
        installedOf(run(tinyBytes, store))
        assertTrue("fixture: the first install asks for nothing", asked.isEmpty())
        assertTrue("fixture: the earlier install unpacked", store.isExtracted("tiny"))

        installedOf(run(second, store))

        assertEquals(
            "the earlier unpacked directory is removed, and nothing else",
            listOf(store.extractedDirectory("tiny")),
            asked,
        )
        assertArrayEquals(
            "the archive already holds the new bytes when the earlier files are removed",
            second,
            checkNotNull(archiveAtRemoval) { "fixture: the removal must have been asked for" },
        )
        assertFilesHold(store, TarFixtures.TINY_FILES, withStamp = true) { TarFixtures.contentOf("v2-$it", 48) }
    }

    @Test
    fun `the earlier unpacked files are gone before the new archive is unpacked`() {
        val store = newStore()
        installedOf(run(tinyBytes, store))
        assertTrue("fixture: the first install unpacked", store.isExtracted("tiny"))

        val seen = ArrayList<String>()
        val extractor = ModelExtractor(
            open = { file ->
                seen.add(
                    "extracted=${store.isExtracted("tiny")}" +
                        " newArchive=${store.archiveFile("tiny").readBytes().contentEquals(second)}",
                )
                file.inputStream()
            },
        )
        val installed = ModelInstaller(
            store,
            ArchiveFetcher(second),
            NoDebugSink,
            extractor,
            usableSpace = { Long.MAX_VALUE },
        ).install(entry("tiny", Fixtures.independentSha256(second), second.size.toLong()))

        installedOf(installed)
        assertEquals("the archive is opened once", 1, seen.size)
        assertEquals(
            "when the unpack starts the earlier files are gone and the archive is the new one",
            "extracted=false newArchive=true",
            seen.single(),
        )
    }
}