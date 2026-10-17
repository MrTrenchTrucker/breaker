package dev.breaker.dictation.wiring

import dev.breaker.dictation.stt.ondevice.ModelInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

internal class InstallerPortProgressTest {

    private val entry = modelEntryFor("probe")

    /** A port whose progress installer and plain installer both answer [result]. */
    private fun bothForms(result: ModelInstaller.InstallResult): InstallerPort =
        InstallerPort(
            installerWithProgress = { _, _ -> result },
            installer = { result },
        )

    @Test
    fun `the three-argument install gives the callback to the progress installer`() {
        val seen: MutableList<Pair<Long, Long>> = ArrayList()
        val port = InstallerPort(
            installerWithProgress = { e, progress ->
                progress(365_748_162L, 365_748_162L)
                ModelInstaller.InstallResult.Installed(e.id, "digest")
            },
            installer = { throw IllegalStateException("app: the plain installer ran") },
        )
        val outcome = port.install(entry) { received, total -> seen.add(received to total) }
        assertSame("app: the answer is Installed", InstallOutcome.Installed, outcome)
        assertEquals(
            "app: the callback saw the pair the progress installer sent",
            listOf(365_748_162L to 365_748_162L),
            seen,
        )
    }

    @Test
    fun `with no progress installer the three-argument form uses the plain installer and never calls the callback`() {
        var plainCalls = 0
        var reports = 0
        val port = InstallerPort { e ->
            plainCalls += 1
            ModelInstaller.InstallResult.Installed(e.id, "digest")
        }
        val outcome = port.install(entry) { _, _ -> reports += 1 }
        assertSame("app: the answer is Installed", InstallOutcome.Installed, outcome)
        assertEquals("app: the plain installer ran once", 1, plainCalls)
        assertEquals("app: the callback was never called", 0, reports)
    }

    @Test
    fun `Installed maps to Installed in both forms`() {
        val port = bothForms(ModelInstaller.InstallResult.Installed("probe", "digest"))
        assertSame("app: the one-argument form maps Installed", InstallOutcome.Installed, port.install(entry))
        assertSame("app: the callback form maps Installed", InstallOutcome.Installed, port.install(entry) { _, _ -> })
    }

    @Test
    fun `Refused keeps its installer sentence in both forms`() {
        val port = bothForms(
            ModelInstaller.InstallResult.Refused(ModelInstaller.Refusal.FETCH_FAILED, "The model could not be downloaded."),
        )
        val expected = InstallOutcome.Refused("The model could not be downloaded.")
        assertEquals("app: the one-argument form maps Refused with its sentence", expected, port.install(entry))
        assertEquals("app: the callback form maps Refused with its sentence", expected, port.install(entry) { _, _ -> })
    }

    @Test
    fun `NO_SPACE maps to NoSpace in both forms`() {
        val port = bothForms(ModelInstaller.InstallResult.Refused(ModelInstaller.Refusal.NO_SPACE, "technical detail"))
        assertSame("app: the one-argument form maps NO_SPACE to NoSpace", InstallOutcome.NoSpace, port.install(entry))
        assertSame("app: the callback form maps NO_SPACE to NoSpace", InstallOutcome.NoSpace, port.install(entry) { _, _ -> })
    }
}
