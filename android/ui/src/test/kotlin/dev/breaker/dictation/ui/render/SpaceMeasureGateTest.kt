package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Gate for the empty-view measure. A plain View answers "at most N" with N, so in a scroll view
 * whose window it fills (fillViewport's fill pass) an empty view that ends up with a WRAP or MATCH
 * height grows to the whole offered height: RELAY measured the first history card stretched to 2544
 * px, its two spread-row spacers pinned to 2124 px, and cards 2-3 squashed to 0 px on the Android 16
 * emulator. Both empty views are exactly that: the spread-row separator (separatorOf, its own params
 * are (0, WRAP_CONTENT, 1f)) and the horizontal Spacer (spacerOf, which placedChild re-assigns to
 * (size px, WRAP_CONTENT) in a horizontal box). The fix builds both from the platform
 * android.widget.Space, written FULLY QUALIFIED at both sites (no import: the bare name Space in this
 * file is the ui.screen.Space enum, imported on line 29 and used by pixelsOf), keeping the weight-1
 * (0, WRAP_CONTENT, 1f) separator params and the (size px, WRAP_CONTENT) horizontal-Spacer params
 * exactly as they were. So a plain View in either path body is the regression, and re-importing
 * android.widget.Space is a compile breaker. This gate reads the real ScreenRenderer.kt, comments and
 * strings blanked, and is shown to fail on the broken forms.
 */
internal class SpaceMeasureGateTest {

    private val source: String = mainSourceOf("render/ScreenRenderer.kt")

    /** The raw text of the named function: its signature through the brace that closes its own block. */
    private fun rawBody(source: String, name: String): String {
        val start = source.indexOf("private fun $name")
        assertTrue("ui: $name is not in ScreenRenderer.kt", start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("ui: the open brace of $name was not found", open > start)
        var depth = 0
        var end = open
        while (end < source.length) {
            when (source[end]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            end++
        }
        assertTrue("ui: the closing brace of $name was not found", end > open)
        return source.substring(start, end + 1)
    }

    /** The body of the named function, comments and strings blanked and whitespace folded away. */
    private fun pathBody(source: String, name: String): String = flat(rawBody(source, name))

    /** [text] with comments and string literals removed and whitespace folded away. */
    private fun flat(text: String): String = withoutCommentsAndStrings(text).replace(Regex("""\s+"""), "")

    /**
     * A plain View construction: the whole word View followed by an open paren, so Space(,
     * android.widget.Space(, TextView(, LinearLayout( and the View.IMPORTANT... constants never match.
     * Any here in an empty-view path means a plain View is back and will take AT_MOST under the fill
     * pass.
     */
    private val plainView = Regex("""(?<![A-Za-z0-9_])View\(""")

    @Test fun `the spread path has no plain view and exactly one space`() {
        val sep = pathBody(source, "separatorOf")
        assertFalse("ui: separatorOf must hold no plain View (it is the spread-row separator)", plainView.find(sep) != null)
        assertEquals("ui: separatorOf must carry exactly one Space(context)", 1, countOccurrences(sep, "Space(context)"))
    }

    @Test fun `the spacer path has no plain view and exactly one space`() {
        val spa = pathBody(source, "spacerOf")
        assertFalse("ui: spacerOf must hold no plain View (it becomes the horizontal Spacer)", plainView.find(spa) != null)
        assertEquals("ui: spacerOf must carry exactly one Space(context)", 1, countOccurrences(spa, "Space(context)"))
    }

    /**
     * The realistic regression for [name]'s body: the fully-qualified android.widget.Space(context) is
     * replaced by the bare plain View(context) the pre-fix code had; assert the construction is unique
     * in the body, so the result reads as a plain View, not a mangled qualified name.
     */
    private fun mutateIn(source: String, name: String): String {
        val body = rawBody(source, name)
        assertTrue("ui: $name must contain exactly one android.widget.Space(context)", countOccurrences(body, "android.widget.Space(context)") == 1)
        return source.replace(body, body.replace("android.widget.Space(context)", "View(context)"))
    }

    @Test fun `a plain view in the spread path is reported`() {
        val mutant = mutateIn(source, "separatorOf") // the spread path back to the pre-fix plain View
        assertTrue("ui: a plain View in the spread (separatorOf) path must be detected", plainView.find(pathBody(mutant, "separatorOf")) != null)
    }

    @Test fun `a plain view in the spacer path is reported`() {
        val mutant = mutateIn(source, "spacerOf") // the spacer path back to the pre-fix plain View
        assertTrue("ui: a plain View in the spacer (spacerOf) path must be detected", plainView.find(pathBody(mutant, "spacerOf")) != null)
    }

    @Test fun `a stray plain view with a wrap height in the spread path is reported`() {
        // A second, plain View with a WRAP-height LayoutParams declared inside the separatorOf body:
        // compiles (a local declaration in the apply block) and adds a plain View to the spread path.
        val stray = "val strayView = View(context).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }"
        val sepBody = rawBody(source, "separatorOf")
        val marker = "importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO"
        assertTrue("ui: the marker line must sit in the separatorOf body exactly once", countOccurrences(sepBody, marker) == 1)
        val mutant = source.replace(sepBody, sepBody.replace(marker, marker + "; " + stray))
        assertTrue("ui: a stray plain View in the spread path must be detected", plainView.find(pathBody(mutant, "separatorOf")) != null)
        assertFalse("ui: the stray View must not be seen in the other path", plainView.find(pathBody(mutant, "spacerOf")) != null)
    }

    @Test fun `both paths back to plain views are reported`() {
        // The pre-fix state: both fully-qualified constructions are the bare plain View again.
        val both = source.replace("android.widget.Space(context)", "View(context)")
        assertTrue("ui: the spread path must be detected as a plain View", plainView.find(pathBody(both, "separatorOf")) != null)
        assertTrue("ui: the spacer path must be detected as a plain View", plainView.find(pathBody(both, "spacerOf")) != null)
    }

    @Test fun `the fixed render must not re-add the widget-Space import and a re-added one is detected`() {
        // 7a GREEN on the real (fixed) source: the forbidden widget import is absent. The bare name
        // Space in this file is the ui.screen.Space enum (imported line 29, used by pixelsOf); a
        // widget import would make it ambiguous and the compile red.
        assertFalse("ui: ScreenRenderer.kt must not import android.widget.Space (that name is the ui.screen.Space enum)", hasWidgetImport(source))

        // 7b RED-by-mutant: insert the forbidden import after the (unique) LinearLayout import; the
        // predicate flips false -> true.
        val anchor = "import android.widget.LinearLayout"
        assertEquals("ui: the anchor import must occur exactly once for the mutant transform", source.lines().count { it == anchor }, 1)
        val mutant = source.replace(anchor, anchor + "\nimport android.widget.Space")
        assertTrue("ui: a re-added android.widget.Space import must be detected", hasWidgetImport(mutant))
    }

    /** True if [text] carries the forbidden widget-Space import line. */
    private fun hasWidgetImport(text: String): Boolean = text.contains("import android.widget.Space")

    /** The number of non-overlapping `target` occurrences in [text]. */
    private fun countOccurrences(text: String, target: String): Int =
        Regex(Regex.escape(target)).findAll(text).count()
}
