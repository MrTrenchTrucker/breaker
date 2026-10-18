package dev.breaker.dictation.ui.gate

/** The file of the first-run entry, which is offered as a public declaration. */
internal const val FIRST_RUN_FILE = "FirstRunEntry.kt"

/** The settled shape of the first-run entry, with its parameter types in full. */
internal const val SETTLED_FIRST_RUN = "fun createFirstRunView(context: android.content.Context, " +
    "accessibilityServiceComponent: String, modelReady: () -> Boolean, downloadLine: () -> String?, " +
    "modelDownloadMb: () -> Int, onDownloadModel: () -> Unit, " +
    "settings: dev.breaker.dictation.core.port.SettingsStore, onFinished: () -> Unit): android.view.View"

/** The file of the first-run entry, which hands its view to the app and may name the framework. */
internal const val FIRST_RUN_ENTRY_FILE = "FirstRunEntry.kt"

/** The compiled form of the first-run entry file. */
internal const val FIRST_RUN_ENTRY_CLASS = "FirstRunEntryKt.class"
