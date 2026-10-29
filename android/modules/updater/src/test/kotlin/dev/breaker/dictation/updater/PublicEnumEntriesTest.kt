package dev.breaker.dictation.updater

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the entries of the two public enums of the install module, the whole list in its declared order. */
internal class PublicEnumEntriesTest {

    @Test
    fun `the Shown enum has the entries NOTHING, INSTALLED and SENTENCE in that order`() {
        assertEquals(listOf(Shown.NOTHING, Shown.INSTALLED, Shown.SENTENCE), Shown.values().toList())
    }

    @Test
    fun `the CommitStatus enum has its six entries in the order of the commit results`() {
        assertEquals(
            listOf(
                CommitStatus.Success,
                CommitStatus.PendingUserAction,
                CommitStatus.Aborted,
                CommitStatus.RefusedByInstaller,
                CommitStatus.StorageFailure,
                CommitStatus.OtherFailure,
            ),
            CommitStatus.values().toList(),
        )
    }
}
