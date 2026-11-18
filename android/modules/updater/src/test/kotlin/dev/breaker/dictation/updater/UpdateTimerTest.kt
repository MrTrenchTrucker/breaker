package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.dictation.core.model.UpdateCheckResult
import org.junit.Assert.assertEquals
import org.junit.Test

/** The timer: one check on start, the wait that the answer sets, and stop. */
class UpdateTimerTest {

    /** Counts the checks and answers as scripted; the scheduler keeps every wait it is given. */
    private class Harness(val answer: () -> UpdateCheckResult?) {
        val scheduler = FakeTimerScheduler()
        var checks = 0
        val timer = UpdateTimer(
            runCheck = {
                checks += 1
                answer()
            },
            scheduler = scheduler,
        )
    }

    private val release = ReleaseInfo(
        version = "1.5.0",
        apkUrl = "https://example.test/files/app-1.5.0.apk",
        sha256 = "timer-test-digest",
        signature = "timer-test-signature",
    )

    @Test
    fun `start runs one check and waits a day after an up to date answer`() {
        val h = Harness { UpdateCheckResult.UpToDate("1.4.2") }
        h.timer.start()
        assertEquals("android_updater: one check on start", 1, h.checks)
        assertEquals("android_updater: one wait pending", 1, h.scheduler.pendingWaits().size)
        assertEquals("android_updater: a day", 86_400_000L, h.scheduler.pendingWaits()[0].delayMs)
    }

    @Test
    fun `start waits a day after an available answer`() {
        val h = Harness { UpdateCheckResult.Available(release) }
        h.timer.start()
        assertEquals("android_updater: one check on start", 1, h.checks)
        assertEquals("android_updater: a day", 86_400_000L, h.scheduler.pendingWaits()[0].delayMs)
    }

    @Test
    fun `start waits an hour after a failed answer`() {
        val h = Harness { UpdateCheckResult.Failed(CheckReasons.UNFINISHED) }
        h.timer.start()
        assertEquals("android_updater: one check on start", 1, h.checks)
        assertEquals("android_updater: an hour", 3_600_000L, h.scheduler.pendingWaits()[0].delayMs)
    }

    @Test
    fun `start waits an hour after a null answer`() {
        val h = Harness { null }
        h.timer.start()
        assertEquals("android_updater: one check on start", 1, h.checks)
        assertEquals("android_updater: an hour", 3_600_000L, h.scheduler.pendingWaits()[0].delayMs)
    }

    @Test
    fun `start waits an hour after a check that throws and does not propagate`() {
        val h = Harness { throw IllegalStateException("the check threw") }
        h.timer.start()
        assertEquals("android_updater: one check on start", 1, h.checks)
        assertEquals("android_updater: an hour", 3_600_000L, h.scheduler.pendingWaits()[0].delayMs)
    }

    @Test
    fun `running the waiting task runs the next check and waits again`() {
        val h = Harness { UpdateCheckResult.UpToDate("1.4.2") }
        h.timer.start()
        h.scheduler.fire(0)
        assertEquals("android_updater: two checks", 2, h.checks)
        assertEquals("android_updater: two waits made", 2, h.scheduler.waits.size)
        assertEquals("android_updater: the second wait is a day", 86_400_000L, h.scheduler.waits[1].delayMs)
        assertEquals("android_updater: one wait pending", 1, h.scheduler.pendingWaits().size)
    }

    @Test
    fun `stop drops the pending wait`() {
        val h = Harness { UpdateCheckResult.UpToDate("1.4.2") }
        h.timer.start()
        h.timer.stop()
        assertEquals("android_updater: no wait pending after stop", 0, h.scheduler.pendingWaits().size)
    }

    @Test
    fun `a wait fired after stop runs no check and schedules nothing`() {
        val h = Harness { UpdateCheckResult.UpToDate("1.4.2") }
        h.timer.start()
        h.timer.stop()
        h.scheduler.fire(0)
        assertEquals("android_updater: no check after stop", 1, h.checks)
        assertEquals("android_updater: nothing scheduled after stop", 1, h.scheduler.waits.size)
    }

    @Test
    fun `a stop during the check schedules nothing`() {
        var late: UpdateTimer? = null
        val scheduler = FakeTimerScheduler()
        val timer = UpdateTimer(
            runCheck = {
                late?.stop()
                UpdateCheckResult.UpToDate("1.4.2")
            },
            scheduler = scheduler,
        )
        late = timer
        timer.start()
        assertEquals("android_updater: nothing scheduled after a stop", 0, scheduler.waits.size)
    }

    @Test
    fun `start twice drops the first wait and leaves one pending`() {
        val h = Harness { UpdateCheckResult.UpToDate("1.4.2") }
        h.timer.start()
        h.timer.start()
        assertEquals("android_updater: two checks", 2, h.checks)
        assertEquals("android_updater: one wait pending", 1, h.scheduler.pendingWaits().size)
        assertEquals("android_updater: the pending one is the second", h.scheduler.waits[1], h.scheduler.pendingWaits()[0])
        h.scheduler.fire(0)
        assertEquals("android_updater: the dropped first wait runs no check", 2, h.checks)
    }

    @Test
    fun `a scheduler that throws does not propagate from start or stop`() {
        val h = Harness { UpdateCheckResult.UpToDate("1.4.2") }
        h.scheduler.throwOnSchedule = true
        h.scheduler.throwOnCancel = true
        h.timer.start()
        h.timer.stop()
        assertEquals("android_updater: one check still ran", 1, h.checks)
        assertEquals("android_updater: no wait was kept", 0, h.scheduler.waits.size)
    }

    @Test
    fun `start after stop runs a check again`() {
        val h = Harness { UpdateCheckResult.UpToDate("1.4.2") }
        h.timer.start()
        h.timer.stop()
        h.timer.start()
        assertEquals("android_updater: two checks", 2, h.checks)
        assertEquals("android_updater: one wait pending", 1, h.scheduler.pendingWaits().size)
    }

    @Test
    fun `the daily wait is 24 hours in milliseconds`() {
        assertEquals("android_updater: the daily wait", 86_400_000L, UpdateTimerDelays.DAILY_MS)
        assertEquals("android_updater: 24 hours", 24L * 60L * 60L * 1000L, UpdateTimerDelays.DAILY_MS)
    }

    @Test
    fun `the retry wait is one hour in milliseconds`() {
        assertEquals("android_updater: the retry wait", 3_600_000L, UpdateTimerDelays.RETRY_MS)
        assertEquals("android_updater: one hour", 60L * 60L * 1000L, UpdateTimerDelays.RETRY_MS)
    }
}
