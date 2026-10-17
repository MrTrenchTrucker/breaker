package dev.breaker.dictation.wiring

import dev.breaker.dictation.stt.ondevice.HttpModelFetcher
import dev.breaker.dictation.stt.ondevice.LocalModelStore
import dev.breaker.dictation.stt.ondevice.ModelFetcher
import dev.breaker.dictation.stt.ondevice.ModelInstaller
import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelRegistry
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The plain sentences about the speech model. */
object ModelSentences {
    /** Shown when dictation starts and the speech model is not on the phone. */
    const val NO_MODEL: String = "Download the speech model in Breaker first."

    /** Shown while the speech model is being downloaded. */
    const val DOWNLOADING_TEMPLATE: String = "Downloading the speech model from GitHub ({size} MB)..."

    /** The downloading sentence for a model of [sizeMb] mebibytes, with the size Android shows. */
    fun downloadingFor(sizeMb: Int): String = DOWNLOADING_TEMPLATE.replace("{size}", mibToMb(sizeMb).toString())

    /** The size in decimal megabytes Android shows, from the registry's mebibytes. */
    fun mibToMb(sizeMb: Int): Int = (sizeMb * 1.048576).roundToInt()

    /** Shown when the speech model could not be downloaded; the download button in Breaker tries again. */
    const val DOWNLOAD_FAILED: String = "Could not download the speech model. Open Breaker to retry."

    /** Shown when the phone has too little free space for the speech model; the download button in Breaker tries again once space is free. */
    const val DOWNLOAD_NO_SPACE: String = "Not enough free space for the speech model. Free up some space."

    /** Shown once every byte of the speech model is in, while the archive is checked. */
    const val CHECKING: String = "Checking the speech model."

    /** Progress while the speech model downloads and its total is known; the placeholders are megabytes. */
    const val PROGRESS_TEMPLATE: String = "Downloading the speech model: {done} of {total} MB."

    /** Progress while the speech model downloads and its total is not known; the placeholder is megabytes so far. */
    const val PROGRESS_UNKNOWN_TEMPLATE: String = "Downloading the speech model: {done} MB so far."

    /** The progress sentence for [doneMb] of [totalMb] megabytes. */
    fun progressFor(doneMb: Int, totalMb: Int): String =
        PROGRESS_TEMPLATE.replace("{done}", doneMb.toString()).replace("{total}", totalMb.toString())

    /** The progress sentence for [doneMb] megabytes when the total is not known. */
    fun progressUnknownFor(doneMb: Int): String = PROGRESS_UNKNOWN_TEMPLATE.replace("{done}", doneMb.toString())

    /** Whole megabytes received; any part of a megabyte is dropped. MB means 1,000,000 bytes, the unit Android shows. */
    fun doneMb(bytes: Long): Int = (bytes / 1_000_000L).toInt()

    /** The total in megabytes, to the nearest whole megabyte. MB means 1,000,000 bytes, the unit Android shows. */
    fun totalMb(bytes: Long): Int = ((bytes + 500_000L) / 1_000_000L).toInt()
}

/**
 * Tells whether the speech model the settings select is ready to use: installed and unpacked.
 *
 * The id is read each time it is asked, so a changed setting is seen at once. It never throws; a
 * store or a setting that cannot be read answers false.
 */
class StoreModelReady(
    private val store: LocalModelStore,
    private val selectedId: () -> String,
) : ModelReady {
    override fun isReady(): Boolean =
        try {
            val id = selectedId()
            store.isInstalled(id) && store.isExtracted(id)
        } catch (e: Exception) {
            false
        }
}

/** What an install attempt came to. */
sealed class InstallOutcome {
    /** The model is installed, verified and unpacked. */
    object Installed : InstallOutcome()

    /** Nothing was installed because the phone has too little free space. */
    object NoSpace : InstallOutcome()

    /** Nothing usable was installed; [reason] is the installer's fixed sentence. */
    data class Refused(val reason: String) : InstallOutcome()
}

/** Installs one registry model into the local store. Blocking: call it off the main thread. */
interface ModelInstallPort {
    fun install(entry: ModelEntry): InstallOutcome

    /**
     * Installs like [install] and reports the model bytes received so far and the total, on the thread
     * that installs; the total is -1 when the length is not known. The default ignores the reports.
     */
    fun install(entry: ModelEntry, onProgress: (received: Long, total: Long) -> Unit): InstallOutcome = install(entry)
}

/**
 * An install port that asks [installer] to install and maps its answer. [installerWithProgress] is the
 * same install with the progress callback and is optional: the callback form of [install] uses it when
 * it is given, and otherwise ignores the reports and behaves like the one-argument form. The mapping of
 * the installer's answer is in one place, [toOutcome], for both forms. The optional parameter comes
 * first so that a trailing lambda still binds to [installer].
 */
class InstallerPort(
    private val installerWithProgress: ((ModelEntry, (Long, Long) -> Unit) -> ModelInstaller.InstallResult)? = null,
    private val installer: (ModelEntry) -> ModelInstaller.InstallResult,
) : ModelInstallPort {
    override fun install(entry: ModelEntry): InstallOutcome = toOutcome(installer(entry))

    override fun install(entry: ModelEntry, onProgress: (received: Long, total: Long) -> Unit): InstallOutcome =
        toOutcome(installerWithProgress?.invoke(entry, onProgress) ?: installer(entry))

    private fun toOutcome(result: ModelInstaller.InstallResult): InstallOutcome =
        when (result) {
            is ModelInstaller.InstallResult.Installed -> InstallOutcome.Installed
            is ModelInstaller.InstallResult.Refused ->
                if (result.refusal == ModelInstaller.Refusal.NO_SPACE) {
                    InstallOutcome.NoSpace
                } else {
                    InstallOutcome.Refused(result.detail)
                }
        }
}

/**
 * The real install port over [store]: the speech engine module's installer, fetching through [fetcher]
 * (the module's network fetcher unless another is handed in). It reports the fetch progress too.
 */
fun modelInstallPortFor(store: LocalModelStore, fetcher: ModelFetcher = HttpModelFetcher()): ModelInstallPort {
    val modelInstaller = ModelInstaller(store, fetcher)
    return InstallerPort(
        installerWithProgress = { entry, progress -> modelInstaller.install(entry, progress) },
        installer = { modelInstaller.install(it) },
    )
}

/** The notices a download shows. Each call is made on the main thread. */
interface ModelDownloadNotice {
    /** A download started; the sentence names the source and the size. */
    fun downloading(sentence: String)

    /**
     * Bytes are arriving. [totalMb] is 0 when there is no known total, and the bar is then indeterminate.
     * Called on the main thread.
     */
    fun progress(sentence: String, doneMb: Int, totalMb: Int) {}

    /** The model is installed. */
    fun done()

    /** The download failed; [sentence] says so in plain words. */
    fun failed(sentence: String)
}

/** How a download request ended. */
sealed class DownloadOutcome {
    /** The model [modelId] is installed. */
    data class Done(val modelId: String) : DownloadOutcome()

    /** Nothing was installed; [sentence] says why in plain words. */
    data class Failed(val sentence: String) : DownloadOutcome()

    /** A download was already running; this request did nothing. */
    object AlreadyRunning : DownloadOutcome()
}

/**
 * Downloads the speech model the settings select.
 *
 * [requestDownload] is the one entry point: the launcher's button calls it, and so will any later
 * step that offers the download. Only one download runs at a time. The model, its address and its
 * checksum come from the registry entry [lookup] returns for [selectedId]; nothing is written
 * here. The install runs on [background]; the notices and the answer are on [main]. A failed
 * install leaves nothing the speech engine can read (the installer deletes what it wrote), so the
 * same call tries again.
 *
 * Nothing here throws: any failure becomes a [DownloadOutcome.Failed] and a failed notice.
 *
 * Progress reaches the notice on the main thread. Nothing is posted after the answer: a token for the
 * download is cleared first when it ends, and a progress post that runs later finds no matching token.
 */
class ModelDownloader(
    private val installer: ModelInstallPort,
    private val selectedId: () -> String,
    private val lookup: (String) -> ModelEntry? = ModelRegistry::byId,
    private val background: Background,
    private val main: MainPost,
    private val notice: ModelDownloadNotice,
) {
    private val running = AtomicBoolean(false)
    private val active = AtomicReference<Any?>(null)

    /**
     * Starts the download of the selected model, from the main thread, and returns at once.
     *
     * [onFinished] is called on the main thread with how the request ended: [DownloadOutcome.Done] or
     * [DownloadOutcome.Failed] when the install is over, or [DownloadOutcome.AlreadyRunning] at once
     * when a download is already running.
     */
    fun requestDownload(onFinished: (DownloadOutcome) -> Unit = {}) {
        if (!running.compareAndSet(false, true)) {
            tell(onFinished, DownloadOutcome.AlreadyRunning)
            return
        }
        val token = Any()
        active.set(token)
        val entry = try {
            lookup(selectedId())
        } catch (e: Exception) {
            null
        }
        if (entry == null) {
            finish(DownloadOutcome.Failed(ModelSentences.DOWNLOAD_FAILED), onFinished)
            return
        }
        try {
            notice.downloading(ModelSentences.downloadingFor(entry.sizeMb))
            background.submit { installAndReport(entry, token, onFinished) }
        } catch (e: Exception) {
            finish(DownloadOutcome.Failed(ModelSentences.DOWNLOAD_FAILED), onFinished)
        }
    }

    private fun installAndReport(entry: ModelEntry, token: Any, onFinished: (DownloadOutcome) -> Unit) {
        val outcome = try {
            when (installer.install(entry) { received, total -> postProgress(token, received, total) }) {
                is InstallOutcome.Installed -> DownloadOutcome.Done(entry.id)
                is InstallOutcome.NoSpace -> DownloadOutcome.Failed(ModelSentences.DOWNLOAD_NO_SPACE)
                is InstallOutcome.Refused -> DownloadOutcome.Failed(ModelSentences.DOWNLOAD_FAILED)
            }
        } catch (e: Exception) {
            DownloadOutcome.Failed(ModelSentences.DOWNLOAD_FAILED)
        }
        try {
            main.post { finish(outcome, onFinished) }
        } catch (e: Exception) {
            active.set(null)
            running.set(false)
        }
    }

    private fun postProgress(token: Any, received: Long, total: Long) {
        try {
            main.post { if (active.get() === token) deliverProgress(received, total) }
        } catch (e: Exception) {
            // Best effort: a progress line that cannot be posted is skipped; the download goes on.
        }
    }

    private fun deliverProgress(received: Long, total: Long) {
        try {
            val done = ModelSentences.doneMb(received)
            when {
                total > 0 && received >= total -> notice.progress(ModelSentences.CHECKING, 0, 0)
                total > 0 -> notice.progress(ModelSentences.progressFor(done, ModelSentences.totalMb(total)), done, ModelSentences.totalMb(total))
                else -> notice.progress(ModelSentences.progressUnknownFor(done), done, 0)
            }
        } catch (e: Exception) {
            // The notice could not be shown; the download goes on.
        }
    }

    private fun finish(outcome: DownloadOutcome, onFinished: (DownloadOutcome) -> Unit) {
        active.set(null)
        running.set(false)
        try {
            when (outcome) {
                is DownloadOutcome.Done -> notice.done()
                is DownloadOutcome.Failed -> notice.failed(outcome.sentence)
                DownloadOutcome.AlreadyRunning -> Unit
            }
        } catch (e: Exception) {
            // The notice could not be shown; the answer below is still given.
        }
        tell(onFinished, outcome)
    }

    private fun tell(onFinished: (DownloadOutcome) -> Unit, outcome: DownloadOutcome) {
        try {
            onFinished(outcome)
        } catch (e: Exception) {
            // The caller's own callback failed; the download state is unchanged.
        }
    }
}
