package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The microphone rationale answer of the phone adapter, pinned as text.
 *
 * The answer tells the setup screen whether the system would still show the microphone
 * dialog after an earlier refusal. It is asked only while the microphone is denied, only
 * through the activity found in the context, and never for the notification prompt. No
 * unit test can run the adapter against a real activity, so each rule reads the status
 * function with comments and literal text removed, and each rule is shown to fire on an
 * edited copy of the real file.
 */

private const val PLATFORM = "render/AndroidSetupPlatform.kt"

/** The rationale field as it is written in the status function. */
private const val FIELD_EXPRESSION =
    "micDenied && (activityOf(context)?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ?: false)"

/** The text of the status function between its braces, or null when there is none. */
private fun statusBodyOf(code: String): String? {
    val header = Regex("""override\s+fun\s+status\(\s*\)\s*:\s*PlatformStatus\s*\{""").find(code) ?: return null
    val open = header.range.last
    var depth = 0
    for (index in open until code.length) {
        when (code[index]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return code.substring(open + 1, index)
            }
        }
    }
    return null
}

/** Every rule the rationale answer must keep that [text] breaks, each named by its rule label. */
private fun problemsIn(text: String): List<String> {
    val code = withoutCommentsAndStrings(text)
    val body = statusBodyOf(code) ?: return listOf("status() is not found")
    val problems = mutableListOf<String>()
    val fromActivity = Regex(
        """\bmicrophoneRationale\s*=[^,\n]*activityOf\(\s*context\s*\)\s*\?\.\s*shouldShowRequestPermissionRationale\(\s*Manifest\.permission\.RECORD_AUDIO\s*\)""",
    )
    if (!fromActivity.containsMatchIn(body)) {
        problems.add("rule A: the rationale field is not the activity's answer for the microphone")
    }
    if (!Regex("""\bmicrophoneRationale\s*=[^,\n]*\?:\s*false\b""").containsMatchIn(body)) {
        problems.add("rule B: the rationale is not false when no activity is found")
    }
    val guarded = Regex("""\bmicrophoneRationale\s*=\s*micDenied\s*&&""").containsMatchIn(body)
    val denied = Regex(
        """\bval\s+micDenied\s*=\s*context\s*\.\s*checkSelfPermission\(\s*Manifest\.permission\.RECORD_AUDIO\s*\)\s*==\s*PackageManager\.PERMISSION_DENIED\b""",
    ).containsMatchIn(body)
    if (!guarded || !denied) {
        problems.add("rule C: the rationale is not asked only while the microphone is denied")
    }
    if (Regex("""shouldShowRequestPermissionRationale\(\s*Manifest\.permission\.POST_NOTIFICATIONS""").containsMatchIn(code)) {
        problems.add("rule D: the notification permission goes through the rationale question")
    }
    return problems
}

/** The setup platform's rationale answer, pinned as text. */
class SetupRationaleGateTest {
    private val platform = mainSourceOf(PLATFORM)

    /** [text] with [old] replaced by [new]; fails when [old] is not there, so a sample cannot be a no-op. */
    private fun edit(text: String, old: String, new: String): String {
        assertTrue("the text to change is not in the source: $old", text.contains(old))
        return text.replace(old, new)
    }

    private fun assertFires(what: String, edited: String, rule: String) {
        val problems = problemsIn(edited)
        assertTrue("$what: nothing fired, expected $rule in $problems", problems.any { it.startsWith(rule) })
    }

    @Test
    fun `the real status function passes every rule`() {
        assertEquals("AndroidSetupPlatform rationale: ", emptyList<String>(), problemsIn(platform))
    }

    @Test
    fun `the field is built from the activity's answer for the microphone`() {
        assertFires("the field fixed to false", edit(platform, FIELD_EXPRESSION, "false"), "rule A")
        assertFires("the field fixed to true", edit(platform, FIELD_EXPRESSION, "true"), "rule A")
        assertFires(
            "the answer asked of the context",
            edit(platform, "activityOf(context)?.shouldShowRequestPermissionRationale", "context.shouldShowRequestPermissionRationale"),
            "rule A",
        )
    }

    @Test
    fun `the answer is false when no activity is found`() {
        assertFires("the default true", edit(platform, FIELD_EXPRESSION, FIELD_EXPRESSION.replace("?: false", "?: true")), "rule B")
    }

    @Test
    fun `the answer is asked only while the microphone is denied`() {
        assertFires("the guard dropped", edit(platform, "micDenied && (activityOf", "(activityOf"), "rule C")
        assertFires(
            "the denied value defined as granted",
            edit(platform, "== PackageManager.PERMISSION_DENIED", "== PackageManager.PERMISSION_GRANTED"),
            "rule C",
        )
        assertFires(
            "the denied value defined with a not-equal test",
            edit(platform, "== PackageManager.PERMISSION_DENIED", "!= PackageManager.PERMISSION_DENIED"),
            "rule C",
        )
    }

    @Test
    fun `the notification permission never goes through the rationale question`() {
        assertFires(
            "the call made with the notification permission",
            edit(platform, "shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)", "shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)"),
            "rule D",
        )
    }

    @Test
    fun `a comment that names the question is not a call`() {
        assertEquals(
            "a comment naming the question is not a call",
            emptyList<String>(),
            problemsIn(platform + "\n// shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)\n"),
        )
    }
}
