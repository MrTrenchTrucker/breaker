package dev.breaker.dictation.history

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The history adapter's delete-all and paging bodies, read as text.
 *
 * `SqliteHistoryDatabase` runs in no JVM test, and the twin hard-codes the walk and the bind order,
 * so a change to the adapter's own body would leave every twin test green. These checks read the
 * adapter's source through [AdapterStatements] and pin the walk and the bind order.
 */
class HistoryAdapterDeleteAllSourceTest {

    private fun body(method: String): String = AdapterStatements.bodyOf(AdapterStatements.adapterSource(), method)

    /** HIST_R3: a failure here means the clear filters to USER tombstones, or bounds its walk to the newest 1000. */
    @Test
    fun `deleteAllTombstones walks every tombstone, of every reason`() {
        val text = body("deleteAllTombstones")
        assertTrue(
            "android_history: the clear must walk every tombstone",
            Regex("newestTombstones\\s*\\(\\s*Int\\.MAX_VALUE\\s*\\)").containsMatchIn(text),
        )
        assertFalse("android_history: the clear must not filter to USER", text.contains("newestUserTombstones"))
        assertFalse("android_history: the clear must not bound its walk to 1000", text.contains("newestTombstones(1000)"))
    }

    /** HIST_R3: a failure here means the paging query binds its two arguments in the wrong order. */
    @Test
    fun `the paging query binds lastId then limit`() {
        val text = body("newestUserTombstonesAfter")
        assertTrue(
            "android_history: the paging query must bind lastId then limit",
            Regex("arrayOf\\s*\\(\\s*lastId\\s*,\\s*limit\\.toString\\s*\\(\\s*\\)\\s*\\)").containsMatchIn(text),
        )
    }
}
