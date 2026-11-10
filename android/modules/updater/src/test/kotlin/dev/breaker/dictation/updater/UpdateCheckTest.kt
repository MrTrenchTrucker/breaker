package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.dictation.core.model.UpdateCheckResult
import dev.breaker.dictation.http.BytesReply
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {

    private fun check(status: Int, body: String, installed: Long = 1L, name: String = "0.0.0.0"): UpdateCheckResult =
        check(FakeTransport { BytesReply(status, bytesOf(body)) }, installed, name)

    private fun check(transport: FakeTransport, installed: Long = 1L, name: String = "0.0.0.0"): UpdateCheckResult =
        UpdateCheck(ManifestFetch(transport) { "https://host.test" }, { installed }, { name }).check()

    @Test
    fun `a newer code is available with all five fields of the release`() {
        val result = check(200, manifestText(versionCode = "2"), installed = 1L)
        val expected = ReleaseInfo("0.0.0.1", "https://example.test/app.apk", "aaaa", "bbbb", "Latest release")
        assertEquals("android_updater: available", UpdateCheckResult.Available(expected), result)
    }

    @Test
    fun `an equal code is up to date and names the installed version`() {
        assertEquals(
            "android_updater: equal code",
            UpdateCheckResult.UpToDate("0.0.0.7"),
            check(200, manifestText(versionCode = "5"), installed = 5L, name = "0.0.0.7"),
        )
    }

    @Test
    fun `a lower code is up to date`() {
        assertEquals(
            "android_updater: lower code",
            UpdateCheckResult.UpToDate("0.0.0.7"),
            check(200, manifestText(versionCode = "4"), installed = 5L, name = "0.0.0.7"),
        )
    }

    @Test
    fun `a newer code with a blank field fails as unusable`() {
        for (text in listOf(
            manifestText(version = "\" \""),
            manifestText(apkUrl = "\"\""),
            manifestText(sha256 = "\"\""),
            manifestText(signature = "\" \""),
        )) {
            assertEquals(
                "android_updater: blank field with a newer code",
                UpdateCheckResult.Failed(CheckReasons.UNUSABLE),
                check(200, text, installed = 1L),
            )
        }
    }

    @Test
    fun `an equal code with a blank field is up to date because nothing newer is offered`() {
        assertEquals(
            "android_updater: equal code with blank field",
            UpdateCheckResult.UpToDate("0.0.0.0"),
            check(200, manifestText(versionCode = "1", signature = "\"\""), installed = 1L),
        )
    }

    @Test
    fun `no release yet is up to date`() {
        assertEquals(
            "android_updater: 404 not_found",
            UpdateCheckResult.UpToDate("0.0.0.0"),
            check(404, NOT_FOUND_TEXT),
        )
    }

    @Test
    fun `each failed fetch is a failed check with the fixed sentence`() {
        assertEquals("android_updater: 503", UpdateCheckResult.Failed(CheckReasons.SERVER_ERROR), check(503, "{}"))
        assertEquals("android_updater: bad json", UpdateCheckResult.Failed(CheckReasons.BAD_ANSWER), check(200, "nope"))
        assertEquals(
            "android_updater: unreachable",
            UpdateCheckResult.Failed(CheckReasons.UNREACHABLE),
            check(FakeTransport { throw IOException("LEAK-MARKER") }),
        )
    }

    @Test
    fun `a failed check keeps nothing of the reply in its reason`() {
        val result = check(200, "LEAK-MARKER") as UpdateCheckResult.Failed
        assertFalse("android_updater: reply text in reason", result.reason.contains("LEAK-MARKER"))
    }

    @Test
    fun `the installed code is read on every check`() {
        var installed = 1L
        val updateCheck = UpdateCheck(
            ManifestFetch(FakeTransport { BytesReply(200, bytesOf(manifestText(versionCode = "2"))) }) { "https://host.test" },
            { installed },
            { "0.0.0.0" },
        )
        assertTrue("android_updater: first check", updateCheck.check() is UpdateCheckResult.Available)
        installed = 2L
        assertEquals("android_updater: second check", UpdateCheckResult.UpToDate("0.0.0.0"), updateCheck.check())
    }

    @Test
    fun `a throwing version provider is a failed check and does not escape`() {
        val updateCheck = UpdateCheck(
            ManifestFetch(FakeTransport { BytesReply(200, bytesOf(manifestText())) }) { "https://host.test" },
            { throw IllegalStateException("LEAK-MARKER") },
            { "0.0.0.0" },
        )
        assertEquals("android_updater: unfinished", UpdateCheckResult.Failed(CheckReasons.UNFINISHED), updateCheck.check())
    }
}
