package dev.breaker.dictation.commit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only the adapter folder may name the Android framework, and that folder holds
 * exactly the files that are known to be device code.
 *
 * Adapter files are the part of the module no JVM test runs, so every new one has
 * to be argued for: the set is written out here, and adding a file means changing
 * this list on purpose. Test sources run on a plain JVM, so they may not name the
 * framework either.
 */
internal class AndroidConfinementTest {

    private val adapterFiles: Set<String> = setOf(
        "adapter/BreakerInputMethodService.kt",
        "adapter/ImeFocusedField.kt",
        "adapter/AndroidClipboardWriter.kt",
        "adapter/ToastNotice.kt",
        "adapter/HandlerMainThread.kt",
        "adapter/ImeHolder.kt",
        "adapter/CommitServices.kt",
    )

    private val rules: Map<String, Regex> = mapOf(
        "framework package" to Regex("""(?<![\p{L}\p{N}_.])androidx?\s*\."""),
    )

    @Test
    fun `no main file outside the adapter folder names the framework`() {
        val outside: Map<String, String> = SourceFiles.mainSources().filterKeys { !it.startsWith("adapter/") }
        assertTrue("commit: found no main file outside the adapter folder to scan", outside.isNotEmpty())
        assertEquals(
            "commit: a file outside the adapter folder names android or androidx",
            emptyList<String>(),
            SourceFiles.offences(outside, rules),
        )
    }

    @Test
    fun `the adapter folder holds exactly the seven known files`() {
        assertEquals(
            "commit: the adapter folder changed; a new device file has to be argued for and listed here",
            adapterFiles,
            adapterNames(SourceFiles.mainSources()),
        )
    }

    @Test
    fun `the scan sees real framework use in the adapter files that talk to the device`() {
        val adapter: Map<String, String> = SourceFiles.mainSources().filterKeys { it.startsWith("adapter/") }
        val naming: Set<String> = SourceFiles.filesMatching(adapter, rules.getValue("framework package")).toSet()
        val talkToDevice: Set<String> = adapterFiles - "adapter/ImeHolder.kt"
        assertTrue(
            "commit: the scan no longer finds the framework in ${talkToDevice - naming}",
            naming.containsAll(talkToDevice),
        )
    }

    @Test
    fun `no test source names the framework`() {
        val tests: Map<String, String> = SourceFiles.testSources()
        assertTrue("commit: found no test source to scan", tests.isNotEmpty())
        assertEquals(
            "commit: a test source names android or androidx; tests run on a plain JVM",
            emptyList<String>(),
            SourceFiles.offences(tests, rules),
        )
    }

    @Test
    fun `samples that name the framework outside the adapter folder are reported`() {
        val samples: Map<String, String> = mapOf(
            "an import" to "import android.os.Bundle\nobject A",
            "an androidx import" to "import androidx.annotation.Foo\nobject A",
            "a qualified use" to "object A { val b = android.os.Build.VERSION.SDK_INT }",
            "a name inside a template hole" to "object A { val s = \"v \${android.os.Build.ID}\" }",
        )
        for ((label, source) in samples) {
            val found: List<String> = SourceFiles.offences(mapOf("Outside.kt" to source), rules)
            assertEquals("commit: the confinement scan missed $label", 1, found.size)
        }
    }

    @Test
    fun `samples with the framework only in prose, strings or longer words are not reported`() {
        val samples: Map<String, String> = mapOf(
            "a comment" to "// import android.os.Bundle\nobject A",
            "a KDoc" to "/**\n * See android.os.Build.\n */\nobject A",
            "a string" to "object A { const val S = \"android.os.Build\" }",
            "a raw string" to "object A { const val S = \"\"\"androidx.core\"\"\" }",
            "a longer word" to "object A { val androids = 1; val subandroid = 2; val androidx = 3 }",
        )
        for ((label, source) in samples) {
            assertEquals(
                "commit: the confinement scan flagged $label",
                emptyList<String>(),
                SourceFiles.offences(mapOf("Outside.kt" to source), rules),
            )
        }
    }

    @Test
    fun `the adapter file set check sees an added file, a nested file and a look-alike folder`() {
        val sample: Map<String, String> = mapOf(
            "Top.kt" to "",
            "adapter/One.kt" to "",
            "adapter/deep/Two.kt" to "",
            "adapterOther/Three.kt" to "",
        )
        assertEquals(
            "commit: the adapter set check misread the folder layout",
            setOf("adapter/One.kt", "adapter/deep/Two.kt"),
            adapterNames(sample),
        )
        val withExtra: Map<String, String> = adapterFiles.associateWith { "" } + ("adapter/Extra.kt" to "")
        assertNotEquals(
            "commit: the adapter set check did not notice an added file",
            adapterFiles,
            adapterNames(withExtra),
        )
        val missingOne: Map<String, String> = (adapterFiles - "adapter/ToastNotice.kt").associateWith { "" }
        assertNotEquals(
            "commit: the adapter set check did not notice a missing file",
            adapterFiles,
            adapterNames(missingOne),
        )
    }

    private fun adapterNames(sources: Map<String, String>): Set<String> =
        sources.keys.filter { it.startsWith("adapter/") }.toSet()
}
