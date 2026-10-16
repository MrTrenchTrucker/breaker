package dev.breaker.dictation.ui.controls

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutComments
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download bar view, checked on its source text the way ControlsViewGateTest checks the other
 * views: no colour literal, every colour read from the theme, no string of its own, the math it
 * reads, no posting or thread word, and the animator tied to the animation setting and cancelled on
 * detach. Each rule runs first on made-up samples and only then on the real file.
 */
class DownloadBarViewGateTest {

    private val view = "render/controls/DownloadBarView.kt"

    private val hexString = Regex("""(?i)["']#(?:[0-9a-f]{8}|[0-9a-f]{6})["']""")
    private val hexNumber = Regex("""\b0[xX](?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6})\b""")
    private val largeDecimal = Regex("""(?<![\w.$])-?[1-9][0-9]{6,}(?![\w.$])""")
    private val frameworkColour = Regex("""\bandroid\.graphics\.(?:Color|color)\b""")
    private val colourAssignment = Regex("""\.color\s*=""")
    private val scheduling = Regex("""\b(?:postDelayed|removeCallbacks|Handler|Thread)\b""")

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("download bar view: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("download bar view: $what must not be reported", emptyList<String>(), problems)

    private fun realView(): String = mainSourceOf(view)

    private fun colourProblems(text: String): List<String> {
        val code = withoutComments(text)
        return listOfNotNull(
            "the framework colour type".takeIf { frameworkColour.containsMatchIn(code) },
            "a hex colour string".takeIf { hexString.containsMatchIn(code) },
            "a hex colour number".takeIf { hexNumber.containsMatchIn(code) },
            "a packed colour as a decimal number".takeIf { largeDecimal.containsMatchIn(code) },
        )
    }

    private fun paintProblems(text: String): List<String> =
        withoutCommentsAndStrings(text).lines().mapIndexedNotNull { number, line ->
            val match = colourAssignment.find(line) ?: return@mapIndexedNotNull null
            val value = line.substring(match.range.last + 1).trim()
            if (value.startsWith("theme.color(PaletteSlot.")) null
            else "${number + 1}: the colour is set from '$value'"
        }

    private fun slotProblems(text: String): List<String> =
        listOf("SURFACE", "TEXT_MUTED", "PRIMARY", "PRIMARY_HOVER").mapNotNull { slot ->
            "the view never reads PaletteSlot.$slot".takeUnless { text.contains("theme.color(PaletteSlot.$slot)") }
        }

    private fun stringProblems(text: String): List<String> =
        withoutComments(text).lines().mapIndexedNotNull { number, line ->
            if (line.contains('"')) "${number + 1}: the view holds a string of its own" else null
        }

    private fun mathProblems(text: String): List<String> =
        listOf("fillFraction", "stripeOffsetDp", "stripeStarts").mapNotNull { name ->
            "the view never reads DownloadBarMath.$name".takeUnless { text.contains("DownloadBarMath.$name") }
        }

    private fun schedulingProblems(text: String): List<String> =
        withoutCommentsAndStrings(text).lines().mapIndexedNotNull { number, line ->
            scheduling.find(line)?.let { "${number + 1}: the view names ${it.value}" }
        }

    private fun animatorProblems(text: String): List<String> =
        if (withoutCommentsAndStrings(text).contains("ValueAnimator.areAnimatorsEnabled()")) emptyList()
        else listOf("the view never asks whether animators are enabled")

    private fun cancelProblems(text: String): List<String> {
        val code = withoutCommentsAndStrings(text)
        val start = code.indexOf("override fun onDetachedFromWindow()")
        if (start < 0) return listOf("the view never overrides onDetachedFromWindow")
        val end = code.indexOf("\n    }", start)
        val body = code.substring(start, if (end < 0) code.length else end)
        return if (body.contains("cancel()")) emptyList()
        else listOf("onDetachedFromWindow does not cancel the animator")
    }

    @Test
    fun `no colour literal of any of the four shapes is written in the view`() {
        assertFires("a hex colour string", colourProblems("val c = \"#1E7A46\"\n"), "a hex colour string")
        assertFires("a hex colour number", colourProblems("val c = 0xFF1E7A46.toInt()\n"), "a hex colour number")
        assertFires("a packed colour as a decimal", colourProblems("val c = -14451130\n"), "a packed colour as a decimal number")
        assertFires("the framework colour type", colourProblems("val c = android.graphics.Color.RED\n"), "the framework colour type")
        assertQuiet("a palette read", colourProblems("paint.color = theme.color(PaletteSlot.PRIMARY).argb\n"))
        assertEquals("colour literals in the view", emptyList<String>(), colourProblems(realView()))
    }

    @Test
    fun `every colour the view paints is read from the theme`() {
        assertFires("a colour constant", paintProblems("paint.color = Color.RED\n"), "the colour is set from 'Color.RED'")
        assertFires("a colour variable", paintProblems("paint.color = tint\n"), "the colour is set from 'tint'")
        assertQuiet("a theme read", paintProblems("paint.color = theme.color(PaletteSlot.SURFACE).argb\n"))
        assertEquals("colours set outside the theme", emptyList<String>(), paintProblems(realView()))
    }

    @Test
    fun `the view reads each of the four colour slots it needs`() {
        assertFires("a view without the hover slot", slotProblems("theme.color(PaletteSlot.PRIMARY).argb"), "PRIMARY_HOVER")
        val all = listOf("SURFACE", "TEXT_MUTED", "PRIMARY", "PRIMARY_HOVER").joinToString("\n") { "theme.color(PaletteSlot.$it)" }
        assertQuiet("a view with all four slots", slotProblems(all))
        assertEquals("slots missing from the view", emptyList<String>(), slotProblems(realView()))
    }

    @Test
    fun `the view holds no string literal of its own`() {
        assertFires("a string of its own", stringProblems("val own = \"READY\"\n"), "holds a string")
        assertQuiet("a view without a string", stringProblems("val count = 2\n"))
        assertEquals("strings held by the view", emptyList<String>(), stringProblems(realView()))
    }

    @Test
    fun `the view reads the three functions of the download bar math`() {
        assertFires("no stripe starts", mathProblems("DownloadBarMath.fillFraction DownloadBarMath.stripeOffsetDp"), "stripeStarts")
        assertQuiet(
            "all three reads",
            mathProblems("DownloadBarMath.fillFraction(a) DownloadBarMath.stripeOffsetDp(b) DownloadBarMath.stripeStarts(c)"),
        )
        assertEquals("math reads missing from the view", emptyList<String>(), mathProblems(realView()))
    }

    @Test
    fun `the view names no posting, handler or thread word`() {
        assertFires("a postDelayed", schedulingProblems("view.postDelayed(task, 16)\n"), "postDelayed")
        assertFires("a Handler", schedulingProblems("val h = Handler(looper)\n"), "Handler")
        assertFires("a Thread", schedulingProblems("val t = Thread { }\n"), "Thread")
        assertQuiet("an animator", schedulingProblems("ValueAnimator.ofFloat(0f, 1f)\n"))
        assertEquals("scheduling words in the view", emptyList<String>(), schedulingProblems(realView()))
    }

    @Test
    fun `the view runs its animator only where the phone allows animations`() {
        assertFires("no check of the setting", animatorProblems("val animate = true\n"), "never asks")
        assertQuiet("a check of the setting", animatorProblems("val animate = ValueAnimator.areAnimatorsEnabled()\n"))
        assertEquals("the view does not check the setting", emptyList<String>(), animatorProblems(realView()))
    }

    @Test
    fun `the view cancels its animator when it detaches`() {
        assertFires(
            "a detach that never cancels",
            cancelProblems("override fun onDetachedFromWindow() {\n        super.onDetachedFromWindow()\n    }\n"),
            "does not cancel",
        )
        assertQuiet(
            "a detach that cancels",
            cancelProblems("override fun onDetachedFromWindow() {\n        animator?.cancel()\n        super.onDetachedFromWindow()\n    }\n"),
        )
        assertEquals("the view's detach does not cancel", emptyList<String>(), cancelProblems(realView()))
    }
}
