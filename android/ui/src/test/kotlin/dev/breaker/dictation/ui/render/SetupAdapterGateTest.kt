package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutComments
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The setup adapters are not run by any unit test, so what they must do is pinned as
 * text. Each rule reads code with comments and string literals removed, is run on the
 * real file, and is also shown to fire: the failing sample is the real file with one
 * line changed by edit(), which fails the test if the line it means to change is not
 * there. A rule that nothing can make fail would pass for the wrong reason.
 */

private const val PLATFORM = "render/AndroidSetupPlatform.kt"
private const val HOST = "render/SetupHostView.kt"
private const val ENTRY = "SettingsEntry.kt"
private const val SWITCH = "BreakerSwitch.kt"

/** The system pages and permissions the platform adapter may name, and no others. */
private val PAGES = setOf(
    "ACTION_MANAGE_OVERLAY_PERMISSION", "ACTION_APP_NOTIFICATION_SETTINGS",
    "ACTION_ACCESSIBILITY_SETTINGS", "ACTION_APPLICATION_DETAILS_SETTINGS",
)
private val PERMISSIONS = setOf("RECORD_AUDIO", "POST_NOTIFICATIONS")
private val NEEDED_CATCHES = listOf("ActivityNotFoundException", "SecurityException", "RuntimeException")

private val FIRST_COLUMN = Regex("""^(?:fun|interface|class|object|val|var|sealed|data|enum|typealias)\b""")
private val CALL = Regex("""\.(?:startActivity|requestPermissions)\(""")
private val TRY_OPEN = Regex("""\btry\s*\{""")
private val CATCH_OPEN = Regex("""\s*catch\s*\(\s*\w+\s*:\s*(\w+)\s*\)\s*\{""")

/** What each kind of thing a setup adapter must not do looks like in code. */
private val FORBIDDEN = listOf(
    "a log call" to Regex("""\b(?:Log|Timber)\.\w+\(|\bandroid\.util\.Log\b"""),
    "console output" to Regex("""\bprint(?:ln)?\(|\bSystem\.(?:out|err)\b|\bprintStackTrace\("""),
    "storage" to Regex("""\bFile\(|SharedPreferences|\bjava\.io\.|DataStore"""),
    "a thread, timer or coroutine" to Regex("""\b(?:Thread|Handler|Looper|Timer|postDelayed|runBlocking|launch)\b|Executor"""),
)

/** [text] with [old] replaced by [new]; fails when [old] is not there, so a sample cannot be a no-op. */
private fun edit(text: String, old: String, new: String): String {
    assertTrue("the text to change is not in the source: $old", text.contains(old))
    return text.replace(old, new)
}

/** [text] with the first [old] after [anchor] replaced by [new]; fails when the anchor or the text is not there. */
private fun editAfter(text: String, anchor: String, old: String, new: String): String {
    val at = text.indexOf(anchor)
    assertTrue("the anchor is not in the source: $anchor", at >= 0)
    val from = text.indexOf(old, at)
    assertTrue("the text to change is not after the anchor: $old", from >= 0)
    return text.substring(0, from) + new + text.substring(from + old.length)
}

/** The index just past the brace that closes the one opened at [open]. */
private fun closeOf(code: String, open: Int): Int {
    var depth = 0
    for (index in open until code.length) {
        when (code[index]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return index + 1
            }
        }
    }
    return code.length
}

/** A try block: where its body is, which exceptions it catches and what each handler holds. */
private class Guarded(val body: IntRange, val caught: List<String>, val handlers: List<String>)

private fun triesIn(code: String): List<Guarded> =
    TRY_OPEN.findAll(code).map { start ->
        val open = start.range.last
        val end = closeOf(code, open)
        val caught = mutableListOf<String>()
        val handlers = mutableListOf<String>()
        var at = end
        while (true) {
            val handler = CATCH_OPEN.matchAt(code, at) ?: break
            val handlerEnd = closeOf(code, handler.range.last)
            caught.add(handler.groupValues[1])
            handlers.add(code.substring(handler.range.last, handlerEnd))
            at = handlerEnd
        }
        Guarded(open until end, caught, handlers)
    }.toList()

/** Which settings pages and permissions the adapter names, and whether it spells one as a string. */
private fun namesProblemsIn(text: String): List<String> {
    val code = withoutCommentsAndStrings(text)
    val pages = Regex("""\bSettings\.(ACTION_\w+)""").findAll(code).map { it.groupValues[1] }.toSet()
    val permissions = Regex("""\bManifest\.permission\.(\w+)""").findAll(code).map { it.groupValues[1] }.toSet()
    val problems = mutableListOf<String>()
    if (pages != PAGES) problems.add("settings pages named: $pages")
    if (permissions != PERMISSIONS) problems.add("permissions named: $permissions")
    if (Regex("""["']android\.(?:settings|permission)\.""").containsMatchIn(withoutComments(text))) {
        problems.add("a settings page or permission is spelled as a string")
    }
    for (needed in listOf("Settings.canDrawOverlays(", "Settings.EXTRA_APP_PACKAGE", "Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES")) {
        if (!code.contains(needed)) problems.add("not named: $needed")
    }
    return problems
}

/** The new-task flag may be added once, and only when no activity was found inside the context. */
private fun newTaskProblemsIn(text: String): List<String> {
    val lines = withoutCommentsAndStrings(text).lines()
    val uses = lines.indices.filter { lines[it].contains("FLAG_ACTIVITY_NEW_TASK") }
    if (uses.size != 1) return listOf("the new-task flag is used ${uses.size} times, expected once")
    val guard = Regex("""if\s*\(\s*(\w+)\s*==\s*null\s*\)\s*\w+\.addFlags\(\s*Intent\.FLAG_ACTIVITY_NEW_TASK\s*\)""")
        .find(lines[uses.single()]) ?: return listOf("the flag is not added only when the activity is missing")
    val previous = lines.subList(0, uses.single()).lastOrNull { it.isNotBlank() }.orEmpty().trim()
    val found = Regex("""val\s+""" + guard.groupValues[1] + """\s*=\s*activityOf\(\s*context\s*\)""")
    return if (found.matches(previous)) emptyList() else listOf("${guard.groupValues[1]} is not the activity found in the context")
}

/** Every call that starts a page or a prompt, not inside a try that catches the three failures and answers not possible. */
private fun unguardedCallsIn(text: String): List<String> {
    val code = withoutCommentsAndStrings(text)
    val tries = triesIn(code)
    return CALL.findAll(code).filterNot { call ->
        tries.any { t ->
            call.range.first in t.body && t.caught.containsAll(NEEDED_CATCHES) &&
                t.handlers.all { it.contains("OpenResult.NOT_POSSIBLE") }
        }
    }.map { it.value }.toList()
}

/** The notification prompt is asked only on Android 13 and later. */
private fun versionGuardProblemsIn(text: String): List<String> {
    val lines = withoutCommentsAndStrings(text).lines()
    val uses = lines.indices.filter { lines[it].contains("Manifest.permission.POST_NOTIFICATIONS") }
    if (uses.size != 1) return listOf("the notification prompt is named ${uses.size} times, expected once")
    val near = lines.subList(maxOf(0, uses.single() - 2), uses.single() + 1).joinToString("\n")
    val guard = Regex("""Build\.VERSION\.SDK_INT\s*>=\s*Build\.VERSION_CODES\.TIRAMISU""")
    return if (guard.containsMatchIn(near)) emptyList() else listOf("the notification prompt is not guarded by the Android 13 check")
}

/** The accessibility check reads the secure setting and compares whole entries of it with a String. */
private fun accessibilityProblemsIn(text: String): List<String> {
    val code = withoutCommentsAndStrings(text)
    val problems = mutableListOf<String>()
    val read = Regex("""val\s+(\w+)\s*=\s*Settings\.Secure\.getString\([^)]*Settings\.Secure\.ENABLED_ACCESSIBILITY_SERVICES\s*\)""").find(code)
    val asked = Regex("""AccessibilityList\.contains\(\s*(\w+)\s*,\s*accessibilityServiceComponent\s*\)""").findAll(code).toList()
    if (read == null) problems.add("the enabled list is not read from the secure settings")
    if (asked.size != 1) {
        problems.add("the list is compared with the component ${asked.size} times, expected once")
    } else if (read != null && asked.single().groupValues[1] != read.groupValues[1]) {
        problems.add("the comparison is not given the list that was read")
    }
    if (!Regex("""private val accessibilityServiceComponent: String\b""").containsMatchIn(code)) problems.add("the component is not a String")
    val literals = Regex("""\b(\w+)::class""").findAll(code).map { it.groupValues[1] }.filter { it != "NotificationManager" }.toList()
    if (literals.isNotEmpty()) problems.add("class literals: $literals")
    if (Regex("(?i)commit").containsMatchIn(withoutComments(text))) problems.add("the module that owns the service is named")
    return problems
}

/** The host draws the current screen again when the window gains focus, and only then. */
private fun focusProblemsIn(text: String): List<String> {
    val code = withoutCommentsAndStrings(text)
    val header = Regex("""override\s+fun\s+onWindowFocusChanged\(\s*(\w+)\s*:\s*Boolean\s*\)\s*\{""").find(code)
        ?: return listOf("onWindowFocusChanged is not overridden")
    val body = code.substring(header.range.last, closeOf(code, header.range.last))
    val guard = Regex("""if\s*\(\s*""" + header.groupValues[1] + """\s*\)""").find(body)
        ?: return listOf("the redraw is not guarded by the window gaining focus")
    return if (body.substring(guard.range.last).contains("show(handler.current())")) emptyList() else listOf("gaining focus does not show the current screen")
}

/** What one way of opening must name (its own page or permission) and which helpers it must call. */
private class Wiring(val names: Set<String>, val calls: Set<String>)

private val OPEN_WIRING = mapOf(
    "REQUEST_MICROPHONE" to Wiring(setOf("RECORD_AUDIO"), setOf("request")),
    "REQUEST_NOTIFICATIONS" to Wiring(setOf("POST_NOTIFICATIONS", "ACTION_APP_NOTIFICATION_SETTINGS"), setOf("request", "start")),
    "OVERLAY_PAGE" to Wiring(setOf("ACTION_MANAGE_OVERLAY_PERMISSION"), setOf("start")),
    "NOTIFICATION_PAGE" to Wiring(setOf("ACTION_APP_NOTIFICATION_SETTINGS"), setOf("start")),
    "ACCESSIBILITY_LIST" to Wiring(setOf("ACTION_ACCESSIBILITY_SETTINGS"), setOf("start")),
    "APP_INFO" to Wiring(setOf("ACTION_APPLICATION_DETAILS_SETTINGS"), setOf("start")),
)
private val PAGE_HELPERS = listOf("notificationPage", "appInfoPage")
private val OPEN_HEADER = Regex("""override\s+fun\s+open\(\s*\w+\s*:\s*OpenAction\s*\)\s*:\s*OpenResult\s*=\s*when\s*\(\s*\w+\s*\)\s*\{""")
private val BRANCH_START = Regex("""\bOpenAction\.(\w+)\s*->""")
private val NAME_IN_CODE = Regex("""\bSettings\.(ACTION_\w+)|\bManifest\.permission\.(\w+)""")

/** The settings pages and permissions [code] names directly. */
private fun namesIn(code: String): Set<String> =
    NAME_IN_CODE.findAll(code).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toSet()

/** The text of the function called [name], from its header to the next function; null when there is none. */
private fun funText(code: String, name: String): String? {
    val header = Regex("""\bfun\s+""" + name + """\(""").find(code) ?: return null
    val next = Regex("""\bfun\s""").find(code, header.range.last)
    return code.substring(header.range.first, next?.range?.first ?: code.length)
}

/** Each way of opening names its own page or permission, and starts it through the right helper. */
private fun openWiringProblemsIn(text: String): List<String> {
    val code = withoutCommentsAndStrings(text)
    val header = OPEN_HEADER.find(code) ?: return listOf("open is not a when over the actions")
    val body = code.substring(header.range.last, closeOf(code, header.range.last))
    val starts = BRANCH_START.findAll(body).toList()
    val problems = mutableListOf<String>()
    val seen = starts.map { it.groupValues[1] }
    if (seen.sorted() != OPEN_WIRING.keys.sorted()) problems.add("open has the branches $seen")
    for ((index, start) in starts.withIndex()) {
        val action = start.groupValues[1]
        val wanted = OPEN_WIRING[action] ?: continue
        val branch = body.substring(start.range.last, starts.getOrNull(index + 1)?.range?.first ?: body.length)
        val names = namesIn(branch).toMutableSet()
        for (helper in PAGE_HELPERS) {
            if (Regex("""\b""" + helper + """\(""").containsMatchIn(branch)) names.addAll(namesIn(funText(code, helper).orEmpty()))
        }
        val calls = Regex("""\b(request|start)\(""").findAll(branch).map { it.groupValues[1] }.toSet()
        if (names != wanted.names) problems.add("$action names $names, expected ${wanted.names}")
        if (calls != wanted.calls) problems.add("$action calls $calls, expected ${wanted.calls}")
    }
    return problems
}

/** A prompt needs the activity found in the context, and a page is started whether or not there is one. */
private fun noActivityProblemsIn(text: String): List<String> {
    val code = withoutCommentsAndStrings(text)
    val problems = mutableListOf<String>()
    val request = funText(code, "request").orEmpty()
    val found = Regex("""val\s+(\w+)\s*=\s*activityOf\(\s*context\s*\)\s*\?:\s*return\s+OpenResult\.NOT_POSSIBLE\b""").find(request)
    if (found == null) {
        problems.add("a prompt with no activity does not answer not possible")
    } else if (!Regex("""\b""" + found.groupValues[1] + """\.requestPermissions\(""").containsMatchIn(request)) {
        problems.add("the prompt is not shown by the activity that was found")
    }
    if (found != null && found.range.first > request.indexOf(".requestPermissions(")) problems.add("the prompt is shown before the activity is looked up")
    if (Regex("""\.(?:startActivity|requestPermissions)\([^\n]*\)\s*OpenResult\.OPENED\b""").findAll(code).count() != 2) problems.add("a started page or prompt is not answered as opened")
    val walk = Regex("""fun\s+activityOf\(\s*(\w+)\s*:\s*Context\s*\)\s*:\s*Activity\?\s*\{\s*var\s+(\w+)\s*:\s*Context\?\s*=\s*\1\s*while\s*\(\s*\2\s*!=\s*null\s*\)\s*\{\s*if\s*\(\s*\2\s+is\s+Activity\s*\)\s*return\s+\2\s*\2\s*=\s*if\s*\(\s*\2\s+is\s+ContextWrapper\s*\)\s*\2\.baseContext\s+else\s+null\s*\}\s*return\s+null\b""")
    if (!walk.containsMatchIn(code)) problems.add("the activity is not found by opening wrappers one at a time")
    val start = funText(code, "start").orEmpty()
    if (Regex("""\?:\s*return\b""").containsMatchIn(start)) problems.add("a page is refused when there is no activity")
    val looked = Regex("""val\s+(\w+)\s*=\s*activityOf\(\s*context\s*\)""").find(start)?.groupValues?.get(1)
    val used = Regex("""\(\s*(\w+)\s*\?:\s*context\s*\)\.startActivity\(""").find(start)?.groupValues?.get(1)
    if (looked == null || used != looked) problems.add("a page is not started from the activity or else the context")
    return problems
}

/** The pages that need this app's own address are given it, built from the package name. */
private fun addressProblemsIn(text: String): List<String> {
    val code = withoutCommentsAndStrings(text)
    val problems = mutableListOf<String>()
    val overlay = Regex("""OpenAction\.OVERLAY_PAGE\s*->\s*start\(\s*Intent\(\s*Settings\.ACTION_MANAGE_OVERLAY_PERMISSION\s*,\s*packageUri\(\)\s*\)\s*\)""")
    if (!overlay.containsMatchIn(code)) problems.add("the overlay page is not given the app's address")
    val info = Regex("""Intent\(\s*Settings\.ACTION_APPLICATION_DETAILS_SETTINGS\s*,\s*packageUri\(\)\s*\)""")
    if (!info.containsMatchIn(funText(code, "appInfoPage").orEmpty())) problems.add("the app info page is not given the app's address")
    val extra = Regex("""Intent\(\s*Settings\.ACTION_APP_NOTIFICATION_SETTINGS\s*\)\s*\.putExtra\(\s*Settings\.EXTRA_APP_PACKAGE\s*,\s*context\.packageName\s*\)""")
    if (!extra.containsMatchIn(funText(code, "notificationPage").orEmpty())) problems.add("the notification page is not given the package name")
    val address = Regex("""Uri\.parse\(\s*"package:"\s*\+\s*context\.packageName\s*\)""")
    if (!address.containsMatchIn(funText(withoutComments(text), "packageUri").orEmpty())) problems.add("the address is not package: plus the package name")
    return problems
}

/** What each field of the status is read from: its own question, and the permission test is against GRANTED. */
private fun statusProblemsIn(text: String): List<String> {
    val code = withoutCommentsAndStrings(text)
    val status = funText(code, "status").orEmpty()
    val fields = listOf(
        "overlay" to Regex("""\boverlay\s*=\s*Settings\.canDrawOverlays\(\s*context\s*\)\s*,"""),
        "microphone" to Regex("""\bmicrophone\s*=\s*context\.checkSelfPermission\(\s*Manifest\.permission\.RECORD_AUDIO\s*\)\s*==\s*PackageManager\.PERMISSION_GRANTED\s*,"""),
        "accessibility" to Regex("""\baccessibility\s*=\s*AccessibilityList\.contains\(\s*\w+\s*,\s*accessibilityServiceComponent\s*\)\s*,"""),
        "sdkInt" to Regex("""\bsdkInt\s*=\s*Build\.VERSION\.SDK_INT\s*,"""),
    )
    val problems = fields.filterNot { (_, shape) -> shape.containsMatchIn(status) }.map { "status field ${it.first} is not wired to its own question" }.toMutableList()
    val service = Regex("""val\s+(\w+)\s*=\s*context\.getSystemService\(\s*NotificationManager::class\.java\s*\)""").find(status)
    val wired = service != null &&
        Regex("""\bnotifications\s*=\s*""" + service.groupValues[1] + """\?\.areNotificationsEnabled\(\)\s*\?:\s*false\s*,""").containsMatchIn(status)
    if (!wired) problems.add("status field notifications is not wired to its own question")
    return problems
}

/** The host draws on attachment, shows what the handler answers to a tap, and keeps its place. */
private fun hostProblemsIn(text: String): List<String> {
    val code = withoutCommentsAndStrings(text)
    val problems = mutableListOf<String>()
    val attach = Regex("""override\s+fun\s+onAttachedToWindow\(\s*\)\s*\{""").find(code)
    val attachBody = attach?.let { code.substring(it.range.last, closeOf(code, it.range.last)) }
    if (attachBody == null) {
        problems.add("onAttachedToWindow is not overridden")
    } else {
        if (!attachBody.contains("super.onAttachedToWindow()")) problems.add("attachment does not call super")
        if (!attachBody.contains("show(handler.current())")) problems.add("attachment does not show the current screen")
    }
    val tap = Regex("""fun\s+onIntent\(\s*(\w+)\s*:\s*ScreenIntent\s*\)\s*\{""").find(code)
    val tapBody = tap?.let { code.substring(it.range.last, closeOf(code, it.range.last)) }
    if (tap == null || tapBody == null || !tapBody.contains("show(handler.handle(" + tap.groupValues[1] + "))")) {
        problems.add("the result of handling a tap is not shown")
    }
    val show = Regex("""fun\s+show\(\s*(\w+)\s*:\s*Screen\s*\)\s*\{""").find(code)
    val showBody = show?.let { code.substring(it.range.last, closeOf(code, it.range.last)) }.orEmpty()
    if (!Regex("""\bremoveAllViews\(\)[\s\S]*\baddView\(""").containsMatchIn(showBody)) problems.add("show does not replace the whole child")
    val routed = Regex("""\baddView\(\s*renderer\.render\(\s*""" + show?.groupValues?.get(1) + """\s*,\s*theme\s*,\s*::onIntent\s*\)\s*\)""")
    if (show == null || !routed.containsMatchIn(showBody)) problems.add("show does not route taps to onIntent")
    val notice = """\s*=\s*if\s*\(\s*""" + show?.groupValues?.get(1) + """\.nodes\.any\s*\{\s*it\.id\s*==\s*SETUP_NOTICE_ID\s*\}\s*\)\s*0\s+else\s+scrollY\b"""
    if (!Regex("""val\s+(\w+)""" + notice + """[\s\S]*\baddView\([\s\S]*\bscrollTo\(\s*0\s*,\s*\1\s*\)""").containsMatchIn(showBody)) problems.add("show does not put the scroll position back, or go to the top for the notice")
    if (!Regex("""\binit\s*\{[^}]*\bshow\(handler\.current\(\)\)""").containsMatchIn(code)) problems.add("the first drawing is not made on construction")
    return problems
}

/** What [text] does that no adapter may do: log, print, store, or start work of its own. */
private fun forbiddenIn(text: String): List<String> =
    withoutCommentsAndStrings(text).let { code -> FORBIDDEN.filter { (_, shape) -> shape.containsMatchIn(code) }.map { it.first } }

/** The first-column declarations of the two entry functions and the switch, which must be exactly the three promised. */
private fun surfaceProblemsIn(entry: String, switch: String): List<String> {
    val declared = { text: String ->
        withoutComments(text).lines().filter { FIRST_COLUMN.containsMatchIn(it) }.map { it.substringBefore("(").removeSuffix("{").trim() }
    }
    val problems = mutableListOf<String>()
    if (declared(entry) != listOf("fun createSettingsView", "fun createOnboardingView")) problems.add("entry file declares ${declared(entry)}")
    if (declared(switch) != listOf("interface BreakerSwitch")) problems.add("switch file declares ${declared(switch)}")
    return problems
}

/** The setup adapters and the entry, pinned as text. */
class SetupAdapterGateTest {
    private val platform = mainSourceOf(PLATFORM)
    private val host = mainSourceOf(HOST)
    private val entry = mainSourceOf(ENTRY)
    private val switch = mainSourceOf(SWITCH)

    private fun assertFires(what: String, problems: List<String>, reason: String) {
        assertTrue("$what: nothing fired, expected '$reason' in $problems", problems.any { it.contains(reason) })
    }

    @Test
    fun `the platform adapter names exactly the settings pages and permissions it needs`() {
        assertEquals("AndroidSetupPlatform names: ", emptyList<String>(), namesProblemsIn(platform))
        val other = edit(platform, "Settings.ACTION_ACCESSIBILITY_SETTINGS", "Settings.ACTION_WIFI_SETTINGS")
        assertFires("a page that is not one of the four", namesProblemsIn(other), "settings pages named")
        assertFires("a permission that is not one of the two", namesProblemsIn(edit(platform, "Manifest.permission.RECORD_AUDIO", "Manifest.permission.CAMERA")), "permissions named")
        assertFires("a page spelled as a string", namesProblemsIn(edit(platform, "Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)", "Intent(\"android.settings.WIFI_SETTINGS\")")), "spelled as a string")
        assertFires("the overlay question dropped", namesProblemsIn(edit(platform, "Settings.canDrawOverlays(context)", "true")), "canDrawOverlays")
        assertEquals("names in prose are not names", emptyList<String>(), namesProblemsIn(platform + "\n// Settings.ACTION_WIFI_SETTINGS and Manifest.permission.CAMERA\nval note = \"Settings.ACTION_NFC_SETTINGS\"\n"))
    }

    @Test
    fun `the new-task flag is added only when no activity is found inside the context`() {
        assertEquals("AndroidSetupPlatform new-task flag: ", emptyList<String>(), newTaskProblemsIn(platform))
        val flag = "if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)"
        assertFires("the flag added always", newTaskProblemsIn(edit(platform, flag, "intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)")), "not added only when")
        assertFires("the comparison reversed", newTaskProblemsIn(edit(platform, flag, flag.replace("==", "!="))), "not added only when")
        assertFires("a second use of the flag", newTaskProblemsIn(platform + "\nval again = Intent.FLAG_ACTIVITY_NEW_TASK\n"), "2 times")
        assertFires("the flag with no activity lookup before it", newTaskProblemsIn(edit(platform, "val activity = activityOf(context)\n", "val activity = null\n")), "is not the activity found")
    }

    @Test
    fun `every call that starts a page or a prompt sits in a try that catches the three failures`() {
        assertEquals("AndroidSetupPlatform unguarded calls: ", emptyList<String>(), unguardedCallsIn(platform))
        assertEquals("both the page start and the prompt are expected as calls", 2, CALL.findAll(withoutCommentsAndStrings(platform)).count())
        assertEquals("a missing catch", listOf(".requestPermissions(", ".startActivity("), unguardedCallsIn(edit(platform, "catch (failure: SecurityException)", "catch (failure: IllegalStateException)")))
        assertEquals("handlers that do not answer not possible", 2, unguardedCallsIn(edit(platform, "OpenResult.NOT_POSSIBLE", "OpenResult.OPENED")).size)
        assertEquals("a call outside any try", listOf(".startActivity("), unguardedCallsIn(platform + "\nprivate fun bare(i: Intent) { context.startActivity(i) }\n"))
        assertEquals("a prompt outside any try", listOf(".requestPermissions("), unguardedCallsIn(platform + "\nprivate fun bare(a: Activity) { a.requestPermissions(arrayOf(), 1) }\n"))
    }

    @Test
    fun `the notification prompt is asked only from Android 13`() {
        assertEquals("AndroidSetupPlatform version guard: ", emptyList<String>(), versionGuardProblemsIn(platform))
        val guard = "Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU"
        assertFires("the comparison reversed", versionGuardProblemsIn(edit(platform, guard, "Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU")), "not guarded")
        assertFires("the guard removed", versionGuardProblemsIn(edit(platform, guard, "true")), "not guarded")
    }

    @Test
    fun `the accessibility check compares whole entries of the enabled list with a String component`() {
        assertEquals("AndroidSetupPlatform accessibility check: ", emptyList<String>(), accessibilityProblemsIn(platform))
        val ask = "AccessibilityList.contains(enabled, accessibilityServiceComponent)"
        assertFires("a fixed name instead of the component", accessibilityProblemsIn(edit(platform, ask, "AccessibilityList.contains(enabled, \"a/b\")")), "expected once")
        assertFires("a different setting read", accessibilityProblemsIn(edit(platform, "val enabled = Settings.Secure.getString(", "val enabled = Settings.Global.getString(")), "not read from the secure")
        assertFires("the component no longer a String", accessibilityProblemsIn(edit(platform, "private val accessibilityServiceComponent: String", "private val accessibilityServiceComponent: Any")), "not a String")
        assertFires("a class literal", accessibilityProblemsIn(platform + "\nval k = SomeService::class.java\n"), "class literals")
        assertFires("the owning module named in a string", accessibilityProblemsIn(platform + "\nval n = \"x/dev.commit.Service\"\n"), "is named")
        assertEquals("prose naming it is not a name", emptyList<String>(), accessibilityProblemsIn(platform + "\n// the commit module is not named in code\n"))
    }

    @Test
    fun `the host draws the current screen again when the window gains focus`() {
        assertEquals("SetupHostView focus: ", emptyList<String>(), focusProblemsIn(host))
        assertFires("no override", focusProblemsIn(edit(host, "override fun onWindowFocusChanged", "fun onWindowFocusChangedLater")), "not overridden")
        assertFires("the comparison reversed", focusProblemsIn(edit(host, "if (hasWindowFocus) {", "if (!hasWindowFocus) {")), "not guarded")
        assertFires("nothing drawn", focusProblemsIn(edit(host, "show(handler.current())", "Unit")), "does not show")
    }

    @Test
    fun `every way of opening names its own page or permission`() {
        assertEquals("AndroidSetupPlatform open: ", emptyList<String>(), openWiringProblemsIn(platform))
        val overlay = "Settings.ACTION_MANAGE_OVERLAY_PERMISSION"
        val list = "Settings.ACTION_ACCESSIBILITY_SETTINGS"
        val swappedPages = edit(edit(edit(platform, overlay, "@@"), list, overlay), "@@", list)
        assertFires("the overlay and accessibility pages swapped", openWiringProblemsIn(swappedPages), "OVERLAY_PAGE names")
        val mic = "Manifest.permission.RECORD_AUDIO"
        val notices = "Manifest.permission.POST_NOTIFICATIONS"
        val swappedPermissions = edit(edit(edit(platform, mic, "@@"), notices, mic), "@@", notices)
        assertFires("the two permissions swapped", openWiringProblemsIn(swappedPermissions), "REQUEST_MICROPHONE names")
        val info = "OpenAction.APP_INFO -> start(appInfoPage())"
        assertFires("app info opening the notification page", openWiringProblemsIn(edit(platform, info, "OpenAction.APP_INFO -> start(notificationPage())")), "APP_INFO names")
        val page = "OpenAction.NOTIFICATION_PAGE -> start(notificationPage())"
        assertFires("a page that opens nothing", openWiringProblemsIn(edit(platform, page, "OpenAction.NOTIFICATION_PAGE -> OpenResult.NOT_POSSIBLE")), "NOTIFICATION_PAGE names")
        val micBranch = "OpenAction.REQUEST_MICROPHONE -> request(Manifest.permission.RECORD_AUDIO)"
        assertFires("the microphone sent to a page", openWiringProblemsIn(edit(platform, micBranch, "OpenAction.REQUEST_MICROPHONE -> start(appInfoPage())")), "REQUEST_MICROPHONE calls")
        val list2 = "OpenAction.ACCESSIBILITY_LIST -> start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))"
        assertFires("a branch dropped", openWiringProblemsIn(edit(platform, list2, "")), "has the branches")
    }

    @Test
    fun `a prompt without an activity is not possible while the pages open from any context`() {
        assertEquals("AndroidSetupPlatform no activity: ", emptyList<String>(), noActivityProblemsIn(platform))
        val bail = "activityOf(context) ?: return OpenResult.NOT_POSSIBLE"
        assertFires("the app info page opened instead of a prompt", noActivityProblemsIn(edit(platform, bail, "activityOf(context) ?: return start(appInfoPage())")), "does not answer not possible")
        assertFires("a prompt shown by something else", noActivityProblemsIn(edit(platform, "activity.requestPermissions(", "context.requestPermissions(")), "not shown by the activity")
        val lookup = "val activity = activityOf(context)\n"
        assertFires("a page refused without an activity", noActivityProblemsIn(edit(platform, lookup, "val activity = activityOf(context) ?: return OpenResult.NOT_POSSIBLE\n")), "page is refused")
        assertFires("a page started from the context only", noActivityProblemsIn(edit(platform, "(activity ?: context).startActivity(intent)", "context.startActivity(intent)")), "not started from the activity")
        assertFires("only a context that is an activity is understood", noActivityProblemsIn(edit(platform, "current = if (current is ContextWrapper) current.baseContext else null", "current = null")), "opening wrappers")
        assertFires("no context is ever an activity", noActivityProblemsIn(edit(platform, "if (current is Activity) return current", "if (current is Activity) return null")), "opening wrappers")
        val asked = "val activity = activityOf(context) ?: return OpenResult.NOT_POSSIBLE\n        return try {\n            activity.requestPermissions(arrayOf(permission), PERMISSION_REQUEST)"
        assertFires("the prompt before the lookup", noActivityProblemsIn(edit(platform, asked, asked.lines().drop(1).joinToString("\n") + "\n" + asked.lines().first())), "before the activity is looked up")
        assertFires("a started page answered as not possible", noActivityProblemsIn(edit(platform, "startActivity(intent)\n            OpenResult.OPENED", "startActivity(intent)\n            OpenResult.NOT_POSSIBLE")), "not answered as opened")
    }

    @Test
    fun `the pages that need the app's address are given it`() {
        assertEquals("AndroidSetupPlatform address: ", emptyList<String>(), addressProblemsIn(platform))
        val overlay = "Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri())"
        assertFires("the overlay page without the address", addressProblemsIn(edit(platform, overlay, "Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)")), "overlay page")
        val info = "Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())"
        assertFires("the app info page without the address", addressProblemsIn(edit(platform, info, "Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)")), "app info page")
        val extra = "putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)"
        assertFires("the notification page without the package", addressProblemsIn(edit(platform, extra, "putExtra(Settings.EXTRA_APP_PACKAGE, \"\")")), "notification page")
        assertFires("another address scheme", addressProblemsIn(edit(platform, "Uri.parse(\"package:\" + context.packageName)", "Uri.parse(\"pkg:\" + context.packageName)")), "package: plus")
    }

    @Test
    fun `each field of the status is read from its own question`() {
        assertEquals("AndroidSetupPlatform status: ", emptyList<String>(), statusProblemsIn(platform))
        assertFires("denied read as granted", statusProblemsIn(edit(platform, "PackageManager.PERMISSION_GRANTED", "PackageManager.PERMISSION_DENIED")), "microphone")
        val overlay = "overlay = Settings.canDrawOverlays(context)"
        val microphone = "context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED"
        assertFires("the overlay read from the microphone question", statusProblemsIn(edit(platform, overlay, "overlay = $microphone")), "overlay")
        assertFires("notices assumed on", statusProblemsIn(edit(platform, "?: false", "?: true")), "notifications")
        assertFires("the accessibility field fixed", statusProblemsIn(edit(platform, "accessibility = AccessibilityList.contains(enabled, accessibilityServiceComponent)", "accessibility = true")), "accessibility")
        assertFires("the sdk number fixed", statusProblemsIn(edit(platform, "sdkInt = Build.VERSION.SDK_INT", "sdkInt = 0")), "sdkInt")
    }

    @Test
    fun `the host draws on attachment, shows what a tap leads to and keeps its place`() {
        assertEquals("SetupHostView: ", emptyList<String>(), hostProblemsIn(host))
        val attach = "override fun onAttachedToWindow()"
        assertFires("no redraw on attachment", hostProblemsIn(editAfter(host, attach, "show(handler.current())", "Unit")), "attachment does not show")
        assertFires("no call to super", hostProblemsIn(editAfter(host, attach, "super.onAttachedToWindow()", "Unit")), "does not call super")
        assertFires("the result of a tap dropped", hostProblemsIn(edit(host, "show(handler.handle(intent))", "handler.handle(intent)")), "result of handling a tap")
        assertFires("taps not routed", hostProblemsIn(edit(host, "::onIntent", "{ _ -> }")), "route taps")
        assertFires("the scroll position lost", hostProblemsIn(edit(host, "scrollTo(0, held)", "scrollTo(0, 0)")), "scroll position")
        val held = "val held = if (screen.nodes.any { it.id == SETUP_NOTICE_ID }) 0 else scrollY"
        assertFires("no top for the notice", hostProblemsIn(edit(host, held, "val held = scrollY")), "to the top for the notice")
        assertFires("the top for every other drawing", hostProblemsIn(edit(host, held, held.replace(") 0 else scrollY", ") scrollY else 0"))), "to the top for the notice")
        assertFires("another row sends it to the top", hostProblemsIn(edit(host, held, held.replace("==", "!="))), "to the top for the notice")
        assertFires("no first drawing on construction", hostProblemsIn(editAfter(host, "init {", "show(handler.current())", "Unit")), "on construction")
        assertFires("the old child kept", hostProblemsIn(edit(host, "removeAllViews()", "Unit")), "replace the whole child")
    }

    @Test
    fun `the adapters and the entry log nothing, keep nothing and start no work of their own`() {
        for ((path, text) in listOf(PLATFORM to platform, HOST to host, ENTRY to entry, SWITCH to switch)) {
            assertEquals("$path breaks: ", emptyList<String>(), forbiddenIn(text))
        }
        val samples = mapOf(
            "a log call" to "Log.d(\"tag\", \"text\")", "console output" to "println(text)",
            "storage" to "val f = File(\"x\")", "a thread, timer or coroutine" to "Thread { }.start()",
        )
        for ((shape, sample) in samples) assertEquals("sample for $shape", listOf(shape), forbiddenIn("fun f() { $sample }"))
        assertEquals("prose and strings are not code", emptyList<String>(), forbiddenIn("// Log.d(x) and println(x)\nval note = \"Thread File( launch\"\n"))
    }

    @Test
    fun `the module offers exactly the two entry functions and the switch interface, with the result nested`() {
        assertEquals("surface: ", emptyList<String>(), surfaceProblemsIn(entry, switch))
        assertFires("a renamed setup entry", surfaceProblemsIn(edit(entry, "fun createOnboardingView(", "fun createOnboardingScreen("), switch), "entry file declares")
        assertFires("a third function", surfaceProblemsIn(entry + "\nfun createThird(): Int = 1\n", switch), "entry file declares")
        assertFires("a renamed interface", surfaceProblemsIn(entry, edit(switch, "interface BreakerSwitch", "interface BreakerToggle")), "switch file declares")
        assertFires("the result lifted out of the interface", surfaceProblemsIn(entry, edit(switch, "    sealed class Result {", "sealed class Result {")), "switch file declares")
    }
}
