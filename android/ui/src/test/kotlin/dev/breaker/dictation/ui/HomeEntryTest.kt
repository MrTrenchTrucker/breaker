package dev.breaker.dictation.ui

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The Home entry, checked on its source text.
 *
 * The entry takes onHowItWorks and onAbout as parameters and passes them to
 * the view it builds. Each rule reads the entry source with comments and
 * literal text removed, and is shown to fire on an edited copy of that source
 * before it is held on the real one.
 */
class HomeEntryTest {

    private val entry = mainSourceOf("HomeEntry.kt")

    @Test
    fun `the entry takes onHowItWorks and onAbout`() {
        val flat = withoutCommentsAndStrings(entry).replace(Regex("""\s+"""), "")
        assertTrue("the entry does not take onHowItWorks", flat.contains("onHowItWorks:()->Unit"))
        assertTrue("the entry does not take onAbout", flat.contains("onAbout:()->Unit"))
    }

    @Test
    fun `the entry passes onHowItWorks and onAbout to the view`() {
        val flat = withoutCommentsAndStrings(entry).replace(Regex("""\s+"""), "")
        assertTrue("the entry does not pass onHowItWorks to the view", flat.contains("onHowItWorks,"))
        assertTrue("the entry does not pass onAbout to the view", flat.contains("onAbout,"))
    }

    @Test
    fun `an entry that drops onHowItWorks is reported`() {
        val edited = entry.replace("    onHowItWorks: () -> Unit,\n", "")
        val flat = withoutCommentsAndStrings(edited).replace(Regex("""\s+"""), "")
        assertTrue("the edited entry still takes onHowItWorks", !flat.contains("onHowItWorks:()->Unit"))
    }

    @Test
    fun `an entry that drops onAbout is reported`() {
        val edited = entry.replace("    onAbout: () -> Unit,\n", "")
        val flat = withoutCommentsAndStrings(edited).replace(Regex("""\s+"""), "")
        assertTrue("the edited entry still takes onAbout", !flat.contains("onAbout:()->Unit"))
    }
}
