package dev.breaker.dictation.commit.accessibility

import org.junit.Assert.assertTrue

/**
 * Shared fixtures for the probe gate tests: the two service sources they read, and the helpers that
 * read, strip and edit their text. Each probe test class extends this class, so every helper exists once.
 */
internal abstract class ProbeGateTestBase {

    protected val nodeFile: String = "adapter/AndroidFieldNode.kt"
    protected val serviceFile: String = "adapter/BreakerAccessibilityService.kt"

    protected fun real(path: String): String {
        val text: String = SourceFiles.mainSources()[path] ?: ""
        assertTrue("commit/accessibility: the probe check did not find " + path, text.isNotBlank())
        return text
    }

    protected fun code(source: String): String = SourceFiles.strip(source).code

    protected fun count(rule: Regex, code: String): Int = rule.findAll(code).count()

    protected fun edit(source: String, from: String, to: String): String {
        assertTrue("commit/accessibility: the probe check sample has no text " + from, source.contains(from))
        return source.replaceFirst(from, to)
    }
}
