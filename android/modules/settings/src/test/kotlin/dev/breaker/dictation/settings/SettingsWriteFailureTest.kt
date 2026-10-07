package dev.breaker.dictation.settings

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * An I/O failure is an error, not a damaged key: it propagates out of both
 * `save` and `load`, and a failed write leaves the keystore port untouched.
 *
 * The unreachable paths below are made by putting a regular file where a
 * directory would have to be, so `mkdirs` cannot succeed and the open cannot
 * either.
 */
class SettingsWriteFailureTest : SettingsFileStoreTestBase() {

    /** A path whose parent is a regular file: mkdirs cannot make it. */
    private fun unreachableFile(): File {
        val blocker = newFile("not-a-directory")
        blocker.writeText("occupied")
        return File(blocker, "settings.properties")
    }

    @Test
    fun `a write that cannot happen propagates instead of being swallowed`() {
        // The store must let the failure out — a silently dropped write would
        // leave the user believing a change was kept.
        val thrown = try {
            store(unreachableFile()).save(validSettings())
            null
        } catch (error: IOException) {
            error
        }

        assertNotNull("save() swallowed the write failure", thrown)
    }

    @Test
    fun `a failed write does not point the keystore at a reference that was never stored`() {
        val keystore = FakeKeystore()

        try {
            store(unreachableFile(), keystore).save(validSettings().copy(apiKeyRef = "REFOFFAILED123"))
        } catch (expected: IOException) {
            // The propagation itself is asserted by the test above.
        }

        assertNull(
            "the port was pointed at a reference whose settings were never written",
            keystore.activeRef(),
        )
    }

    /**
     * A file that cannot be read is an error, not a damaged key. The path is a
     * directory: it exists, so the store reads it and the open fails with an
     * [IOException]. Per-line recovery must not reach the read itself — the
     * defaults would say settings were lost when nothing was read.
     */
    @Test
    fun `a file that cannot be read propagates instead of being read as damaged`() {
        val unreadable = temp.newFolder("settings-as-a-directory")
        val thrown = try {
            store(unreadable).load()
            null
        } catch (error: IOException) {
            error
        }

        assertNotNull("load() swallowed a read failure", thrown)
    }
}
