package dev.breaker.dictation.commit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds the one focused field that can take text right now.
 *
 * A text-insert mechanism (ADR-022) publishes its field when it starts typing
 * into one and clears it when it finishes. The commit reads it through
 * [FocusedFieldSource].
 */
internal class FocusedFieldRegistry : FocusedFieldSource {
    /** Identity only. Each publish returns a new one. */
    internal class Token

    private class Entry(val token: Token, val field: FocusedField)

    private val state: MutableStateFlow<Entry?> = MutableStateFlow(null)

    /** Make [field] the current one, replacing any older one, and return its token. */
    fun publish(field: FocusedField): Token {
        val token = Token()
        state.value = Entry(token, field)
        return token
    }

    /**
     * Clear the current field, but only when [token] is still the current one.
     *
     * A late "finished" for an older field must not clear a newer field that has
     * already been published.
     */
    fun clear(token: Token) {
        state.update { entry: Entry? -> if (entry != null && entry.token === token) null else entry }
    }

    /** Clear whatever is current, whichever field it belongs to. */
    fun clearAll() {
        state.value = null
    }

    override fun current(): FocusedField? = state.value?.field
}
