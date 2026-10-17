package dev.breaker.dictation.ui.screen

import dev.breaker.dictation.ui.theme.PaletteSlot

/*
 * A screen, described rather than drawn.
 *
 * Each screen in this module is a function from the domain's state to a tree of
 * nodes. The tree carries no colour values: every painted attribute is a
 * PaletteSlot name, resolved to a token value by the theme at the last moment.
 * There is no field on this tree a literal colour could be written into, so
 * "the screens render with ui-tokens" is a property of the types.
 *
 * The whole appearance of a screen is therefore checkable in a plain JVM unit
 * test, with no device and no Android framework class.
 */

/** One element of a screen tree, in the order the layout stack draws them. */
internal sealed class Node {
    /** Stable identity, for tests and for view reuse. */
    abstract val id: String
}

/** A filled block of background: a screen, a card or a panel. */
internal data class Box(
    override val id: String,
    val background: PaletteSlot,
    val children: List<Node> = emptyList(),
    /** Lays the children out in a row rather than a column. */
    val horizontal: Boolean = false,
) : Node()

/** A run of text in one of the three type roles, painted in one palette slot. */
internal data class Label(
    override val id: String,
    val text: String,
    val role: TypeRole,
    val color: PaletteSlot,
    val align: TextAlign = TextAlign.START,
) : Node()

/** A control the user can act on. It is at least the token touch-target size. */
internal data class Action(
    override val id: String,
    val text: String,
    val enabled: Boolean = true,
    val emphasis: Emphasis = Emphasis.SECONDARY,
    /** What the screen reports back when this control is chosen. */
    val intent: ScreenIntent,
    /** The kit control this action is drawn as, or null for the plain button. */
    val kit: KitLook? = null,
) : Node()

/** A single horizontal rule in the trim colour. */
internal data class TrimStripe(override val id: String) : Node()

/** A short word in an outlined pill, such as a source tag. */
internal data class TagPill(override val id: String, val text: String, val color: PaletteSlot = PaletteSlot.TEXT_MUTED) : Node()

/** A run of text that is cut to a few lines while it is closed, and shown whole while it is open. */
internal data class CollapsibleText(override val id: String, val text: String, val color: PaletteSlot, val opened: Boolean) : Node()

/** One cell of a [SegmentedRow]: data, not a node, so it is never drawn as a child. */
internal data class SegmentCell(val id: String, val text: String, val selected: Boolean, val intent: ScreenIntent)

/** A row of cells where exactly one is selected. */
internal data class SegmentedRow(override val id: String, val cells: List<SegmentCell>) : Node()

/** A label, a help sentence and a switch on one row. */
internal data class SwitchRow(override val id: String, val label: String, val help: String, val on: Boolean, val intent: ScreenIntent) : Node()

/** A text shown in a field that the user cannot edit. */
internal data class ValueField(override val id: String, val text: String, val muted: Boolean) : Node()

/** The look of a kit control: the raised face, the neutral face, the danger face or the outlined face. */
internal enum class KitLook { RAISED, NEUTRAL, DANGER, OUTLINED }

/** The three type roles the design names. */
internal enum class TypeRole { DISPLAY, BODY, MONO }

/** Horizontal alignment of text. */
internal enum class TextAlign { START, CENTER, END }

/** How prominent an action is: [PRIMARY] is the main path, [DESTRUCTIVE] removes something. */
internal enum class Emphasis { PRIMARY, SECONDARY, DESTRUCTIVE }

/**
 * Something a screen asks the layer above it to do.
 *
 * The hierarchy is sealed so a new request is one more subclass here and a new
 * branch where intents are handled; no other type has to change.
 */
internal sealed class ScreenIntent {
    /** Switch the theme to the opposite of the mode now on screen. */
    data object ToggleTheme : ScreenIntent()

    /** Set the theme back to following the phone's own light or dark mode. */
    data object UseSystemTheme : ScreenIntent()

    /** Change the transcription routing mode to [mode], named by the mode's enum name. */
    data class SetRoutingMode(val mode: String) : ScreenIntent()

    /** Set the theme to the stored mode [mode], named by its enum name: SYSTEM, LIGHT or DARK. */
    data class SetThemeMode(val mode: String) : ScreenIntent()

    /** Change the setting named [key] to [value], given as text. */
    data class SetSetting(val key: String, val value: String) : ScreenIntent()

    /** Do the step of the setup walk-through named by [action], such as opening a page or switching on. */
    data class Setup(val action: String) : ScreenIntent()

    /** Do the step of the history screen named by [action] (see [HistoryActions]) on the row [id]. */
    data class History(val action: String, val id: String = "") : ScreenIntent()

    /** Do the step of the home screen named by [action] (see home's HomeActions). */
    data class Home(val action: String) : ScreenIntent()
}

/** The action names a [ScreenIntent.History] may carry. */
internal object HistoryActions {
    const val COPY = "COPY"
    const val DELETE = "DELETE"
    const val UNDO = "UNDO"
    const val MORE = "MORE"
    const val SHOW_ALL = "SHOW_ALL"
    const val HIDE = "HIDE"
}

/** A whole screen: an identity, a title and the tree that makes it up. */
internal data class Screen(
    val id: String,
    val title: String,
    val nodes: List<Node>,
)

/** This node and every node below it, depth first, parents before children. */
internal fun Node.flatten(): List<Node> = buildList {
    add(this@flatten)
    if (this@flatten is Box) this@flatten.children.forEach { addAll(it.flatten()) }
}
