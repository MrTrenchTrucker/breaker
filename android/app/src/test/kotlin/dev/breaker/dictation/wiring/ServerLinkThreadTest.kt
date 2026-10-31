package dev.breaker.dictation.wiring

import dev.breaker.dictation.BreakerCompositionRoot
import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.host.SerialBackground
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The server engine is never called on the calling thread: not while the dictation is built, and not in a take. */
class ServerLinkThreadTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `the server engine is not called while the dictation is built`() {
        val engine = ServerLinkEngine()
        val root = BreakerCompositionRoot(
            filesDir = tmp.root,
            historyStore = RecordingHistoryStore(),
            serviceController = serverLinkArmed(),
            committer = FakeCommitter(),
            micSource = ScriptedMic(),
            serverEngineFactory = { _, _ -> engine },
        )
        assertEquals("app: a new root should be idle", DictationState.IDLE, root.dictation.runner.sessionState)
        assertEquals("app: building the dictation must not call the server engine", 0, engine.calls)
    }

    @Test
    fun `the server engine runs on the take worker and never on the calling thread`() {
        val engine = ServerLinkEngine()
        val mic = ScriptedMic()
        val controller = serverLinkArmed()
        val root = BreakerCompositionRoot(
            filesDir = tmp.root,
            historyStore = RecordingHistoryStore(),
            serviceController = controller,
            committer = FakeCommitter(),
            micSource = mic,
            serverEngineFactory = { _, _ -> engine },
        )
        root.settingsStore.save(AppSettings().copy(mode = SttMode.SERVER, serverUrl = "https://session.example.test"))
        val begun = CompletableDeferred<Unit>()
        val settled = CompletableDeferred<Unit>()
        val owner = TakeOwner()
        val adapter = HomeTakeAdapter(
            take = OwnedTakePort(TakePortAdapter(root.dictation.runner), owner, TakeOwnerKind.HOME),
            owner = owner,
            settle = TakeSettle(null, FakeTakenClipboard()),
            switch = ArmedSwitch(controller, MemoryOffStore()),
            background = SerialBackground("breaker-dictation"),
            main = ServerLinkInlineMain(),
            onLine = { },
            onState = { state ->
                when (state) {
                    DictationState.RECORDING -> begun.complete(Unit)
                    DictationState.IDLE -> settled.complete(Unit)
                    else -> Unit
                }
            },
        )
        adapter.onTap()
        awaitBounded("the begin on the take worker", begun)
        awaitBounded("the capture to read twice", mic.secondReadEntered)
        adapter.onTap()
        awaitBounded("the finish to settle on the take worker", settled)
        val thread: Thread = awaitBounded("the server engine to be called", engine.firstCallThread)
        assertEquals("app: the server engine must run on the take worker", "breaker-dictation", thread.name)
        assertNotSame("app: the server engine must not run on the calling thread", Thread.currentThread(), thread)
        assertEquals("app: the take should call the server engine once", 1, engine.calls)
    }
}
