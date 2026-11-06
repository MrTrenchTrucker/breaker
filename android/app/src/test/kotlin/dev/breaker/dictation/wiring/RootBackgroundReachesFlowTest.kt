package dev.breaker.dictation.wiring

import dev.breaker.dictation.BreakerCompositionRoot
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A background that only counts the blocks it is given and never runs them. */
private class RbCountingBackground : Background {
    var submits: Int = 0

    override fun submit(block: () -> Unit) {
        submits++
    }
}

/**
 * The background given to the root reaches the return flow, through the primary constructor and
 * through the supplier constructor. The blocks are never run, so no network is reached.
 */
class RootBackgroundReachesFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val view = "android.intent.action.VIEW"
    private val link: String = "breaker://handoff?code=" + "Q".repeat(43)

    private fun controller() = DictationServiceController(FakePermission(), FakeLauncher())

    private fun primaryRoot(background: Background?): BreakerCompositionRoot =
        if (background == null) {
            BreakerCompositionRoot(
                filesDir = tmp.newFolder("primary-default"),
                historyStore = RecordingHistoryStore(),
                serviceController = controller(),
                committer = FakeCommitter(),
            )
        } else {
            BreakerCompositionRoot(
                filesDir = tmp.newFolder("primary"),
                historyStore = RecordingHistoryStore(),
                serviceController = controller(),
                committer = FakeCommitter(),
                background = background,
            )
        }

    private fun supplierRoot(background: Background?): BreakerCompositionRoot =
        if (background == null) {
            BreakerCompositionRoot(
                filesDir = tmp.newFolder("supplier-default"),
                history = { RecordingHistoryStore() },
                serviceController = controller(),
                committer = FakeCommitter(),
            )
        } else {
            BreakerCompositionRoot(
                filesDir = tmp.newFolder("supplier"),
                history = { RecordingHistoryStore() },
                serviceController = controller(),
                committer = FakeCommitter(),
                background = background,
            )
        }

    private fun assertSubmittedOnceAndNothingRecorded(
        root: BreakerCompositionRoot,
        fake: RbCountingBackground,
    ) {
        root.signInHolder.begin()
        assertTrue("wiring: handle must answer true", root.handoffReturnFlow.handle(view, link))
        assertEquals("wiring: the return must be submitted to the given background once", 1, fake.submits)
        assertNull("wiring: nothing may be recorded before the block runs", root.signInReturnStore.take())
    }

    @Test
    fun `the primary constructor hands its background to the return flow`() {
        val fake = RbCountingBackground()
        assertSubmittedOnceAndNothingRecorded(primaryRoot(fake), fake)
    }

    @Test
    fun `the supplier constructor hands its background to the return flow`() {
        val fake = RbCountingBackground()
        assertSubmittedOnceAndNothingRecorded(supplierRoot(fake), fake)
    }

    @Test
    fun `a root built without a background records at once on the calling thread`() {
        for (root in listOf(primaryRoot(null), supplierRoot(null))) {
            assertTrue("wiring: handle must answer true", root.handoffReturnFlow.handle(view, link))
            assertEquals(
                "wiring: with no waiting attempt the default records before handle returns",
                SignInReturnState.NO_ATTEMPT,
                root.signInReturnStore.take(),
            )
        }
    }
}
