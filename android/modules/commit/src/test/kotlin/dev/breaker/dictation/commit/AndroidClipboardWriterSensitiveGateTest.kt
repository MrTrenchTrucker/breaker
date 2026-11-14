package dev.breaker.dictation.commit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate for the private-clip flag in the clipboard writer. The writer talks to the device, so no JVM test can run it;
 * what a plain JVM can read is its text. The rule: a clip is marked private inside the branch that tests the sensitive
 * argument, the mark is the true value under the platform's key, and the clip is set after the branch. Each rule runs
 * first on a made-up source that obeys it, which must stay quiet, then on a mutant built from the real source with one
 * replace, which must be reported; only then is the real source asserted quiet. Comments are removed and string
 * literals are kept out of the code the rule reads, so the key is checked on the raw text of its own line.
 */
internal class AndroidClipboardWriterSensitiveGateTest {
    private val writer: String = SourceFiles.mainSources().getValue("adapter/AndroidClipboardWriter.kt")

    private val branchTest: String = "if (sensitive) {"
    private val markTrue: String = "extras.putBoolean(IS_SENSITIVE_KEY, true)"
    private val attach: String = "clip.description.setExtras(extras)"
    private val keyLine: String = "const val IS_SENSITIVE_KEY: String = \"android.content.extra.IS_SENSITIVE\""

    /** The branch as it must read once comments are gone and white space is dropped. */
    private val wholeBranch: String = flat(
        "if (sensitive) { val extras = PersistableBundle() extras.putBoolean(IS_SENSITIVE_KEY, true) " +
            "clip.description.setExtras(extras) } clipboard.setPrimaryClip(clip)",
    )

    /** [text] as code with every run of white space dropped, so a line compares however it is wrapped. */
    private fun flat(text: String): String = SourceFiles.strip(text).code.replace(Regex("""\s+"""), "")

    /** [text] with [old] replaced by [new]; fails unless [old] is in [text] exactly once, so a mutant can never be a no-op. */
    private fun replaceOnce(text: String, old: String, new: String): String {
        assertTrue("commit: sensitive gate: the text to change is not in the source exactly once: $old", text.split(old).size == 2)
        return text.replace(old, new)
    }

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("commit: sensitive gate: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    /** The problems with the private-clip flag in [source]. */
    private fun problems(source: String): List<String> = listOfNotNull(
        "the private mark is not set inside the branch that tests sensitive, before the clip is set".takeIf { !flat(source).contains(wholeBranch) },
        "the key of the private mark is not the platform key".takeIf { !source.contains(keyLine) },
    )

    private val good = """
        internal class W {
            fun copy(text: String, sensitive: Boolean): Boolean {
                if (sensitive) {
                    val extras = PersistableBundle()
                    extras.putBoolean(IS_SENSITIVE_KEY, true)
                    clip.description.setExtras(extras)
                }
                clipboard.setPrimaryClip(clip)
                return true
            }
            private companion object {
                $keyLine
            }
        }
    """

    @Test
    fun `an obeying source is quiet`() {
        assertEquals("commit: sensitive gate: control: the obeying source must not be reported", emptyList<String>(), problems(good))
    }

    @Test
    fun `a clip marked private for the wrong case is reported`() {
        assertFires("the branch test inverted", problems(replaceOnce(writer, branchTest, "if (!sensitive) {")), "private mark")
        assertFires("the branch test inverted on the made-up source", problems(replaceOnce(good, branchTest, "if (!sensitive) {")), "private mark")
    }

    @Test
    fun `a mark that is not true or not attached is reported`() {
        assertFires("a false mark", problems(replaceOnce(writer, markTrue, "extras.putBoolean(IS_SENSITIVE_KEY, false)")), "private mark")
        assertFires("a mark never attached", problems(replaceOnce(writer, attach, "")), "private mark")
        assertFires("a mark attached after the clip is set", problems(replaceOnce(replaceOnce(writer, attach, ""), "clipboard.setPrimaryClip(clip)", "clipboard.setPrimaryClip(clip)\n        $attach")), "private mark")
    }

    @Test
    fun `a changed platform key is reported`() {
        assertFires("another key", problems(replaceOnce(writer, "android.content.extra.IS_SENSITIVE", "android.content.extra.IS_PRIVATE")), "platform key")
    }

    /** A failure here means a dictated clip is no longer marked private, so the paste preview shows the text on Android 13 and later. */
    @Test
    fun `the clipboard writer marks a sensitive clip private before it sets it`() {
        assertEquals("commit: AndroidClipboardWriter.kt must mark a sensitive clip private", emptyList<String>(), problems(writer))
    }
}
