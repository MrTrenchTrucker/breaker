package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.dictation.core.model.UpdateCheckResult
import dev.breaker.dictation.http.BytesReply
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterBuildTest {

    /** The app's time store as a test sees it: the last check time is set by the test, and every record is kept. */
    private class BuildTimes(var lastCheck: Long? = null) : UpdateTimes {
        val checkRecords = ArrayList<Long>()
        val updateRecords = ArrayList<Long>()

        override fun lastCheckAtMs(): Long? = lastCheck

        override fun recordCheckAtMs(atMs: Long) {
            checkRecords.add(atMs)
        }

        override fun recordUpdateAtMs(atMs: Long) {
            updateRecords.add(atMs)
        }
    }

    private val folder: File = Files.createTempDirectory("updaterbuild").toFile()
    private val nowValue = 5_000L

    private fun config(
        transport: FakeTransport,
        times: BuildTimes,
        scheduler: FakeTimerScheduler,
        installedCode: Long = 1L,
        installedName: String = "0.1.0-debug",
        admin: Boolean = false,
    ): UpdaterConfig = UpdaterConfig(
        transport = transport,
        serverUrl = { "https://host.test" },
        apkFolder = { folder },
        pinnedKeys = emptyList(),
        isAdmin = { admin },
        installedVersionCode = { installedCode },
        installedVersionName = { installedName },
        installer = FakeApkInstaller { InstallResult.Started },
        times = times,
        nowMs = { nowValue },
        scheduler = scheduler,
        onInstallResult = { },
    )

    private fun manifestAnswer(version: String, versionCode: String): FakeTransport =
        FakeTransport { BytesReply(200, bytesOf(manifestText(version = version, versionCode = versionCode))) }

    @Test
    fun `the automatic check answers available with the manifest version`() {
        val transport = FakeTransport { BytesReply(200, bytesOf(manifestText())) }
        val updater = buildUpdater(config(transport, BuildTimes(), FakeTimerScheduler()))
        val result = updater.checker.check(false)
        assertTrue("android_updater: an available answer", result is UpdateCheckResult.Available)
        assertEquals(
            "android_updater: the manifest version",
            "0.0.0.1",
            (result as UpdateCheckResult.Available).release.version,
        )
    }

    @Test
    fun `the check time is recorded after the automatic check`() {
        val times = BuildTimes()
        val transport = FakeTransport { BytesReply(200, bytesOf(manifestText())) }
        val updater = buildUpdater(config(transport, times, FakeTimerScheduler()))
        updater.checker.check(false)
        assertEquals("android_updater: the check time", listOf(nowValue), times.checkRecords)
    }

    @Test
    fun `a recent check time makes the automatic check answer null and send nothing`() {
        val transport = FakeTransport { BytesReply(200, bytesOf(manifestText())) }
        val updater = buildUpdater(config(transport, BuildTimes(lastCheck = nowValue - 1_000L), FakeTimerScheduler()))
        assertNull("android_updater: not due and not forced", updater.checker.check(false))
        assertEquals("android_updater: no request", 0, transport.sent.size)
    }

    @Test
    fun `a forced check by a non-admin is not forced and answers null when not due`() {
        val transport = FakeTransport { BytesReply(200, bytesOf(manifestText())) }
        val updater = buildUpdater(
            config(transport, BuildTimes(lastCheck = nowValue - 1_000L), FakeTimerScheduler(), admin = false),
        )
        assertNull("android_updater: a non-admin force is not forced", updater.checker.check(true))
        assertEquals("android_updater: no request", 0, transport.sent.size)
    }

    @Test
    fun `a forced check by an admin runs even when not due`() {
        val transport = FakeTransport { BytesReply(200, bytesOf(manifestText())) }
        val updater = buildUpdater(
            config(transport, BuildTimes(lastCheck = nowValue - 1_000L), FakeTimerScheduler(), admin = true),
        )
        val result = updater.checker.check(true)
        assertTrue("android_updater: an available answer", result is UpdateCheckResult.Available)
        assertEquals("android_updater: one request", 1, transport.sent.size)
    }

    @Test
    fun `start runs one check and schedules the daily wait`() {
        val transport = FakeTransport { BytesReply(200, bytesOf(manifestText())) }
        val scheduler = FakeTimerScheduler()
        val updater = buildUpdater(config(transport, BuildTimes(), scheduler))
        updater.start()
        assertEquals("android_updater: one request", 1, transport.sent.size)
        assertEquals("android_updater: one pending wait", 1, scheduler.pendingWaits().size)
        assertEquals(
            "android_updater: the daily wait",
            UpdateTimerDelays.DAILY_MS,
            scheduler.pendingWaits()[0].delayMs,
        )
    }

    @Test
    fun `a rollback shaped offer is an ordinary update`() {
        val transport = manifestAnswer(version = "\"1.2.3.4\"", versionCode = "6")
        val updater = buildUpdater(config(transport, BuildTimes(), FakeTimerScheduler(), installedCode = 5L, installedName = "1.2.3.5"))
        val expected = ReleaseInfo("1.2.3.4", "https://example.test/app.apk", "aaaa", "bbbb", "Latest release")
        assertEquals(
            "android_updater: the older name under a higher code is available",
            UpdateCheckResult.Available(expected),
            updater.checker.check(false),
        )
    }

    @Test
    fun `a non-admin forced check still runs the daily rule only`() {
        val dueTransport = FakeTransport { BytesReply(200, bytesOf(manifestText())) }
        val dueTimes = BuildTimes(lastCheck = nowValue - 86_400_001L)
        val due = buildUpdater(config(dueTransport, dueTimes, FakeTimerScheduler(), admin = false))
        assertTrue("android_updater: a due day runs the check", due.checker.check(true) is UpdateCheckResult.Available)
        assertEquals("android_updater: one request when due", 1, dueTransport.sent.size)
        assertEquals("android_updater: the check time is recorded", listOf(nowValue), dueTimes.checkRecords)

        val recentTransport = FakeTransport { BytesReply(200, bytesOf(manifestText())) }
        val recentTimes = BuildTimes(lastCheck = nowValue - 1_000L)
        val recent = buildUpdater(config(recentTransport, recentTimes, FakeTimerScheduler(), admin = false))
        assertNull("android_updater: a recent check is not forced", recent.checker.check(true))
        assertEquals("android_updater: no request when not due", 0, recentTransport.sent.size)
        assertEquals("android_updater: no time recorded when not due", 0, recentTimes.checkRecords.size)
    }

    @Test
    fun `stop drops the waiting task so a late run checks nothing`() {
        val transport = FakeTransport { BytesReply(200, bytesOf(manifestText())) }
        val scheduler = FakeTimerScheduler()
        val updater = buildUpdater(config(transport, BuildTimes(), scheduler))
        updater.start()
        updater.stop()
        assertEquals("android_updater: no pending wait after stop", 0, scheduler.pendingWaits().size)
        scheduler.fire(0)
        assertEquals("android_updater: no request after the late run", 1, transport.sent.size)
        assertEquals("android_updater: nothing scheduled after the late run", 1, scheduler.waits.size)
    }
}
