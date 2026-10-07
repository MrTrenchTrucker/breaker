package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** The address policy and the redirect handling of the real fetcher. */
class HttpModelFetcherRedirectTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var staging: File

    private val payload = ByteArray(300) { (it % 251).toByte() }

    private val release = HttpFixtures.RELEASE_URL

    @Before
    fun setUp() {
        staging = tmp.newFolder("staging")
    }

    private fun fetch(opener: FakeOpener, limits: DownloadLimits = DownloadLimits(), entry: ModelEntry = testEntry()): Attempt =
        attemptModel(fetcherOf(opener, limits), entry, staging)

    private fun redirects(n: Int): List<Step> = List(n) { serve(redirectTo(release)) }

    private fun refusedAt(location: String, claim: String): Pair<String, FakeOpener> {
        val opener = FakeOpener(serve(redirectTo(location)), serve(okBytes(payload)))
        return fetch(opener).failedReason(claim) to opener
    }

    @Test
    fun `a redirect to an allowed host is followed and the body staged`() {
        for (status in listOf(301, 302, 303, 307, 308)) {
            val opener = FakeOpener(serve(redirectTo(release, status)), serve(okBytes(payload)))
            val file = fetch(opener).fetchedFile("redirect status $status")
            assertArrayEquals("status $status: the body of the second reply is staged", payload, file.readBytes())
            assertEquals("status $status", listOf(testEntry().url, release), opener.requests.map { it.url })
        }
    }

    @Test
    fun `an allowed host is matched without regard to letter case`() {
        val location = "https://RELEASE-ASSETS.GITHUBUSERCONTENT.COM/x?sig=1"
        val opener = FakeOpener(serve(redirectTo(location)), serve(okBytes(payload)))
        fetch(opener).fetchedFile("upper-case host")
        assertEquals(2, opener.requests.size)
    }

    @Test
    fun `a reply with a location that is not a redirect status is a failure and is not followed`() {
        for (status in listOf(300, 304, 305)) {
            val opener = FakeOpener(serve(reply(status, location = release)), serve(okBytes(payload)))
            assertEquals("http $status", fetch(opener).failedReason("status $status with a location"))
            assertEquals("status $status was not followed", 1, opener.requests.size)
        }
    }

    @Test
    fun `a redirect to a host outside the allowed set is refused and that host gets no request`() {
        val cases = mapOf(
            "https://evil.example.com/x?token=abc" to "evil.example.com",
            "https://EVIL.Example.com/x" to "evil.example.com",
            "https://release-assets.githubusercontent.com.evil.example/x" to "release-assets.githubusercontent.com.evil.example",
            "https://evilrelease-assets.githubusercontent.com/x" to "evilrelease-assets.githubusercontent.com",
            "https://githubusercontent.com/x" to "githubusercontent.com",
            "https://api.github.com/other" to "api.github.com",
        )
        for ((location, host) in cases) {
            val (reason, opener) = refusedAt(location, "redirect to $host")
            assertEquals("redirect refused: $host", reason)
            assertEquals("no request to the refused host $host", 1, opener.requests.size)
        }
    }

    @Test
    fun `a redirect to the older release host is refused`() {
        val location = "https://objects.githubusercontent.com/github-production-release-asset-2e65be/1/2?X-Amz-Signature=zzz"
        val (reason, opener) = refusedAt(location, "redirect to the older release host")
        assertEquals("redirect refused: objects.githubusercontent.com", reason)
        assertEquals("that host got no request", 1, opener.requests.size)
    }

    @Test
    fun `a redirect to http is refused`() {
        val (reason, opener) = refusedAt("http://release-assets.githubusercontent.com/x", "redirect to http")
        assertTrue("reason: $reason", reason.startsWith("redirect refused"))
        assertEquals("no second request", 1, opener.requests.size)
    }

    @Test
    fun `a redirect carrying user info is refused`() {
        val (reason, opener) = refusedAt("https://user:pw@release-assets.githubusercontent.com/x", "redirect with user info")
        assertTrue("reason: $reason", reason.startsWith("redirect refused"))
        assertFalse("the credentials are not echoed", reason.contains("pw") || reason.contains("user:") || reason.contains("@"))
        assertEquals("no second request", 1, opener.requests.size)
    }

    @Test
    fun `a redirect to a port other than 443 is refused and port 443 is accepted`() {
        val (reason, opener) = refusedAt("https://release-assets.githubusercontent.com:8443/x", "redirect to port 8443")
        assertTrue("reason: $reason", reason.startsWith("redirect refused"))
        assertEquals("no second request", 1, opener.requests.size)
        val explicit = "https://release-assets.githubusercontent.com:443/x?sig=1"
        val ok = FakeOpener(serve(redirectTo(explicit)), serve(okBytes(payload)))
        fetch(ok).fetchedFile("explicit port 443")
        assertEquals(listOf(testEntry().url, explicit), ok.requests.map { it.url })
    }

    @Test
    fun `exactly the maximum number of redirects is followed and one more is refused`() {
        val two = DownloadLimits(maxRedirects = 2)
        val atMax = FakeOpener(redirects(2) + serve(okBytes(payload)))
        fetch(atMax, two).fetchedFile("two redirects under a limit of two")
        assertEquals("three requests", 3, atMax.requests.size)
        val over = FakeOpener(redirects(3) + serve(okBytes(payload)))
        assertEquals("too many redirects", fetch(over, two).failedReason("three redirects under a limit of two"))
        assertEquals("the fourth request was never made", 3, over.requests.size)
    }

    @Test
    fun `a limit of zero follows no redirect and a limit of five is the default`() {
        val none = DownloadLimits(maxRedirects = 0)
        fetch(FakeOpener(serve(okBytes(payload))), none).fetchedFile("a direct reply under a limit of zero")
        val first = FakeOpener(redirects(1) + serve(okBytes(payload)))
        assertEquals("too many redirects", fetch(first, none).failedReason("one redirect under a limit of zero"))
        assertEquals(1, first.requests.size)
        fetch(FakeOpener(redirects(5) + serve(okBytes(payload)))).fetchedFile("five redirects by default")
        val six = FakeOpener(redirects(6) + serve(okBytes(payload)))
        assertEquals("too many redirects", fetch(six).failedReason("six redirects by default"))
        assertEquals(6, six.requests.size)
    }

    @Test
    fun `a relative location is resolved against the current address and checked`() {
        val (reason, first) = refusedAt("/github-production-release-asset/1/abc?sig=x", "relative location on the first hop")
        assertEquals("resolved against the first host, which is not allowed on a later hop", "redirect refused: api.github.com", reason)
        assertEquals(1, first.requests.size)
        val opener = FakeOpener(serve(redirectTo(release)), serve(redirectTo("/second?sig=2")), serve(okBytes(payload)))
        fetch(opener).fetchedFile("relative location on a later hop")
        assertEquals("https://${HttpFixtures.RELEASE_HOST}/second?sig=2", opener.requests[2].url)
        val (reason2, second) = refusedAt("//evil.example.com/x", "scheme-relative location")
        assertEquals("redirect refused: evil.example.com", reason2)
        assertEquals(1, second.requests.size)
    }

    @Test
    fun `a redirect with no location fails`() {
        for (status in listOf(301, 302, 303, 307, 308)) {
            val opener = FakeOpener(serve(redirectTo(null, status)), serve(okBytes(payload)))
            assertEquals("redirect without location", fetch(opener).failedReason("status $status without a location"))
            assertEquals(1, opener.requests.size)
        }
    }

    @Test
    fun `a first address that is not https on the first hop host makes no request`() {
        val bad = listOf(
            "http://api.github.com/repos/x",
            "https://evil.example.com/x",
            "https://release-assets.githubusercontent.com/x",
            "https://objects.githubusercontent.com/x",
            "https://user@api.github.com/x",
            "https://api.github.com:8443/x",
            "ftp://api.github.com/x",
            "file:///etc/passwd",
            "//api.github.com/x",
            "api.github.com/x",
        )
        for (url in bad) {
            val opener = FakeOpener(serve(okBytes(payload)))
            val reason = fetch(opener, entry = testEntry(url = url)).failedReason("first address $url")
            assertTrue("first address $url: reason '$reason'", reason.startsWith("address refused"))
            assertEquals("first address $url makes no request", 0, opener.requests.size)
        }
        val list = FakeOpener(serve(okBytes(payload)))
        val limits = DownloadLimits(checksumsUrl = "http://api.github.com/list")
        assertTrue(attemptChecksums(fetcherOf(list, limits), staging).failedReason("insecure checksum address").startsWith("address refused"))
        assertEquals("no request for the checksum address", 0, list.requests.size)
    }

    @Test
    fun `a first address on the first hop host is accepted with port 443 and in any letter case`() {
        for (url in listOf("https://api.github.com:443/x", "https://API.GITHUB.COM/x")) {
            val opener = FakeOpener(serve(okBytes(payload)))
            fetch(opener, entry = testEntry(url = url)).fetchedFile("first address $url")
            assertEquals(listOf(url), opener.requests.map { it.url })
        }
    }

    @Test
    fun `an address that cannot be parsed fails without a request`() {
        val opener = FakeOpener(serve(okBytes(payload)))
        val reason = fetch(opener, entry = testEntry(url = "https://exa mple.com/x")).failedReason("unparseable address")
        assertTrue("a short reason is given: '$reason'", reason.isNotEmpty() && !reason.contains("exa mple"))
        assertEquals(0, opener.requests.size)
    }

    @Test
    fun `a redirect location that cannot be parsed fails without a second request`() {
        val (reason, opener) = refusedAt("https://exa mple.com/x?sig=${HttpFixtures.SECRET}", "unparseable location")
        assertTrue("a short reason is given: '$reason'", reason.isNotEmpty() && !reason.contains(HttpFixtures.SECRET))
        assertEquals("no second request", 1, opener.requests.size)
    }

    @Test
    fun `the failure reason after a redirect names no query string or full address`() {
        val secret = HttpFixtures.SECRET
        val outcomes = listOf(
            refusedAt("https://evil.example.com/p/a/t/h?sig=$secret", "refused signed redirect").first,
            fetch(FakeOpener(serve(redirectTo(release)), serve(reply(status = 403)))).failedReason("403 after redirect"),
            fetch(FakeOpener(redirects(2)), DownloadLimits(maxRedirects = 1)).failedReason("too many signed redirects"),
            fetch(FakeOpener(serve(redirectTo(release)), failWith(IOException("failed $release")))).failedReason("network error"),
            fetch(FakeOpener(serve(redirectTo(release)), serve(reply(body = FailingStream(10, IOException("cut $release")), contentLength = 99))))
                .failedReason("read error after a redirect"),
        )
        for (reason in outcomes) {
            assertFalse("reason '$reason' carries the token", reason.contains(secret))
            assertFalse("reason '$reason' carries a query", reason.contains("?") || reason.contains("sig="))
            assertFalse("reason '$reason' carries an address", reason.contains("://") || reason.contains("/"))
        }
    }
}
