package dev.breaker.dictation.ui.screen.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.CollapsibleText
import dev.breaker.dictation.ui.screen.Emphasis
import dev.breaker.dictation.ui.screen.HistoryActions
import dev.breaker.dictation.ui.screen.KitLook
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.TagPill
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.FakeClipboardSink
import dev.breaker.dictation.ui.testing.FakeHistoryStore
import dev.breaker.dictation.ui.theme.PaletteSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The nodes one history row is built from, in order, and the toggle that opens and closes its
 * text. The tree is read by id, and the opening and closing go through the real intent handler,
 * so a redraw and a tap are both in play.
 */
class HistoryCardNodesTest {
    private val wholeText = (1..9).joinToString("\n") { "line $it" }

    private class Fixture(vararg rows: Transcription) {
        val store = FakeHistoryStore(rows.toMutableList())
        val clipboard = FakeClipboardSink()
        val work = FakeDelayedWork()
        val handler = HistoryIntentHandler(store, clipboard, stamp, work, HistoryScreen())
    }

    private fun calls(f: Fixture): List<Any> = listOf(
        f.store.listCalls.toList(),
        f.store.deleted.toList(),
        f.clipboard.calls.toList(),
        f.work.windows.size,
    )

    private fun nodesOf(s: Screen) = s.nodes.flatMap { it.flatten() }

    private fun rowBox(s: Screen, id: String): Box =
        s.node("history.row.$id") as? Box ?: throw AssertionError("history: no row $id in ${nodesOf(s).map { it.id }}")

    private fun buttonsOf(s: Screen, id: String): Box =
        s.node("history.row.$id.buttons") as? Box ?: throw AssertionError("history: no button row of $id")

    private fun tagOf(s: Screen, id: String): TagPill =
        s.node("history.row.$id.tag") as? TagPill ?: throw AssertionError("history: no tag of $id")

    private fun textOf(s: Screen, id: String): CollapsibleText =
        s.node("history.row.$id.text") as? CollapsibleText ?: throw AssertionError("history: no text of $id")

    private fun actionOf(s: Screen, id: String): Action =
        s.node(id) as? Action ?: throw AssertionError("history: no action $id in ${nodesOf(s).map { it.id }}")

    private fun labelOf(s: Screen, id: String): Label =
        s.node(id) as? Label ?: throw AssertionError("history: no label $id in ${nodesOf(s).map { it.id }}")

    @Test
    fun `a row is a surface block with its time, tag, text and button row in that order`() {
        val row = rowBox(Fixture(transcription("a")).handler.current(), "a")
        assertEquals("history: row background", PaletteSlot.SURFACE, row.background)
        assertFalse("history: the row stacks its children", row.horizontal)
        assertEquals(
            "history: row children",
            listOf("history.row.a.time", "history.row.a.tag", "history.row.a.text", "history.row.a.buttons"),
            row.children.map { it.id },
        )
    }

    @Test
    fun `the tag is a TagPill that names the source in the muted colour`() {
        val f = Fixture(transcription("l"), transcription("s", source = TranscriptionSource.SERVER))
        val s = f.handler.current()
        assertEquals("history: local tag", HistoryTexts.TAG_LOCAL, tagOf(s, "l").text)
        assertEquals("history: server tag", HistoryTexts.TAG_SERVER, tagOf(s, "s").text)
        assertEquals("history: tag colour", PaletteSlot.TEXT_MUTED, tagOf(s, "l").color)
    }

    @Test
    fun `the text is a CollapsibleText in the text colour and the placeholder in the muted colour when blank`() {
        val f = Fixture(transcription("a", text = "hello"), transcription("e", text = ""), transcription("w", text = "   "))
        val s = f.handler.current()
        assertEquals("history: spoken text", "hello", textOf(s, "a").text)
        assertEquals("history: spoken colour", PaletteSlot.TEXT, textOf(s, "a").color)
        assertFalse("history: spoken text starts closed", textOf(s, "a").opened)
        assertEquals("history: empty placeholder", HistoryTexts.BLANK_TEXT, textOf(s, "e").text)
        assertEquals("history: spaces placeholder", HistoryTexts.BLANK_TEXT, textOf(s, "w").text)
        assertEquals("history: placeholder colour", PaletteSlot.TEXT_MUTED, textOf(s, "e").color)
    }

    @Test
    fun `the button row is horizontal on the surface with copy, toggle and delete in that order`() {
        val buttons = buttonsOf(Fixture(transcription("a")).handler.current(), "a")
        assertTrue("history: buttons are horizontal", buttons.horizontal)
        assertEquals("history: buttons background", PaletteSlot.SURFACE, buttons.background)
        assertEquals(
            "history: button order",
            listOf("history.row.a.copy", "history.row.a.toggle", "history.row.a.delete"),
            buttons.children.map { it.id },
        )
    }

    @Test
    fun `copy, toggle and delete carry their kit looks, emphases and intents`() {
        val s = Fixture(transcription("a")).handler.current()
        val copy = actionOf(s, "history.row.a.copy")
        assertEquals("history: copy look", KitLook.RAISED, copy.kit)
        assertEquals("history: copy emphasis", Emphasis.SECONDARY, copy.emphasis)
        assertEquals("history: copy text", HistoryTexts.BUTTON_COPY, copy.text)
        assertEquals("history: copy intent", ScreenIntent.History(HistoryActions.COPY, "a"), copy.intent)
        val toggle = actionOf(s, "history.row.a.toggle")
        assertEquals("history: toggle look", KitLook.OUTLINED, toggle.kit)
        assertEquals("history: toggle emphasis", Emphasis.SECONDARY, toggle.emphasis)
        val delete = actionOf(s, "history.row.a.delete")
        assertEquals("history: delete look", KitLook.DANGER, delete.kit)
        assertEquals("history: delete emphasis", Emphasis.DESTRUCTIVE, delete.emphasis)
        assertEquals("history: delete text", HistoryTexts.BUTTON_DELETE, delete.text)
        assertEquals("history: delete intent", ScreenIntent.History(HistoryActions.DELETE, "a"), delete.intent)
    }

    @Test
    fun `the toggle reads Show all and sends SHOW_ALL while closed, then Hide and HIDE once open`() {
        val f = Fixture(transcription("a"))
        val closed = actionOf(f.handler.current(), "history.row.a.toggle")
        assertEquals("history: closed toggle text", HistoryTexts.BUTTON_SHOW_ALL, closed.text)
        assertEquals("history: closed toggle intent", ScreenIntent.History(HistoryActions.SHOW_ALL, "a"), closed.intent)
        val afterOpen = f.handler.handle(intent(HistoryActions.SHOW_ALL, "a"))
        val opened = actionOf(afterOpen, "history.row.a.toggle")
        assertEquals("history: open toggle text", HistoryTexts.BUTTON_HIDE, opened.text)
        assertEquals("history: open toggle intent", ScreenIntent.History(HistoryActions.HIDE, "a"), opened.intent)
        assertTrue("history: the text is open", textOf(afterOpen, "a").opened)
    }

    @Test
    fun `SHOW_ALL opens only the row it names and HIDE closes it again`() {
        val f = Fixture(transcription("a"), transcription("b"))
        f.handler.current()
        val one = f.handler.handle(intent(HistoryActions.SHOW_ALL, "a"))
        assertTrue("history: a is open", textOf(one, "a").opened)
        assertFalse("history: b stays closed", textOf(one, "b").opened)
        val back = f.handler.handle(intent(HistoryActions.HIDE, "a"))
        assertFalse("history: a is closed again", textOf(back, "a").opened)
        assertEquals(
            "history: the toggle reads Show all again",
            HistoryTexts.BUTTON_SHOW_ALL,
            actionOf(back, "history.row.a.toggle").text,
        )
    }

    @Test
    fun `an opened row stays open through a later redraw from a theme change or a copy`() {
        val f = Fixture(transcription("a"))
        f.handler.current()
        f.handler.handle(intent(HistoryActions.SHOW_ALL, "a"))
        val themed = f.handler.handle(ScreenIntent.SetThemeMode("DARK"))
        assertTrue("history: open after a theme change", textOf(themed, "a").opened)
        val copied = f.handler.handle(intent(HistoryActions.COPY, "a"))
        assertTrue("history: open after a copy", textOf(copied, "a").opened)
        assertEquals(
            "history: toggle still reads Hide",
            HistoryTexts.BUTTON_HIDE,
            actionOf(copied, "history.row.a.toggle").text,
        )
    }

    @Test
    fun `an unknown id changes nothing and makes no call, for Show all or Hide`() {
        val f = Fixture(transcription("a"))
        val before = f.handler.current()
        val callsBefore = calls(f)
        assertEquals(
            "history: unknown Show all draws the same screen",
            before,
            f.handler.handle(intent(HistoryActions.SHOW_ALL, "zzz")),
        )
        assertEquals(
            "history: unknown Hide draws the same screen",
            before,
            f.handler.handle(intent(HistoryActions.HIDE, "zzz")),
        )
        assertEquals("history: no store, clipboard or window call", callsBefore, calls(f))
        f.store.save(transcription("zzz"))
        val later = f.handler.current()
        assertEquals(
            "history: a row saved with the unknown id is drawn closed",
            HistoryTexts.BUTTON_SHOW_ALL,
            actionOf(later, "history.row.zzz.toggle").text,
        )
        assertFalse("history: its text is closed", textOf(later, "zzz").opened)
    }

    @Test
    fun `the set-theme intent only draws the screen again and touches nothing else`() {
        val f = Fixture(transcription("a"))
        f.handler.current()
        val callsBefore = calls(f)
        val drawn = f.handler.handle(ScreenIntent.SetThemeMode("LIGHT"))
        assertEquals("history: set theme makes no call", callsBefore, calls(f))
        assertEquals("history: the row is drawn", listOf("history.row.a"), drawn.rowIds())
    }

    @Test
    fun `copy passes the whole stored text of a long row whether it is collapsed or open`() {
        val f = Fixture(transcription("a", text = wholeText))
        assertEquals("history: the text node holds the whole text", wholeText, textOf(f.handler.current(), "a").text)
        f.handler.handle(intent(HistoryActions.COPY, "a"))
        assertEquals("history: closed copy", listOf(wholeText), f.clipboard.calls)
        f.handler.handle(intent(HistoryActions.SHOW_ALL, "a"))
        f.handler.handle(intent(HistoryActions.COPY, "a"))
        assertEquals("history: open copy", listOf(wholeText, wholeText), f.clipboard.calls)
    }

    @Test
    fun `LOAD MORE is neutral under a full page below the cap`() {
        val rows = listOf(HistoryRow("a", "TIME", TranscriptionSource.LOCAL, "words"))
        val s = HistoryScreen().render(HistoryState(rows = rows, pending = emptySet(), shown = PAGE_SIZE, fullPage = true), null)
        val more = actionOf(s, "history.more")
        assertEquals("history: load more look", KitLook.NEUTRAL, more.kit)
        assertEquals("history: load more emphasis", Emphasis.SECONDARY, more.emphasis)
        assertEquals("history: load more text", HistoryTexts.BUTTON_LOAD_MORE, more.text)
    }

    @Test
    fun `the limit note replaces LOAD MORE at the cap and a short page shows neither`() {
        val rows = listOf(HistoryRow("a", "TIME", TranscriptionSource.LOCAL, "words"))
        val atCap = HistoryScreen().render(HistoryState(rows, emptySet(), ROW_CAP, fullPage = true), null)
        assertEquals("history: limit note", HistoryTexts.LIMIT_NOTE, labelOf(atCap, "history.limit").text)
        assertNull("history: no LOAD MORE at the cap", atCap.node("history.more"))
        val short = HistoryScreen().render(HistoryState(rows, emptySet(), PAGE_SIZE, fullPage = false), null)
        assertNull("history: no limit note on a short page", short.node("history.limit"))
        assertNull("history: no LOAD MORE on a short page", short.node("history.more"))
    }

    @Test
    fun `the empty state is a heading and a hint that join back to EMPTY with one space`() {
        val s = HistoryScreen().render(HistoryState(emptyList(), emptySet(), PAGE_SIZE, false), null)
        val heading = labelOf(s, "history.empty")
        val hint = labelOf(s, "history.empty.hint")
        assertEquals("history: heading and hint", HistoryTexts.EMPTY, heading.text + " " + hint.text)
        assertEquals(
            "history: heading and hint as literals",
            listOf("Nothing here yet.", "What you dictate will show up here."),
            listOf(heading.text, hint.text),
        )
        assertEquals("history: EMPTY as a literal", "Nothing here yet. What you dictate will show up here.", HistoryTexts.EMPTY)
        assertTrue("history: the heading ends its sentence", heading.text.endsWith("."))
        val emptyIds = s.nodes.map { it.id }.filter { it.startsWith("history.empty") }
        assertEquals("history: heading before hint", listOf("history.empty", "history.empty.hint"), emptyIds)
        assertEquals("history: heading role", TypeRole.BODY, heading.role)
        assertEquals("history: hint colour", PaletteSlot.TEXT_MUTED, hint.color)
    }

    @Test
    fun `the history tree draws only in the surface, text and muted text slots`() {
        val rows = listOf(
            HistoryRow("a", "TIME", TranscriptionSource.LOCAL, "words"),
            HistoryRow("e", "TIME", TranscriptionSource.SERVER, ""),
        )
        val state = HistoryState(rows = rows, pending = emptySet(), shown = PAGE_SIZE, fullPage = false, opened = setOf("a"))
        val nodes = nodesOf(HistoryScreen().render(state, null))
        val used = nodes.mapNotNull { n ->
            when (n) {
                is Label -> n.color
                is TagPill -> n.color
                is CollapsibleText -> n.color
                is Box -> n.background
                else -> null
            }
        }.toSet()
        assertEquals(
            "history: the slots this tree draws in",
            setOf(PaletteSlot.TEXT, PaletteSlot.TEXT_MUTED, PaletteSlot.SURFACE),
            used,
        )
    }

    @Test
    fun `the new words and action names are exactly as agreed`() {
        assertEquals("history: show all word", "Show all", HistoryTexts.BUTTON_SHOW_ALL)
        assertEquals("history: hide word", "Hide", HistoryTexts.BUTTON_HIDE)
        assertEquals("history: show all action", "SHOW_ALL", HistoryActions.SHOW_ALL)
        assertEquals("history: hide action", "HIDE", HistoryActions.HIDE)
    }
}
