package dev.breaker.dictation.commit.accessibility

import dev.breaker.dictation.commit.FieldCommit
import dev.breaker.dictation.commit.FocusedField

/**
 * The field that has input focus, as the commit module sees it: it puts dictated text into
 * whichever field has input focus at the moment it is asked.
 *
 * Each call looks the field up afresh through [finder], and gives the node back before it
 * returns, whatever happened on the way. It never reads before it must (an empty text does
 * not even look at the screen, and a field that is turned away has none of its text read)
 * and it never keeps a node or any text. It never throws: anything that goes wrong is a
 * refusal, and the caller then takes the text somewhere else. No text, node content or
 * exception message is logged, stored or put into a message.
 *
 * A field that belongs to the app named by [ownPackage] is never a target.
 *
 * @param finder where the input-focused node comes from, asked once per call
 * @param ownPackage the package of this app; a field that belongs to it is refused
 */
internal class NodeFocusedField(
    private val finder: FocusedNodeFinder,
    private val ownPackage: String,
) : FocusedField {

    /** Put [text] into the focused field and say whether the field took it. */
    override fun commitText(text: String): FieldCommit =
        try {
            findAndInsert(text)
        } catch (e: Exception) {
            FieldCommit.REFUSED
        }

    /** Look the field up, insert into it, and give the node back on every way out. */
    private fun findAndInsert(text: String): FieldCommit {
        if (text.isEmpty()) return FieldCommit.REFUSED
        val node: FieldNode = finder.findInputFocus() ?: return FieldCommit.REFUSED
        try {
            return insertInto(node, text)
        } finally {
            giveBack(node)
        }
    }

    /** Hand the node back; a node that fails to go back cannot change what was already decided. */
    private fun giveBack(node: FieldNode) {
        try {
            node.release()
        } catch (e: Exception) {
            // Nothing is left to do with a node that will not go back, and the answer stands.
        }
    }

    /**
     * Check the node, merge [dictated] into its text and set the result.
     *
     * The flags are read first, and the password flag first among them: a field that must
     * never be touched is turned away before any of its text or selection is read.
     */
    private fun insertInto(node: FieldNode, dictated: String): FieldCommit {
        if (!node.refresh()) return FieldCommit.REFUSED
        if (node.packageName == ownPackage) return FieldCommit.REFUSED
        if (node.isPassword) return FieldCommit.REFUSED
        if (!node.isEditable) return FieldCommit.REFUSED
        if (!node.isEnabled) return FieldCommit.REFUSED

        val showingHint: Boolean = node.isShowingHint
        val limit: Int = node.maxTextLength
        val current: String = node.text ?: ""
        val start: Int = node.selectionStart
        val end: Int = node.selectionEnd

        // The flags above are already clean; InsertPlan checks them again as a second line.
        val state = FieldState(
            text = current,
            selectionStart = start,
            selectionEnd = end,
            isShowingHint = showingHint,
            isPassword = false,
            isEditable = true,
            isEnabled = true,
            maxTextLength = limit,
        )
        return when (val outcome: InsertOutcome = InsertPlan.plan(state, dictated)) {
            is Refused -> FieldCommit.REFUSED
            is Inserted -> place(node, outcome)
        }
    }

    /** Set the merged text, then put the cursor after the inserted part. */
    private fun place(node: FieldNode, inserted: Inserted): FieldCommit {
        if (!node.setText(inserted.newText)) return FieldCommit.REFUSED
        try {
            node.setSelection(inserted.cursor, inserted.cursor)
        } catch (e: Exception) {
            // The text is in; a cursor that stays where it was does not undo that.
        }
        return FieldCommit.ACCEPTED
    }
}
