package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.core.model.SignInAttempt
import dev.breaker.dictation.core.model.SignInLinkResult
import dev.breaker.dictation.core.model.SignInPrivateKey
import dev.breaker.dictation.core.model.buildSignInLink
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.testing.FakeSettingsStore
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.dictation.ui.theme.ThemeController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A typed address read back the way the app reads it, then given to the two readers that use it.
 *
 * The app reads the address with `settingsStore.load().serverUrl`, in BreakerCompositionRoot.kt line 116
 * (the server speech engine) and in host/SignInBrowser.kt line 34 (the sign-in link). The first reader is
 * the sign-in link builder, which is the real core function. The second is a stand-in for the server speech
 * check, which lives in another module (stt-server ServerEndpoint.addressProblem, called from
 * SttServerEngine.kt line 54) and cannot be called from here, so its three parts are written out below.
 */
class SettingsServerAddressReadersTest {
    private val challenge = "DwBzhbb51LfusnSGBa_hqYSgo7-j8BTQnip4TOnlzRo"

    /** The typed address, and the start of the sign-in link it must give. */
    private val samples = listOf(
        Pair("https://box.example", "https://box.example/signin.html#challenge="),
        Pair("https://box.example/", "https://box.example/signin.html#challenge="),
        Pair("https://box.example/api", "https://box.example/api/signin.html#challenge="),
        Pair("http://192.0.2.20:8080", "http://192.0.2.20:8080/signin.html#challenge="),
        Pair("HTTP://Box.Example", "http://Box.Example/signin.html#challenge="),
        Pair("http://box_1.example:65535", "http://box_1.example:65535/signin.html#challenge="),
    )

    /**
     * A real attempt from fixed valid parts: a canonical secret text, a 32 byte public key
     * and a 43 character challenge.
     */
    private fun attempt(): SignInAttempt = SignInAttempt(
        "A".repeat(43).toByteArray(Charsets.US_ASCII),
        SignInPrivateKey(ByteArray(32) { 4.toByte() }),
        ByteArray(32) { 2.toByte() },
        challenge,
    )

    /**
     * The stand-in for the server speech check: no space and no control character, a start of http:// or
     * https:// in any letter case, and a non-empty host part with no user name and no leading colon.
     */
    private fun serverSpeechAccepts(base: String): Boolean {
        if (base.any { it == ' ' || it.isISOControl() }) return false
        val lower = base.lowercase()
        val prefix = when {
            lower.startsWith("https://") -> "https://"
            lower.startsWith("http://") -> "http://"
            else -> return false
        }
        val authority = base.substring(prefix.length).substringBefore('/')
        return authority.isNotEmpty() && !authority.startsWith(':') && !authority.contains('@')
    }

    /** The screen handler saves the typed address, and the store is then read as the app reads it. */
    private fun savedThenRead(typed: String): String {
        val store = FakeSettingsStore(NONDEFAULT)
        val handler = SettingsIntentHandler(
            store,
            ThemeController(store, systemIsDark = false),
            SettingsScreen(),
        )
        handler.handle(ScreenIntent.SetSetting("server_url", typed))
        return store.load().serverUrl
    }

    @Test
    fun `a typed address is what the app reads back from the store`() {
        for ((typed, _) in samples) {
            assertEquals("read back for $typed", typed, savedThenRead(typed))
        }
    }

    @Test
    fun `a stored address gives the sign-in link builder a link with the typed address`() {
        for ((typed, prefix) in samples) {
            val result = buildSignInLink(savedThenRead(typed), attempt())
            assertTrue("a link for $typed", result is SignInLinkResult.Link)
            val url = (result as SignInLinkResult.Link).url
            assertTrue("the link prefix for $typed", url.startsWith(prefix))
            assertTrue("the challenge in the link for $typed", url.contains("#challenge=$challenge&app_key="))
        }
    }

    @Test
    fun `a stored address passes the stand-in for the server speech check`() {
        for ((typed, _) in samples) {
            assertTrue("the speech check for $typed", serverSpeechAccepts(savedThenRead(typed)))
        }
    }

    @Test
    fun `a bare host with no scheme gives the sign-in link builder no link`() {
        val result = buildSignInLink("box.example", attempt())
        assertTrue("a bare host gives no link", result is SignInLinkResult.Refused)
    }

    @Test
    fun `the speech stand-in refuses the texts the address rule refuses`() {
        assertFalse("a space", serverSpeechAccepts("https://box local"))
        assertFalse("no scheme", serverSpeechAccepts("box.example"))
        assertFalse("no host", serverSpeechAccepts("https://:8080"))
        assertFalse("a user name", serverSpeechAccepts("https://u@box.example"))
        assertFalse("a control character", serverSpeechAccepts("https://box\u0001.example"))
    }
}
