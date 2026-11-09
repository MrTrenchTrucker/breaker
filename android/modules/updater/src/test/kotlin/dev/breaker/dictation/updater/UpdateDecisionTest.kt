package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.shared.api.UpdateManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class UpdateDecisionTest {

    private fun manifest(code: Long,
                         version: String = "0.0.0.1",
                         apkUrl: String = "https://example.com/app.apk",
                         sha256: String = "digest",
                         signature: String = "signature",
                         notes: String = "notes"): UpdateManifest =
        UpdateManifest(version, code, apkUrl, sha256, signature, notes)

    @Test
    fun `installed 5 with manifest 6 is newer`() {
        val m = manifest(6L)
        val decision = UpdateDecision.of(5L, m)
        assertNotNull("android_updater: installed 5 with manifest 6 is newer", decision)
        val expected = ReleaseInfo("0.0.0.1", "https://example.com/app.apk", "digest", "signature", "notes")
        assertEquals("android_updater: same release", expected, (decision as UpdateDecision.Newer).release)
    }

    @Test
    fun `equal code is not newer`() {
        val m = manifest(5L)
        assertEquals("android_updater: equal code is not newer",
            UpdateDecision.NotNewer, UpdateDecision.of(5L, m))
    }

    @Test
    fun `lower code is not newer`() {
        val m = manifest(4L)
        assertEquals("android_updater: lower code is not newer",
            UpdateDecision.NotNewer, UpdateDecision.of(5L, m))
    }

    @Test
    fun `blank version with equal code is not newer`() {
        val m = manifest(5L, version = "   ")
        assertEquals("android_updater: blank version with equal code is not newer",
            UpdateDecision.NotNewer, UpdateDecision.of(5L, m))
    }

    @Test
    fun `blank signature with equal code is not newer`() {
        val m = manifest(5L, signature = "   ")
        assertEquals("android_updater: blank signature with equal code is not newer",
            UpdateDecision.NotNewer, UpdateDecision.of(5L, m))
    }

    @Test
    fun `blank version with lower code is not newer`() {
        val m = manifest(4L, version = "   ")
        assertEquals("android_updater: blank version with lower code is not newer",
            UpdateDecision.NotNewer, UpdateDecision.of(5L, m))
    }

    @Test
    fun `Long MIN installed with manifest 1 is newer`() {
        val m = manifest(1L)
        assertEquals("android_updater: Long MIN installed with manifest 1 is newer",
            ReleaseInfo("0.0.0.1", "https://example.com/app.apk", "digest", "signature", "notes"),
            (UpdateDecision.of(Long.MIN_VALUE, m) as UpdateDecision.Newer).release)
    }

    @Test
    fun `Long MAX installed with manifest 1 is not newer`() {
        val m = manifest(1L)
        assertEquals("android_updater: Long MAX installed with manifest 1 is not newer",
            UpdateDecision.NotNewer, UpdateDecision.of(Long.MAX_VALUE, m))
    }

    @Test
    fun `installed 1 with manifest Long MAX is newer`() {
        val m = manifest(Long.MAX_VALUE)
        assertEquals("android_updater: installed 1 with manifest Long MAX is newer",
            ReleaseInfo("0.0.0.1", "https://example.com/app.apk", "digest", "signature", "notes"),
            (UpdateDecision.of(1L, m) as UpdateDecision.Newer).release)
    }

    @Test
    fun `Long MAX installed with manifest Long MAX is not newer`() {
        val m = manifest(Long.MAX_VALUE)
        assertEquals("android_updater: Long MAX installed with manifest Long MAX is not newer",
            UpdateDecision.NotNewer, UpdateDecision.of(Long.MAX_VALUE, m))
    }

    @Test
    fun `installed 0 with manifest 1 is newer`() {
        val m = manifest(1L)
        assertEquals("android_updater: installed 0 with manifest 1 is newer",
            ReleaseInfo("0.0.0.1", "https://example.com/app.apk", "digest", "signature", "notes"),
            (UpdateDecision.of(0L, m) as UpdateDecision.Newer).release)
    }

    @Test
    fun `blank version with greater code is unusable`() {
        val m = manifest(6L, version = "   ")
        assertEquals("android_updater: blank version with greater code is unusable",
            UpdateDecision.Unusable, UpdateDecision.of(5L, m))
    }

    @Test
    fun `blank apkUrl with greater code is unusable`() {
        val m = manifest(6L, apkUrl = "   ")
        assertEquals("android_updater: blank apkUrl with greater code is unusable",
            UpdateDecision.Unusable, UpdateDecision.of(5L, m))
    }

    @Test
    fun `blank sha256 with greater code is unusable`() {
        val m = manifest(6L, sha256 = "   ")
        assertEquals("android_updater: blank sha256 with greater code is unusable",
            UpdateDecision.Unusable, UpdateDecision.of(5L, m))
    }

    @Test
    fun `blank signature with greater code is unusable`() {
        val m = manifest(6L, signature = "   ")
        assertEquals("android_updater: blank signature with greater code is unusable",
            UpdateDecision.Unusable, UpdateDecision.of(5L, m))
    }

    @Test
    fun `empty signature with greater code is unusable`() {
        val m = manifest(6L, signature = "")
        assertEquals("android_updater: empty signature with greater code is unusable",
            UpdateDecision.Unusable, UpdateDecision.of(5L, m))
    }
}
