package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source pins for the app's updater wiring. The Android object of the wiring has no JVM test, so these
 * pins hold its fixed names, its start guard and its call shapes in the source text. Each pin reads the
 * code through the source helper, where comments and the text of string literals are blanked first, so
 * a name in a comment or a sentence does not count.
 */
internal class UpdaterWiringGateTest {

    private val wiring: String = "kotlin/dev/breaker/dictation/wiring/"
    private val appFile: String = "kotlin/dev/breaker/dictation/BreakerApp.kt"
    private val keyAsset: String = "update_keys.txt"

    private fun codeOf(path: String): String = AppSourceFiles.strip(AppSourceFiles.mainFile(path)).code

    private fun literalsOf(path: String): List<String> = AppSourceFiles.strip(AppSourceFiles.mainFile(path)).literals

    @Test
    fun `BreakerApp names the updater start exactly once`() {
        val count: Int = Regex("AppUpdater").findAll(codeOf(appFile)).count()
        assertEquals("app: BreakerApp.kt must name AppUpdater exactly once", 1, count)
    }

    @Test
    fun `BreakerApp stays within three hundred lines`() {
        val text: String = AppSourceFiles.mainFile(appFile)
        assertTrue("app: BreakerApp.kt is over the 300 line cap", text.count { it == '\n' } <= 300)
    }

    @Test
    fun `the plain wiring file for the updater names no android type`() {
        val code: String = codeOf(wiring + "UpdaterWiring.kt")
        assertTrue("app: UpdaterWiring.kt names an android type", !code.contains("android"))
    }

    @Test
    fun `the app updater reads only the key asset as a text file`() {
        val literals: List<String> = literalsOf(wiring + "AppUpdater.kt")
        val otherTexts: List<String> = literals.filter { it.endsWith(".txt") && it != keyAsset }
        assertTrue("app: AppUpdater.kt reads a text file other than the key asset", otherTexts.isEmpty())
        assertTrue("app: AppUpdater.kt does not name the key asset", literals.contains(keyAsset))
    }

    @Test
    fun `the start is guarded and runs on one named thread off the main thread`() {
        val code: String = codeOf(wiring + "AppUpdater.kt")
        val literals: List<String> = literalsOf(wiring + "AppUpdater.kt")
        assertTrue("app: AppUpdater.kt has no guard on start", code.contains("compareAndSet(false, true)"))
        assertTrue("app: AppUpdater.kt does not name its start thread update-start", literals.contains("update-start"))
        assertTrue(
            "app: AppUpdater.kt does not run the start on a new thread",
            code.contains("Thread(Runnable { runStart(app) }, UPDATE_START_THREAD).start()"),
        )
    }

    @Test
    fun `the app updater uses the fixed folder and file names`() {
        val literals: List<String> = literalsOf(wiring + "AppUpdater.kt")
        assertTrue("app: the cache folder of the app updater is not updates", literals.contains("updates"))
        assertTrue("app: the timestamp folder of the app updater is not updater", literals.contains("updater"))
    }

    @Test
    fun `the app updater takes the time from the system clock and records the install result`() {
        val code: String = codeOf(wiring + "AppUpdater.kt")
        assertTrue("app: AppUpdater.kt does not read the system clock", code.contains("nowMs = System::currentTimeMillis,"))
        assertTrue("app: AppUpdater.kt does not keep the install result", code.contains("lastInstallResult = result"))
    }

    @Test
    fun `the store and the scheduler are the updater ports`() {
        val store: String = codeOf(wiring + "UpdateTimesStore.kt")
        assertTrue(store.contains("class UpdateTimesStore(private val folder: File) : UpdateTimes {"))
        assertTrue(store.contains("override fun lastCheckAtMs(): Long? = readTime(CHECK_FILE)"))
        assertTrue(store.contains("override fun recordCheckAtMs(atMs: Long) {"))
        assertTrue(store.contains("override fun recordUpdateAtMs(atMs: Long) {"))
        val timer: String = codeOf(wiring + "UpdateTimerAndroid.kt")
        assertTrue(timer.contains("class HandlerTimerScheduler : TimerScheduler {"))
        assertTrue(timer.contains("override fun scheduleOnce(afterMs: Long, task: Runnable) {"))
        assertTrue(timer.contains("override fun cancel() {"))
    }

    @Test
    fun `the updater wiring passes every named argument through`() {
        val code: String = codeOf(wiring + "UpdaterWiring.kt")
        val arguments: List<String> = listOf(
            "transport = transport,",
            "serverUrl = serverUrl,",
            "apkFolder = apkFolder,",
            "pinnedKeys = pinnedKeys,",
            "isAdmin = isAdmin,",
            "installedVersionCode = installedVersionCode,",
            "installedVersionName = installedVersionName,",
            "installer = installer,",
            "times = times,",
            "nowMs = nowMs,",
            "scheduler = scheduler,",
            "onInstallResult = onInstallResult,",
        )
        for (argument in arguments) {
            assertTrue("app: UpdaterWiring.kt does not pass $argument", code.contains(argument))
        }
    }
}
