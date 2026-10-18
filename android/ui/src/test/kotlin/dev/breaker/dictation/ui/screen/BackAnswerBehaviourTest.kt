package dev.breaker.dictation.ui.screen

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.ui.screen.history.Cancellation
import dev.breaker.dictation.ui.screen.history.DelayedWork
import dev.breaker.dictation.ui.screen.history.HistoryIntentHandler
import dev.breaker.dictation.ui.screen.history.HistoryScreen
import dev.breaker.dictation.ui.screen.history.TimestampFormat
import dev.breaker.dictation.ui.screen.onboarding.PlatformStatus
import dev.breaker.dictation.ui.screen.onboarding.SetupIntentHandler
import dev.breaker.dictation.ui.screen.onboarding.SetupScreen
import dev.breaker.dictation.ui.screen.settings.SettingsIntentHandler
import dev.breaker.dictation.ui.screen.settings.SettingsScreen
import dev.breaker.dictation.ui.testing.FakeBreakerSwitch
import dev.breaker.dictation.ui.testing.FakeClipboardSink
import dev.breaker.dictation.ui.testing.FakeHistoryStore
import dev.breaker.dictation.ui.testing.FakeSettingsStore
import dev.breaker.dictation.ui.testing.FakeSetupPlatform
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.dictation.ui.theme.ThemeController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What the Back intent answers on the three screens that own a branch for it. The answer is the
 * screen as it was drawn, with nothing read from or written to the store, the phone, the switch,
 * the clipboard or the scheduler. A refusal the set-up screen holds stays on the screen.
 */
class BackAnswerBehaviourTest {
    private class ManualWindow(val work: () -> Unit) {
        var cancelled: Boolean = false
    }

    /** Keeps every window it is asked for; nothing here waits on a clock or a thread. */
    private class ManualScheduler : DelayedWork {
        val windows: MutableList<ManualWindow> = mutableListOf()

        override fun schedule(delayMs: Long, work: () -> Unit): Cancellation {
            val window = ManualWindow(work)
            windows.add(window)
            return Cancellation { window.cancelled = true }
        }

        /** The windows that have not been cancelled. */
        fun open(): List<ManualWindow> = windows.filter { !it.cancelled }
    }

    private class HistoryFixture(rows: List<Transcription>) {
        val store = FakeHistoryStore(rows.toMutableList())
        val clipboard = FakeClipboardSink()
        val scheduler = ManualScheduler()
        val handler = HistoryIntentHandler(store, clipboard, TimestampFormat { "at $it" }, scheduler, HistoryScreen())

        /** Every count a Back must leave unchanged: store reads, deletes, copies and windows opened. */
        fun counts(): List<Any> = listOf(
            store.listCalls.toList(),
            store.deleted.toList(),
            clipboard.calls.toList(),
            scheduler.windows.size,
        )
    }

    private class SetupFixture {
        val platform = FakeSetupPlatform(NOTHING_GRANTED)
        val switch = FakeBreakerSwitch(on = false)
        val handler = SetupIntentHandler(
            platform,
            switch,
            { false },
            { null },
            { 0 },
            { },
            SetupScreen(),
        )

        /** Every call the phone and the switch have taken so far. */
        fun calls(): List<Int> = listOf(
            platform.statusCalls,
            platform.opened.size,
            switch.isOnCalls,
            switch.switchOnCalls,
            switch.switchOffCalls,
        )
    }

    private companion object {
        val NOTHING_GRANTED = PlatformStatus(
            overlay = false,
            microphone = false,
            notifications = false,
            accessibility = false,
            sdkInt = 36,
        )

        fun transcriptionOf(id: String) = Transcription(
            id = id,
            text = "words $id",
            source = TranscriptionSource.LOCAL,
            model = "tiny",
            durationMs = 1000L,
            createdAt = 1000L,
        )
    }

    @Test
    fun `back on the settings screen draws the same screen and saves nothing`() {
        val store = FakeSettingsStore(NONDEFAULT)
        val handler = SettingsIntentHandler(store, ThemeController(store, systemIsDark = false), SettingsScreen())
        val before = handler.current().screen
        val after = handler.handle(ScreenIntent.Back).screen
        assertEquals("settings: Back draws what was drawn", before, after)
        assertEquals("settings: Back saves nothing", 0, store.saveAttempts)
        assertEquals("settings: the stored settings are as they were", NONDEFAULT, store.current)
    }

    @Test
    fun `back on the settings screen keeps a message already on the screen`() {
        val store = FakeSettingsStore(NONDEFAULT)
        val handler = SettingsIntentHandler(store, ThemeController(store, systemIsDark = false), SettingsScreen())
        val shown = handler.handle(ScreenIntent.Home("CLOSE")).screen
        assertEquals("settings: Back keeps the message", shown, handler.handle(ScreenIntent.Back).screen)
        assertEquals("settings: Back saves nothing", 0, store.saveAttempts)
    }

    @Test
    fun `back on the history screen changes no row and touches no store, clipboard or window`() {
        val f = HistoryFixture(listOf(transcriptionOf("a"), transcriptionOf("b")))
        f.handler.current()
        val afterDelete = f.handler.handle(ScreenIntent.History(HistoryActions.DELETE, "a"))
        val counts = f.counts()
        val afterBack = f.handler.handle(ScreenIntent.Back)
        assertEquals("history: Back draws what was drawn", afterDelete, afterBack)
        assertEquals("history: Back touches nothing", counts, f.counts())
        assertEquals("history: the window the delete opened is still open", 1, f.scheduler.open().size)
    }

    @Test
    fun `back on the set-up screen draws the same screen and asks the phone and the switch nothing`() {
        val f = SetupFixture()
        val before = f.handler.current()
        val counts = f.calls()
        val after = f.handler.handle(ScreenIntent.Back)
        assertEquals("set-up: Back draws what was drawn", before, after)
        assertEquals("set-up: Back asks nothing new", counts, f.calls())
    }

    @Test
    fun `back on the set-up screen keeps a refusal the app reported`() {
        val f = SetupFixture()
        val refused = f.handler.switchRefused("Not now")
        val counts = f.calls()
        val after = f.handler.handle(ScreenIntent.Back)
        assertEquals("set-up: Back keeps the pinned refusal", refused, after)
        assertEquals("set-up: Back asks nothing new", counts, f.calls())
        assertNotEquals("set-up: the refusal is what the screen shows", SetupFixture().handler.current(), after)
    }
}
