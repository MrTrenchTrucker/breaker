package dev.breaker.dictation.service

import dev.breaker.dictation.service.ServiceGateSource.bodyOf
import dev.breaker.dictation.service.ServiceGateSource.edit
import dev.breaker.dictation.service.ServiceGateSource.read
import dev.breaker.dictation.service.ServiceGateSource.squash
import dev.breaker.dictation.service.ServiceGateSource.strip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds four small facts of the Android service files that no JVM test can run: the service is not a
 * bound service, its destroy step reports the end before the platform's own destroy step, the
 * notification id is a positive number, and the launcher addresses the microphone service and no other
 * component. The gate reads code only (comments and string text are removed first). Each rule has
 * firing samples and quiet samples, and the checks on the real files fail by name when a file is missing.
 */
internal class ServiceFilesGateTest {

    private val serviceFile = ServiceGateSource.SERVICE_DIR + "DictationForegroundService.kt"
    private val notificationFile = ServiceGateSource.SERVICE_DIR + "DictationNotification.kt"
    private val launcherFile = ServiceGateSource.SERVICE_DIR + "AndroidServiceLauncher.kt"

    private val bindHead = Regex("""\boverride\s+fun\s+onBind\s*\(""")
    private val nullAnswer = Regex("""^\s*(?::\s*[\w.]+\??\s*)?=\s*null\s*(?:\n|$)""")
    private val returnsNull = Regex("""^\s*return\s+null\s*$""")
    private val endedCall = Regex("""\bserviceEnded\s*\(\s*\)""")
    private val superDestroy = Regex("""\bsuper\s*\.\s*onDestroy\s*\(\s*\)""")
    private val idDeclaration = Regex("""\bconst\s+val\s+NOTIFICATION_ID\s*(?::\s*Int\s*)?=\s*([1-9][0-9]{0,8})\s*(?:\n|$)""")
    private val intentHelper = Regex(
        """\bfun\s+serviceIntent\s*\(\s*\)[^={]*(?:=|\{\s*return)\s*Intent\s*\(\s*context\s*,\s*([A-Za-z_][\w.]*)\s*::\s*class\s*\.\s*java\s*\)""",
    )
    private val rightClass = setOf("DictationForegroundService", "dev.breaker.dictation.service.DictationForegroundService")

    private val rules = setOf("ON_BIND_NULL", "DESTROY_ORDER", "ID_POSITIVE", "INTENT_TARGET")

    private fun bindProblems(source: String): List<String> {
        val code = strip(source)
        val head = bindHead.find(code) ?: return listOf("ON_BIND_NULL")
        var index = head.range.last + 1
        var depth = 1
        while (index < code.length && depth > 0) {
            if (code[index] == '(') depth++
            if (code[index] == ')') depth--
            index++
        }
        val rest = code.substring(index)
        if (nullAnswer.containsMatchIn(rest)) return emptyList()
        val body = bodyOf(code, "onBind")
        return if (body != null && returnsNull.matches(squash(body))) emptyList() else listOf("ON_BIND_NULL")
    }

    private fun destroyProblems(source: String): List<String> {
        val body = bodyOf(strip(source), "onDestroy") ?: return listOf("DESTROY_ORDER")
        val ended = endedCall.find(body)
        val platform = superDestroy.find(body)
        val right = ended != null && platform != null && ended.range.first < platform.range.first
        return if (right) emptyList() else listOf("DESTROY_ORDER")
    }

    private fun idProblems(source: String): List<String> =
        if (idDeclaration.containsMatchIn(strip(source))) emptyList() else listOf("ID_POSITIVE")

    private fun intentProblems(source: String): List<String> {
        val target = intentHelper.find(strip(source))?.groupValues?.get(1)
        return if (target != null && target in rightClass) emptyList() else listOf("INTENT_TARGET")
    }

    private val sampleService = """
        package x
        class DictationForegroundService : Service() {
            override fun onBind(intent: Intent?): IBinder? = null
            override fun onDestroy() {
                controller.serviceEnded()
                super.onDestroy()
            }
        }
    """.trimIndent()

    private val sampleNotification = """
        package x
        internal object DictationNotification {
            const val NOTIFICATION_ID: Int = 1
        }
    """.trimIndent()

    private val sampleLauncher = """
        package x
        class AndroidServiceLauncher(private val context: Context) : ServiceLauncher {
            private fun serviceIntent(): Intent = Intent(context, DictationForegroundService::class.java)
        }
    """.trimIndent()

    @Test
    fun `the service is not a bound service`() {
        assertEquals("app: DictationForegroundService.onBind must return null", emptyList<String>(), bindProblems(read(serviceFile)))
    }

    @Test
    fun `the service reports its end before the platform destroy step and still calls it`() {
        assertEquals("app: DictationForegroundService.onDestroy breaks its order", emptyList<String>(), destroyProblems(read(serviceFile)))
    }

    @Test
    fun `the notification id is a const equal to a positive number`() {
        assertEquals("app: DictationNotification.NOTIFICATION_ID must be a positive const", emptyList<String>(), idProblems(read(notificationFile)))
    }

    @Test
    fun `the launcher addresses the microphone service and no other component`() {
        assertEquals("app: AndroidServiceLauncher.serviceIntent must name the service", emptyList<String>(), intentProblems(read(launcherFile)))
    }

    @Test
    fun `the unedited and the quiet samples break no rule`() {
        assertEquals("app: the service sample was reported", emptyList<String>(), bindProblems(sampleService) + destroyProblems(sampleService))
        assertEquals("app: the notification sample was reported", emptyList<String>(), idProblems(sampleNotification))
        assertEquals("app: the launcher sample was reported", emptyList<String>(), intentProblems(sampleLauncher))
        val block = edit(sampleService, "IBinder? = null", "IBinder? {\n        return null\n    }")
        assertEquals("app: a block body that returns null was reported", emptyList<String>(), bindProblems(block))
        val big = edit(sampleNotification, "Int = 1", "Int = 42")
        assertEquals("app: a larger positive id was reported", emptyList<String>(), idProblems(big))
        val untyped = edit(sampleNotification, "NOTIFICATION_ID: Int = 1", "NOTIFICATION_ID = 7")
        assertEquals("app: an id with no declared type was reported", emptyList<String>(), idProblems(untyped))
        val qualified = edit(sampleLauncher, "DictationForegroundService::class", "dev.breaker.dictation.service.DictationForegroundService::class")
        assertEquals("app: the qualified service name was reported", emptyList<String>(), intentProblems(qualified))
        val blockHelper = edit(edit(sampleLauncher, "Intent = Intent(", "Intent { return Intent("), "::class.java)", "::class.java) }")
        assertEquals("app: the helper with a block body was reported", emptyList<String>(), intentProblems(blockHelper))
    }

    @Test
    fun `an edited copy that breaks one thing is reported under the rule that guards it`() {
        val found: Map<String, List<String>> = mapOf(
            "ON_BIND_NULL" to bindProblems(edit(sampleService, "IBinder? = null", "IBinder? = android.os.Binder()")),
            "ON_BIND_NULL (block)" to bindProblems(edit(sampleService, "IBinder? = null", "IBinder? {\n        return binder\n    }")),
            "ON_BIND_NULL (no member)" to bindProblems(edit(sampleService, "override fun onBind", "override fun onRebind")),
            "ON_BIND_NULL (null in a comment)" to bindProblems(edit(sampleService, "IBinder? = null", "IBinder? = binder // null")),
            "DESTROY_ORDER (no platform call)" to destroyProblems(edit(sampleService, "super.onDestroy()", "")),
            "DESTROY_ORDER (swapped)" to destroyProblems(edit(sampleService, "controller.serviceEnded()\n        super.onDestroy()", "super.onDestroy()\n        controller.serviceEnded()")),
            "DESTROY_ORDER (no report)" to destroyProblems(edit(sampleService, "controller.serviceEnded()", "controller.hold()")),
            "DESTROY_ORDER (platform call in a comment)" to destroyProblems(edit(sampleService, "super.onDestroy()", "// super.onDestroy()")),
            "DESTROY_ORDER (no member)" to destroyProblems(edit(sampleService, "override fun onDestroy()", "override fun onStop()")),
            "ID_POSITIVE" to idProblems(edit(sampleNotification, "Int = 1", "Int = 0")),
            "ID_POSITIVE (negative)" to idProblems(edit(sampleNotification, "Int = 1", "Int = -1")),
            "ID_POSITIVE (not const)" to idProblems(edit(sampleNotification, "const val", "val")),
            "ID_POSITIVE (expression)" to idProblems(edit(sampleNotification, "Int = 1", "Int = 1 + ZERO")),
            "ID_POSITIVE (computed)" to idProblems(edit(sampleNotification, "Int = 1", "Int = ZERO + 1")),
            "INTENT_TARGET" to intentProblems(edit(sampleLauncher, "DictationForegroundService::class", "dev.breaker.dictation.SettingsLauncherActivity::class")),
            "INTENT_TARGET (short name)" to intentProblems(edit(sampleLauncher, "DictationForegroundService::class", "SettingsLauncherActivity::class")),
            "INTENT_TARGET (no helper)" to intentProblems(edit(sampleLauncher, "fun serviceIntent()", "fun otherIntent()")),
        )
        for ((label, keys) in found) {
            assertEquals("app: the gate missed the firing sample '$label'", listOf(label.substringBefore(" (")), keys)
        }
        assertEquals("app: a rule has no firing sample", rules, found.keys.map { it.substringBefore(" (") }.toSet())
    }

    @Test
    fun `a sample whose target text is missing is refused by name`() {
        val thrown = assertThrows("app: a sample with a lost target must fail", IllegalStateException::class.java) {
            edit(sampleService, "super.onGone()", "")
        }
        assertTrue("app: the failure must name the lost text: ${thrown.message}", thrown.message.orEmpty().contains("super.onGone()"))
        val missing = assertThrows("app: a missing file must fail", IllegalStateException::class.java) { read(ServiceGateSource.SERVICE_DIR + "Nope.kt") }
        assertTrue("app: the missing-file failure must name the file: ${missing.message}", missing.message.orEmpty().contains("Nope.kt"))
    }
}
