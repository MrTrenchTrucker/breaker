package dev.breaker.dictation.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate over the shapes of the service and its start handler. The service is the one place no JVM test runs, so
 * it is held to a shape in which nothing can hide: the start member only passes the action to the handler, each
 * host member is one platform call, and the handler (plain, no platform type) enters the foreground first inside
 * a guard that ends the service quietly. Each rule has firing samples made by an edit and a quiet sample.
 */
internal class ServiceShapeGateTest {

    private val serviceFile = ServiceGateSource.SERVICE_DIR + "DictationForegroundService.kt"
    private val handlerFile = ServiceGateSource.SERVICE_DIR + "ServiceStartHandler.kt"

    private val enterCall = Regex(
        """^\s*startForeground\s*\(\s*DictationNotification\s*\.\s*NOTIFICATION_ID\s*,\s*DictationNotification\s*\.\s*build\s*\(\s*this(?:@DictationForegroundService)?\s*\)\s*,\s*ServiceInfo\s*\.\s*FOREGROUND_SERVICE_TYPE_MICROPHONE\s*,?\s*\)\s*$""",
    )
    private val leaveCalls = Regex("""^\s*stopForeground\s*\(\s*STOP_FOREGROUND_REMOVE\s*\)\s*;?\s*stopSelf\s*\(\s*\)\s*;?\s*$""")
    private val guardHead = Regex("""^\s*try\s*\{\s*host\s*\.\s*enterForeground\s*\(\s*\)\s*\}\s*catch\s*\(\s*\w+\s*:\s*RuntimeException\s*\)\s*\{""")
    private val platformName = Regex("""\bandroid\s*\.""")

    private val rules = setOf("START_BODY", "HOST_ENTER", "HOST_LEAVE", "HOST_STOP", "HANDLER_PLAIN", "HANDLER_FIRST")

    private fun startProblems(source: String): List<String> {
        val body = ServiceGateSource.bodyOf(ServiceGateSource.strip(source), "onStartCommand")
        val right = body != null && ServiceGateSource.squash(body) == "handler.onStart(intent?.action) return START_NOT_STICKY"
        return if (right) emptyList() else listOf("START_BODY")
    }

    private fun hostProblems(source: String): List<String> {
        val code = ServiceGateSource.strip(source)
        val bad = ArrayList<String>()
        val enter = ServiceGateSource.bodyOf(code, "enterForeground")
        if (enter == null || !enterCall.matches(enter)) bad.add("HOST_ENTER")
        val leave = ServiceGateSource.bodyOf(code, "leaveForegroundAndStop")
        if (leave == null || !leaveCalls.matches(leave)) bad.add("HOST_LEAVE")
        val stop = ServiceGateSource.bodyOf(code, "stopWithoutForeground")
        if (stop == null || !Regex("""\bstopSelf\s*\(""").containsMatchIn(stop) || stop.contains("stopForeground")) bad.add("HOST_STOP")
        return bad
    }

    private fun handlerProblems(source: String): List<String> {
        val code = ServiceGateSource.strip(source)
        val bad = ArrayList<String>()
        if (platformName.containsMatchIn(code)) bad.add("HANDLER_PLAIN")
        val body = ServiceGateSource.bodyOf(code, "onStart")
        val head = body?.let { guardHead.find(it) }
        val caught = if (body != null && head != null) ServiceGateSource.blockAfter(body, head.range.last) else null
        val right = caught != null &&
            ServiceGateSource.squash(caught) == "controller.serviceEnded() host.stopWithoutForeground() return"
        if (!right) bad.add("HANDLER_FIRST")
        return bad
    }

    private fun lines(vararg text: String): String = text.joinToString("\n")

    private val sampleService = lines(
        "package x",
        "import android.app.Service",
        "class DictationForegroundService : Service() {",
        "private val host: ServiceHost = object : ServiceHost {",
        "override fun enterForeground() {",
        "startForeground(",
        "DictationNotification.NOTIFICATION_ID,",
        "DictationNotification.build(this@DictationForegroundService),",
        "ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,",
        ")",
        "}",
        "override fun leaveForegroundAndStop() {",
        "stopForeground(STOP_FOREGROUND_REMOVE)",
        "stopSelf()",
        "}",
        "override fun stopWithoutForeground() {",
        "stopSelf()",
        "}",
        "}",
        "override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {",
        "handler.onStart(intent?.action)",
        "return START_NOT_STICKY",
        "}",
        "}",
    )

    private val sampleHandler = lines(
        "package x",
        "import dev.breaker.dictation.audio.Other",
        "class ServiceStartHandler(private val controller: DictationServiceController, private val host: ServiceHost) {",
        "fun onStart(actionText: String?) {",
        "try {",
        "host.enterForeground()",
        "} catch (e: RuntimeException) {",
        "controller.serviceEnded()",
        "host.stopWithoutForeground()",
        "return",
        "}",
        "val action = serviceActionOf(actionText)",
        "when (ServiceStartDecisions.decide(action, controller.isArmed)) {",
        "StartDecision.KEEP -> Unit",
        "StartDecision.STOP_AT_ONCE -> host.leaveForegroundAndStop()",
        "else -> Unit",
        "}",
        "}",
        "}",
    )

    private val leavePair = "stopForeground(STOP_FOREGROUND_REMOVE)\nstopSelf()"
    private val stopMember = "override fun stopWithoutForeground() {\nstopSelf()\n}"
    private val guardText = "try {\nhost.enterForeground()\n} catch (e: RuntimeException) {\ncontroller.serviceEnded()\nhost.stopWithoutForeground()\nreturn\n}"

    @Test
    fun `the service start member only passes the action to the handler and returns START_NOT_STICKY`() {
        assertEquals("app: the service start member breaks its shape", emptyList<String>(), startProblems(ServiceGateSource.read(serviceFile)))
    }

    @Test
    fun `the foreground member of the host is one startForeground call with the id, the notification and the microphone type`() {
        assertTrue("app: the host enterForeground breaks its shape", "HOST_ENTER" !in hostProblems(ServiceGateSource.read(serviceFile)))
    }

    @Test
    fun `leaving the foreground removes the notification before the service stops`() {
        assertTrue("app: the host leaveForegroundAndStop breaks its shape", "HOST_LEAVE" !in hostProblems(ServiceGateSource.read(serviceFile)))
    }

    @Test
    fun `stopping without the foreground only stops the service`() {
        assertTrue("app: the host stopWithoutForeground breaks its shape", "HOST_STOP" !in hostProblems(ServiceGateSource.read(serviceFile)))
    }

    @Test
    fun `the start handler names no platform type`() {
        assertEquals("app: the handler names a platform type", emptyList<String>(), handlerProblems(ServiceGateSource.read(handlerFile)) - "HANDLER_FIRST")
    }

    @Test
    fun `the start handler enters the foreground first inside a guard that switches off and stops quietly`() {
        assertEquals("app: the handler start breaks its shape", emptyList<String>(), handlerProblems(ServiceGateSource.read(handlerFile)) - "HANDLER_PLAIN")
    }

    @Test
    fun `the unedited samples break no rule`() {
        assertEquals("app: the start sample was reported", emptyList<String>(), startProblems(sampleService))
        assertEquals("app: the host sample was reported", emptyList<String>(), hostProblems(sampleService))
        assertEquals("app: the handler sample was reported", emptyList<String>(), handlerProblems(sampleHandler))
    }

    @Test
    fun `an edited copy that breaks one thing is reported under the rule that guards it`() {
        val start = "handler.onStart(intent?.action)"
        val ret = "return START_NOT_STICKY"
        val type = "ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE"
        val found: Map<String, List<String>> = mapOf(
            "START_BODY (early return)" to startProblems(edit(sampleService, start, "if (intent == null) return START_NOT_STICKY\n$start")),
            "START_BODY (stop before)" to startProblems(edit(sampleService, start, "stopSelf()\n$start")),
            "START_BODY (stop between)" to startProblems(edit(sampleService, ret, "stopSelf()\n$ret")),
            "START_BODY (stop after)" to startProblems(edit(sampleService, ret, "$ret\nstopSelf()")),
            "START_BODY (second call)" to startProblems(edit(sampleService, start, "$start\nhandler.onStart(null)")),
            "START_BODY (other argument)" to startProblems(edit(sampleService, start, "handler.onStart(null)")),
            "START_BODY (no call)" to startProblems(edit(sampleService, start, "")),
            "START_BODY (sticky)" to startProblems(edit(sampleService, ret, "return START_STICKY")),
            "HOST_ENTER (no type)" to hostProblems(edit(sampleService, "$type,", "")),
            "HOST_ENTER (other type)" to hostProblems(edit(sampleService, type, "ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION")),
            "HOST_ENTER (literal id)" to hostProblems(edit(sampleService, "DictationNotification.NOTIFICATION_ID", "1")),
            "HOST_ENTER (extra call)" to hostProblems(edit(sampleService, "startForeground(", "stopSelf()\nstartForeground(")),
            "HOST_ENTER (other call)" to hostProblems(edit(sampleService, "startForeground(", "notify(")),
            "HOST_LEAVE (swapped)" to hostProblems(edit(sampleService, leavePair, "stopSelf()\nstopForeground(STOP_FOREGROUND_REMOVE)")),
            "HOST_LEAVE (no remove)" to hostProblems(edit(sampleService, leavePair, "stopSelf()")),
            "HOST_LEAVE (no stop)" to hostProblems(edit(sampleService, leavePair, "stopForeground(STOP_FOREGROUND_REMOVE)")),
            "HOST_LEAVE (detach)" to hostProblems(edit(sampleService, "STOP_FOREGROUND_REMOVE", "STOP_FOREGROUND_DETACH")),
            "HOST_STOP (with remove)" to hostProblems(edit(sampleService, stopMember, "override fun stopWithoutForeground() {\nstopForeground(STOP_FOREGROUND_REMOVE)\nstopSelf()\n}")),
            "HOST_STOP (no stop)" to hostProblems(edit(sampleService, stopMember, "override fun stopWithoutForeground() {\n}")),
            "HANDLER_PLAIN (import)" to handlerProblems(edit(sampleHandler, "import dev.breaker.dictation.audio.Other", "import android.content.Context")),
            "HANDLER_PLAIN (qualified)" to handlerProblems(edit(sampleHandler, "val action =", "val log = android.util.Log.d()\nval action =")),
            "HANDLER_FIRST (not first)" to handlerProblems(edit(sampleHandler, "try {", "if (controller.isArmed) return\ntry {")),
            "HANDLER_FIRST (no guard)" to handlerProblems(edit(sampleHandler, guardText, "host.enterForeground()")),
            "HANDLER_FIRST (narrow catch)" to handlerProblems(edit(sampleHandler, "RuntimeException", "IllegalStateException")),
            "HANDLER_FIRST (no switch off)" to handlerProblems(edit(sampleHandler, "controller.serviceEnded()\n", "")),
            "HANDLER_FIRST (wrong stop)" to handlerProblems(edit(sampleHandler, "host.stopWithoutForeground()", "host.leaveForegroundAndStop()")),
            "HANDLER_FIRST (no return)" to handlerProblems(edit(sampleHandler, "host.stopWithoutForeground()\nreturn", "host.stopWithoutForeground()")),
            "HANDLER_FIRST (enter after)" to handlerProblems(edit(edit(sampleHandler, guardText, ""), "else -> Unit", "else -> Unit\n$guardText")),
        )
        for ((label, keys) in found) {
            assertEquals("app: the gate missed the firing sample '$label'", listOf(label.substringBefore(" (")), keys)
        }
        assertEquals("app: a rule has no firing sample", rules, found.keys.map { it.substringBefore(" (") }.toSet())
    }

    @Test
    fun `names in comments and strings are not reported`() {
        val prose = "// android.app.Service and startForeground( are not used here\n" + sampleHandler + "\nval s = \"android.util.Log\"\n/* android. */\n"
        assertEquals("app: prose was reported as a platform type", emptyList<String>(), handlerProblems(prose))
    }

    @Test
    fun `a sample edit whose target is missing and a file that cannot be read fail by name`() {
        val lost = assertThrows("app: an edit of text that is not there must fail", IllegalStateException::class.java) {
            edit(sampleHandler, "no such text", "x")
        }
        assertTrue("app: the edit failure must name the missing text: ${lost.message}", lost.message.orEmpty().contains("no such text"))
        val missing = assertThrows("app: a missing file must fail", IllegalStateException::class.java) {
            ServiceGateSource.read(ServiceGateSource.SERVICE_DIR + "Nope.kt")
        }
        assertTrue("app: the missing-file failure must name the file: ${missing.message}", missing.message.orEmpty().contains("Nope.kt"))
    }

    private fun edit(text: String, old: String, new: String): String = ServiceGateSource.edit(text, old, new)
}
