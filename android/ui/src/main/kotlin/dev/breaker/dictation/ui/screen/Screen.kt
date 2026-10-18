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
    /** A stroke around the box, in this slot, or null for no stroke. */
    val outline: PaletteSlot? = null,
    /** A bar down the left edge of the box, in this slot, or null for no bar. */
    val edge: PaletteSlot? = null,
    /** The intent the box reports when it is tapped, or null when a tap does nothing. */
    val onClick: ScreenIntent? = null,
    /** The space the box keeps inside its edges on all four sides. */
    val pad: Space = Space.NONE,
    /** The space between consecutive children; none before the first. */
    val gap: Space = Space.NONE,
    /** In a horizontal box, every child keeps its own width and the free width is shared between the children; gap is not applied then. A vertical box ignores it. */
    val spread: Boolean = false,
) : Node()

/** A run of text in one of the three type roles, painted in one palette slot. */
internal data class Label(
    override val id: String,
    val text: String,
    val role: TypeRole,
    val color: PaletteSlot,
    val align: TextAlign = TextAlign.START,
    /** Drawn in capitals; the text itself stays as written. */
    val caps: Boolean = false,
    /** Drawn in the italic face of its role. */
    val italic: Boolean = false,
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
    /** Whether the kit control takes the compact width: only the History entry rows set it. */
    val compact: Boolean = false,
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

/** A three-line menu icon the host draws as three horizontal lines. */
internal data class MenuIcon(
    override val id: String,
    val intent: ScreenIntent,
    /** The words a screen reader says for the icon, or null for none. */
    val description: String? = null,
) : Node()

/** A switch the host draws as a track with a knob, the knob on or off. */
internal data class Switch(
    override val id: String,
    val on: Boolean,
    val intent: ScreenIntent,
    /** The words a screen reader says for the switch, or null for none. */
    val description: String? = null,
) : Node()

/** A text shown in a field that the user cannot edit. */
internal data class ValueField(override val id: String, val text: String, val muted: Boolean) : Node()

/** A thin full-width line in one palette slot. */
internal data class Rule(override val id: String, val color: PaletteSlot) : Node()

/**
 * The brand stripes, drawn full width at the bottom of the screen. [plain] is the three-bar look
 * of the Settings and History screens; the Home look has four bars.
 */
internal data class BottomStripes(override val id: String, val plain: Boolean = false) : Node()

/** A drawn back arrow that reports [intent] when it is tapped. */
internal data class BackArrow(
    override val id: String,
    val intent: ScreenIntent,
    /** The words a screen reader says for the arrow, or null for none. */
    val description: String? = null,
) : Node()

/** An empty block of [size]: a height in a column and a width in a row. It has no children. */
internal data class Spacer(override val id: String, val size: Space) : Node()

/** A small filled square in one palette slot, drawn before a status word. It has no children. */
internal data class Dot(override val id: String, val color: PaletteSlot) : Node()

/** An empty block the size of a touch target, with no words and no focus. It has no children. */
internal data class TouchGap(override val id: String) : Node()

/** The look of a kit control: the raised face, the neutral face, the danger face, the outlined face or a plain text row. */
internal enum class KitLook { RAISED, NEUTRAL, DANGER, OUTLINED, TEXT }

/** A named distance on the spacing scale of the design tokens; NONE is no distance. A name, never a number. */
internal enum class Space { NONE, XS, S, M, L, XL }

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

    /** Go back to the screen before this one, where a screen has one. */
    data object Back : ScreenIntent()
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
