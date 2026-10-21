package dev.breaker.dictation.sync

/**
 * An ordered record of the steps the test doubles saw, shared by all of them in one test.
 *
 * Each double adds one word when it is called (`probe`, `head`, `next`, `insert`, `remove`,
 * `upload`), so a test can check not only that something happened but in what order, and that a
 * step which must not happen left no word behind.
 */
internal class CallLog {

    /** Every word added so far, oldest first. */
    val events = ArrayList<String>()

    /** Adds [event] to the end of the record. */
    fun add(event: String) {
        events.add(event)
    }

    /** How many times [event] has been recorded. */
    fun count(event: String): Int = events.count { it == event }

    /** The words recorded after the first [mark] words, for a test that looks at one call only. */
    fun since(mark: Int): List<String> = events.subList(mark, events.size).toList()

    /** Forgets everything recorded so far, so a test can ignore its own set-up. */
    fun clear() {
        events.clear()
    }
}
