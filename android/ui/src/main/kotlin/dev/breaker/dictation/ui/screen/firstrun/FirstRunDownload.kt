package dev.breaker.dictation.ui.screen.firstrun

/*
 * The memory of the model download page: what the download is doing and whether a new one may start.
 *
 * The page may be shown again while a download runs, be rebuilt or be tapped twice, and progress
 * may arrive before the page asks for anything. The tracker is the one place that says whether a
 * start is allowed, so a second download never begins while one is running, and a failed one may
 * be tried again.
 */

/** What the download page is doing. */
internal enum class DownloadPhase { IDLE, RUNNING, CHECKING, FAILED, READY }

/** The download page's memory: plain data and plain rules. The caller starts the download when [requestStart] says so. */
internal class DownloadTracker {
    /** What the download is doing now. */
    var phase: DownloadPhase = DownloadPhase.IDLE
        private set

    /** Megabytes done so far; zero when nothing is known. */
    var doneMb: Int = 0
        private set

    /** The total in megabytes; zero when the total is not known. */
    var totalMb: Int = 0
        private set

    /**
     * Whether the caller may start a download now. True only when the phase is IDLE or FAILED,
     * and then the tracker is RUNNING with zero numbers. In every other phase nothing changes.
     */
    fun requestStart(): Boolean {
        if (phase != DownloadPhase.IDLE && phase != DownloadPhase.FAILED) return false
        phase = DownloadPhase.RUNNING
        doneMb = 0
        totalMb = 0
        return true
    }

    /**
     * Records progress. Ignored when the model is ready or being checked. Any other phase becomes
     * RUNNING with these numbers, and negative numbers count as zero. Progress that arrives while
     * IDLE shows a download that is already going, such as one seen again after the page was left.
     */
    fun onProgress(doneMb: Int, totalMb: Int) {
        if (phase == DownloadPhase.READY || phase == DownloadPhase.CHECKING) return
        phase = DownloadPhase.RUNNING
        this.doneMb = maxOf(0, doneMb)
        this.totalMb = maxOf(0, totalMb)
    }

    /** The bytes are all in and the model is being checked. Ignored when ready; the numbers stay. */
    fun onChecking() {
        if (phase == DownloadPhase.READY) return
        phase = DownloadPhase.CHECKING
    }

    /**
     * The download has ended. READY when the model is on the phone, FAILED when it is not and a
     * line is given, IDLE otherwise. The numbers go back to zero unless READY.
     */
    fun onStopped(modelReady: Boolean, line: String?) {
        phase = when {
            modelReady -> DownloadPhase.READY
            line != null -> DownloadPhase.FAILED
            else -> DownloadPhase.IDLE
        }
        if (phase != DownloadPhase.READY) {
            doneMb = 0
            totalMb = 0
        }
    }

    /** The speech model is on the phone. */
    fun onModelReady() {
        phase = DownloadPhase.READY
    }
}
