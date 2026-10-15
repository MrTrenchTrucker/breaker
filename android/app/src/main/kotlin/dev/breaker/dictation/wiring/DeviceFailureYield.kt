package dev.breaker.dictation.wiring

/**
 * A take the microphone cut by a device failure: the audio captured so far is finished, saved and
 * copied, and one plain sentence is shown. The text is never typed into the field, and the busy face
 * is never used. The finish runs on [background] while the session still records; the answer comes
 * back on [main].
 */
internal class DeviceFailureYield(
    private val background: Background,
    private val main: MainPost,
    private val take: TakePort,
    private val settle: TakeSettle,
    private val generation: () -> Int,
    private val onSettled: (String) -> Unit,
) {
    /** True from a start until its answer is applied on main, or a clear. Main thread only. */
    private var busy: Boolean = false

    /** The number of the latest start or clear. Only the run with this number may clear busy. Main thread only. */
    private var runSeq: Int = 0

    /** True while a device failure is being transcribed. Main thread only. */
    fun running(): Boolean = busy

    /**
     * Starts the finish on the background. False when one is already running, or when the background
     * refuses the work; a refusal touches nothing else (the session keeps recording), so the caller's
     * fallback does the plain end.
     */
    fun start(): Boolean {
        if (busy) return false
        val epoch: Int = generation()
        runSeq += 1
        val run: Int = runSeq
        busy = true
        val queued: Boolean = try {
            background.submit {
                val finished: FinishResult? = try {
                    take.finish()
                } catch (e: Exception) {
                    null
                }
                try {
                    take.resetAfterTaken()
                } catch (e: Exception) {
                    // The settle still runs.
                }
                val saveFailed: Boolean = settle.save(finished)
                main.post {
                    // Only the latest run may free the flag: an older run that finishes late must not free a later one.
                    if (run == runSeq) busy = false
                    if (epoch == generation()) onSettled(settle.line(finished, saveFailed, DeviceFailureSentences.lines))
                }
            }
            true
        } catch (e: Exception) {
            false
        }
        if (!queued) busy = false
        return queued
    }

    /** Clears the running flag on a switch off and retires the runs in flight, so a late answer frees nothing. Main thread only. */
    fun clear() {
        busy = false
        runSeq += 1
    }
}
