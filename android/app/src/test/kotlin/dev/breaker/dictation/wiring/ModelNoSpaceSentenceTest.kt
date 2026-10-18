package dev.breaker.dictation.wiring

import dev.breaker.dictation.stt.ondevice.ModelInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the no-space sentence word for word, and the mapping of the installer's refusal categories
 * by the install port: only the no-space category becomes its own outcome, every other refusal keeps
 * the installer's sentence as it did before.
 */
internal class ModelNoSpaceSentenceTest {

    @Test
    fun `the no space sentence is the fixed text`() {
        assertEquals(
            "app: the no space sentence changed",
            "Not enough free space for the speech model. Free up some space.",
            ModelSentences.DOWNLOAD_NO_SPACE,
        )
    }

    @Test
    fun `the no space sentence is printable ASCII with no address and no path`() {
        val text = ModelSentences.DOWNLOAD_NO_SPACE
        assertTrue("app: the sentence holds only printable ASCII", text.all { it.code in 0x20..0x7e })
        assertTrue("app: the sentence is 63 characters or fewer", text.length <= 63)
        assertFalse("app: the sentence holds no http", text.contains("http"))
        assertFalse("app: the sentence holds no slash", text.contains("/"))
        assertFalse("app: the sentence holds no backslash", text.contains("\\"))
        assertTrue("app: the sentence names the free space", text.contains("free space"))
    }

    @Test
    fun `the no space sentence differs from the download sentence and from the no model sentence`() {
        assertFalse("app: the no space sentence is not the download sentence", ModelSentences.DOWNLOAD_NO_SPACE == ModelSentences.DOWNLOAD_FAILED)
        assertFalse("app: the no space sentence is not the no model sentence", ModelSentences.DOWNLOAD_NO_SPACE == ModelSentences.NO_MODEL)
    }

    @Test
    fun `a no space refusal maps to the no space outcome object`() {
        val entry = modelEntryFor("probe")
        val port = InstallerPort { ModelInstaller.InstallResult.Refused(ModelInstaller.Refusal.NO_SPACE, "x") }
        assertSame("app: a no space refusal maps to the NoSpace object", InstallOutcome.NoSpace, port.install(entry))
    }

    @Test
    fun `every other refusal maps to a refused outcome that keeps its detail`() {
        val entry = modelEntryFor("probe")
        var checked = 0
        for (refusal in ModelInstaller.Refusal.values()) {
            if (refusal == ModelInstaller.Refusal.NO_SPACE) continue
            val port = InstallerPort { ModelInstaller.InstallResult.Refused(refusal, "x") }
            assertEquals("app: $refusal maps to a refused outcome with the detail", InstallOutcome.Refused("x"), port.install(entry))
            checked += 1
        }
        assertEquals("app: every refusal but no space was checked", ModelInstaller.Refusal.values().size - 1, checked)
        assertTrue("app: at least nine other refusals were checked", checked >= 9)
    }
}
