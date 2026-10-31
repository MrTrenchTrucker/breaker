package dev.breaker.dictation.gates

import java.io.File

/**
 * Keeps the app's unit tests runnable on a plain JVM, where no Android class exists.
 *
 * Every main Kotlin file is listed below as plain (it touches no Android type, so a test
 * may build it) or as android (it is the thin layer over the platform). The lists are
 * literals, so adding, removing or renaming a file turns a rule red and says which one.
 * A plain file may not import or name `android.` or `androidx.`; an android file must
 * name at least one; the unit tests may not import either; and no Kotlin file of the
 * module is longer than 300 lines. The files are found by walking the module, never from
 * a list. Comments, string literals and names in backticks are blanked before a rule
 * reads a file, so a word in a sentence is not a use.
 */
open class AppLayoutGateRules {

    /** Main files that touch no Android type, by path below the package folder. */
    protected val plainFiles: Set<String> = setOf(
        "AppStartPurge.kt", "BreakerCompositionRoot.kt", "FileCredentialRefHolder.kt", "PurgeScope.kt", "SystemClockAdapter.kt", "AutomaticTimeOn.kt", "PurgeBookkeepingStore.kt", "PurgeGuard.kt",
        "service/DictationServiceController.kt", "service/NotificationRoute.kt", "service/ReportingMicSource.kt",
        "service/ServiceStartDecision.kt", "service/ServiceStartHandler.kt", "service/ServiceTypes.kt",
        "wiring/DictationComponent.kt", "wiring/DictationRunner.kt", "wiring/Insets.kt", "wiring/LazyHistoryStore.kt", "wiring/FirstRunDoneStore.kt",
        "wiring/UnavailableSlots.kt", "wiring/UuidIdSource.kt", "wiring/Rebuilt.kt", "wiring/ClockText.kt",
        "wiring/TilePorts.kt", "wiring/TilePortCalls.kt", "wiring/DelayedPost.kt", "wiring/NoticeClearingTilePort.kt", "wiring/TileStateMap.kt", "wiring/TileCoordinator.kt", "wiring/TileShowGuard.kt", "wiring/KeyboardHideHold.kt", "wiring/KeyboardReevaluation.kt", "wiring/TakePortAdapter.kt", "wiring/NoopPorts.kt", "wiring/KeyboardTileDecision.kt", "wiring/KeyboardTileSession.kt", "wiring/BlockGuard.kt", "wiring/KeyboardTileWiring.kt",
        "wiring/ArmedSwitch.kt", "wiring/DisplayWatch.kt", "wiring/LaunchRule.kt", "wiring/IconLaunchOnce.kt", "wiring/OffStore.kt", "wiring/ModelSupport.kt", "wiring/ThemeModeSupport.kt", "wiring/Swaps.kt",
        "wiring/BreakerSwitchAdapter.kt", "wiring/ForwardingDownloadNotice.kt",
        "wiring/TakeYield.kt", "wiring/TakenClipboard.kt", "wiring/MicTakeHold.kt",
        "wiring/TakenSentences.kt", "wiring/TakeSettle.kt", "wiring/DeviceFailureSentences.kt", "wiring/DeviceFailureYield.kt", "wiring/TakeFinisher.kt",
        "wiring/HomeTakeAdapter.kt", "wiring/EndingTexts.kt", "wiring/HomeSentences.kt", "wiring/TakeOwner.kt", "wiring/MicBusyPort.kt",
        "wiring/CompositeLevelSink.kt", "wiring/HomeLevelFeed.kt", "wiring/LevelMap.kt", "wiring/LevelMeter.kt", "wiring/LevelFeed.kt", "wiring/LevelTap.kt", "wiring/NoiseFloor.kt",
        "host/ServiceRefusalNotice.kt",
    )

    /** Main files that are the layer over the platform and name Android types. */
    protected val androidFiles: Set<String> = setOf(
        "BreakerApp.kt", "SettingsLauncherActivity.kt", "AndroidAutomaticTimeOn.kt", "service/AndroidMicPermission.kt",
        "service/AndroidServiceLauncher.kt", "service/DictationForegroundService.kt", "service/DictationNotification.kt",
        "service/ModelNotifications.kt", "host/AppThreads.kt", "host/OverlayTilePort.kt", "host/TileHost.kt", "host/DisplayChangeAndroid.kt",
        "wiring/AndroidSwaps.kt", "wiring/AndroidInsets.kt", "wiring/ThemedScreens.kt",
        "wiring/InfoScreensWiring.kt",
        "wiring/HomeTakeWiring.kt", "wiring/HomeScreenMic.kt",
        "host/TakenClipboardAndroid.kt", "host/ActivityHelpers.kt",
    )

    private val softCap: Int = 300
    private val hardCap: Int = 500
    private val mainPrefix: String = "src/main/kotlin/dev/breaker/dictation/"
    private val testPrefix: String = "src/test/kotlin/dev/breaker/dictation/"

    /** `android.` or `androidx.` as a name of its own (not the end of another name), with what follows it. */
    private val platformName: Regex = Regex("""(?<![\w.])androidx?(?:\s*\.\s*\w+)+""")
    private val spaces: Regex = Regex("""\s+""")

    /** Every Kotlin file below [folder] of the module, by path with [prefix] cut off when it starts the path. */
    private fun readTree(folder: String, prefix: String): Map<String, String> {
        val root: File = AppSourceFiles.moduleRoot
        val start = File(root, folder)
        check(start.isDirectory) { "app: no source folder at $start" }
        val result: MutableMap<String, String> = LinkedHashMap()
        for (file in start.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }) {
            val path: String = file.relativeTo(root).path.replace(File.separatorChar, '/')
            result[path.removePrefix(prefix)] = file.readText(Charsets.UTF_8)
        }
        return result
    }

    protected fun mainFiles(): Map<String, String> = readTree("src/main", mainPrefix)

    protected fun testFiles(): Map<String, String> = readTree("src/test/kotlin", testPrefix)

    /** The distinct `android.` and `androidx.` names used in the code of [source], comments and literals blanked. */
    private fun platformUses(source: String): List<String> =
        platformName.findAll(AppSourceFiles.strip(source).code).map { it.value.replace(spaces, "") }.distinct().toList()

    /** Rule 1: one line per main file in neither or both lists and per listed file that is not there. */
    protected fun layoutProblems(main: Set<String>, plainNames: Set<String>, androidNames: Set<String>): List<String> {
        val found: MutableList<String> = ArrayList()
        for (name in main.sorted()) {
            val inPlain: Boolean = name in plainNames
            val inAndroid: Boolean = name in androidNames
            if (inPlain && inAndroid) {
                found.add("app: the main file $name is in both lists")
            } else if (!inPlain && !inAndroid) {
                found.add("app: the main file $name is in neither list; add it to the plain list or the android list")
            }
        }
        for (name in (plainNames + androidNames).sorted()) {
            if (name !in main) found.add("app: the listed file $name is not in the main sources")
        }
        return found
    }

    /** Rule 2: one line per plain file that is missing or names an android type. */
    protected fun plainProblems(main: Map<String, String>, plainNames: Set<String>): List<String> = plainNames.sorted().mapNotNull { name ->
        val text: String? = main[name]
        val uses: List<String> = if (text == null) emptyList() else platformUses(text)
        when {
            text == null -> "app: the plain file $name is missing"
            uses.isNotEmpty() -> "app: the plain file $name uses ${uses.joinToString(", ")}, which a JVM test cannot load"
            else -> null
        }
    }

    /** Rule 3: one line per android file that is missing or names no android type. */
    protected fun androidProblems(main: Map<String, String>, androidNames: Set<String>): List<String> = androidNames.sorted().mapNotNull { name ->
        val text: String? = main[name]
        when {
            text == null -> "app: the android file $name is missing"
            platformUses(text).isEmpty() -> "app: the android file $name names no android type, so it belongs in the plain list"
            else -> null
        }
    }

    /** Rule 4: one line per test file that names an android type. */
    protected fun testProblems(tests: Map<String, String>): List<String> = tests.toSortedMap().mapNotNull { (name, text) ->
        val uses: List<String> = platformUses(text)
        if (uses.isEmpty()) null else "app: the test file $name uses ${uses.joinToString(", ")}; unit tests run on a plain JVM"
    }

    protected fun lineCount(text: String): Int =
        if (text.isEmpty()) 0 else text.count { it == '\n' } + (if (text.endsWith("\n")) 0 else 1)

    /** Rule 5: one line per file longer than the soft cap; a file over the hard cap is named as that too. */
    protected fun sizeProblems(kind: String, files: Map<String, String>): List<String> = files.toSortedMap().mapNotNull { (name, text) ->
        val lines: Int = lineCount(text)
        val hard: String = if (lines > hardCap) " and over the $hardCap line hard cap" else ""
        if (lines > softCap) "app: the $kind file $name has $lines lines, over the $softCap line cap$hard" else null
    }

    /** [source] with every [from] replaced; fails when [from] is not there, so a sample cannot go quiet by a typo. */
    protected fun edit(label: String, source: String, from: String, to: String): String {
        check(source.contains(from)) { "app: the sample \"$label\" has no text \"$from\" to change" }
        return source.replace(from, to)
    }

    protected val goodPlain: String = "package p\n\nimport dev.breaker.dictation.core.Thing\n\nclass A {\n    fun f(): Int = 1\n}\n"
    protected val goodAndroid: String = "package p\n\nimport android.content.Context\n\nclass B(private val c: Context) {\n    fun g(): Int = 2\n}\n"
    protected val goodTest: String = "package p\n\nimport org.junit.Test\n\nclass ATest {\n    @Test fun t() {}\n}\n"

    protected fun linesOfText(count: Int): String = "x\n".repeat(count)
}
