package dev.breaker.dictation.wiring

import dev.breaker.dictation.stt.ondevice.HttpModelFetcher
import dev.breaker.dictation.stt.ondevice.LocalModelStore
import dev.breaker.dictation.stt.ondevice.ModelFetcher
import dev.breaker.dictation.stt.ondevice.ModelInstaller
import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelRegistry
import java.util.concurrent.atomic.AtomicBoolean

/** The plain sentences about the speech model. */
object ModelSentences {
    /** Shown when dictation starts and the speech model is not on the phone. */
    const val NO_MODEL: String = "Download the speech model in Breaker first."

    /** Shown while the speech model is being downloaded. */
    const val DOWNLOADING: String = "Downloading the speech model."

    /** Shown when the speech model could not be downloaded; the download button in Breaker tries again. */
    const val DOWNLOAD_FAILED: String = "Could not download the speech model. Open Breaker to retry."
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

    /** Nothing usable was installed; [reason] is the installer's fixed sentence. */
    data class Refused(val reason: String) : InstallOutcome()
}

/** Installs one registry model into the local store. Blocking: call it off the main thread. */
interface ModelInstallPort {
    fun install(entry: ModelEntry): InstallOutcome
}

/** An install port that asks [installer] to install and maps its answer. */
class InstallerPort(private val installer: (ModelEntry) -> ModelInstaller.InstallResult) : ModelInstallPort {
    override fun install(entry: ModelEntry): InstallOutcome =
        when (val result = installer(entry)) {
            is ModelInstaller.InstallResult.Installed -> InstallOutcome.Installed
            is ModelInstaller.InstallResult.Refused -> InstallOutcome.Refused(result.detail)
        }
}

/**
 * The real install port over [store]: the speech engine module's installer, fetching through [fetcher]
 * (the module's network fetcher unless another is handed in).
 */
fun modelInstallPortFor(store: LocalModelStore, fetcher: ModelFetcher = HttpModelFetcher()): ModelInstallPort =
    InstallerPort(ModelInstaller(store, fetcher)::install)

/** The notices a download shows. Each call is made on the main thread. */
interface ModelDownloadNotice {
    /** A download started; it has no known end, so the notice shows no percentage. */
    fun downloading()

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
            notice.downloading()
            background.submit { installAndReport(entry, onFinished) }
        } catch (e: Exception) {
            finish(DownloadOutcome.Failed(ModelSentences.DOWNLOAD_FAILED), onFinished)
        }
    }

    private fun installAndReport(entry: ModelEntry, onFinished: (DownloadOutcome) -> Unit) {
        val outcome = try {
            when (installer.install(entry)) {
                is InstallOutcome.Installed -> DownloadOutcome.Done(entry.id)
                is InstallOutcome.Refused -> DownloadOutcome.Failed(ModelSentences.DOWNLOAD_FAILED)
            }
        } catch (e: Exception) {
            DownloadOutcome.Failed(ModelSentences.DOWNLOAD_FAILED)
        }
        try {
            main.post { finish(outcome, onFinished) }
        } catch (e: Exception) {
            running.set(false)
        }
    }

    private fun finish(outcome: DownloadOutcome, onFinished: (DownloadOutcome) -> Unit) {
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
