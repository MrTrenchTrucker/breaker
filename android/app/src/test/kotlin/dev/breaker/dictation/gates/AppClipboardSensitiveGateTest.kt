package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate for the sensitive mark on the two app clipboard writers. Both writers talk to the platform, so no
 * JVM test can run them; what a plain JVM can read is their text. The rule: the clip carries the true mark
 * under the platform's key, the mark is attached to the clip, and the clip reaches the platform only after
 * the mark is set. Writer 1 builds the clip into a val and sets the extras before its setPrimaryClip call;
 * writer 2 hands the clip to setPrimaryClip as an `.also` argument, so its mark must sit inside that call's
 * argument. Each rule runs first on a made-up source that obeys it, which must stay quiet, then on a mutant
 * built from the real source with one replace, which must be reported; only then is the real source asserted
 * quiet. Comments are removed and string literals are kept out of the code the rule reads, so the key is
 * checked on the raw text of its own line.
 */
internal class AppClipboardSensitiveGateTest {

    private val taken: String = "kotlin/dev/breaker/dictation/host/TakenClipboardAndroid.kt"
    private val helpers: String = "kotlin/dev/breaker/dictation/host/ActivityHelpers.kt"

    /** How the mark reaches the clip: writer 1 sets it before the call, writer 2 sets it inside the call's argument. */
    private enum class Shape { VAL, ALSO }

    private val markTrue: String = """putBoolean("android.content.extra.IS_SENSITIVE", true)"""
    private val label: String = """newPlainText("Breaker", text)"""
    private val attach: String = "description.setExtras("
    private val clipCall: String = "setPrimaryClip("

    /** [text] as code with every run of white space dropped, so an index compares however the text is wrapped. */
    private fun flat(text: String): String = AppSourceFiles.strip(text).code.replace(Regex("""\s+"""), "")

    /** [text] with [old] replaced by [new]; fails unless [old] is in [text] exactly once, so a mutant can never be a no-op. */
    private fun replaceOnce(text: String, old: String, new: String): String {
        assertTrue("app: clipboard sensitive gate: the text to change is not in the source exactly once: $old", text.split(old).size == 2)
        return text.replace(old, new)
    }

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("app: clipboard sensitive gate: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    /** The matching close paren of the `(` at [open], or null when the code never closes it. */
    private fun matchingParen(code: String, open: Int): Int? {
        var depth = 0
        var at = open
        while (at < code.length) {
            when (code[at]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return at
                }
            }
            at += 1
        }
        return null
    }

    /** R5: the mark is set before the clip reaches the platform, in the same statement. */
    private fun markBeforeClip(code: String, flattened: String, shape: Shape): Boolean {
        if (shape == Shape.VAL) {
            val markAt: Int = flattened.indexOf(attach)
            val clipAt: Int = flattened.indexOf(clipCall)
            return markAt >= 0 && clipAt >= 0 && markAt < clipAt
        }
        val clipAt: Int = code.indexOf(clipCall)
        if (clipAt < 0) return false
        val open: Int = code.indexOf('(', clipAt)
        if (open < 0) return false
        val close: Int = matchingParen(code, open) ?: return false
        val markInArgs: Int = code.indexOf(attach, open)
        return markInArgs in open until close
    }

    /** The problems with the sensitive mark in [source], read as [shape]. */
    private fun problems(source: String, shape: Shape): List<String> {
        val code: String = AppSourceFiles.strip(source).code
        val flattened: String = flat(source)
        val calls: Int = Regex("""setPrimaryClip\s*\(""").findAll(code).count()
        return listOfNotNull(
            "the file must hold exactly one setPrimaryClip( call, found $calls".takeIf { calls != 1 },
            "the mark is not the platform key with the true value".takeIf { !source.contains(markTrue) },
            "the clip label is not the app's name".takeIf { !source.contains(label) },
            "the mark is not attached to the clip".takeIf { !code.contains(attach) },
            "the mark is not set before the clip reaches the platform".takeIf { !markBeforeClip(code, flattened, shape) },
        )
    }

    private class Sample(val what: String, val old: String, val new: String, val part: String)

    /** Writer 1's approved shape, made up: the val form. */
    private val goodVal: String = """
package dev.breaker.dictation.host

import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData

class MadeUpClipboardAndroid(private val context: Context) {
    fun copy(text: String) {
        val clipboard = context.getSystemService(
            android.content.Context.CLIPBOARD_SERVICE,
        ) as ClipboardManager
        val clip = ClipData.newPlainText("Breaker", text)
        clip.description.setExtras(
            android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) },
        )
        clipboard.setPrimaryClip(clip)
    }
}
"""

    /** Writer 2's approved shape, made up: the `.also` argument form. */
    private val goodAlso: String = """
package dev.breaker.dictation.host

import android.content.Context

internal fun madeUpCopyToClip(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(
        android.content.ClipData.newPlainText("Breaker", text).also { clip ->
            clip.description.setExtras(
                android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) },
            )
        },
    )
}
"""

    private val valSamples: List<Sample> = listOf(
        Sample(
            "the extras block dropped",
            """        clip.description.setExtras(
            android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) },
        )
""",
            "",
            "not attached",
        ),
        Sample(
            "the putBoolean call dropped",
            """.apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }""",
            "",
            "platform key",
        ),
        Sample(
            "a false mark",
            """putBoolean("android.content.extra.IS_SENSITIVE", true)""",
            """putBoolean("android.content.extra.IS_SENSITIVE", false)""",
            "platform key",
        ),
        Sample(
            "a changed key",
            "\"android.content.extra.IS_SENSITIVE\"",
            "\"android.content.extra.IS_PRIVATE\"",
            "platform key",
        ),
        Sample(
            "the mark set after the clip",
            """        clip.description.setExtras(
            android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) },
        )
        clipboard.setPrimaryClip(clip)
""",
            """        clipboard.setPrimaryClip(clip)
        clip.description.setExtras(
            android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) },
        )
""",
            "before the clip",
        ),
    )

    private val alsoSamples: List<Sample> = listOf(
        Sample(
            "the extras block dropped",
            """            clip.description.setExtras(
                android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) },
            )
""",
            "",
            "not attached",
        ),
        Sample(
            "the putBoolean call dropped",
            """.apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }""",
            "",
            "platform key",
        ),
        Sample(
            "a false mark",
            """putBoolean("android.content.extra.IS_SENSITIVE", true)""",
            """putBoolean("android.content.extra.IS_SENSITIVE", false)""",
            "platform key",
        ),
        Sample(
            "a changed key",
            "\"android.content.extra.IS_SENSITIVE\"",
            "\"android.content.extra.IS_PRIVATE\"",
            "platform key",
        ),
        Sample(
            "the mark set after the clip",
            """    clipboard.setPrimaryClip(
        android.content.ClipData.newPlainText("Breaker", text).also { clip ->
            clip.description.setExtras(
                android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) },
            )
        },
    )
""",
            """    clipboard.setPrimaryClip(
        android.content.ClipData.newPlainText("Breaker", text),
    )
    clipboard.primaryClip?.description?.setExtras(
        android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) },
    )
""",
            "before the clip",
        ),
    )

    private fun assertSamples(shape: Shape, writer: String, good: String, samples: List<Sample>) {
        for (sample in samples) {
            assertFires(sample.what, problems(replaceOnce(writer, sample.old, sample.new), shape), sample.part)
            assertFires("${sample.what} on the made-up source", problems(replaceOnce(good, sample.old, sample.new), shape), sample.part)
        }
    }

    @Test
    fun `an obeying source is quiet`() {
        assertEquals("app: clipboard sensitive gate: control: the obeying val source must not be reported", emptyList<String>(), problems(goodVal, Shape.VAL))
        assertEquals("app: clipboard sensitive gate: control: the obeying also source must not be reported", emptyList<String>(), problems(goodAlso, Shape.ALSO))
    }

    @Test
    fun `a dropped, false, changed or late mark is reported on the val writer`() {
        assertSamples(Shape.VAL, AppSourceFiles.mainFile(taken), goodVal, valSamples)
    }

    @Test
    fun `a dropped, false, changed or late mark is reported on the also writer`() {
        assertSamples(Shape.ALSO, AppSourceFiles.mainFile(helpers), goodAlso, alsoSamples)
    }

    /** A failure here means a dictated clip is no longer marked sensitive before it reaches the platform. */
    @Test
    fun `both app clipboard writers mark the clip sensitive before they set it`() {
        assertEquals("app: TakenClipboardAndroid.kt must mark the clip sensitive before setPrimaryClip", emptyList<String>(), problems(AppSourceFiles.mainFile(taken), Shape.VAL))
        assertEquals("app: ActivityHelpers.kt must mark the clip sensitive before setPrimaryClip", emptyList<String>(), problems(AppSourceFiles.mainFile(helpers), Shape.ALSO))
    }
}
