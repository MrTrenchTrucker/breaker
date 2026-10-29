package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.UpdateCheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the checker's answer when its server base is refused by the origin rule, and when the base is usable.
 * The checker is wired to the real manifest source and a recording opener; the other ports are the shared fakes.
 */
internal class CheckerOriginBaseTest {

    private fun checkerFor(rig: CheckerRig, serverBase: String, opener: ScriptedOpener): ReleaseUpdateChecker =
        ReleaseUpdateChecker(
            rig.clock,
            rig.auth,
            rig.ledger,
            ManifestHttpSource(serverBase, opener),
            rig.downloads,
            rig.installer,
            rig.installed,
            PINNED_KEYS,
        )

    private fun manifestOpener(): ScriptedOpener =
        ScriptedOpener(status = 200, body = TrackedStream(manifestJson().toByteArray(Charsets.UTF_8)))

    @Test
    fun `a refused server base fails the check with no request download or install`() {
        val rig = CheckerRig()
        rig.auth.session = adminSession()
        val opener = manifestOpener()
        val result = checkerFor(rig, "https://user@breaker.example.test:8443", opener).check(true)
        assertEquals(UpdateCheckResult.Failed(UpdateSentences.SERVER_UNREACHABLE), result)
        assertTrue("the refused base must not be requested", opener.calls.isEmpty())
        assertFalse(
            "the refused base must not start a download",
            rig.log.snapshot().any { it.startsWith("download:") },
        )
        assertEquals("the installer must not be called", 0, rig.installer.calls)
    }

    @Test
    fun `a usable server base reaches the manifest once through the checker`() {
        val rig = CheckerRig()
        rig.auth.session = adminSession()
        val opener = manifestOpener()
        val result = checkerFor(rig, SERVER_BASE, opener).check(true)
        assertEquals(UpdateCheckResult.Available(defaultRelease()), result)
        assertEquals(
            listOf(
                OpenCall(
                    "https://breaker.example.test:8443/v1/updates/latest.json",
                    HttpRules.CONNECT_TIMEOUT_MS,
                    HttpRules.READ_TIMEOUT_MS,
                ),
            ),
            opener.calls,
        )
    }
}
