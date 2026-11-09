package dev.breaker.shared.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update manifest carries the spec minimum of its version code in its init block: a code of 1
 * or more builds, a code below 1 is refused with an IllegalArgumentException naming the field.
 */
class UpdateManifestTest {

    private fun manifest(code: Long): UpdateManifest =
        UpdateManifest("0.0.0.1", code, "https://example.com/app.apk", "digest", "signature", "notes")

    @Test
    fun `an update manifest with version code 1 builds and keeps its fields`() {
        val built = manifest(1L)
        assertEquals("version code", 1L, built.versionCode)
        assertEquals("version", "0.0.0.1", built.version)
    }

    @Test
    fun `an update manifest with version code 0 is refused naming the field`() {
        val refusal = try {
            manifest(0L)
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertTrue("version code 0 must be refused", refusal != null)
        assertTrue("the refusal names versionCode", refusal!!.message!!.contains("versionCode"))
    }

    @Test
    fun `an update manifest with a negative version code is refused`() {
        val refusal = try {
            manifest(-5L)
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertTrue("a negative version code must be refused", refusal != null)
    }
}
