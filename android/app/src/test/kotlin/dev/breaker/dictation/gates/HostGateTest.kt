package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Android files that put the tile and the threads together, because no JVM test can run
 * them: `host/AppThreads.kt`, `host/OverlayTilePort.kt` and `host/TileHost.kt`. The notices and the
 * launcher activity are pinned by `HostGateNotifyTest`, which reads the files the same way. The rules
 * read the real files with comments and literal text removed by the shared scanner. Each rule holds on
 * the real file, is broken by at least two edited samples, and stays quiet on harmless edits; an edit
 * whose target text is missing fails by name, so a sample cannot go quiet by a typo. One rule reads
 * the whole app: `host/AppThreads.kt` is the only file that names a handler, a looper, an executor or
 * a thread.
 */

internal class HostGateTest {

    private val dir: String = "kotlin/dev/breaker/dictation/"
    private val files: Map<String, String> = mapOf(
        "threads" to dir + "host/AppThreads.kt",
        "tile" to dir + "host/OverlayTilePort.kt",
        "host" to dir + "host/TileHost.kt",
    )

    private class Rule(val name: String, val file: String, val holds: (String) -> Boolean)
    private class Sample(val rule: String, val old: String, val new: String)
    private class Quiet(val file: String, val old: String, val new: String)

    /** The pattern must match the code exactly [times] times. */
    private fun rx(name: String, file: String, pattern: String, times: Int = 1) =
        Rule(name, file) { Regex(pattern).findAll(it).count() == times }

    private val rules: List<Rule> = listOf(
        rx("MAIN_POST_USES_THE_MAIN_LOOPER", "threads", """\bHandler\s*\(\s*Looper\s*\.\s*getMainLooper\s*\(\s*\)\s*\)"""),
        rx("MAIN_POST_QUEUES_EVERY_BLOCK", "threads", """\bhandler\s*\.\s*post\s*\{\s*block\s*\(\s*\)\s*\}"""),
        rx("MAIN_POST_NEVER_RUNS_INLINE", "threads", """\b(myLooper|isCurrentThread|runOnUiThread)\b""", 0),
        rx("BACKGROUND_IS_ONE_SINGLE_THREAD_EXECUTOR", "threads", """\bExecutors\s*\.\s*newSingleThreadExecutor\s*[({]"""),
        rx("BACKGROUND_HAS_NO_OTHER_POOL", "threads", """\bExecutors\s*\.\s*new(?!SingleThreadExecutor)\w+""", 0),
        rx("BACKGROUND_THREAD_IS_A_NAMED_DAEMON", "threads", """\bThread\s*\(\s*work\s*,\s*threadName\s*\)\s*\.\s*apply\s*\{\s*isDaemon\s*=\s*true\s*\}"""),
        rx("BACKGROUND_KEEPS_RUNNING_AFTER_A_THROW", "threads", """\bexecutor\s*\.\s*execute\s*\{\s*try\s*\{\s*block\s*\(\s*\)\s*\}\s*catch\s*\(\s*\w+\s*:\s*Exception\s*\)"""),
        rx("TILE_SHOWN_IS_SHOWN", "tile", """\bShowResult\s*\.\s*SHOWN\s*->\s*TileShow\s*\.\s*SHOWN\b"""),
        rx("TILE_ALREADY_SHOWN_IS_SHOWN", "tile", """\bShowResult\s*\.\s*ALREADY_SHOWN\s*->\s*TileShow\s*\.\s*SHOWN\b"""),
        rx("TILE_PERMISSION_MISSING_IS_NO_PERMISSION", "tile", """\bShowResult\s*\.\s*PERMISSION_MISSING\s*->\s*TileShow\s*\.\s*NO_PERMISSION\b"""),
        rx("TILE_FAILED_IS_FAILED", "tile", """\bShowResult\s*\.\s*FAILED\s*->\s*TileShow\s*\.\s*FAILED\b"""),
        rx("TILE_RESULT_AND_THEME_MAPS_HAVE_NO_ELSE", "tile", """\belse\s*->""", 0),
        rx("TILE_THEME_MAP_IS_COMPLETE", "tile", """\bThemeMode\s*\.\s*LIGHT\s*->\s*TileTheme\s*\.\s*LIGHT\s+ThemeMode\s*\.\s*DARK\s*->\s*TileTheme\s*\.\s*DARK\s+ThemeMode\s*\.\s*SYSTEM\s*->\s*if\s*\(\s*phoneIsInNightMode\s*\(\s*\)\s*\)\s*TileTheme\s*\.\s*DARK\s+else\s+TileTheme\s*\.\s*LIGHT\b"""),
        rx("TILE_IS_MADE_ONCE_AND_KEPT", "tile", """\btile\s*\?:\s*FloatingTile\s*\.\s*create\s*\([\s\S]*?\)\s*tile\s*=\s*made\b"""),
        rx("TILE_IS_MADE_ONLY_THERE", "tile", """\bFloatingTile\s*\.\s*create\s*\("""),
        rx("TILE_THEME_IS_SET_BEFORE_SHOW", "tile", """\bmade\s*\.\s*setTheme\s*\(\s*theme\s*\)\s*return\s+when\s*\(\s*made\s*\.\s*show\s*\(\s*\)\s*\)"""),
        rx("TILE_TAPS_REACH_THE_CALLBACKS", "tile", """\bonTap\s*=\s*onTap\s*,\s*theme\s*=\s*theme\s*,\s*onBegin\s*=\s*onBegin\s*,\s*onCancel\s*=\s*onCancel\s*,\s*onSend\s*=\s*onSend\b"""),
        rx("TILE_IS_NEVER_FORCED", "tile", """!!""", 0),
        rx("COORDINATOR_IS_NAMED_THREE_TIMES", "host", """\bcoordinator\b""", 3),
        rx("COORDINATOR_IS_REACHED_ON_THE_MAIN_POST", "host", """\bmain\s*\.\s*post\s*\{\s*block\s*\(\s*coordinator\s*\)\s*\}"""),
        rx("COORDINATOR_IS_BUILT_LAZILY", "host", """\bval\s+coordinator\s*:\s*TileCoordinator\s+by\s+lazy\b"""),
        rx("TILE_TAPS_GO_THROUGH_THE_POST", "host", """\bonTap\s*=\s*\{\s*toCoordinator\s*\{\s*it\s*\.\s*onTap\s*\(\s*\)\s*\}\s*\}\s*,\s*onBegin\s*=\s*\{\s*toCoordinator\s*\{\s*it\s*\.\s*onBegin\s*\(\s*\)\s*\}\s*\}\s*,\s*onCancel\s*=\s*\{\s*toCoordinator\s*\{\s*it\s*\.\s*onCancel\s*\(\s*\)\s*\}\s*\}\s*,\s*onSend\s*=\s*\{\s*toCoordinator\s*\{\s*it\s*\.\s*onSend\s*\(\s*\)\s*\}\s*\}"""),
        rx("ARMED_CHANGE_GOES_THROUGH_THE_POST", "host", """\bgesture\s*\.\s*stop\s*\(\s*\)\s*\}\s*toCoordinator\s*\{\s*it\s*\.\s*onArmedChanged\s*\(\s*armed\s*\)\s*\}\s*\}"""),
        rx("ONE_DOWNLOADER", "host", """\bModelDownloader\s*\("""),
        rx("ONE_DOWNLOAD_ENTRY_POINT", "host", """\bfun\s+requestDownload\s*\(\s*\)\s*\{\s*downloader\s*\.\s*requestDownload\s*\(\s*\)\s*\}"""),
        rx("SELECTED_MODEL_IS_THE_MODEL_SIZE_SETTING", "host", """\bselectedId\s*:\s*\(\s*\)\s*->\s*String\s*=\s*\{\s*root\s*\.\s*settingsStore\s*\.\s*load\s*\(\s*\)\s*\.\s*modelSize\s*\}"""),
        rx("TILE_NIGHT_MODE_IS_THE_PHONES", "tile", """\buiMode\s+and\s+Configuration\s*\.\s*UI_MODE_NIGHT_MASK\s+return\s+night\s*==\s*Configuration\s*\.\s*UI_MODE_NIGHT_YES\b"""),
        rx("DOWNLOAD_AND_TILE_SHARE_ONE_MODEL_STORE", "host", """\bmodelInstallPortFor\s*\(\s*root\s*\.\s*modelStore\s*\)[\s\S]*\bStoreModelReady\s*\(\s*root\s*\.\s*modelStore\s*,|\bStoreModelReady\s*\(\s*root\s*\.\s*modelStore\s*,[\s\S]*\bmodelInstallPortFor\s*\(\s*root\s*\.\s*modelStore\s*\)"""),
        rx("TWO_BACKGROUNDS", "host", """\bSerialBackground\s*\(\s*""\s*\)""", 2),
        rx("LAUNCHER_OPENS_AS_A_NEW_TASK", "host", """\bIntent\s*\(\s*context\s*,\s*SettingsLauncherActivity\s*::\s*class\s*\.\s*java\s*\)\s*\.\s*addFlags\s*\(\s*Intent\s*\.\s*FLAG_ACTIVITY_NEW_TASK\b"""),
        rx("LAUNCHER_GETS_THE_ROUTE", "host", """\bputExtra\s*\(\s*NotificationRoute\s*\.\s*EXTRA_ROUTE\s*,\s*route\s*\)"""),
        rx("LAUNCHER_REFUSAL_IS_CAUGHT", "host", """\btry\s*\{[\s\S]*?\bcontext\s*\.\s*startActivity\s*\(\s*intent\s*\)[\s\S]*?\}\s*catch\s*\(\s*\w+\s*:\s*RuntimeException\s*\)"""),
        rx("TILE_HOST_TAKES_THE_SWITCH_STATE_AS_A_REQUIRED_LAMBDA", "host", """\bprivate\s+val\s+isOn\s*:\s*\(\s*\)\s*->\s*Boolean\s*,?\s*\)\s*:\s*Opener\b"""),
        rx("LAUNCHER_VISIBLE_SHOWS_THE_TILE_ONLY_WHILE_THE_SWITCH_IS_ON", "host", """\bfun\s+onLauncherVisible\s*\(\s*\)\s*\{\s*main\s*\.\s*post\s*\{\s*if\s*\(\s*isOn\s*\(\s*\)\s*\)\s*coordinator\s*\.\s*onArmedChanged\s*\(\s*true\s*\)\s*\}\s*\}"""),
        rx("GESTURE_COMES_FROM_THE_SWAP_POINT", "host", """\bval\s+gesture\s*=\s*appGesture\s*\(\s*\)"""),
        rx("NO_PLACEHOLDER_GESTURE_IS_NAMED_IN_THE_HOST", "host", """\bNoGesture\b""", 0),
        rx("GESTURE_STARTS_WHEN_THE_SERVICE_GOES_ON", "host", """\bif\s*\(\s*armed\s*\)\s*gesture\s*\.\s*start\s*\{"""),
        rx("GESTURE_STOPS_WHEN_THE_SERVICE_GOES_OFF", "host", """\}\s*\}\s*else\s*gesture\s*\.\s*stop\s*\(\s*\)\s*\}"""),
        rx("GESTURE_TRIGGER_BEGINS_A_TAKE_THROUGH_THE_POST", "host", """\bgesture\s*\.\s*start\s*\{\s*toCoordinator\s*\{\s*it\s*\.\s*onBegin\s*\(\s*\)\s*\}\s*\}"""),
        rx("GESTURE_CHANGE_IS_POSTED_TO_THE_MAIN_LOOPER_FIRST", "host", """\bfun\s+onArmedChanged\s*\(\s*armed\s*:\s*Boolean\s*\)\s*\{\s*main\s*\.\s*post\s*\{\s*if\s*\(\s*armed\s*\)"""),
        rx("MODEL_READY_GETS_THE_SELECTED_ID_LAMBDA", "host", """\bStoreModelReady\s*\(\s*root\s*\.\s*modelStore\s*,\s*selectedId\s*\)"""),
        rx("DOWNLOADER_GETS_THE_SELECTED_ID_LAMBDA", "host", """\bselectedId\s*=\s*selectedId\s*,"""),
        rx("TILE_HIDE_HIDES_THE_TILE", "tile", """\boverride\s+fun\s+hide\s*\(\s*\)\s*\{\s*tile\s*\?\.\s*hide\s*\(\s*\)\s*\}"""),
        rx("TILE_SET_STATE_PASSES_THE_STATE", "tile", """\boverride\s+fun\s+setState\s*\(\s*state\s*:\s*TileState\s*\)\s*\{\s*tile\s*\?\.\s*setState\s*\(\s*state\s*\)\s*\}"""),
        rx("TILE_SHOW_NOTICE_PASSES_THE_TEXT", "tile", """\boverride\s+fun\s+showNotice\s*\(\s*text\s*:\s*String\s*\)\s*\{\s*tile\s*\?\.\s*showNotice\s*\(\s*text\s*\)\s*\}"""),
        rx("TILE_CLEAR_NOTICE_CLEARS_THE_NOTICE", "tile", """\boverride\s+fun\s+clearNotice\s*\(\s*\)\s*\{\s*tile\s*\?\.\s*clearNotice\s*\(\s*\)\s*\}"""),
        rx("DOWNLOADER_GETS_THE_FORWARDING_NOTICE", "host", """\bnotice\s*=\s*forwardingNotice\s*,"""),
        rx("TAKE_ENDED_IS_WIRED_THROUGH_THE_POST", "host", """\broot\.onTakeEnded\s*=\s*\{\s*toCoordinator\s*\{\s*it\.onTakeEnded\(\)\s*\}\s*\}"""),
    )
    private val firing: List<Sample> = listOf(
        Sample("MAIN_POST_USES_THE_MAIN_LOOPER", "Handler(Looper.getMainLooper())", "Handler(Looper.myLooper()!!)"),
        Sample("MAIN_POST_USES_THE_MAIN_LOOPER", "Handler(Looper.getMainLooper())", "Handler()"),
        Sample("MAIN_POST_QUEUES_EVERY_BLOCK", "handler.post { block() }", "block()"),
        Sample("MAIN_POST_QUEUES_EVERY_BLOCK", "handler.post { block() }", "handler.postDelayed({ block() }, 5)"),
        Sample("MAIN_POST_NEVER_RUNS_INLINE", "handler.post { block() }", "if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post { block() }"),
        Sample("MAIN_POST_NEVER_RUNS_INLINE", "handler.post { block() }", "handler.post { block() }\n        runOnUiThread { }"),
        Sample("BACKGROUND_IS_ONE_SINGLE_THREAD_EXECUTOR", "Executors.newSingleThreadExecutor {", "Executors.newFixedThreadPool(2) {"),
        Sample("BACKGROUND_IS_ONE_SINGLE_THREAD_EXECUTOR", "Executors.newSingleThreadExecutor {", "Executors.newSingleThreadExecutor {\n        Executors.newSingleThreadExecutor()\n"),
        Sample("BACKGROUND_HAS_NO_OTHER_POOL", "Executors.newSingleThreadExecutor {", "Executors.newCachedThreadPool {"),
        Sample("BACKGROUND_HAS_NO_OTHER_POOL", "executor.execute {", "Executors.newScheduledThreadPool(1)\n        executor.execute {"),
        Sample("BACKGROUND_THREAD_IS_A_NAMED_DAEMON", "Thread(work, threadName).apply { isDaemon = true }", "Thread(work, threadName)"),
        Sample("BACKGROUND_THREAD_IS_A_NAMED_DAEMON", "Thread(work, threadName).apply { isDaemon = true }", "Thread(work).apply { isDaemon = true }"),
        Sample("BACKGROUND_KEEPS_RUNNING_AFTER_A_THROW", "catch (e: Exception) {", "catch (e: IllegalStateException) {"),
        Sample("BACKGROUND_KEEPS_RUNNING_AFTER_A_THROW", "            try {\n                block()\n            } catch (e: Exception) {\n                // The caller's own guard reports a failure; the worker must stay alive for the next block.\n            }", "            block()"),
        Sample("TILE_SHOWN_IS_SHOWN", "ShowResult.SHOWN -> TileShow.SHOWN", "ShowResult.SHOWN -> TileShow.FAILED"),
        Sample("TILE_SHOWN_IS_SHOWN", "ShowResult.SHOWN -> TileShow.SHOWN\n", ""),
        Sample("TILE_ALREADY_SHOWN_IS_SHOWN", "ShowResult.ALREADY_SHOWN -> TileShow.SHOWN", "ShowResult.ALREADY_SHOWN -> TileShow.FAILED"),
        Sample("TILE_ALREADY_SHOWN_IS_SHOWN", "            ShowResult.ALREADY_SHOWN -> TileShow.SHOWN\n", ""),
        Sample("TILE_PERMISSION_MISSING_IS_NO_PERMISSION", "ShowResult.PERMISSION_MISSING -> TileShow.NO_PERMISSION", "ShowResult.PERMISSION_MISSING -> TileShow.FAILED"),
        Sample("TILE_PERMISSION_MISSING_IS_NO_PERMISSION", "ShowResult.PERMISSION_MISSING -> TileShow.NO_PERMISSION", "ShowResult.PERMISSION_MISSING -> TileShow.SHOWN"),
        Sample("TILE_FAILED_IS_FAILED", "ShowResult.FAILED -> TileShow.FAILED", "ShowResult.FAILED -> TileShow.SHOWN"),
        Sample("TILE_FAILED_IS_FAILED", "            ShowResult.FAILED -> TileShow.FAILED\n", "            else -> TileShow.FAILED\n"),
        Sample("TILE_RESULT_AND_THEME_MAPS_HAVE_NO_ELSE", "ShowResult.FAILED -> TileShow.FAILED", "else -> TileShow.FAILED"),
        Sample("TILE_RESULT_AND_THEME_MAPS_HAVE_NO_ELSE", "ThemeMode.DARK -> TileTheme.DARK", "else -> TileTheme.DARK"),
        Sample("TILE_THEME_MAP_IS_COMPLETE", "ThemeMode.DARK -> TileTheme.DARK", "ThemeMode.DARK -> TileTheme.LIGHT"),
        Sample("TILE_THEME_MAP_IS_COMPLETE", "ThemeMode.SYSTEM -> if (phoneIsInNightMode()) TileTheme.DARK else TileTheme.LIGHT", "ThemeMode.SYSTEM -> TileTheme.LIGHT"),
        Sample("TILE_IS_MADE_ONCE_AND_KEPT", "tile = made", "tile = null"),
        Sample("TILE_IS_MADE_ONCE_AND_KEPT", "val made = tile ?: FloatingTile.create(", "val made = FloatingTile.create("),
        Sample("TILE_IS_MADE_ONLY_THERE", "override fun hide() {", "override fun hide() {\n        FloatingTile.create(context, settings, onTap, TileTheme.LIGHT)"),
        Sample("TILE_IS_MADE_ONLY_THERE", "tile = made", "tile = made\n        FloatingTile.create(context, settings, onTap, theme)"),
        Sample("TILE_THEME_IS_SET_BEFORE_SHOW", "made.setTheme(theme)\n", ""),
        Sample("TILE_THEME_IS_SET_BEFORE_SHOW", "made.setTheme(theme)", "made.setTheme(TileTheme.LIGHT)"),
        Sample("TILE_TAPS_REACH_THE_CALLBACKS", "onCancel = onCancel,", ""),
        Sample("TILE_TAPS_REACH_THE_CALLBACKS", "onSend = onSend,", "onSend = onBegin,"),
        Sample("TILE_IS_NEVER_FORCED", "tile?.hide()", "tile!!.hide()"),
        Sample("TILE_IS_NEVER_FORCED", "tile?.setState(state)", "tile!!.setState(state)"),
        Sample("COORDINATOR_IS_NAMED_THREE_TIMES", "block(coordinator)", "block(coordinator)\n        coordinator.onTap()"),
        Sample("COORDINATOR_IS_NAMED_THREE_TIMES", "toCoordinator { it.onArmedChanged(armed) }", "coordinator.onArmedChanged(armed)"),
        Sample("COORDINATOR_IS_REACHED_ON_THE_MAIN_POST", "main.post { block(coordinator) }", "block(coordinator)"),
        Sample("COORDINATOR_IS_REACHED_ON_THE_MAIN_POST", "main.post { block(coordinator) }", "main.post { }\n        block(coordinator)"),
        Sample("COORDINATOR_IS_BUILT_LAZILY", "val coordinator: TileCoordinator by lazy {", "val coordinator: TileCoordinator = run {"),
        Sample("COORDINATOR_IS_BUILT_LAZILY", "private val coordinator: TileCoordinator by lazy {", "private val coordinator by lazy {"),
        Sample("TILE_TAPS_GO_THROUGH_THE_POST", "onTap = { toCoordinator { it.onTap() } },", "onTap = { },"),
        Sample("TILE_TAPS_GO_THROUGH_THE_POST", "onSend = { toCoordinator { it.onSend() } },", "onSend = { toCoordinator { it.onBegin() } },"),
        Sample("ARMED_CHANGE_GOES_THROUGH_THE_POST", "toCoordinator { it.onArmedChanged(armed) }", "main.post { }"),
        Sample("ARMED_CHANGE_GOES_THROUGH_THE_POST", "toCoordinator { it.onArmedChanged(armed) }", "toCoordinator { it.onArmedChanged(true) }"),
        Sample("ONE_DOWNLOADER", "    /** Starts the download", "    private val second = ModelDownloader(installer = modelInstallPortFor(root.modelStore), selectedId = selectedId, background = SerialBackground(\"x\"), main = main, notice = notifications)\n\n    /** Starts the download"),
        Sample("ONE_DOWNLOADER", "private val downloader = ModelDownloader(", "private val downloader = other("),
        Sample("ONE_DOWNLOAD_ENTRY_POINT", "downloader.requestDownload()\n    }", "downloader.requestDownload { }\n    }"),
        Sample("ONE_DOWNLOAD_ENTRY_POINT", "fun requestDownload() {", "fun requestDownload(again: Boolean) {"),
        Sample("SELECTED_MODEL_IS_THE_MODEL_SIZE_SETTING", "root.settingsStore.load().modelSize", "root.settingsStore.load().language"),
        Sample("SELECTED_MODEL_IS_THE_MODEL_SIZE_SETTING", "{ root.settingsStore.load().modelSize }", "{ \"small\" }"),
        Sample("TILE_NIGHT_MODE_IS_THE_PHONES", "night == Configuration.UI_MODE_NIGHT_YES", "night != Configuration.UI_MODE_NIGHT_YES"),
        Sample("TILE_NIGHT_MODE_IS_THE_PHONES", "and Configuration.UI_MODE_NIGHT_MASK", "and Configuration.UI_MODE_NIGHT_YES"),
        Sample("DOWNLOAD_AND_TILE_SHARE_ONE_MODEL_STORE", "installer = modelInstallPortFor(root.modelStore),", "installer = modelInstallPortFor(LocalModelStore(java.io.File(\"x\"))),"),
        Sample("DOWNLOAD_AND_TILE_SHARE_ONE_MODEL_STORE", "StoreModelReady(root.modelStore, selectedId)", "StoreModelReady(other, selectedId)"),
        Sample("TWO_BACKGROUNDS", "background = SerialBackground(\"breaker-model-download\"),", "background = main,"),
        Sample("TWO_BACKGROUNDS", "            main,\n            SerialBackground(\"breaker-dictation\"),", "            main,\n            SerialBackground(\"breaker-dictation\"),\n            SerialBackground(\"breaker-extra\"),"),
        Sample("LAUNCHER_OPENS_AS_A_NEW_TASK", "Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP", "Intent.FLAG_ACTIVITY_CLEAR_TOP"),
        Sample("LAUNCHER_OPENS_AS_A_NEW_TASK", "SettingsLauncherActivity::class.java", "OtherActivity::class.java"),
        Sample("LAUNCHER_GETS_THE_ROUTE", "intent.putExtra(NotificationRoute.EXTRA_ROUTE, route)", "intent.putExtra(\"route\", route)"),
        Sample("LAUNCHER_GETS_THE_ROUTE", "if (route != null) intent.putExtra(NotificationRoute.EXTRA_ROUTE, route)\n", ""),
        Sample("LAUNCHER_REFUSAL_IS_CAUGHT", "catch (e: RuntimeException) {", "catch (e: IllegalStateException) {"),
        Sample("LAUNCHER_REFUSAL_IS_CAUGHT", "            context.startActivity(intent)\n        } catch (e: RuntimeException) {\n            // The platform refused to open the launcher; the tile stays as it is.\n        }", "            context.startActivity(intent)\n        } finally {\n        }"),
        Sample("TILE_HOST_TAKES_THE_SWITCH_STATE_AS_A_REQUIRED_LAMBDA", "private val isOn: () -> Boolean,", "private val isOn: () -> Boolean = { true },"),
        Sample("TILE_HOST_TAKES_THE_SWITCH_STATE_AS_A_REQUIRED_LAMBDA", "private val isOn: () -> Boolean,", "private val isOn: () -> Boolean? = null,"),
        Sample("LAUNCHER_VISIBLE_SHOWS_THE_TILE_ONLY_WHILE_THE_SWITCH_IS_ON", "if (isOn()) coordinator.onArmedChanged(true)", "coordinator.onArmedChanged(true)"),
        Sample("LAUNCHER_VISIBLE_SHOWS_THE_TILE_ONLY_WHILE_THE_SWITCH_IS_ON", "if (isOn()) coordinator.onArmedChanged(true)", "if (isOn()) coordinator.onArmedChanged(false)"),
        Sample("LAUNCHER_VISIBLE_SHOWS_THE_TILE_ONLY_WHILE_THE_SWITCH_IS_ON", "if (isOn()) coordinator.onArmedChanged(true)", "if (!isOn()) coordinator.onArmedChanged(true)"),
        Sample("LAUNCHER_VISIBLE_SHOWS_THE_TILE_ONLY_WHILE_THE_SWITCH_IS_ON", "main.post { if (isOn()) coordinator.onArmedChanged(true) }", "main.post { val c = coordinator; if (isOn()) c.onArmedChanged(true) }"),
        Sample("LAUNCHER_VISIBLE_SHOWS_THE_TILE_ONLY_WHILE_THE_SWITCH_IS_ON", "main.post { if (isOn()) coordinator.onArmedChanged(true) }", "toCoordinator { if (isOn()) it.onArmedChanged(true) }"),
        Sample("GESTURE_COMES_FROM_THE_SWAP_POINT", "val gesture = appGesture()", "val gesture = NoGesture()"),
        Sample("GESTURE_COMES_FROM_THE_SWAP_POINT", "val gesture = appGesture()", "val gesture = otherGesture()"),
        Sample("NO_PLACEHOLDER_GESTURE_IS_NAMED_IN_THE_HOST", "val gesture = appGesture()", "val gesture = NoGesture()"),
        Sample("NO_PLACEHOLDER_GESTURE_IS_NAMED_IN_THE_HOST", "val gesture = appGesture()", "val gesture = appGesture()\n    private val extra = NoGesture()"),
        Sample("GESTURE_STARTS_WHEN_THE_SERVICE_GOES_ON", "if (armed) gesture.start {", "if (!armed) gesture.start {"),
        Sample("GESTURE_STARTS_WHEN_THE_SERVICE_GOES_ON", "if (armed) gesture.start {", "if (armed) gesture.stop {"),
        Sample("GESTURE_STOPS_WHEN_THE_SERVICE_GOES_OFF", "else gesture.stop()", "else Unit"),
        Sample("GESTURE_STOPS_WHEN_THE_SERVICE_GOES_OFF", "else gesture.stop()", "else gesture.start { }"),
        Sample("GESTURE_STOPS_WHEN_THE_SERVICE_GOES_OFF", "else gesture.stop()", "else gesture.stop().also { }"),
        Sample("GESTURE_TRIGGER_BEGINS_A_TAKE_THROUGH_THE_POST", "gesture.start { toCoordinator { it.onBegin() } }", "gesture.start { toCoordinator { it.onSend() } }"),
        Sample("GESTURE_TRIGGER_BEGINS_A_TAKE_THROUGH_THE_POST", "gesture.start { toCoordinator { it.onBegin() } }", "gesture.start { }"),
        Sample("GESTURE_CHANGE_IS_POSTED_TO_THE_MAIN_LOOPER_FIRST", "main.post { if (armed)", "if (armed)"),
        Sample("GESTURE_CHANGE_IS_POSTED_TO_THE_MAIN_LOOPER_FIRST", "main.post { if (armed)", "toCoordinator { if (armed)"),
        Sample("ARMED_CHANGE_GOES_THROUGH_THE_POST", "gesture.stop() }\n        toCoordinator", "gesture.stop() }\n        main.post { }\n        toCoordinator"),
        Sample("MODEL_READY_GETS_THE_SELECTED_ID_LAMBDA", "StoreModelReady(root.modelStore, selectedId)", "StoreModelReady(root.modelStore) { \"small\" }"),
        Sample("MODEL_READY_GETS_THE_SELECTED_ID_LAMBDA", "StoreModelReady(root.modelStore, selectedId)", "StoreModelReady(root.modelStore, { \"tiny\" })"),
        Sample("DOWNLOADER_GETS_THE_SELECTED_ID_LAMBDA", "selectedId = selectedId,", "selectedId = { \"small\" },"),
        Sample("DOWNLOADER_GETS_THE_SELECTED_ID_LAMBDA", "selectedId = selectedId,", "selectedId = { \"tiny\" },"),
        Sample("TILE_HIDE_HIDES_THE_TILE", "tile?.hide()", "tile?.clearNotice()"),
        Sample("TILE_HIDE_HIDES_THE_TILE", "tile?.hide()", "tile?.show()"),
        Sample("TILE_SET_STATE_PASSES_THE_STATE", "tile?.setState(state)", "tile?.hide()"),
        Sample("TILE_SET_STATE_PASSES_THE_STATE", "tile?.setState(state)", "tile?.showNotice(\"\")"),
        Sample("TILE_SHOW_NOTICE_PASSES_THE_TEXT", "tile?.showNotice(text)", "tile?.clearNotice()"),
        Sample("TILE_SHOW_NOTICE_PASSES_THE_TEXT", "tile?.showNotice(text)", "tile?.showNotice(\"\")"),
        Sample("TILE_CLEAR_NOTICE_CLEARS_THE_NOTICE", "tile?.clearNotice()", "tile?.hide()"),
        Sample("TILE_CLEAR_NOTICE_CLEARS_THE_NOTICE", "tile?.clearNotice()", "tile?.showNotice(\"\")"),
        Sample("DOWNLOADER_GETS_THE_FORWARDING_NOTICE", "notice = forwardingNotice,", "notice = notifications,"),
        Sample("DOWNLOADER_GETS_THE_FORWARDING_NOTICE", "notice = forwardingNotice,", "notice = otherNotice,"),
        Sample("TAKE_ENDED_IS_WIRED_THROUGH_THE_POST", "root.onTakeEnded = { toCoordinator { it.onTakeEnded() } }", "root.onTakeEnded = null"),
        Sample("TAKE_ENDED_IS_WIRED_THROUGH_THE_POST", "root.onTakeEnded = { toCoordinator { it.onTakeEnded() } }", "root.onTakeEnded = { }"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet("threads", "handler.post { block() }", "handler.post {\n            block() // myLooper isCurrentThread newFixedThreadPool\n        }"),
        Quiet("tile", "override fun hide() {", "/* else -> tile!! FloatingTile.create( */\n    override fun hide() {"),
        Quiet("host", "main.post { block(coordinator) }", "main.post {\n            block(coordinator) // coordinator.onTap()\n        }"),
        Quiet("host", "route: String?) {", "route: String?) { // putExtra( startActivity("),
        Quiet("host", "if (isOn()) coordinator.onArmedChanged(true)", "if (isOn()) // onArmedChanged(false) coordinator\n            coordinator.onArmedChanged(true)"),
        Quiet("host", "else gesture.stop()", "else gesture.stop() /* gesture.start { } NoGesture( */"),
        Quiet("host", "selectedId = selectedId,", "selectedId = selectedId, // { \"small\" }"),
        Quiet("host", "StoreModelReady(root.modelStore, selectedId)", "StoreModelReady( root.modelStore,\n            selectedId )"),
        Quiet("tile", "tile?.clearNotice()", "tile\n            ?.clearNotice() // tile?.hide()"),
        Quiet("host", "root.onTakeEnded = { toCoordinator { it.onTakeEnded() } }", "root.onTakeEnded = { toCoordinator { it.onTakeEnded() } } // comment"),
    )

    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a host gate sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }
    private fun raw(key: String): String = AppSourceFiles.mainFile(files.getValue(key))
    private fun codeOf(key: String, old: String? = null, new: String = ""): String =
        AppSourceFiles.strip(if (old == null) raw(key) else edit(raw(key), old, new)).code
    private val threadNames: Regex = Regex("""\b(Handler|HandlerThread|Looper|Executors?|ExecutorService|Thread|runOnUiThread)\b""")

    /** The files other than `host/AppThreads.kt` that name a handler, looper, executor or thread. */
    private fun threadOffences(sources: Map<String, String>): List<String> =
        sources.toSortedMap().filter { (name, text) -> name != "host/AppThreads.kt" && threadNames.containsMatchIn(AppSourceFiles.strip(text).code) }.keys.toList()
    private fun backgroundNames(text: String): List<String> = AppSourceFiles.strip(text).literals.filter { it.startsWith("breaker-") }

    @Test
    fun `every rule holds on the real files`() {
        for (key in files.keys) assertTrue("app: ${files.getValue(key)} was read as empty", codeOf(key).isNotBlank())
        for (rule in rules) assertTrue("app: ${files.getValue(rule.file)} breaks rule ${rule.name}", rule.holds(codeOf(rule.file)))
    }

    @Test
    fun `the firing samples cover each rule at least twice`() {
        val names = rules.map { it.name }
        assertEquals("app: a host rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val count = firing.count { it.rule == rule.name }
            assertTrue("app: host rule ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
    }

    @Test
    fun `every rule fires on each of its edited samples`() {
        val byName = rules.associateBy { it.name }
        for (sample in firing) {
            val rule = byName.getValue(sample.rule)
            assertFalse("app: host rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'", rule.holds(codeOf(rule.file, sample.old, sample.new)))
        }
    }

    @Test
    fun `no rule fires on a harmless edit`() {
        for (sample in quiet) {
            for (rule in rules.filter { it.file == sample.file }) {
                assertTrue("app: host rule ${rule.name} must stay quiet on the harmless edit '${sample.old}' => '${sample.new}'", rule.holds(codeOf(sample.file, sample.old, sample.new)))
            }
        }
    }

    @Test
    fun `only host AppThreads names a handler, a looper, an executor or a thread`() {
        val real = AppSourceFiles.mainKotlinSources()
        assertTrue("app: the host gate did not find host/AppThreads.kt in ${real.keys}", "host/AppThreads.kt" in real)
        assertEquals("app: a main file other than host/AppThreads.kt names a thread type", emptyList<String>(), threadOffences(real))
        val firingSources = mapOf(
            "host/TileHost.kt" to "class A { val h = Handler(Looper.getMainLooper()) }",
            "service/ModelNotifications.kt" to "class A { val e = Executors.newSingleThreadExecutor() }",
            "wiring/TileCoordinator.kt" to "class A { val t = Thread { } }",
            "BreakerApp.kt" to "class A { fun f() { runOnUiThread { } } }",
        )
        for ((name, text) in firingSources) assertEquals("app: $name was not reported", listOf(name), threadOffences(mapOf(name to text)))
        val quietSources = mapOf(
            "host/AppThreads.kt" to "class A { val h = Handler(Looper.getMainLooper()) }",
            "wiring/A.kt" to "// Handler Looper Executors Thread\nclass A { val s = \"Handler Thread\"; val threads = 1; val threadName = 2; val Handlers = 3 }",
        )
        for ((name, text) in quietSources) assertEquals("app: $name was reported", emptyList<String>(), threadOffences(mapOf(name to text)))
    }

    @Test
    fun `the tile host builds two background workers with different names`() {
        assertEquals("app: the tile host must name two different workers", 2, backgroundNames(raw("host")).toSet().size)
        for ((old, new) in listOf("SerialBackground(\"breaker-model-download\")" to "SerialBackground(\"breaker-dictation\")", "SerialBackground(\"breaker-dictation\")" to "SerialBackground(\"breaker-model-download\")")) {
            val names = backgroundNames(edit(raw("host"), old, new)).toSet().size
            assertEquals("app: the two workers shared a name after '$old' => '$new'", 1, names)
        }
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) { edit("val a = 1", "no such text", "x") }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text"))
    }
}
