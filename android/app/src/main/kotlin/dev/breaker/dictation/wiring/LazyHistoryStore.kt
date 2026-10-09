package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.port.HistoryStore

/**
 * A [HistoryStore] that builds the real store on first use, not at construction.
 *
 * The store behind it opens a database, so the app should not pay for that (or fail on it) just
 * because something else was wired. [supplier] is called once, by the first call of any member,
 * and every call then goes to the store it returned.
 *
 * The cell is `lazy` in its synchronized mode, so two threads that reach the first use together
 * cannot both build a store. That mode does not keep a failure: when [supplier] throws, the
 * exception reaches the caller and the next use calls [supplier] again.
 */
class LazyHistoryStore(private val supplier: () -> HistoryStore) : HistoryStore {
    private val store: HistoryStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { supplier() }

    override fun save(transcription: Transcription) = store.save(transcription)

    override fun list(limit: Int): List<Transcription> = store.list(limit)

    override fun delete(id: String): Boolean = store.delete(id)
}
