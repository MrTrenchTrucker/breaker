package dev.breaker.dictation.gates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source pins for the key asset read in the app's updater and for the hand-over of the key list it
 * parses. The read must fall back to the empty text when the asset is missing or cannot be read, and the
 * build function must pass the parsed list to the updater wiring. The read is checked on the raw text, so
 * the empty text literal is seen; the hand-over is checked on the code text, so a comment that names the
 * call does not count. Each pin has planted samples that must fire and harmless samples that must stay
 * quiet, and every sample is made by an edit that is first checked to land in the real text.
 */
internal class AppUpdaterKeyGateTest {

    private val appUpdater: String = "kotlin/dev/breaker/dictation/wiring/AppUpdater.kt"

    private val catchOfKeyRead: String = "catch (e: Exception) {\n            \"\"\n"

    /** The text of keyListText, from its first line to the closing brace of the object. */
    private fun keyListBody(text: String): String {
        val start: Int = text.indexOf("private fun keyListText(app: BreakerApp): String =")
        if (start < 0) return ""
        val close: Int = text.indexOf("\n}", start)
        return if (close < 0) text.substring(start) else text.substring(start, close)
    }

    /** True when keyListText reads the key asset inside a try and gives the empty text in its catch. */
    private fun emptyTextOnFailure(text: String): Boolean {
        val body: String = keyListBody(text)
        val emptyCatch = Regex(
            """catch \(\w+: Exception\) \{(?:\s|//[^\n]*)*""(?:\s|//[^\n]*)*\}""",
        )
        return body.contains("try {") && body.contains("assets.open(KEY_ASSET)") && emptyCatch.containsMatchIn(body)
    }

    /** True when the code of the build function hands the parsed key list to the updater wiring. */
    private fun parsedListHandedOver(text: String): Boolean {
        val code: String = AppSourceFiles.strip(text).code
        val handOver = Regex("""pinnedKeys\s*=\s*UpdateKeyList\.parse\(\s*keyListText\(\s*app\s*\)\s*\)""")
        return handOver.containsMatchIn(code)
    }

    /** The text with one planted edit; the old text must be in the real text first, so a sample always changes it. */
    private fun edited(text: String, old: String, new: String): String {
        require(text.contains(old)) { "app: the planted sample does not match the real file text" }
        return text.replace(old, new)
    }

    @Test
    fun `a missing key asset gives the empty text through the catch of the read`() {
        val real: String = AppSourceFiles.mainFile(appUpdater)
        assertTrue("app: the key asset read falls back to the empty text", emptyTextOnFailure(real))
        val fired = listOf(
            edited(real, catchOfKeyRead, "catch (e: Exception) {\n            \"stale\"\n"),
            edited(real, catchOfKeyRead, "catch (e: Exception) {\n            throw e\n"),
            edited(real, "} catch (e: Exception) {\n            \"\"\n        }", "}"),
        )
        for (sample in fired) {
            assertFalse("app: a planted read that does not give the empty text must fire", emptyTextOnFailure(sample))
        }
        val quiet = listOf(
            edited(real, catchOfKeyRead, "catch (ignored: Exception) {\n            \"\"\n"),
            edited(real, "catch (e: Exception) {\n            \"\"\n        }", "catch (e: Exception) {   \"\"   }"),
            edited(real, catchOfKeyRead, "catch (e: Exception) {\n            // the asset may be missing\n            \"\"\n"),
        )
        for (sample in quiet) {
            assertTrue("app: a harmless edit of the read must stay quiet", emptyTextOnFailure(sample))
        }
    }

    @Test
    fun `the parsed key list is the one handed to the updater wiring in the build function`() {
        val real: String = AppSourceFiles.mainFile(appUpdater)
        val handOver = "pinnedKeys = UpdateKeyList.parse(keyListText(app)),"
        assertTrue("app: the build function hands the parsed list over", parsedListHandedOver(real))
        val fired = listOf(
            edited(real, handOver, "pinnedKeys = emptyList(),"),
            edited(real, handOver, "// pinnedKeys = UpdateKeyList.parse(keyListText(app)),\n            pinnedKeys = emptyList(),"),
            edited(real, "UpdateKeyList.parse(keyListText(app)),", "UpdateKeyList.parse(keyListText(app).trim()),"),
        )
        for (sample in fired) {
            assertFalse("app: a planted hand-over that is not the parsed list must fire", parsedListHandedOver(sample))
        }
        val quiet = listOf(
            edited(real, handOver, "pinnedKeys =   UpdateKeyList.parse( keyListText( app ) ),"),
            edited(real, handOver, "pinnedKeys = UpdateKeyList.parse(keyListText(app)), // the key list"),
        )
        for (sample in quiet) {
            assertTrue("app: a harmless edit of the hand-over must stay quiet", parsedListHandedOver(sample))
        }
    }
}
