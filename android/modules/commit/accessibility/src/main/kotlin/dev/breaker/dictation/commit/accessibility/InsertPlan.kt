package dev.breaker.dictation.commit.accessibility

/**
 * What an insert needs to know about a text field, copied from the field at one moment.
 *
 * It carries the user's own text. Never log it, store it, or put it in a message or
 * a string built from it. [toString] prints a fixed text for that reason; this is a
 * plain class on purpose, because a data class would print every property.
 *
 * Offsets are UTF-16 units, the way the platform reports them.
 *
 * @property text the field's current text, or "" when the field has none
 * @property selectionStart where the selection starts, or -1 when there is none
 * @property selectionEnd where the selection ends, or -1 when there is none
 * @property isShowingHint true when [text] is the field's hint and not the user's text
 * @property isPassword true for a password field
 * @property isEditable true when the field accepts typed text
 * @property isEnabled true when the field is switched on
 * @property maxTextLength the longest text the field accepts; a negative value means no limit
 */
internal class FieldState(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val isShowingHint: Boolean,
    val isPassword: Boolean,
    val isEditable: Boolean,
    val isEnabled: Boolean,
    val maxTextLength: Int,
) {
    override fun toString(): String = "FieldState(redacted)"
}

/** Why a text was not inserted. Each reason is a fixed name; none ever holds text. */
internal enum class Refusal {
    /** The field is a password field. Nothing is ever put into one. */
    PASSWORD,

    /** The field does not accept typed text. */
    NOT_EDITABLE,

    /** The field is switched off. */
    NOT_ENABLED,

    /** There was nothing to insert. */
    EMPTY_TEXT,

    /** The new text would be longer than the field accepts. */
    TOO_LONG,
}

/** The answer of [InsertPlan.plan]: either the text to set, or the reason for not touching the field. */
internal sealed interface InsertOutcome

/**
 * The field's whole new text and where the cursor goes once it is set.
 *
 * It carries the user's text. Never log it, store it or put it in a message;
 * [toString] prints a fixed text for that reason.
 *
 * @property newText the complete text the field should hold
 * @property cursor the cursor position after the inserted text, in UTF-16 units of [newText]
 */
internal class Inserted(val newText: String, val cursor: Int) : InsertOutcome {
    override fun toString(): String = "Inserted(redacted)"
}

/**
 * The field was left alone, for [reason].
 *
 * The reason is one of a few fixed names, so it is safe to print.
 *
 * @property reason why nothing was inserted
 */
internal class Refused(val reason: Refusal) : InsertOutcome {
    override fun toString(): String = "Refused(" + reason.name + ")"
}

/**
 * Merges dictated text into a field's current text, as pure logic with no platform types.
 *
 * Setting a node's text replaces all of it, so the merge at the cursor has to be done
 * here: read the text and selection, build the new text, and let the caller set it.
 */
internal object InsertPlan {

    /** A start and an end, both inside the base text, with start at or before end. */
    private class Span(val start: Int, val end: Int)

    /**
     * Decide what [dictated] does to the field described by [state].
     *
     * The first rule that applies wins: a password field, a field that is not editable,
     * a field that is not enabled, or an empty [dictated] is refused; otherwise [dictated]
     * replaces the selection (or goes in at the cursor, or at the end when the field
     * reports no selection) exactly as given, with no spacing, trimming or case change.
     * A hint is not the user's text: it is dropped and the insert starts from nothing.
     * A selection or cursor never splits a surrogate pair. A result longer than the
     * field's limit is refused and is never cut short.
     *
     * It never throws, whatever the selection values are.
     */
    fun plan(state: FieldState, dictated: String): InsertOutcome {
        if (state.isPassword) return Refused(Refusal.PASSWORD)
        if (!state.isEditable) return Refused(Refusal.NOT_EDITABLE)
        if (!state.isEnabled) return Refused(Refusal.NOT_ENABLED)
        if (dictated.isEmpty()) return Refused(Refusal.EMPTY_TEXT)

        val base: String = if (state.isShowingHint) "" else state.text
        val span: Span = spanToReplace(state, base)
        val newText: String = base.substring(0, span.start) + dictated + base.substring(span.end)
        val cursor: Int = span.start + dictated.length

        if (state.maxTextLength >= 0 && newText.length > state.maxTextLength) {
            return Refused(Refusal.TOO_LONG)
        }
        return Inserted(newText, cursor)
    }

    /** The part of [base] the dictated text replaces; start == end means a plain insert point. */
    private fun spanToReplace(state: FieldState, base: String): Span {
        if (state.isShowingHint) return Span(0, 0)
        if (state.selectionStart < 0 || state.selectionEnd < 0) return Span(base.length, base.length)

        val first: Int = state.selectionStart.coerceIn(0, base.length)
        val second: Int = state.selectionEnd.coerceIn(0, base.length)
        return outsidePairs(base, minOf(first, second), maxOf(first, second))
    }

    /**
     * Move [low] and [high] off the middle of a surrogate pair.
     *
     * A cursor (low == high) inside a pair moves down to the start of the pair. In a real
     * selection the start inside a pair moves down to the pair's start and the end inside
     * a pair moves up to the pair's end, so no half pair is kept or cut.
     */
    private fun outsidePairs(base: String, low: Int, high: Int): Span {
        if (low == high) {
            val point: Int = if (insidePair(base, low)) low - 1 else low
            return Span(point, point)
        }
        val start: Int = if (insidePair(base, low)) low - 1 else low
        val end: Int = if (insidePair(base, high)) high + 1 else high
        return Span(start, end)
    }

    /** True when [position] lies between the two halves of a surrogate pair in [text]. */
    private fun insidePair(text: String, position: Int): Boolean =
        position > 0 &&
            position < text.length &&
            text[position - 1].isHighSurrogate() &&
            text[position].isLowSurrogate()
}
