package dev.breaker.dictation.wiring

import dev.breaker.shared.models.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The size in the downloading sentence is the size of the model the request is
 * actually downloading: the selected registry entry's own mebibytes, converted,
 * not one number for every model. Driven through the real
 * [ModelDownloader.requestDownload] with a tiny model and then a medium one.
 */
internal class ModelDownloadNoticeSizeTest {

    private val background = ModelQueueBackground()
    private val main = ModelQueueMain()
    private val notice = ModelRecordingNotice()
    private var selected = ModelRegistry.TINY.id

    private val downloader = ModelDownloader(
        installer = ModelFakeInstaller(),
        selectedId = { selected },
        background = background,
        main = main,
        notice = notice,
    )

    @Test
    fun `the notice sentence carries the size of the model actually being downloaded`() {
        downloader.requestDownload { }
        assertEquals(
            "app: the notice is shown at the request with the tiny model's own converted size",
            listOf("downloading:" + ModelSentences.downloadingFor(ModelRegistry.TINY.sizeMb)),
            notice.log,
        )
        background.runAll()
        main.runAll()
        assertEquals(
            "app: the first request ends in done",
            listOf("downloading:" + ModelSentences.downloadingFor(ModelRegistry.TINY.sizeMb), "done"),
            notice.log,
        )

        selected = ModelRegistry.MEDIUM.id
        downloader.requestDownload { }
        background.runAll()
        main.runAll()
        assertEquals(
            "app: each request shows the size of the model selected at that time",
            listOf(
                "downloading:" + ModelSentences.downloadingFor(ModelRegistry.TINY.sizeMb),
                "done",
                "downloading:" + ModelSentences.downloadingFor(ModelRegistry.MEDIUM.sizeMb),
                "done",
            ),
            notice.log,
        )
        assertTrue("app: the tiny model's sentence shows its converted size of 128", notice.log[0].contains("128"))
        assertTrue("app: the medium model's sentence shows its converted size of 1906", notice.log[2].contains("1906"))
        assertFalse("app: no sentence shows the small model's size of 366", notice.log.any { it.contains("366") })
    }
}
