package dev.breaker.dictation.wiring

import dev.breaker.shared.models.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Protects the default of the download lookup: a downloader built without one reads the registry. */
internal class ModelDownloaderDefaultLookupTest {
    @Test
    fun `a downloader built without a lookup hands the registry entry of the selected id to the installer`() {
        val background = ModelQueueBackground()
        val main = ModelQueueMain()
        val installer = ModelFakeInstaller()
        val downloader = ModelDownloader(
            installer = installer,
            selectedId = { "small" },
            background = background,
            main = main,
            notice = ModelRecordingNotice(),
        )
        downloader.requestDownload()
        background.runAll()
        main.runAll()
        val expected = ModelRegistry.byId("small")
        assertNotNull("app: the registry should know the small model", expected)
        val handed = installer.entries.single()
        assertSame("app: the installer should get the registry's own small entry", expected, handed)
        assertEquals("app: the address should be the registry's", expected?.url, handed.url)
        assertEquals("app: the checksum should be the registry's", expected?.sha256, handed.sha256)
    }
}
