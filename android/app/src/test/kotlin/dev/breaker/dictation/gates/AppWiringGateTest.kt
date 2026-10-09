package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the app wiring that no JVM test can run, because it lives in an Android class or behind a
 * lambda nothing calls: how BreakerApp hands the history to the root and builds the microphone
 * service controller, the switch and the tile host, the one state observer that keeps them in step
 * with the service, when the launcher activity switches the service on (only through the switch,
 * which does not arm after a switch-off the user chose), what the root's never-armed controller
 * answers, where the server address comes from and which history store and controller reach the
 * dictation component. The gate reads the real BreakerApp.kt,
 * SettingsLauncherActivity.kt and BreakerCompositionRoot.kt with comments and literal text removed
 * by the shared scanner. Each rule holds on the real file, is broken by at least two edited
 * samples, and stays quiet on harmless edits; an edit whose target text is missing fails by name,
 * so a sample cannot go quiet by a typo.
 */
internal class AppWiringGateTest {

    private val app: String = "kotlin/dev/breaker/dictation/BreakerApp.kt"
    private val activity: String = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"
    private val root: String = "kotlin/dev/breaker/dictation/BreakerCompositionRoot.kt"

    /** The pattern must match the code of [file] exactly [times] times. */
    private class Rule(val name: String, val file: String, val pattern: String, val times: Int = 1) {
        fun holds(code: String): Boolean = Regex(pattern).findAll(code).count() == times
    }

    /** An edit of [file] (old text to new text) that must break the rule named [rule]. */
    private class Sample(val rule: String, val file: String, val old: String, val new: String)

    /** An edit of [file] that leaves everything a rule reads as it was. */
    private class Quiet(val file: String, val old: String, val new: String)

    private val rules: List<Rule> = listOf(
        Rule("HISTORY_HANDED_AS_SUPPLIER", app, """\bBreakerCompositionRoot\s*\(\s*filesDir\s*,\s*\{\s*history\s*\}\s*,\s*dictationServiceController\s*,?\s*\)"""),
        Rule("CONTROLLER_BUILT_FROM_ANDROID_ADAPTERS", app, """\bDictationServiceController\s*\(\s*AndroidMicPermission\s*\(\s*applicationContext\s*\)\s*,\s*AndroidServiceLauncher\s*\(\s*applicationContext\s*\)\s*,?\s*\)"""),
        Rule("CONTROLLER_IS_BUILT_LAZILY", app, """\bval\s+dictationServiceController\s*:\s*DictationServiceController\s+by\s+lazy\s*\{"""),
        Rule("PURGE_SCOPE_FROM_FACTORY", app, """\bval\s+purgeScope\s*=\s*createPurgeScope\s*\(\s*\)(?!\s*[.(])"""),
        Rule("PURGE_SCHEDULED_ON_PURGE_SCOPE", app, """\bscheduleAppStartPurge\s*\(\s*purgeScope\s*,"""),
        Rule("PURGE_CALLS_PURGE_EXPIRED", app, """\bpurge\s*=\s*\{\s*history\s*\.\s*purgeExpired\s*\(\s*\)\s*\}"""),
        Rule("ONCREATE_CALLS_SUPER_THEN_PURGE", app, """\boverride\s+fun\s+onCreate\s*\(\s*\)\s*\{\s*super\s*\.\s*onCreate\s*\(\s*\)\s*scheduleAppStartPurge\s*\("""),
        Rule("NO_OWN_SCOPE_IN_APP", app, """\bDispatchers\b|\bCoroutineScope\s*\(""", times = 0),
        Rule("SWITCH_IS_OVER_THE_CONTROLLER_AND_THE_OFF_FILE", app, """\bval\s+armedSwitch\s*:\s*ArmedSwitch\s+by\s+lazy\s*\{\s*ArmedSwitch\s*\(\s*dictationServiceController\s*,\s*FileOffStore\s*\(\s*File\s*\(\s*filesDir\s*,\s*""\s*\)\s*\)\s*\)\s*\}"""),
        Rule("TILE_HOST_IS_LAZY_OVER_THE_ROOT", app, """\bval\s+tileHost\s*:\s*TileHost\s+by\s+lazy\s*\{\s*TileHost\s*\(\s*applicationContext\s*,\s*compositionRoot\s*,\s*armedSwitch\s*::\s*isOn\s*,?\s*\)\s*\}"""),
        Rule("OBSERVER_IS_SET_ONCE", app, """\bsetStateObserver\b"""),
        Rule("OBSERVER_IS_SET_IN_ONCREATE_AFTER_THE_PURGE", app, """\boverride\s+fun\s+onCreate\s*\(\s*\)\s*\{\s*super\s*\.\s*onCreate\s*\(\s*\)\s*scheduleAppStartPurge\s*\([\s\S]*?\)\s*dictationServiceController\s*\.\s*setStateObserver\s*\{"""),
        Rule("OBSERVER_FEEDS_THE_SWITCH", app, """\bsetStateObserver\s*\{\s*armed\s*,\s*reason\s*->\s*armedSwitch\s*\.\s*onStateChanged\s*\(\s*armed\s*,\s*reason\s*\)"""),
        Rule("OBSERVER_THEN_FEEDS_THE_TILE", app, """\barmedSwitch\s*\.\s*onStateChanged\s*\(\s*armed\s*,\s*reason\s*\)\s*tileHost\s*\.\s*onArmedChanged\s*\(\s*armed\s*\)\s*\}"""),
        Rule("ONSTART_SWITCHES_ON_FOR_THE_ICON_AND_ARMS_AT_START_OTHERWISE", activity, """\boverride\s+fun\s+onStart\s*\(\s*\)\s*\{\s*super\s*\.\s*onStart\s*\(\s*\)\s*val\s+app\s*=\s*applicationContext\s+as\s+BreakerApp\s+val\s+launch\s*=\s*intent\s+if\s*\(\s*isIconLaunch\s*\(\s*launch\s*\?\s*\.\s*action\s*,\s*launch\s*\?\s*\.\s*categories\s*\.\s*orEmpty\s*\(\s*\)\s*,\s*launch\s*\?\s*\.\s*getStringExtra\s*\(\s*NotificationRoute\s*\.\s*EXTRA_ROUTE\s*\)\s*\)\s*\)\s*\{\s*app\s*\.\s*armedSwitch\s*\.\s*switchOn\s*\(\s*\)\s*\}\s*else\s*\{\s*app\s*\.\s*armedSwitch\s*\.\s*armAtStart\s*\(\s*\)\s*\}\s*app\s*\.\s*tileHost\s*\.\s*onLauncherVisible\s*\(\s*\)\s*\}"""),
        Rule("ACTIVITY_REACHES_THE_TILE_HOST_ONLY_TO_DOWNLOAD_AND_TO_SHOW_AGAIN", activity, """\btileHost\s*\.\s*(?!requestDownload\b|onLauncherVisible\b)\w+""", times = 0),
        Rule("ARM_AT_START_IS_CALLED_ONCE", activity, """\barmAtStart\s*\("""),
        Rule("SWITCH_IS_REACHED_IN_THE_TWO_BRANCHES_ONLY", activity, """\barmedSwitch\b""", times = 2),
        Rule("SWITCH_ON_IS_CALLED_ONCE", activity, """\bswitchOn\s*\("""),
        Rule("NO_UNCONDITIONAL_ARM_IN_THE_ACTIVITY", activity, """\barm\s*\(""", times = 0),
        Rule("CONTROLLER_IS_NOT_REACHED_BY_THE_ACTIVITY", activity, """\bdictationServiceController\b""", times = 0),
        Rule("NEVER_ARMED_PERMISSION_IS_OFF", root, """\bpermission\s*=\s*MicPermission\s*\{\s*false\s*\}"""),
        Rule("NEVER_ARMED_LAUNCH_REFUSES", root, """\boverride\s+fun\s+launch\s*\(\s*\)\s*:\s*LaunchResult\s*=\s*LaunchResult\s*\.\s*Refused\b"""),
        Rule("TWO_ARGUMENT_CONSTRUCTOR_NEVER_ARMED", root, """\bthis\s*\(\s*filesDir\s*,\s*historyStore\s*,\s*neverArmedController\s*\(\s*\)\s*\)"""),
        Rule("SUPPLIER_CONSTRUCTOR_WRAPS_LAZY", root, """\bthis\s*\(\s*filesDir\s*,\s*LazyHistoryStore\s*\(\s*history\s*\)\s*,\s*serviceController\s*\)"""),
        Rule("SERVER_ADDRESS_IS_THE_SAVED_ONE", root, """\bserverUrlProvider\s*=\s*\{\s*settingsStore\s*\.\s*load\s*\(\s*\)\s*\.\s*serverUrl\s*\}"""),
        Rule("COMPONENT_GETS_ROOT_HISTORY", root, """\bhistory\s*=\s*historyStore(?=\s*,)"""),
        Rule("COMPONENT_GETS_SERVICE_CONTROLLER", root, """\bcontroller\s*=\s*serviceController(?=\s*[,)])"""),
    )

    private val onStartArm = "app.armedSwitch.armAtStart()"
    private val stopSwitch = "override fun onStop() {\n        (applicationContext as BreakerApp).armedSwitch.switchOff()\n    }\n\n    override fun onStart() {"

    private val firing: List<Sample> = listOf(
        Sample("HISTORY_HANDED_AS_SUPPLIER", app, "{ history }", "history"),
        Sample("HISTORY_HANDED_AS_SUPPLIER", app, "{ history }", "{ history.also { } }"),
        Sample("HISTORY_HANDED_AS_SUPPLIER", app, "{ history }", "{ other }"),
        Sample("HISTORY_HANDED_AS_SUPPLIER", app, "{ history }, dictationServiceController)", "{ history })"),
        Sample("HISTORY_HANDED_AS_SUPPLIER", app, "BreakerCompositionRoot(filesDir,", "BreakerCompositionRoot(cacheDir,"),
        Sample("CONTROLLER_BUILT_FROM_ANDROID_ADAPTERS", app, "AndroidMicPermission(applicationContext)", "MicPermission { true }"),
        Sample("CONTROLLER_BUILT_FROM_ANDROID_ADAPTERS", app, "AndroidServiceLauncher(applicationContext)", "NoLauncher()"),
        Sample("CONTROLLER_BUILT_FROM_ANDROID_ADAPTERS", app, "AndroidMicPermission(applicationContext)", "AndroidMicPermission(applicationContext).also { }"),
        Sample(
            "CONTROLLER_BUILT_FROM_ANDROID_ADAPTERS",
            app,
            "AndroidMicPermission(applicationContext),\n            AndroidServiceLauncher(applicationContext),",
            "AndroidServiceLauncher(applicationContext),\n            AndroidMicPermission(applicationContext),",
        ),
        Sample("CONTROLLER_IS_BUILT_LAZILY", app, "DictationServiceController by lazy {", "DictationServiceController = run {"),
        Sample("CONTROLLER_IS_BUILT_LAZILY", app, "val dictationServiceController:", "val serviceController:"),
        Sample("PURGE_SCOPE_FROM_FACTORY", app, "createPurgeScope()", "CoroutineScope(Dispatchers.Default)"),
        Sample("PURGE_SCOPE_FROM_FACTORY", app, "createPurgeScope()", "createPurgeScope().also { }"),
        Sample("PURGE_SCOPE_FROM_FACTORY", app, "private val purgeScope", "private val scope"),
        Sample("PURGE_SCHEDULED_ON_PURGE_SCOPE", app, "purgeScope,", "otherScope,"),
        Sample("PURGE_SCHEDULED_ON_PURGE_SCOPE", app, "purgeScope,", "CoroutineScope(Dispatchers.Default),"),
        Sample("PURGE_CALLS_PURGE_EXPIRED", app, "history.purgeExpired()", "Unit"),
        Sample("PURGE_CALLS_PURGE_EXPIRED", app, "history.purgeExpired()", "history.purgeExpired().also { }"),
        Sample("ONCREATE_CALLS_SUPER_THEN_PURGE", app, "super.onCreate()", ""),
        Sample("ONCREATE_CALLS_SUPER_THEN_PURGE", app, "super.onCreate()", "super.onCreate()\n        history.hashCode()"),
        Sample("ONCREATE_CALLS_SUPER_THEN_PURGE", app, "scheduleAppStartPurge(", "launchPurge("),
        Sample("ONCREATE_CALLS_SUPER_THEN_PURGE", app, "override fun onCreate()", "fun onCreateLater()"),
        Sample("NO_OWN_SCOPE_IN_APP", app, "createPurgeScope()", "CoroutineScope(Dispatchers.Default)"),
        Sample("NO_OWN_SCOPE_IN_APP", app, "override fun onCreate() {", "private val extra = Dispatchers.IO\n\n    override fun onCreate() {"),
        Sample("SWITCH_IS_OVER_THE_CONTROLLER_AND_THE_OFF_FILE", app, "ArmedSwitch(dictationServiceController,", "ArmedSwitch(neverArmed,"),
        Sample("SWITCH_IS_OVER_THE_CONTROLLER_AND_THE_OFF_FILE", app, "File(filesDir, \"dictation-off\")", "File(cacheDir, \"dictation-off\")"),
        Sample("SWITCH_IS_OVER_THE_CONTROLLER_AND_THE_OFF_FILE", app, "FileOffStore(", "MemoryOffStore("),
        Sample("TILE_HOST_IS_LAZY_OVER_THE_ROOT", app, "TileHost(applicationContext, compositionRoot, armedSwitch::isOn)", "TileHost(this, compositionRoot, armedSwitch::isOn)"),
        Sample("TILE_HOST_IS_LAZY_OVER_THE_ROOT", app, "armedSwitch::isOn", "armedSwitch::switchOn"),
        Sample("TILE_HOST_IS_LAZY_OVER_THE_ROOT", app, ", armedSwitch::isOn)", ", { true })"),
        Sample("TILE_HOST_IS_LAZY_OVER_THE_ROOT", app, "compositionRoot, armedSwitch::isOn)", "compositionRoot)"),
        Sample("TILE_HOST_IS_LAZY_OVER_THE_ROOT", app, "TileHost by lazy {", "TileHost = run {"),
        Sample("OBSERVER_IS_SET_ONCE", app, "dictationServiceController.setStateObserver {", "dictationServiceController.setEndedListener {"),
        Sample("OBSERVER_IS_SET_ONCE", app, "scheduleAppStartPurge(\n            purgeScope,", "dictationServiceController.setStateObserver { _, _ -> }\n        scheduleAppStartPurge(\n            purgeScope,"),
        Sample("OBSERVER_IS_SET_IN_ONCREATE_AFTER_THE_PURGE", app, "dictationServiceController.setStateObserver {", "compositionRoot.setStateObserver {"),
        Sample("OBSERVER_IS_SET_IN_ONCREATE_AFTER_THE_PURGE", app, "scheduleAppStartPurge(", "launchPurge("),
        Sample("OBSERVER_IS_SET_IN_ONCREATE_AFTER_THE_PURGE", app, "super.onCreate()", "super.onCreate()\n        history.hashCode()"),
        Sample("OBSERVER_FEEDS_THE_SWITCH", app, "armedSwitch.onStateChanged(armed, reason)", "armedSwitch.onStateChanged(reason, armed)"),
        Sample("OBSERVER_FEEDS_THE_SWITCH", app, "armedSwitch.onStateChanged(armed, reason)", "armedSwitch.switchOff()"),
        Sample("OBSERVER_FEEDS_THE_SWITCH", app, "{ armed, reason ->", "{ armed, _ ->"),
        Sample("OBSERVER_THEN_FEEDS_THE_TILE", app, "tileHost.onArmedChanged(armed)", "tileHost.onArmedChanged(true)"),
        Sample("OBSERVER_THEN_FEEDS_THE_TILE", app, "            tileHost.onArmedChanged(armed)\n", ""),
        Sample("OBSERVER_THEN_FEEDS_THE_TILE", app, "armedSwitch.onStateChanged(armed, reason)\n            tileHost.onArmedChanged(armed)", "tileHost.onArmedChanged(armed)\n            armedSwitch.onStateChanged(armed, reason)"),
        Sample("ONSTART_SWITCHES_ON_FOR_THE_ICON_AND_ARMS_AT_START_OTHERWISE", activity, onStartArm, ""),
        Sample("ONSTART_SWITCHES_ON_FOR_THE_ICON_AND_ARMS_AT_START_OTHERWISE", activity, "super.onStart()", ""),
        Sample("ONSTART_SWITCHES_ON_FOR_THE_ICON_AND_ARMS_AT_START_OTHERWISE", activity, "super.onStart()\n        val app = applicationContext as BreakerApp\n", "val app = applicationContext as BreakerApp\n        super.onStart()\n"),
        Sample("ONSTART_SWITCHES_ON_FOR_THE_ICON_AND_ARMS_AT_START_OTHERWISE", activity, "app.tileHost.onLauncherVisible()", ""),
        Sample("ONSTART_SWITCHES_ON_FOR_THE_ICON_AND_ARMS_AT_START_OTHERWISE", activity, "app.tileHost.onLauncherVisible()", "app.tileHost.onArmedChanged(true)"),
        Sample("ONSTART_SWITCHES_ON_FOR_THE_ICON_AND_ARMS_AT_START_OTHERWISE", activity, "val launch = intent\n", "val launch = intent\n        app.tileHost.onLauncherVisible()\n"),
        Sample("ONSTART_SWITCHES_ON_FOR_THE_ICON_AND_ARMS_AT_START_OTHERWISE", activity, "app.tileHost.onLauncherVisible()", "if (app.armedSwitch.isOn()) app.tileHost.onLauncherVisible()"),
        Sample("ONSTART_SWITCHES_ON_FOR_THE_ICON_AND_ARMS_AT_START_OTHERWISE", activity, "override fun onStart()", "override fun onResume()"),
        Sample("ONSTART_SWITCHES_ON_FOR_THE_ICON_AND_ARMS_AT_START_OTHERWISE", activity, "armedSwitch.armAtStart()", "armedSwitch.switchOn()"),
        Sample("ONSTART_SWITCHES_ON_FOR_THE_ICON_AND_ARMS_AT_START_OTHERWISE", activity, "app.armedSwitch.switchOn()", "app.armedSwitch.armAtStart()"),
        Sample("ONSTART_SWITCHES_ON_FOR_THE_ICON_AND_ARMS_AT_START_OTHERWISE", activity, "if (isIconLaunch(", "if (!isIconLaunch("),
        Sample("ONSTART_SWITCHES_ON_FOR_THE_ICON_AND_ARMS_AT_START_OTHERWISE", activity, "launch?.getStringExtra(NotificationRoute.EXTRA_ROUTE)", "null"),
        Sample("ONSTART_SWITCHES_ON_FOR_THE_ICON_AND_ARMS_AT_START_OTHERWISE", activity, "launch?.action", "launch?.type"),
        Sample("ONSTART_SWITCHES_ON_FOR_THE_ICON_AND_ARMS_AT_START_OTHERWISE", activity, "} else {", "}\n        run {"),
        Sample("ONSTART_SWITCHES_ON_FOR_THE_ICON_AND_ARMS_AT_START_OTHERWISE", activity, "val launch = intent", "val launch = intent.also { }"),
        Sample("SWITCH_ON_IS_CALLED_ONCE", activity, "super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\n        (applicationContext as BreakerApp).armedSwitch.switchOn()"),
        Sample("SWITCH_ON_IS_CALLED_ONCE", activity, "app.armedSwitch.armAtStart()", "app.armedSwitch.switchOn()"),
        Sample("SWITCH_ON_IS_CALLED_ONCE", activity, "app.armedSwitch.switchOn()", "app.armedSwitch"),
        Sample("ONSTART_SWITCHES_ON_FOR_THE_ICON_AND_ARMS_AT_START_OTHERWISE", activity, "armedSwitch.armAtStart()", "armedSwitch.armAtStart().also { }"),
        Sample("ONSTART_SWITCHES_ON_FOR_THE_ICON_AND_ARMS_AT_START_OTHERWISE", activity, "armedSwitch.armAtStart()", "dictationServiceController.arm()"),
        Sample("ARM_AT_START_IS_CALLED_ONCE", activity, "super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\n        $onStartArm"),
        Sample("ARM_AT_START_IS_CALLED_ONCE", activity, "override fun onStart() {", "override fun onResume() {\n        $onStartArm\n    }\n\n    override fun onStart() {"),
        Sample("ARM_AT_START_IS_CALLED_ONCE", activity, "armedSwitch.armAtStart()", "armedSwitch"),
        Sample("ACTIVITY_REACHES_THE_TILE_HOST_ONLY_TO_DOWNLOAD_AND_TO_SHOW_AGAIN", activity, "app.tileHost.onLauncherVisible()", "app.tileHost.onArmedChanged(true)"),
        Sample("ACTIVITY_REACHES_THE_TILE_HOST_ONLY_TO_DOWNLOAD_AND_TO_SHOW_AGAIN", activity, "super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\n        (applicationContext as BreakerApp).tileHost.onArmedChanged(true)"),
        Sample("ACTIVITY_REACHES_THE_TILE_HOST_ONLY_TO_DOWNLOAD_AND_TO_SHOW_AGAIN", activity, "app.tileHost.requestDownload()", "app.tileHost.openLauncher(null)"),
        Sample("SWITCH_IS_REACHED_IN_THE_TWO_BRANCHES_ONLY", activity, "override fun onStart() {", stopSwitch),
        Sample("SWITCH_IS_REACHED_IN_THE_TWO_BRANCHES_ONLY", activity, "super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\n        $onStartArm.hashCode()"),
        Sample("NO_UNCONDITIONAL_ARM_IN_THE_ACTIVITY", activity, "super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\n        arm()"),
        Sample("NO_UNCONDITIONAL_ARM_IN_THE_ACTIVITY", activity, "armedSwitch.armAtStart()", "armedSwitch.armAtStart()\n        controller.arm()"),
        Sample("CONTROLLER_IS_NOT_REACHED_BY_THE_ACTIVITY", activity, "override fun onStart() {", "override fun onStop() {\n        (applicationContext as BreakerApp).dictationServiceController.disarm(null)\n    }\n\n    override fun onStart() {"),
        Sample("CONTROLLER_IS_NOT_REACHED_BY_THE_ACTIVITY", activity, "super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\n        (applicationContext as BreakerApp).dictationServiceController.isArmed"),
        Sample("NEVER_ARMED_PERMISSION_IS_OFF", root, "MicPermission { false }", "MicPermission { true }"),
        Sample("NEVER_ARMED_PERMISSION_IS_OFF", root, "MicPermission { false }", "MicPermission { granted }"),
        Sample("NEVER_ARMED_PERMISSION_IS_OFF", root, "permission = MicPermission { false },", ""),
        Sample("NEVER_ARMED_LAUNCH_REFUSES", root, "LaunchResult = LaunchResult.Refused", "LaunchResult = LaunchResult.Launched"),
        Sample("NEVER_ARMED_LAUNCH_REFUSES", root, "LaunchResult = LaunchResult.Refused", "LaunchResult = launched()"),
        Sample("TWO_ARGUMENT_CONSTRUCTOR_NEVER_ARMED", root, "neverArmedController())", "armedController())"),
        Sample("TWO_ARGUMENT_CONSTRUCTOR_NEVER_ARMED", root, "this(filesDir, historyStore, neverArmedController())", "this(filesDir, historyStore, serviceFromElsewhere)"),
        Sample("SUPPLIER_CONSTRUCTOR_WRAPS_LAZY", root, "LazyHistoryStore(history)", "history()"),
        Sample("SUPPLIER_CONSTRUCTOR_WRAPS_LAZY", root, "LazyHistoryStore(history)", "LazyHistoryStore(history())"),
        Sample("SUPPLIER_CONSTRUCTOR_WRAPS_LAZY", root, "LazyHistoryStore(history), serviceController)", "LazyHistoryStore(history), neverArmedController())"),
        Sample("SERVER_ADDRESS_IS_THE_SAVED_ONE", root, "{ settingsStore.load().serverUrl }", "{ \"https://example.invalid\" }"),
        Sample("SERVER_ADDRESS_IS_THE_SAVED_ONE", root, "{ settingsStore.load().serverUrl }", "{ settingsStore.load().modelSize }"),
        Sample("SERVER_ADDRESS_IS_THE_SAVED_ONE", root, "serverUrlProvider = {", "serverUrlProvider = fixed"),
        Sample("COMPONENT_GETS_ROOT_HISTORY", root, "history = historyStore,", "history = RecordingHistoryStore(),"),
        Sample("COMPONENT_GETS_ROOT_HISTORY", root, "history = historyStore,", "history = LazyHistoryStore { historyStore },"),
        Sample("COMPONENT_GETS_ROOT_HISTORY", root, "history = historyStore,", "history = otherStore,"),
        Sample("COMPONENT_GETS_SERVICE_CONTROLLER", root, "controller = serviceController,", "controller = neverArmedController(),"),
        Sample("COMPONENT_GETS_SERVICE_CONTROLLER", root, "controller = serviceController,", "controller = other,"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet(app, "{ history }", "{   history   }"),
        Quiet(app, "createPurgeScope()", "createPurgeScope( ) // Dispatchers CoroutineScope( history }"),
        Quiet(app, "super.onCreate()", "super . onCreate ( )"),
        Quiet(app, "AndroidServiceLauncher(applicationContext),", "AndroidServiceLauncher(applicationContext), /* arm( throw */"),
        Quiet(app, "purge = { history.purgeExpired() },", "purge = {\n                history.purgeExpired()\n            },"),
        Quiet(app, "\"start-up purge failed\"", "\"start-up purge failed Dispatchers CoroutineScope(\""),
        Quiet(activity, "super.onStart()", "super . onStart ( )"),
        Quiet(activity, "app.tileHost.onLauncherVisible()", "app\n            .tileHost\n            .onLauncherVisible()"),
        Quiet(activity, "app.tileHost.onLauncherVisible()", "app.tileHost.onLauncherVisible() // tileHost.onArmedChanged(true)"),
        Quiet(app, "armedSwitch::isOn", "armedSwitch :: isOn /* armedSwitch::switchOn */"),
        Quiet(activity, "armedSwitch.armAtStart()", "armedSwitch\n            .armAtStart()"),
        Quiet(activity, "app.armedSwitch.switchOn()", "app.armedSwitch\n                .switchOn()"),
        Quiet(activity, "launch?.action,", "launch ?. action ,"),
        Quiet(activity, "super.onStart()", "super.onStart() // switchOn( armedSwitch isIconLaunch("),
        Quiet(activity, "super.onStart()", "super.onStart() // arm( dictationServiceController armedSwitch armAtStart("),
        Quiet(activity, "override fun onStart() {", "override fun onStart() { /* arm( dictationServiceController */"),
        Quiet(app, "armedSwitch.onStateChanged(armed, reason)", "armedSwitch.onStateChanged( armed, reason ) // setStateObserver"),
        Quiet(app, "tileHost.onArmedChanged(armed)", "tileHost\n                .onArmedChanged(armed)"),
        Quiet(app, "\"dictation-off\"", "\"dictation-off setStateObserver tileHost\""),
        Quiet(root, "MicPermission { false }", "MicPermission {\n            false\n        }"),
        Quiet(root, "history = historyStore,", "history =\n            historyStore, // the store"),
        Quiet(root, "controller = serviceController,", "controller = serviceController, // the controller"),
        Quiet(root, "{ settingsStore.load().serverUrl }", "{\n                    settingsStore.load().serverUrl\n                }"),
        Quiet(root, "LazyHistoryStore(history)", "LazyHistoryStore( history )"),
        Quiet(root, "\"credential-ref\"", "\"credential-ref permission = MicPermission { true } controller = other,\""),
        Quiet(root, "LaunchResult = LaunchResult.Refused", "LaunchResult =\n                LaunchResult.Refused"),
    )

    /** [old] replaced by [new] at its first place; fails by name when it is not there. */
    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a gate sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun codeOf(file: String, old: String? = null, new: String = ""): String {
        val text = AppSourceFiles.mainFile(file)
        return AppSourceFiles.strip(if (old == null) text else edit(text, old, new)).code
    }

    @Test
    fun `every rule holds on the real files`() {
        for (file in listOf(app, activity, root)) {
            assertTrue("app: $file was read as empty", codeOf(file).isNotBlank())
        }
        for (rule in rules) {
            assertTrue("app: ${rule.file} breaks rule ${rule.name}", rule.holds(codeOf(rule.file)))
        }
    }

    @Test
    fun `the firing samples cover each rule at least twice and use its own file`() {
        val names = rules.map { it.name }
        assertEquals("app: a rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val samples = firing.filter { it.rule == rule.name }
            assertTrue("app: rule ${rule.name} needs at least two firing samples, has ${samples.size}", samples.size >= 2)
            for (sample in samples) {
                assertEquals("app: a sample of rule ${rule.name} edits another file", rule.file, sample.file)
            }
        }
    }

    @Test
    fun `every rule fires on each of its edited samples`() {
        val byName = rules.associateBy { it.name }
        for (sample in firing) {
            assertFalse(
                "app: rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'",
                byName.getValue(sample.rule).holds(codeOf(sample.file, sample.old, sample.new)),
            )
        }
    }

    @Test
    fun `no rule fires on a harmless edit`() {
        for (sample in quiet) {
            for (rule in rules.filter { it.file == sample.file }) {
                assertTrue(
                    "app: rule ${rule.name} must stay quiet on the harmless edit '${sample.old}' => '${sample.new}'",
                    rule.holds(codeOf(sample.file, sample.old, sample.new)),
                )
            }
        }
    }

    @Test
    fun `the off file is the one named dictation-off and a rename is seen`() {
        val named = AppSourceFiles.strip(AppSourceFiles.mainFile(app)).literals
        assertTrue("app: BreakerApp.kt does not name the off file dictation-off, its texts are $named", "dictation-off" in named)
        val renamed = AppSourceFiles.strip(edit(AppSourceFiles.mainFile(app), "\"dictation-off\"", "\"switch-off\"")).literals
        assertFalse("app: a renamed off file was still read as dictation-off", "dictation-off" in renamed)
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) {
            edit("val a = 1", "no such text", "x")
        }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text"))
    }
}
