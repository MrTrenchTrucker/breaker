package dev.breaker.dictation.wiring

import dev.breaker.shared.models.ModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One progress call as the notice received it. */
private data class ProgressShown(val sentence: String, val doneMb: Int, val totalMb: Int)

/** A download notice that records every call into [log] and keeps the progress calls apart; it can throw from progress. */
private class ProgressNoticeFake(val log: MutableList<String>) : ModelDownloadNotice {
    val shown: MutableList<ProgressShown> = ArrayList()
    var progressThrows: Boolean = false

    override fun downloading(sentence: String) {
        log.add("downloading:$sentence")
    }

    override fun progress(sentence: String, doneMb: Int, totalMb: Int) {
        log.add("progress:$sentence")
        shown.add(ProgressShown(sentence, doneMb, totalMb))
        if (progressThrows) throw IllegalStateException("notice broke")
    }

    override fun done() {
        log.add("done")
    }

    override fun failed(sentence: String) {
        log.add("failed:$sentence")
    }
}

/**
 * An install port that answers [outcome]. Its one-argument form answers the outcome; its callback form
 * sends each scripted pair to the callback, keeps the callback in [kept] and answers the outcome. When
 * [refuseMain] is set, the main queue refuses every post while the pairs are sent.
 */
private class ScriptedProgressPort(
    private val outcome: InstallOutcome,
    private val script: List<Pair<Long, Long>> = emptyList(),
) : ModelInstallPort {
    var kept: ((Long, Long) -> Unit)? = null
    var refuseMain: ModelQueueMain? = null

    override fun install(entry: ModelEntry): InstallOutcome = outcome

    override fun install(entry: ModelEntry, onProgress: (received: Long, total: Long) -> Unit): InstallOutcome {
        kept = onProgress
        val main = refuseMain
        main?.refuse = true
        try {
            for ((received, total) in script) onProgress(received, total)
        } finally {
            main?.refuse = false
        }
        return outcome
    }
}

/** One download on fresh queues; [port] answers and [notice] records. The request is made by [request]. */
private class DownloadRig(val port: ScriptedProgressPort) {
    val log: MutableList<String> = ArrayList()
    val notice = ProgressNoticeFake(log)
    val background = ModelQueueBackground()
    val main = ModelQueueMain()
    val answers: MutableList<DownloadOutcome> = ArrayList()
    private val downloader = ModelDownloader(
        installer = port,
        selectedId = { "probe" },
        lookup = { modelEntryFor(it) },
        background = background,
        main = main,
        notice = notice,
    )

    fun request() {
        downloader.requestDownload { answers.add(it) }
    }

    fun runAll() {
        background.runAll()
        main.runAll()
    }
}

/** Runs one whole download with [script] and answers the rig. */
private fun runDownload(script: List<Pair<Long, Long>>, outcome: InstallOutcome = InstallOutcome.Installed): DownloadRig {
    val rig = DownloadRig(ScriptedProgressPort(outcome, script))
    rig.request()
    rig.runAll()
    return rig
}

internal class ModelDownloaderProgressTest {

    @Test
    fun `a known total shows the progress sentence and the bar numbers`() {
        val rig = runDownload(listOf(120_000_000L to 365_748_162L))
        assertEquals(
            "app: one progress entry with the known total",
            listOf(ProgressShown("Downloading the speech model: 120 of 366 MB.", 120, 366)),
            rig.notice.shown,
        )
    }

    @Test
    fun `an unknown total shows megabytes so far and no total`() {
        val rig = runDownload(listOf(120_000_000L to -1L))
        assertEquals(
            "app: one progress entry with no total",
            listOf(ProgressShown("Downloading the speech model: 120 MB so far.", 120, 0)),
            rig.notice.shown,
        )
    }

    @Test
    fun `all bytes in with a known total shows the checking line`() {
        val rig = runDownload(listOf(365_748_162L to 365_748_162L))
        assertEquals(
            "app: one checking entry with an indeterminate bar",
            listOf(ProgressShown("Checking the speech model.", 0, 0)),
            rig.notice.shown,
        )
    }

    @Test
    fun `one byte short of the total is still the progress sentence`() {
        val rig = runDownload(listOf(365_748_161L to 365_748_162L))
        assertEquals(
            "app: one byte short is the progress sentence, not the checking line",
            listOf(ProgressShown("Downloading the speech model: 365 of 366 MB.", 365, 366)),
            rig.notice.shown,
        )
    }

    @Test
    fun `an unknown total never shows the checking line`() {
        val rig = runDownload(listOf(365_748_162L to -1L))
        assertEquals(
            "app: an unknown total shows megabytes so far",
            listOf(ProgressShown("Downloading the speech model: 365 MB so far.", 365, 0)),
            rig.notice.shown,
        )
    }

    @Test
    fun `progress waits for the main thread`() {
        val rig = DownloadRig(ScriptedProgressPort(InstallOutcome.Installed, listOf(120_000_000L to 365_748_162L)))
        rig.request()
        rig.background.runAll()
        assertTrue("app: progress was shown before the main thread ran", rig.notice.shown.isEmpty())
        rig.main.runAll()
        assertEquals("app: one progress entry after the main thread ran", 1, rig.notice.shown.size)
    }

    @Test
    fun `progress comes between the start and the answer, in order`() {
        val rig = runDownload(
            listOf(
                120_000_000L to 365_748_162L,
                240_000_000L to 365_748_162L,
                365_748_162L to 365_748_162L,
            ),
        )
        assertEquals(
            "app: downloading, three progress entries, then done",
            listOf("downloading", "progress", "progress", "progress", "done"),
            rig.log.map { it.substringBefore(":") },
        )
    }

    @Test
    fun `nothing is shown after the model is installed`() {
        val rig = runDownload(listOf(120_000_000L to 365_748_162L))
        assertEquals("app: the download ended with Done", listOf<DownloadOutcome>(DownloadOutcome.Done("probe")), rig.answers)
        val before = rig.notice.shown.size
        val late = checkNotNull(rig.port.kept) { "app: no callback was kept" }
        late(200_000_000L, 365_748_162L)
        rig.main.runAll()
        assertEquals("app: a report after the install shows nothing", before, rig.notice.shown.size)
    }

    @Test
    fun `nothing is shown after a failed install`() {
        val rig = runDownload(listOf(120_000_000L to 365_748_162L), InstallOutcome.Refused("The model could not be downloaded."))
        assertEquals(
            "app: the download ended with the failure sentence",
            listOf<DownloadOutcome>(DownloadOutcome.Failed(ModelSentences.DOWNLOAD_FAILED)),
            rig.answers,
        )
        val before = rig.notice.shown.size
        val late = checkNotNull(rig.port.kept) { "app: no callback was kept" }
        late(200_000_000L, 365_748_162L)
        rig.main.runAll()
        assertEquals("app: a report after the failed install shows nothing", before, rig.notice.shown.size)
    }

    @Test
    fun `a late report of one download is dropped while the next one runs`() {
        val port = ScriptedProgressPort(InstallOutcome.Installed, listOf(120_000_000L to 365_748_162L))
        val rig = DownloadRig(port)
        rig.request()
        rig.runAll()
        val late = checkNotNull(port.kept) { "app: no callback was kept" }
        val before = rig.notice.shown.size
        rig.request()
        late(200_000_000L, 365_748_162L)
        rig.main.runAll()
        assertEquals("app: a report of the earlier download is dropped", before, rig.notice.shown.size)
    }

    @Test
    fun `a progress post that main refuses is skipped and the download still ends`() {
        val port = ScriptedProgressPort(InstallOutcome.Installed, listOf(120_000_000L to 365_748_162L))
        val rig = DownloadRig(port)
        port.refuseMain = rig.main
        rig.request()
        rig.runAll()
        assertTrue("app: a refused progress post showed a line", rig.notice.shown.isEmpty())
        assertEquals("app: the download still ended with Done", listOf<DownloadOutcome>(DownloadOutcome.Done("probe")), rig.answers)
    }

    @Test
    fun `a notice that throws on progress does not stop the download`() {
        val rig = DownloadRig(ScriptedProgressPort(InstallOutcome.Installed, listOf(120_000_000L to 365_748_162L)))
        rig.notice.progressThrows = true
        rig.request()
        rig.runAll()
        assertEquals("app: the progress notice was reached", 1, rig.notice.shown.size)
        assertEquals("app: the download still ended with Done", listOf<DownloadOutcome>(DownloadOutcome.Done("probe")), rig.answers)
    }
}
