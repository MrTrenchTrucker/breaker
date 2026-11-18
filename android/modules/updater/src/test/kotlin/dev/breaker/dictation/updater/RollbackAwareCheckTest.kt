package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.dictation.core.model.UpdateCheckResult
import dev.breaker.dictation.http.BytesReply
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RollbackAwareCheckTest {

    private val rollback = ReleaseInfo("1.2.3.4", "https://example.test/app.apk", "aaaa", "bbbb", "Latest release")
    private val newer = ReleaseInfo("1.2.3.9", "https://example.test/app.apk", "aaaa", "bbbb", "Latest release")
    private val other = ReleaseInfo("1.2.3.8", "https://example.test/app.apk", "aaaa", "bbbb", "Latest release")

    private fun holdingRollback(): HeldRollback = HeldRollback().also { it.hold(rollback) }

    private fun checkWith(
        transport: FakeTransport,
        installedCode: () -> Long,
        installedName: () -> String,
        held: HeldRollback,
    ): UpdateCheckResult =
        RollbackAwareCheck(ManifestFetch(transport) { "https://host.test" }, installedCode, installedName, held).check()

    private fun reply(status: Int, body: String, held: HeldRollback): UpdateCheckResult =
        checkWith(FakeTransport { BytesReply(status, bytesOf(body)) }, { 5L }, { "1.2.3.5" }, held)

    @Test
    fun `a rollback shaped offer answers up to date with the installed name and holds the release`() {
        val held = HeldRollback()
        val result = reply(200, manifestText(version = "\"1.2.3.4\"", versionCode = "6"), held)
        assertEquals("android_updater: up to date with the installed name", UpdateCheckResult.UpToDate("1.2.3.5"), result)
        assertEquals("android_updater: the rollback is held with all five fields", rollback, held.current())
    }

    @Test
    fun `a lower code offer is never a rollback and clears an earlier held offer`() {
        val held = holdingRollback()
        val result = reply(200, manifestText(version = "\"1.2.3.4\"", versionCode = "4"), held)
        assertEquals("android_updater: up to date", UpdateCheckResult.UpToDate("1.2.3.5"), result)
        assertNull("android_updater: the earlier offer is cleared", held.current())
    }

    @Test
    fun `an equal code offer is not a rollback and clears an earlier held offer`() {
        val held = holdingRollback()
        val result = reply(200, manifestText(version = "\"1.2.3.4\"", versionCode = "5"), held)
        assertEquals("android_updater: up to date", UpdateCheckResult.UpToDate("1.2.3.5"), result)
        assertNull("android_updater: the earlier offer is cleared", held.current())
    }

    @Test
    fun `a normal newer offer answers available and clears an earlier held offer`() {
        val held = holdingRollback()
        val result = reply(200, manifestText(version = "\"1.2.3.9\"", versionCode = "6"), held)
        assertEquals("android_updater: available", UpdateCheckResult.Available(newer), result)
        assertNull("android_updater: the earlier offer is cleared", held.current())
    }

    @Test
    fun `an offer with the installed name is not a rollback and answers available`() {
        val held = HeldRollback()
        val result = reply(200, manifestText(version = "\"1.2.3.5\"", versionCode = "6"), held)
        val same = ReleaseInfo("1.2.3.5", "https://example.test/app.apk", "aaaa", "bbbb", "Latest release")
        assertEquals("android_updater: available with the same name", UpdateCheckResult.Available(same), result)
        assertNull("android_updater: nothing is held", held.current())
    }

    @Test
    fun `no release yet answers up to date and clears an earlier held offer`() {
        val held = holdingRollback()
        val result = reply(404, NOT_FOUND_TEXT, held)
        assertEquals("android_updater: no release yet", UpdateCheckResult.UpToDate("1.2.3.5"), result)
        assertNull("android_updater: the earlier offer is cleared", held.current())
    }

    @Test
    fun `a failed fetch keeps the held offer and answers the fetch reason`() {
        val held = holdingRollback()
        val result = reply(503, "{}", held)
        assertEquals("android_updater: the fetch reason", UpdateCheckResult.Failed(CheckReasons.SERVER_ERROR), result)
        assertEquals("android_updater: the held offer is kept", rollback, held.current())
    }

    @Test
    fun `an unreachable server keeps the held offer and answers the unreachable reason`() {
        val held = holdingRollback()
        val result = checkWith(FakeTransport { throw IOException("refused") }, { 5L }, { "1.2.3.5" }, held)
        assertEquals("android_updater: unreachable", UpdateCheckResult.Failed(CheckReasons.UNREACHABLE), result)
        assertEquals("android_updater: the held offer is kept", rollback, held.current())
    }

    @Test
    fun `an unusable newer offer answers the unusable reason`() {
        val held = HeldRollback()
        val result = reply(200, manifestText(version = "\"1.2.3.9\"", versionCode = "6", signature = "\"\""), held)
        assertEquals("android_updater: unusable", UpdateCheckResult.Failed(CheckReasons.UNUSABLE), result)
    }

    @Test
    fun `a rollback shaped offer with a blank field is refused as unusable and not held`() {
        val held = HeldRollback()
        val result = reply(200, manifestText(version = "\"1.2.3.4\"", versionCode = "6", apkUrl = "\"\""), held)
        assertEquals("android_updater: unusable", UpdateCheckResult.Failed(CheckReasons.UNUSABLE), result)
        assertNull("android_updater: nothing is held", held.current())
    }

    @Test
    fun `an exception from the installed code answers unfinished and keeps the held offer`() {
        val held = holdingRollback()
        val transport = FakeTransport { BytesReply(200, bytesOf(manifestText(versionCode = "6"))) }
        val result = checkWith(transport, { throw IllegalStateException("probe") }, { "1.2.3.5" }, held)
        assertEquals("android_updater: unfinished", UpdateCheckResult.Failed(CheckReasons.UNFINISHED), result)
        assertEquals("android_updater: the held offer is kept", rollback, held.current())
    }

    @Test
    fun `an installed name that is not comparable with a higher code is an ordinary available offer`() {
        val held = HeldRollback()
        val transport = FakeTransport { BytesReply(200, bytesOf(manifestText(versionCode = "6"))) }
        val result = checkWith(transport, { 5L }, { "0.1.0-debug" }, held)
        val low = ReleaseInfo("0.0.0.1", "https://example.test/app.apk", "aaaa", "bbbb", "Latest release")
        assertEquals("android_updater: available", UpdateCheckResult.Available(low), result)
        assertNull("android_updater: nothing is held", held.current())
    }

    @Test
    fun `a held offer is kept until it is cleared`() {
        val held = HeldRollback()
        assertNull("android_updater: nothing held at first", held.current())
        held.hold(rollback)
        assertEquals("android_updater: the held offer", rollback, held.current())
        held.clear()
        assertNull("android_updater: cleared", held.current())
    }

    @Test
    fun `a second hold replaces the first held offer`() {
        val held = holdingRollback()
        held.hold(other)
        assertEquals("android_updater: the newer hold", other, held.current())
    }

    @Test
    fun `the two rollback reason sentences are pinned and hold no address or digit`() {
        assertEquals("android_updater: none held", "no earlier version is on offer", RollbackReasons.NONE_HELD)
        assertEquals("android_updater: not allowed", "only an admin can roll back", RollbackReasons.NOT_ALLOWED)
        for (text in listOf(RollbackReasons.NONE_HELD, RollbackReasons.NOT_ALLOWED)) {
            assertFalse("android_updater: no slash in a reason", text.contains("/"))
            assertFalse("android_updater: no web address in a reason", text.contains("http"))
            assertFalse("android_updater: no digit in a reason", text.any { it in '0'..'9' })
        }
    }
}
