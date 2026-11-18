package dev.breaker.dictation.updater

/**
 * A [TimerScheduler] that keeps every wait it was given. [cancel] drops the waits still pending.
 * [fire] runs one wait by hand, dropped or not, so a test can see what a late wait does.
 */
internal class FakeTimerScheduler : TimerScheduler {
    /** One wait: its length, its task, and whether it was dropped or has fired. */
    class Wait(val delayMs: Long, val task: Runnable) {
        var dropped = false
        var fired = false

        fun pending(): Boolean = !dropped && !fired
    }

    val waits = ArrayList<Wait>()
    var cancels = 0
    var throwOnSchedule = false
    var throwOnCancel = false

    override fun scheduleOnce(afterMs: Long, task: Runnable) {
        if (throwOnSchedule) throw IllegalStateException("the scheduler refused the wait")
        waits.add(Wait(afterMs, task))
    }

    override fun cancel() {
        cancels += 1
        waits.forEach { it.dropped = true }
        if (throwOnCancel) throw IllegalStateException("the scheduler refused the cancel")
    }

    /** The waits that are neither dropped nor fired. */
    fun pendingWaits(): List<Wait> = waits.filter { it.pending() }

    /** Runs wait number [index] by hand, marking it fired first. */
    fun fire(index: Int) {
        val wait = waits[index]
        wait.fired = true
        wait.task.run()
    }
}
