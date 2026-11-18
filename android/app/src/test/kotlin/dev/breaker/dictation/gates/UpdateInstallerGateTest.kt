package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source pins for the install adapter in wiring/UpdateInstallerAndroid.kt. The adapter names Android
 * types, so no JVM test can run it. Each pin reads the real file and counts one safety point in its code,
 * with comments and string text blanked; only the pin on the status action name reads the text as written.
 * Every pin is broken by at least two edited samples and must stay quiet on harmless edits: a comment,
 * renamed locals and blank lines.
 */
internal class UpdateInstallerGateTest {

    private val file: String = "kotlin/dev/breaker/dictation/wiring/UpdateInstallerAndroid.kt"
    private val raw: String = AppSourceFiles.mainFile(file)

    /** The file text with each pair applied in order; a pair whose old text is missing fails by name. */
    private fun applied(edits: List<Pair<String, String>>): String {
        var text: String = raw
        for ((old, new) in edits) {
            check(text.contains(old)) { "app: a sample lost its text \"$old\"" }
            text = text.replace(old, new)
        }
        return text
    }

    /** Counts the matches of [pattern] in the edited file, in its code (or its raw text when [literal]), and checks the pin. */
    private fun pin(label: String, pattern: String, times: Int, firing: List<List<Pair<String, String>>>, literal: Boolean = false) {
        val regex = Regex(pattern)
        fun hits(edits: List<Pair<String, String>>): Int {
            val text: String = applied(edits)
            return regex.findAll(if (literal) text else AppSourceFiles.strip(text).code).count()
        }
        assertEquals("app: $label does not hold on the real file", times, hits(emptyList()))
        assertTrue("app: $label has fewer than two firing samples", firing.size >= 2)
        for (edits in firing) {
            assertTrue("app: $label still holds after an edit that breaks it", hits(edits) != times)
        }
        for (edits in quiet) {
            assertEquals("app: $label fires on a harmless edit", times, hits(edits))
        }
    }

    private val quiet: List<List<Pair<String, String>>> = listOf(
        listOf(
            "val ours: Boolean" to "val mine: Boolean",
            "confirm != null, ours)" to "confirm != null, mine)",
            "val filter = IntentFilter(statusAction)" to "val receiverFilter = IntentFilter(statusAction)",
            "registerReceiver(statusReceiver, filter, Context" to "registerReceiver(statusReceiver, receiverFilter, Context",
            "registerReceiver(statusReceiver, filter)" to "registerReceiver(statusReceiver, receiverFilter)",
            "val intent = Intent(statusAction)" to "val message = Intent(statusAction)",
            "sessionId, intent, pendingFlags()" to "sessionId, message, pendingFlags()",
            "val params = PackageInstaller.SessionParams" to "val sessionParams = PackageInstaller.SessionParams",
            "params.setAppPackageName(context.packageName)" to "sessionParams.setAppPackageName(context.packageName)",
            "installer.createSession(params)" to "installer.createSession(sessionParams)",
            "override fun launch(apk: File)" to "override fun launch(file: File)",
            "PackageInstallRun(opener).run(apk)" to "PackageInstallRun(opener).run(file)",
            "val component: ActivityInfo" to "val info: ActivityInfo",
            "if (component.packageName == context.packageName)" to "if (info.packageName == context.packageName)",
            "val owner: ApplicationInfo = component.applicationInfo" to "val app: ApplicationInfo = info.applicationInfo",
            "return (owner.flags and" to "return (app.flags and",
            "private fun isSystemTarget(confirm: Intent)" to "private fun isSystemTarget(intent: Intent)",
            "resolveActivity(confirm, 0)" to "resolveActivity(intent, 0)",
            "private companion object {" to "// a note that names Log and Thread\n    private companion object {",
        ),
        listOf(
            "private companion object {" to "/** Constants. Nothing here is a Log, a Thread or a Handler. */\n    private companion object {",
            "if (!context.packageManager.canRequestPackageInstalls()) {" to "\n        if (!context.packageManager.canRequestPackageInstalls()) {",
        ),
    )

    @Test
    fun `the status receiver is registered at run time with the not exported flag on API 33 and later`() {
        pin(
            "the receiver registration",
            """if\s*\(\s*Build\s*\.\s*VERSION\s*\.\s*SDK_INT\s*>=\s*33\s*\)\s*\{\s*context\s*\.\s*registerReceiver\s*\(\s*statusReceiver\s*,\s*\w+\s*,\s*Context\s*\.\s*RECEIVER_NOT_EXPORTED\s*\)""",
            1,
            listOf(
                listOf("context.registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)" to "context.registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED)"),
                listOf("SDK_INT >= 33) {" to "SDK_INT >= 31) {"),
            ),
        )
    }

    @Test
    fun `the status receiver is registered the plain way only in the branch below API 33`() {
        pin(
            "the plain receiver registration",
            """\}\s*else\s*\{\s*context\s*\.\s*registerReceiver\s*\(\s*statusReceiver\s*,\s*\w+\s*\)\s*\}""",
            1,
            listOf(
                listOf("context.registerReceiver(statusReceiver, filter)" to "context.registerReceiver(statusReceiver, filter, 0)"),
                listOf("            } else {" to "            }\n            if (false) {"),
            ),
        )
    }

    @Test
    fun `the status intent is addressed to this package with setPackage`() {
        pin(
            "the status intent package",
            """Intent\s*\(\s*statusAction\s*\)\s*\.\s*setPackage\s*\(\s*context\s*\.\s*packageName\s*\)""",
            1,
            listOf(
                listOf(".setPackage(context.packageName)" to ".setPackage(statusAction)"),
                listOf("val intent = Intent(statusAction)" to "val intent = Intent(\"x\")"),
            ),
        )
    }

    @Test
    fun `the session id is checked against the own sessions and that answer is the decision input`() {
        pin(
            "the own session check",
            """val\s+(\w+)\s*:\s*Boolean\s*=\s*ownSessions\s*\.\s*contains\s*\(\s*sessionId\s*\)[\s\S]*?InstallStatusRule\s*\.\s*decide\s*\(\s*status\s*,\s*confirm\s*!=\s*null\s*,\s*\1\s*\)""",
            1,
            listOf(
                listOf("ownSessions.contains(sessionId)" to "ownSessions.contains(NO_SESSION_ID)"),
                listOf("confirm != null, ours)" to "confirm != null, !ours)"),
            ),
        )
    }

    @Test
    fun `the install permission check runs before a session is opened`() {
        pin(
            "the install permission check",
            """if\s*\(\s*!\s*context\s*\.\s*packageManager\s*\.\s*canRequestPackageInstalls\s*\(\s*\)\s*\)[\s\S]*?return\s+PackageInstallRun\s*\(\s*opener\s*\)\s*\.\s*run\s*\(\s*\w+\s*\)""",
            1,
            listOf(
                listOf("if (!context.packageManager.canRequestPackageInstalls()) {" to "if (!context.packageManager.canRequestPackageInstalls() && false) {"),
                listOf(
                    "return PackageInstallRun(opener).run(apk)" to "return early",
                    "if (!context.packageManager.canRequestPackageInstalls()) {" to "val early = PackageInstallRun(opener).run(apk)\n        if (!context.packageManager.canRequestPackageInstalls()) {",
                ),
            ),
        )
    }

    @Test
    fun `the session params name this app package`() {
        pin(
            "the session package",
            """val\s+(\w+)\s*=\s*PackageInstaller\s*\.\s*SessionParams\s*\([^)]*\)[\s\S]*?\b\1\s*\.\s*setAppPackageName\s*\(\s*context\s*\.\s*packageName\s*\)""",
            1,
            listOf(
                listOf("params.setAppPackageName(context.packageName)" to "params.setAppPackageName(statusAction)"),
                listOf("val params = PackageInstaller.SessionParams(" to "val params = listOf("),
            ),
        )
    }

    @Test
    fun `the pending intent is mutable on API 31 and later`() {
        pin(
            "the mutable flag guard",
            """if\s*\(\s*Build\s*\.\s*VERSION\s*\.\s*SDK_INT\s*>=\s*31\s*\)\s*\{\s*PendingIntent\s*\.\s*FLAG_UPDATE_CURRENT\s+or\s+PendingIntent\s*\.\s*FLAG_MUTABLE\s*\}""",
            1,
            listOf(
                listOf("PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE" to "PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE"),
                listOf("SDK_INT >= 31) {" to "SDK_INT >= 33) {"),
            ),
        )
    }

    @Test
    fun `the mutable flag appears in one place only`() {
        pin(
            "the mutable flag count",
            """\bFLAG_MUTABLE\b""",
            1,
            listOf(
                listOf("private val receiverOn: AtomicBoolean = AtomicBoolean(false)" to "private val receiverOn: AtomicBoolean = AtomicBoolean(false)\n    private val mutableFlag: Int = PendingIntent.FLAG_MUTABLE"),
                listOf("PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE" to "PendingIntent.FLAG_UPDATE_CURRENT"),
            ),
        )
    }

    @Test
    fun `the status pending intent carries the package and the mutable flags`() {
        pin(
            "the status pending intent",
            """setPackage\s*\(\s*context\s*\.\s*packageName\s*\)[\s\S]*?getBroadcast\s*\(\s*context\s*,\s*sessionId\s*,\s*\w+\s*,\s*pendingFlags\s*\(\s*\)\s*\)""",
            1,
            listOf(
                listOf("pendingFlags())" to "0)"),
                listOf(".setPackage(context.packageName)" to ""),
            ),
        )
    }

    @Test
    fun `the adapter uses no reflection`() {
        pin(
            "the reflection ban",
            """\bgetDeclared\w*|\bforName\s*\(|\.getMethod\s*\(|\.getField\s*\(|\bjava\s*\.\s*lang\s*\.\s*reflect\b|\bsetAccessible\s*\(|\.invoke\s*\(""",
            0,
            listOf(
                listOf("private companion object {" to "private fun tricks() = Any().javaClass.getDeclaredMethod(\"x\")\n\n    private companion object {"),
                listOf("private companion object {" to "private fun probe() = Class.forName(\"x\")\n\n    private companion object {"),
            ),
        )
    }

    @Test
    fun `the confirm intent is resolved with no flags before it is started`() {
        pin(
            "the resolve call",
            """resolveActivity\s*\(\s*\w+\s*,\s*0\s*\)""",
            1,
            listOf(
                listOf("resolveActivity(confirm, 0)" to "resolveActivity(confirm, 65536)"),
                listOf("context.packageManager.resolveActivity(confirm, 0) ?: return false" to "null ?: return false"),
            ),
        )
    }

    @Test
    fun `the system flag is read from the resolved target and must be set`() {
        pin(
            "the system flag test",
            """\.flags\s+and\s+ApplicationInfo\s*\.\s*FLAG_SYSTEM\s*\)\s*!=\s*0""",
            1,
            listOf(
                listOf("ApplicationInfo.FLAG_SYSTEM) != 0" to "ApplicationInfo.FLAG_SYSTEM) == 0"),
                listOf("(owner.flags and ApplicationInfo.FLAG_SYSTEM)" to "(owner.flags and ApplicationInfo.FLAG_DEBUGGABLE)"),
            ),
        )
    }

    @Test
    fun `a target in this app is refused`() {
        pin(
            "the own package refusal",
            """\bpackageName\s*==\s*context\s*\.\s*packageName\s*\)\s*return\s+false""",
            1,
            listOf(
                listOf("if (component.packageName == context.packageName) return false" to "if (component.packageName == context.packageName) return true"),
                listOf("if (component.packageName == context.packageName) return false" to "if (component.packageName == \"\") return false"),
            ),
        )
    }

    @Test
    fun `the confirm intent is started only after the system check and a refusal abandons the session`() {
        pin(
            "the start after the system check",
            """if\s*\(\s*!isSystemTarget\s*\(\s*confirm\s*\)\s*\)\s*\{\s*abandonById\s*\(\s*sessionId\s*\)\s*return\s*\}[\s\S]*?context\s*\.\s*startActivity\s*\(\s*confirm\s*\)""",
            1,
            listOf(
                listOf("if (!isSystemTarget(confirm)) {" to "if (!isSystemTarget(confirm) && false) {"),
                listOf("if (!isSystemTarget(confirm)) {" to "if (!isSystemTarget(confirm)) {\n                return"),
            ),
        )
    }

    @Test
    fun `the adapter never writes the confirm intent to a log`() {
        pin(
            "the log and print ban",
            """\bLog\s*\.|\bprintln\s*\(|\bprint\s*\(|\bLogger\b|\bTimber\b|\bSystem\s*\.\s*(out|err)\b|\bToast\b""",
            0,
            listOf(
                listOf("context.startActivity(confirm)" to "android.util.Log.d(\"u\", \"x\")\n            context.startActivity(confirm)"),
                listOf("context.startActivity(confirm)" to "println(confirm)\n            context.startActivity(confirm)"),
            ),
        )
    }

    @Test
    fun `the adapter names no handler looper executor thread or lock`() {
        pin(
            "the thread and lock ban",
            """\b(Handler|HandlerThread|Looper|Executors?|ExecutorService|Thread|runOnUiThread|synchronized|Synchronized|Volatile|Timer|ReentrantLock|CountDownLatch|Semaphore)\b""",
            0,
            listOf(
                listOf("private companion object {" to "private val pool = Executors.newSingleThreadExecutor()\n\n    private companion object {"),
                listOf("context.startActivity(confirm)" to "synchronized(this) { }\n            context.startActivity(confirm)"),
            ),
        )
    }

    @Test
    fun `the status action name is this package and a private suffix as written`() {
        pin(
            "the status action name",
            """context\s*\.\s*packageName\s*\+\s*"\.action\.INSTALL_STATUS"\s*""",
            1,
            listOf(
                listOf("context.packageName + \".action.INSTALL_STATUS\"" to "context.packageName + \".action.STATUS\""),
                listOf("context.packageName + \".action" to "\"com.other\" + \".action"),
            ),
            literal = true,
        )
    }
}
