package dev.breaker.dictation.wiring

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate test for [applyInsets] and its helper [applyPadding] in AndroidInsets.kt.
 *
 * The bar and cutout insets must be applied as margins, through
 * MarginLayoutParams, so the system bars and the cutout stay clear of the host's
 * own background. The gutter must be applied as padding so the content sits
 * inside the margin edge. A view that cannot carry margins takes the whole
 * amount as padding instead, so it is still clear of the bars.
 *
 * This is a source-code gate test because applyInsets operates on
 * android.view.View, which cannot be instantiated in a JVM unit test.
 */
internal class ApplyInsetsTest {

    private val source: String by lazy {
        val file = java.io.File("src/main/kotlin/dev/breaker/dictation/wiring/AndroidInsets.kt")
        assertTrue("AndroidInsets.kt not found at ${file.absolutePath}", file.isFile)
        file.readText()
    }

    private val code: String by lazy { stripCommentsAndStrings(source) }

    private fun flat(): String = code.replace(Regex("""\s+"""), "")

    @Test
    fun `applyPadding uses layoutFor to split margins and padding`() {
        assertTrue(
            "applyPadding must call layoutFor to split clear and gutter into margins and padding",
            flat().contains("layoutFor("),
        )
    }

    @Test
    fun `applyPadding casts to MarginLayoutParams`() {
        assertTrue(
            "applyPadding must cast layoutParams to MarginLayoutParams",
            flat().contains("as?ViewGroup.MarginLayoutParams"),
        )
    }

    @Test
    fun `applyPadding sets margins from the clear insets`() {
        assertTrue(
            "applyPadding must put the clear insets on setMargins",
            flat().contains("setMargins(clear.left,clear.top,clear.right,clear.bottom)"),
        )
    }

    @Test
    fun `applyPadding sets padding from the gutter`() {
        assertTrue(
            "applyPadding must put the gutter on setPadding",
            flat().contains("setPadding(inner.left,inner.top,inner.right,inner.bottom)"),
        )
    }

    @Test
    fun `applyPadding falls back to combined padding when MarginLayoutParams is not available`() {
        val flat: String = flat()
        assertTrue(
            "applyPadding must have an else branch for views that cannot carry margins",
            flat.contains("else"),
        )
        assertTrue("the else branch must add clear.left + inner.left", flat.contains("clear.left+inner.left"))
        assertTrue("the else branch must add clear.top + inner.top", flat.contains("clear.top+inner.top"))
        assertTrue("the else branch must add clear.right + inner.right", flat.contains("clear.right+inner.right"))
        assertTrue("the else branch must add clear.bottom + inner.bottom", flat.contains("clear.bottom+inner.bottom"))
    }

    @Test
    fun `applyInsets sets an OnApplyWindowInsetsListener`() {
        assertTrue(
            "applyInsets must set an OnApplyWindowInsetsListener for future insets changes",
            flat().contains("setOnApplyWindowInsetsListener"),
        )
    }

    @Test
    fun `applyInsets reads rootWindowInsets for the initial padding`() {
        assertTrue(
            "applyInsets must read rootWindowInsets for the initial padding",
            flat().contains("rootWindowInsets"),
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private fun stripCommentsAndStrings(text: String): String {
        val kept = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            index = when {
                text.startsWith("//", index) -> endOfLine(text, index)
                text.startsWith("/*", index) -> endOf(text, index + 2, "*/")
                text[index] == '"' -> endOfQuoted(text, index)
                else -> {
                    kept.append(text[index])
                    index + 1
                }
            }
        }
        return kept.toString()
    }

    private fun endOfLine(text: String, from: Int): Int =
        text.indexOf('\n', from).let { if (it < 0) text.length else it }

    private fun endOf(text: String, from: Int, close: String): Int {
        val found = text.indexOf(close, from)
        return if (found < 0) text.length else found + close.length
    }

    private fun endOfQuoted(text: String, from: Int): Int {
        var index = from + 1
        while (index < text.length) {
            when (text[index]) {
                '\\' -> index += 2
                '"' -> return index + 1
                '\n' -> return index
                else -> index++
            }
        }
        return text.length
    }
}
