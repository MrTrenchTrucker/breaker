package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AddressedSession
import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.UpdateCheckResult
import dev.breaker.dictation.core.model.UserRole
import dev.breaker.dictation.http.BytesReply
import dev.breaker.dictation.http.CallRequest
import dev.breaker.dictation.http.HttpTransport
import dev.breaker.dictation.http.StreamReply
import dev.breaker.dictation.updater.ApkInstaller
import dev.breaker.dictation.updater.InstallResult
import dev.breaker.dictation.updater.TimerScheduler
import dev.breaker.dictation.updater.UpdateTimes
import dev.breaker.dictation.updater.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The plain part of the updater wiring, built with fakes. The values reach the updater by name: the
 * server path is the manifest path, a forced check runs only for an admin, the installed version is read
 * on each check, the check time is kept through the store, and the daily wait is handed to the scheduler.
 * A session answers as an admin only when it is present and belongs to an admin.
 */
class UpdaterWiringTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    @Test
    fun `the check asks the server manifest path with the configured address`() {
        val transport = WiringTestTransport { manifestReply(2) }
        build(transport).checker.check(false)
        assertEquals("https://server.test/v1/updates/latest.json", transport.sent.single().url)
    }

    @Test
    fun `a newer manifest is answered as available with its version`() {
        val result: UpdateCheckResult? = build(WiringTestTransport { manifestReply(2) }).checker.check(false)
        val available: UpdateCheckResult.Available = result as UpdateCheckResult.Available
        assertEquals("1.2.3.4", available.release.version)
    }

    @Test
    fun `an equal code is answered as up to date with the installed name`() {
        val result: UpdateCheckResult? =
            build(WiringTestTransport { manifestReply(2) }, installedCode = { 2L }, installedName = { "1.2.3.4" })
                .checker.check(false)
        assertEquals(UpdateCheckResult.UpToDate("1.2.3.4"), result)
    }

    @Test
    fun `the check time is recorded through the app store`() {
        val times = WiringTestTimes()
        build(WiringTestTransport { manifestReply(2) }, times = times, nowMs = { 4000L }).checker.check(false)
        assertEquals(4000L, times.checkAt)
    }

    @Test
    fun `a forced check by a person who is not an admin runs only when due`() {
        val notDue = WiringTestTransport { manifestReply(2) }
        val early: UpdateCheckResult? =
            build(notDue, times = WiringTestTimes(checkAt = 1000L), nowMs = { 1000L }, admin = { false }).checker.check(true)
        assertNull(early)
        assertTrue(notDue.sent.isEmpty())

        val due: UpdateCheckResult? = build(WiringTestTransport { manifestReply(2) }, admin = { false }).checker.check(true)
        assertTrue(due is UpdateCheckResult.Available)
    }

    @Test
    fun `a forced check by an admin runs even when the check is not due`() {
        val transport = WiringTestTransport { manifestReply(2) }
        val result: UpdateCheckResult? =
            build(transport, times = WiringTestTimes(checkAt = 1000L), nowMs = { 1000L }, admin = { true }).checker.check(true)
        assertTrue(result is UpdateCheckResult.Available)
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun `the installed code and name are read on every check`() {
        var installedCode: Long = 1L
        val updater: Updater = build(WiringTestTransport { manifestReply(2) }, installedCode = { installedCode }, admin = { true })
        val first: UpdateCheckResult? = updater.checker.check(true)
        installedCode = 2L
        val second: UpdateCheckResult? = updater.checker.check(true)
        assertTrue(first is UpdateCheckResult.Available)
        assertEquals(UpdateCheckResult.UpToDate("1.2.3.3"), second)
    }

    @Test
    fun `start schedules the next check one day after a check`() {
        val scheduler = WiringTestScheduler()
        build(WiringTestTransport { manifestReply(2) }, scheduler = scheduler).start()
        assertEquals(listOf(24L * 60L * 60L * 1000L), scheduler.delays)
    }

    @Test
    fun `a missing session is not an admin`() {
        assertFalse(appIsAdmin { null })
    }

    @Test
    fun `an admin session is an admin`() {
        assertTrue(appIsAdmin { session(UserRole.ADMIN) })
    }

    @Test
    fun `a user session is not an admin`() {
        assertFalse(appIsAdmin { session(UserRole.USER) })
    }

    @Test
    fun `a session that throws counts as not an admin`() {
        assertFalse(appIsAdmin { throw IllegalStateException("no session in this test") })
    }

    private fun session(role: UserRole): AddressedSession =
        AddressedSession("https://server.test", AuthSession("user-1", "someone", "token-1", role, emptySet()))

    private fun manifestReply(code: Int): BytesReply = BytesReply(200, manifestBody(code))

    private fun manifestBody(code: Int): ByteArray =
        ("{\"version\":\"1.2.3.4\",\"version_code\":$code,\"apk_url\":\"https://server.test/app.apk\"," +
            "\"sha256\":\"aaaa\",\"signature\":\"bbbb\",\"notes\":\"Latest release\"}").toByteArray(Charsets.UTF_8)

    private fun build(
        transport: WiringTestTransport,
        times: UpdateTimes = WiringTestTimes(),
        scheduler: TimerScheduler = WiringTestScheduler(),
        installedCode: () -> Long = { 1L },
        installedName: () -> String = { "1.2.3.3" },
        admin: () -> Boolean = { false },
        nowMs: () -> Long = { 1000L },
    ): Updater = buildAppUpdater(
        transport = transport,
        serverUrl = { "https://server.test/" },
        apkFolder = { folder.root },
        pinnedKeys = emptyList(),
        isAdmin = admin,
        installedVersionCode = installedCode,
        installedVersionName = installedName,
        installer = ApkInstaller { InstallResult.Started },
        times = times,
        nowMs = nowMs,
        scheduler = scheduler,
        onInstallResult = { },
    )
}

private class WiringTestTransport(private val answer: (CallRequest) -> BytesReply) : HttpTransport {
    val sent: MutableList<CallRequest> = ArrayList()

    override fun send(request: CallRequest): BytesReply {
        sent.add(request)
        return answer(request)
    }

    override fun open(request: CallRequest): StreamReply = throw IllegalStateException("no stream in this test")
}

private class WiringTestTimes(var checkAt: Long? = null) : UpdateTimes {
    var installAt: Long? = null

    override fun lastCheckAtMs(): Long? = checkAt

    override fun recordCheckAtMs(atMs: Long) {
        checkAt = atMs
    }

    override fun recordUpdateAtMs(atMs: Long) {
        installAt = atMs
    }
}

private class WiringTestScheduler : TimerScheduler {
    val delays: MutableList<Long> = ArrayList()

    override fun scheduleOnce(afterMs: Long, task: Runnable) {
        delays.add(afterMs)
    }

    override fun cancel() {
    }
}
