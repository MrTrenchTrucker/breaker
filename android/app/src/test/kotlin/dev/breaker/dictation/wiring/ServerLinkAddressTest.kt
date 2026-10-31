package dev.breaker.dictation.wiring

import dev.breaker.dictation.BreakerCompositionRoot
import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The server address the engine and the probe use is the one saved in the settings, read at each call. */
class ServerLinkAddressTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `the engine reads the saved address again at each call`() {
        var address: (() -> String)? = null
        val root = BreakerCompositionRoot(
            filesDir = tmp.root,
            historyStore = RecordingHistoryStore(),
            serviceController = serverLinkArmed(),
            committer = FakeCommitter(),
            micSource = ScriptedMic(),
            serverEngineFactory = { url, _ ->
                address = url
                ServerLinkEngine()
            },
        )
        root.settingsStore.save(AppSettings().copy(mode = SttMode.SERVER, serverUrl = "https://first.example.test"))
        assertEquals("app: a new root should be idle", DictationState.IDLE, root.dictation.runner.sessionState)
        val read: () -> String = checkNotNull(address) { "app: the engine factory was not given the address" }
        assertEquals("app: the engine should be given the saved address", "https://first.example.test", read())
        root.settingsStore.save(AppSettings().copy(mode = SttMode.SERVER, serverUrl = "https://second.example.test"))
        assertEquals("app: the same reader should give the address saved last", "https://second.example.test", read())
    }

    @Test
    fun `the probe dials the stored address and a reachable answer sends an automatic take to the server engine`() {
        val listener = ServerLinkListener()
        try {
            val engine = ServerLinkEngine()
            var engineAddress: (() -> String)? = null
            val mic = ScriptedMic()
            val root = BreakerCompositionRoot(
                filesDir = tmp.root,
                historyStore = RecordingHistoryStore(),
                serviceController = serverLinkArmed(),
                committer = FakeCommitter(),
                micSource = mic,
                serverEngineFactory = { url, _ ->
                    engineAddress = url
                    engine
                },
            )
            val address = "http://" + serverLinkLoopbackAddress.hostAddress + ":" + listener.port
            root.settingsStore.save(AppSettings().copy(mode = SttMode.AUTO, serverUrl = address))
            val result = serverLinkOneTake(root) { awaitBounded("the capture to read twice", mic.secondReadEntered) }
            val read: () -> String = checkNotNull(engineAddress) { "app: the engine factory was not given the address" }
            assertEquals("app: the engine should be given the stored address", address, read())
            assertEquals("app: the probe should have dialled the stored address once", 1, listener.pending())
            assertEquals("app: the automatic take should reach the server engine once", 1, engine.calls)
            assertTrue("app: a reachable server should end the take ready to send, got $result", result is FinishResult.ReadyToSend)
        } finally {
            listener.close()
        }
    }
}
