package dev.breaker.dictation

import dev.breaker.dictation.history.SqliteHistoryStore
import org.junit.Assert.assertNotNull
import org.junit.Test

/** The app module reaches the history store's sweep: this fails to compile if the method stops being public. */
class HistorySweepVisibilityTest {

    @Test
    fun `the history store offers a public sweep that answers a count`() {
        val sweep: (SqliteHistoryStore) -> Int = SqliteHistoryStore::sweepExpiredTombstones
        assertNotNull("android_app: the app can reach the sweep", sweep)
    }
}
