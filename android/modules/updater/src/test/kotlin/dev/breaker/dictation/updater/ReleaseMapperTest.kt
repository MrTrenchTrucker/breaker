package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.shared.api.UpdateManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseMapperTest {

    private fun manifest(version: String = "0.0.0.1",
                         code: Long = 1L,
                         apkUrl: String = "https://example.com/app.apk",
                         sha256: String = "digest",
                         signature: String = "signature",
                         notes: String = "notes"): UpdateManifest =
        UpdateManifest(version, code, apkUrl, sha256, signature, notes)

    @Test
    fun `mapper maps all five fields`() {
        val m = manifest("1.2.3", 7L, "https://srv.apk/apk", "aaaa", "bbbb", "cccc")
        val release = ReleaseMapper.toRelease(m)
        assertNotNull("android_updater: mapper maps all five fields", release)
        assertEquals("android_updater: version", "1.2.3", release!!.version)
        assertEquals("android_updater: apkUrl", "https://srv.apk/apk", release.apkUrl)
        assertEquals("android_updater: sha256", "aaaa", release.sha256)
        assertEquals("android_updater: signature", "bbbb", release.signature)
        assertEquals("android_updater: notes", "cccc", release.notes)
    }

    @Test
    fun `unchecked non-blank signature passes through unchanged`() {
        val m = manifest(signature = "  x ")
        val release = ReleaseMapper.toRelease(m)
        assertNotNull("android_updater: unchecked non-blank signature passes through", release)
        assertEquals("android_updater: signature kept as is", "  x ", release!!.signature)
    }

    @Test
    fun `blank version gives null`() {
        assertNull("android_updater: blank version gives null",
            ReleaseMapper.toRelease(manifest(version = "   ")))
    }

    @Test
    fun `blank apkUrl gives null`() {
        assertNull("android_updater: blank apkUrl gives null",
            ReleaseMapper.toRelease(manifest(apkUrl = "   ")))
    }

    @Test
    fun `blank sha256 gives null`() {
        assertNull("android_updater: blank sha256 gives null",
            ReleaseMapper.toRelease(manifest(sha256 = "   ")))
    }

    @Test
    fun `blank signature gives null`() {
        assertNull("android_updater: blank signature gives null",
            ReleaseMapper.toRelease(manifest(signature = "   ")))
    }

    @Test
    fun `empty signature gives null`() {
        assertNull("android_updater: empty signature gives null",
            ReleaseMapper.toRelease(manifest(signature = "")))
    }

    @Test
    fun `blank notes is fine`() {
        val release = ReleaseMapper.toRelease(manifest(notes = ""))
        assertNotNull("android_updater: blank notes is fine", release)
        assertEquals("android_updater: notes empty string", "", release!!.notes)
    }
}
