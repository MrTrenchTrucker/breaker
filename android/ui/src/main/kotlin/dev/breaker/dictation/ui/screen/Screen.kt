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
) : Node()

/** A single horizontal rule in the trim colour. */
internal data class TrimStripe(override val id: String) : Node()

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

    /** Change the setting named [key] to [value], given as text. */
    data class SetSetting(val key: String, val value: String) : ScreenIntent()

    /** Do the step of the setup walk-through named by [action], such as opening a page or switching on. */
    data class Setup(val action: String) : ScreenIntent()
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
