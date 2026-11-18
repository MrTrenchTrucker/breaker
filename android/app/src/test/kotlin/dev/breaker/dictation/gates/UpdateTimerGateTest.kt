package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source pins for the handler timer in wiring/UpdateTimerAndroid.kt. The class names Android types, so no JVM
 * test can run it. Each pin reads the real file with comments and string text blanked, except the thread name,
 * which is read from the raw text. Each pin counts one safety point in its code and is broken by at least two
 * edited samples. Every pin must stay quiet on harmless edits: a comment, renamed locals and blank lines.
 */
internal class UpdateTimerGateTest {

    private val file: String = "kotlin/dev/breaker/dictation/wiring/UpdateTimerAndroid.kt"
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
    private fun pin(
        label: String,
        pattern: String,
        firing: List<List<Pair<String, String>>>,
        literal: Boolean = false,
        times: Int = 1,
    ) {
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
            "override fun cancel() {" to "override fun cancel() { // drops the waiting tasks",
            "override fun scheduleOnce(afterMs: Long, task: Runnable) {" to "override fun scheduleOnce(afterMs: Long, task: Runnable) { // posts once",
            "private var handler: Handler? = null" to "private var handler: Handler? = null // created on first use",
            "private fun startHandler(): Handler {" to "private fun startHandler(): Handler { // a thread of its own",
        ),
        listOf(
            "private val lock: Any = Any()" to "private val guard: Any = Any()",
            "synchronized(lock)" to "synchronized(guard)",
            "val thread = HandlerThread(THREAD_NAME)" to "val worker = HandlerThread(THREAD_NAME)",
            "thread.start()" to "worker.start()",
            "Handler(thread.looper)" to "Handler(worker.looper)",
            "task: Runnable" to "job: Runnable",
            "postDelayed(task," to "postDelayed(job,",
            "afterMs" to "delayMs",
            "catch (e: Exception) {" to "catch (failure: Exception) {",
        ),
        listOf(
            "override fun cancel() {" to "override fun cancel() {\n\n",
            "override fun scheduleOnce(afterMs: Long, task: Runnable) {" to "override fun scheduleOnce(afterMs: Long, task: Runnable) {\n\n",
            "private fun startHandler(): Handler {" to "private fun startHandler(): Handler {\n\n",
        ),
    )

    @Test
    fun `cancel drops the tasks still waiting on the timer thread`() {
        pin(
            "the cancel body",
            """override\s+fun\s+cancel\(\)\s*\{\s*try\s*\{\s*synchronized\(\w+\)\s*\{\s*handler\?\.removeCallbacksAndMessages\(null\)\s*\}""",
            listOf(
                listOf("synchronized(lock) { handler?.removeCallbacksAndMessages(null) }" to "synchronized(lock) { Unit }"),
                listOf("handler?.removeCallbacksAndMessages(null)" to "handler?.removeCallbacksAndMessages(this)"),
            ),
        )
    }

    @Test
    fun `scheduleOnce posts the task with the given delay and clamps a negative delay to zero`() {
        pin(
            "the scheduled post",
            """override\s+fun\s+scheduleOnce\((\w+):\s*Long,\s*(\w+):\s*Runnable\)\s*\{\s*try\s*\{\s*handlerOrCreate\(\)\.postDelayed\(\s*\2,\s*\1\.coerceAtLeast\(0L\)\s*\)""",
            listOf(
                listOf("handlerOrCreate().postDelayed(task, afterMs.coerceAtLeast(0L))" to "handlerOrCreate().post(task)"),
                listOf("afterMs.coerceAtLeast(0L)" to "afterMs"),
            ),
        )
    }

    @Test
    fun `the handler is created lazily under the lock and not in a property initialiser`() {
        pin(
            "the lazy creation under the lock",
            """synchronized\(\w+\)\s*\{\s*handler\s*\?:\s*startHandler\(\)\.also\s*\{\s*handler\s*=\s*it\s*\}\s*\}""",
            listOf(
                listOf("handler ?: startHandler().also { handler = it }" to "handler ?: startHandler()"),
                listOf("synchronized(lock) {" to "run {"),
            ),
        )
        pin(
            "the null property initialiser",
            """private\s+var\s+handler:\s*Handler\?\s*=\s*null\b""",
            listOf(
                listOf("private var handler: Handler? = null" to "private var handler: Handler? = startHandler()"),
                listOf("private var handler: Handler? = null" to "private var handler: Handler? = nullable"),
            ),
        )
    }

    @Test
    fun `the timer thread is started with the update-timer name and its looper is used`() {
        pin(
            "the raw thread name",
            "THREAD_NAME:\\s*String\\s*=\\s*\"update-timer\"",
            listOf(
                listOf("\"update-timer\"" to "\"update-timers\""),
                listOf("THREAD_NAME: String = \"update-timer\"" to "THREAD_NAME: String = \"update-check\""),
            ),
            literal = true,
        )
        pin(
            "the thread start and looper",
            """val\s+(\w+)\s*=\s*HandlerThread\(THREAD_NAME\)\s*\1\.start\(\)\s*return\s+Handler\(\1\.looper\)""",
            listOf(
                listOf("thread.start()" to "thread.name"),
                listOf("Handler(thread.looper)" to "Handler(thread.name)"),
            ),
        )
    }

    @Test
    fun `neither scheduleOnce nor cancel lets an exception escape`() {
        pin(
            "the try and catch of scheduleOnce",
            """override\s+fun\s+scheduleOnce\([^)]*\)\s*\{\s*try\s*\{[\s\S]*?\}\s*catch\s*\(\s*\w+\s*:\s*Exception\s*\)\s*\{""",
            listOf(
                listOf("catch (e: Exception) {" to "catch (e: Throwable) {"),
                listOf(
                    "override fun scheduleOnce(afterMs: Long, task: Runnable) {" to
                        "override fun scheduleOnce(afterMs: Long, task: Runnable) { if (afterMs < 0L) throw IllegalStateException()",
                ),
            ),
        )
        pin(
            "the try and catch of cancel",
            """override\s+fun\s+cancel\(\)\s*\{\s*try\s*\{[\s\S]*?\}\s*catch\s*\(\s*\w+\s*:\s*Exception\s*\)\s*\{""",
            listOf(
                listOf("catch (e: Exception) {" to "catch (e: Throwable) {"),
                listOf("override fun cancel() {" to "override fun cancel() { if (handler == null) throw IllegalStateException()"),
            ),
        )
    }

    @Test
    fun `the class declares the TimerScheduler supertype and overrides both methods`() {
        pin(
            "the supertype",
            """class\s+HandlerTimerScheduler\s*:\s*TimerScheduler\s*\{""",
            listOf(
                listOf("class HandlerTimerScheduler : TimerScheduler {" to "class HandlerTimerScheduler : Runnable {"),
                listOf("class HandlerTimerScheduler" to "class HandlerTimerSchedule"),
            ),
        )
        pin(
            "the two overrides",
            """override\s+fun\s+(scheduleOnce|cancel)\b""",
            listOf(
                listOf("override fun cancel() {" to "fun cancel() {"),
                listOf("override fun scheduleOnce(" to "fun scheduleOnce("),
            ),
            times = 2,
        )
    }
}
